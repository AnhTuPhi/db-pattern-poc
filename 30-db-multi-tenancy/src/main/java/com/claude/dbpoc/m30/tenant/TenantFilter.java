package com.claude.dbpoc.m30.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the {@code X-Tenant-Id} header and stores it on {@link TenantContext}
 * for the rest of the request, then clears it on the way out.
 *
 * <p>In a real app you'd resolve the tenant from the JWT subject /
 * the host header / the session, not a raw HTTP header. The header
 * here keeps the demo trivially scriptable from curl.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("X-Tenant-Id");
        if (header != null && !header.isBlank()) {
            try {
                TenantContext.set(Long.parseLong(header.trim()));
            } catch (NumberFormatException ignored) {
                // ignore — the controller layer will reject if it needed a tenant
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
