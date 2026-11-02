package com.praneetkamble.matching.sim;

/**
 * @param orders          number of order-flow actions (new orders and cancels) to generate
 * @param seed            RNG seed; the same seed always produces the same flow and the same result
 * @param initialMid      starting mid price in ticks
 * @param marketOrderPct  share of actions that are market orders (0-100)
 * @param cancelPct       share of actions that are cancels of a live order (0-100)
 * @param maxOffsetTicks  how far from the mid limit orders are placed at most
 * @param maxQuantity     maximum order size in lots
 */
public record SimulationConfig(long orders, long seed, long initialMid, int marketOrderPct, int cancelPct,
                               int maxOffsetTicks, int maxQuantity) {

    public SimulationConfig {
        if (orders < 0 || initialMid <= 0 || maxOffsetTicks < 1 || maxQuantity < 1
                || marketOrderPct < 0 || cancelPct < 0 || marketOrderPct + cancelPct > 100) {
            throw new IllegalArgumentException("invalid simulation parameters");
        }
    }

    public static SimulationConfig of(long orders, long seed) {
        return new SimulationConfig(orders, seed, 10_000, 8, 25, 50, 100);
    }
}
