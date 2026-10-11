# Round 2 实施报告 · 三档质量与课程均值口径（v0.2）

日期：2026-09-14。范围：**只做 Round 2**（含 Round 1 已交付内容）。本轮明确不实现二次模型调用。

> 目标：让"数据少"与"数据不可测"在系统里可区分，并消除 0.60 单阈值造成的状态翻转。

---

## 1. 修改文件

| 文件 | 改动 |
| --- | --- |
| `src/headup/config.py` | 新增 `quality_valid_threshold`(0.70)、`quality_review_threshold`(0.50)、`quality_threshold_hysteresis`；`/health` 暴露 `promptVersion`/`metricVersion`/`quality` 段落 |
| `src/headup/pipeline.py` | 新增 `classify_window()`（三档，按 coverage）+ `QualityDecision`；`WindowOutcome` 增加 `quality_status`；payload 每窗增加 `qualityStatus`；REVIEW 窗口在 COUNTS/RATIO_ONLY 下都带人数与比率；`compute_stats()` 升为 **stats-v2** |
| `src/headup/wire.py` | 新增 `QUALITY_REVIEW`；`QUALITIES` 与窗口校验接受 REVIEW（计数约束同 VALID）；新增 `QUALITIES_AGGREGATED = (VALID,)` |
| `tests/test_round2_quality.py` | **新增** 21 个验收用例 |
| `tests/test_pipeline.py` | 旧口径用例改写为 v2 语义（时长加权 → SUM 比），并**新增**分叉对照用例 |
| `tests/test_contract.py` | `mean_is_duration_weighted_*` 改为"v1 对照值 + v2 正式值"双断言 |
| `tests/test_http.py` | 降级用例适配新原因码（`NO_VISIBLE_PERSON`）与 `qualityStatus` |
| `docs/fixtures/.../verify_head_up_fixtures.py` | 自校验脚本升到 stats-v2（含 v1 对照与 `aggregation`/`aggregatedWindowCount` 断言） |
| `docs/fixtures/.../head-up-realclassroom-stats.json` | 用冻结的真实结果按 stats-v2 **重算**（真实模型输出 `-success.json` 未改动） |

**未删除任何测试与冻结样例**；`head-up-success.json` / `head-up-ratio-success.json` 与主项目冻结样例仍逐字段一致。

---

## 2. 关键设计

### 2.1 三档质量以 coverage 为准

```text
coverage = evaluable_count / visible_count

画面里没有人（visible=0）      → NO_SUBJECTS   （status=NO_DATA，reason=NO_VISIBLE_PERSON）
有人但一个都判断不了            → LOW_QUALITY   （status=UNREADABLE，reason=UNREADABLE_ALL_SUBJECTS）
coverage >= 0.70               → VALID         （进课程均值）
0.50 <= coverage < 0.70        → REVIEW        （不进均值，待 Round 3 复核）
coverage < 0.50                → LOW_QUALITY   （status=UNREADABLE，reason=COVERAGE_BELOW_REVIEW）
```

阈值全部来自配置（`HEADUP_QUALITY_VALID_THRESHOLD` / `HEADUP_QUALITY_REVIEW_THRESHOLD`），
测试用"改配置就能改档位"证明没有硬编码。

### 2.2 0.599 / 0.600 不再翻转

v0.1 的单一 0.60 阈值会让 `0.599` 与 `0.600` 在 `VALID` 与 `LOW_QUALITY` 之间翻转。
v0.2 下两者都落在 **REVIEW 带**内 ⇒ 状态相同，且都不进课程均值。
（用例 `test_old_0_6_cliff_no_longer_flips_state` 钉住这一点。）

### 2.3 REVIEW 的语义与数据

REVIEW 表示**"算得出来、但可观测性不足"**，因此：

- 保留 `headUpCount` / `validObservedCount`（COUNTS）或 `headUpRatio`（RATIO_ONLY）；
- 保留 `headUpRate` 与 `coverage`；
- 带 `qualityReason = COVERAGE_IN_REVIEW_BAND`；
- **不进入课程均值**。

这保证"数据少"不会被显示成"没有数据"，也不会污染均值。

### 2.4 课程均值（stats-v2）

```text
meanRate = SUM(head_up_count) / SUM(evaluable_count)     只含 VALID 窗口
```

同时输出：`aggregation="sum_ratio"`、`aggregatedWindowCount`、`sumHeadUpCount`、
`sumEvaluableCount`、`validCoverage`（避免"均值很精确但大部分课程不可测"）、
`qualityCounts`、`windowRates`（只 VALID 有值）、`windowRatesAll`（含 REVIEW 的实测值，诊断用）。

### 2.5 ⚠️ 与冻结契约的口径冲突（必须三方确认）

Round 2 要求的 `SUM(up)/SUM(evaluable)` 与契约 v0.1 冻结的 `stats-v1`（时长加权
`Σ(r_i·d_i)/Σd_i`）**在窗口不等长时分叉**；主项目文档原文写的是"**不使用**
`ΣheadUpCount/ΣvalidObservedCount`"。本次按你的 Round 2 明确要求执行，并做了三件事控制风险：

