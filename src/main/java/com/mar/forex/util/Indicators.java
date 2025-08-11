package com.mar.forex.util;

import java.util.Arrays;

public class Indicators {

    public static double[] sma(double[] series, int period) {
        double[] out = new double[series.length];
        Arrays.fill(out, Double.NaN);
        double sum = 0.0;
        for (int i = 0; i < series.length; i++) {
            sum += series[i];
            if (i >= period) sum -= series[i - period];
            if (i >= period - 1) out[i] = sum / period;
        }
        return out;
    }

    /**
     * Exponential Moving Average (EMA) with smoothing factor k = 2/(period+1).
     * Seeds with SMA of first `period` values (or first value if not enough).
     */
    public static double[] ema(double[] values, int period) {
        if (values == null || values.length == 0 || period <= 1) {
            return values == null ? new double[0] : values.clone();
        }
        double[] out = new double[values.length];
        final double k = 2.0 / (period + 1.0);

        double seed;
        if (values.length >= period) {
            double sum = 0.0;
            for (int i = 0; i < period; i++) sum += values[i];
            seed = sum / period;
        } else {
            seed = values[0];
        }
        out[0] = seed;

        for (int i = 1; i < values.length; i++) {
            out[i] = values[i] * k + out[i - 1] * (1.0 - k);
        }
        return out;
    }

    public static double[] rsi(double[] close, int period) {
        double[] out = new double[close.length];
        Arrays.fill(out, Double.NaN);
        double gain = 0, loss = 0;
        for (int i = 1; i < close.length; i++) {
            double ch = close[i] - close[i-1];
            double up = Math.max(ch, 0);
            double dn = Math.max(-ch, 0);
            if (i <= period) {
                gain += up; loss += dn;
                if (i == period) {
                    double rs = (loss == 0) ? 0 : (gain/period) / (loss/period);
                    out[i] = 100 - 100/(1+rs);
                }
            } else {
                gain = (gain*(period-1) + up)/period;
                loss = (loss*(period-1) + dn)/period;
                double rs = (loss == 0) ? 0 : (gain) / (loss);
                out[i] = 100 - 100/(1+rs);
            }
        }
        return out;
    }

    public static double[] atr(double[] high, double[] low, double[] close, int period) {
        double[] out = new double[close.length];
        Arrays.fill(out, Double.NaN);
        double prevClose = Double.NaN;
        double trEMA = 0.0;
        for (int i = 0; i < close.length; i++) {
            double hl = high[i] - low[i];
            double hc = (Double.isNaN(prevClose)) ? hl : Math.abs(high[i] - prevClose);
            double lc = (Double.isNaN(prevClose)) ? hl : Math.abs(low[i] - prevClose);
            double tr = Math.max(hl, Math.max(hc, lc));
            if (i < period) {
                trEMA += tr;
                if (i == period - 1) {
                    trEMA /= period;
                    out[i] = trEMA;
                }
            } else {
                trEMA = (trEMA*(period-1) + tr)/period;
                out[i] = trEMA;
            }
            prevClose = close[i];
        }
        return out;
    }

    public static double pipSize(String instrument) {
        return instrument.endsWith("JPY") ? 0.01 : 0.0001;
    }
}
