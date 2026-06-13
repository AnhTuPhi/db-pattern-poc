package com.claude.dbpoc.m30.service;

import com.claude.dbpoc.m30.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Strategy 2 — SCHEMA-PER-TENANT.
 *
 * <p>Each tenant has its own Postgres schema ({@code m30_t_1},
 * {@code m30_t_2}, …) with its own copy of every table. There is no
 * {@code tenant_id} column; isolation comes from the schema boundary
 * itself.
 *
 * <p>Per-request setup: set the connection's {@code search_path} so
 * that unqualified table names resolve to the tenant's schema. With
 * {@code SET LOCAL}, the change scopes to the current transaction
 * and reverts on commit — safe to use against a shared connection
 * pool.
 *
 * <p>Tradeoffs vs the shared-schema strategy:
 * <ul>
 *   <li>+ Isolation is structural: a forgotten filter can't leak
 *       another tenant's data. There is nothing TO leak — the table
 *       isn't reachable on this search_path.</li>
 *   <li>+ Per-tenant {@code pg_dump}, drop, restore — trivial.</li>
 *   <li>- Schema explosion: 10k tenants = 10k tables. {@code pg_catalog}
 *       gets unhappy past tens of thousands of objects.</li>
 *   <li>- Migrations replicated N times. You'll write a runner that
 *       loops over every tenant schema.</li>
 *   <li>- Cross-tenant analytics needs UNION ALL across schemas.</li>
 * </ul>
 */
@Service
public class SchemaPerTenantService {

    private final JdbcTemplate jdbc;

    public SchemaPerTenantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Switch the active schema for the current transaction. */
    private void bindSchema() {
        Long t = TenantContext.require();
        // Identifier — can't bind as a parameter, must concat. The
        // tenant id has been parsed as a Long upstream, so it's safe.
        jdbc.execute("set local search_path = m30_t_" + t + ", public");
    }

    @Transactional
    public Map<String, Object> addProduct(String sku, String name, BigDecimal price) {
        bindSchema();
        Long t = TenantContext.require();
        Long id = jdbc.queryForObject(
            "insert into product(sku, name, price) values (?,?,?) returning id",
            Long.class, sku, name, price);
        return Map.of("id", id, "tenantId", t, "schema", "m30_t_" + t);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProducts() {
        bindSchema();
        return jdbc.queryForList("select id, sku, name, price from product order by id");
    }

    @Transactional
    public Map<String, Object> onboardTenant(Long tenantId) {
        jdbc.execute("set local search_path = m30_shared, public");
        jdbc.update("select create_tenant_schema(?)", tenantId);
        return Map.of(
            "tenantId", tenantId,
            "schema", "m30_t_" + tenantId,
            "note",
                "Tenant schema created with the standard tables. " +
                "Run cross-tenant migrations by looping over m30_t_* schemas.");
    }

    /**
     * Cross-tenant analytics in this strategy means a UNION ALL across
     * every tenant schema. Expensive at scale and a real reason the
     * shared-schema strategy wins for analytics-heavy workloads.
     *
     * <p>This method returns the row count per tenant schema by
     * generating an exact {@code count(*)} for each one — the kind of
     * loop a "cross-tenant" report has to do in this topology. For
     * thousands of tenants, you'd materialize this into an analytics
     * table via outbox (m27) rather than scan all schemas on demand.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> globalProductCount() {
        List<String> schemas = jdbc.queryForList(
            "select nspname from pg_namespace " +
            "where nspname like 'm30\\_t\\_%' order by nspname",
            String.class);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String s : schemas) {
            Long c = jdbc.queryForObject(
                "select count(*) from " + s + ".product", Long.class);
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("schema", s);
            row.put("rows", c);
            out.add(row);
        }
        return out;
    }
}
