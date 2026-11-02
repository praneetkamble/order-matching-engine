package com.praneetkamble.matching.book;

import com.praneetkamble.matching.event.BookEvent;
import com.praneetkamble.matching.event.BookEvent.TradeExecuted;
import com.praneetkamble.matching.event.EventListener;
import java.util.ArrayList;
import java.util.List;

/** Test listener that records all events. */
final class Events implements EventListener {

    final List<BookEvent> all = new ArrayList<>();

    @Override
    public void onEvent(BookEvent event) {
        all.add(event);
    }

    List<TradeExecuted> trades() {
        return all.stream().filter(TradeExecuted.class::isInstance).map(TradeExecuted.class::cast).toList();
    }

    <T extends BookEvent> List<T> of(Class<T> type) {
        return all.stream().filter(type::isInstance).map(type::cast).toList();
    }

    void clear() {
        all.clear();
    }
}
