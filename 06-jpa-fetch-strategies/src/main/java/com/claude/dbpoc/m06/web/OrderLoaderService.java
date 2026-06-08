package com.claude.dbpoc.m06.web;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.claude.dbpoc.m06.domain.Order;
import com.claude.dbpoc.m06.dto.OrderSummaryDto;
import com.claude.dbpoc.m06.repo.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * The transactional helper for LazyExceptionController.
 *
 * Spring's @Transactional is implemented via a proxy. A proxy only fires when
 * the call crosses a bean boundary — so the controller MUST call into this
 * separate bean for the boundary to take effect. If we tried to inline the
 * @Transactional method on the controller itself, an internal `this.foo()`
 * call would BYPASS the proxy and the tx would never start.
 *
 * The controller is deliberately NOT @Transactional. That's how we put the
 * lazy-collection touch OUTSIDE the transaction — which is the whole point
 * of the LIE demo.
 */
@Service
@RequiredArgsConstructor
public class OrderLoaderService {

    private final OrderRepository orderRepo;

    /**
     * Loads one Order with no JOIN FETCH. When this returns, the tx commits
     * and the Session closes — order.items is a lazy proxy ready to throw
     * LazyInitializationException on the next access.
     */
    @Transactional(readOnly = true)
    public Order loadOneOrder() {
        return orderRepo.findAll().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No orders — call POST /seed first."));
    }

    /**
     * Same shape as loadOneOrder() but uses LEFT JOIN FETCH so the items
     * collection is fully initialised inside the tx. The "fix" you should
     * reach for when you actually need the entity graph.
     */
    @Transactional(readOnly = true)
    public Order loadOneOrderWithItems() {
        Long id = orderRepo.findAll().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No orders — call POST /seed first."))
            .getId();
        return orderRepo.findByIdWithItems(id).orElseThrow();
    }

    /**
     * The "read path" equivalent: project to a DTO directly. Records, no proxies,
     * no LIE risk regardless of session lifetime.
     */
    @Transactional(readOnly = true)
    public List<OrderSummaryDto> loadAllAsDto() {
        return orderRepo.findAllAsDto();
    }
}
