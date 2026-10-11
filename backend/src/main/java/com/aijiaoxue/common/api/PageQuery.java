package com.aijiaoxue.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class PageQuery {
    @Min(1)
    private int page = 1;
    @Min(1) @Max(100)
    private int size = 20;
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public long offset() {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        return ((long) page - 1) * size;
    }
}
