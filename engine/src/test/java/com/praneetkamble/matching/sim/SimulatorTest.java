package com.praneetkamble.matching.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.praneetkamble.matching.event.BookEvent;
import com.praneetkamble.matching.event.BookEvent.TradeExecuted;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatorTest {

    private static SimulationResult withoutTiming(SimulationResult r) {
        return new SimulationResult(r.ordersSubmitted(), r.cancelsRequested(), r.cancelsSucceeded(), r.trades(),
                r.tradedQuantity(), r.rejects(), r.openOrders(), r.bestBid(), r.bestAsk(), r.lastTradePrice(), 0);
    }

    @Test
    void sameSeedProducesIdenticalResultsAndEventStream() {
        List<BookEvent> first = new ArrayList<>();
        List<BookEvent> second = new ArrayList<>();

        SimulationResult a = new Simulator(SimulationConfig.of(20_000, 5), first::add).run();
        SimulationResult b = new Simulator(SimulationConfig.of(20_000, 5), second::add).run();

        assertThat(withoutTiming(a)).isEqualTo(withoutTiming(b));
        assertThat(first).isEqualTo(second).isNotEmpty();
    }

    @Test
    void differentSeedsProduceDifferentFlow() {
        SimulationResult a = new Simulator(SimulationConfig.of(20_000, 1)).run();
        SimulationResult b = new Simulator(SimulationConfig.of(20_000, 2)).run();

        assertThat(withoutTiming(a)).isNotEqualTo(withoutTiming(b));
    }

    @Test
    void generatedFlowIsAlwaysValidAndProducesTrades() {
        SimulationResult r = new Simulator(SimulationConfig.of(50_000, 11)).run();

        assertThat(r.rejects()).isZero();
        assertThat(r.trades()).isPositive();
        assertThat(r.tradedQuantity()).isGreaterThanOrEqualTo(r.trades());
        assertThat(r.ordersSubmitted() + r.cancelsRequested()).isEqualTo(50_000);
        assertThat(r.cancelsSucceeded()).isLessThanOrEqualTo(r.cancelsRequested());
        assertThat(r.openOrders()).isPositive();
        if (r.bestBid().isPresent() && r.bestAsk().isPresent()) {
            assertThat(r.bestBid().getAsLong()).isLessThan(r.bestAsk().getAsLong());
        }
    }

    @Test
    void downstreamListenerSeesEveryTradeInOrder() {
        List<TradeExecuted> trades = new ArrayList<>();
        SimulationResult r = new Simulator(SimulationConfig.of(10_000, 3), e -> {
            if (e instanceof TradeExecuted t) {
                trades.add(t);
            }
        }).run();

        assertThat(trades).hasSize((int) r.trades());
        assertThat(trades).extracting(TradeExecuted::tradeId).isSorted().doesNotHaveDuplicates();
        assertThat(trades.stream().mapToLong(TradeExecuted::quantity).sum()).isEqualTo(r.tradedQuantity());
    }

    @Test
    void zeroOrdersIsAnEmptyRun() {
        SimulationResult r = new Simulator(SimulationConfig.of(0, 1)).run();
        assertThat(r.ordersSubmitted()).isZero();
        assertThat(r.openOrders()).isZero();
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new SimulationConfig(1, 1, 0, 8, 25, 50, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulationConfig(1, 1, 100, 60, 50, 50, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulationConfig(-1, 1, 100, 8, 25, 50, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
