# FRAME-4 实施报告 · 一次多图复核 + 质量层独立

日期：2026-09-15。范围：**FRAME-4**。对应你的决策③（Review = 一次多模态请求带三张图）。

---

## 1. 修改文件

| 文件 | 内容 |
| --- | --- |
| `src/headup/prompts.py` | 新增 `headup-strict-review-multi-v0.2` 提示词：三图 = **同一个观察窗口**，输出**一组**计数；明确"不是每帧各出一组""禁止用前后帧推断看不清的人" |
| `src/headup/vision/qwen_client.py` | `_chat()` 支持多图；新增 **`analyze_window(image_paths)`**（一次请求带多张图，返回一个观测，`frameCount=N`）+ `analyze_frame` 别名 |
| `src/headup/analysis/quality.py` | **新增（从 pipeline 迁出）**：三档质量、复核触发、复核结论；新增 `finalize_review_observation()`（多图观测直接判级，不做多帧中位数） |
| `src/headup/pipeline.py` | 新增 `ReviewObservation`、`_review_observation()`（多图优先 + 逐帧回退）、`_review_frames()` 兼容壳；payload 的 `review` 增加 `strategy`/`frameCount`/`observation`；质量符号改为 re-import（零重复实现） |
| `src/headup/config.py` | 新增 `review_multi_image`（默认 true） |
| `tests/test_frame4_multi_image.py` | **新增** 12 个用例 |
| `tests/test_round3_review.py` | 脚本化分析器实现 `analyze_window()`；3 个调用量断言改为"按请求数"口径（**未删除任何用例**） |

---

## 2. 关键设计

### 2.1 一次多图请求（决策③）

```python
analyzer.analyze_window([t-500ms, t, t+500ms])   # 1 次请求 / 3 张图 / 1 个窗口观测
```

- 提示词把三张图定义为**同一个观察窗口**，要求输出**一组**计数（`visible/up/down/unknown`）；
  并额外禁止"因为前后帧看起来在抬头就把某一帧看不清的人推断成抬头"；
- 返回的 `FrameReading.frameCount = 3`，结果层据此标注
  `resultKind=WINDOW_ESTIMATE_MULTI_FRAME`、`review.strategy=MULTI_IMAGE_SINGLE_REQUEST`；
- 计数仍走同一套自校验（`up+down+unknown=visible`），**比率仍由程序计算**。

**回退路径**（`review_multi_image=false` 或分析器没有 `analyze_window`）：逐帧单图调用 + `aggregate_counts`，
证据里标 `strategy=THREE_SINGLE_FRAME` 并附逐帧计数——两种语义在结果里**可区分**，不会互相冒充。

### 2.2 质量层独立（plan §12）

```text
video/ (探测·抽帧·采样)  →  vision/ (只看图片)  →  analysis/quality (判级·复核)  →  pipeline (编排·统计)
```

`classify_window` / `needs_review` / `finalize_review*` 现在都在 `headup.analysis.quality`，
`pipeline` 通过 re-import 保留同名符号（`pipeline.classify_window is quality.classify_window` 有用例断言）。

### 2.3 多图观测直接判级（不再做多帧中位数）

```python
finalize_review_observation(counts, valid_threshold, review_threshold, frame_count=3)
# coverage >= valid 阈值 ⇒ VALID（REVIEW_CONFIRMED_VALID）
# 否则              ⇒ LOW_QUALITY（REVIEW_INSUFFICIENT_EVIDENCE，两数 null）
```

