package com.aijiaoxue.classroom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Anonymous sampled-frame estimate; never a formal attendance record. */
final class ClassroomAttendanceEstimate {
    private ClassroomAttendanceEstimate() {}

    static Result fromEnvelope(String envelopeJson,ObjectMapper mapper) {
        if(envelopeJson==null) return new Result(null,0,null);
        try {
            JsonNode windows=mapper.readTree(envelopeJson).path("payload").path("windows");
            if(!windows.isArray()) return new Result(null,0,null);
            List<Integer> counts=new ArrayList<>();
            for(JsonNode window:windows) {
                String quality=window.path("quality").asText();
                if(!"VALID".equals(quality)&&!"NO_SUBJECTS".equals(quality)) continue;
                JsonNode visible=window.path("visibleStudentCount");
                if(!visible.isIntegralNumber()||visible.intValue()<0||visible.intValue()>1000) continue;
                JsonNode observed=window.path("validObservedCount");
                if(observed.isIntegralNumber()&&visible.intValue()<observed.intValue()) continue;
                counts.add(visible.intValue());
            }
            if(counts.size()<3) return new Result(null,counts.size(),null);
            counts.sort(Comparator.reverseOrder());
            int spread=counts.get(0)-counts.get(2);
            String confidence=counts.size()>=5&&spread<=1?"HIGH":spread<=3?"MEDIUM":"LOW";
            return new Result(counts.get(1),counts.size(),confidence);
        } catch(Exception malformed) {
            return new Result(null,0,null);
        }
    }

    record Result(Integer estimatedCount,int observedWindows,String confidence) {}
}
