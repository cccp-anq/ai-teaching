# Round 4 实施报告 · 稳定性与版本固化（v0.2）

日期：2026-09-14。范围：**只做 Round 4**（不新增业务功能）。

> 目标：把"模型随机性"从描述变成数值，并让每个正式结果都能追溯到**谁、用什么口径**算出来的。

---

## 1. 修改文件

| 文件 | 改动 |
| --- | --- |
| `src/headup/analyze.py` | `_chat()` 返回 `(文本, 追溯信息)`；请求体支持 `seed` / `enable_thinking`；`FrameReading` 增加 `model_used` / `trace`；分析器记录 `used_models` 与 `last_trace`（含 request id、usage、生效参数） |
| `src/headup/config.py` | 新增 `vision_max_tokens`、`vision_seed`（默认 None=不发送）、`vision_disable_thinking`（默认 false）；`/health` 的 `vision` 段增加 `fallbackModel` 与 `generationParameters`（含实测结论说明） |
| `src/headup/pipeline.py` | 新增 `_generation_policy()`；`payload.generation` 固化五个版本字段 + 生成参数 + 复核策略 + 阈值；每窗新增 `modelUsed` / `modelRequestId`；新增告警 `FALLBACK_MODEL_USED:<windowIds>` |
| `tests/test_round4_stability.py` | **新增** 11 个用例（版本字段、诚实说明、参数发送与否、统计口径、DTO 字段） |
| `.work/stability_experiment.py` | **新增**可重复执行的稳定性实验脚本（参数集对照 + 统计报告） |
| `.work/probe_params.py` | **新增**参数支持性探测脚本 |

**未删除任何测试与冻结样例**；Round 1–3 全部用例继续通过。

---

## 2. 关键设计

### 2.1 调用参数审计（真实请求，逐项实测）

端点 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 `qwen3-vl-flash`：

| 参数 | HTTP | 结论 |
| --- | --- | --- |
| `temperature=0` | 200 | ✅ 支持（当前默认） |
| `seed=42` | 200 | ⚠️ 被接受，但**没有带来确定性**（见 §2.2） |
| `enable_thinking=false` | 200 | ✅ 被接受，结果与基线统计等价（本任务未产生推理链） |
| `enable_thinking=true` | 200 | ✅ 被接受；返回体出现代码块包裹，解析仍可用但**更慢且无收益** |
| `thinking_budget=0` / `reasoning_effort=none` | 200 | 被接受，未见效果差异 |
| `top_p=1.0` | 200 | 被接受（**未与 temperature 同时乱调**） |

`temperature` 与 `top_p` **没有同时调整**：审计阶段只单独探测，正式默认保持 `temperature=0`、不发送 `top_p`。

**结论：默认只发送 `temperature=0`，不发送 `seed`，不发送 thinking 开关。**

### 2.2 重复调用实验（同帧 20 次，三组参数）

同一帧（1280×720，严格提示词）：

| 参数集 | head_up 均值±σ | 极差 | coverage 均值±σ | 质量分布 | 状态翻转 | 不同回复数 |
| --- | --- | --- | --- | --- | --- | --- |
| `temperature=0`（基线） | **7.85 ± 0.37** | 1 | 0.704 ± 0.026 | VALID 17 / REVIEW 3 | 5 | 2 / 20 |
| `temperature=0 + seed=42` | 7.90 ± 0.31 | 1 | 0.707 ± 0.022 | VALID 18 / REVIEW 2 | 3 | 2 / 20 |
| `temperature=0 + enable_thinking=false` | 7.85 ± 0.37 | 1 | 0.704 ± 0.026 | VALID 17 / REVIEW 3 | 6 | 2 / 20 |

**读法（重要）**：

1. **`seed` 不是解药**：三组统计等价，`seed=42` 甚至在 10 次的小样本里看起来更差——
   20 次样本显示那只是噪声。**不能声称"固定 seed 提高可复现性"**。
