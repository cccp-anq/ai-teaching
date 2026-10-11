package com.aijiaoxue.common.api;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public PageResult {
        if (total < 0 || page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        items = List.copyOf(items);
    }
    public long totalPages() { return total / size + (total % size == 0 ? 0 : 1); }
}
