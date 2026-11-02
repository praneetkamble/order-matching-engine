package com.praneetkamble.matching.event;

public enum CancelReason {
    /** Explicit cancel request for a resting order. */
    USER_REQUESTED,
    /** Remainder of an IOC limit order that could not be matched. */
    IOC_REMAINDER,
    /** Remainder of a market order for which the book ran out of liquidity. */
    NO_LIQUIDITY,
    /** A fill-or-kill order that could not be executed in full. */
    FOK_UNFILLABLE
}
