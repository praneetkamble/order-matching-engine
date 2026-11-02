package com.praneetkamble.matching.event;

import com.praneetkamble.matching.model.OrderType;
import com.praneetkamble.matching.model.Side;

/**
 * Everything the book reports, in the order it happened. {@link #sequence()} increases by one with every
 * event, so a consumer can detect gaps and replay deterministically.
 */
public sealed interface BookEvent {

    long sequence();

    /** The order passed validation and entered matching. */
    record OrderAccepted(long sequence, long orderId, Side side, OrderType type, long price, long quantity)
            implements BookEvent {
    }

    record OrderRejected(long sequence, long orderId, RejectReason reason) implements BookEvent {
    }

    /** Two orders matched. The trade price is always the resting (maker) order's price. */
    record TradeExecuted(long sequence, long tradeId, long takerOrderId, long makerOrderId, Side takerSide,
                         long price, long quantity) implements BookEvent {
    }

    record OrderCancelled(long sequence, long orderId, long remainingQuantity, CancelReason reason)
            implements BookEvent {
    }
}
