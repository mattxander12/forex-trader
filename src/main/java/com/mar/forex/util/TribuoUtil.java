package com.mar.forex.util;

import java.util.List;
import org.tribuo.Example;
import org.tribuo.classification.Label;
import org.tribuo.impl.ArrayExample;
import com.mar.forex.domain.model.Candle;

public class TribuoUtil {

    public static Example<Label> exampleFromBar(List<Candle> candles, int i, int fast, int slow, int atrP) {
        // Backward-compatible default: SMA for both fast & slow
        return exampleFromBar(candles, i, fast, slow, atrP, "SMA");
    }

    public static Example<Label> exampleFromBar(List<Candle> candles,
                                                int i,
                                                int fast,
                                                int slow,
                                                int atrP,
                                                String maType) {
        if (i < 1) return null;

        double[] close = candles.stream().mapToDouble(c -> c.close).toArray();
        double[] high  = candles.stream().mapToDouble(c -> c.high).toArray();
        double[] low   = candles.stream().mapToDouble(c -> c.low).toArray();

        double[] maFast = ("EMA".equalsIgnoreCase(maType) || "HYBRID".equalsIgnoreCase(maType))
            ? com.mar.forex.util.Indicators.ema(close, fast)
            : com.mar.forex.util.Indicators.sma(close, fast);

        double[] maSlow = ("EMA".equalsIgnoreCase(maType))
            ? com.mar.forex.util.Indicators.ema(close, slow)
            : com.mar.forex.util.Indicators.sma(close, slow);

        double[] rsi = com.mar.forex.util.Indicators.rsi(close, 14);
        double[] atr = com.mar.forex.util.Indicators.atr(high, low, close, atrP);

        return exampleFromArrays(i, close, maFast, maSlow, rsi, atr);
    }

    /**
     * Accepts precomputed arrays for close, maFast, maSlow, rsi, atr, and builds an Example for bar i.
     * Includes engineered features for both training and inference.
     */
    public static Example<Label> exampleFromArrays(int i,
                                                   double[] close,
                                                   double[] maFast,
                                                   double[] maSlow,
                                                   double[] rsi,
                                                   double[] atr) {
        if (i < 1) return null;

        if (Double.isNaN(maFast[i]) || Double.isNaN(maSlow[i]) ||
            Double.isNaN(rsi[i]) || Double.isNaN(atr[i])) {
            return null;
        }

        double ret1 = (close[i] - close[i - 1]) / close[i - 1];

        ArrayExample<Label> ex = new ArrayExample<>(new Label("UP")); // Placeholder label for prediction
        ex.add("maFast", maFast[i]);
        ex.add("maSlow", maSlow[i]);
        ex.add("rsi",     rsi[i]);
        ex.add("atr",     atr[i]);
        ex.add("ret1",    ret1);

        // Engineered features (for both training and inference)
        ex.add("maDiff", maFast[i] - maSlow[i]);
        if (i >= 1) ex.add("rsiDelta", rsi[i] - rsi[i - 1]);
        ex.add("atrNorm", atr[i] / close[i]);
        ex.add("maRatio", maFast[i] / (maSlow[i] + 1e-9));
        if (i >= 5) {
            ex.add("ret5", (close[i] - close[i - 5]) / close[i - 5]);
        }

        // Extra engineered features
        ex.add("maSlopeFast", i >= 1 ? (maFast[i] - maFast[i-1]) : 0.0);
        ex.add("maSlopeSlow", i >= 1 ? (maSlow[i] - maSlow[i-1]) : 0.0);
        ex.add("atrRatio", atr[i] / (atr[i-1] + 1e-9));
        ex.add("rsiNorm", rsi[i] / 100.0);
        if (i >= 10) {
            ex.add("ret10", (close[i] - close[i-10]) / close[i-10]);
        }

        return ex;
    }
}
