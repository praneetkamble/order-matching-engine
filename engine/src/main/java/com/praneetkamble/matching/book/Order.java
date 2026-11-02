package com.praneetkamble.matching.book;

import com.praneetkamble.matching.model.Side;

/** A resting order. Doubles as a node of its price level's intrusive doubly linked list. */
final class Order {

    final long id;
    final Side side;
    final long price;
    long remaining;

    PriceLevel level;
    Order prev;
    Order next;

    Order(long id, Side side, long price, long remaining) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = remaining;
    }
}
