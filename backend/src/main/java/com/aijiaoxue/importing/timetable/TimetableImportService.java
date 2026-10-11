package com.aijiaoxue.importing.timetable;

import com.aijiaoxue.identity.CurrentUserProvider;
import com.aijiaoxue.identity.PermissionService;
import com.aijiaoxue.importing.SourceKeys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorized application boundary for one safe, atomic HTML timetable import. */
@Service
public class TimetableImportService {
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final String SOURCE_TYPE = "HTML_TIMETABLE";
    static final String SOURCE_NAMESPACE = "HTML_TIMETABLE_V1";
    static final String PARSER_VERSION = "html-timetable-v1";
    private static final Logger LOG = LoggerFactory.getLogger(TimetableImportService.class);

    private final HtmlTimetableParser parser;
    private final PermissionService permissions;
    private final CurrentUserProvider currentUsers;
    private final TimetableImportMapper mapper;
    private final TransactionTemplate transactions;
    private final ImportTransactionCheckpoint checkpoint;

    TimetableImportService(HtmlTimetableParser parser, PermissionService permissions,
                           CurrentUserProvider currentUsers, TimetableImportMapper mapper,
                           PlatformTransactionManager transactionManager,
                           ImportTransactionCheckpoint checkpoint) {
        this.parser = parser;
        this.permissions = permissions;
        this.currentUsers = currentUsers;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.checkpoint = checkpoint;
    }

    /** Reject before MultipartFile.getBytes() can allocate a video-sized byte array. */
    public void preflightSize(long size) {
        var user = currentUsers.requireCurrentUser();
        permissions.requireTimetableImport(user);
        if (size > MAX_FILE_BYTES) reject(HttpStatus.PAYLOAD_TOO_LARGE,
                TimetableImportResult.rejected(TimetableImportStatus.FAILED, 0,
                        List.of(error(null, "file", "Parser", "FILE_TOO_LARGE",
                                "HTML file exceeds the 5 MiB limit"))));
    }

