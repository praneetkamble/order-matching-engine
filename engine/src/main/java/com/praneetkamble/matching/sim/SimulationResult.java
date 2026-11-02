package com.praneetkamble.matching.sim;

import java.util.OptionalLong;

public record SimulationResult(long ordersSubmitted, long cancelsRequested, long cancelsSucceeded, long trades,
                               long tradedQuantity, long rejects, long openOrders, OptionalLong bestBid,
                               OptionalLong bestAsk, long lastTradePrice, long elapsedNanos) {

    public double actionsPerSecond() {
        return elapsedNanos == 0 ? 0 : (ordersSubmitted + cancelsRequested) * 1e9 / elapsedNanos;
    }
}
