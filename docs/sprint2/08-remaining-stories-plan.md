# 第14组 Sprint 2 剩余故事落地说明（S2-01 / S2-10）

状态：Sprint 2 的 14 条故事中 **13 条已完成**（S2-01 ~ S2-12、S2-14），
`S2-13` 部分完成（"AI 建议不可用原因"已实现，"受控重试"待与吕厚含对齐）。
本文记录 S2-01 与 S2-10 这两条与"报告不可变"前提冲突的故事的设计与**落地结果**。

## 一、现状判断（为什么这两条和前 11 条性质不同）

前 11 条都建立在「报告一旦确认即冻结」这个前提下：报告 JSON 是不可变事实，我们只在其上叠加
派生数据（行动、复核、通知）。S2-10 要求**修改片段划分**，这与"报告不可变"直接冲突，
因此必须新增一层**覆盖（override）**，而不是改报告内容——这也是这两条比看起来更重的原因。

另一点：本项目里「主题片段」目前**没有独立实体**，它就是生成报告 `topics[]` 的
`{startMs, endMs, summary}` 三元组（客观报告另有 `transcript[]` 与 `headUpWindows[]` 提供证据时间）。
因此 S2-01 是「把已有报告片段组织成目录并支持选取」，S2-10 是「在不可变报告之上存一份可审计的覆盖」。

## 二、S2-01 浏览主题片段目录（已完成）

验收：督导可在课堂详情看到按主题/环节组织的片段目录；无片段时显示明确空状态；仅可见授权范围内课堂。

**落地结果**
1. 后端**未新增查询接口**：目录数据直接来自既有 `GET /class-sessions/media/{mediaId}/generated-report`
   的 `topics[]`（S2-10 的目录接口在其上叠加覆盖，见下节）。
2. 前端 `lib/segmentDirectory.ts` 提供纯逻辑：`segmentDirectory()`（按 `startMs` 升序，保留报告原始序号）、
   `segmentLabel()`（`02｜00:00 – 00:20（20 秒，1 条证据）`）、`findOverlaps()`（半开区间）、`segmentEvidence()`。
3. 督导复核页 `SupervisorReportReviewView.vue` 内的「片段目录」分区渲染上述结果，点击目录项即把该片段
   设为当前复核对象，并复用 `lib/evidencePlayback.ts` 播放该区间。
4. 空状态：`topics` 为空时显示"这份报告没有可用的主题片段"，而不是渲染空列表。

**要点**：不要新建独立片段表来"表示"目录；那会立刻产生与报告 `topics` 的双份真相（DEC-S2-01-01）。

## 三、S2-10 调整片段标题与边界（已完成）

验收：督导可修改片段标题与起止边界；修改留痕；越界或重叠有校验提示。

**表结构（V24，已落地）**

```sql
CREATE TABLE classroom_segment_override (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,          -- 绑定具体报告版本；报告重算不继承覆盖
    media_id BIGINT NOT NULL,
    segment_key VARCHAR(64) NOT NULL,   -- 报告内片段标识："topic-<index>"
    title VARCHAR(200) NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    edited_by_user_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_segment_override_report FOREIGN KEY (report_id) REFERENCES classroom_generated_report(id),
    CONSTRAINT fk_segment_override_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_segment_override_user FOREIGN KEY (edited_by_user_id) REFERENCES user_account(id),
    CONSTRAINT ck_segment_override_start CHECK (start_ms >= 0),
    CONSTRAINT ck_segment_override_range CHECK (end_ms > start_ms)
);
CREATE UNIQUE INDEX uq_segment_override_target ON classroom_segment_override (report_id,segment_key);
CREATE INDEX idx_segment_override_media ON classroom_segment_override (media_id,report_id);
```

> 与最初草案的差异：`start_ms/end_ms` 用 `BIGINT`（与 `classroom_media.duration_ms` 类型一致，
> 避免大时长课堂被截断）；补了两条 CHECK 把"区间合法"下沉到数据库。

**校验规则（服务端强制，前端只做提示；校验顺序固定为 未知 → 标题 → 区间 → 越界 → 重叠）**

| 规则 | 说明 | 错误码 / HTTP |
| --- | --- | --- |
| 未知片段 | `segmentKey` 不在当前报告版本的 `topics` 中 | `SEGMENT_UNKNOWN` / 404 |
| 标题 | 非空、去空白后 ≤ 200 字（空白与超长同一个码） | `SEGMENT_TITLE_REQUIRED` / 400 |
| 区间合法 | `end_ms > start_ms` | `SEGMENT_RANGE_INVALID` / 400 |
| 不超媒体时长 | `0 ≤ start_ms` 且 `end_ms ≤ classroom_media.duration_ms` | `SEGMENT_OUT_OF_MEDIA` / 400 |
| 同报告内不重叠 | 与**生效区间**（已叠加覆盖）比较，半开区间首尾相接不算重叠 | `SEGMENT_OVERLAP` / 409，消息点名冲突片段序号 |
| 只改标题 | 边界与当前生效边界相同时不查重叠 | 见 DEC-S2-10-02 |
| 已确认报告 | **仍可覆盖**，不改报告本体与冻结快照 | 见 DEC-S2-10-01 |
| 无报告版本 | 目录返回 204；写入被拒 | `SEGMENT_REPORT_NOT_READY` / 409 |

