package com.aijiaoxue.course.access;

import com.aijiaoxue.common.api.PageResult;
import com.aijiaoxue.common.net.SafeLocator;
import com.aijiaoxue.course.comparison.TeacherComparison;
import com.aijiaoxue.course.comparison.TeacherComparisonOffering;
import com.aijiaoxue.course.comparison.TeacherComparisonRejectedException;
import com.aijiaoxue.course.comparison.TeacherComparisonResult;
import com.aijiaoxue.course.detail.CollegeDetail;
import com.aijiaoxue.course.detail.CourseOfferingDetail;
import com.aijiaoxue.course.detail.LocationDetail;
import com.aijiaoxue.course.detail.ScheduleDetail;
import com.aijiaoxue.course.detail.SemesterDetail;
import com.aijiaoxue.course.detail.SyllabusStatus;
import com.aijiaoxue.course.detail.TeacherDetail;
import com.aijiaoxue.course.query.CourseOfferingListItem;
import com.aijiaoxue.course.query.CourseOfferingQueryCriteria;
import com.aijiaoxue.identity.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuthorizedOfferingRepository {
    private final OfferingAccessMapper mapper;
    private final CurrentUserProvider currentUsers;
    private final AuthorizedScopeProvider scopes;
    public AuthorizedOfferingRepository(OfferingAccessMapper mapper, CurrentUserProvider currentUsers, AuthorizedScopeProvider scopes) {
        this.mapper = mapper; this.currentUsers = currentUsers; this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public List<OfferingReference> list(Long teacherFilter, Long sourceCollegeFilter) {
        return mapper.select(scopes.resolve(currentUsers.requireCurrentUser(), AuthorizedScopeProvider.Purpose.READ),
                null, teacherFilter, sourceCollegeFilter);
    }

    @Transactional(readOnly = true)
    public OfferingReference requireVisible(long offeringId) {
        var result = mapper.select(scopes.resolve(currentUsers.requireCurrentUser(), AuthorizedScopeProvider.Purpose.READ),
                offeringId, null, null);
        if (result.isEmpty()) throw new NotVisibleException();
        return result.get(0);
    }

    @Transactional(readOnly = true)
    public boolean visibleForPurpose(CurrentUser user, long offeringId,
                                     AuthorizedScopeProvider.Purpose purpose) {
        if (purpose == AuthorizedScopeProvider.Purpose.READ)
            throw new IllegalArgumentException("Use requireVisible for general read access");
        return !mapper.select(scopes.resolve(user, purpose), offeringId, null, null).isEmpty();
    }

    @Transactional(readOnly = true)
    public PageResult<CourseOfferingListItem> search(CurrentUser currentUser,
                                                     CourseOfferingQueryCriteria criteria) {
        return search(scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ), criteria);
    }

    /**
     * Returns the complete personal-purpose dataset without allowing GLOBAL read grants
     * to masquerade as a person's own teaching or supervision schedule.
     */
    @Transactional(readOnly = true)
    public List<CourseOfferingListItem> listByPurpose(CurrentUser currentUser,
                                                       AuthorizedScopeProvider.Purpose purpose) {
        if (purpose == AuthorizedScopeProvider.Purpose.READ)
            throw new IllegalArgumentException("personal schedule requires a personal purpose");
        var scope = scopes.resolve(currentUser, purpose);
        if (!scope.global() && scope.teacherIds().isEmpty() && scope.offeringIds().isEmpty()) return List.of();

        var firstCriteria = new CourseOfferingQueryCriteria(null, null, null, null,
                null, null, null, null, null, null, 1, 100);
        var first = search(scope, firstCriteria);
        var all = new ArrayList<>(first.items());
        for (int page = 2; all.size() < first.total(); page++) {
            var criteria = new CourseOfferingQueryCriteria(null, null, null, null,
                    null, null, null, null, null, null, page, 100);
            all.addAll(search(scope, criteria).items());
        }
        return List.copyOf(all);
    }

    private PageResult<CourseOfferingListItem> search(OfferingScope scope,
                                                       CourseOfferingQueryCriteria criteria) {
        long total = mapper.countSearch(scope, criteria);
        if (total == 0) return new PageResult<>(List.of(), 0, criteria.page(), criteria.size());

        var rows = mapper.selectSearchPage(scope, criteria, criteria.offset(), criteria.size());
        if (rows.isEmpty()) return new PageResult<>(List.of(), total, criteria.page(), criteria.size());

        var offeringIds = rows.stream().map(OfferingQueryRow::offeringId).toList();
        var scheduleRows = mapper.selectListSchedules(offeringIds);
        var scheduleIds = scheduleRows.stream().map(OfferingListScheduleRow::scheduleId).toList();
        var weekRows = scheduleIds.isEmpty() ? List.<ScheduleWeekRow>of() : mapper.selectDetailWeeks(scheduleIds);
        var teacherRows = scheduleIds.isEmpty() ? List.<ScheduleTeacherRow>of() : mapper.selectDetailTeachers(scheduleIds);
        var weeksBySchedule = weeksBySchedule(weekRows);
        var teachersBySchedule = teachersBySchedule(teacherRows);
        var schedulesByOffering = new LinkedHashMap<Long, List<ScheduleDetail>>();
        for (var schedule : scheduleRows)
            schedulesByOffering.computeIfAbsent(schedule.offeringId(), ignored -> new ArrayList<>())
                    .add(toScheduleDetail(schedule.schedule(), weeksBySchedule, teachersBySchedule));

        var teachersByOffering = new LinkedHashMap<Long, LinkedHashMap<Long, String>>();
        var offeringBySchedule = new LinkedHashMap<Long, Long>();
        for (var schedule : scheduleRows) offeringBySchedule.put(schedule.scheduleId(), schedule.offeringId());
        for (var teacher : teacherRows) {
            Long offeringId = offeringBySchedule.get(teacher.scheduleId());
            if (offeringId != null) teachersByOffering
                    .computeIfAbsent(offeringId, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(teacher.teacherId(), teacher.displayName());
        }
        var teacherNames = new LinkedHashMap<Long, List<String>>();
        teachersByOffering.forEach((offeringId, teachers) -> teacherNames.put(offeringId,
                teachers.entrySet().stream()
                        .sorted(Map.Entry.<Long, String>comparingByValue().thenComparingLong(Map.Entry::getKey))
                        .map(Map.Entry::getValue).toList()));

        var items = rows.stream().map(row -> new CourseOfferingListItem(
                row.offeringId(), row.displayName(), row.courseCode(), row.activityType(),
                row.classGroupText(), row.studentCount(), row.semesterId(), row.semesterCode(),
                row.semesterName(), row.sourceScopeCollegeId(),
                row.ownerCollegeId() == null ? null : new CollegeDetail(
                        row.ownerCollegeId(), row.ownerCollegeCode(), row.ownerCollegeName()),
                syllabusStatus(row.syllabusRef()), safeSyllabusLocator(row.syllabusRef()),
                teacherNames.getOrDefault(row.offeringId(), List.of()),
                schedulesByOffering.getOrDefault(row.offeringId(), List.of()))).toList();
        return new PageResult<>(items, total, criteria.page(), criteria.size());
    }

    @Transactional(readOnly = true)
    public CourseOfferingDetail getDetail(CurrentUser currentUser, long offeringId) {
        var scope = scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ);
        var base = mapper.selectDetailBase(scope, offeringId);
        if (base == null) throw new NotVisibleException();

        var scheduleRows = mapper.selectDetailSchedules(offeringId);
        var scheduleIds = scheduleRows.stream().map(OfferingScheduleRow::scheduleId).toList();
        var weekRows = scheduleIds.isEmpty() ? List.<ScheduleWeekRow>of() : mapper.selectDetailWeeks(scheduleIds);
        var teacherRows = scheduleIds.isEmpty() ? List.<ScheduleTeacherRow>of() : mapper.selectDetailTeachers(scheduleIds);

        var weeksBySchedule = weeksBySchedule(weekRows);
        var teachersBySchedule = teachersBySchedule(teacherRows);
        var schedules = scheduleRows.stream()
                .map(schedule -> toScheduleDetail(schedule, weeksBySchedule, teachersBySchedule)).toList();

        var teacherSummary = teacherRows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ScheduleTeacherRow::teacherId,
                        row -> new TeacherDetail(row.teacherId(), row.displayName()),
                        (first, ignored) -> first))
                .values().stream()
                .sorted(Comparator.comparing(TeacherDetail::displayName).thenComparingLong(TeacherDetail::teacherId))
                .toList();

        CollegeDetail ownerCollege = base.ownerCollegeId() == null ? null
                : new CollegeDetail(base.ownerCollegeId(), base.ownerCollegeCode(), base.ownerCollegeName());
        return new CourseOfferingDetail(
                base.offeringId(), base.displayName(), base.description(), base.courseId(), base.courseName(),
                base.courseCode(), base.activityType(), base.classGroupText(), base.studentCount(),
                new SemesterDetail(base.semesterId(), base.semesterCode(), base.semesterName(),
                        base.firstWeekDate()),
                new CollegeDetail(base.sourceScopeCollegeId(), base.sourceScopeCollegeCode(),
                        base.sourceScopeCollegeName()),
                ownerCollege,
                syllabusStatus(base.syllabusRef()),
                safeSyllabusLocator(base.syllabusRef()),
                teacherSummary,
                schedules);
    }

    /**
     * B-02: only an HTTPS locator or an explicitly approved logical locator (demo/fixture
     * prefixes) is ever exposed; a raw ref of any other shape stays internal (status only).
     */
    static String safeSyllabusLocator(String syllabusRef) {
        if (syllabusRef == null) return null;
        String lower = syllabusRef.toLowerCase(java.util.Locale.ROOT);
        if (SafeLocator.isSafeHttps(syllabusRef) || lower.startsWith("demo:") || lower.startsWith("fixture:"))
            return syllabusRef;
        return null;
    }

    @Transactional(readOnly = true)
    public TeacherComparisonResult compareTeachers(CurrentUser currentUser, List<Long> selectedTeacherIds) {
        var scope = scopes.resolve(currentUser, AuthorizedScopeProvider.Purpose.READ);
        var requestedTeachers = mapper.selectComparisonTeachers(scope, selectedTeacherIds);
        var requestedById = new LinkedHashMap<Long, ComparisonTeacherRow>();
        for (var teacher : requestedTeachers) requestedById.putIfAbsent(teacher.teacherId(), teacher);
        for (long teacherId : selectedTeacherIds)
            if (!requestedById.containsKey(teacherId))
                throw TeacherComparisonRejectedException.unknownTeacher(teacherId);

        var offeringRows = mapper.selectComparisonOfferings(scope, selectedTeacherIds);
        var scheduleRows = mapper.selectComparisonSchedules(scope, selectedTeacherIds);
        var scheduleIds = scheduleRows.stream().map(ComparisonScheduleRow::scheduleId).distinct().toList();
        var weekRows = scheduleIds.isEmpty() ? List.<ScheduleWeekRow>of() : mapper.selectDetailWeeks(scheduleIds);
        var coTeacherRows = scheduleIds.isEmpty() ? List.<ScheduleTeacherRow>of()
                : mapper.selectDetailTeachers(scheduleIds);

        var weeksBySchedule = weeksBySchedule(weekRows);
        var teachersBySchedule = teachersBySchedule(coTeacherRows);
        Map<TeacherOfferingKey, LinkedHashMap<Long, ScheduleDetail>> schedulesByTeacherOffering = new LinkedHashMap<>();
        for (var row : scheduleRows) {
            var key = new TeacherOfferingKey(row.selectedTeacherId(), row.offeringId());
            schedulesByTeacherOffering.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(row.scheduleId(), toScheduleDetail(row.schedule(), weeksBySchedule, teachersBySchedule));
        }

        Map<Long, LinkedHashMap<Long, TeacherComparisonOffering>> offeringsByTeacher = new LinkedHashMap<>();
        for (var row : offeringRows) {
            var key = new TeacherOfferingKey(row.selectedTeacherId(), row.offeringId());
            var teachingSchedules = List.copyOf(schedulesByTeacherOffering
                    .getOrDefault(key, new LinkedHashMap<>()).values());
            var offering = new TeacherComparisonOffering(
                    row.offeringId(), row.displayName(), row.activityType(), row.classGroupText(),
                    row.description(),
                    new SemesterDetail(row.semesterId(), row.semesterCode(), row.semesterName()),
                    row.studentCount(), syllabusStatus(row.syllabusRef()), teachingSchedules);
            offeringsByTeacher.computeIfAbsent(row.selectedTeacherId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(row.offeringId(), offering);
        }

        var groups = new ArrayList<TeacherComparison>();
        for (long teacherId : selectedTeacherIds) {
            var teacher = requestedById.get(teacherId);
            groups.add(new TeacherComparison(teacher.teacherId(), teacher.displayName(),
                    List.copyOf(offeringsByTeacher.getOrDefault(teacherId, new LinkedHashMap<>()).values())));
        }
        return new TeacherComparisonResult(groups);
    }

    private static Map<Long, LinkedHashMap<Integer, Integer>> weeksBySchedule(List<ScheduleWeekRow> rows) {
        Map<Long, LinkedHashMap<Integer, Integer>> result = new LinkedHashMap<>();
        for (var week : rows)
            result.computeIfAbsent(week.scheduleId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(week.weekNo(), week.weekNo());
        return result;
    }

    private static Map<Long, LinkedHashMap<Long, TeacherDetail>> teachersBySchedule(List<ScheduleTeacherRow> rows) {
        Map<Long, LinkedHashMap<Long, TeacherDetail>> result = new LinkedHashMap<>();
        for (var teacher : rows)
            result.computeIfAbsent(teacher.scheduleId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(teacher.teacherId(), new TeacherDetail(teacher.teacherId(), teacher.displayName()));
        return result;
    }

    private static ScheduleDetail toScheduleDetail(
            OfferingScheduleRow schedule,
            Map<Long, LinkedHashMap<Integer, Integer>> weeksBySchedule,
            Map<Long, LinkedHashMap<Long, TeacherDetail>> teachersBySchedule) {
        return new ScheduleDetail(
                schedule.scheduleId(),
                List.copyOf(weeksBySchedule.getOrDefault(schedule.scheduleId(), new LinkedHashMap<>()).values()),
                schedule.rawWeekText(), schedule.weekday(), schedule.startSection(), schedule.endSection(),
                new LocationDetail(schedule.locationId(), schedule.locationDisplayName(),
                        schedule.locationGroup(), schedule.locationType()),
                List.copyOf(teachersBySchedule.getOrDefault(schedule.scheduleId(), new LinkedHashMap<>()).values()));
    }

    private static SyllabusStatus syllabusStatus(String syllabusRef) {
        return syllabusRef == null || syllabusRef.isBlank() ? SyllabusStatus.MISSING : SyllabusStatus.AVAILABLE;
    }

    private record TeacherOfferingKey(long teacherId, long offeringId) {}
}
