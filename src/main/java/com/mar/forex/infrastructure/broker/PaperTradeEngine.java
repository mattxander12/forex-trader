package com.mar.forex.infrastructure.broker;

import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import com.mar.forex.config.AppProperties;
import com.mar.forex.domain.model.PaperTrade;

@Service
@RequiredArgsConstructor
public class PaperTradeEngine {

    private final AppProperties props;

    private final List<PaperTrade> open = new ArrayList<>();
    private final List<PaperTrade> closed = new ArrayList<>();

    public List<PaperTrade> getOpen() {
        return List.copyOf(open);
    }

    public List<PaperTrade> getClosed() {
        return List.copyOf(closed);
    }

    public void reset() {
        open.clear();
        closed.clear();
    }

    /**
     * Open a pretend trade with configured R:R around the given entry price.
     */
    public PaperTrade open(String instrument, PaperTrade.Side side, double entry, double atr,
                           int candleIndex, Instant ts) {
        double riskDistance;
        if ("ATR".equalsIgnoreCase(props.getPaper().getMode())) {
            riskDistance = atr * props.getPaper().getRisk();
        } else {
            double pip = instrument.endsWith("_JPY") ? 0.01 : 0.0001; // simple pip size heuristic
            riskDistance = props.getPaper().getPips() * pip;
        }
        double rewardDistance = riskDistance * props.getPaper().getRr();

        double stop, take;
        if (side == PaperTrade.Side.BUY) {
            stop = entry - riskDistance;
            take = entry + rewardDistance;
        } else {
            stop = entry + riskDistance;
            take = entry - rewardDistance;
        }

        var t = PaperTrade.builder()
                          .instrument(instrument)
                          .side(side)
                          .openedAt(ts)
                          .entry(entry)
                          .stop(stop)
                          .take(take)
                          .status(PaperTrade.Status.OPEN)
                          .openIndex(candleIndex)
                          .build();

        open.add(t);
        return t;
    }

    public boolean canOpen(String instrument) {
        long cnt = open.stream().filter(t -> t.getInstrument().equals(instrument)).count();
        return cnt < props.getPaper().getMaxOpenPerInstrument();
    }

    /**
     * Call this for every new candle to settle trades by first-touch rule.
     */
    public void onCandle(String instrument, double high, double low, double close, Instant ts) {
        // Iterate over a snapshot so we can remove from 'open'
        var snapshot = new ArrayList<>(open);
        for (var t : snapshot) {
            if (!t.getInstrument().equals(instrument)) continue;

            if (t.getSide() == PaperTrade.Side.BUY) {
                if (low <= t.getStop()) closeAs(t, PaperTrade.Status.LOST, t.getStop(), "SL", ts);
                else if (high >= t.getTake()) closeAs(t, PaperTrade.Status.WON, t.getTake(), "TP", ts);
            } else {
                if (high >= t.getStop()) closeAs(t, PaperTrade.Status.LOST, t.getStop(), "SL", ts);
                else if (low <= t.getTake()) closeAs(t, PaperTrade.Status.WON, t.getTake(), "TP", ts);
            }
        }
    }

    private void closeAs(PaperTrade t, PaperTrade.Status status, double exit, String reason, Instant ts) {
        t.setStatus(status);
        t.setExit(exit);
        t.setReason(reason);
        t.setClosedAt(ts);
        open.remove(t);
        closed.add(t);
    }
}
