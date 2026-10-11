# FRAME-5 实施报告 · Workspace 生命周期 + 任务状态机 + 逐窗口样本

日期：2026-09-15。范围：**FRAME-5**（plan §6/§16/§18/§19/§20）。同时把 **SamplingPolicy 真正驱动抽帧**接上。

---

## 1. 修改文件

| 文件 | 内容 |
| --- | --- |
| `src/headup/video/workspace.py` | **新增**：`FrameWorkspaceService` / `TaskWorkspace` —— `primary/` + `review/{centerTimestamp}/` + `task.json`；成功即删、失败按 TTL 保留、`cleanup_expired()` 幂等清理 |
| `src/headup/store.py` | `head_up_job` 增列 `current_stage` / `error_stage`（**幂等增量迁移**）；新增 `head_up_sample` 表与 `save_samples()`（唯一键 `(job_id, window_id)`，可重复写）、`samples_of()`、`set_stage()`/`stage_of()` |
| `src/headup/pipeline.py` | 阶段上报（`STAGE_*` + `stage_callback`）、工作目录接入、**批量抽帧驱动**（`_extract_primary_frames`）、复核帧落 `review/` 子目录、结果里带 `generation.workspace` |
| `src/headup/service.py` | 把阶段回调接到 `store.set_stage`；成功后 `_persist_samples()` 写逐窗口样本；`samples()`/`stage()` 查询 |
| `src/headup/providers.py` | `Provider.run()` 增加 `job_id` / `stage_callback`（合成 provider 也接受） |
| `src/headup/server.py` | `GET /api/v1/jobs/{id}` 增量返回 `progress`（阶段）与 `samples`（逐窗口样本） |
| `src/headup/cli.py` | 新增 `cleanup` 子命令（TTL 清理，可挂定时任务） |
| `src/headup/config.py` | 新增 `failed_task_retention_hours`（默认 12） |
| `tests/test_frame5_workspace_store.py` | **新增** 18 个用例 |
| `tests/support.py` | 测试服务改用系统分配端口（见 §4 环境注记） |

**未删除任何测试**；受"代表帧改名"影响的脚本化分析器用例已改按时间戳匹配。

---

## 2. 关键设计

### 2.1 工作目录生命周期（plan §6/§16）

```text
{workDir}/tasks/headup/{taskId}/
├── primary/               代表帧（文件名带 timestampMs）
├── review/000010000/      复核三帧（按 centerTimestamp 分目录，证据自包含）
└── task.json              任务元数据 + outcome + stage
```

| 结局 | 行为 |
| --- | --- |
| SUCCESS | **立即删除**整个任务目录（不永久保存课堂截图）；`generation.workspace.cleanedUp=true` |
| FAILED | 保留 `failed_task_retention_hours`（默认 12h），`task.json` 记 `outcome=FAILED` 与 `stage` |
| TTL | `cleanup_expired()` 只删"已过期且 outcome=FAILED"的目录，幂等、可重复调用（CLI：`python -m headup cleanup`） |

`frames_retain > 0`（调试模式）时成功的任务也会保留。

### 2.2 任务状态机（plan §18）

```text
PENDING → PROBING_VIDEO → EXTRACTING_PRIMARY → ANALYZING_PRIMARY → REVIEWING → AGGREGATING → SUCCESS
                                                                                          ↘ FAILED
```

- 对外**仍只暴露契约的** `QUEUED/RUNNING/SUCCEEDED/FAILED`；阶段是内部进度，通过 `GET` 的
  `progress.currentStage` 暴露（增量字段，主项目可忽略）；
- 失败时写 `error_stage`（由 `_ERROR_STAGE_BY_CODE` 映射，如 `VISION_PROVIDER_ERROR → ANALYZING_PRIMARY`）；
- 终态后阶段不再变更（有用例钉住）。

### 2.3 逐窗口样本落库（plan §19/§20）

表 `head_up_sample`：时间戳、窗口区间、`visible/evaluable/head_up/head_down/unknown`、
`head_up_rate`、`coverage`、`quality`/`status`/`reason`、`reviewed`、`review_strategy`、
**`final_observation_source`**（`PRIMARY` / `REVIEW_WINDOW`）、五个版本字段。

**不落临时帧路径**（有用例断言表里没有 `image_path` 之类列）；唯一键 `(job_id, window_id)` ⇒ 重复写幂等。

### 2.4 采样策略驱动抽帧（FRAME-2/3 的遗留）

```python
timestamps = [plan.sample_at_ms for plan in plans]      # 网格来自 SamplingPolicy
if cfg.sampling_use_batch_extraction and len(timestamps) > 1:
    extractor.extract_batch(timestamps, workspace.primary_dir)   # 一次 ffmpeg
else:
    [extractor.extract_at(ts, ...) for ts in timestamps]         # 逐点 seek
```

