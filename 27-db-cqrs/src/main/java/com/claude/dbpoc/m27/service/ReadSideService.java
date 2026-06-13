package com.claude.dbpoc.m27.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The QUERY side.
 *
 * Reads come from the READ MODEL (user_order_summary), which is
 * eventually-consistent with the write model. The shape of the read
 * model matches the shape of the query: "give me a user's summary"
 * is a single PK lookup, no joins, no aggregates at read time.
 *
 * In a real system the read model lives elsewhere — Elasticsearch
 * for search, Redis for "hot" cache, ClickHouse for analytics. The
 * sync mechanism is identical: outbox → consumer → upsert into the
 * external system. We keep it in Postgres for the POC so you only
 * need one container.
 */
@Service
public class ReadSideService {

    private final JdbcTemplate jdbc;

    public ReadSideService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> getUserSummary(Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select user_id, order_count, total_revenue, last_order_at, last_event_id " +
            "from user_order_summary where user_id = ?", userId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("userId", userId);
            out.put("note", "Read model has no entry yet — either the user has no orders, " +
                "or the outbox poller hasn't caught up. Compare to /cqrs/raw/{userId}.");
            return out;
        }
        out.putAll(rows.get(0));
        out.put("source", "READ MODEL (user_order_summary) — eventually consistent");
        return out;
    }

    /**
     * Read directly from the WRITE model. Authoritative but expensive.
     * Useful for "compare with the read model and check lag".
     */
    public Map<String, Object> getUserSummaryFromWriteModel(Long userId) {
        Map<String, Object> row = jdbc.queryForMap(
            "select ? as user_id, " +
            "       count(*)        as order_count, " +
            "       coalesce(sum(case when status = 'PLACED' then total " +
            "                         when status = 'CANCELLED' then -total " +
            "                         else 0 end), 0) as total_revenue, " +
            "       max(created_at) as last_order_at " +
            "from orders where user_id = ?", userId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(row);
        out.put("source", "WRITE MODEL (orders) — authoritative, but aggregate is expensive");
        return out;
    }

    public Map<String, Object> outboxBacklog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unprocessed", jdbc.queryForObject(
            "select count(*) from outbox_events where processed_at is null", Long.class));
        out.put("processed", jdbc.queryForObject(
            "select count(*) from outbox_events where processed_at is not null", Long.class));
        out.put("oldestUnprocessedAgeMs", jdbc.queryForObject(
            "select extract(epoch from (now() - min(created_at))) * 1000 " +
            "from outbox_events where processed_at is null",
            Double.class));
        return out;
    }

    /**
     * Rebuild the read model from the outbox. The replay property is
     * the whole reason for the outbox: you can drop the read model,
     * re-process every event in order, and end up with the same state.
     * Useful for fixing projection bugs, adding new fields to the
     * read model, or migrating to a new read store entirely.
     */
    @Transactional
    public Map<String, Object> rebuildReadModel() {
        jdbc.execute("truncate user_order_summary");
        // Also clear processed_at so the poller will re-process.
        jdbc.update("update outbox_events set processed_at = null, attempts = 0");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "READ MODEL TRUNCATED + OUTBOX RESET");
        out.put("note", "The next few poll intervals will rebuild user_order_summary from " +
            "the full outbox stream. This works because the projection is idempotent — " +
            "applying the same events in order yields the same final state. THIS is the " +
            "magic of event sourcing / CQRS — your read model is disposable.");
        return out;
    }
}
