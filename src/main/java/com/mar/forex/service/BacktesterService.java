package com.mar.forex.service;
import static com.mar.forex.util.BacktestUtils.*;
import static java.util.Map.entry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.tribuo.Example;
import org.tribuo.Model;
import org.tribuo.classification.Label;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mar.forex.config.AppProperties;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.domain.model.PaperTrade;
import com.mar.forex.domain.model.TrainResult;
import com.mar.forex.infrastructure.broker.PaperTradeEngine;
import com.mar.forex.infrastructure.messaging.SseHub;
import com.mar.forex.util.Indicators;
import com.mar.forex.util.TribuoUtil;

@Slf4j
@Component
@RequiredArgsConstructor
public class BacktesterService implements ApplicationRunner {
    private final AppProperties props;
    private final MLService mlService;
    private final PaperTradeEngine paper;
    private final SseHub sseHub;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Override
    public void run(ApplicationArguments args) {
    }

    /**
     * Backtests using an already-fetched candle list and associates progress/results with a UI job id. Currently logs
     * progress; hook your SSE/event bus inside if desired.
     */
    public void runForUI(List<Candle> candles, String jobId) throws Exception {
        // --- Calibration bins (taken-trade pWin vs. a realized outcome) ---
        double[] binEdges = new double[]{0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 1.01};
        int B = binEdges.length - 1;
        int[] binCount = new int[B];
        int[] binWins = new int[B];
        double[] binSumR = new double[B];
        java.util.LinkedList<Double> pendingPWin = new java.util.LinkedList<>(); // FIFO since maxOpenPerInstrument=1
        int lastClosedCount = 0;
        // Use configured instrument/granularity for metadata
        String instrument = props.getTrading().getInstrument();
        String granularity = props.getTrading().getGranularity();
        Path modelPath = Path.of("models/model.zip");

        double[][] calibTable = loadCalibration(java.nio.file.Path.of("models/calibration.trade.csv"));
        String calibName = "calibration.trade.csv";
        if (calibTable == null) {
            calibTable = loadCalibration(java.nio.file.Path.of("models/calibration.csv"));
            calibName = (calibTable == null) ? null : "calibration.csv";
        }
        if (calibTable == null) log.info("Calibration table not found; using raw probabilities.");
        else log.info("Loaded calibration table ({}) with {} bins.", calibName, calibTable.length);

        // ---- Log out the full config being applied (for UI transparency/debugging) ----
        double execThreshCfg = 0.0;
        try {
            execThreshCfg = props.getExecution().getSignalThreshold();
        } catch (Throwable ignored) {
        }
        if (!Double.isFinite(execThreshCfg)) execThreshCfg = 0.0;
        double basePwCfg = Math.min(1.0, (1.0 / (1.0 + props.getPaper().getRr())) + props.getFilter().getEvMargin());
        double effPwCfg = Math.max(basePwCfg, execThreshCfg);
        logJ("CONFIG", Map.ofEntries(
            entry("instrument", instrument),
            entry("granularity", granularity),
            entry("fast", props.getTrading().getFastSma()),
            entry("slow", props.getTrading().getSlowSma()),
            entry("atrP", props.getPaper().getAtrPeriod()),
            entry("rr", props.getPaper().getRr()),
            entry("mode", String.valueOf(props.getPaper().getMode())),
            entry("leverage", props.getPaper().getLeverage()),
            entry("startBalance", props.getPaper().getStartBalance()),
            entry("stopAtrMult", props.getPaper().getStopAtrMulti()),
            entry("evMargin", r2(props.getFilter().getEvMargin())),
            entry("evMarginR", r2(props.getFilter().getEvMarginR())),
            entry("volWin", props.getFilter().getAtrWindow()),
            entry("volPct", props.getFilter().getAtrPercentile()),
            entry("sessionStr", props.getFilter().getSession()),
            entry("rsiLong", props.getFilter().getRsiLong()),
            entry("rsiShort", props.getFilter().getRsiShort()),
            entry("onePerDay", props.getFilter().isOnePerDay()),
            entry("execThresh", r2(execThreshCfg)),
            entry("basePw", r2(basePwCfg)),
            entry("effPw", r2(effPwCfg))
        ));

        log.info("Backtest(UI) | {} {} | candles={} rr={} mode={} jobId={}",
            instrument, granularity, candles.size(), props.getPaper().getRr(), props.getPaper().getMode(), jobId);

        double leverage = props.getPaper().getLeverage();
        double startBalance = props.getPaper().getStartBalance();
        double equityUSD = startBalance;
        java.util.LinkedList<Double> pendingRiskUSD = new java.util.LinkedList<>();
        List<Double> equityCurveUSD = new ArrayList<>();
        double stopAtrMult = props.getPaper().getStopAtrMulti();
        try {
            Map<String, Object> lever = new HashMap<>();
            lever.put("leverage", leverage);
            lever.put("startBalance", startBalance);
            sseHub.emit(jobId, "progress", objectMapper.writeValueAsString(lever));
        } catch (Exception ignored) {
        }
        try {
            Map<String, Object> startPayload = new HashMap<>();
            startPayload.put("phase", "start");
            startPayload.put("instrument", props.getTrading().getInstrument());
            startPayload.put("granularity", props.getTrading().getGranularity());
            startPayload.put("candles", candles.size());
            startPayload.put("rr", props.getPaper().getRr());
            startPayload.put("mode", String.valueOf(props.getPaper().getMode()));
            sseHub.emit(jobId, "progress", objectMapper.writeValueAsString(startPayload));
        } catch (Exception ignored) {
        }

        if (candles.size() < 300) {
            log.warn("Too few candles ({}). Aborting backtest(UI) for jobId={}", candles.size(), jobId);
            return;
        }

        int n = candles.size();
        double[] close = new double[n], high = new double[n], low = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = candles.get(i).close;
            high[i] = candles.get(i).high;
            low[i] = candles.get(i).low;
        }

