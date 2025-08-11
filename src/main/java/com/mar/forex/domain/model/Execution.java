package com.mar.forex.domain.model;

import lombok.Data;

@Data
public class Execution {
    private Double signalThreshold = 0.25;
    private Double slippagePips = 0.1;
    private Double commissionPips = 0.0;
}
