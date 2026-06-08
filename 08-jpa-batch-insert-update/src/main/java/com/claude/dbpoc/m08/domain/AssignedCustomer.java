package com.claude.dbpoc.m08.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Application-assigned PK (UUID). Because the app picks the ID before
 * Hibernate ever sees the entity, there is no "wait for the DB to tell us
 * the PK" problem — JDBC batching just works, on every DB, with no special
 * sequence or IDENTITY plumbing.
 *
 * Trade-offs vs sequences:
 *   + Trivially correct for bulk inserts; no IDENTITY/sequence config needed
 *   + Globally unique (good for sharded systems, idempotent writes)
 *   - 16 bytes vs 8 bytes per PK (index size, FK columns)
 *   - Random UUIDs hurt B-tree insert locality (use ULID/UUIDv7 in prod)
 *
 * For this bench we use random UUIDs because the *insert-throughput* delta
 * vs sequence is what matters; index locality is a separate (also worth
 * measuring, but separate) topic.
 */
@Entity
@Table(name = "assigned_customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignedCustomer {

    @Id
    @Column(length = 36)
    private String id; // String form keeps the bench DB-agnostic (UUID type varies)

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