        int fast = props.getTrading().getFastSma();
        int slow = props.getTrading().getSlowSma();
        int atrP = props.getPaper().getAtrPeriod();

        double[] atr = Indicators.atr(high, low, close, atrP);

        double evMargin = props.getFilter().getEvMargin();
        int volWin = props.getFilter().getAtrWindow();
        double volPct = props.getFilter().getAtrPercentile();
        String sessionStr = props.getFilter().getSession();
        int[] sess = parseSessionHoursUtc(sessionStr);
        int sessionStart = sess[0], sessionEnd = sess[1];
        int rsiLong = props.getFilter().getRsiLong();
        int rsiShort = props.getFilter().getRsiShort();
        boolean onePerDay = props.getFilter().isOnePerDay();
        // Execution probability floor (independent of EV gate)
        double execThresh = 0.0;
        try {
            execThresh = props.getExecution().getSignalThreshold();
        } catch (Throwable ignored) {
        }
        if (!Double.isFinite(execThresh)) execThresh = 0.0;
        // Compute a base EV-derived threshold using current RR
        double rrForLog = props.getPaper().getRr();
        double basePw = Math.min(1.0, (1.0 / (1.0 + rrForLog)) + evMargin);
        // The Effective threshold is the stricter of the two
        double effPw = Math.max(basePw, execThresh);

        double evMarginR = props.getFilter().getEvMarginR();
        logJ("FILTERS", Map.ofEntries(
            entry("evMargin", r2(evMargin)),
            entry("evMarginR", r2(evMarginR)),
            entry("atrWindow", volWin),
            entry("atrPercentile", volPct),
            entry("session", sessionStr),
            entry("rsiLong", rsiLong),
            entry("rsiShort", rsiShort),
            entry("onePerDay", onePerDay),
            entry("signalThr", r2(execThresh)),
            entry("basePw", r2(basePw)),
            entry("effThr", r2(effPw))
        ));

