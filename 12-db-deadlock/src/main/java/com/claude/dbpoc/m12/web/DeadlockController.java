package com.claude.dbpoc.m12.web;

import com.claude.dbpoc.m12.repo.AccountRepository;
import com.claude.dbpoc.m12.service.DeadlockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Four endpoints, four lessons:
 *
 *   GET /deadlock/reproduce        — fire the textbook race; expect 40P01.
 *   GET /deadlock/graph            — pg_locks + pg_blocking_pids during the race.
 *   GET /deadlock/lock-ordering    — canonical id order, no deadlock.
 *   GET /deadlock/retry            — buggy ordering + retry loop.
 *
 * idA / idB default to alice and bob from POST /seed. Pass explicit ids
 * if you've re-seeded with different ones.
 */
@RestController
@RequestMapping("/deadlock")
public class DeadlockController {

    private final DeadlockService deadlock;
    private final AccountRepository accountRepo;

    public DeadlockController(DeadlockService deadlock, AccountRepository accountRepo) {
        this.deadlock = deadlock;
        this.accountRepo = accountRepo;
    }

    @GetMapping("/reproduce")
    public Map<String, Object> reproduce(
            @RequestParam(required = false) Long idA,
            @RequestParam(required = false) Long idB) {
        long[] ids = resolveIds(idA, idB);
        return deadlock.reproduce(ids[0], ids[1]);
    }

    @GetMapping("/graph")
    public Map<String, Object> graph(
            @RequestParam(required = false) Long idA,
            @RequestParam(required = false) Long idB) {
        long[] ids = resolveIds(idA, idB);
        return deadlock.graph(ids[0], ids[1]);
    }

    @GetMapping("/lock-ordering")
    public Map<String, Object> lockOrdering(
            @RequestParam(required = false) Long idA,
            @RequestParam(required = false) Long idB) {
        long[] ids = resolveIds(idA, idB);
        return deadlock.lockOrdering(ids[0], ids[1]);
    }

    @GetMapping("/retry")
    public Map<String, Object> retry(
            @RequestParam(required = false) Long idA,
            @RequestParam(required = false) Long idB) {
        long[] ids = resolveIds(idA, idB);
        return deadlock.retryAtBoundary(ids[0], ids[1]);
    }

    /**
     * If the caller didn't pass explicit ids, resolve alice + bob from the
     * repo. This lets the endpoints work after a fresh /seed without the
     * caller having to copy-paste ids into the query string.
     */
    private long[] resolveIds(Long idA, Long idB) {
        if (idA != null && idB != null) return new long[]{ idA, idB };

        List<com.claude.dbpoc.m12.domain.Account> all = accountRepo.findAll();
        if (all.size() < 2) throw new IllegalStateException("call POST /seed first");
        long a = all.stream().filter(x -> "alice".equals(x.getOwner()))
                .findFirst().orElseThrow().getId();
        long b = all.stream().filter(x -> "bob".equals(x.getOwner()))
                .findFirst().orElseThrow().getId();
        return new long[]{ a, b };
    }
}
