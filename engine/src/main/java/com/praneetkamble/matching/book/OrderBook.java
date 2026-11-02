package com.praneetkamble.matching.book;

import com.praneetkamble.matching.event.BookEvent.OrderAccepted;
import com.praneetkamble.matching.event.BookEvent.OrderCancelled;
import com.praneetkamble.matching.event.BookEvent.OrderRejected;
import com.praneetkamble.matching.event.BookEvent.TradeExecuted;
import com.praneetkamble.matching.event.CancelReason;
import com.praneetkamble.matching.event.EventListener;
import com.praneetkamble.matching.event.RejectReason;
import com.praneetkamble.matching.model.OrderRequest;
import com.praneetkamble.matching.model.OrderType;
import com.praneetkamble.matching.model.Side;
import com.praneetkamble.matching.model.TimeInForce;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A single-instrument limit order book with strict <b>price-time priority</b>.
 *
 * <ul>
 *   <li>Better prices match first (highest bid, lowest ask); at one price, the earliest order matches first.</li>
 *   <li>Trades execute at the price of the resting order, so an aggressive order can get price improvement.</li>
 *   <li>Supports limit orders (GTC / IOC / FOK), market orders and cancels.</li>
 * </ul>
 *
 * <p>The book is deliberately <b>single-threaded</b>: a matching engine's correctness depends on a total order of
 * events, and a lock-free single writer is both faster and easier to reason about than a concurrent book. Run one
 * book per instrument and feed it from one thread (e.g. fed by a queue).
 *
 * <p>Complexity: submit is O(L log P + F) where L is the number of price levels crossed, P the number of price levels
 * and F the number of fills; cancel is O(log P) at worst (O(1) unless the level becomes empty).
 */
public final class OrderBook {

    private final String symbol;
    private final EventListener listener;
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, Order> ordersById = new HashMap<>();
    private long sequence;
    private long tradeCounter;

    public OrderBook(String symbol, EventListener listener) {
        this.symbol = symbol;
        this.listener = listener;
    }

    public OrderBook(String symbol) {
        this(symbol, EventListener.NOOP);
    }

    public String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------------ commands

    /** Validates and matches an order; every outcome is reported through the listener. */
    public void submit(OrderRequest request) {
        RejectReason rejection = validate(request);
        if (rejection != null) {
            listener.onEvent(new OrderRejected(++sequence, request.orderId(), rejection));
            return;
        }
        boolean market = request.type() == OrderType.MARKET;
        listener.onEvent(new OrderAccepted(++sequence, request.orderId(), request.side(), request.type(),
                market ? 0 : request.price(), request.quantity()));

        if (request.timeInForce() == TimeInForce.FOK && availableLiquidity(request) < request.quantity()) {
            listener.onEvent(new OrderCancelled(++sequence, request.orderId(), request.quantity(),
                    CancelReason.FOK_UNFILLABLE));
            return;
        }

        long remaining = match(request);
        if (remaining == 0) {
            return;
        }
        if (market) {
            listener.onEvent(new OrderCancelled(++sequence, request.orderId(), remaining, CancelReason.NO_LIQUIDITY));
        } else if (request.timeInForce() == TimeInForce.GTC) {
            rest(new Order(request.orderId(), request.side(), request.price(), remaining));
        } else {
            listener.onEvent(new OrderCancelled(++sequence, request.orderId(), remaining, CancelReason.IOC_REMAINDER));
        }
    }