        // Build effective filters used during this run
        Map<String, Object> effectiveFilters = Map.ofEntries(
            entry("evMargin", r2(evMargin)),
            entry("evMarginR", r2(evMarginR)),
            entry("atrWindow", volWin),
            entry("atrPercentile", volPct),
            entry("session", sessionStr),
            entry("rsiLong", rsiLong),
            entry("rsiShort", rsiShort),
            entry("onePerDay", onePerDay),
            entry("signalThr", r2(execThresh)),
            entry("basePw", r2(basePw)),
            entry("effThr", r2(effPw))
        );

        String maType = props.getTrading().getMaType();
        // Precompute arrays for close, high, low, maFast, maSlow, rsi, atr for use in the main loop
        double[] preClose = candles.stream().mapToDouble(c -> c.close).toArray();
        double[] preHigh  = candles.stream().mapToDouble(c -> c.high).toArray();
        double[] preLow   = candles.stream().mapToDouble(c -> c.low).toArray();

        double[] preMaFast = ("EMA".equalsIgnoreCase(maType) || "HYBRID".equalsIgnoreCase(maType))
            ? Indicators.ema(preClose, fast)
            : Indicators.sma(preClose, fast);

        double[] preMaSlow = ("EMA".equalsIgnoreCase(maType))
            ? Indicators.ema(preClose, slow)
            : Indicators.sma(preClose, slow);

        double[] preRsi = Indicators.rsi(preClose, 14);
        double[] preAtr = Indicators.atr(preHigh, preLow, preClose, atrP);

        String featSig = featureSignature(maType, fast, slow, atrP);
        Path metaPath = Path.of("models/model.meta.txt");
        // Load or train a model with feature signature check
        Model<Label> model = null;
        boolean needTrain = true;
        String existingSig = readMeta(metaPath);
        if (Files.exists(modelPath) && existingSig != null && existingSig.equals(featSig)) {
            model = mlService.load(modelPath);
            log.info("Loaded model: {} (sig OK, UI path)", modelPath.toAbsolutePath());
            needTrain = false;
        } else if (Files.exists(modelPath)) {
            log.info("Model present but signature changed (UI path). Old='{}' New='{}'. Retraining...", existingSig, featSig);
        } else {
            log.info("Model missing — training (UI path)...");
        }
        if (needTrain) {
            TrainResult tr = mlService.trainClassifier(candles, instrument, fast, slow, atrP);
            model = tr.getModel();
            Files.createDirectories(modelPath.getParent());
            mlService.save(model, modelPath);
            writeMeta(metaPath, featSig);
            log.info("Trained & saved: {} (sig written, UI path)", modelPath.toAbsolutePath());
        }

        // ---- Probability scan (pre-loop) ----
        int warmup = Math.max(Math.max(fast, slow), atrP) + 1;
        ScanStats scan = doProbabilityScan(model, candles, warmup, n, fast, slow, atrP, props.getTrading().getMaType(), calibTable);
        logJ("SCAN", Map.ofEntries(
            entry("count", scan.count),
            entry("calibrated", calibTable != null),
            entry("max", r2(scan.maxP)),
            entry("p95", r2(scan.p95)),
            entry("ge45", scan.ge45),
            entry("ge50", scan.ge50),
            entry("ge55", scan.ge55),
            entry("ge60", scan.ge60),
            entry("meanRaw", r3(scan.meanRaw)),
            entry("meanCal", r3(scan.meanCal)),
            entry("deltaGt01", scan.deltaGt01)
        ));

        if (scan.count > 0 && scan.maxP >= 0.999 && scan.p95 >= 0.999 && scan.meanRaw >= 0.999) {
            log.warn("SCAN appears degenerate (all ~1.0). Using softmaxed scores; check trainer/outputs if this persists.");
        }