1. **显式升版**为 `stats-v2`，`statisticsVersion` 字段变化可被主项目检测；
2. **保留 v1 对照值** `v1DurationWeightedMeanRate`，便于逐窗核对；
3. 演示冻结样例**未改动**：其 6 个窗口等长，两式同值 0.4625，`expected-statistics.json` 仍然成立。

真实数据上的差异（50 秒 5 窗、flash、严格口径）：

| 口径 | 值 |
| --- | --- |
| v2 `SUM(up)/SUM(evaluable)` = 27/35 | **0.7714** |
| v1 时长加权 | 0.6687 |

⇒ 主项目必须决定页面展示哪一个，并同步更新 `docs/接入指南` 与 DTO。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 164 tests in 44.1s
OK
```

Round 2 新增 21 个用例，逐条对应验收要求：

| 验收要求 | 用例 |
| --- | --- |
| coverage 0.71 → VALID | `test_coverage_0_71_is_valid` |
| coverage 0.70 → VALID | `test_coverage_0_70_is_valid` |
| coverage 0.60 → REVIEW | `test_coverage_0_60_is_review` |
| coverage 0.50 → REVIEW | `test_coverage_0_50_is_review` |
| coverage 0.49 → LOW_QUALITY | `test_coverage_0_49_is_low_quality` |
| 不再有 0.6 单阈值翻转 | `test_old_0_6_cliff_no_longer_flips_state` |
| 阈值配置化 | `test_thresholds_come_from_config`、`test_review_band_is_configurable_and_validates_order` |
| REVIEW 不进均值 | `test_review_excluded_from_mean`、`test_review_windows_do_not_enter_course_mean` |
| LOW_QUALITY 不进均值 | `test_low_quality_excluded_from_mean`、`test_low_quality_and_no_subjects_do_not_enter_course_mean` |
| 均值 = SUM 比（不是率平均） | `test_mean_is_sum_ratio_not_average_of_window_rates` |
| 加权/覆盖率同报 | `test_valid_coverage_reported_alongside_mean` |
| 窗口含 rate/coverage/quality | `PayloadAndWireCompatibilityTest`（4 个用例） |

### 3.1 真实模型端到端（严格口径 + flash + HTTP 契约路径）

| 窗口 | 采样点 | 质量 | status | 抬头/分母 | coverage | 原因 |
| --- | --- | --- | --- | --- | --- | --- |
| win-001 | 5000 | VALID | VALID | 4/6 | 0.750 | — |
| win-002 | 15000 | **REVIEW** | REVIEW | 4/6 | **0.600** | `COVERAGE_IN_REVIEW_BAND` |
| win-003 | 25000 | VALID | VALID | 14/16 | 0.800 | — |
| win-004 | 35000 | VALID | VALID | 8/10 | 0.714 | — |
| win-005 | 45000 | VALID | VALID | 1/3 | 0.750 | — |

- 统计：`stats-v2`、`sum_ratio`、均值 **0.7714**（=27/35，4 个 VALID 窗口）、v1 对照 0.6687；
- 有效窗口覆盖 0.8（5 窗中 1 窗进 REVIEW）；端到端 8.1 秒；
- **背对镜头机位（win-002，coverage 0.60）没有被当成 VALID**——正是本轮想达到的效果。

---

## 4. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | REVIEW 目前只是"不进均值"，没有任何后续动作 | **Round 3**：三帧自适应复核 |
| 2 | `stats-v1` ↔ `stats-v2` 冲突需主项目确认展示口径 | 待确认；两份值都已暴露 |
| 3 | 阈值 0.70/0.50 是方案给定值，**未用真实课堂分布校准** | Round 5（有 Ground Truth 后校准） |
| 4 | `quality_threshold_hysteresis` 已加配置但默认 0（未启用） | 若 Round 4 实测翻转率高再启用 |
| 5 | 未验证阈值变化对真实课堂"可测比例"的影响（本机素材只有 5 窗） | Round 5 用 100–200 窗统计 |
| 6 | 契约 v0.1 未定义 `REVIEW` 与 `qualityStatus`，主项目适配器需识别 | 已在交接表/接入指南登记为补充值 |

---

## 5. 验收标准核对

| 验收标准 | 结果 |
| --- | --- |
| 不存在单一 0.6 阈值导致的大幅状态翻转 | ✅ 0.599/0.600 同档（REVIEW）；阈值配置化，两侧区间明确 |
| VALID / REVIEW / LOW_QUALITY 逻辑清晰 | ✅ 以 coverage 判定，`status` 四态（VALID/REVIEW/NO_DATA/UNREADABLE）+ 语义化 reason |
| 课程均值只使用可靠窗口 | ✅ 只含 VALID；同时输出 `validCoverage`、`aggregatedWindowCount`、`qualityCounts` |
| 全部测试通过 | ✅ 164 个用例全绿；冻结样例逐字段一致仍为 `True` |

**本轮验收通过。** 本轮未实现（按要求）：二次模型调用、时间平滑、Flash/Plus 切换、真实课堂 Benchmark。
