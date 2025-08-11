package com.mar.forex.domain.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class PaperTrade {
    public enum Side { BUY, SELL }
    public enum Status { OPEN, WON, LOST }

    private String instrument;
    private Side side;

    private Instant openedAt;
    private double entry;
    private double stop;
    private double take;

    private Instant closedAt;
    private Double exit;
    private Status status;
    private String reason;   // "TP" or "SL"

    // optional helpers for backtest indexing
    private int openIndex;
}