        // Paper trading
        paper.reset();
        double equityR = 0.0;
        List<Double> equityCurve = new ArrayList<>();
        int cooldownBars = barsPerDayFor(granularity);
        int lastOpenIndex = -cooldownBars;
        int lastOpenDay = -1;
        int rejProb = 0, rejVol = 0, rejSession = 0, rejTrend = 0, rejWindow = 0, rejMargin = 0;
        int rejEVR = 0;
        int considered = 0, passedProb = 0, opened = 0;
        for (int i = warmup; i < n; i++) {
            Candle c = candles.get(i);

            Example<Label> ex = TribuoUtil.exampleFromArrays(i, preClose, preMaFast, preMaSlow, preRsi, preAtr);
            if (ex == null) continue;
            considered++;

            var pred = model.predict(ex);
            String label = pred.getOutput().getLabel();
            PaperTrade.Side side = "UP".equals(label) ? PaperTrade.Side.BUY : PaperTrade.Side.SELL;

            // Gate entries by positive EV with a margin, volatility floor, session filter, and regime filter
            double rrVal = props.getPaper().getRr();
            double minPw = 1.0 / (1.0 + rrVal);
            double baseThreshold = Math.min(1.0, minPw + evMargin);
            // Combine with an execution signal threshold (stricter wins)
            double execFloor = 0.0;
            try {
                execFloor = props.getExecution().getSignalThreshold();
            } catch (Throwable ignored) {
            }
            if (!Double.isFinite(execFloor)) execFloor = 0.0;
            double probThreshold = Math.max(baseThreshold, execFloor);
            boolean allowTrade = true;
            double pWinCandidate = Double.NaN;
            try {
                var scores = pred.getOutputScores();

                Double vUp = null, vDown = null;
                if (scores != null) {
                    for (var e : scores.entrySet()) {
                        String lab = labelName(e.getKey());
                        double val = e.getValue().getScore();
                        if ("UP".equals(lab)) vUp = val;
                        else if ("DOWN".equals(lab)) vDown = val;
                    }
                }

                if (vUp != null && vDown != null) {
                    Double pUp, pDown;
                    boolean in01 = (vUp >= 0.0 && vUp <= 1.0 && vDown >= 0.0 && vDown <= 1.0);
                    boolean oneHot = in01 && (
                        (Math.abs(vUp - 1.0) < 1e-9 && Math.abs(vDown - 0.0) < 1e-9) ||
                            (Math.abs(vDown - 1.0) < 1e-9 && Math.abs(vUp - 0.0) < 1e-9)
                    );
                    boolean sumsToOne = in01 && Math.abs((vUp + vDown) - 1.0) < 1e-6;

                    if (in01 && sumsToOne && !oneHot) {
                        pUp = vUp; pDown = vDown; // proper probs
                    } else {
                        double m = Math.max(vUp, vDown);
                        double eUp = Math.exp(vUp - m), eDn = Math.exp(vDown - m);
                        double z = eUp + eDn;
                        pUp = eUp / z; pDown = eDn / z; // softmax
                    }

                    double pWin = ("UP".equals(label)) ? pUp : pDown;
                    double pCal = calibrate(pWin, calibTable);
                    pWinCandidate = pCal; // used later for binning & streaming

                    // EV gate in R-units: EV_R = p*RR - (1-p)*1
                    double marginR = env.getProperty("forex.filters.ev.marginR", Double.class, 0.30);
                    double evR = pCal * rrVal - (1.0 - pCal);
                    if (evR < marginR) { allowTrade = false; rejEVR++; }
                    if (allowTrade) {
                        if (pCal < probThreshold) { allowTrade = false; rejProb++; }
                        else { passedProb++; }
                    }
                }
            } catch (Throwable ignored) {
            }

            // Volatility floor with small Slack (5% below percentile) to avoid razor-thin rejections
            if (allowTrade && (i - 1) >= 0 && (i - 1) >= volWin - 1) {
                double pXX = percentileOfWindow(atr, i - 1, volWin, volPct);
                if (Double.isFinite(pXX)) {
                    double slack = pXX * 0.95; // 5% slack
                    if (atr[i] < slack) {
                        allowTrade = false;
                        rejVol++;
                    }
                }
            }

            // Session filter with soft override for confident signals
            double pBoost = Double.isNaN(pWinCandidate) ? 0.0 : (pWinCandidate - probThreshold);
            if (allowTrade && !inSession(c.time, sessionStart, sessionEnd)) {
                // Soft override: if model is at least +0.5% above the effective threshold, allow outside session
                if (!(pBoost >= 0.005)) {
                    allowTrade = false;
                    rejSession++;
                }
            }

            // Trend/Regime filter: require BOTH by default, but allow OR if model is very confident (+0.5% over an eff threshold)
            if (allowTrade) {
                double maTol = Math.abs(preMaSlow[i]) * 0.005; // 0.5% tolerance
                boolean maAligned = (side == PaperTrade.Side.BUY)
                    ? (preMaFast[i] > preMaSlow[i] - maTol)
                    : (preMaFast[i] < preMaSlow[i] + maTol);
                boolean rsiRegime = (side == PaperTrade.Side.BUY)
                    ? (preRsi[i] > rsiLong)
                    : (preRsi[i] < rsiShort);
                boolean trendOk = maAligned && rsiRegime; // baseline
                if (!trendOk && pBoost >= 0.005) {
                    // High-confidence override: accept if either MA alignment OR RSI regime is satisfied
                    trendOk = (maAligned || rsiRegime);
                }
                if (!trendOk) {
                    allowTrade = false;
                    rejTrend++;
                }
            }

            int currentDay = toUtcDayKey(c.time);
            long tradesToday = paper.getClosed().stream()
                .filter(t -> toUtcDayKey(t.getOpenedAt()) == currentDay)
                .count();
            boolean windowOk = onePerDay ? (tradesToday < 2) : ((i - lastOpenIndex) >= cooldownBars);
            if (allowTrade && paper.canOpen(instrument) && windowOk) {
                double entry = c.close;   // use mid/bid/ask if available
                double atrVal = atr[i];

                // Use ATR-based stop distance multiplier to avoid unrealistically tight stops
                double stopDist = Math.max(1e-6, atrVal * stopAtrMult);

                // Intended $ risk and target units
                double riskUSD = Math.max(0.0, props.getPaper().getRisk()) * equityUSD;
                double targetUnits = (stopDist > 0) ? (riskUSD / stopDist) : 0.0;

                // Margin cap: respect available equity and leverage
                double maxUnitsByMargin = (equityUSD * leverage) / Math.max(1e-9, entry);
                double units = Math.min(targetUnits, Math.max(0.0, maxUnitsByMargin));

                if (units <= 0) {
                    // No feasible position size under current margin/equity
                    rejMargin++;
                } else {
                    // Track actual $ risk used (may be < intended if margin-capped)
                    double actualRiskUSD = units * stopDist;

                    paper.open(instrument, side, entry, atrVal, i, c.time);
                    lastOpenIndex = i;
                    lastOpenDay = toUtcDayKey(c.time);
                    if (!Double.isNaN(pWinCandidate)) pendingPWin.add(pWinCandidate);
                    pendingRiskUSD.add(actualRiskUSD);
                    opened++;
                }
            } else if (allowTrade && !windowOk) {
                rejWindow++;
            }

            paper.onCandle(instrument, c.high, c.low, c.close, c.time);

            // Stream newly closed trades as SSE events
            var closedNow = paper.getClosed();
            if (closedNow.size() > lastClosedCount) {
                for (int k = lastClosedCount; k < closedNow.size(); k++) {
                    var t = closedNow.get(k);
                    double risk = Math.abs(t.getEntry() - t.getStop());
                    if (risk == 0) continue;
                    double r = (t.getSide() == PaperTrade.Side.BUY)
                        ? (t.getExit() - t.getEntry()) / risk
                        : (t.getEntry() - t.getExit()) / risk;
                    // Calibration binning
                    double p = pendingPWin.isEmpty() ? Double.NaN : pendingPWin.pollFirst();
                    if (!Double.isNaN(p)) {
                        for (int b = 0; b < B; b++) {
                            if (p >= binEdges[b] && p < binEdges[b + 1]) {
                                binCount[b]++;
                                if (r > 0) binWins[b]++;
                                binSumR[b] += r;
                                break;
                            }
                        }
                    }
                    // Map R to $ using risk at entry; update equity and USD equity curve
                    double usedRiskUSD = pendingRiskUSD.isEmpty() ? 0.0 : pendingRiskUSD.pollFirst();
                    double pnlUSD = r * usedRiskUSD;
                    equityUSD += pnlUSD;
                    equityCurveUSD.add(equityUSD);
                    equityR += r;
                    equityCurve.add(equityR);
                    try {
                        Map<String, Object> tradePayload = new HashMap<>();
                        tradePayload.put("type", "trade");
                        tradePayload.put("index", i);
                        tradePayload.put("side", String.valueOf(t.getSide()));
                        tradePayload.put("r", r);
                        tradePayload.put("equityR", equityR);
                        tradePayload.put("status", String.valueOf(t.getStatus()));
                        tradePayload.put("entry", t.getEntry());
                        tradePayload.put("exit", t.getExit());
                        tradePayload.put("stop", t.getStop());
                        tradePayload.put("takeProfit", t.getTake());
                        // Best-effort timestamp; if trade has exit time, prefer it, else candle time
                        try {
                            tradePayload.put("time", t.getExit() != null ? t.getExit().toString() : c.time.toString());
                        } catch (Throwable __ignore) {
                            tradePayload.put("time", c.time.toString());
                        }
                        tradePayload.put("pnlUSD", pnlUSD);
                        tradePayload.put("equityUSD", equityUSD);
                        sseHub.emit(jobId, "trade", objectMapper.writeValueAsString(tradePayload));
                    } catch (Exception ignored) {
                    }
                }
                lastClosedCount = closedNow.size();
            }

            if ((i % 500) == 0) {
                try {
                    sseHub.emit(jobId, "progress", objectMapper.writeValueAsString(Map.of(
                        "phase", "loop",
                        "i", i,
                        "of", n
                    )));
                } catch (Exception ignored) {
                }
            }
        }

