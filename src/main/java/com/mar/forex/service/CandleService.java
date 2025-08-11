package com.mar.forex.service;

import java.util.List;
import com.mar.forex.domain.model.Candle;

public interface CandleService {
    List<Candle> loadHistorical(String instrument, String granularity, int years) throws Exception;
}
