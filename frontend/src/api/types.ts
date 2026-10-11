/**
 * TypeScript mirrors of the real backend DTOs (Round 2.3—3.6 contracts).
 * Null, [] and 0 are meaningful: null means "not stored", 0 means the stored value 0.
 */

export interface AuthorizationScope {
  roleCode: string
  scopeType: string
  collegeId: number | null
  teacherId: number | null
  offeringId: number | null
}

export interface CurrentUser {
  userId: number
  username: string
  teacherId: number | null
  roles: string[]
  authorizationScopes: AuthorizationScope[]
}

export interface CsrfToken {
  token: string
  headerName: string
  parameterName: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

// ---- CourseOffering list (Round 3.3) ------------------------------------

export interface CourseOfferingListItem {
  offeringId: number
  displayName: string
  courseCode: string | null
  activityType: string
  classGroupText: string
  studentCount: number | null
  semesterId: number
  semesterCode: string
  semesterName: string
  sourceScopeCollegeId: number
  ownerCollege: CollegeDetail | null
  syllabusStatus: SyllabusStatus
  syllabusUrl: string | null
  teacherNames: string[]
  schedules: ScheduleDetail[]
}

// ---- CourseOffering detail (Round 3.5) -----------------------------------

export type SyllabusStatus = 'AVAILABLE' | 'MISSING'

export interface SemesterDetail {
  semesterId: number
  semesterCode: string
  semesterName: string
  firstWeekDate: string | null
}

export interface CollegeDetail {
  collegeId: number
  collegeCode: string
  collegeName: string
}

export interface TeacherDetail {
  teacherId: number
  displayName: string
}

export interface LocationDetail {
  locationId: number
  displayName: string
  locationGroup: string
  locationType: 'PHYSICAL' | 'ONLINE' | 'TBD'
}

export interface ScheduleDetail {
  scheduleId: number
  weeks: number[]
  rawWeekText: string
  weekday: number
  startSection: number
  endSection: number
  location: LocationDetail
  teachers: TeacherDetail[]
}

export interface CourseOfferingDetail {
  offeringId: number
  displayName: string
  description: string | null
  syllabusUrl: string | null
  courseId: number | null
  courseName: string | null
  courseCode: string | null
  activityType: string
  classGroupText: string
  studentCount: number | null
  semester: SemesterDetail
  sourceScopeCollege: CollegeDetail
  ownerCollege: CollegeDetail | null
  syllabusStatus: SyllabusStatus
  teachers: TeacherDetail[]
  schedules: ScheduleDetail[]
}

export interface MyScheduleView {
  teaching: CourseOfferingListItem[]
  supervision: CourseOfferingListItem[]
}

// ---- Teacher comparison (Round 3.6) ---------------------------------------

export interface TeacherLookup {
  teacherId: number
  displayName: string
}

export interface TeacherComparisonOffering {
  offeringId: number
  displayName: string
  activityType: string
  classGroupText: string
  description: string | null
  semester: SemesterDetail
  studentCount: number | null
  syllabusStatus: SyllabusStatus
  teachingSchedules: ScheduleDetail[]
}

export interface TeacherComparison {
  teacherId: number
  displayName: string
  offerings: TeacherComparisonOffering[]
}

export interface TeacherComparisonResult {
  teachers: TeacherComparison[]
}

// ---- Timetable import (Round 3.2) ------------------------------------------

export type TimetableImportStatus = 'SUCCESS' | 'ALREADY_IMPORTED' | 'FAILED' | 'CONFLICT'

export interface TimetableImportError {
  sourceIndex: number | null
  field: string | null
  domain: string
  errorCode: string
  reason: string
}

export interface TimetableImportResult {
  batchId: number | null
  status: TimetableImportStatus
  totalRows: number
  successRows: number
  failureRows: number
  createdOfferings: number
  reusedOfferings: number
  createdSchedules: number
  reusedSchedules: number
  createdTeachers: number
  reusedTeachers: number
  createdLocations: number
  reusedLocations: number
  errors: TimetableImportError[]
}

// ---- Sprint 3 supervision coverage ------------------------------------------

export interface SupervisionCoverageSummary {
  semesterId: number | null
  ownerCollegeId: number | null
  eligible: number
  covered: number
  uncovered: number
  /** null exactly when eligible is 0 (zero denominator, OQ-13). */
  coverageRate: number | null
}

export interface SupervisionCoverageView {
  summary: SupervisionCoverageSummary
  covered: CoverageOfferingRow[]
  uncovered: CoverageOfferingRow[]
}

export interface CoverageOfferingRow {
  offeringId: number
  displayName: string
  semesterCode: string
  semesterName: string
  studentCount: number | null
  ownerCollegeId: number | null
}

export type SupervisionImportStatus = 'SUCCESS' | 'ALREADY_IMPORTED' | 'REJECTED'

export interface SupervisionImportError {
  sourceIndex: number | null
  field: string | null
  domain: string
  errorCode: string
  reason: string
}

export interface SupervisionImportResult {
  batchId: number | null
  status: SupervisionImportStatus
  totalRows: number
  successRows: number
  failureRows: number
  importedEvaluations: number
  errors: SupervisionImportError[]
}

// ---- Integration-support lookups (Round 3.7) --------------------------------

export interface SemesterLookup {
  semesterId: number
  semesterCode: string
  semesterName: string
}

export interface CollegeLookup {
  collegeId: number
  collegeCode: string
  collegeName: string
}

// ---- Single-classroom video and automatic analysis foundation -------------

export type ClassroomJobStatus = 'QUEUED' | 'RUNNING' | 'WAITING_EXTERNAL' | 'SUCCEEDED' | 'FAILED'

export interface ClassroomModuleJob {
  jobId: number
  moduleType: 'TRANSCRIPTION' | 'HEAD_UP'
  status: ClassroomJobStatus
  providerId: string
  moduleVersion: string | null
  resultRevision: number | null
  errorCode: string | null
  errorMessage: string | null
}

export interface TranscriptSegment {
  id: string
  startMs: number
  endMs: number
  text: string
}

export interface HeadUpWindow {
  id: string
  startMs: number
  endMs: number
  quality: 'VALID' | 'NO_SUBJECTS' | 'LOW_QUALITY'
  qualityReason: string | null
  headUpCount: number | null
  validObservedCount: number | null
  headUpRate: number | null
}

export interface ClassroomReport {
  reportId: number | null
  status: 'WAITING_INPUT' | 'READY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  dataMode: 'EXTERNAL' | 'SIMULATED'
  meanHeadUpRate: number | null
  validCoverageRate: number | null
  estimatedAttendanceCount: number | null
  attendanceObservedWindows: number
  attendanceConfidence: 'HIGH' | 'MEDIUM' | 'LOW' | null
  summary: string | null
  analysis: string | null
  evidenceStartMs: number | null
  evidenceEndMs: number | null
  errorMessage: string | null
  transcript: TranscriptSegment[]
  headUpWindows: HeadUpWindow[]
}

export interface GeneratedReportEvidenceRef {
  resultId: number
  itemType: 'SEGMENT' | 'WINDOW'
  itemId: string
}

export interface GeneratedClassroomReport {
  topics: { startMs: number; endMs: number; summary: string; evidenceRefs: GeneratedReportEvidenceRef[] }[]
  changes: { fromWindowId: string; toWindowId: string; startMs: number; endMs: number;
    deltaPercentagePoints: number; evidenceRefs: GeneratedReportEvidenceRef[] }[]
  interpretations: { hypothesis: string; alternatives: string[]; suggestion: string;
    evidenceRefs: GeneratedReportEvidenceRef[] }[]
  limitations: string[]
}

/** Metadata only; historical report bodies and version-bound evidence are not exposed. */
export interface GeneratedReportVersion {
  reportId: number
  generatedAt: string
  current: boolean
}

export interface GeneratedReportVersions {
  items: GeneratedReportVersion[]
  nextPage: number | null
}

export type GeneratedReportProgressState = 'NOT_APPLICABLE' | 'WAITING_INPUT' | 'FAILED_INPUT'
  | 'NOT_QUEUED' | 'PAUSED' | 'QUEUED' | 'PROCESSING' | 'NEEDS_REVIEW' | 'COMPLETED'

export interface GeneratedReportProgress {
  state: GeneratedReportProgressState
}

export interface ClassroomMedia {
  mediaId: number
  originalFilename: string
  contentType: string
  byteSize: number
  sha256: string
  durationMs: number
  status: string
  playbackUrl: string
  moduleJobs: ClassroomModuleJob[]
  report: ClassroomReport | null
}

export interface ClassroomAnalysis {
  classSessionId: number
  offeringId: number
  scheduleId: number
  sessionDate: string
  title: string
  createdAt: string
  media: ClassroomMedia[]
}

// ---- Sprint 2 teacher action chain (S2-06/07/08/09/14) ----------------------

export interface ActionSuggestionEvidence {
  itemType: 'SEGMENT' | 'WINDOW'
  itemId: string
}

export interface ActionSuggestion {
  index: number
  hypothesis: string | null
  alternatives: string[]
  suggestion: string
  evidence: ActionSuggestionEvidence[]
}

/** Present exactly when suggestions cannot be offered, so the teacher sees why (S2-13). */
export type ActionSuggestionReason = 'REPORT_NOT_READY' | 'NO_INTERPRETATIONS'

export interface ClassroomActionSuggestionResult {
  available: boolean
  reasonCode: ActionSuggestionReason | null
  suggestions: ActionSuggestion[]
  /** S2-11：教师端据此定位证据并播放；无媒体上下文时为 null。 */
  classSessionId: number | null
}

export type TeacherActionStatus = 'PLANNED' | 'ACTIVE' | 'DONE' | 'ABANDONED'

export interface TeacherAction {
  actionId: number
  mediaId: number | null
  sourceKind: 'SUGGESTION' | 'MANUAL'
  sourceIndex: number | null
  title: string
  content: string
  status: TeacherActionStatus
  revision: number
  createdAt: string
  updatedAt: string
}

// ---- Sprint 2 supervisor report review (S2-03/S2-04) ------------------------

export type AnnotationTargetKind = 'TOPIC' | 'CHANGE' | 'INTERPRETATION'

/** ISSUE = 认定 AI 结论有问题；ACKNOWLEDGED = 认可该结论。 */
export type AnnotationVerdict = 'ISSUE' | 'ACKNOWLEDGED'

export interface ReviewAnnotation {
  targetKind: AnnotationTargetKind
  targetIndex: number
  verdict: AnnotationVerdict
  /** 必填的复核说明；空白说明不允许提交（服务端同样校验）。 */
  note: string
}

export interface ClassroomReportReview {
  reviewId: number
  reportId: number
  mediaId: number
  confirmed: boolean
  confirmedAt: string | null
  revision: number
  annotations: ReviewAnnotation[]
}

/** 保存草稿的请求体。 */
export interface SupervisorReviewDraft {
  annotations: ReviewAnnotation[]
}

/** 已确认的冻结快照：教师读到的就是这份内容，后续 AI 重算不会改变它。 */
export interface ConfirmedReportSnapshot {
  reportId: number
  report: GeneratedClassroomReport
  annotations: ReviewAnnotation[]
}

// ---- Sprint 2 teacher notification (S2-05) ----------------------------------
export type TeacherNotificationKind = 'REPORT_PUBLISHED'
export type TeacherNotificationState = 'UNREAD' | 'READ'

export interface TeacherNotification {
  notificationId: number
  mediaId: number
  kind: TeacherNotificationKind
  state: TeacherNotificationState
  createdAt: string
  readAt: string | null
}

// ---- Sprint 2 segment directory and override (S2-01/S2-10) -------------------

/**
 * 目录里的一条片段。片段不是独立实体：它就是报告 topics[] 的三元组，
 * 存在覆盖（override）时这里给出修正后的值，同时用 original* 保留报告原值以便追溯。
 */
export interface SegmentDirectoryEntry {
  key: string
  index: number
  title: string
  startMs: number
  endMs: number
  durationMs: number
  evidenceCount: number
  overridden: boolean
  originalTitle: string
  originalStartMs: number
  originalEndMs: number
  editedByUserId: number | null
  revision: number | null
  updatedAt: string | null
}

/** GET /class-sessions/media/{id}/segments 的响应；没有报告版本时服务端返回 204。 */
export interface SegmentDirectoryResponse {
  reportId: number
  mediaId: number
  mediaDurationMs: number
  segments: SegmentDirectoryEntry[]
}

/** PUT /class-sessions/media/{id}/segments/{segmentKey} 的请求体。 */
export interface SegmentOverrideDraft {
  title: string
  startMs: number
  endMs: number
}
