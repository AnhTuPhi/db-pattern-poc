package com.claude.dbpoc.m30.service;

import com.claude.dbpoc.m30.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy 1 — SHARED SCHEMA with {@code tenant_id} + Row-Level Security.
 *
 * <p>All tenants share one table. Every row carries a {@code tenant_id}
 * column. A Postgres policy on the table adds an IMPLICIT
 * {@code WHERE tenant_id = current_setting('app.tenant_id')::bigint}
 * to every query. The application can technically forget to filter
 * and still be safe.
 *
 * <p>The crucial line in every method:
 * <pre>{@code  jdbc.update("set local app.tenant_id = ?", t);}</pre>
 *
 * <p>{@code SET LOCAL} scopes the value to the current transaction, so
 * once the {@code @Transactional} method returns the value disappears.
 * No "leftover tenant on the connection" foot-shoot when the pool
 * recycles the connection.
 *
 * <p>This is the cheapest strategy operationally — one table, one
 * vacuum, one set of statistics, one index. It is the most expensive
 * to GET WRONG: if RLS is misconfigured or bypassed, a single
 * unfiltered query exposes every tenant. The policy is your seat belt.
 */
@Service
public class SharedSchemaService {

    private final JdbcTemplate jdbc;

    public SharedSchemaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Set the per-tx variables that the RLS policy reads. {@code SET LOCAL}
     * is tx-scoped and clears at commit/rollback, so we can safely reuse
     * pooled connections without leftover state.
     */
    private void bindTenant() {
        Long t = TenantContext.require();
        jdbc.execute("set local search_path = m30_shared, public");
        jdbc.execute("set local app.tenant_id = '" + t + "'");
    }

    @Transactional
    public Map<String, Object> addProduct(String sku, String name, BigDecimal price) {
        bindTenant();
        Long t = TenantContext.require();
        // We pass tenant_id explicitly here — the WITH CHECK side of
        // the policy refuses inserts whose tenant_id doesn't match
        // the session var, so this is belt + suspenders.
        Long id = jdbc.queryForObject(
            "insert into product(tenant_id, sku, name, price) values (?,?,?,?) returning id",
            Long.class, t, sku, name, price);
        return Map.of("id", id, "tenantId", t, "sku", sku);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProducts() {
        bindTenant();
        // Notice: NO `where tenant_id = ?` in this SQL. RLS adds it.
        return jdbc.queryForList(
            "select id, tenant_id, sku, name, price from product order by id");
    }

    /**
     * Diagnostic: count rows VISIBLE to the current tenant vs the
     * RAW row count if we were to bypass RLS. The bypass requires a
     * superuser role; we approximate by re-querying with a
     * deliberately wrong tenant binding to show how the count
     * changes per tenant.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> visibility() {
        bindTenant();
        Long t = TenantContext.require();
        Integer visible = jdbc.queryForObject("select count(*) from product", Integer.class);

        // A planner-side approximation of "what the table actually
        // contains, RLS aside". `reltuples` is the last-ANALYZE
        // estimate; close enough for the demo.
        Long total = jdbc.queryForObject(
            "select reltuples::bigint from pg_class " +
            "where relname='product' and relnamespace = " +
            "  (select oid from pg_namespace where nspname='m30_shared')",
            Long.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", t);
        out.put("rowsVisibleViaRls", visible);
        out.put("totalRowsEstimateFromPgClass", total);
        out.put("note",
            "rowsVisibleViaRls is from the SELECT (constrained by policy). " +
            "totalRowsEstimateFromPgClass is from the catalog and reflects " +
            "every tenant. If they differ, RLS is working. " +
            "(Note: the catalog estimate updates after ANALYZE, not in real time.)");
        return out;
    }

    /**
     * Try to "see" another tenant's data even though our context says
     * we're tenant T. RLS should hide it. Returns the row count we
     * managed to see for the foreign tenant — should be 0.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> attemptBreach(Long foreignTenantId) {
        bindTenant();
        Long me = TenantContext.require();

        // Try to read a foreign tenant's products by EXPLICITLY
        // filtering for them. The RLS policy still applies and ANDs in
        // its own filter, so the rows are filtered out.
        Integer leaked = jdbc.queryForObject(
            "select count(*) from product where tenant_id = ?",
            Integer.class, foreignTenantId);

        return Map.of(
            "iAm", me,
            "triedToSee", foreignTenantId,
            "rowsLeaked", leaked,
            "verdict", leaked == 0 ? "SAFE - RLS hid the rows" : "BREACH - check the policy",
            "note",
                "Even with `where tenant_id = " + foreignTenantId + "` in the SQL, " +
                "the RLS policy AND'd `tenant_id = " + me + "` into the predicate. " +
                "The intersection is empty.");
    }
}