        // Always log rejection summary, even if no trades closed
        logJ("REJECTIONS", Map.ofEntries(
            entry("considered", considered),
            entry("passedProb", passedProb),
            entry("opened", opened),
            entry("prob", rejProb),
            entry("vol", rejVol),
            entry("session", rejSession),
            entry("trend", rejTrend),
            entry("window", rejWindow),
            entry("evR", rejEVR),
            entry("margin", rejMargin)
        ));

        var closed = paper.getClosed();
        if (closed.isEmpty()) {
            log.warn("No paper trades closed (UI) jobId={}", jobId);
            return;
        }

        var rSeries = realizedRSeries(closed);
        double totalR = sum(rSeries);
        double avgR = totalR / rSeries.size();
        long wins = closed.stream().filter(t -> t.getStatus() == PaperTrade.Status.WON).count();
        long losses = closed.stream().filter(t -> t.getStatus() == PaperTrade.Status.LOST).count();
        double winRate = 100.0 * wins / (wins + losses);
        double pf = profitFactor(rSeries);
        double mddR = maxDrawdownR(rSeries);

        logCalibrationTable(binEdges, binCount, binWins, binSumR);
        logJ("REJECTIONS", Map.ofEntries(
            entry("considered", considered),
            entry("passedProb", passedProb),
            entry("opened", opened),
            entry("prob", rejProb),
            entry("vol", rejVol),
            entry("session", rejSession),
            entry("trend", rejTrend),
            entry("window", rejWindow),
            entry("evR", rejEVR),
            entry("margin", rejMargin)
        ));

