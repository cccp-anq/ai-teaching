# Round 1 实施报告 · 严格观察口径（v0.2）

日期：2026-09-14。范围：**只做 Round 1**（用户方案明确"完成后停止，等待下一 Round"）。

> 核心原则落地：**宁可输出"无法可靠判断"，也不在视觉证据不足时猜测一个精确的抬头率。**

---

## 1. 修改文件

### 新增

| 文件 | 作用 |
| --- | --- |
| `src/headup/prompts.py` | 提示词台账（版本化）。`headup-strict-v0.2`（默认，严格口径）与 `headup-prompt-1`（旧版，保留兼容） |
| `src/headup/counts.py` | 统一计数模型 `WindowCounts`、两条等式的自校验、程序计算的 `head_up_rate`/`coverage`、多帧聚合（Round 3 复用） |
| `tests/test_round1_strict.py` | Round 1 验收测试（23 个用例） |
| `docs/rounds/Round1-严格口径.md` | 本报告 |

### 修改

| 文件 | 改动 |
| --- | --- |
| `src/headup/analyze.py` | 默认改用严格提示词；解析新五计数结构；计数不自洽 ⇒ `CountInconsistency` ⇒ 额外重试 + 主备模型；`FrameReading` 增加 `counts`/`visible_count`/`evaluable_count`/`head_up_rate`/`coverage`（旧构造点自动补齐，向后兼容） |
| `src/headup/config.py` | 新增 `prompt_version`、`strict_counts`、`counts_retry_extra`（均可环境变量覆盖） |
| `src/headup/pipeline.py` | 窗口诊断记录增加 counts/rate/coverage/metricVersion/promptVersion；payload 每窗增加增量字段 `headUpRate`/`coverage`；payload 增加 `metricVersion`/`promptVersion` |
| `tests/test_qwen_client.py` | 假服务改为返回新五计数结构；新增后方兼容与自适应重试断言（**未删除任何用例**） |

---

## 2. 关键设计

### 2.1 严格提示词（`headup-strict-v0.2`）

四条硬约束（测试逐条断言其存在，防止被无意改回宽松版）：

```text
1. 只根据直接可见的视觉证据判断
2. 仅看到后脑 / 背对镜头 ⇒ 必须 UNKNOWN
3. 不得根据身体朝向、座位位置、周围同学状态、课堂常识推测 UP / DOWN
4. 宁可增加 UNKNOWN，也不猜
```

模型输出新结构（旧三字段仍被兼容层接受）：

```json
{"visible_count": 20, "evaluable_count": 15, "head_up_count": 10,
 "head_down_count": 5, "unknown_count": 5, "confidence": 0.9, "note": ""}
```

### 2.2 程序端自校验（不做静默纠正）

```text
up + down + unknown = visible      （等式一）
up + down           = evaluable    （等式二；evaluable 由程序算，不信模型给的）
```

不满足 ⇒ `CountInconsistency` ⇒ **视同可重试错误**：先重试 `counts_retry_extra` 次（默认 1），
再换备用模型；仍然不自洽 ⇒ 抛 `VISION_PROVIDER_ERROR`，**该窗口不进入统计**。

`evaluable_count` 采用"以 `up+down` 为准"而不是"信模型给的 evaluable"：
即使模型返回矛盾的 `evaluable_count=999`，程序也按 `up+down` 计算比率。

### 2.3 比率全部由程序计算

```text
head_up_rate = head_up_count / evaluable_count     （evaluable=0 ⇒ null，绝不写 0）
coverage     = evaluable_count / visible_count     （visible=0  ⇒ null）
```

### 2.4 兼容层

