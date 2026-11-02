package com.praneetkamble.matching.event;

/** Receives book events synchronously on the matching thread; implementations must not block. */
@FunctionalInterface
public interface EventListener {

    EventListener NOOP = event -> { };

    void onEvent(BookEvent event);
}
