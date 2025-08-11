package com.mar.forex.domain.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class Paper {
    private boolean enabled = true;
    @Pattern(regexp = "ATR|PIPS")
    private String mode = "ATR";

    @Positive
    private double risk = 1.0;     // ATR multiples (ATR mode) or pips (PIPS mode)

    @Positive
    private double rr = 1.5;       // TP distance = risk * rr

    @Min(1)
    private int atrPeriod = 14;

    @Positive
    private double pips = 10.0;    // only used in PIPS mode

    @Min(1)
    private int maxOpenPerInstrument = 1;

    @Positive
    private int startBalance = 10000;

    @Positive
    private int leverage = 50;

    @Positive
    private double stopAtrMulti = 10;
}