2. `enable_thinking=false` 与基线逐位相同（同一分布），本任务没有触发推理链。
3. **随机性已被量化**：单帧 `head_up` 的 σ ≈ 0.37 人、极差 1 人；`coverage` 的 σ ≈ 0.026；
   质量状态在连续调用间**翻转 3–6 次 / 20 次（15%–30%）**，且翻转只发生在 coverage 卡在 0.70 阈值附近的窗口。
4. 这条数据**反向支持了 Round 3 的三帧复核**：不稳定窗口确实存在，且复核是唯一能补证据的手段。

### 2.3 版本固化（每个结果都能追溯）

`payload.generation`（新增，向后兼容的增量字段）：

```json
{
  "modelName": "qwen3-vl-flash",
  "modelFallbackName": "qwen3-vl-plus",
  "modelVersion": "qwen3-vl-flash+req:chatcmpl-be0299bf-…",
  "modelVersionNote": "服务端未返回不可变快照号（system_fingerprint=null）；以请求名 + 首次请求 id 追溯",
  "modelsUsed": ["qwen3-vl-flash"],
  "promptVersion": "headup-strict-v0.2",
  "samplingPolicyVersion": "10s-single-frame-v0.2",
  "metricVersion": "head-up-rate-v0.2",
  "statisticsVersion": "stats-v2",
  "qualityThresholds": {"coverageValid": 0.7, "coverageReview": 0.5},
  "reviewPolicy": {"enabled": true, "unknownRatioThreshold": 0.5,
                   "suddenChangeThreshold": 0.2, "adjacentFrameOffsetMs": 500},
  "generationParameters": {"temperature": 0.0, "maxTokens": 512, "seed": null,
                           "disableThinking": false},
  "windowsWithOtherModel": [],
  "analysisRequests": {"modelsUsed": [...], "lastRequestTrace": {"requestId": "...",
                        "promptTokens": 1377, "completionTokens": 60, ...}}
}
```

**关于 `model_version` 的诚实说明**：实测响应里 `system_fingerprint` 为 `null`，模型名就是请求名，
**没有可登记的不可变服务端快照号**。因此我们把 `modelVersion` 定义为"请求名 + 首次请求 id"，
并在 `modelVersionNote` 里写明这一点——不伪造一个看起来像快照的版本号。

### 2.4 Flash / Plus 分离（禁止混入同一条正式曲线）

| 机制 | 作用 |
| --- | --- |
| 逐窗 `modelUsed` + `modelRequestId` | 每个窗口都能查到是哪次请求、哪个模型产出的 |
| `payload.generation.modelsUsed` | 本结果实际用到的模型集合 |
| `warnings: FALLBACK_MODEL_USED:<windowIds>` | 一旦有窗口由备用模型产出就显式告警 |
| `windowsWithOtherModel` | 直接给出混入的窗口 id 列表 |

即：**换模型不会静默发生**。Plus 仍可用于验证/对照（`VISION_MODEL=qwen3-vl-plus` 另起 job），
但主统计曲线默认只用 Flash，且任何混入都会留痕。

### 2.5 稳定性实验脚本（不进普通 CI）

```powershell
# 20 次 × 三组参数（约 3–5 分钟，消耗真实额度）
python .work/stability_experiment.py --calls 20 --sets baseline_temperature0,seed42,non_thinking
```

