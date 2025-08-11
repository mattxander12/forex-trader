package com.mar.forex.domain.model;

import lombok.Data;

@Data
public class Filter {
    private double evMargin;
    private double evMarginR;
    private int atrWindow;
    private int atrPercentile;
    private String session;
    private int rsiLong;
    private int rsiShort;
    private boolean onePerDay;
}
