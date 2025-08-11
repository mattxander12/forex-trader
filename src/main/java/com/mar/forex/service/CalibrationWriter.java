package com.mar.forex.service;

import lombok.extern.slf4j.Slf4j;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.tribuo.Example;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.classification.Label;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.domain.model.MarketIndicators;
import com.mar.forex.util.TribuoUtil;

@Service
@Slf4j
public class CalibrationWriter {

    private static final double[] BIN_EDGES = {0.45,0.50,0.55,0.60,0.65,0.70,0.75,1.01};
    private static final String CSV_HEADER = "lo,hi,winRate\n";

    /**
     * Writes a simple probability calibration table from the test set.
     */
    public void writeProbabilityTable(Model<Label> model,
                                            MutableDataset<Label> test,
                                            Path path) {
        try {
            int binCount = BIN_EDGES.length - 1;
            int[] count = new int[binCount];
            int[] correct = new int[binCount];

            for (Example<Label> ex : test) {
                var pred = model.predict(ex);
                var scores = pred.getOutputScores();
                if (pred.hasProbabilities() && scores != null &&
                    scores.containsKey("UP") && scores.containsKey("DOWN")) {
                    double pUp = scores.get("UP").getScore();
                    double pDown = scores.get("DOWN").getScore();
                    double maxProb = Math.max(pUp, pDown);
                    String predictedLabel = (pUp >= pDown) ? "UP" : "DOWN";
                    String actualLabel = ((Label) ex.getOutput()).getLabel();

                    updateBinCounts(maxProb, predictedLabel, actualLabel, count, correct);
                }
            }

            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path)) {
                w.write(CSV_HEADER);
                for (int b = 0; b < binCount; b++) {
                    double lowerBound = BIN_EDGES[b], upperBound = BIN_EDGES[b+1];
                    double winRate = (count[b] == 0) ? Double.NaN : ((double) correct[b]) / count[b];
                    w.write(String.format(Locale.ROOT, "%.2f,%.2f,%.6f%n", lowerBound, upperBound, winRate));
                }
            }
            log.info("TRAIN | wrote calibration table to {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("TRAIN | failed to write calibration table: {}", e, e);
        }
    }

    /**
     * Writes a trade-regime calibration table for predictions on held-out candles.
     */
    public void writeRegimeTable(Model<Label> model,
                                            List<Candle> candles,
                                            MarketIndicators indicators,
                                            int splitIdx,
                                            Path path) {
        try {
            int n = candles.size();
            double[] atr = indicators.atr();
            double[] rsi = indicators.rsi();
            double[] maFast = indicators.maFast();
            double[] maSlow = indicators.maSlow();

            int binCount = BIN_EDGES.length - 1;
            int[] count = new int[binCount];
            int[] correct = new int[binCount];

            // regime filter knobs (read from system properties like before)
            int volWin = Integer.parseInt(System.getProperty("forex.filters.atr.window", "20"));
            double volPct = Double.parseDouble(System.getProperty("forex.filters.atr.percentile", "30.0"));
            String sessionStr = System.getProperty("forex.filters.session", "13:00-17:00Z");
            int rsiLong = Integer.parseInt(System.getProperty("forex.filters.rsi.long", "55"));
            int rsiShort = Integer.parseInt(System.getProperty("forex.filters.rsi.short", "45"));

            // parse session window
            int[] sessionBounds = parseSession(sessionStr);
            int sessionStart = sessionBounds[0], sessionEnd = sessionBounds[1];

            int warmup = Math.max(Math.max(maFast.length, maSlow.length), Math.max(14, volWin)) + 1;

            for (int i = Math.max(warmup, splitIdx); i < n - 1; i++) {
                // --- volatility filter
                if (!volatilityOk(atr, i, volWin, volPct)) continue;

                // --- session filter
                int hour = utcHour(candles.get(i).time);
                if (hour < sessionStart || hour >= sessionEnd) continue;

                // --- regime filter
                double maTol = Math.abs(maSlow[i]) * 0.005;
                boolean rsiLongOk = rsi[i] > rsiLong;
                boolean rsiShortOk = rsi[i] < rsiShort;
                boolean maLongOk = maFast[i] > maSlow[i] - maTol;
                boolean maShortOk = maFast[i] < maSlow[i] + maTol;
                if (!((rsiLongOk && maLongOk) || (rsiShortOk && maShortOk))) continue;

                var ex = TribuoUtil.exampleFromBar(candles, i, 12, 48, 14); // TODO: pass real params
                if (ex == null) continue;

                var pred = model.predict(ex);
                var scores = pred.getOutputScores();
                if (pred.hasProbabilities() && scores != null &&
                    scores.containsKey("UP") && scores.containsKey("DOWN")) {
                    double pUp = scores.get("UP").getScore();
                    double pDown = scores.get("DOWN").getScore();
                    double maxProb = Math.max(pUp, pDown);
                    String predictedLabel = (pUp >= pDown) ? "UP" : "DOWN";
                    String actualLabel = ((Label) ex.getOutput()).getLabel();

                    updateBinCounts(maxProb, predictedLabel, actualLabel, count, correct);
                }
            }

            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path)) {
                w.write(CSV_HEADER);
                for (int b = 0; b < binCount; b++) {
                    double lowerBound = BIN_EDGES[b], upperBound = BIN_EDGES[b+1];
                    double winRate = (count[b] == 0) ? Double.NaN : ((double) correct[b]) / count[b];
                    w.write(String.format(Locale.ROOT, "%.2f,%.2f,%.6f%n", lowerBound, upperBound, winRate));
                }
            }
            log.info("TRAIN | wrote trade-regime calibration table to {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("TRAIN | failed to write trade-regime calibration table: {}", e, e);
        }
    }

    private void updateBinCounts(double maxProb, String predictedLabel, String actualLabel,
                                 int[] count, int[] correct) {
        for (int b = 0; b < BIN_EDGES.length - 1; b++) {
            if (maxProb >= BIN_EDGES[b] && maxProb < BIN_EDGES[b+1]) {
                count[b]++;
                if (predictedLabel.equals(actualLabel)) correct[b]++;
                break;
            }
        }
    }

    // --- helpers ---
    private int[] parseSession(String s) {
        try {
            if (s == null || s.isBlank()) return new int[]{13,17};
            String[] parts = s.replace("Z","").split("-");
            String[] a = parts[0].split(":");
            String[] b = parts[1].split(":");
            return new int[]{Integer.parseInt(a[0]), Integer.parseInt(b[0])};
        } catch (Exception e) {
            return new int[]{13,17};
        }
    }

    private boolean volatilityOk(double[] atr, int idx, int win, double pct) {
        if (idx - 1 < win) return true;
        int start = Math.max(0, idx - win + 1);
        int len = idx - start + 1;
        double[] tmp = Arrays.copyOfRange(atr, start, idx+1);
        Arrays.sort(tmp);
        double rank = (pct / 100.0) * (len - 1);
        int loI = (int)Math.floor(rank);
        int hiI = (int)Math.ceil(rank);
        double val = (loI == hiI) ? tmp[loI]
            : tmp[loI] * (1.0 - (rank - loI)) + tmp[hiI] * (rank - loI);
        return atr[idx] >= val * 0.98;
    }

    private int utcHour(Object t) {
        try {
            ZonedDateTime zdt;
            if (t instanceof ZonedDateTime) zdt = (ZonedDateTime) t;
            else if (t instanceof OffsetDateTime) zdt = ((OffsetDateTime) t).toZonedDateTime();
            else if (t instanceof java.time.Instant) zdt = ZonedDateTime.ofInstant((java.time.Instant) t, java.time.ZoneOffset.UTC);
            else zdt = ZonedDateTime.parse(String.valueOf(t));
            return zdt.withZoneSameInstant(java.time.ZoneOffset.UTC).getHour();
        } catch (Exception e) {
            return 14;
        }
    }

    /**
     * Writes out a CSV of raw predicted probabilities and actual outcomes for isotonic calibration.
     * Each line: prob,predicted,actual
     */
    public void writeIsotonicCalibration(Model<Label> model,
                                         MutableDataset<Label> test,
                                         Path path) {
        final String HEADER = "prob,predicted,actual\n";
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(path)) {
                w.write(HEADER);
                for (Example<Label> ex : test) {
                    var pred = model.predict(ex);
                    var scores = pred.getOutputScores();
                    double pUp = Double.NaN, pDown = Double.NaN;
                    if (scores != null && scores.containsKey("UP") && scores.containsKey("DOWN")) {
                        pUp = scores.get("UP").getScore();
                        pDown = scores.get("DOWN").getScore();
                    }
                    String predictedLabel;
                    double maxProb;
                    if (!Double.isNaN(pUp) && !Double.isNaN(pDown)) {
                        maxProb = Math.max(pUp, pDown);
                        predictedLabel = (pUp >= pDown) ? "UP" : "DOWN";
                    } else {
                        // fallback: use output label and probability as NaN
                        maxProb = Double.NaN;
                        predictedLabel = pred.getOutput().getLabel();
                    }
                    String actualLabel = ((Label) ex.getOutput()).getLabel();
                    w.write(String.format(Locale.ROOT, "%.6f,%s,%s%n", maxProb, predictedLabel, actualLabel));
                }
            }
            log.info("TRAIN | wrote isotonic calibration CSV to {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("TRAIN | failed to write isotonic calibration CSV: {}", e, e);
        }
    }
}


