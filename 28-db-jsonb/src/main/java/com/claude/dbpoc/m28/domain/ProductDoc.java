package com.claude.dbpoc.m28.domain;

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

import java.util.Map;

/**
 * The "schemaless" shape: one JSONB column. We get flexibility — any
 * attribute can show up — at the cost of moving every type and shape
 * guarantee out of the database and into the calling code. The CHECK
 * constraints on the table enforce a minimum (sku string, price number),
 * but everything past that is on us.
 *
 * Hibernate 6 maps {@code Map<String, Object>} to {@code jsonb} via
 * {@code @JdbcTypeCode(SqlTypes.JSON)}. No hypersistence-utils annotation
 * needed for the simple case.
 */
@Entity
@Table(name = "product_doc")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data;
}
