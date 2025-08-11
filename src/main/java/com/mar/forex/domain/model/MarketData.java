package com.mar.forex.domain.model;

import lombok.Data;

@Data
public class MarketData {
    private Integer lookback = 0;
    private Double atrMult = 1.2;
}
