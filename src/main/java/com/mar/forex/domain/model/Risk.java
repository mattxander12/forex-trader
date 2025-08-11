package com.mar.forex.domain.model;

import lombok.Data;

@Data
public class Risk {
    private Integer maxDailyLossR = 3;
    private Integer maxConsecLosses = 5;
}