    public TimetableImportResult importHtml(String originalFileName, byte[] sourceBytes,
                                            long semesterId, long sourceScopeCollegeId) {
        var user = currentUsers.requireCurrentUser();
        permissions.requireTimetableImport(user);

        String fileName = safeFileName(originalFileName);
        List<TimetableImportError> requestErrors = validateRequest(fileName, sourceBytes,
                semesterId, sourceScopeCollegeId);
        if (!requestErrors.isEmpty()) {
            HttpStatus status = requestErrors.stream()
                    .anyMatch(error -> "FILE_TOO_LARGE".equals(error.errorCode()))
                    ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY;
            reject(status,
                    TimetableImportResult.rejected(TimetableImportStatus.FAILED, 0, requestErrors));
        }

        TimetableParseResult parsed = parser.parse(sourceBytes);
        if (!parsed.validationErrors().isEmpty()) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    TimetableImportResult.rejected(TimetableImportStatus.FAILED,
                            parsed.totalSourceRows(), parsed.validationErrors().stream()
                                    .map(TimetableImportError::parser).toList()));
        }
        if (parsed.totalSourceRows() == 0) {
            reject(HttpStatus.UNPROCESSABLE_ENTITY,
                    TimetableImportResult.rejected(TimetableImportStatus.FAILED, 0,
                            List.of(error(null, null, "Parser", "EMPTY_TIMETABLE",
                                    "COURSES must contain at least one row"))));
        }

        String sourceSha256 = SourceKeys.contentSha256(sourceBytes);
        try {
            TransactionOutcome outcome = transactions.execute(status -> executeImport(
                    parsed, fileName, sourceSha256, semesterId, sourceScopeCollegeId, user.userId()));
            if (outcome == null) {
                throw new IllegalStateException("Import transaction returned no outcome");
            }
            if (outcome.rejectionStatus() != null) {
                reject(outcome.rejectionStatus(), outcome.result());
            }
            return outcome.result();
        } catch (TimetableImportRejectedException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            LOG.error("Timetable import database failure; sourceSha256={}", sourceSha256, exception);
            reject(HttpStatus.INTERNAL_SERVER_ERROR, persistenceFailure(parsed.totalSourceRows()));
            throw new IllegalStateException("unreachable");
        } catch (RuntimeException exception) {
            LOG.error("Timetable import transaction failure; sourceSha256={}", sourceSha256, exception);
            reject(HttpStatus.INTERNAL_SERVER_ERROR, persistenceFailure(parsed.totalSourceRows()));
            throw new IllegalStateException("unreachable");
        }
    }

    private TransactionOutcome executeImport(TimetableParseResult parsed, String fileName,
                                             String sourceSha256, long semesterId,
                                             long sourceScopeCollegeId, long operatorUserId) {
        TimetableImportMapper.CollegeRef college = mapper.collegeForUpdate(sourceScopeCollegeId);
        if (college == null) {
            return rejected(HttpStatus.UNPROCESSABLE_ENTITY, parsed.totalSourceRows(),
                    error(null, "sourceScopeCollegeId", "College", "UNKNOWN_SOURCE_SCOPE",
                            "sourceScopeCollegeId=" + sourceScopeCollegeId + " does not exist"));
        }
        TimetableImportMapper.SemesterRef semester = mapper.semester(semesterId);
        if (semester == null) {
            return rejected(HttpStatus.UNPROCESSABLE_ENTITY, parsed.totalSourceRows(),
                    error(null, "semesterId", "Semester", "UNKNOWN_SEMESTER",
                            "semesterId=" + semesterId + " does not exist"));
        }

        TimetableImportMapper.BatchRecord duplicate = mapper.successfulBatch(
                sourceSha256, semesterId, sourceScopeCollegeId);
        if (duplicate != null) {
            return successful(new TimetableImportResult(duplicate.id(),
                    TimetableImportStatus.ALREADY_IMPORTED, duplicate.totalRows(),
                    duplicate.successRows(), duplicate.failureRows(),
                    0, 0, 0, 0, 0, 0, 0, 0, List.of()));
        }

        Plan plan = buildPlan(parsed.parsedRows(), semester, college);
        if (!plan.errors.isEmpty()) {
            return new TransactionOutcome(TimetableImportResult.rejected(
                    TimetableImportStatus.CONFLICT, parsed.totalSourceRows(), plan.errors),
                    HttpStatus.CONFLICT);
        }

        Resolutions resolutions = resolveExisting(plan, semesterId, sourceScopeCollegeId);
        if (!resolutions.errors.isEmpty()) {
            return new TransactionOutcome(TimetableImportResult.rejected(
                    TimetableImportStatus.CONFLICT, parsed.totalSourceRows(), resolutions.errors),
                    HttpStatus.CONFLICT);
        }

        LocalDateTime startedAt = LocalDateTime.now();
        var batchId = new TimetableImportMapper.GeneratedId();
        mapper.insertBatch(batchId, new TimetableImportMapper.NewBatch(
                SOURCE_TYPE, SOURCE_NAMESPACE, fileName, sourceScopeCollegeId, semesterId,
                fileName, sourceSha256, PARSER_VERSION, parsed.totalSourceRows(),
                operatorUserId, startedAt));
        long persistedBatchId = requiredId(batchId, "ImportBatch");

        int createdOfferings = createOfferings(plan, resolutions, semesterId, sourceScopeCollegeId);
        int createdLocations = createLocations(plan, resolutions);
        int createdTeachers = createTeachers(plan, resolutions);
        int createdSchedules = createSchedules(plan, resolutions);

        for (RowPlan row : plan.rows) {
            long offeringId = requiredResolution(resolutions.offeringIds, row.offeringKey, "CourseOffering");
            long scheduleId = requiredResolution(resolutions.scheduleIds, row.scheduleKey, "ClassSchedule");
            mapper.insertSourceRow(new TimetableImportMapper.NewSourceRow(
                    persistedBatchId, row.source,
                    SourceKeys.contentSha256(row.source.rawJson().getBytes(StandardCharsets.UTF_8)),
                    offeringId, scheduleId));
        }
        verifyIntegrity(plan, resolutions);
        LocalDateTime finishedAt = LocalDateTime.now();
        if (mapper.finishBatch(persistedBatchId, finishedAt, parsed.totalSourceRows()) != 1) {
            throw new IllegalStateException("ImportBatch lifecycle update failed");
        }

        return successful(new TimetableImportResult(persistedBatchId, TimetableImportStatus.SUCCESS,
                parsed.totalSourceRows(), parsed.totalSourceRows(), 0,
                createdOfferings, resolutions.existingOfferingKeys.size(),
                createdSchedules, resolutions.existingScheduleKeys.size(),
                createdTeachers, resolutions.existingTeacherKeys.size(),
                createdLocations, resolutions.existingLocationKeys.size(), List.of()));
    }

    private Plan buildPlan(List<TimetableSourceRow> rows, TimetableImportMapper.SemesterRef semester,
                           TimetableImportMapper.CollegeRef college) {
        Plan plan = new Plan();
        for (TimetableSourceRow source : rows) {
            validateLengths(source, plan.errors);
            String offeringKey = SourceKeys.offering(SOURCE_NAMESPACE, semester.code(), college.code(),
                    source.displayName(), source.rawT().trim(), source.classGroupText());
            String scheduleKey = SourceKeys.schedule(offeringKey, source.weekMask(), source.weekday(),
                    source.startSection(), source.endSection(), source.locationName());
            OfferingPlan offering = plan.offerings.computeIfAbsent(offeringKey,
                    ignored -> new OfferingPlan(source, offeringKey));
            if (offering.studentCount != source.studentCount()) {
                plan.errors.add(error(source.sourceIndex(), "p", "CourseOffering",
                        "SOURCE_OFFERING_STUDENT_COUNT_CONFLICT",
                        "expected p=" + offering.studentCount + " but source row contains p="
                                + source.studentCount()));
            }

            String locationType = locationType(source.locationName(), source.locationGroup());
            String locationIdentity = locationIdentity(source.locationName(), source.locationGroup());
            plan.locations.putIfAbsent(locationIdentity,
                    new LocationPlan(source.sourceIndex(), source.locationName(),
                            source.locationGroup(), locationType));

            LinkedHashSet<String> teacherKeys = new LinkedHashSet<>();
            for (String teacherName : source.teacherNames()) {
                String teacherKey = SourceKeys.teacher(SOURCE_NAMESPACE, college.code(), teacherName);
                teacherKeys.add(teacherKey);
                plan.teachers.putIfAbsent(teacherKey,
                        new TeacherPlan(source.sourceIndex(), teacherName, teacherKey));
            }
            SchedulePlan candidate = new SchedulePlan(source, scheduleKey, offeringKey,
                    locationIdentity, locationType, teacherKeys);
            SchedulePlan previous = plan.schedules.putIfAbsent(scheduleKey, candidate);
            if (previous != null && !previous.compatible(candidate)) {
                plan.errors.add(error(source.sourceIndex(), null, "ClassSchedule",
                        "SOURCE_SCHEDULE_CONFLICT",
                        "rows with the same source schedule key disagree on location group, week text, or teachers"));
            }
            offering.scheduleKeys.add(scheduleKey);
            plan.rows.add(new RowPlan(source, offeringKey, scheduleKey));
        }
        return plan;
    }

    private Resolutions resolveExisting(Plan plan, long semesterId, long sourceScopeCollegeId) {
        Resolutions resolutions = new Resolutions();
        for (OfferingPlan expected : plan.offerings.values()) {
            TimetableImportMapper.OfferingRecord actual = mapper.offering(expected.key);
            if (actual == null) continue;
            resolutions.offeringIds.put(expected.key, actual.id());
            resolutions.existingOfferingKeys.add(expected.key);
            if (actual.semesterId() != semesterId
                    || actual.sourceScopeCollegeId() != sourceScopeCollegeId
                    || !actual.displayName().equals(expected.displayName)
                    || !actual.activityType().equals(expected.activityType)
                    || !actual.classGroupText().equals(expected.classGroupText)
                    || !Objects.equals(actual.studentCount(), expected.studentCount)) {
                resolutions.errors.add(error(expected.firstSourceIndex, null, "CourseOffering",
                        "EXISTING_OFFERING_CONFLICT",
                        "existing source_offering_key has incompatible normalized source content"));
            }
        }
        for (Map.Entry<String, LocationPlan> entry : plan.locations.entrySet()) {
            LocationPlan expected = entry.getValue();
            TimetableImportMapper.LocationRecord actual = mapper.location(
                    expected.normalizedName, expected.locationGroup);
            if (actual == null) continue;
            resolutions.locationIds.put(entry.getKey(), actual.id());
            resolutions.existingLocationKeys.add(entry.getKey());
            if (!locationCompatible(actual, expected)) {
                resolutions.errors.add(error(expected.firstSourceIndex, "l", "Location",
                        "EXISTING_LOCATION_CONFLICT",
                        "existing location identity has incompatible normalized content"));
            }
        }
        for (TeacherPlan expected : plan.teachers.values()) {
            List<TimetableImportMapper.TeacherRecord> actual = mapper.teachers(expected.key);
            if (actual.size() > 1) {
                resolutions.errors.add(error(expected.firstSourceIndex, "h", "Teacher",
                        "AMBIGUOUS_SOURCE_TEACHER",
                        "multiple teachers already use the same source_teacher_key"));
            } else if (actual.size() == 1) {
                TimetableImportMapper.TeacherRecord teacher = actual.get(0);
                resolutions.teacherIds.put(expected.key, teacher.id());
                resolutions.existingTeacherKeys.add(expected.key);
                if (!teacher.displayName().equals(expected.displayName)
                        || !teacher.normalizedName().equals(expected.normalizedName)) {
                    resolutions.errors.add(error(expected.firstSourceIndex, "h", "Teacher",
                            "EXISTING_TEACHER_CONFLICT",
                            "existing source_teacher_key has incompatible normalized source content"));
                }
            }
        }
        for (SchedulePlan expected : plan.schedules.values()) {
            TimetableImportMapper.ScheduleRecord actual = mapper.schedule(expected.key);
            if (actual == null) continue;
            resolutions.scheduleIds.put(expected.key, actual.id());
            resolutions.existingScheduleKeys.add(expected.key);
            Long expectedOfferingId = resolutions.offeringIds.get(expected.offeringKey);
            LocationPlan location = plan.locations.get(expected.locationIdentity);
            boolean scalarCompatible = expectedOfferingId != null && actual.offeringId() == expectedOfferingId
                    && actual.weekday() == expected.weekday && actual.startSection() == expected.startSection
                    && actual.endSection() == expected.endSection && actual.weekMask().equals(expected.weekMask)
                    && actual.rawWeekText().equals(expected.rawWeekText)
                    && actual.locationNormalizedName().equals(location.normalizedName)
                    && actual.locationGroup().equals(location.locationGroup)
                    && actual.locationType().equals(location.locationType);
            Set<Long> expectedTeacherIds = new LinkedHashSet<>();
            for (String teacherKey : expected.teacherKeys) {
                Long id = resolutions.teacherIds.get(teacherKey);
                if (id != null) expectedTeacherIds.add(id);
            }
            Set<Long> actualTeacherIds = new LinkedHashSet<>(mapper.scheduleTeacherIds(actual.id()));
            List<Integer> actualWeeks = mapper.scheduleWeeks(actual.id());
            if (!scalarCompatible || expectedTeacherIds.size() != expected.teacherKeys.size()
                    || !actualTeacherIds.equals(expectedTeacherIds)
                    || !actualWeeks.equals(expected.weekNumbers)) {
                resolutions.errors.add(error(expected.firstSourceIndex, null, "ClassSchedule",
                        "EXISTING_SCHEDULE_CONFLICT",
                        "existing source_schedule_key has incompatible schedule, week, location, or teacher content"));
            }
        }
        return resolutions;
    }

    private int createOfferings(Plan plan, Resolutions resolutions, long semesterId,
                                long sourceScopeCollegeId) {
        int created = 0;
        for (OfferingPlan row : plan.offerings.values()) {
            if (resolutions.offeringIds.containsKey(row.key)) continue;
            var holder = new TimetableImportMapper.GeneratedId();
            mapper.insertOffering(holder, new TimetableImportMapper.NewOffering(semesterId,
                    sourceScopeCollegeId, row.key, row.displayName, row.activityType,
                    row.classGroupText, row.studentCount, "SOURCE_ONLY"));
            resolutions.offeringIds.put(row.key, requiredId(holder, "CourseOffering"));
            created++;
        }
        return created;
    }

    private int createLocations(Plan plan, Resolutions resolutions) {
        int created = 0;
        for (Map.Entry<String, LocationPlan> entry : plan.locations.entrySet()) {
            if (resolutions.locationIds.containsKey(entry.getKey())) continue;
            LocationPlan row = entry.getValue();
            var holder = new TimetableImportMapper.GeneratedId();
            try {
                mapper.insertLocation(holder, new TimetableImportMapper.NewLocation(row.displayName,
                        row.normalizedName, row.locationGroup, row.locationType));
                resolutions.locationIds.put(entry.getKey(), requiredId(holder, "Location"));
                created++;
            } catch (DuplicateKeyException exception) {
                TimetableImportMapper.LocationRecord concurrent = mapper.locationForUpdate(
                        row.normalizedName, row.locationGroup);
                if (concurrent == null) throw exception;
                if (!locationCompatible(concurrent, row)) {
                    reject(HttpStatus.CONFLICT, TimetableImportResult.rejected(
                            TimetableImportStatus.CONFLICT, plan.rows.size(),
                            List.of(error(row.firstSourceIndex, "l", "Location",
                                    "EXISTING_LOCATION_CONFLICT",
                                    "existing location identity has incompatible normalized content"))));
                }
                resolutions.locationIds.put(entry.getKey(), concurrent.id());
                resolutions.existingLocationKeys.add(entry.getKey());
            }
        }
        return created;
    }

    private boolean locationCompatible(TimetableImportMapper.LocationRecord actual,
                                       LocationPlan expected) {
        return actual.displayName().equals(expected.displayName)
                && actual.normalizedName().equals(expected.normalizedName)
                && actual.locationGroup().equals(expected.locationGroup)
                && actual.locationType().equals(expected.locationType);
    }

    private int createTeachers(Plan plan, Resolutions resolutions) {
        int created = 0;
        for (TeacherPlan row : plan.teachers.values()) {
            if (resolutions.teacherIds.containsKey(row.key)) continue;
            var holder = new TimetableImportMapper.GeneratedId();
            mapper.insertTeacher(holder, new TimetableImportMapper.NewTeacher(row.displayName,
                    row.normalizedName, row.key, "NAME_ONLY"));
            resolutions.teacherIds.put(row.key, requiredId(holder, "Teacher"));
            created++;
        }
        return created;
    }

    private int createSchedules(Plan plan, Resolutions resolutions) {
        int created = 0;
        for (SchedulePlan row : plan.schedules.values()) {
            if (resolutions.scheduleIds.containsKey(row.key)) continue;
            long offeringId = requiredResolution(resolutions.offeringIds, row.offeringKey, "CourseOffering");
            long locationId = requiredResolution(resolutions.locationIds, row.locationIdentity, "Location");
            var holder = new TimetableImportMapper.GeneratedId();
            mapper.insertSchedule(holder, new TimetableImportMapper.NewSchedule(offeringId,
                    row.weekday, row.startSection, row.endSection, locationId, row.weekMask,
                    row.rawWeekText, row.key));
            long scheduleId = requiredId(holder, "ClassSchedule");
            resolutions.scheduleIds.put(row.key, scheduleId);
            for (int week : row.weekNumbers) mapper.insertScheduleWeek(scheduleId, week);
            for (String teacherKey : row.teacherKeys) {
                mapper.insertScheduleTeacher(scheduleId,
                        requiredResolution(resolutions.teacherIds, teacherKey, "Teacher"));
            }
            checkpoint.afterScheduleWrite();
            created++;
        }
        return created;
    }

    private void verifyIntegrity(Plan plan, Resolutions resolutions) {
        for (OfferingPlan offering : plan.offerings.values()) {
            long id = requiredResolution(resolutions.offeringIds, offering.key, "CourseOffering");
            if (mapper.scheduleCount(id) < 1) {
                throw new IllegalStateException("CourseOffering has no ClassSchedule");
            }
        }
        for (SchedulePlan schedule : plan.schedules.values()) {
            long id = requiredResolution(resolutions.scheduleIds, schedule.key, "ClassSchedule");
            if (mapper.weekCount(id) < 1 || mapper.teacherCount(id) < 1) {
                throw new IllegalStateException("ClassSchedule integrity check failed");
            }
        }
    }

    private void validateLengths(TimetableSourceRow row, List<TimetableImportError> errors) {
        length(row, "n", row.rawN(), 255, errors);
        length(row, "t", row.rawT(), 32, errors);
        length(row, "c", row.rawC(), 1000, errors);
        length(row, "w", row.rawW(), 255, errors);
        length(row, "rw", row.rawRw(), 255, errors);
        length(row, "s", row.rawS(), 32, errors);
        length(row, "h", row.rawH(), 1000, errors);
        length(row, "l", row.rawL(), 255, errors);
        length(row, "g", row.rawG(), 64, errors);
        for (String teacher : row.teacherNames()) length(row, "h", teacher, 255, errors);
        if (row.startSection() > Short.MAX_VALUE || row.endSection() > Short.MAX_VALUE) {
            errors.add(error(row.sourceIndex(), "s", "ClassSchedule", "VALUE_OUT_OF_RANGE",
                    "section number exceeds SMALLINT range"));
        }
    }

    private void length(TimetableSourceRow row, String field, String value, int maximum,
                        List<TimetableImportError> errors) {
        if (value.length() > maximum) {
            errors.add(error(row.sourceIndex(), field, "SourceTimetableRow", "VALUE_TOO_LONG",
                    field + " exceeds maximum length " + maximum));
        }
    }

    private List<TimetableImportError> validateRequest(String fileName, byte[] bytes,
                                                        long semesterId, long collegeId) {
        List<TimetableImportError> errors = new ArrayList<>();
        if (fileName == null || fileName.isBlank()) {
            errors.add(error(null, "file", "ImportBatch", "INVALID_FILE_NAME",
                    "an HTML file name is required"));
        } else {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".html") || lower.endsWith(".htm"))) {
                errors.add(error(null, "file", "ImportBatch", "UNSUPPORTED_FILE_TYPE",
                        "file name must end in .html or .htm"));
            }
            if (fileName.length() > 255) {
                errors.add(error(null, "file", "ImportBatch", "FILE_NAME_TOO_LONG",
                        "file name exceeds maximum length 255"));
            }
        }
        if (bytes == null || bytes.length == 0) {
            errors.add(error(null, "file", "Parser", "INVALID_SOURCE_DOCUMENT",
                    "source document is empty"));
        } else if (bytes.length > MAX_FILE_BYTES) {
            errors.add(error(null, "file", "Parser", "FILE_TOO_LARGE",
                    "HTML file exceeds the 5 MiB limit"));
        }
        if (semesterId < 1) errors.add(error(null, "semesterId", "Semester", "INVALID_REFERENCE",
                "semesterId must be positive"));
        if (collegeId < 1) errors.add(error(null, "sourceScopeCollegeId", "College", "INVALID_REFERENCE",
                "sourceScopeCollegeId must be positive"));
        return errors;
    }

    private String safeFileName(String original) {
        if (original == null) return null;
        int separator = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        return original.substring(separator + 1).trim();
    }

    private String locationType(String name, String group) {
        if ("线上".equals(group)) return "ONLINE";
        if ("待定".equals(name)) return "TBD";
        return "PHYSICAL";
    }

    private String locationIdentity(String name, String group) {
        return name + "\u0000" + group;
    }

    private long requiredId(TimetableImportMapper.GeneratedId holder, String domain) {
        if (holder.getId() == null) throw new IllegalStateException(domain + " generated no id");
        return holder.getId();
    }

    private long requiredResolution(Map<String, Long> ids, String key, String domain) {
        Long id = ids.get(key);
        if (id == null) throw new IllegalStateException(domain + " resolution missing");
        return id;
    }

    private TimetableImportResult persistenceFailure(int totalRows) {
        return TimetableImportResult.rejected(TimetableImportStatus.FAILED, totalRows,
                List.of(error(null, null, "Persistence", "IMPORT_PERSISTENCE_ERROR",
                        "import transaction failed and was rolled back")));
    }

    private TransactionOutcome rejected(HttpStatus status, int totalRows, TimetableImportError error) {
        return new TransactionOutcome(TimetableImportResult.rejected(
                TimetableImportStatus.FAILED, totalRows, List.of(error)), status);
    }

    private TransactionOutcome successful(TimetableImportResult result) {
        return new TransactionOutcome(result, null);
    }

    private void reject(HttpStatus status, TimetableImportResult result) {
        throw new TimetableImportRejectedException(status, result);
    }

    private static TimetableImportError error(Integer sourceIndex, String field, String domain,
                                              String code, String reason) {
        return new TimetableImportError(sourceIndex, field, domain, code, reason);
    }

    private record TransactionOutcome(TimetableImportResult result, HttpStatus rejectionStatus) {}
    private record RowPlan(TimetableSourceRow source, String offeringKey, String scheduleKey) {}

    private static final class Plan {
        final Map<String, OfferingPlan> offerings = new LinkedHashMap<>();
        final Map<String, SchedulePlan> schedules = new LinkedHashMap<>();
        final Map<String, TeacherPlan> teachers = new LinkedHashMap<>();
        final Map<String, LocationPlan> locations = new LinkedHashMap<>();
        final List<RowPlan> rows = new ArrayList<>();
        final List<TimetableImportError> errors = new ArrayList<>();
    }

    private static final class Resolutions {
        final Map<String, Long> offeringIds = new LinkedHashMap<>();
        final Map<String, Long> scheduleIds = new LinkedHashMap<>();
        final Map<String, Long> teacherIds = new LinkedHashMap<>();
        final Map<String, Long> locationIds = new LinkedHashMap<>();
        final Set<String> existingOfferingKeys = new LinkedHashSet<>();
        final Set<String> existingScheduleKeys = new LinkedHashSet<>();
        final Set<String> existingTeacherKeys = new LinkedHashSet<>();
        final Set<String> existingLocationKeys = new LinkedHashSet<>();
        final List<TimetableImportError> errors = new ArrayList<>();
    }

    private static final class OfferingPlan {
        final int firstSourceIndex;
        final String key;
        final String displayName;
        final String activityType;
        final String classGroupText;
        final int studentCount;
        final Set<String> scheduleKeys = new LinkedHashSet<>();
        OfferingPlan(TimetableSourceRow row, String key) {
            this.firstSourceIndex = row.sourceIndex();
            this.key = key;
            this.displayName = row.displayName();
            this.activityType = row.activityType().name();
            this.classGroupText = row.classGroupText();
            this.studentCount = row.studentCount();
        }
    }

    private static final class LocationPlan {
        final int firstSourceIndex;
        final String displayName;
        final String normalizedName;
        final String locationGroup;
        final String locationType;
        LocationPlan(int firstSourceIndex, String displayName, String locationGroup, String locationType) {
            this.firstSourceIndex = firstSourceIndex;
            this.displayName = displayName;
            this.normalizedName = displayName;
            this.locationGroup = locationGroup;
            this.locationType = locationType;
        }
    }

    private static final class TeacherPlan {
        final int firstSourceIndex;
        final String displayName;
        final String normalizedName;
        final String key;
        TeacherPlan(int firstSourceIndex, String displayName, String key) {
            this.firstSourceIndex = firstSourceIndex;
            this.displayName = displayName;
            this.normalizedName = displayName;
            this.key = key;
        }
    }

    private static final class SchedulePlan {
        final int firstSourceIndex;
        final String key;
        final String offeringKey;
        final String locationIdentity;
        final String locationType;
        final int weekday;
        final int startSection;
        final int endSection;
        final String weekMask;
        final List<Integer> weekNumbers;
        final String rawWeekText;
        final Set<String> teacherKeys;
        SchedulePlan(TimetableSourceRow row, String key, String offeringKey,
                     String locationIdentity, String locationType, Set<String> teacherKeys) {
            this.firstSourceIndex = row.sourceIndex();
            this.key = key;
            this.offeringKey = offeringKey;
            this.locationIdentity = locationIdentity;
            this.locationType = locationType;
            this.weekday = row.weekday();
            this.startSection = row.startSection();
            this.endSection = row.endSection();
            this.weekMask = row.weekMask();
            this.weekNumbers = row.weekNumbers();
            this.rawWeekText = row.rawWeekText();
            this.teacherKeys = Set.copyOf(teacherKeys);
        }
        boolean compatible(SchedulePlan other) {
            return offeringKey.equals(other.offeringKey)
                    && locationIdentity.equals(other.locationIdentity)
                    && locationType.equals(other.locationType)
                    && weekday == other.weekday && startSection == other.startSection
                    && endSection == other.endSection && weekMask.equals(other.weekMask)
                    && weekNumbers.equals(other.weekNumbers) && rawWeekText.equals(other.rawWeekText)
                    && teacherKeys.equals(other.teacherKeys);
        }
    }
}
