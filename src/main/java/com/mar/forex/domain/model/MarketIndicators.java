package com.mar.forex.domain.model;

public record MarketIndicators(
    double[] maFast,
    double[] maSlow,
    double[] rsi,
    double[] atr
) {}
