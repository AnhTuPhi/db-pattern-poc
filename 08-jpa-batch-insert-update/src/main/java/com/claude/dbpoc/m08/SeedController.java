package com.claude.dbpoc.m08;

import com.claude.dbpoc.m08.domain.SequenceCustomer;
import com.claude.dbpoc.m08.repo.AssignedCustomerRepository;
import com.claude.dbpoc.m08.repo.CustomerOrderRepository;
import com.claude.dbpoc.m08.repo.IdentityCustomerRepository;
import com.claude.dbpoc.m08.repo.SequenceCustomer100Repository;
import com.claude.dbpoc.m08.repo.SequenceCustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-populates the SequenceCustomer table with N rows for the UPDATE-side
 * benchmarks. Wipes everything else, since the /bench endpoint re-inserts
 * fresh data on every call.
 *
 * The UPDATE bench needs existing rows to mutate, so seed with
 *   POST /seed?customers=10000
 * before calling /update/bulk.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final IdentityCustomerRepository identityRepo;
    private final SequenceCustomerRepository sequenceRepo;
    private final SequenceCustomer100Repository sequence100Repo;
    private final AssignedCustomerRepository assignedRepo;
    private final CustomerOrderRepository customerOrderRepo;

    @PersistenceContext
    private EntityManager em;

    public SeedController(IdentityCustomerRepository identityRepo,
                          SequenceCustomerRepository sequenceRepo,
                          SequenceCustomer100Repository sequence100Repo,
                          AssignedCustomerRepository assignedRepo,
                          CustomerOrderRepository customerOrderRepo) {
        this.identityRepo = identityRepo;
        this.sequenceRepo = sequenceRepo;
        this.sequence100Repo = sequence100Repo;
        this.assignedRepo = assignedRepo;
        this.customerOrderRepo = customerOrderRepo;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> seed(@RequestParam(defaultValue = "10000") int customers) {
        // Wipe in any order — no FKs across the bench tables.
        identityRepo.deleteAllInBatch();
        sequenceRepo.deleteAllInBatch();
        sequence100Repo.deleteAllInBatch();
        assignedRepo.deleteAllInBatch();
        customerOrderRepo.deleteAllInBatch();

        // Country code rotates through 4 values so the UPDATE-by-country
        // demo has a realistic cardinality (~25% of rows match).
        String[] countries = { "US", "GB", "DE", "FR" };
        for (int i = 0; i < customers; i++) {
            sequenceRepo.save(SequenceCustomer.builder()
                .name("seed-" + i)
                .email("seed" + i + "@example.com")
                .country(countries[i % countries.length])
                .createdAt(Instant.now())
                .balance(new BigDecimal("100.00"))
                .build());
            if (i > 0 && i % 50 == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sequenceCustomers", customers);
        out.put("note", "Seed complete. Run /update/bulk?n=N&approach=X to bench the UPDATE-side variants.");
        return out;
    }

    @DeleteMapping
    @Transactional
    public Map<String, Object> wipe() {
        identityRepo.deleteAllInBatch();
        sequenceRepo.deleteAllInBatch();
        sequence100Repo.deleteAllInBatch();
        assignedRepo.deleteAllInBatch();
        customerOrderRepo.deleteAllInBatch();
        return Map.of("wiped", true);
    }
}
