package com.claude.dbpoc.m29.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Customer entity, hybrid shape.
 *
 * <p>The columns ({@code tenantId}, {@code email}) are the parts of
 * the customer record that EVERY query touches and that need real
 * constraints (FK targets, NOT NULL, the {@code (tenant_id, email)}
 * uniqueness). They get B-tree indexes and the planner has statistics
 * on them.
 *
 * <p>The {@code profile} JSONB column holds whatever varies. Today
 * that might be:
 * <pre>{@code
 *   { "marketingConsent": true,
 *     "preferredChannel":  "email",
 *     "tags":              ["vip","beta"],
 *     "thirdParty":        { "stripe": "cus_xxx", "intercom": "5..." } }
 * }</pre>
 * Adding a new key tomorrow is a no-op. None of those fields are
 * queried by every endpoint, so paying B-tree storage for them is
 * wasteful — and several of them are sparse, present on 5% of rows.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> profile = new HashMap<>();
}