        logJ("RESULTS", Map.ofEntries(
            entry("trades", rSeries.size()),
            entry("wins", wins),
            entry("losses", losses),
            entry("winRatePct", r2(winRate)),
            entry("totalR", r2(totalR)),
            entry("avgR", r3(avgR)),
            entry("pf", r3(pf)),
            entry("maxDDR", r2(mddR)),
            entry("jobId", jobId),
            entry("maType", props.getTrading().getMaType()),
            entry("filters", effectiveFilters)
        ));

        try {
            Map<String, Object> result = new HashMap<>();
            result.put("trades", rSeries.size());
            result.put("wins", wins);
            result.put("losses", losses);
            result.put("winRate", Double.parseDouble(String.format("%.1f", winRate)));
            result.put("totalR", Double.parseDouble(format2(totalR)));
            result.put("avgR", Double.parseDouble(format3(avgR)));
            result.put("profitFactor", Double.parseDouble(format3(pf)));
            result.put("maxDrawdownR", Double.parseDouble(format2(mddR)));
            result.put("equityCurve", equityCurve);
            result.put("startBalance", startBalance);
            result.put("endBalance", Double.parseDouble(String.format("%.2f", equityUSD)));
            result.put("equityCurveUSD", equityCurveUSD);
            sseHub.emit(jobId, "result", objectMapper.writeValueAsString(result));
        } catch (Exception ignored) {
        }

