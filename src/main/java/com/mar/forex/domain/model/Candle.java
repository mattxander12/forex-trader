package com.mar.forex.domain.model;

import lombok.Data;
import java.time.Instant;

@Data
public class Candle {
    public Instant time;
    public double open, high, low, close;
    public long volume;
    public Candle(Instant time, double open, double high, double low, double close, long volume) {
        this.time = time; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
    }
}
