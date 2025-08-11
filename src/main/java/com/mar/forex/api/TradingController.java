package com.mar.forex.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mar.forex.config.AppProperties;
import com.mar.forex.domain.model.Candle;
import com.mar.forex.domain.model.JobResponse;
import com.mar.forex.infrastructure.messaging.SseHub;
import com.mar.forex.service.DataService;
import com.mar.forex.service.MLService;
import com.mar.forex.service.BacktesterService;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class TradingController {

    private final AppProperties props;
    private final MLService mlService;
    private final BacktesterService backtesterService;
    private final DataService dataService;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    private final SseHub hub;

    private final ObjectMapper objectMapper;

    @GetMapping("/config")
    public AppProperties getConfig() {
        return props;
    }

    @PostMapping("/train")
    public JobResponse train(
            @RequestBody(required = false) JsonNode body
    ) {
        AppProperties incoming = (body != null) ? objectMapper.convertValue(body, AppProperties.class) : null;

        if (incoming != null) {
            try {
                if (incoming.getTrading() != null) {
                    objectMapper.updateValue(props.getTrading(), incoming.getTrading());
                }
                if (incoming.getPaper() != null) {
                    objectMapper.updateValue(props.getPaper(), incoming.getPaper());
                }
                if (incoming.getMarketData() != null) {
                    objectMapper.updateValue(props.getMarketData(), incoming.getMarketData());
                }
                if (incoming.getExecution() != null) {
                    objectMapper.updateValue(props.getExecution(), incoming.getExecution());
                }
                if (incoming.getRisk() != null) {
                    objectMapper.updateValue(props.getRisk(), incoming.getRisk());
                }
                if (incoming.getFilter() != null) {
                    objectMapper.updateValue(props.getFilter(), incoming.getFilter());
                }
            } catch (JsonMappingException e) {
                throw new RuntimeException(e);
            }
        }

        String jobId = "train-" + System.currentTimeMillis();
        pool.submit(() -> {
            try {
                List<Candle> candles = dataService.loadCandlesPaged(
                    props.getTrading().getInstrument(),
                    props.getTrading().getGranularity(),
                    candlesCountFromYears(props.getTraining().getYears(),
                        props.getTrading().getGranularity(), props.getTrading().getWarmup())
                );
                mlService.trainClassifier(
                    candles,
                    props.getTrading().getInstrument(),
                    props.getTrading().getFastSma(),
                    props.getTrading().getSlowSma(),
                    props.getPaper().getAtrPeriod()
                );
            } catch (Exception e) {
                emit(jobId, e.getMessage());
            } finally {
                complete(jobId);
            }
        });

        return new JobResponse(jobId);
    }

    @PostMapping("/backtest")
    public JobResponse backtest(
        @RequestBody(required = false) JsonNode body
    ) {
        AppProperties incoming = (body != null) ? objectMapper.convertValue(body, AppProperties.class) : null;

        if (incoming != null) {
            try {
                if (incoming.getTrading() != null) {
                    objectMapper.updateValue(props.getTrading(), incoming.getTrading());
                }
                if (incoming.getPaper() != null) {
                    objectMapper.updateValue(props.getPaper(), incoming.getPaper());
                }
                if (incoming.getMarketData() != null) {
                    objectMapper.updateValue(props.getMarketData(), incoming.getMarketData());
                }
                if (incoming.getExecution() != null) {
                    objectMapper.updateValue(props.getExecution(), incoming.getExecution());
                }
                if (incoming.getRisk() != null) {
                    objectMapper.updateValue(props.getRisk(), incoming.getRisk());
                }
                if (incoming.getFilter() != null) {
                    objectMapper.updateValue(props.getFilter(), incoming.getFilter());
                }
            } catch (JsonMappingException e) {
                throw new RuntimeException(e);
            }
        }

        String jobId = "bt-" + System.currentTimeMillis();
        pool.submit(() -> {
            try {
                List<Candle> candles = dataService.loadCandlesPaged(
                    props.getTrading().getInstrument(),
                    props.getTrading().getGranularity(),
                    candlesCountFromYears(props.getTraining().getYears(), props.getTrading().getGranularity(),
                        props.getTrading().getWarmup())
                );
                backtesterService.runForUI(candles, jobId);
            } catch (Exception e) {
                emit(jobId, e.getMessage());
            } finally {
                complete(jobId);
            }
        });
        return new JobResponse(jobId);
    }

    @GetMapping(path = "/stream/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPath(@PathVariable String jobId) {
        return hub.connect(jobId);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String jobId) {
        return hub.connect(jobId);
    }

    @GetMapping(value = "/result/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getResult(@PathVariable String jobId) {
        return hub.getLast(jobId);
    }

    private void emit(String jobId, String data) {
        hub.emit(jobId, "error", data);
    }

    private void complete(String jobId) {
        hub.complete(jobId);
    }

    private int candlesCountFromYears(int years, String granularity, int warmup) {
        int perDay;
        try {
            if (granularity.startsWith("M")) {
                int minutes = Integer.parseInt(granularity.substring(1));
                perDay = (24 * 60) / Math.max(1, minutes);
            } else if (granularity.startsWith("H")) {
                int hours = Integer.parseInt(granularity.substring(1));
                perDay = 24 / Math.max(1, hours);
            } else if (granularity.equals("D")) {
                perDay = 1;
            } else {
                // default assume M5
                perDay = (24 * 60) / 5;
            }
        } catch (Exception e) {
            // fallback to M5 if parsing fails
            perDay = (24 * 60) / 5;
        }
        int base = years * 365 * perDay;
        int buffer = Math.max(500, warmup * 5);
        return base + buffer;
    }
}
