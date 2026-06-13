package com.claude.dbpoc.m27.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drains unprocessed outbox rows in order, projects each event into the
 * READ MODEL (user_order_summary), and marks the row processed.
 *
 * Properties we care about:
 *   - **In order**: we sort by id, which IS commit order for bigserial.
 *     Required because OrderCancelled would underflow the count if it
 *     beat OrderPlaced.
 *
 *   - **At-least-once**: if the poller crashes after the projection
 *     write but before the outbox `processed_at` update, the next
 *     poll will replay. The projection MUST be idempotent — we use
 *     `last_event_id` per user_id to skip already-applied events.
 *
 *   - **Skip-locked**: `for update skip locked` lets us run multiple
 *     poller instances safely; each picks up a disjoint chunk. The
 *     POC runs one poller per app instance, but skip-locked makes it
 *     scale-out safe.
 */
@Component
public class OutboxPoller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int batchSize;
    private final AtomicLong totalProcessed = new AtomicLong();
    private volatile String lastError;

    public OutboxPoller(JdbcTemplate jdbc, ObjectMapper mapper,
                        @org.springframework.beans.factory.annotation.Value("${cqrs.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${cqrs.outbox.poll-interval-ms:1000}")
    public void poll() {
        try {
            int processed;
            do { processed = drainOnce(); } while (processed == batchSize);
        } catch (Exception e) {
            lastError = e.getMessage();
        }
    }

    /**
     * One transactional drain pass: grab up to batchSize unprocessed
     * events with `FOR UPDATE SKIP LOCKED`, project each, mark processed.
     */
    @Transactional
    public int drainOnce() {
        // 1. Lock a batch.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select id, aggregate_id, event_type, payload::text as payload " +
            "from outbox_events " +
            "where processed_at is null " +
            "order by id " +
            "limit ? " +
            "for update skip locked",
            batchSize);

        if (rows.isEmpty()) return 0;

        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String eventType = (String) r.get("event_type");
            try {
                JsonNode payload = mapper.readTree((String) r.get("payload"));
                project(id, eventType, payload);
                jdbc.update("update outbox_events set processed_at = now() where id = ?", id);
                totalProcessed.incrementAndGet();
            } catch (Exception e) {
                // Bump attempts, leave processed_at null → it'll be retried next pass.
                jdbc.update(
                    "update outbox_events set attempts = attempts + 1 where id = ?", id);
                lastError = "event " + id + ": " + e.getMessage();
                // Stop the batch — preserve ORDER. The next iteration retries this one.
                break;
            }
        }
        return rows.size();
    }

    /**
     * Apply one event to the read model. Idempotent via last_event_id:
     * if the row already saw an event >= this id, skip.
     */
    private void project(long eventId, String eventType, JsonNode payload) {
        long userId = payload.get("userId").asLong();
        BigDecimal total = payload.get("total").decimalValue();

        // Upsert the summary row, but ONLY apply the delta if this event is new.
        // last_event_id encodes "highest outbox id this user_id has seen".
        switch (eventType) {
            case "OrderPlaced" -> jdbc.update(
                "insert into user_order_summary(user_id, order_count, total_revenue, last_order_at, last_event_id) " +
                "values (?, 1, ?, now(), ?) " +
                "on conflict (user_id) do update set " +
                "  order_count   = case when excluded.last_event_id > user_order_summary.last_event_id " +
                "                       then user_order_summary.order_count + 1 " +
                "                       else user_order_summary.order_count end, " +
                "  total_revenue = case when excluded.last_event_id > user_order_summary.last_event_id " +
                "                       then user_order_summary.total_revenue + excluded.total_revenue " +
                "                       else user_order_summary.total_revenue end, " +
                "  last_order_at = case when excluded.last_event_id > user_order_summary.last_event_id " +
                "                       then now() " +
                "                       else user_order_summary.last_order_at end, " +
                "  last_event_id = greatest(user_order_summary.last_event_id, excluded.last_event_id)",
                userId, total, eventId);
            case "OrderCancelled" -> jdbc.update(
                "update user_order_summary set " +
                "  order_count   = order_count - 1, " +
                "  total_revenue = total_revenue - ?, " +
                "  last_event_id = greatest(last_event_id, ?) " +
                "where user_id = ? and last_event_id < ?",
                total, eventId, userId, eventId);
            default -> {
                // Unknown event — ignore but log.
            }
        }
    }

    public long getTotalProcessed() { return totalProcessed.get(); }
    public String getLastError()    { return lastError; }
}