        // signal completion to clients
        try {
            sseHub.complete(jobId);
        } catch (Exception ignored) {
        }
    }

    // ------------------------- Compact logging helpers -------------------------
    private void logJ(String tag, Map<String, Object> fields) {
        try {
            log.info("{} {}", tag, objectMapper.writeValueAsString(fields));
        } catch (Exception e) {
            log.info("{} {}", tag, fields); // fallback
        }
    }

    private static double r2(double v) {
        return Double.parseDouble(String.format("%.2f", v));
    }

    private static double r3(double v) {
        return Double.parseDouble(String.format("%.3f", v));
    }

    /**
     * Returns the label text from a Tribuo key which may be a Label or a String.
     */
    private static String labelName(Object key) {
        if (key instanceof org.tribuo.classification.Label) {
            return ((org.tribuo.classification.Label) key).getLabel();
        }
        return String.valueOf(key);
    }
    // ------------------------- Helper types & methods (extracted) -------------------------

    private static final class ScanStats {
        int count, ge45, ge50, ge55, ge60, deltaGt01;
        double maxP, p95, meanRaw, meanCal;
    }

    /**
     * Emits the standard probability scan by iterating over bars and applying optional calibration.
     */
    private ScanStats doProbabilityScan(Model<Label> model,
                                        List<Candle> candles,
                                        int warmup,
                                        int n,
                                        int fast,
                                        int slow,
                                        int atrP,
                                        String maType,
                                        double[][] calibTable) {
        ScanStats s = new ScanStats();
        double sumRaw = 0.0, sumCal = 0.0;
        double maxP = 0.0;
        int ge45 = 0, ge50 = 0, ge55 = 0, ge60 = 0, deltaGt01 = 0;
        double[] allP = new double[n];
        int allPSize = 0;

        // Precompute arrays once
        double[] close = candles.stream().mapToDouble(c -> c.close).toArray();
        double[] high  = candles.stream().mapToDouble(c -> c.high).toArray();
        double[] low   = candles.stream().mapToDouble(c -> c.low).toArray();

        boolean useEMAForFast = "EMA".equalsIgnoreCase(maType) || "HYBRID".equalsIgnoreCase(maType);
        boolean useEMAForSlow = "EMA".equalsIgnoreCase(maType);

        double[] maFast = useEMAForFast ? Indicators.ema(close, fast) : Indicators.sma(close, fast);
        double[] maSlow = useEMAForSlow ? Indicators.ema(close, slow) : Indicators.sma(close, slow);
        double[] rsi    = Indicators.rsi(close, 14);
        double[] atr    = Indicators.atr(high, low, close, atrP);


        for (int i = warmup; i < n; i++) {
            Example<Label> ex = TribuoUtil.exampleFromArrays(i, close, maFast, maSlow, rsi, atr);
            if (ex == null) continue;
            try {
                var pred = model.predict(ex);
                var scores = pred.getOutputScores();

                // Collect the two values first (works whether they are probs or raw scores).
                Double vUp = null, vDown = null;
                if (scores != null) {
                    for (var e : scores.entrySet()) {
                        String lab = labelName(e.getKey());
                        double val = e.getValue().getScore();
                        if ("UP".equals(lab)) vUp = val;
                        else if ("DOWN".equals(lab)) vDown = val;
                    }
                }

                // Decide: use as probabilities, or softmax raw/one-hot values.
                Double pUp = null, pDown = null;
                if (vUp != null && vDown != null) {
                    boolean in01 = (vUp >= 0.0 && vUp <= 1.0 && vDown >= 0.0 && vDown <= 1.0);
                    boolean oneHot = in01 && (
                        (Math.abs(vUp - 1.0) < 1e-9 && Math.abs(vDown - 0.0) < 1e-9) ||
                            (Math.abs(vDown - 1.0) < 1e-9 && Math.abs(vUp - 0.0) < 1e-9)
                    );
                    boolean sumsToOne = in01 && Math.abs((vUp + vDown) - 1.0) < 1e-6;

                    if (in01 && sumsToOne && !oneHot) {
                        pUp = vUp;
                        pDown = vDown; // proper probabilities
                    } else {
                        double m = Math.max(vUp, vDown);
                        double eUp = Math.exp(vUp - m), eDn = Math.exp(vDown - m);
                        double z = eUp + eDn;
                        pUp = eUp / z;
                        pDown = eDn / z; // softmax fallback
                    }
                }

                if (pUp != null) {
                    double pRaw = Math.max(pUp, pDown);
                    double p = calibrate(pRaw, calibTable);
                    sumRaw += pRaw;
                    sumCal += p;
                    if (Math.abs(p - pRaw) > 0.01) deltaGt01++;
                    if (Double.isFinite(p)) {
                        allP[allPSize++] = p;
                        if (p >= 0.45) ge45++;
                        if (p >= 0.50) ge50++;
                        if (p >= 0.55) ge55++;
                        if (p >= 0.60) ge60++;
                        if (p > maxP) maxP = p;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        s.count = allPSize;
        s.maxP = maxP;
        s.p95 = percentile(allP, allPSize);
        s.ge45 = ge45;
        s.ge50 = ge50;
        s.ge55 = ge55;
        s.ge60 = ge60;
        s.deltaGt01 = deltaGt01;
        s.meanRaw = (allPSize == 0 ? Double.NaN : sumRaw / Math.max(1, allPSize));
        s.meanCal = (allPSize == 0 ? Double.NaN : sumCal / Math.max(1, allPSize));
        return s;
    }

    /**
     * Logs the taken-trade calibration table as a compact JSON payload.
     */
    private void logCalibrationTable(double[] binEdges, int[] binCount, int[] binWins, double[] binSumR) {
        java.util.List<java.util.Map<String, Object>> bins = new java.util.ArrayList<>();
        for (int b = 0; b < binCount.length; b++) {
            double lo = binEdges[b], hi = binEdges[b + 1];
            int cnt = binCount[b];
            double winp = cnt == 0 ? 0.0 : (100.0 * binWins[b] / Math.max(1, cnt));
            double avgr = cnt == 0 ? 0.0 : (binSumR[b] / Math.max(1, cnt));
            bins.add(java.util.Map.of(
                "lo", r2(lo),
                "hi", r2(hi),
                "count", cnt,
                "winPct", r2(winp),
                "avgR", r3(avgr)
            ));
        }
        logJ("CALIB_TAKEN", java.util.Map.of("bins", bins));
    }
}
