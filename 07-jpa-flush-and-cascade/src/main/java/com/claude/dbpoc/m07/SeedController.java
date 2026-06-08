package com.claude.dbpoc.m07;

import com.claude.dbpoc.m07.domain.Account;
import com.claude.dbpoc.m07.domain.BadChild;
import com.claude.dbpoc.m07.domain.BadParent;
import com.claude.dbpoc.m07.domain.Customer;
import com.claude.dbpoc.m07.domain.GoodChild;
import com.claude.dbpoc.m07.domain.GoodParent;
import com.claude.dbpoc.m07.domain.Transaction;
import com.claude.dbpoc.m07.repo.AccountRepository;
import com.claude.dbpoc.m07.repo.BadParentRepository;
import com.claude.dbpoc.m07.repo.CustomerRepository;
import com.claude.dbpoc.m07.repo.GoodParentRepository;
import com.claude.dbpoc.m07.repo.TransactionRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Populates Bad/Good parent-child sets plus a configurable Customer ↔ Account ↔
 * Transaction graph for the dirty-checking demos.
 *
 * One call to POST /seed wipes the previous data and rebuilds. All cascade /
 * flush / dirty-check endpoints assume seed has been run first.
 */
@RestController
@RequestMapping("/seed")
public class SeedController {

    private final BadParentRepository badParentRepo;
    private final GoodParentRepository goodParentRepo;
    private final CustomerRepository customerRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository transactionRepo;

    public SeedController(BadParentRepository badParentRepo,
                          GoodParentRepository goodParentRepo,
                          CustomerRepository customerRepo,
                          AccountRepository accountRepo,
                          TransactionRepository transactionRepo) {
        this.badParentRepo = badParentRepo;
        this.goodParentRepo = goodParentRepo;
        this.customerRepo = customerRepo;
        this.accountRepo = accountRepo;
        this.transactionRepo = transactionRepo;
    }

    /**
     * @param parentCount     # of BadParent + GoodParent each, default 5
     * @param childrenPer     # of children per parent, default 4
     * @param customers       # of customers, default 100
     * @param accountsPer     # of accounts per customer, default 3
     * @param transactionsPer # of transactions per account, default 5
     */
    @PostMapping
    @Transactional
    public Map<String, Object> seed(
            @RequestParam(defaultValue = "5") int parentCount,
            @RequestParam(defaultValue = "4") int childrenPer,
            @RequestParam(defaultValue = "100") int customers,
            @RequestParam(defaultValue = "3") int accountsPer,
            @RequestParam(defaultValue = "5") int transactionsPer) {

        // Wipe in reverse FK order so we don't fight orphan constraints.
        transactionRepo.deleteAllInBatch();
        accountRepo.deleteAllInBatch();
        customerRepo.deleteAllInBatch();
        // bad/good child rows go via cascade — using deleteAll on parents takes
        // them with it (BadParent uses cascade=ALL, so children evaporate cleanly).
        badParentRepo.deleteAll();
        goodParentRepo.deleteAll();

        // BadParent tree — exercises the cascade=ALL + orphanRemoval=true combo.
        for (int p = 0; p < parentCount; p++) {
            BadParent parent = new BadParent("bad-parent-" + p);
            for (int c = 0; c < childrenPer; c++) {
                parent.addChild(new BadChild("bad-child-" + p + "-" + c));
            }
            badParentRepo.save(parent);  // cascade=PERSIST saves children too.
        }

        // GoodParent tree — explicit-cascade pattern.
        for (int p = 0; p < parentCount; p++) {
            GoodParent parent = new GoodParent("good-parent-" + p);
            for (int c = 0; c < childrenPer; c++) {
                parent.addChild(new GoodChild("good-child-" + p + "-" + c));
            }
            goodParentRepo.save(parent);
        }

        // Customer graph — fuel for the dirty-checking demos.
        long totalAccounts = 0;
        long totalTransactions = 0;
        for (int i = 0; i < customers; i++) {
            Customer customer = new Customer("customer-" + i, "user" + i + "@example.com");
            for (int a = 0; a < accountsPer; a++) {
                Account acc = new Account("ACC-" + i + "-" + a, BigDecimal.valueOf(1000 + i * 10 + a));
                for (int t = 0; t < transactionsPer; t++) {
                    acc.addTransaction(new Transaction("txn-" + i + "-" + a + "-" + t,
                            BigDecimal.valueOf((t + 1) * 10)));
                    totalTransactions++;
                }
                customer.addAccount(acc);
                totalAccounts++;
            }
            customerRepo.save(customer);  // PERSIST cascade reaches accounts + txns.
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("badParents", parentCount);
        out.put("badChildren", parentCount * childrenPer);
        out.put("goodParents", parentCount);
        out.put("goodChildren", parentCount * childrenPer);
        out.put("customers", customers);
        out.put("accounts", totalAccounts);
        out.put("transactions", totalTransactions);
        out.put("note", "Seed complete. Hit /cascade/*, /flush/*, /dirty-check/* to demo.");
        return out;
    }
}
