package com.praneetkamble.matching.book;

import static org.assertj.core.api.Assertions.assertThat;

import com.praneetkamble.matching.event.BookEvent.OrderAccepted;
import com.praneetkamble.matching.event.BookEvent.OrderCancelled;
import com.praneetkamble.matching.event.BookEvent.TradeExecuted;
import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.OrderType;
import com.praneetkamble.matching.model.Side;
import com.praneetkamble.matching.model.TimeInForce;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Every accepted lot must end up traded (on both sides), cancelled, or still resting. Nothing appears or vanishes. */
class QuantityConservationTest {

    @Test
    void acceptedQuantityEqualsTradedTwicePlusCancelledPlusResting() {
        Events events = new Events();
        OrderBook book = new OrderBook("CONS", events);
        SplittableRandom rng = new SplittableRandom(7);
        long nextId = 1;

        for (int i = 0; i < 20_000; i++) {
            int roll = rng.nextInt(100);
            if (roll < 20 && nextId > 1) {
                book.cancel(1 + rng.nextLong(nextId - 1));
                continue;
            }
            Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long qty = 1 + rng.nextInt(40);
            long price = 90 + rng.nextInt(21);
            if (roll < 30) {
                book.submit(OrderRequest.market(nextId++, side, qty));
            } else {
                TimeInForce tif = switch (rng.nextInt(6)) {
                    case 0 -> TimeInForce.IOC;
                    case 1 -> TimeInForce.FOK;
                    default -> TimeInForce.GTC;
                };
                book.submit(new OrderRequest(nextId++, side, OrderType.LIMIT, price, qty, tif));
            }
        }

        long accepted = events.of(OrderAccepted.class).stream().mapToLong(OrderAccepted::quantity).sum();
        long traded = events.trades().stream().mapToLong(TradeExecuted::quantity).sum();
        long cancelled = events.of(OrderCancelled.class).stream().mapToLong(OrderCancelled::remainingQuantity).sum();
        long resting = book.depth(Side.BUY, Integer.MAX_VALUE).stream().mapToLong(LevelSnapshot::quantity).sum()
                + book.depth(Side.SELL, Integer.MAX_VALUE).stream().mapToLong(LevelSnapshot::quantity).sum();

        assertThat(traded).isPositive();
        assertThat(accepted).isEqualTo(2 * traded + cancelled + resting);
    }
}
