package com.aijiaoxue.classroom;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class FfprobeClassroomMediaProbeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test void parsesStreamsAndRoundsContainerDurationToMilliseconds() {
        var result=FfprobeClassroomMediaProbe.parseProbe("""
                {"format":{"duration":"59.2496"},"streams":[
                  {"codec_type":"video"},{"codec_type":"audio"},{"codec_type":"subtitle"}]}
                """,objectMapper);

        assertAll(
                ()->assertEquals(59_250,result.durationMs()),
                ()->assertTrue(result.hasVideo()),
                ()->assertTrue(result.hasAudio()));
    }

    @Test void preservesMissingStreamFactsForUploadPolicyValidation() {
        var result=FfprobeClassroomMediaProbe.parseProbe(
                "{\"format\":{\"duration\":\"60.0\"},\"streams\":[]}",objectMapper);
        assertFalse(result.hasVideo());
        assertFalse(result.hasAudio());
    }

    @Test void coverArtDoesNotCountAsClassroomVideo() {
        var coverOnly=FfprobeClassroomMediaProbe.parseProbe("""
                {"format":{"duration":"20.0"},"streams":[
                  {"codec_type":"audio"},
                  {"codec_type":"video","disposition":{"attached_pic":1}}]}
                """,objectMapper);
        assertFalse(coverOnly.hasVideo());
        assertTrue(coverOnly.hasAudio());

        var recordingWithCover=FfprobeClassroomMediaProbe.parseProbe("""
                {"format":{"duration":"20.0"},"streams":[
                  {"codec_type":"video","disposition":{"attached_pic":0}},
                  {"codec_type":"video","disposition":{"attached_pic":1}},
                  {"codec_type":"audio"}]}
                """,objectMapper);
        assertTrue(recordingWithCover.hasVideo());
    }

    @Test void rejectsMissingInvalidAndOverflowingDuration() {
        for(String output : new String[]{
                "{}",
                "{\"format\":{\"duration\":\"N/A\"}}",
                "{\"format\":{\"duration\":\"0\"}}",
                "{\"format\":{\"duration\":\"999999999999999999999999\"}}"}) {
            var failure=assertThrows(ClassroomAnalysisException.class,
                    ()->FfprobeClassroomMediaProbe.parseProbe(output,objectMapper));
            assertEquals("VIDEO_DECODE_FAILED",failure.code());
            assertEquals(422,failure.status());
        }
    }

    @Test void reportsMissingExecutableWithoutExposingItsPath() throws Exception {
        Path testRoot=Path.of("target","test-tmp");
        Files.createDirectories(testRoot);
        Path directory=Files.createTempDirectory(testRoot,"ffprobe-missing-");
        Path media=Files.write(directory.resolve("video.mp4"),new byte[]{1});
        try {
            var properties=new ClassroomAnalysisProperties();
            properties.setFfprobePath(directory.resolve("secret-missing-ffprobe").toString());
            var probe=new FfprobeClassroomMediaProbe(properties,objectMapper);

            var failure=assertThrows(ClassroomAnalysisException.class,()->probe.probe(media));
            assertEquals("MEDIA_PROBE_UNAVAILABLE",failure.code());
            assertEquals(503,failure.status());
            assertFalse(failure.getMessage().contains(directory.toString()));
        } finally {
            Files.deleteIfExists(media);
            Files.deleteIfExists(directory);
        }
    }
}
