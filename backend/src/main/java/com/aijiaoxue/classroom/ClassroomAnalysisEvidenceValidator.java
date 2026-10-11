package com.aijiaoxue.classroom;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local evidence checks only; no model output or report is accepted by this class. */
final class ClassroomAnalysisEvidenceValidator {
    private ClassroomAnalysisEvidenceValidator() {}

    static void validateRefs(ClassroomAnalysisInputSnapshotService.RawInput input,List<EvidenceRef> refs) {
        validate(index(input),refs,0,0,false);
    }

    static void validateClaim(ClassroomAnalysisInputSnapshotService.RawInput input,
                              long startMs,long endMs,List<EvidenceRef> refs) {
        if(input==null||startMs<0||endMs<=startMs||endMs>input.durationMs()) throw invalid();
        validate(index(input),refs,startMs,endMs,true);
    }

    private static Map<EvidenceRef,ClassroomAnalysisInputSnapshotService.EvidenceItem> index(
            ClassroomAnalysisInputSnapshotService.RawInput input) {
        if(input==null||input.evidenceIndex()==null||input.transcriptSource()==null
                ||input.headUpSource()==null) throw invalid();
        Map<EvidenceRef,ClassroomAnalysisInputSnapshotService.EvidenceItem> index=new HashMap<>();
        for(var item:input.evidenceIndex()) {
            if(item==null||item.itemId()==null||item.itemId().isBlank()
                    ||item.startMs()<0||item.endMs()<=item.startMs()||item.endMs()>input.durationMs()
                    ||!("SEGMENT".equals(item.itemType())&&item.resultId()==input.transcriptSource().resultId()
                        ||"WINDOW".equals(item.itemType())&&item.resultId()==input.headUpSource().resultId()))
                throw invalid();
            var ref=new EvidenceRef(item.resultId(),item.itemType(),item.itemId());
            if(index.putIfAbsent(ref,item)!=null) throw invalid();
        }
        return index;
    }

    private static void validate(Map<EvidenceRef,ClassroomAnalysisInputSnapshotService.EvidenceItem> index,
                                 List<EvidenceRef> refs,long startMs,long endMs,boolean requireOverlap) {
        if(refs==null||refs.isEmpty()) throw invalid();
        Set<EvidenceRef> used=new HashSet<>();
        for(var ref:refs) {
            if(ref==null||!used.add(ref)) throw invalid();
            var item=index.get(ref);
            if(item==null||requireOverlap&&(startMs>=item.endMs()||endMs<=item.startMs())) throw invalid();
        }
    }

    private static EvidenceViolation invalid() {
        return new EvidenceViolation();
    }

    record EvidenceRef(long resultId,String itemType,String itemId) {}

    static final class EvidenceViolation extends IllegalArgumentException {
        EvidenceViolation() { super("analysis evidence is not bound to the prepared input and time range"); }
    }
}