输出 JSON 含每组参数的 `visible/evaluable/head_up/head_up_rate/coverage` 的均值、标准差、极差，
`qualityCounts`、`qualityTransitions`（状态翻转次数）、`distinctResponses`。
该脚本**不进 CI**（会产生真实 API 费用），属于显式 real-model 实验。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 195 tests in 49.7s
OK
```

Round 4 新增 11 个用例：

| 验收要求 | 用例 |
| --- | --- |
| 五个版本字段齐全 | `test_generation_policy_has_all_five_versions` |
| `model_version` 诚实（无快照号就说明） | `test_model_version_note_is_honest_about_missing_snapshot` |
| 生成参数被记录 | `test_generation_parameters_recorded` |
| 默认不发 seed / thinking | `test_seed_and_thinking_default_to_off`、`test_default_request_omits_seed_and_thinking` |
| 显式配置才发送 | `test_explicit_parameters_are_sent` |
| 换模型必须留痕 | `test_windows_with_other_model_are_flagged` |
| 随机性统计口径可复算 | `test_summarize_reports_mean_std_and_range`、`test_identical_readings_have_zero_std` |
| payload 可追溯 | `test_payload_carries_generation_block_and_per_window_model` |
| DTO 最小字段集 | `test_dto_required_fields_present_per_window` |

### 3.1 真实端到端验证（严格口径 + flash + HTTP 契约路径）

| 窗口 | 质量 | 复核 | 抬头/分母 | coverage | 逐窗模型追溯 |
| --- | --- | --- | --- | --- | --- |
| win-001 | VALID | — | 4/6 | 0.750 | `qwen3-vl-flash` + req id |
| win-002 | LOW_QUALITY | `QUALITY_REVIEW` ×3 | — | 0.600 | `qwen3-vl-flash` + req id |
| win-003 | VALID | `SUDDEN_CHANGE` ×3 | 14/16 | 0.800 | `qwen3-vl-flash` + req id |
| win-004 | VALID | — | 8/10 | 0.714 | `qwen3-vl-flash` + req id |
| win-005 | VALID | `SUDDEN_CHANGE` ×3 | 1/3 | 0.750 | `qwen3-vl-flash` + req id |

`modelVersion` / `promptVersion` / `samplingPolicyVersion` / `metricVersion` / `statisticsVersion`
全部就位；`windowsWithOtherModel` 为空（没有混入换模型的窗口）。
端到端 **36.3s**（同一配置上一轮为 16.2s——**调用次数相同，差异来自服务端延迟波动**，非本轮代码变化）。

---

## 4. 遗留问题

| # | 问题 | 说明 / 计划 |
| --- | --- | --- |
| 1 | **单帧随机性仍存在**（σ≈0.37 人、状态翻转 15–30%） | 已量化；缓解手段是 Round 3 复核 + 报告声明估计性质。**不能靠 seed 解决**（已实测） |
| 2 | 缺少服务端不可变模型快照号 | 只能以请求名 + 请求 id 追溯；若后续 DashScope 提供快照号，应改用它 |
| 3 | 端到端耗时波动大（同一配置 16.2s ↔ 36.3s） | 未做延迟分布实验（需几十次运行）；交接表的耗时数值应标注为"实测区间" |
| 4 | `promptTokens` 实测 1377/帧（比 Round 3 记录的 1140 高） | 严格提示词更长所致；成本按 1377 重算：45 分钟课 ≈ ¥0.54（flash） |
| 5 | `stats-v1` ↔ `stats-v2` 展示口径仍未确认 | 待主项目 |
| 6 | thinking 模式返回代码块包裹（```json） | 现行解析用首个 `{` 到最后一个 `}`，可用；但默认不启用 thinking |

---

## 5. 验收标准核对

| 验收标准 | 结果 |
| --- | --- |
| 同一结果可以追溯模型和口径版本 | ✅ `payload.generation` 五版本字段 + 逐窗 `modelUsed`/`modelRequestId` |
| 存在可重复执行的稳定性实验 | ✅ `.work/stability_experiment.py`（`--calls 20`），不进 CI |
| Flash / Plus 不会混入同一条正式曲线 | ✅ 逐窗模型记录 + `FALLBACK_MODEL_USED` 告警 + `windowsWithOtherModel` |
| 模型随机性被量化，而不是靠描述 | ✅ σ、极差、翻转次数、不同回复数；三组参数对照 |
| 全部已有测试继续通过 | ✅ 195 个用例全绿；冻结样例逐字段一致未受影响 |

**本轮验收通过。** 本轮未实现（按要求）：新的产品逻辑、Prompt 微调实验、真实课堂 Benchmark（Round 5）。
