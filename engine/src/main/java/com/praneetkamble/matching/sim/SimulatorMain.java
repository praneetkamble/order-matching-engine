package com.praneetkamble.matching.sim;

import java.util.Locale;

/**
 * {@code java -jar matching-engine.jar [--orders N] [--seed S]}: replays a random order flow through a book and
 * prints statistics.
 */
public final class SimulatorMain {

    private SimulatorMain() {
    }

    public static void main(String[] args) {
        long orders = 1_000_000;
        long seed = 42;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--orders" -> orders = Long.parseLong(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("Usage: java -jar matching-engine.jar [--orders N] [--seed S]");
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        SimulationResult r = new Simulator(SimulationConfig.of(orders, seed)).run();
        System.out.printf(Locale.ROOT, "Orders submitted : %,d%n", r.ordersSubmitted());
        System.out.printf(Locale.ROOT, "Cancels          : %,d requested, %,d succeeded%n", r.cancelsRequested(), r.cancelsSucceeded());
        System.out.printf(Locale.ROOT, "Trades           : %,d (%,d lots)%n", r.trades(), r.tradedQuantity());
        System.out.printf(Locale.ROOT, "Rejects          : %,d%n", r.rejects());
        System.out.printf(Locale.ROOT, "Resting orders   : %,d%n", r.openOrders());
        System.out.printf(Locale.ROOT, "Top of book      : bid %s / ask %s (last trade %d)%n",
                r.bestBid().isPresent() ? r.bestBid().getAsLong() : "-",
                r.bestAsk().isPresent() ? r.bestAsk().getAsLong() : "-", r.lastTradePrice());
        System.out.printf(Locale.ROOT, "Elapsed          : %.1f ms (%,.0f actions/s)%n", r.elapsedNanos() / 1e6, r.actionsPerSecond());
    }
}
