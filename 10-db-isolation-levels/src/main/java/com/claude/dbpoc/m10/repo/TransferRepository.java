package com.claude.dbpoc.m10.repo;

import com.claude.dbpoc.m10.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

/**
 * Just enough surface to drive the phantom-read demo. The count() query
 * is what produces the phantom — it doesn't lock the rows it counts, so
 * a concurrent INSERT into the matching range is visible (or not,
 * depending on isolation).
 */
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Query("select count(t) from Transfer t where t.amount > :threshold")
    long countByAmountGreaterThan(@Param("threshold") BigDecimal threshold);
}
