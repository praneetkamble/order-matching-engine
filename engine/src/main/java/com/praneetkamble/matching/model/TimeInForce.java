package com.praneetkamble.matching.model;

public enum TimeInForce {
    /** Good till cancelled: the unfilled remainder of a limit order rests in the book. */
    GTC,
    /** Immediate or cancel: fill what is possible right now, cancel the rest. */
    IOC,
    /** Fill or kill: either the whole quantity executes immediately or the order is cancelled untouched. */
    FOK
}
