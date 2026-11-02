package com.praneetkamble.matching.model;

import java.util.Objects;

/**
 * An instruction to the order book. Prices are integers in ticks and quantities integers in lots, which keeps
 * the matching arithmetic exact. Validation happens inside the book, which answers bad input with an
 * {@code OrderRejected} event rather than an exception.
 *
 * @param price limit price in ticks (ignored for market orders)
 */
public record OrderRequest(long orderId, Side side, OrderType type, long price, long quantity, TimeInForce timeInForce) {

    public OrderRequest {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timeInForce, "timeInForce");
    }

    public static OrderRequest limit(long orderId, Side side, long price, long quantity) {
        return new OrderRequest(orderId, side, OrderType.LIMIT, price, quantity, TimeInForce.GTC);
    }

    public static OrderRequest limit(long orderId, Side side, long price, long quantity, TimeInForce tif) {
        return new OrderRequest(orderId, side, OrderType.LIMIT, price, quantity, tif);
    }

    public static OrderRequest market(long orderId, Side side, long quantity) {
        return new OrderRequest(orderId, side, OrderType.MARKET, 0, quantity, TimeInForce.IOC);
    }
}
