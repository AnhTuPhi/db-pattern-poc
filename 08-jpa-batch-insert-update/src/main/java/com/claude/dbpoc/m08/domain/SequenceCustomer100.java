package com.claude.dbpoc.m08.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Same as {@link SequenceCustomer} but with allocationSize=100. Used to
 * demonstrate the hop-count drop on the sequence: with batch_size=50, this
 * means one sequence round-trip per TWO batches instead of per batch.
 *
 * On a fast network the gain is small. On a high-latency link or a clustered
 * sequence (Oracle RAC) it's measurable. The contrast vs SequenceCustomer
 * (allocationSize=50) makes the "sequence hops are a real cost" point concrete.
 */
@Entity
@Table(name = "sequence_customer_100")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SequenceCustomer100 {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_customer_100_gen")
    @SequenceGenerator(
        name = "seq_customer_100_gen",
        sequenceName = "seq_customer_100",
        allocationSize = 100
    )
    private Long id;

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
}