- 批量路径内部有**首帧等价性校验**与**整段回退**（FRAME-2 已实测：最大像素差 0.0017）；
- 批量失败不影响任务：回退逐点 seek 并记警告。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 277 tests in 80.3s
OK
```

FRAME-5 新增 18 个用例：工作目录（创建/成功清理/失败保留/TTL/幂等/保留开关/列表）、
阶段（写入、失败阶段、终态不可改）、流水线（阶段顺序、成功无残留、失败留目录并上报错误阶段、
复核帧落 `review/{centerTimestamp}/`、边界裁剪跳过）、样本（幂等写入、不落帧路径、
`final_observation_source` 区分 PRIMARY/REVIEW_WINDOW、服务层实际落库）。

### 3.1 真实端到端（批量抽帧 + 多图复核 + 阶段 + 样本）

| 项 | 实测 |
| --- | --- |
| 均值 | 0.6957 = 16/23（与 FRAME-4 完全一致 ⇒ 批量抽帧未改变结果） |
| 质量分布 | 3 VALID + 2 LOW_QUALITY |
| 复核 | 4/4 窗口 `MULTI_IMAGE_SINGLE_REQUEST` |
| workspace | `cleanedUp=true`，共 17 帧（5 代表帧 + 4×3 复核帧）在成功后被删除 |
| 端到端 | 14.2s（FRAME-4 同配置 14.1s） |
| 阶段 | `currentStage=SUCCESS`、`errorStage=null` |
| 样本 | 5 条落库，逐窗含 rate/coverage/质量/来源/五版本 |

落库样本实况（数据库查询）：

| 窗口 | 时间戳 | 抬头/分母 | rate | coverage | 质量 | 最终来源 |
| --- | --- | --- | --- | --- | --- | --- |
| win-001 | 5000 | 4/6 | 0.667 | 0.75 | VALID | PRIMARY |
| win-002 | 15000 | — | — | 0.375 | LOW_QUALITY | REVIEW_WINDOW |
| win-003 | 25000 | 12/14 | 0.857 | 0.778 | VALID | REVIEW_WINDOW |
| win-004 | 35000 | — | — | 0.600 | LOW_QUALITY | REVIEW_WINDOW |
| win-005 | 45000 | 0/3 | 0.0 | 0.75 | VALID | REVIEW_WINDOW |

---

## 4. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | TTL 清理目前**靠外部调用**（CLI/定时任务），服务内没有后台线程 | 部署时挂计划任务；若需要可加后台线程（FRAME-6 之后） |
| 2 | 阶段只在 `GET` 暴露，`POST` 的 202 响应不含 `progress` | 可接受（受理时阶段必然是 PENDING） |
| 3 | 逐窗口样本没有对外独立路由（只在 `GET jobs/{id}` 附带，5 窗规模无压力） | 若主项目需要可分页路由 |
| 4 | `frames.py` 的旧 `plan_frames` 仍在（CLI/旧测试用），与 SamplingPolicy 并存 | FRAME-6 清理 |
| 5 | 多图复核的 token 用量仍未单独测 | FRAME-6 记录成本 |
| 6 | 真实 45 分钟视频未到，性能/成本仍是外推 | 等用户提供 |

### 环境注记（本机实测，非代码问题）

- **端口 8080 在本机被系统保留**（绑定直接 `WinError 10013`，`netstat` 也看不到监听者）。
  测试已改用系统分配端口（`server_port=0`）；部署时请避开保留端口段。

---

## 5. 验收标准核对

| 验收标准（plan §16/§18/§19/§20 + §23 FRAME-5） | 结果 |
| --- | --- |
| `FrameWorkspaceService`：创建 primary/review、成功清理、失败 TTL 清理 | ✅ 18 个用例覆盖；真实运行 `cleanedUp=true` |
| SUCCESS 无临时残留 / FAILED 可短期调试 / TTL 后自动清除 | ✅ 实测 + 用例（含幂等与"非失败目录不参与清理"） |
| 任务阶段可追踪，失败有 `error_stage` | ✅ 真实运行 `SUCCESS`；失败用例上报错误阶段 |
| `head_up_sample` 落库，含时间戳/观测/质量/版本，且不存临时帧 | ✅ 真实运行 5 条；列断言排除帧路径 |
| `final_observation_source` 明确最终结果来源 | ✅ PRIMARY / REVIEW_WINDOW 实测区分 |
| SamplingPolicy 驱动实际抽帧（批量优先 + 回退） | ✅ 真实运行批量模式，结果与逐点一致（0.6957） |
| 不破坏既有口径与测试 | ✅ 277 个用例全绿；冻结样例逐字段一致未受影响 |

**FRAME-5 验收通过。** 等指示继续 **FRAME-6**（性能与稳定性 Benchmark：批量 vs 逐点耗时/CPU/磁盘、
Qwen 总调用数、Review 比例、每节课耗时与成本；并据此决定是否引入 Decord —— 按 plan §23/§28，
没有实测数据前不引入）。
