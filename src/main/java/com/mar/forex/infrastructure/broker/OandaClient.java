package com.mar.forex.infrastructure.broker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mar.forex.config.AppProperties;

@Slf4j
@Component
@RequiredArgsConstructor
public class OandaClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final AppProperties props;
    private final ObjectMapper mapper;

    private String base() {
        // e.g. https://api-fxpractice.oanda.com/v3
        return props.getOanda().getUrl();
    }
    private String authHeader() {
        return "Bearer " + props.getOanda().getApiKey();
    }
    private String accountId() {
        return props.getOanda().getAccountId();
    }

    /** GET /instruments/{instrument}/candles */
    public String getCandles(String instrument, String granularity, int count) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(base() + "/instruments/" + instrument + "/candles"))
                             .newBuilder()
                             .addQueryParameter("granularity", granularity)
                             .addQueryParameter("count", String.valueOf(count))
                             .addQueryParameter("price", "M")
                             .build();

        Request req = new Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .get()
            .build();

        try (Response r = http.newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                log.error("OANDA candles failed: HTTP {} {}", r.code(), body);
                throw new IOException("HTTP " + r.code() + " " + body);
            }
            return body;
        }
    }

    /** GET /accounts/{accountId}/pricing?instruments=EUR_USD,GBP_USD */
    public String getPricing(String instrumentsCsv) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(base() + "/accounts/" + accountId() + "/pricing"))
                             .newBuilder()
                             .addQueryParameter("instruments", instrumentsCsv)
                             .build();

        Request req = new Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .get()
            .build();

        try (Response r = http.newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                log.error("OANDA pricing failed: HTTP {} {}", r.code(), body);
                throw new IOException("HTTP " + r.code() + " " + body);
            }
            return body;
        }
    }

    /** POST /accounts/{accountId}/orders (market order with optional SL/TP) */
    public String placeMarketOrder(String instrument, int units, Double slPrice, Double tpPrice) throws IOException {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("type", "MARKET");
        order.put("instrument", instrument);
        order.put("units", String.valueOf(units));   // OANDA expects string for units
        order.put("timeInForce", "FOK");
        if (slPrice != null) order.put("stopLossOnFill", Map.of("price", String.format("%.5f", slPrice)));
        if (tpPrice != null) order.put("takeProfitOnFill", Map.of("price", String.format("%.5f", tpPrice)));

        String body = mapper.writeValueAsString(Map.of("order", order));

        Request req = new Request.Builder()
            .url(base() + "/accounts/" + accountId() + "/orders")
            .header("Authorization", authHeader())
            .post(RequestBody.create(body, JSON))
            .build();

        try (Response r = http.newCall(req).execute()) {
            String resp = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                log.error("OANDA order failed: HTTP {} {}", r.code(), resp);
                throw new IOException("HTTP " + r.code() + " " + resp);
            }
            return resp;
        }
    }

    public String getCandles(String instrument, String granularity, int count, java.time.Instant to) throws java.io.IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(base() + "/instruments/" + instrument + "/candles"))
                             .newBuilder()
                             .addQueryParameter("granularity", granularity)
                             .addQueryParameter("count", String.valueOf(count))
                             .addQueryParameter("price", "M")
                             .addQueryParameter("smooth", String.valueOf(false))
                             .addQueryParameter("to", to.toString())
                             .build();

        okhttp3.Request req = new okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .build();

        try (Response r = http.newCall(req).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                log.error("OANDA candles failed: HTTP {} {}", r.code(), body);
                throw new IOException("HTTP " + r.code() + " " + body);
            }
            return body;
        }
    }
}
