package com.praneetkamble.matching.book;

import static com.praneetkamble.matching.model.OrderRequest.limit;
import static com.praneetkamble.matching.model.OrderRequest.market;
import static org.assertj.core.api.Assertions.assertThat;

import com.praneetkamble.matching.event.BookEvent.OrderAccepted;
import com.praneetkamble.matching.event.BookEvent.OrderCancelled;
import com.praneetkamble.matching.event.BookEvent.OrderRejected;
import com.praneetkamble.matching.event.BookEvent.TradeExecuted;
import com.praneetkamble.matching.event.CancelReason;
import com.praneetkamble.matching.event.RejectReason;
import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.OrderType;
import com.praneetkamble.matching.model.Side;
import com.praneetkamble.matching.model.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private Events events;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        events = new Events();
        book = new OrderBook("TEST", events);
    }

    @Nested
    class Resting {

        @Test
        void nonCrossingOrdersRestAndFormTheSpread() {
            book.submit(limit(1, Side.BUY, 99, 10));
            book.submit(limit(2, Side.SELL, 101, 5));

            assertThat(book.bestBid()).hasValue(99);
            assertThat(book.bestAsk()).hasValue(101);
            assertThat(book.spread()).hasValue(2);
            assertThat(book.openOrders()).isEqualTo(2);
            assertThat(events.trades()).isEmpty();
            assertThat(events.of(OrderAccepted.class)).hasSize(2);
        }

        @Test
        void emptyBookHasNoQuotes() {
            assertThat(book.bestBid()).isEmpty();
            assertThat(book.bestAsk()).isEmpty();
            assertThat(book.spread()).isEmpty();
        }

        @Test
        void bestPricesAreHighestBidAndLowestAsk() {
            book.submit(limit(1, Side.BUY, 98, 1));
            book.submit(limit(2, Side.BUY, 100, 1));
            book.submit(limit(3, Side.BUY, 99, 1));
            book.submit(limit(4, Side.SELL, 103, 1));
            book.submit(limit(5, Side.SELL, 101, 1));
            book.submit(limit(6, Side.SELL, 102, 1));

            assertThat(book.bestBid()).hasValue(100);
            assertThat(book.bestAsk()).hasValue(101);
            assertThat(book.depth(Side.BUY, 10)).extracting(LevelSnapshot::price).containsExactly(100L, 99L, 98L);
            assertThat(book.depth(Side.SELL, 2)).extracting(LevelSnapshot::price).containsExactly(101L, 102L);
        }

        @Test
        void ordersAtTheSamePriceAggregateIntoOneLevel() {
            book.submit(limit(1, Side.BUY, 100, 10));
            book.submit(limit(2, Side.BUY, 100, 15));

            assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
            assertThat(book.quantityAt(Side.BUY, 100)).isEqualTo(25);
            assertThat(book.depth(Side.BUY, 5)).containsExactly(new LevelSnapshot(100, 25, 2));
            assertThat(book.quantityAt(Side.BUY, 99)).isZero();
        }
    }

    @Nested
    class Matching {

        @Test
        void crossingLimitOrderTradesAtTheRestingPrice() {
            book.submit(limit(1, Side.SELL, 100, 10));
            events.clear();

            book.submit(limit(2, Side.BUY, 105, 10)); // willing to pay 105, gets the better 100

            assertThat(events.trades()).singleElement().satisfies(t -> {
                assertThat(t.price()).isEqualTo(100);
                assertThat(t.quantity()).isEqualTo(10);
                assertThat(t.takerOrderId()).isEqualTo(2);
                assertThat(t.makerOrderId()).isEqualTo(1);
                assertThat(t.takerSide()).isEqualTo(Side.BUY);
            });
            assertThat(book.openOrders()).isZero();
            assertThat(book.bestAsk()).isEmpty();
        }

        @Test
        void sellTakerHitsTheBestBid() {
            book.submit(limit(1, Side.BUY, 100, 10));
            book.submit(limit(2, Side.BUY, 101, 10));
            events.clear();

            book.submit(limit(3, Side.SELL, 100, 15));

            assertThat(events.trades()).extracting(TradeExecuted::makerOrderId, TradeExecuted::price, TradeExecuted::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(2L, 101L, 10L),
                            org.assertj.core.groups.Tuple.tuple(1L, 100L, 5L));
            assertThat(book.remainingQuantity(1)).hasValue(5);
            assertThat(book.contains(3)).isFalse();
        }

        @Test
        void earlierOrderAtTheSamePriceMatchesFirst() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 100, 5));
            book.submit(limit(3, Side.SELL, 100, 5));
            events.clear();

            book.submit(limit(4, Side.BUY, 100, 7));

            assertThat(events.trades()).extracting(TradeExecuted::makerOrderId, TradeExecuted::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 5L), org.assertj.core.groups.Tuple.tuple(2L, 2L));
            assertThat(book.remainingQuantity(2)).hasValue(3);
            assertThat(book.remainingQuantity(3)).hasValue(5);
        }

        @Test
        void betterPriceBeatsEarlierTime() {
            book.submit(limit(1, Side.SELL, 101, 5)); // earlier but worse
            book.submit(limit(2, Side.SELL, 100, 5)); // later but better
            events.clear();

            book.submit(limit(3, Side.BUY, 101, 5));

            assertThat(events.trades()).singleElement().satisfies(t -> {
                assertThat(t.makerOrderId()).isEqualTo(2);
                assertThat(t.price()).isEqualTo(100);
            });
        }

        @Test
        void aPartiallyFilledMakerKeepsItsQueuePosition() {
            book.submit(limit(1, Side.SELL, 100, 10));
            book.submit(limit(2, Side.SELL, 100, 10));
            book.submit(limit(3, Side.BUY, 100, 4)); // partially fills order 1
            events.clear();

            book.submit(limit(4, Side.BUY, 100, 8));

            assertThat(events.trades()).extracting(TradeExecuted::makerOrderId, TradeExecuted::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 6L), org.assertj.core.groups.Tuple.tuple(2L, 2L));
        }

        @Test
        void largeOrderSweepsSeveralLevelsAndRestsTheRemainder() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 101, 5));
            book.submit(limit(3, Side.SELL, 102, 5));
            events.clear();

            book.submit(limit(4, Side.BUY, 101, 20));

            assertThat(events.trades()).extracting(TradeExecuted::price, TradeExecuted::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(100L, 5L), org.assertj.core.groups.Tuple.tuple(101L, 5L));
            assertThat(book.bestAsk()).hasValue(102);
            assertThat(book.bestBid()).as("remainder rests at the limit price").hasValue(101);
            assertThat(book.remainingQuantity(4)).hasValue(10);
        }

        @Test
        void bookIsNeverCrossedAfterMatching() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.BUY, 100, 8));

            assertThat(book.bestBid()).hasValue(100);
            assertThat(book.bestAsk()).isEmpty();
        }

        @Test
        void tradeIdsAndEventSequenceNumbersIncreaseMonotonically() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 101, 5));
            book.submit(limit(3, Side.BUY, 101, 10));

            assertThat(events.trades()).extracting(TradeExecuted::tradeId).containsExactly(1L, 2L);
            assertThat(events.all).extracting(e -> e.sequence()).isSorted().doesNotHaveDuplicates();
            assertThat(events.all.get(0).sequence()).isEqualTo(1);
        }

        @Test
        void eventOrderForAnAggressiveOrderIsAcceptedThenTrades() {
            book.submit(limit(1, Side.SELL, 100, 5));
            events.clear();
            book.submit(limit(2, Side.BUY, 100, 5));

            assertThat(events.all).hasSize(2);
            assertThat(events.all.get(0)).isInstanceOf(OrderAccepted.class);
            assertThat(events.all.get(1)).isInstanceOf(TradeExecuted.class);
        }
    }

    @Nested
    class MarketOrders {

        @Test
        void marketOrderTakesLiquidityAtAnyPrice() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 150, 5));
            events.clear();

            book.submit(market(3, Side.BUY, 8));

            assertThat(events.trades()).extracting(TradeExecuted::price, TradeExecuted::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(100L, 5L), org.assertj.core.groups.Tuple.tuple(150L, 3L));
            assertThat(book.openOrders()).isEqualTo(1);
        }

        @Test
        void unfilledMarketRemainderIsCancelledNotRested() {
            book.submit(limit(1, Side.SELL, 100, 5));
            events.clear();

            book.submit(market(2, Side.BUY, 8));

            assertThat(events.trades()).hasSize(1);
            assertThat(events.of(OrderCancelled.class)).singleElement().satisfies(c -> {
                assertThat(c.orderId()).isEqualTo(2);
                assertThat(c.remainingQuantity()).isEqualTo(3);
                assertThat(c.reason()).isEqualTo(CancelReason.NO_LIQUIDITY);
            });
            assertThat(book.contains(2)).isFalse();
            assertThat(book.bestBid()).isEmpty();
        }

        @Test
        void marketOrderOnEmptyBookIsCancelled() {
            book.submit(market(1, Side.SELL, 10));

            assertThat(events.of(OrderCancelled.class)).singleElement()
                    .extracting(OrderCancelled::reason).isEqualTo(CancelReason.NO_LIQUIDITY);
            assertThat(book.openOrders()).isZero();
        }
    }

    @Nested
    class TimeInForceRules {

        @Test
        void iocFillsWhatItCanAndCancelsTheRest() {
            book.submit(limit(1, Side.SELL, 100, 5));
            events.clear();

            book.submit(limit(2, Side.BUY, 100, 8, TimeInForce.IOC));

            assertThat(events.trades()).hasSize(1);
            assertThat(events.of(OrderCancelled.class)).singleElement().satisfies(c -> {
                assertThat(c.reason()).isEqualTo(CancelReason.IOC_REMAINDER);
                assertThat(c.remainingQuantity()).isEqualTo(3);
            });
            assertThat(book.contains(2)).isFalse();
        }

        @Test
        void iocThatCannotMatchLeavesNoTrace() {
            book.submit(limit(1, Side.SELL, 105, 5));

            book.submit(limit(2, Side.BUY, 100, 5, TimeInForce.IOC));

            assertThat(book.openOrders()).isEqualTo(1);
            assertThat(book.bestBid()).isEmpty();
        }

        @Test
        void fokExecutesCompletelyWhenEnoughLiquidityExists() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 101, 5));
            events.clear();

            book.submit(limit(3, Side.BUY, 101, 10, TimeInForce.FOK));

            assertThat(events.trades()).hasSize(2);
            assertThat(book.openOrders()).isZero();
            assertThat(events.of(OrderCancelled.class)).isEmpty();
        }

        @Test
        void fokIsKilledWithoutAnyPartialExecution() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 105, 5)); // beyond the FOK limit
            events.clear();

            book.submit(limit(3, Side.BUY, 101, 10, TimeInForce.FOK));

            assertThat(events.trades()).isEmpty();
            assertThat(events.of(OrderCancelled.class)).singleElement().satisfies(c -> {
                assertThat(c.reason()).isEqualTo(CancelReason.FOK_UNFILLABLE);
                assertThat(c.remainingQuantity()).isEqualTo(10);
            });
            assertThat(book.quantityAt(Side.SELL, 100)).as("book untouched").isEqualTo(5);
            assertThat(book.openOrders()).isEqualTo(2);
        }

        @Test
        void marketFokChecksAllLiquidity() {
            book.submit(limit(1, Side.SELL, 100, 5));

            book.submit(new OrderRequest(2, Side.BUY, OrderType.MARKET, 0, 6, TimeInForce.FOK));
            assertThat(events.trades()).isEmpty();
            assertThat(book.remainingQuantity(1)).hasValue(5);

            book.submit(new OrderRequest(3, Side.BUY, OrderType.MARKET, 0, 5, TimeInForce.FOK));
            assertThat(events.trades()).hasSize(1);
        }
    }

    @Nested
    class Cancelling {

        @Test
        void cancelRemovesTheOrderAndReportsRemainingQuantity() {
            book.submit(limit(1, Side.BUY, 100, 10));
            book.submit(limit(2, Side.BUY, 100, 10));
            book.submit(limit(3, Side.SELL, 100, 3)); // fills 3 of order 1
            events.clear();

            assertThat(book.cancel(1)).isTrue();

            assertThat(events.of(OrderCancelled.class)).singleElement().satisfies(c -> {
                assertThat(c.remainingQuantity()).isEqualTo(7);
                assertThat(c.reason()).isEqualTo(CancelReason.USER_REQUESTED);
            });
            assertThat(book.contains(1)).isFalse();
            assertThat(book.quantityAt(Side.BUY, 100)).isEqualTo(10);
        }

        @Test
        void cancellingTheLastOrderRemovesThePriceLevel() {
            book.submit(limit(1, Side.BUY, 100, 10));
            book.submit(limit(2, Side.BUY, 99, 10));

            book.cancel(1);

            assertThat(book.bestBid()).hasValue(99);
            assertThat(book.levelCount(Side.BUY)).isEqualTo(1);
        }

        @Test
        void cancellingTheMiddleOfAQueuePreservesTheOthersPriority() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.SELL, 100, 5));
            book.submit(limit(3, Side.SELL, 100, 5));
            book.cancel(2);
            events.clear();

            book.submit(limit(4, Side.BUY, 100, 10));

            assertThat(events.trades()).extracting(TradeExecuted::makerOrderId).containsExactly(1L, 3L);
        }

        @Test
        void cancellingHeadAndTailLeavesALinkedListThatStillWorks() {
            for (long id = 1; id <= 4; id++) {
                book.submit(limit(id, Side.SELL, 100, 1));
            }
            book.cancel(1);
            book.cancel(4);
            events.clear();

            book.submit(limit(5, Side.BUY, 100, 5));

            assertThat(events.trades()).extracting(TradeExecuted::makerOrderId).containsExactly(2L, 3L);
            assertThat(book.remainingQuantity(5)).hasValue(3);
        }

        @Test
        void cancellingAnUnknownOrderIsRejected() {
            assertThat(book.cancel(42)).isFalse();
            assertThat(events.of(OrderRejected.class)).singleElement().satisfies(r -> {
                assertThat(r.orderId()).isEqualTo(42);
                assertThat(r.reason()).isEqualTo(RejectReason.UNKNOWN_ORDER);
            });
        }

        @Test
        void filledOrdersCannotBeCancelled() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.BUY, 100, 5));

            assertThat(book.cancel(1)).isFalse();
            assertThat(book.cancel(2)).isFalse();
        }

        @Test
        void cancellingTwiceFailsTheSecondTime() {
            book.submit(limit(1, Side.BUY, 100, 5));
            assertThat(book.cancel(1)).isTrue();
            assertThat(book.cancel(1)).isFalse();
        }
    }

    @Nested
    class Validation {

        @Test
        void nonPositiveQuantityIsRejected() {
            book.submit(limit(1, Side.BUY, 100, 0));
            book.submit(limit(2, Side.BUY, 100, -5));
            book.submit(market(3, Side.SELL, 0));

            assertThat(events.of(OrderRejected.class)).extracting(OrderRejected::reason)
                    .containsOnly(RejectReason.INVALID_QUANTITY).hasSize(3);
            assertThat(events.of(OrderAccepted.class)).isEmpty();
            assertThat(book.openOrders()).isZero();
        }

        @Test
        void nonPositiveLimitPriceIsRejected() {
            book.submit(limit(1, Side.BUY, 0, 5));
            book.submit(limit(2, Side.SELL, -1, 5));

            assertThat(events.of(OrderRejected.class)).extracting(OrderRejected::reason)
                    .containsOnly(RejectReason.INVALID_PRICE).hasSize(2);
        }

        @Test
        void duplicateIdsOfRestingOrdersAreRejected() {
            book.submit(limit(1, Side.BUY, 100, 5));
            book.submit(limit(1, Side.SELL, 200, 5));

            assertThat(events.of(OrderRejected.class)).singleElement()
                    .extracting(OrderRejected::reason).isEqualTo(RejectReason.DUPLICATE_ORDER_ID);
            assertThat(book.openOrders()).isEqualTo(1);
            assertThat(book.bestAsk()).isEmpty();
        }

        @Test
        void aRejectedOrderDoesNotChangeTheBook() {
            book.submit(limit(1, Side.SELL, 100, 5));
            book.submit(limit(2, Side.BUY, 100, 0));
            assertThat(book.remainingQuantity(1)).hasValue(5);
            assertThat(events.trades()).isEmpty();
        }
    }

    @Test
    void renderShowsAsksAboveBids() {
        book.submit(limit(1, Side.BUY, 99, 10));
        book.submit(limit(2, Side.SELL, 101, 5));

        String ladder = book.render(5);

        assertThat(ladder).contains("TEST");
        assertThat(ladder.indexOf("ASK")).isLessThan(ladder.indexOf("BID"));
        assertThat(book.symbol()).isEqualTo("TEST");
    }
}
