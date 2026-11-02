package com.praneetkamble.matching.sim;

import com.praneetkamble.matching.book.OrderBook;
import com.praneetkamble.matching.event.BookEvent;
import com.praneetkamble.matching.event.BookEvent.OrderRejected;
import com.praneetkamble.matching.event.BookEvent.TradeExecuted;
import com.praneetkamble.matching.event.EventListener;
import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.Side;
import java.util.SplittableRandom;

/**
 * Generates a reproducible, realistic-looking order flow: limit orders clustered around a drifting mid price
 * (most close to it, a few far away), occasional market orders and cancels of live orders.
 */
public final class Simulator {

    private final SimulationConfig config;
    private final EventListener downstream;

    public Simulator(SimulationConfig config) {
        this(config, EventListener.NOOP);
    }

    /** @param downstream receives every event the book emits (e.g. to print or record trades) */
    public Simulator(SimulationConfig config, EventListener downstream) {
        this.config = config;
        this.downstream = downstream;
    }

    public SimulationResult run() {
        Counters counters = new Counters(config.initialMid(), downstream);
        OrderBook book = new OrderBook("SIM", counters);
        SplittableRandom rng = new SplittableRandom(config.seed());

        // ids of orders that may still be resting; cancelled/filled ones are skipped lazily
        long[] live = new long[1024];
        int liveCount = 0;
        long nextId = 1;
        long mid = config.initialMid();

        long start = System.nanoTime();
        for (long i = 0; i < config.orders(); i++) {
            int roll = rng.nextInt(100);
            if (roll < config.cancelPct() && liveCount > 0) {
                int idx = rng.nextInt(liveCount);
                long id = live[idx];
                live[idx] = live[--liveCount];
                counters.cancelsRequested++;
                if (book.cancel(id)) {
                    counters.cancelsSucceeded++;
                }
                continue;
            }
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long quantity = 1 + rng.nextInt(config.maxQuantity());
            long id = nextId++;
            counters.ordersSubmitted++;
            if (roll >= 100 - config.marketOrderPct()) {
                book.submit(OrderRequest.market(id, side, quantity));
            } else {
                // quadratic bias towards the touch; BUYs below the mid, SELLs above, with some crossing flow
                double u = rng.nextDouble();
                long offset = (long) (u * u * config.maxOffsetTicks()) - 2;
                long price = Math.max(1, side == Side.BUY ? mid - offset : mid + offset);
                book.submit(OrderRequest.limit(id, side, price, quantity));
                if (liveCount == live.length) {
                    live = java.util.Arrays.copyOf(live, live.length * 2);
                }
                live[liveCount++] = id;
            }
            mid = counters.lastTradePrice; // the market drifts with where it last traded
        }
        long elapsed = System.nanoTime() - start;

        return new SimulationResult(counters.ordersSubmitted, counters.cancelsRequested, counters.cancelsSucceeded,
                counters.trades, counters.tradedQuantity, counters.rejects, book.openOrders(), book.bestBid(),
                book.bestAsk(), counters.lastTradePrice, elapsed);
    }

    private static final class Counters implements EventListener {

        private final EventListener downstream;
        long ordersSubmitted;
        long cancelsRequested;
        long cancelsSucceeded;
        long trades;
        long tradedQuantity;
        long rejects;
        long lastTradePrice;

        Counters(long initialMid, EventListener downstream) {
            this.lastTradePrice = initialMid;
            this.downstream = downstream;
        }

        @Override
        public void onEvent(BookEvent event) {
            if (event instanceof TradeExecuted t) {
                trades++;
                tradedQuantity += t.quantity();
                lastTradePrice = t.price();
            } else if (event instanceof OrderRejected r && r.reason() != com.praneetkamble.matching.event.RejectReason.UNKNOWN_ORDER) {
                rejects++;
            }
            downstream.onEvent(event);
        }
    }
}
