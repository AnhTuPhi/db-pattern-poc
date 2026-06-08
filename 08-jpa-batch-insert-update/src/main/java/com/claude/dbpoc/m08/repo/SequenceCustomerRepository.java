package com.claude.dbpoc.m08.repo;

import com.claude.dbpoc.m08.domain.SequenceCustomer;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SequenceCustomerRepository extends JpaRepository<SequenceCustomer, Long> {

    /**
     * IN-clause bulk update — single statement, modifies N rows by id.
     * Used by the {@code /update/bulk?approach=in-clause} variant.
     */
    @Modifying
    @Query("UPDATE SequenceCustomer c SET c.balance = c.balance + :amt WHERE c.id IN :ids")
    int bumpBalanceByIds(@Param("ids") List<Long> ids, @Param("amt") BigDecimal amt);

    /**
     * JPQL UPDATE — single statement, no entity state involved. Bypasses
     * the persistence context entirely; cheapest possible mass update.
     * Used by the {@code /update/bulk?approach=update-query} variant.
     */
    @Modifying
    @Query("UPDATE SequenceCustomer c SET c.balance = c.balance + :amt WHERE c.country = :country")
    int bumpBalanceByCountry(@Param("country") String country, @Param("amt") BigDecimal amt);
}
