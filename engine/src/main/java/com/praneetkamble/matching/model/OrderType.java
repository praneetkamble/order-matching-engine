package com.praneetkamble.matching.model;

public enum OrderType {
    /** Executes at the limit price or better; any unfilled remainder rests in the book (unless IOC/FOK). */
    LIMIT,
    /** Executes immediately against the best available prices; any unfilled remainder is cancelled. */
    MARKET
}
