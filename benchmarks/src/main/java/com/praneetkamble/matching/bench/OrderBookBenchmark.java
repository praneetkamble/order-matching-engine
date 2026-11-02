package com.praneetkamble.matching.bench;

import com.praneetkamble.matching.book.OrderBook;
import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.Side;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-operation latency of the core book operations on a book with ~10k resting orders over ~100 price levels
 * per side. Each invocation does exactly one operation and restores the book, so the book stays the same size.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class OrderBookBenchmark {

    private static final int LEVELS = 100;
    private static final int ORDERS_PER_LEVEL = 50;
    private static final long MID = 10_000;

    private OrderBook book;
    private long nextId;
    private long[] tracked;
    private int cursor;
    private SplittableRandom rng;

    @Setup(Level.Iteration)
    public void setUp() {
        book = new OrderBook("BENCH");
        rng = new SplittableRandom(7);
        nextId = 1;
        for (int level = 1; level <= LEVELS; level++) {
            for (int i = 0; i < ORDERS_PER_LEVEL; i++) {
                book.submit(OrderRequest.limit(nextId++, Side.BUY, MID - level, 100));
                book.submit(OrderRequest.limit(nextId++, Side.SELL, MID + level, 100));
            }
        }
        tracked = new long[LEVELS * ORDERS_PER_LEVEL * 2];
        for (int i = 0; i < tracked.length; i++) {
            tracked[i] = i + 1L;
        }
        cursor = 0;
    }

    /** A passive order that rests at a random price (no match), immediately cancelled again. */
    @Benchmark
    public boolean restAndCancel() {
        long id = nextId++;
        long price = MID - 1 - rng.nextInt(LEVELS);
        book.submit(OrderRequest.limit(id, Side.BUY, price, 100));
        return book.cancel(id);
    }

    /** A marketable order that fully fills against one resting order, followed by a replenishing order. */
    @Benchmark
    public void matchSingleFill() {
        book.submit(OrderRequest.limit(nextId++, Side.BUY, MID + 1, 100));
        book.submit(OrderRequest.limit(nextId++, Side.SELL, MID + 1, 100));
    }

    /** A market order sweeping five price levels, followed by orders that replenish them. */
    @Benchmark
    public void sweepFiveLevels() {
        book.submit(OrderRequest.market(nextId++, Side.BUY, 5 * ORDERS_PER_LEVEL * 100L));
        for (int level = 1; level <= 5; level++) {
            for (int i = 0; i < ORDERS_PER_LEVEL; i++) {
                book.submit(OrderRequest.limit(nextId++, Side.SELL, MID + level, 100));
            }
        }
    }

    /**
     * Mixed flow that keeps the book size stable: 60% a passive order replaces the oldest tracked order (submit
     * + cancel), 30% an aggressive order takes one resting order and is replenished, 10% a market order that
     * takes one resting order and is replenished.
     */
    @Benchmark
    public void mixedFlow() {
        int roll = rng.nextInt(10);
        Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
        if (roll < 6) {
            long offset = 1 + rng.nextInt(LEVELS);
            long id = nextId++;
            book.submit(OrderRequest.limit(id, side, side == Side.BUY ? MID - offset : MID + offset, 100));
            book.cancel(tracked[cursor]);
            tracked[cursor] = id;
            cursor = (cursor + 1) % tracked.length;
        } else {
            long touch = side == Side.BUY ? MID + 1 : MID - 1; // best price of the opposite side
            if (roll < 9) {
                book.submit(OrderRequest.limit(nextId++, side, touch, 100));
            } else {
                book.submit(OrderRequest.market(nextId++, side, 100));
            }
            book.submit(OrderRequest.limit(nextId++, side.opposite(), touch, 100));
        }
    }
}
