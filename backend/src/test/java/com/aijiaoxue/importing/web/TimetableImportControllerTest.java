package com.aijiaoxue.importing.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aijiaoxue.importing.timetable.TimetableImportService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class TimetableImportControllerTest {
    @Test void oversizedFileIsRejectedBeforeGetBytes() throws Exception {
        var imports=mock(TimetableImportService.class);
        var file=mock(MultipartFile.class);
        long size=5L*1024*1024+1;
        when(file.getSize()).thenReturn(size);
        doThrow(new IllegalArgumentException("oversized")).when(imports).preflightSize(size);
        assertThrows(IllegalArgumentException.class,()->
                new TimetableImportController(imports).importTimetable(file,1,1));
        verify(file,never()).getBytes();
    }
}
