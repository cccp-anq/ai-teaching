package com.aijiaoxue.supervision.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class SupervisionImportBodyLimitTest {
    @Test void unknownLengthStreamStopsBeforeJacksonCanReadBeyondCap() throws Exception {
        int limit=(int)SupervisionImportBodyLimit.MAX_REQUEST_BYTES;
        var bounded=new SupervisionImportBodyLimit.BoundedInputStream(
                new ByteArrayInputStream(new byte[limit+1]),limit);
        assertEquals(limit,bounded.readNBytes(limit).length);
        assertThrows(SupervisionImportBodyLimit.BodyTooLarge.class,bounded::read);
    }

    @Test void skipCannotBypassTheByteLimit() {
        int limit=(int)SupervisionImportBodyLimit.MAX_REQUEST_BYTES;
        var bounded=new SupervisionImportBodyLimit.BoundedInputStream(
                new ByteArrayInputStream(new byte[limit+1]),limit);
        assertThrows(SupervisionImportBodyLimit.BodyTooLarge.class,()->bounded.skip(limit+1L));
    }
}
