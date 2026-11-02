package com.praneetkamble.matching.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.OrderType;
import com.praneetkamble.matching.model.Side;
import com.praneetkamble.matching.model.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Differential test: the real book must behave exactly like the naive reference on random order flow. */
class OrderBookPropertyTest {

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 42, 1234, 99999})
    void behavesLikeTheReferenceImplementation(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        Events events = new Events();
        OrderBook book = new OrderBook("PROP", events);
        ReferenceMatcher reference = new ReferenceMatcher();
        List<Long> issued = new ArrayList<>();
        long nextId = 1;

        for (int step = 0; step < 4000; step++) {
            events.clear();
            int roll = rng.nextInt(100);
            if (roll < 20 && !issued.isEmpty()) {
                long id = issued.get(rng.nextInt(issued.size()));
                assertThat(book.cancel(id)).as("cancel %d at step %d", id, step).isEqualTo(reference.cancel(id));
                continue;
            }
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = rng.nextInt(10) == 0 ? rng.nextInt(-2, 1) : 1 + rng.nextInt(50);
            long price = 95 + rng.nextInt(11); // 95..105: tight range forces lots of crossing
            OrderRequest request;
            if (roll < 30) {
                request = OrderRequest.market(nextId, side, qty);
            } else {
                TimeInForce[] tifs = {TimeInForce.GTC, TimeInForce.GTC, TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK};
                request = new OrderRequest(nextId, side, OrderType.LIMIT, rng.nextInt(40) == 0 ? 0 : price, qty,
                        tifs[rng.nextInt(tifs.length)]);
            }
            // occasionally reuse an id to exercise duplicate rejection
            if (!issued.isEmpty() && rng.nextInt(50) == 0) {
                request = new OrderRequest(issued.get(rng.nextInt(issued.size())), side, request.type(), request.price(),
                        request.quantity(), request.timeInForce());
            } else {
                issued.add(nextId++);
            }

            List<ReferenceMatcher.Fill> expected = reference.submit(request);
            book.submit(request);

            List<ReferenceMatcher.Fill> actual = events.trades().stream()
                    .map(t -> new ReferenceMatcher.Fill(t.takerOrderId(), t.makerOrderId(), t.price(), t.quantity())).toList();
            assertThat(actual).as("fills at step %d for %s", step, request).isEqualTo(expected);
            assertThat(book.openOrders()).as("open orders at step %d", step).isEqualTo(reference.openOrders());
            if (book.bestBid().isPresent() && book.bestAsk().isPresent()) {
                assertThat(book.bestBid().getAsLong()).as("crossed book at step %d", step)
                        .isLessThan(book.bestAsk().getAsLong());
            }
        }
        for (long id : issued) {
            assertThat(book.remainingQuantity(id).orElse(-1)).as("remaining of %d", id).isEqualTo(reference.remaining(id));
        }
    }
}
