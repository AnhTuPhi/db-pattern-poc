package com.claude.dbpoc.m09.web;

import com.claude.dbpoc.m09.domain.Order.Status;
import com.claude.dbpoc.m09.domain.OrderSummary;
import com.claude.dbpoc.m09.query.OrderSearch;
import com.claude.dbpoc.m09.query.OrderSearchCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Two endpoints sit on top of the OrderSearch implementations:
 *
 *   GET /search/{impl}?customerName=...&country=...&...   — call ONE impl.
 *   GET /compare?customerName=...&country=...&...         — call ALL four
 *                                                            with identical
 *                                                            input, return
 *                                                            per-impl results
 *                                                            and a verdict
 *                                                            matrix.
 *
 * The /compare response is the headline: same input, same paging, same
 * sorting → same OrderSummary list, with elapsedMs side-by-side. If the
 * payloads ever diverge, one of the implementations has drifted from the
 * contract and the JSON tells you which one.
 */
@RestController
public class CompareController {

    private final List<OrderSearch> impls;

    public CompareController(List<OrderSearch> impls) {
        this.impls = impls;
    }

    @GetMapping("/search/{impl}")
    public Map<String, Object> searchOne(
            @PathVariable String impl,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) Set<Status> statuses,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort) {

        OrderSearch chosen = impls.stream()
                .filter(s -> s.name().equalsIgnoreCase(impl))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Unknown impl: " + impl + " (try: " +
                                impls.stream().map(OrderSearch::name).collect(Collectors.joining(", ")) + ")"));

        OrderSearchCriteria c = buildCriteria(customerName, statuses, from, to, minAmount, maxAmount, country);
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        return runOne(chosen, c, pageable);
    }

    @GetMapping("/compare")
    public Map<String, Object> compareAll(
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) Set<Status> statuses,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String country,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort) {

        OrderSearchCriteria c = buildCriteria(customerName, statuses, from, to, minAmount, maxAmount, country);
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));

        List<Map<String, Object>> rows = new ArrayList<>();
        // Warm-up call so the first impl in the list isn't punished by JIT /
        // first-flight overhead. Discarded.
        impls.get(0).search(c, pageable);

        for (OrderSearch impl : impls) {
            rows.add(runOne(impl, c, pageable));
        }

        // Cross-check the payloads — every impl should return the same set of
        // OrderSummary IDs in the same order. If they don't, surface that
        // loudly in the JSON instead of silently letting one drift.
        List<List<Long>> idsByImpl = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            List<OrderSummary> page0 = (List<OrderSummary>) row.get("page");
            idsByImpl.add(page0.stream().map(OrderSummary::orderId).toList());
        }
        boolean allMatch = idsByImpl.stream().distinct().count() <= 1;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("criteria", describeCriteria(c));
        out.put("pageable", Map.of("page", page, "size", size, "sort", sort));
        out.put("results", rows);
        out.put("allImplsAgreeOnIds", allMatch);
        if (!allMatch) {
            out.put("idsPerImpl", idsByImpl);
            out.put("warning", "Implementations disagree on result IDs. Probable cause: drift in WHERE / ORDER BY / paging.");
        }
        return out;
    }

    private Map<String, Object> runOne(OrderSearch impl, OrderSearchCriteria c, Pageable pageable) {
        long t0 = System.nanoTime();
        Page<OrderSummary> result = impl.search(c, pageable);
        long elapsed = System.nanoTime() - t0;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("impl", impl.name());
        row.put("elapsedMs", elapsed / 1_000_000.0);
        row.put("totalElements", result.getTotalElements());
        row.put("returnedRows", result.getNumberOfElements());
        row.put("page", result.getContent());
        return row;
    }

    private OrderSearchCriteria buildCriteria(String customerName, Set<Status> statuses,
                                               Instant from, Instant to,
                                               BigDecimal minAmount, BigDecimal maxAmount,
                                               String country) {
        return new OrderSearchCriteria(
                Optional.ofNullable(customerName),
                Optional.ofNullable(statuses),
                Optional.ofNullable(from),
                Optional.ofNullable(to),
                Optional.ofNullable(minAmount),
                Optional.ofNullable(maxAmount),
                Optional.ofNullable(country));
    }

    private Map<String, Object> describeCriteria(OrderSearchCriteria c) {
        Map<String, Object> m = new LinkedHashMap<>();
        c.customerNameLike().ifPresent(v -> m.put("customerName", v));
        c.statuses().ifPresent(v -> m.put("statuses", v));
        c.from().ifPresent(v -> m.put("from", v));
        c.to().ifPresent(v -> m.put("to", v));
        c.minAmount().ifPresent(v -> m.put("minAmount", v));
        c.maxAmount().ifPresent(v -> m.put("maxAmount", v));
        c.country().ifPresent(v -> m.put("country", v));
        return m;
    }

    /**
     * Parse "field,dir[,field,dir...]" into a Sort. Pageable's default parsing
     * from a single request param is awkward; doing it explicitly keeps the
     * /compare URL honest.
     */
    private Sort parseSort(String sortParam) {
        String[] parts = sortParam.split(",");
        if (parts.length < 2) return Sort.by(Sort.Direction.DESC, "id");
        List<Sort.Order> orders = new ArrayList<>();
        for (int i = 0; i + 1 < parts.length; i += 2) {
            String field = parts[i].trim();
            Sort.Direction dir = parts[i + 1].trim().equalsIgnoreCase("asc")
                    ? Sort.Direction.ASC : Sort.Direction.DESC;
            orders.add(new Sort.Order(dir, field));
        }
        return Sort.by(orders);
    }
}
