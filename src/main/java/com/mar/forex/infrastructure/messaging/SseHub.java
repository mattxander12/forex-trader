package com.mar.forex.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A SseHub
 *
 * Maintains one emitter per jobId, a small replay buffer so late subscribers can
 * catch up, periodic heartbeats to keep proxies warm, and a graceful completion
 * that allows the final event to flush before closing.
 */
@Slf4j
@Component
public class SseHub {
    // how many past events to retain per job for late joiners
    private static final int REPLAY_LIMIT = 32;
    // heartbeat period in seconds
    private static final long HEARTBEAT_SECS = 10;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Deque<Event>> buffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public SseHub() {
        // send heartbeats periodically so the connection isn't idled out by proxies
        scheduler.scheduleAtFixedRate(this::heartbeatAll, HEARTBEAT_SECS, HEARTBEAT_SECS, TimeUnit.SECONDS);
    }

    public SseEmitter connect(String jobId) {
        var emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitter.onTimeout(() -> emitters.remove(jobId));
        emitter.onCompletion(() -> emitters.remove(jobId));
        emitters.put(jobId, emitter);

        // Immediately replay any buffered events for this jobId
        var buf = buffers.get(jobId);
        if (buf != null) {
            for (var ev : buf) {
                try {
                    emitter.send(SseEmitter.event().name(ev.name()).data(ev.data(), MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    log.warn("SSE replay failed: {}", e.getMessage());
                    break;
                }
            }
        }
        return emitter;
    }

    public SseEmitter emitter(String jobId) {
        return emitters.computeIfAbsent(jobId, k -> new SseEmitter(Duration.ofMinutes(30).toMillis()));
    }

    /**
     * Returns the JSON payload of the most recently emitted event for this job id.
     * Falls back to an empty JSON object if nothing has been sent yet.
     */
    public String getLast(String jobId) {
        var buf = buffers.get(jobId);
        if (buf == null || buf.isEmpty()) return "{}";
        var last = buf.peekLast();
        return last != null ? last.data() : "{}";
    }

    public void emit(String jobId, String eventType, String data) {
        // store in replay buffer
        var buf = buffers.computeIfAbsent(jobId, k -> new ArrayDeque<>(REPLAY_LIMIT));
        if (buf.size() == REPLAY_LIMIT) buf.removeFirst();
        buf.addLast(new Event(eventType, data));

        var em = emitters.get(jobId);
        if (em == null) return;
        try {
            em.send(SseEmitter.event().name(eventType).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("SSE send failed: {}", e.getMessage());
            emitters.remove(jobId);
        }
    }

    public void complete(String jobId) {
        // allow a short delay so the last event can flush before completion
        scheduler.schedule(() -> {
            var em = emitters.remove(jobId);
            if (em != null) {
                try {
                    em.send(SseEmitter.event().name("done").data("{}", MediaType.APPLICATION_JSON));
                } catch (Exception ignore) { }
                try { em.complete(); } catch (Exception ignore) { }
            }
            buffers.remove(jobId);
        }, 500, TimeUnit.MILLISECONDS);
    }

    private void heartbeatAll() {
        emitters.forEach((jobId, em) -> {
            try {
                em.send(SseEmitter.event().name("heartbeat").data("{}", MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                log.debug("SSE heartbeat failed for {}: {}", jobId, e.getMessage());
                emitters.remove(jobId);
            }
        });
    }

    private record Event(String name, String data) {}
}
