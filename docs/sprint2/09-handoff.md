# 交接文档：第14组 实验三（Sprint 2）收尾

面向对象：下一个接手本工作区的 AI 会话 / 团队成员
最后更新：2026-10-11（S2-10 落地后）
当前状态：Sprint 2 的 14 条故事中 **13 条已完成**（S2-01 ~ S2-12、S2-14），
`S2-13` 部分完成（缺"受控重试"）。**代码层面没有必须补的故事**，剩下的是数据回填与提交前收尾。

---

## 一、项目与课程背景

- 课程：软件项目管理，实验三 = **Sprint 2 执行与交付**（4 学时教学模拟 Sprint）
- 团队：第 14 组，项目「爱教学」（教学质量全链路数字化管理平台）
- 本轮 DRI：**秦嘉琪**（第 3 位轮值，兼任 PO 与 Scrum Master）
- 本轮需交 7 项：计划会纪要(表3)、源代码、评审纪要(表6)、回顾纪要(表7)、AI互动原始记录(表5)、
  自评报告(表8)+燃尽/速率/技术债、DRI 期望寄语
- 指导书在 `1789256922634-软件项目管理_实验指导书_实验2_Sprint1_4学时_代号蜂鸟.docx`（实验二）
  与 `1791677253964-实验指导书_实验3_Sprint2_4学时.docx`（实验三）
- 本轮故事取自 `爱教学_用户故事地图.xlsx`（2026-10-11 最新版）的 **Sprint 2 泳道，共 14 条**

---

## 二、环境与工具链（最容易浪费时间的地方，先读这节）

### 2.1 仓库与远程

- 工作区：`D:\第14组`；代码仓库：`D:\第14组\sport`
- 远程：https://github.com/cccp-anq/ai-teaching （**私有**，gh 已登录 `cccp-anq`）
- 分支：`main`（Sprint 1 基线，标签 `sprint1-baseline`）、`sprint2`（集成）、
  `feature/S2-01…`、`feature/S2-10-segment-override` 等功能分支
- 分支命名规则：**功能分支统一用 `feature/` 前缀**。已存在 `sprint2` 分支时
  **不能**创建 `sprint2/xxx`（git ref 是层级结构，会直接报 `cannot lock ref`）

### 2.2 沙箱：三类会反复踩的坑

1. **工作区外写入被拒**：`~/.gitconfig`、`~/.m2`、`~/.dsh`、npm 缓存都在工作区外。
   - 受限会话跑 git：设 `$env:GIT_CONFIG_GLOBAL='D:\第14组\.gitconfig-local'` + `$env:GIT_CONFIG_NOSYSTEM='1'`
     （该文件已存在，含 user.name/email）即可让 git 读到身份，但**无法推拉**（需要凭据）。
   - 凡是要联网、要凭据、要 Maven/npm 的操作，**一律用 `sandbox_permissions: danger-full-access` + 中文 justification**，
     这是预期内的，直接做即可。
2. **PowerShell 的管道/重定向对原生程序会失败**：`git status | Select-Object`、`netstat | Select-String`、
   `git ... > file` 会报 `Program 'git.exe' failed to run: Access is denied` 或
   `cannot create standard input pipe`。绕法：**裸跑**（不接管道），或用
   `Start-Process -FilePath git -ArgumentList ... -RedirectStandardOutput <工作区内文件> -Wait`（句柄重定向可用）。
3. **命令体里的中文常量会被破坏**：不要把中文路径/中文常量写进 `python -c "...脚本..."` 或 here-string 里，
   会被传输层破坏。做法：中文**作为参数**传递是安全的；或把脚本/内容写进文件（`write` 工具写文件没问题）。
   另外：PowerShell 5.1 会把**无 BOM 的 UTF-8 脚本文件**当 ANSI 读，脚本内的中文常量会变成乱码——
   传参给脚本或把路径写死在命令体里都可以，但别在 `.ps1` 里写中文常量。