**接口（已落地）**
- `GET  /class-sessions/media/{mediaId}/segments` —— 目录：报告片段 + 覆盖后呈现 + `mediaDurationMs` +
  原始值/修订号/修改人（留痕）；按生效区间升序，与前端 `segmentDirectory()` 同序；无报告版本返回 204。
- `PUT  /class-sessions/media/{mediaId}/segments/{segmentKey}` —— 保存覆盖（督导 + `CLASSROOM_WRITE`），
  返回叠加覆盖后的完整目录；同一片段重复保存只递增 `revision`，不追加历史行。

**实现位置**

| 关注点 | 文件 |
| --- | --- |
| 迁移 | `backend/src/main/resources/db/migration/V24__create_classroom_segment_override.sql` |
| 领域与错误码 | `backend/src/main/java/com/aijiaoxue/classroom/ClassroomSegmentOverride.java` |
| 持久化（UPSERT + revision） | `.../ClassroomSegmentOverrideStore.java` |
| 规则与权限 | `.../ClassroomSegmentService.java` |
| 路由 | `.../ClassroomAnalysisController.java`（`GET/PUT /segments`） |
| 前端 API | `frontend/src/api/segments.ts` |
| 前端目录/校验 | `frontend/src/lib/segmentDirectory.ts`（`directorySegments`、`validateSegmentEdit`） |
| 前端界面 | `frontend/src/views/SupervisorReportReviewView.vue`（片段目录修正态） |

**验证**：后端 `ClassroomAnalysisIntegrationTest` 新增 2 条（目录与五条规则、只改标题/已确认报告两条取舍），
连同 `DataModelTest`（新增表 + 唯一索引断言）、`ApplicationTest`、`MigrationModeTest`、`FixtureIntegrationTest`
迁移版本断言共 88 项 0 失败；**全量后端套件 335 项 0 失败**（10 项需外部环境者 skip）；
前端 `vue-tsc` 干净，`vitest` 24 文件 / 150 项全绿。

**注意（本项目已踩过的坑，后续改这里必看）**
1. 新路径落在 `/api/v1/class-sessions/**` 下，`SecurityConfiguration` 已放行给 authenticated；
   若把接口挪到别的前缀，必须显式加白名单，否则 `anyRequest().denyAll()` 会直接 403；
2. 控制器沿用 `ClassroomAnalysisController`，因此已在 `ClassroomAnalysisExceptionHandler` 的
   `assignableTypes` 内，自定义 4xx 不会被兜成 500；**新增控制器**则必须加进去；
3. 新增迁移必须同步 4 处版本断言：`ApplicationTest`、`DataModelTest`（含期望表集合）、
   `MigrationModeTest`、`FixtureIntegrationTest`（该处计数含注册的 repeatable 种子，为 N+1）；
4. 测试夹具中 `teacher-a-test` **同时持有督导范围**（scope id 6, offering 104）——
   断言"非督导被拒"请用 `director-a-test`，否则会得到 200 而不是 403；
5. 构造"已生成报告"用 `ClassroomAnalysisIntegrationTest` 里的
   `preparedExternalRun(...)` / `mediaWithGeneratedReport(..., topics)` + `paidBudget` + `generatedReports.persist(...)`。

## 四、剩余收尾事项

1. **吕厚含的 Sprint 1 速率数据与回顾改进措施原文**仍未提供；实验三把"上轮改进项落实情况"
   作为硬性检查项并计入 15%，`03-retro-meeting.md` 与 `05-self-assessment.md` 中仍是占位。
   **不得代为编造**（指导书明确"原始记录不得事后补造"）。
2. **AI 互动原始记录（表5）** 仍是空模板；本轮 S2-10 的实现过程需要由记录人（秦嘉琪）
   从实际会话中当场转录，不得事后补造。
3. 提交前跑一次完整 `scripts/Verify.ps1`（本轮开发过程中跑的是针对性子集）。
4. `01-plan-meeting.md` 表4-A「完成状态」列与 `05-self-assessment.md` 的速率/燃尽/技术债附表需回填真实数据。
5. 决策日志见 `docs/sprint2/10-decisions.md`（含 DEC-S2-10-01 ~ 04 与两条既有技术债）。
