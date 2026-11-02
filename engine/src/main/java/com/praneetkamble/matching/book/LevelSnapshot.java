package com.praneetkamble.matching.book;

/** Aggregated view of one price level. */
public record LevelSnapshot(long price, long quantity, int orderCount) {
}
