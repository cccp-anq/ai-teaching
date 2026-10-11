# Round 3 实施报告 · 自适应三帧复核（v0.2）

日期：2026-09-14。范围：**只做 Round 3**。

> 目标：只给**不稳定窗口**补证据，而不是把所有帧都调用两次。
> 核心约束：复核必须使用**额外视觉信息**（相邻帧），三帧仍不足就诚实降级——禁止用时间连续性推断姿态。

---

## 1. 修改文件

| 文件 | 改动 |
| --- | --- |
| `src/headup/config.py` | 新增复核配置：`review_enabled`、`review_unknown_ratio_threshold`(0.50)、`review_sudden_change_threshold`(0.20)、`review_adjacent_frame_offset_ms`(500)、`review_total_max_attempts`(3)、`review_on_low_quality`(false)；`/health` 新增 `review` 段与 `samplingPolicyVersion` |
| `src/headup/pipeline.py` | 新增 `needs_review()`、`select_estimate()`（中位数帧）、`finalize_review()`、`_review_frames()`；`_process_window()` 接入复核；`WindowOutcome.review`；payload 每窗新增 `resultKind` 与 `review` 证据；新增告警 `MULTI_FRAME_REVIEW_APPLIED:n` |
| `tests/test_round3_review.py` | **新增** 20 个验收用例（含脚本化分析器，精确断言调用量） |

**未删除任何测试与冻结样例**；Round 1/2 的全部用例继续通过。

---

## 2. 关键设计

### 2.1 触发条件（任一命中即复核，全部配置化）

| 原因码 | 条件 | 默认阈值 |
| --- | --- | --- |
| `QUALITY_REVIEW` | 三档结果为 REVIEW（coverage 落在 0.50–0.70） | 由 Round 2 阈值决定 |
| `UNKNOWN_RATIO_HIGH` | `unknown_count / visible_count >= 阈值` | 0.50 |
| `SUDDEN_CHANGE_VS_PREVIOUS_VALID` | 与**前一个有效窗口**的抬头率之差 >= 阈值 | 0.20 |
| `LOW_QUALITY_CONFIGURED` | 显式开启 `review_on_low_quality` 时 LOW_QUALITY 也复核 | **默认关闭** |

`review_enabled=false` 时完全不复核（退回 Round 2 行为）。

### 2.2 三帧从哪来（禁止重复同一张图）

```text
代表帧 t（第一轮已判读，直接复用，不重复请求）
  + 相邻帧 t-500ms
  + 相邻帧 t+500ms
```

- 相邻帧是**重新抽帧**、不同文件名、不同时间点；
- 偏移后越界的时间点被裁剪，**且必须仍落在本窗口内**（避免把相邻窗口的画面算进本窗）；
- 相邻帧取不到（抽帧失败/分析失败）只是少一份证据，不编造数据。

### 2.3 三帧如何合成（保持"单帧语义"）

**中位数帧**，不是求和：

```
medianCoverage = median(各帧 coverage)
medianRate     = median(各帧 head_up_rate)
最终计数       = 最接近 medianRate 的那一帧（保持人数与比率的可比性）
```

不求和的原因：求和会把分母变成"人·帧"，语义从"学生里多少人抬头"漂移为"学生观测里多少人次抬头"，
且与课程均值的 `SUM(up)/SUM(evaluable)` 不同源。中位数帧既稳健又不改语义。

### 2.4 状态流转

```text
VALID  ──────────────► 直接接受（1 次调用，无 review 字段）
REVIEW ──► 三帧复核 ──► VALID（REVIEW_CONFIRMED_VALID）
                   └──► LOW_QUALITY（REVIEW_INSUFFICIENT_EVIDENCE，两数 null）
LOW_QUALITY ─────────► 默认不复核
```

复核后**不再停留 REVIEW**：要么确认 VALID，要么诚实降级为 LOW_QUALITY。

### 2.5 窗口语义（Round 3 §3）

payload 每窗新增 `resultKind`：

| 值 | 含义 |
| --- | --- |
| `SINGLE_FRAME` | 单帧结果（正常窗口） |
| `WINDOW_ESTIMATE_MULTI_FRAME` | 三帧复核后的**时间点附近窗口估计值** |

复核证据（`windows[i].review`）包含：`reasons`、`outcome`、`frameCount`、`medianHeadUpRate`、
`medianCoverage`、`perFrameCounts`（逐帧计数）。全量逐帧细节在诊断记录里。

### 2.6 调用量控制

| 情况 | 模型调用次数 |
| --- | --- |
| 稳定窗口（VALID） | 1 |
| REVIEW / 突变 / UNKNOWN 过高 | 3（1 代表帧 + 2 相邻帧） |
| 复核关闭 | 1（全部） |

