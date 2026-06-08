package com.claude.dbpoc.m05.dto;

import com.claude.dbpoc.m05.domain.Order;
import java.time.Instant;
import java.util.List;

/**
 * Stable API shape — decoupled from JPA entities so the HTTP response never
 * accidentally triggers lazy loading during Jackson serialisation (which is
 * one of the classic ways teams ship N+1 to production without noticing).
 */
public record OrderResponse(
    Long id,
    String customerName,
    Instant createdAt,
    List<ItemResponse> items
) {
    public static OrderResponse from(Order o) {
        // .getItems() must already be initialised (inside the @Transactional
        // method that built this response) — Jackson will not be in a session.
        List<ItemResponse> items = o.getItems().stream()
            .map(i -> new ItemResponse(i.getId(), i.getProductName(), i.getQuantity(), i.getUnitPrice()))
            .toList();
        return new OrderResponse(o.getId(), o.getCustomerName(), o.getCreatedAt(), items);
    }

    public record ItemResponse(Long id, String productName, int quantity, double unitPrice) {}
}
