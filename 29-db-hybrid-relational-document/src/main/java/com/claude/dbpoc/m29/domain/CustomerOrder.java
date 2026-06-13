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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The interesting case for the hybrid pattern: an order with a
 * heterogeneous line-item array.
 *
 * <p>The order itself has columns ({@code customerId} FK, {@code status},
 * {@code total}, {@code placedAt}) — the things you index and aggregate.
 *
 * <p>{@code items} is a JSONB array of line items. Each line item
 * has slightly different fields depending on the product type:
 * <pre>{@code
 *   [ { "type":"book",     "sku":"B-1", "qty":2, "isbn":"978-...",  "price":19.99 },
 *     { "type":"electronic","sku":"E-1","qty":1, "serial":"SN-...", "warranty_mo":24, "price":499.00 },
 *     { "type":"digital",   "sku":"D-1","qty":1, "license":"AB-XX-YY","expires":"2027-01-01", "price":9.99 } ]
 * }</pre>
 *
 * In a pure-relational world this is the polymorphic table mess
 * ({@code line_item} table with 20 nullable type-specific columns,
 * or one table per product type with a discriminator join). In a
 * pure-document world, you wouldn't have the order's {@code total}
 * indexed for the financial reports. Hybrid splits the difference.
 */
@Entity
@Table(name = "customer_order")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total;

    @Column(name = "placed_at", nullable = false)
    private OffsetDateTime placedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> items = new ArrayList<>();
}