4. **换行风格（必读，踩过）**：仓库里的既有文件在 git 里是 **LF**，但本机编辑工具会把整份文件重写成
   **CRLF**；提交后 diff 会显示成"整文件替换"（曾出现 3743 行删除的假 diff），无法人工审查。
   提交前务必把本次改动过的文件统一成 LF 再 `git add`：
   ```powershell
   $t=[System.IO.File]::ReadAllText($f); [System.IO.File]::WriteAllText($f,$t.Replace("`r`n","`n"))
   ```
   自查：`git diff --ignore-cr-at-eol --numstat` 为空、而 `--numstat` 很大，就说明只差换行。

### 2.3 后端构建与测试

```powershell
$env:JAVA_HOME='C:\jdk-17.0.14'
$env:PATH='C:\jdk-17.0.14\bin;' + $env:PATH
& 'C:\apache-maven-3.9.9\bin\mvn.cmd' -B -ntp -o -f backend/pom.xml test
# 只跑某几类：  ... test "-Dtest=ClassroomAnalysisIntegrationTest,DataModelTest" "-DfailIfNoSpecifiedTests=false"
```

- JDK 17 在 `C:\jdk-17.0.14`；Maven 在 `C:\apache-maven-3.9.9`；本地仓库 `C:\Users\70363\.m2`
- `PATH` 上的 `java` 是 Java 8，**必须显式设 JAVA_HOME**，否则编译报 release 17 不支持
- 测试用 **H2 内存库（MySQL 模式）**，不需要真 MySQL；`.m2` 首次联网拉依赖较慢
- 完整流水线：仓库内 `scripts/Verify.ps1`（Maven test/package + npm ci/typecheck/test/build）

### 2.4 前端构建与测试

```powershell
npm run typecheck   # vue-tsc --noEmit
npm test            # vitest run（同样需要 danger-full-access）
```
- tsconfig 开了 **`noUncheckedIndexedAccess`**：数组下标访问是 `T | undefined`，测试里请用显式取元素的辅助函数，
  不要用 `!` 掩盖。
- 单测在 `frontend/tests/`（不是 `src` 下），e2e 在 `frontend/e2e/`（Playwright，本轮未跑）。

### 2.5 文档生成工具链（`D:\第14组\_tools\`）

- `md2docx.py`：零依赖 Markdown→docx（标题/列表/表格/粗体）
- `build_docs.py`：把 `sport/docs/sprint2/{01..07}*.md` 生成为工作区根目录的
  `第14组_*.docx`（提交用），同时输出到 `sport/docs/sprint2/docx/`（入库留痕）；
  **09/10 号内部文档不在生成清单里**
- 改完源文件跑 `python _tools\build_docs.py` 即可重新生成 7 份交付文档

### 2.6 网络

- 本机代理实际在 **`127.0.0.1:7897`**；git 全局 `http.proxy`/`https.proxy` **已修正**为 7897（原为失效的 7890）
- `api.github.com` 可用；`github.com` 直连较慢但可推送；`gh` 已登录

---

## 三、已完成的工作（13 条故事 + 基础设施）

### 3.1 迁移与表（V21–V24，已全部通过迁移链测试）

| 版本 | 表 | 用途 |
| --- | --- | --- |
| V21 | `teacher_action` | 教师个人行动（S2-08/09/14），归属唯一用户、无跨用户读路径 |
| V22 | `classroom_report_review` + `_annotation` | 督导复核草稿与**冻结快照**（S2-03/04） |
| V23 | `classroom_report_notification` | 报告发布通知（S2-05），唯一索引 + `NOT EXISTS` 防重复 |
| V24 | `classroom_segment_override` | **片段修正覆盖层（S2-10）**，绑定 `report_id + segment_key`，报告重算不继承 |

### 3.2 接口

- `GET  /class-sessions/media/{id}/generated-report`（既有）
- `GET  /class-sessions/media/{id}/action-suggestions` —— 建议 + 证据 + `classSessionId`（S2-06/07/11/13）
- `GET  /class-sessions/media/{id}/generated-report/review` —— 督导读复核（**教师 403**）
- `PUT  /class-sessions/media/{id}/generated-report/review` —— 存标注草稿（S2-03）
- `POST /class-sessions/media/{id}/generated-report/review/confirm` —— 冻结快照（S2-04），并触发通知
- `GET  /class-sessions/media/{id}/confirmed-report` —— 教师读**最近一次已确认**版本（按 media 取，AI 重算拿不走）
- **`GET /class-sessions/media/{id}/segments`** —— 片段目录（报告片段 + 覆盖后呈现 + `mediaDurationMs` +
  原始值/修订号/修改人）；无报告版本返回 **204**；非督导 **403**（S2-01 / S2-10）
- **`PUT /class-sessions/media/{id}/segments/{segmentKey}`** —— 保存片段覆盖（督导 + `CLASSROOM_WRITE`），
  返回叠加覆盖后的完整目录；错误码 `SEGMENT_UNKNOWN`(404)、`SEGMENT_TITLE_REQUIRED`(400)、
  `SEGMENT_RANGE_INVALID`(400)、`SEGMENT_OUT_OF_MEDIA`(400)、`SEGMENT_OVERLAP`(409)、
  `SEGMENT_REPORT_NOT_READY`(409)（S2-10）
- `GET/POST/PUT /teacher-actions…` —— 教师行动（S2-08/09/14）
- `GET  /me/notifications`、`POST /me/notifications/{id}/read` —— 通知（S2-05）

### 3.3 前端页面

| 路由 | 页面 | 可见角色 |
| --- | --- | --- |
| `/my-actions` | 我的行动（建议 → 选为尝试 → 管理；含「回看证据」就地播放，S2-11） | 任课教师 |
| `/my-feedback` | 我的反馈（通知 → 督导确认版本 → 复核说明逐条对照 + 片段播放，S2-12/05） | 任课教师 |
| `/reports/review`、`/reports/:mediaId/review` | 报告复核（**片段目录 + 片段修正**、标注 → 草稿 → 确认冻结，S2-01/03/04/10） | 教学督导 |
| `/classrooms/:classSessionId/evidence` | 证据回看（区间播放 + 证据联动，S2-02） | 授权读者（**暂无导航入口**） |

纯逻辑模块（均有单测）：`lib/evidencePlayback.ts`、`lib/evidenceRefs.ts`、`lib/confirmedFeedback.ts`、
`lib/segmentDirectory.ts`（含 `directorySegments` 与服务端同规则的 `validateSegmentEdit`）

### 3.4 验证基线（最近一次，S2-10 之后）

- 后端（全量套件）：**335 项 0 失败 / 0 错误**（10 项需外部环境的用例被 skip）
- 后端（针对性 5 个类，迭代时用）：88 项 0 失败（`ClassroomAnalysisIntegrationTest` 33、`FixtureIntegrationTest` 33、
  `DataModelTest` 15、`ApplicationTest` 6、`MigrationModeTest` 1）
- 前端：`vue-tsc` 干净；vitest **24 文件 / 150 项全绿**
- **`scripts/Verify.ps1` 未整跑**（它还含 Maven package 与 npm ci/build，提交前补跑一次）
- 提交历史：`bdee344` → … → `37c29a3` → `2245895`（交接文档）→ `abacee6`（S2-10 代码）→
  `6a6e875`（文档）→ `73f2b53`（`--no-ff` 合并回 `sprint2`），功能分支与 `sprint2` 均已推送 origin

---

## 四、S2-10 的落地结果与取舍（本轮新增）

`V24 classroom_segment_override` + `ClassroomSegmentService`（5 条**服务端强制**规则 + 权限）+
`GET/PUT /class-sessions/media/{id}/segments` + 复核页片段修正态 + 前端 `api/segments.ts`。
完整设计与实现清单见 `docs/sprint2/08-remaining-stories-plan.md` 第三节，决策见 `10-decisions.md`。

两条**必须知道的取舍**（都不是随手定的）：

1. **已确认（冻结）报告仍允许覆盖**（DEC-S2-10-01）：覆盖不改报告本体，冻结快照仍是教师读到的那一版。
2. **边界未变时不查重叠**（DEC-S2-10-02）：AI 报告的 `topics` 允许互相重叠（输出校验器只保证证据落在本片段内），
   若对"只改标题"也强制不重叠，督导连给一个与邻居重叠的片段改名都做不到。
   **服务端与前端 `validateSegmentEdit` 必须同时保持这条规则**，否则会出现"前端提示合法、服务端拒绝"。

**已知限制**：覆盖只在督导复核页生效，教师端「我的反馈」仍读冻结快照里的原始划分（见决策日志"未决事项"）。

---

## 五、`S2-13` 的未完成部分

`查看 AI 建议不可用原因` 已完成（返回 `reasonCode`），但**"受控重试"未实现**：
当前仍走既有的督导报告重试入口（`SUPERVISOR` 权限），教师侧重试额度与频次规则
**需与吕厚含对齐后**再补，不要在未对齐前自行定规则（DEC-S2-13-01）。

---

## 六、外部依赖（不在我们这边，但影响交付质量）

1. **吕厚含的 Sprint 1 速率数据与回顾改进措施原文**——实验三把"上轮改进项落实情况"
   作为硬性检查项并计入 **15%**；`docs/sprint2/03-retro-meeting.md` 与 `05-self-assessment.md`
   中仍是 `【待吕厚含提供】` 占位。**不得代为编造**（指导书明确"原始记录不得事后补造"）。
2. **AI 互动原始记录（表5）仍是空模板**（`04-ai-interaction-log.md`）。本轮 S2-10 的实现过程是
   一次真实的 AI 互动，需由记录人**当场**从会话转录：任务场景（S2-10 后端覆盖层）、
   人类输入要点、AI 输出要点、处置结论（采纳了哪些、人工改了什么——例如把 `start_ms` 由 `INT` 改为
   `BIGINT`、把"已确认报告可否覆盖"上升为人类决策）。**不得事后补造**，也不要直接采用生成式补写。
3. **Sprint 1 的非 DRI 人工交叉验收**尚未补做（原 `require1.md` §6.7 要求）。
4. `sport/docs/` 目录在**交付给我们的 Sprint 1 包里是缺失的**（README 引用的
   `require1.md`/`AGENTS.md`/`PROGRESS.md`/`docs/decisions/*` 都不在包内）；
   本仓库的 `docs/sprint2/` 是我们自己新建的。

---

## 七、待决策事项（已记入 `docs/sprint2/10-decisions.md`）

1. **督导角色判断是全局的**：`user.roles().contains("SUPERVISOR")`，未绑定到具体开课的督导范围
   （与既有 `requestGeneratedReportRetry` 一致）。正确做法应为要求该开课上的 `SUPERVISION` 用途可见性
   （DEC-S2-05-01，技术债）。
2. **片段修正是否要对教师可见**：目前仅督导复核页生效；若要教师看到修正后的划分，
   需先定"修正版 vs 冻结版"的呈现关系（10-decisions 未决事项 #1）。
3. **上下文页的导航入口**：`/classrooms/:classSessionId/evidence` 需要 `classSessionId`，
   目前无导航入口；复核页与「我的反馈」已有入口。是否嵌入 39KB 的 `ClassroomAnalysisView.vue` 待定
   （此前刻意未改该文件）。

---

## 八、下次会话的第一步（可直接执行）

```powershell
# 1. 看仓库状态与分支
git -C 'D:\第14组\sport' log --oneline -10
git -C 'D:\第14组\sport' status --short

# 2. 读三份文档
#    D:\第14组\sport\docs\sprint2\09-handoff.md               （本文）
#    D:\第14组\sport\docs\sprint2\08-remaining-stories-plan.md（S2-01/S2-10 设计与实现清单）
#    D:\第14组\sport\docs\sprint2\10-decisions.md             （决策日志与技术债）

# 3. 提交前：跑完整流水线（需 danger-full-access）
$env:JAVA_HOME='C:\jdk-17.0.14'; $env:PATH='C:\jdk-17.0.14\bin;'+$env:PATH
& 'D:\第14组\sport\scripts\Verify.ps1'          # Maven test/package + npm ci/typecheck/test/build

# 4. 针对性复跑 S2-10（更快）
& 'C:\apache-maven-3.9.9\bin\mvn.cmd' -B -ntp -o -f 'D:\第14组\sport\backend\pom.xml' test `
    "-Dtest=ClassroomAnalysisIntegrationTest,DataModelTest,ApplicationTest,MigrationModeTest,FixtureIntegrationTest" `
    "-DfailIfNoSpecifiedTests=false"
# 前端：workdir = sport\frontend，npm run typecheck; npm test

# 5. 改完 markdown 后重新产出 7 份 docx
python 'D:\第14组\_tools\build_docs.py'
```

**提交纪律**：每完成一条故事 → `feature/` 分支提交 → 合回 `sprint2`（`--no-ff`）→
推送两个分支；提交信息写明故事编号与"已知限制"。**不要把没验证完的东西合进 `sprint2`**。

**提交作业前**：跑一次完整 `scripts/Verify.ps1`；回填 `01-plan-meeting.md` 表4-A 的完成状态、
`05-self-assessment.md` 的速率/燃尽/技术债附表；把本轮人机互动当场录入 `04-ai-interaction-log.md` 表5；
关掉 `~$` 锁文件对应的 Word/Excel 后再打包。
