package com.claude.dbpoc.m08;

import com.claude.dbpoc.common.SqlCounter;
import com.claude.dbpoc.m08.domain.SequenceCustomer;
import com.claude.dbpoc.m08.repo.SequenceCustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UPDATE-side bench. Three approaches against the same goal — bump a
 * balance for N customers — with very different SQL counts and dirty-check
 * costs.
 *
 *   approach=per-entity   load N, mutate each, let dirty-check flush  → N UPDATEs
 *   approach=in-clause    @Query UPDATE ... WHERE id IN (:ids)         → 1 UPDATE
 *   approach=update-query @Query UPDATE ... WHERE country = :country   → 1 UPDATE
 *
 * Call /seed?customers=10000 first, then:
 *   curl 'localhost:8208/update/bulk?n=2000&approach=per-entity'
 *   curl 'localhost:8208/update/bulk?n=2000&approach=in-clause'
 *   curl 'localhost:8208/update/bulk?approach=update-query&country=US'
 */
@RestController
@RequestMapping("/update")
public class UpdateController {

    private final SequenceCustomerRepository sequenceRepo;
    private final SqlCounter sqlCounter;

    @PersistenceContext
    private EntityManager em;

    public UpdateController(SequenceCustomerRepository sequenceRepo, SqlCounter sqlCounter) {
        this.sequenceRepo = sequenceRepo;
        this.sqlCounter = sqlCounter;
    }

    @GetMapping("/bulk")
    @Transactional
    public Map<String, Object> bulk(
            @RequestParam(defaultValue = "1000") int n,
            @RequestParam(defaultValue = "per-entity") String approach,
            @RequestParam(defaultValue = "US") String country) {

        sqlCounter.reset();
        long t0 = System.nanoTime();

        int rowsTouched;
        String verdict;

        switch (approach) {
            case "per-entity" -> {
                // Load N entities, mutate each. Hibernate batches the UPDATEs
                // (if order_updates=true and batch_size>=N) but each row still
                // costs a dirty-check entry.
                List<SequenceCustomer> page = sequenceRepo.findAll().stream().limit(n).toList();
                for (SequenceCustomer c : page) {
                    c.setBalance(c.getBalance().add(new BigDecimal("1.00")));
                }
                em.flush();
                rowsTouched = page.size();
                verdict = "Loaded " + page.size() + " entities, mutated each, let auto-flush emit batched UPDATEs. " +
                          "Statements count = number of batches. Pays the dirty-check cost.";
            }
            case "in-clause" -> {
                List<Long> ids = sequenceRepo.findAll().stream().limit(n).map(SequenceCustomer::getId).toList();
                rowsTouched = sequenceRepo.bumpBalanceByIds(ids, new BigDecimal("1.00"));
                verdict = "One UPDATE ... WHERE id IN (:ids). No managed entities mutated → no dirty-check. " +
                          "Best when you have the ID list already.";
            }
            case "update-query" -> {
                rowsTouched = sequenceRepo.bumpBalanceByCountry(country, new BigDecimal("1.00"));
                verdict = "One UPDATE ... WHERE country = :country. Zero entities loaded. The cheapest bulk update; " +
                          "the trade-off is you have to express the predicate in SQL/JPQL rather than in code.";
            }
            default -> throw new IllegalArgumentException("Unknown approach: " + approach + " (use per-entity | in-clause | update-query)");
        }

        long elapsed = System.nanoTime() - t0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approach", approach);
        out.put("rowsTouched", rowsTouched);
        out.put("sqlStatements", sqlCounter.getStatementCount());
        out.put("batches", sqlCounter.getBatchCount());
        out.put("elapsedMs", elapsed / 1_000_000.0);
        out.put("verdict", verdict);
        return out;
    }
}