| 情况 | 处理 |
| --- | --- |
| 模型返回旧三字段 + 旧口径（`headup-prompt-1`） | 接受；`visible = up+down+unknown`；标记 `strict=false` 以便追溯 |
| 模型返回旧三字段 + **严格口径** | 拒绝（视为计数不自洽）⇒ 重试 |
| 旧代码只构造 `FrameReading(head_up=..., ...)` | 自动补齐 `counts`，行为不变 |
| 契约 payload 的 `windows[i]` | 只**新增** `headUpRate`/`coverage`；`validate_windows` 容忍未知字段 ⇒ 冻结样例逐字段一致仍为 `True` |

### 2.5 配置化（无硬编码散落）

| 环境变量 | 默认 | 含义 |
| --- | --- | --- |
| `HEADUP_PROMPT_VERSION` | `headup-strict-v0.2` | 观测口径版本 |
| `HEADUP_STRICT_COUNTS` | `true` | 是否要求自洽计数 |
| `HEADUP_COUNTS_RETRY_EXTRA` | `1` | 计数不自洽时的额外重试次数（0 = 不额外重试，只标记无效） |

---

## 3. 测试结果

### 3.1 自动化测试

```text
python -m unittest discover -s tests -t .
Ran 138 tests in 43.8s
OK
```

Round 1 新增 23 个用例（`tests/test_round1_strict.py`），逐条对应验收要求：

| 验收要求 | 用例 |
| --- | --- |
| 后脑场景 → UP = 0 | `test_back_of_head_frame_yields_zero_up`（up=0/down=0/unknown=12 ⇒ rate=**null**、`LOW_QUALITY`/`NO_LEGIBLE_SUBJECTS`） |
| 完全不可判断 → `head_up_rate = null` | `test_zero_evaluable_gives_null_rate_not_zero`、`test_empty_frame_gives_null_coverage` |
| UNKNOWN 不计入 UP | `test_unknown_never_counted_as_up_or_down` |
| UNKNOWN 不计入 DOWN | 同上（down 只等于模型给出的 `head_down_count`） |
| 计数不一致 → 拒绝结果 | `test_sum_mismatch_is_rejected`、`test_inconsistent_counts_trigger_retry_then_fail_explicitly`、`test_negative_or_non_integer_is_rejected` |
| rate/coverage 由程序计算 | `test_rate_and_coverage_from_counts`、`test_evaluable_is_derived_not_trusted` |
| 兼容层 | `test_legacy_shape_is_accepted_as_compatibility_layer`、`test_legacy_shape_rejected_under_strict_prompt` |
| 提示词硬约束存在 | `PromptContentTest`（3 个用例） |
| 重试有效 | `test_inconsistent_then_consistent_recovers`、`test_counts_retry_extra_zero_means_no_extra_attempt` |

**既有资产未被删除**：5 个交付信封、真实模型样例、合成冻结样例全部保留，
`head-up-success.json` / `head-up-ratio-success.json` 与主项目冻结样例仍**逐字段一致**。

### 3.2 真实模型验证（这是本轮最有价值的部分）

用真实凭据、`qwen3-vl-flash`、同一批 5 个真实画面窗口各调 2 次（共 20 次调用）：

| 指标 | `headup-strict-v0.2` | `headup-prompt-1` | 变化 |
| --- | --- | --- | --- |
| 平均抬头人数 | **6.1** | 7.9 | ↓ 1.8（不再多算） |
| 平均可判断人数 | **8.1** | 10.9 | ↓ 2.8 |
| 平均 UNKNOWN | **3.1** | 1.4 | ↑ 1.7（承认看不清） |
| 平均 coverage | 0.716 | 0.859 | ↓ 0.14 |
| 平均抬头率 | 0.666 | 0.616 | ↑（分母更干净） |
| 耗时 | 1.6–4.3s/帧 | 同量级 | — |

**逐窗证据（背对镜头机位 win-002）**：

| 轮次 | UP | DOWN | UNKNOWN | visible |
| --- | --- | --- | --- | --- |
| 严格口径 ×2 | 4 / 4 | 2 / 2 | 4 / 4 | 10 / 10 |
| 旧口径 ×2 | 6 / 6 | 5 / 7 | 3 / 2 | 14 / 15 |

