package com.mar.forex.service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.infrastructure.broker.OandaClient;

@Service
public class DataService {
    private static final int MAX_BATCH = 4900; // under OANDA's limit

    private final OandaClient client;
    private final ObjectMapper om = new ObjectMapper();

    public DataService(OandaClient client) { this.client = client; }

    /** If count is small, do a single call; else delegate to paged fetch. */
    public List<Candle> loadCandles(String instrument, String granularity, int count) throws IOException {
        if (count <= MAX_BATCH) {
            String json = client.getCandles(instrument, granularity, count);
            return parseCandles(json);
        }
        return loadCandlesPaged(instrument, granularity, count);
    }

    /** Pull candles in batches using the `to` param to walk backwards. */
    public List<Candle> loadCandlesPaged(String instrument, String granularity, int total) throws IOException {
        List<Candle> all = new ArrayList<>(Math.max(total, 0));
        if (total <= 0) return all;

        Instant to = null; // null = most recent; then page backwards
        while (all.size() < total) {
            int batch = Math.min(MAX_BATCH, total - all.size());

            String json = (to == null)
                ? client.getCandles(instrument, granularity, batch)
                : client.getCandles(instrument, granularity, batch, to);

            List<Candle> got = parseCandles(json);
            if (got.isEmpty()) break;

            // Each new page is earlier-in-time; prepend to keep oldest->newest overall.
            all.addAll(0, got);

            // Next page ends just before the oldest candle we have.
            Instant oldest = got.get(0).getTime();
            to = oldest.minusSeconds(1);

            if (got.size() < batch) break; // hit start of history
        }

        // If we over-collected, keep the newest `total` while preserving order.
        if (all.size() > total) {
            int from = all.size() - total;
            return new ArrayList<>(all.subList(from, all.size()));
        }
        return all;
    }

    private List<Candle> parseCandles(String json) throws IOException {
        JsonNode root = om.readTree(json).get("candles");
        if (root == null || !root.isArray()) return Collections.emptyList();
        List<Candle> out = new ArrayList<>();
        for (JsonNode c : root) {
            if (!c.get("complete").asBoolean()) continue;
            JsonNode mid = c.get("mid");
            out.add(new Candle(
                Instant.parse(c.get("time").asText()),
                mid.get("o").asDouble(),
                mid.get("h").asDouble(),
                mid.get("l").asDouble(),
                mid.get("c").asDouble(),
                c.has("volume") ? c.get("volume").asLong() : 0L
            ));
        }
        return out;
    }
}
