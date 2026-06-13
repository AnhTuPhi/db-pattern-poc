package com.claude.dbpoc.m30.tenant;

/**
 * Per-request tenant identity, stashed in a {@link ThreadLocal} for
 * the lifetime of the request thread.
 *
 * <p>Filled by {@link TenantFilter} from the {@code X-Tenant-Id} HTTP
 * header. Read by every persistence layer call so isolation is
 * enforced — either by setting the Postgres session variable
 * {@code app.tenant_id} (shared-schema + RLS) or by flipping the
 * connection's {@code search_path} (schema-per-tenant).
 *
 * <p>This is the single piece of infrastructure that BOTH strategies
 * depend on. The two strategies differ only in what they do with
 * the value.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId) { CURRENT.set(tenantId); }

    public static Long get() { return CURRENT.get(); }

    public static Long require() {
        Long t = CURRENT.get();
        if (t == null) {
            throw new IllegalStateException(
                "No tenant in context. The X-Tenant-Id header is required for this endpoint.");
        }
        return t;
    }

    public static void clear() { CURRENT.remove(); }
}