读法：严格口径在同一个"多人背对镜头"的画面上**少算了 2 个抬头**、多认了 UNKNOWN，
但**并没有降到 0**——说明该画面里确实有一部分可判断的人（模型在别的窗口给出 18 人可判断，
这里只给 10 人，行为是"更保守"而不是"全判不可见"）。

真实端到端（HTTP 契约路径 + 严格口径 + flash，50 秒 5 窗）：`SUCCEEDED`、**8.1 秒**、
5 窗全 `VALID`、均值 **0.6683**、每窗新增 `headUpRate`/`coverage` 字段。

---

## 4. 遗留问题

| # | 问题 | 影响 | 计划 |
| --- | --- | --- | --- |
| 1 | **严格口径下 win-002 仍报 4 个 UP** | 无法排除"把背对镜头的人算成抬头"，本机无该帧人工标注 | Round 5（Ground Truth Benchmark）定量判定 |
| 2 | 旧口径的模型回复在严格口径下会被判"不自洽" | 若某模型不遵循新 schema，会连续重试后失败（`VISION_PROVIDER_ERROR`，可重试） | 已在文档说明；Round 4 固化模型/口径版本 |
| 3 | `quality` 仍是二档 `VALID`/`LOW_QUALITY` | 阈值 0.60 附近的抖动问题**尚未解决**（本轮明确不实现三档质量） | Round 2 |
| 4 | 课程均值仍按时长加权（`stats-v1`） | 与 Round 2 要求的 `SUM(up)/SUM(evaluable)` **冲突**，需要显式升版 | Round 2 处理，见下 |
| 5 | 逐窗 counts 只在诊断记录里，未落库 | 会话内可复现，跨进程不可查 | Round 4（版本固化）/ Round 6（DTO 字段） |
| 6 | 抖动未量化（±1 人量级） | 报告不能声称精确 | Round 4（重复运行实验 20 次） |

### 4.1 需要你确认的冲突（Round 2 前置）

Round 2 要求课程均值改为：

```text
SUM(head_up_count) / SUM(evaluable_count)
```

但已冻结的契约 `stats-v1` 与主项目 `expected-statistics.json` 用的是**时长加权**
`Σ(r_i·d_i)/Σd_i`，且主项目文档明确写了"**不使用** `ΣheadUpCount/ΣvalidObservedCount`"。
两者在**窗口等长时数值相同**（演示 fixture 均为 10s，故 0.4625 不变），但真实课堂窗口不等长时会分叉。

处理建议（待你确认，Round 2 执行）：
升版为 `stats-v2`，同时保留 `stats-v1` 的时长加权值作为对照字段，冻结样例不动、
另出一份 stats-v2 样例；并在交接表里把这次冲突与选择记录下来。

---

## 5. 验收标准核对

| 验收标准 | 结果 |
| --- | --- |
| 后脑 / 严重遮挡画面不再出现猜测性 UP | **部分达成**：真实 A/B 显示 UP 由 7.9 → 6.1、UNKNOWN 由 1.4 → 3.1、背对镜头窗少算 2 人；但该窗仍报 4 个 UP，**无法在无人工标注下判定是否已消除** |
| `head_up_rate` 与 `coverage` 均由代码计算 | ✅ 达成（模型只给计数；`evaluable` 也由程序算；payload 与诊断记录都带这两个字段） |
| UNKNOWN 成为正式合法状态 | ✅ 达成（既不在分子也不在分母；`evaluable=0 ⇒ rate=null`；`coverage` 独立表达可观测性） |
| 全部测试通过 | ✅ 138 个用例全绿，冻结样例逐字段一致仍为 `True` |

**结论：本轮验收通过（"不再出现猜测性 UP"一项为部分达成，已如实记录并把定量判定交给 Round 5）。**

本轮未实现（按你的要求）：三档质量、三帧复核、时间平滑、Flash/Plus 切换、真实课堂 Benchmark。