**正常采集下不是 ×2**：单元测试验证 4 窗口（3 稳定 + 1 复核）= 6 次调用（1.5/窗），
真实数据实测 5 窗口 = 9 次（1.8/窗，因触发 3 次复核）。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 184 tests in 48.3s
OK
```

Round 3 新增 20 个用例：

| 验收要求 | 用例 |
| --- | --- |
| VALID 不触发二次调用 | `test_valid_does_not_trigger`、`test_valid_window_makes_single_call_no_review` |
| REVIEW 触发三帧复核 | `test_review_quality_triggers`、`test_review_window_makes_three_calls_with_adjacent_frames` |
| 突变窗口触发复核 | `test_sudden_change_triggers`、`test_sudden_change_window_is_reviewed` |
| UNKNOWN 占比过高触发 | `test_high_unknown_ratio_triggers` |
| 三帧仍不可判断 → LOW_QUALITY | `test_insufficient_evidence_degrades_to_low_quality`、`test_all_three_frames_unreadable_is_low_quality`、`test_no_temporal_extrapolation` |
| 相邻帧提供证据 → VALID | `test_extra_evidence_confirms_valid`、`test_review_window_makes_three_calls_with_adjacent_frames` |
| 调用量不是 ×2 | `test_average_calls_per_window_stays_close_to_one` |
| 用相邻帧而非重复同帧 | `test_review_frames_are_distinct_timepoints` |
| 阈值配置化 | `test_thresholds_are_configurable`、`test_disabled_review_never_triggers` |
| 不得求和 | `test_estimate_is_median_frame_not_sum` |

### 3.1 真实模型端到端（严格口径 + flash + HTTP 契约路径）

| 窗口 | 窗口质量 | resultKind | 复核 | 抬头/分母 | coverage | 原因 |
| --- | --- | --- | --- | --- | --- | --- |
| win-001 | VALID | SINGLE_FRAME | — | 4/6 | 0.750 | — |
| win-002 | **LOW_QUALITY** | WINDOW_ESTIMATE_MULTI_FRAME | `QUALITY_REVIEW` ×3 | — | 0.600 | `REVIEW_INSUFFICIENT_EVIDENCE` |
| win-003 | VALID | WINDOW_ESTIMATE_MULTI_FRAME | `SUDDEN_CHANGE` ×3 | 14/16 | 0.800 | `REVIEW_CONFIRMED_VALID` |
| win-004 | VALID | SINGLE_FRAME | — | 8/10 | 0.714 | — |
| win-005 | VALID | WINDOW_ESTIMATE_MULTI_FRAME | `SUDDEN_CHANGE` ×3 | 1/3 | 0.750 | `REVIEW_CONFIRMED_VALID` |

- 触发 3 次复核（win-002 因 REVIEW、win-003/005 因与前值突变），共 9 次模型调用（1.8/窗）；
- `warnings: ["LOW_QUALITY_WINDOWS", "MULTI_FRAME_REVIEW_APPLIED:3"]`；
- 均值仍为 **0.7714**（=27/35，4 个 VALID 窗口）；端到端 **8.1s → 16.2s**（复核增加约 2 倍调用）；
- **背对镜头机位（win-002）复核后仍未达标 ⇒ 降级为 LOW_QUALITY，不进入均值**——
  正是"三帧仍无证据时愿意输出 LOW_QUALITY"的预期行为。

---

## 4. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | 真实数据上 3/5 窗口触发复核（含两次突变），调用量 1.8/窗——突变阈值 0.20 在本素材上偏敏感 | Round 4 用重复实验量化抖动后校准；阈值已配置化 |
| 2 | 复核用中位数帧，仍未做"多帧一致性"判定（如三帧方差过大时是否应降级） | 待 Round 5 有 Ground Truth 后评估 |
| 3 | 复核帧的时间偏移（±500ms）未做敏感性实验 | Round 4/5 |
| 4 | 复核后的 `medianHeadUpRate` 与 `selectedHeadUpRate` 可能不同（取最接近中位数的那一帧） | 已在证据里同时给出，供页面/审计选择 |
| 5 | `stats-v1` ↔ `stats-v2` 展示口径仍待主项目确认 | 待确认 |
| 6 | 复核也会消耗 API 成本（1.8 倍），需在预算里体现 | 交接表已记录 |

---

## 5. 验收标准核对

| 验收标准 | 结果 |
| --- | --- |
| 只有不稳定窗口发生额外调用 | ✅ 稳定窗口 1 次；实测 9 次/5 窗 = 1.8/窗；关闭开关则恒为 1 次 |
| 二次调用使用相邻帧，不是重复同帧 | ✅ `t±500ms` 重新抽帧、不同文件；用例断言文件名与观测不同 |
| 三帧仍无证据时系统愿意输出 LOW_QUALITY | ✅ win-002 真实降级为 LOW_QUALITY + `REVIEW_INSUFFICIENT_EVIDENCE`，不进入均值 |
| 全部测试通过 | ✅ 184 个用例全绿；冻结样例逐字段一致未受影响 |

**本轮验收通过。** 本轮未实现（按要求）：时间平滑、Flash/Plus 切换、真实课堂 Benchmark、页面表达。