    /** Cancels a resting order. Returns {@code false} (and emits a rejection) if no such order is resting. */
    public boolean cancel(long orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) {
            listener.onEvent(new OrderRejected(++sequence, orderId, RejectReason.UNKNOWN_ORDER));
            return false;
        }
        PriceLevel level = order.level;
        long remaining = order.remaining;
        level.remove(order);
        if (level.isEmpty()) {
            sideOf(order.side).remove(level.price);
        }
        listener.onEvent(new OrderCancelled(++sequence, orderId, remaining, CancelReason.USER_REQUESTED));
        return true;
    }

    // ------------------------------------------------------------------ matching

    private RejectReason validate(OrderRequest r) {
        if (r.quantity() <= 0) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (r.type() == OrderType.LIMIT && r.price() <= 0) {
            return RejectReason.INVALID_PRICE;
        }
        if (ordersById.containsKey(r.orderId())) {
            return RejectReason.DUPLICATE_ORDER_ID;
        }
        return null;
    }

    /** Matches the incoming order against the opposite side and returns the unfilled quantity. */
    private long match(OrderRequest taker) {
        NavigableMap<Long, PriceLevel> opposite = sideOf(taker.side().opposite());
        boolean market = taker.type() == OrderType.MARKET;
        long remaining = taker.quantity();

        while (remaining > 0 && !opposite.isEmpty()) {
            PriceLevel level = opposite.firstEntry().getValue();
            if (!market && !crosses(taker.side(), taker.price(), level.price)) {
                break;
            }
            Order maker = level.head();
            while (maker != null && remaining > 0) {
                Order nextMaker = maker.next;
                long fill = Math.min(remaining, maker.remaining);
                remaining -= fill;
                level.reduce(maker, fill);
                listener.onEvent(new TradeExecuted(++sequence, ++tradeCounter, taker.orderId(), maker.id,
                        taker.side(), level.price, fill));
                if (maker.remaining == 0) {
                    level.remove(maker);
                    ordersById.remove(maker.id);
                }
                maker = nextMaker;
            }
            if (level.isEmpty()) {
                opposite.remove(level.price);
            }
        }
        return remaining;
    }

    /** Would an incoming order at {@code takerPrice} trade against a resting order at {@code makerPrice}? */
    private static boolean crosses(Side takerSide, long takerPrice, long makerPrice) {
        return takerSide == Side.BUY ? makerPrice <= takerPrice : makerPrice >= takerPrice;
    }

    /** Liquidity an incoming order could consume right now; stops counting once the order would be filled. */
    private long availableLiquidity(OrderRequest taker) {
        boolean market = taker.type() == OrderType.MARKET;
        long available = 0;
        for (PriceLevel level : sideOf(taker.side().opposite()).values()) {
            if (!market && !crosses(taker.side(), taker.price(), level.price)) {
                break;
            }
            available += level.totalQuantity();
            if (available >= taker.quantity()) {
                break;
            }
        }
        return available;
    }

    private void rest(Order order) {
        NavigableMap<Long, PriceLevel> side = sideOf(order.side);
        side.computeIfAbsent(order.price, PriceLevel::new).append(order);
        ordersById.put(order.id, order);
    }

    private NavigableMap<Long, PriceLevel> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    // ------------------------------------------------------------------ queries

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    /** Best ask minus best bid, if both sides are populated. */
    public OptionalLong spread() {
        return bestBid().isPresent() && bestAsk().isPresent()
                ? OptionalLong.of(bestAsk().getAsLong() - bestBid().getAsLong())
                : OptionalLong.empty();
    }

    public boolean contains(long orderId) {
        return ordersById.containsKey(orderId);
    }

    /** Unfilled quantity of a resting order. */
    public OptionalLong remainingQuantity(long orderId) {
        Order o = ordersById.get(orderId);
        return o == null ? OptionalLong.empty() : OptionalLong.of(o.remaining);
    }

    /** Number of resting orders. */
    public int openOrders() {
        return ordersById.size();
    }

    public int levelCount(Side side) {
        return sideOf(side).size();
    }

    /** Total resting quantity at exactly this price. */
    public long quantityAt(Side side, long price) {
        PriceLevel level = sideOf(side).get(price);
        return level == null ? 0 : level.totalQuantity();
    }

    /** The best {@code maxLevels} price levels of a side, best price first. */
    public List<LevelSnapshot> depth(Side side, int maxLevels) {
        List<LevelSnapshot> out = new ArrayList<>(Math.min(maxLevels, sideOf(side).size()));
        for (PriceLevel level : sideOf(side).values()) {
            if (out.size() == maxLevels) {
                break;
            }
            out.add(new LevelSnapshot(level.price, level.totalQuantity(), level.orderCount()));
        }
        return out;
    }

    /** Human-readable ladder: asks on top (highest first), bids below. */
    public String render(int levels) {
        StringBuilder sb = new StringBuilder();
        sb.append(symbol).append('\n');
        List<LevelSnapshot> asksView = depth(Side.SELL, levels);
        for (int i = asksView.size() - 1; i >= 0; i--) {
            LevelSnapshot l = asksView.get(i);
            sb.append(String.format("  ASK %10d  x %-8d (%d orders)%n", l.price(), l.quantity(), l.orderCount()));
        }
        sb.append("  ----------\n");
        for (LevelSnapshot l : depth(Side.BUY, levels)) {
            sb.append(String.format("  BID %10d  x %-8d (%d orders)%n", l.price(), l.quantity(), l.orderCount()));
        }
        return sb.toString();
    }
}
