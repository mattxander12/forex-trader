package com.mar.forex.infrastructure.broker;

import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mar.forex.service.CandleService;
import com.mar.forex.domain.model.Candle;

@Service
@RequiredArgsConstructor
public class OandaCandleService implements CandleService {

    private final OandaClient oanda;
    private final ObjectMapper mapper;

    @Override
    public List<Candle> loadHistorical(String instrument, String granularity, int years) throws Exception {
        // Pull a big chunk: rough heuristic — 365d * 24h * 12 (5-min bars per hour) for years
        // Adjust if you prefer paging; OANDA max count per call is large but not infinite.
        int count = Math.min(5000 * years, 20000); // cap to keep requests sane
        String json = oanda.getCandles(instrument, granularity, count);

        OandaCandlesResponse resp = mapper.readValue(json, OandaCandlesResponse.class);
        List<Candle> out = new ArrayList<>(resp.candles.size());
        for (var c : resp.candles) {
            // Use MID prices; OANDA sends strings
            double h = Double.parseDouble(c.mid.h);
            double l = Double.parseDouble(c.mid.l);
            double o = Double.parseDouble(c.mid.o);
            double cl= Double.parseDouble(c.mid.c);
            long vol  = 0L; // OANDA can return volume; if you parse it, replace this with the actual value

            Candle cc = new Candle(
                Instant.parse(c.time),
                o, h, l, cl,
                vol
            );
            out.add(cc);
        }
        return out;
    }

    // --- minimal DTOs for OANDA response ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OandaCandlesResponse {
        public List<OandaCandle> candles;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OandaCandle {
        public String time;
        public Mid mid;
        public boolean complete;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Mid {
        public String o, h, l, c;
    }
}
