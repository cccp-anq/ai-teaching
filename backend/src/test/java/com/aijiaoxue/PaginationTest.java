package com.aijiaoxue;

import com.aijiaoxue.common.api.PageQuery;
import com.aijiaoxue.common.api.PageResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    @Test void offsetUsesLongAndRejectsInvalidInput() {
        var query = new PageQuery();
        assertEquals(0, query.offset());
        query.setPage(Integer.MAX_VALUE); query.setSize(100);
        assertEquals(214748364600L, query.offset());
        query.setSize(101);
        assertThrows(IllegalArgumentException.class, query::offset);
    }
    @Test void totalPagesAvoidsOverflowAndItemsAreImmutable() {
        var source = new ArrayList<>(List.of("item"));
        var result = new PageResult<>(source, Long.MAX_VALUE, 1, 20);
        source.clear();
        assertEquals(List.of("item"), result.items());
        assertEquals(Long.MAX_VALUE / 20 + 1, result.totalPages());
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
        assertEquals(0, new PageResult<>(List.of(), 0, 1, 20).totalPages());
    }
}
