package com.mar.forex.domain.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class Trading {
    @NotBlank private String instrument = "EUR_USD";
    @NotBlank private String granularity = "M5";
    @Min(1) private int fastSma = 10;
    @Min(1) private int slowSma = 20;
    @Min(0) private int warmup = 15;

    @Positive private double maxSpreadPips = 1.2;
    @Positive private int unitsCap = 10000;

    @Pattern(regexp = "SMA|EMA|HYBRID")
    private String maType = "SMA";
}
