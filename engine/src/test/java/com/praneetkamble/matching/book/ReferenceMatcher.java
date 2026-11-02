package com.praneetkamble.matching.book;

import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.OrderType;
import com.praneetkamble.matching.model.Side;
import com.praneetkamble.matching.model.TimeInForce;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deliberately naive matcher used as a test oracle: a flat list of resting orders, re-sorted for every match.
 * Obviously correct, hopelessly slow, which is exactly what a reference implementation should be.
 */
final class ReferenceMatcher {

    record Fill(long taker, long maker, long price, long quantity) {
    }

    private static final class Resting {
        final long id;
        final Side side;
        final long price;
        final long arrival;
        long remaining;

        Resting(long id, Side side, long price, long arrival, long remaining) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.arrival = arrival;
            this.remaining = remaining;
        }
    }

    private final List<Resting> book = new ArrayList<>();
    private long arrival;

    List<Fill> submit(OrderRequest r) {
        List<Fill> fills = new ArrayList<>();
        if (r.quantity() <= 0 || (r.type() == OrderType.LIMIT && r.price() <= 0)
                || book.stream().anyMatch(o -> o.id == r.orderId())) {
            return fills;
        }
        boolean market = r.type() == OrderType.MARKET;
        if (r.timeInForce() == TimeInForce.FOK) {
            long available = book.stream().filter(o -> o.side != r.side() && (market || crosses(r, o)))
                    .mapToLong(o -> o.remaining).sum();
            if (available < r.quantity()) {
                return fills;
            }
        }
        long remaining = r.quantity();
        Comparator<Resting> priority = Comparator.<Resting>comparingLong(o -> r.side() == Side.BUY ? o.price : -o.price)
                .thenComparingLong(o -> o.arrival);
        while (remaining > 0) {
            Resting best = book.stream().filter(o -> o.side != r.side() && (market || crosses(r, o)))
                    .min(priority).orElse(null);
            if (best == null) {
                break;
            }
            long fill = Math.min(remaining, best.remaining);
            fills.add(new Fill(r.orderId(), best.id, best.price, fill));
            remaining -= fill;
            best.remaining -= fill;
            if (best.remaining == 0) {
                book.remove(best);
            }
        }
        if (remaining > 0 && !market && r.timeInForce() == TimeInForce.GTC) {
            book.add(new Resting(r.orderId(), r.side(), r.price(), arrival++, remaining));
        }
        return fills;
    }

    boolean cancel(long id) {
        return book.removeIf(o -> o.id == id);
    }

    int openOrders() {
        return book.size();
    }

    long remaining(long id) {
        return book.stream().filter(o -> o.id == id).mapToLong(o -> o.remaining).findFirst().orElse(-1);
    }

    private static boolean crosses(OrderRequest r, Resting o) {
        return r.side() == Side.BUY ? o.price <= r.price() : o.price >= r.price();
    }
}
