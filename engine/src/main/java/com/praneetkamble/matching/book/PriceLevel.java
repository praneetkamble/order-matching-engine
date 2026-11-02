package com.praneetkamble.matching.book;

/**
 * All resting orders at one price, in arrival order (the head has time priority). Orders are linked
 * intrusively, so appending, removing from the middle (cancel) and popping the head are all O(1) and allocate nothing.
 */
final class PriceLevel {

    final long price;
    private Order head;
    private Order tail;
    private long totalQuantity;
    private int orderCount;

    PriceLevel(long price) {
        this.price = price;
    }

    Order head() {
        return head;
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orderCount;
    }

    boolean isEmpty() {
        return head == null;
    }

    void append(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.remaining;
        orderCount++;
    }

    void remove(Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        totalQuantity -= order.remaining;
        orderCount--;
        order.prev = null;
        order.next = null;
        order.level = null;
    }

    /** Reduces the resting quantity of an order after a (partial) fill, keeping the level total consistent. */
    void reduce(Order order, long quantity) {
        order.remaining -= quantity;
        totalQuantity -= quantity;
    }
}