原来的 `finalize_review()`（多帧中位数）保留，专用于逐帧单图回退路径——两条路径语义不同，**不混用**。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 259 tests in 66.7s
OK
```

本轮新增 12 个用例：

| 分组 | 覆盖 |
| --- | --- |
| 提示词与接口 | 复核提示词要求"一组计数"、已注册且属严格口径、`analyze_window` 签名、空列表拒绝 |
| 观测判级 | coverage 足够 ⇒ VALID；仍不足 ⇒ LOW_QUALITY；**多图能把 coverage 抬过阈值**；pipeline 与 quality 同一函数对象 |
| 端到端路径 | 多图是默认路径且一次带 3 张不同时间点的图；无 `analyze_window` 时回退逐帧并标注策略；可配置关闭；**复核请求数 1 vs 3** |

### 3.1 真实模型验证（决策③第一次真跑）

50 秒 / 5 窗 / flash / 严格口径：

| 窗口 | 质量 | 复核策略 | 抬头/分母 | coverage | 原因 |
| --- | --- | --- | --- | --- | --- |
| win-001 | VALID | — | 4/6 | 0.750 | — |
| win-002 | LOW_QUALITY | `MULTI_IMAGE_SINGLE_REQUEST` ×3 | — | 0.375 | `REVIEW_INSUFFICIENT_EVIDENCE` |
| win-003 | VALID | `MULTI_IMAGE_SINGLE_REQUEST` ×3 | 12/14 | 0.824 | `REVIEW_CONFIRMED_VALID` |
| win-004 | LOW_QUALITY | `MULTI_IMAGE_SINGLE_REQUEST` ×3 | — | 0.600 | `REVIEW_INSUFFICIENT_EVIDENCE` |
| win-005 | VALID | `MULTI_IMAGE_SINGLE_REQUEST` ×3 | 0/3 | 0.750 | `REVIEW_CONFIRMED_VALID` |

- **4 个复核窗口全部走多图**（`MULTI_FRAME_REVIEW_APPLIED:4`），没有一次是"三图各调三次"；
- 均值 0.6957 = 16/23；质量分布 3 VALID + 2 LOW_QUALITY；
- 端到端 **14.1s**（比逐帧路径的 16.2s 略快——复核请求数从 3 降到 1）。

> ⚠️ **与上一版数值不同（0.7714 → 0.6957）是预期行为，但不是"变准了"的证据**：
> （a）多图合并口径更严（win-002 coverage 0.6 → 0.375），说明模型在合并观察时更保守；
> （b）win-005 从 1/3 变成 0/3；
> （c）**本机没有人工标注，无法判断哪种更接近真实**。定量结论要等 Ground Truth Benchmark。

---

## 4. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | **多图 vs 逐帧谁更准仍是未解**（只知两者数值不同） | FRAME-5/6：Benchmark 同时跑两种策略（plan §23 FRAME-6 的对比对象含"Flash 三帧复核"） |
| 2 | 多图请求的 token 用量未单独测（一张图 ≈303 tokens，三张会更多） | FRAME-6 记录成本 |
| 3 | `SamplingPolicy` 仍未驱动实际抽帧（pipeline 逐窗 `extract_frame`，批量开关未接线） | **FRAME-5**（与 workspace 一起） |
| 4 | `analysis/` 只有 `quality.py`；`review.py`/`aggregator.py` 未拆 | 可按需拆；当前 review 逻辑已在 quality.py 内聚 |
| 5 | workspace 生命周期、任务状态机、`head_up_sample` 表 | FRAME-5 |
| 6 | `frames.py` 的 `plan_frames` 仍被 CLI/测试使用（兼容保留） | FRAME-5/6 清理 |
| 7 | 真实 45 分钟视频未到 | 等用户提供 |

---

## 5. 验收标准核对

| 验收标准（决策③ + plan §13/§14/§23 FRAME-4） | 结果 |
| --- | --- |
| REVIEW 窗口抽 `t-500ms / t / t+500ms` 并调用 `analyzeWindow(frames)` | ✅ 真实数据上 4/4 复核窗口走多图，一次带三张不同时间点的图 |
| **禁止"三张图分别调用三次再平均"作为默认路径** | ✅ 默认多图；逐帧路径只在显式关闭或分析器不支持时启用，且策略在证据里可区分 |
| 三张图共同产生**一个** centerTimestamp 的观测 | ✅ `frameCount=3`、`finalize_review_observation` 直接判级 |
| 三帧仍无证据 ⇒ LOW_QUALITY | ✅ win-002 / win-004 实测降级为 `REVIEW_INSUFFICIENT_EVIDENCE` |
| 相邻帧提供足够证据 ⇒ VALID | ✅ win-003 / win-005 实测 `REVIEW_CONFIRMED_VALID` |
| 质量层独立、旧导入名保留 | ✅ `analysis/quality.py`；`pipeline.classify_window` 同一对象 |
| 不破坏既有口径与测试 | ✅ 259 个用例全绿；冻结样例逐字段一致未受影响 |

**FRAME-4 验收通过。** 等指示继续 **FRAME-5**（Workspace 生命周期 + 任务状态机 + `head_up_sample` 落库 +
让 SamplingPolicy 真正驱动抽帧）。
