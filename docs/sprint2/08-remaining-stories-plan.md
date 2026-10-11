# 第14组 Sprint 2 剩余故事落地说明（S2-01 / S2-10）

状态：Sprint 2 的 14 条故事中已完成 11 条（S2-02/03/04/05/06/07/08/09/11/12/14），
S2-13 部分完成（不可用原因已实现，受控重试待与吕厚含对齐），**S2-01 与 S2-10 尚未实现**。
本文给出这两条的现状判断、设计与落地步骤，供接手者直接开工。

## 一、现状判断（为什么这两条和前 11 条性质不同）

前 11 条都建立在「报告一旦确认即冻结」这个前提下：报告 JSON 是不可变事实，我们只在其上叠加
派生数据（行动、复核、通知）。S2-10 要求**修改片段划分**，这与"报告不可变"直接冲突，
因此必须新增一层**覆盖（override）**，而不是改报告内容——这也是这两条比看起来更重的原因。

另一点：本项目里「主题片段」目前**没有独立实体**，它就是生成报告 `topics[]` 的
`{startMs, endMs, summary}` 三元组（客观报告另有 `transcript[]` 与 `headUpWindows[]` 提供证据时间）。
因此 S2-01 是「把已有报告片段组织成目录并支持选取」，S2-10 是「在不可变报告之上存一份可审计的覆盖」。

## 二、S2-01 浏览主题片段目录

验收：督导可在课堂详情看到按主题/环节组织的片段目录；无片段时显示明确空状态；仅可见授权范围内课堂。

**建议实现（无需新表）**
1. 后端已有 `GET /class-sessions/media/{mediaId}/generated-report`（含 `topics[]`）与
   `/class-sessions/media/{mediaId}/action-suggestions`（返回 `classSessionId`），
   目录数据可直接复用，**不必新增查询接口**；若希望一次拿全（片段 + 时长 + 是否被覆盖），
   可在 S2-10 的接口里一并返回。
2. 前端：在**督导复核页**（`SupervisorReportReviewView.vue`，已加载报告与 mediaId）内加一个
   「片段目录」分区：按 `startMs` 升序、显示 `序号 / 时间区间 / 时长 / 摘要`，点击即把该片段
   设为当前复核对象，并复用已完成的证据播放能力（`lib/evidencePlayback.ts`）播放该区间。
3. 空状态：`topics` 为空时显示"这份报告没有可用片段"，而不是渲染空列表。

**要点**：不要新建独立片段表来"表示"目录；那会立刻产生与报告 `topics` 的双份真相。

## 三、S2-10 调整片段标题与边界

验收：督导可修改片段标题与起止边界；修改留痕；越界或重叠有校验提示。

**表结构（V24 建议）**

```sql
CREATE TABLE classroom_segment_override (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,          -- 绑定具体报告版本；报告重算不继承覆盖
    media_id BIGINT NOT NULL,
    segment_key VARCHAR(64) NOT NULL,   -- 报告内片段标识（建议 "topic-<index>"）
    title VARCHAR(200) NOT NULL,
    start_ms INT NOT NULL,
    end_ms INT NOT NULL,
    edited_by_user_id BIGINT NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_segment_override_report FOREIGN KEY (report_id) REFERENCES classroom_generated_report(id),
    CONSTRAINT fk_segment_override_media FOREIGN KEY (media_id) REFERENCES classroom_media(id),
    CONSTRAINT fk_segment_override_user FOREIGN KEY (edited_by_user_id) REFERENCES user_account(id)
);
CREATE UNIQUE INDEX uq_segment_override_target ON classroom_segment_override (report_id,segment_key);
```

**校验规则（必须服务端强制，前端只是提示）**

| 规则 | 说明 |
| --- | --- |
| `end_ms > start_ms` | 空区间或倒置区间一律拒绝 |
| `start_ms >= 0` 且 `end_ms <= 媒体时长` | 越界拒绝；媒体时长取 `classroom_media.duration_ms` |
| 不与其他片段重叠 | 同一报告内除自身外不得与其他片段的 `[start,end)` 相交；重叠返回明确错误码与冲突片段序号 |
| 标题非空且 ≤200 字 | 空白标题不允许 |
| 已确认报告仍可覆盖？ | **建议允许**：覆盖是"督导对划分的修正"，不改报告本体；但需在自评/决策里写明这一取舍 |

**接口建议**
- `GET  /class-sessions/media/{mediaId}/segments` —— 目录（报告片段 + 覆盖后的呈现 + 时长 + 是否已覆盖）
- `PUT  /class-sessions/media/{mediaId}/segments/{segmentKey}` —— 保存覆盖（督导 + CLASSROOM_WRITE）
- 错误码建议：`SEGMENT_RANGE_INVALID`(400)、`SEGMENT_OUT_OF_MEDIA`(400)、`SEGMENT_OVERLAP`(409)、
  `SEGMENT_TITLE_REQUIRED`(400)、`SEGMENT_UNKNOWN`(404)

**注意（本项目已踩过的坑）**
1. `SecurityConfiguration` 是 `anyRequest().denyAll()`，**新路径必须显式放行**，否则 403；
2. 若新增控制器，需把它的类加入 `ClassroomAnalysisExceptionHandler` 的 `assignableTypes`，
   否则自定义 4xx 会被兜成 500（S2-05 已踩过一次）；
3. 新增迁移后必须同步 4 处版本断言：`ApplicationTest`、`DataModelTest`（含期望表集合）、
   `MigrationModeTest`、`FixtureIntegrationTest`；
4. 测试夹具中 `teacher-a-test` **同时持有督导范围**（scope id 6, offering 104）——
   断言"非督导被拒"请用 `director-a-test`，否则会得到 200 而不是 403。

## 四、剩余收尾事项（与上面两条无关，但影响提交质量）

1. **吕厚含的 Sprint 1 速率数据与回顾改进措施原文**仍未提供；实验三把"上轮改进项落实情况"
   作为硬性检查项并计入 15%，`03-retro-meeting.md` 与 `05-self-assessment.md` 中仍是占位。
2. **督导角色判断是全局的**（`user.roles().contains("SUPERVISOR")`，与既有
   `requestGeneratedReportRetry` 一致），未绑定到具体开课的督导范围；建议记一条决策。
3. **教师端通知与证据回看页的入口**：`/my-feedback`、`/reports/review` 已有导航入口，
   独立的证据回看页 `/classrooms/:classSessionId/evidence` 仍无入口（需上下文参数），入口位置待定。
4. 提交前跑一次完整 `scripts/Verify.ps1`（本轮开发过程中跑的是针对性子集）。
5. 交付文档的实际完成状态：`01-plan-meeting.md` 的表4-A「完成状态」列与
   `05-self-assessment.md` 的速率/燃尽/技术债附表需要回填真实数据。
