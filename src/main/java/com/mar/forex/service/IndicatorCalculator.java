package com.mar.forex.service;

import org.springframework.stereotype.Service;
import com.mar.forex.domain.model.MarketIndicators;
import com.mar.forex.util.Indicators;

@Service
public class IndicatorCalculator {
    public MarketIndicators compute(double[] close, double[] high, double[] low,
                                    int fast, int slow, int atrPeriod, String maType) {
        double[] maFast = ("EMA".equalsIgnoreCase(maType) || "HYBRID".equalsIgnoreCase(maType))
            ? Indicators.ema(close, fast)
            : Indicators.sma(close, fast);
        double[] maSlow = ("EMA".equalsIgnoreCase(maType))
            ? Indicators.ema(close, slow)
            : Indicators.sma(close, slow);
        double[] rsi = Indicators.rsi(close, 14);
        double[] atr = Indicators.atr(high, low, close, atrPeriod);
        return new MarketIndicators(maFast, maSlow, rsi, atr);
    }
}
