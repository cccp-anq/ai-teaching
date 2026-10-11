# FRAME-3 实施报告 · 采样接入 + 视觉层独立

日期：2026-09-15。范围：**FRAME-3**（把 SamplingPolicy 接进流水线、把视觉层独立成 `vision/`）。
本轮**不含** FRAME-4 的三帧多图调用（review 仍是三次单图，已明确列为遗留）。

---

## 1. 修改文件

| 文件 | 内容 |
| --- | --- |
| `src/headup/vision/__init__.py` | **新增**：视觉层入口（只处理图片，不知道"视频"） |
| `src/headup/vision/schema.py` | **新增**：`FrameReading`（自洽计数 + 程序算 rate/coverage + 追溯 + `frameCount`/`perFrame` 预留）、`FrameAnalyzer` 协议 |
| `src/headup/vision/qwen_client.py` | **新增**：`QwenVisionClient`（= `QwenVlAnalyzer` 正式名）、图像预处理（`prepare_image`/`tiled_boxes`/`crop_to_data_url`）、严格计数解析与重试 |
| `src/headup/vision/validator.py` | **新增**：`ObservationValidator`（计数自洽/口径/可读性三类判定，**不做法质分档**） |
| `src/headup/analyze.py` | **改为兼容外壳**（108 行）：保留全部旧导入名，降级实现（`UnavailableAnalyzer`/`HeuristicAnalyzer`/`build_analyzer`）留在此处 |
| `src/headup/video/sampling.py` | 新增 `plan_frame_plans()`：把 SamplingPolicy 网格翻译成窗口计划 |
| `src/headup/config.py` | 新增 `sampling_start_offset_ms` / `sampling_end_offset_ms`（默认 0）/ `sampling_use_batch_extraction`（默认 false） |
| `src/headup/pipeline.py` | **采样改为经 SamplingPolicy**；offset 只过滤；`payload.generation` 增加 `samplingPlan` 与 `samplingPolicyVersion`；空采样点显式报错 |
| `tests/test_frame3_sampling_vision.py` | **新增** 18 个用例 |

**没有删除任何测试或冻结样例**；`analyze.py` 的旧 API 全部保留（`FrameReading` 是同一个类、`QwenVisionClient is QwenVlAnalyzer`）。

---

## 2. 关键设计

### 2.1 网格唯一来源落到流水线里（决策①）

```python
policy = SamplingPolicy(window_ms=request.parameters.window_ms,
                        position=cfg.sample_position,
                        start_offset_ms=cfg.sampling_start_offset_ms,
                        end_offset_ms=cfg.sampling_end_offset_ms)
sampling_plan = build_sampling_plan(policy, ...)      # 网格 + offset 过滤
plans = plan_frame_plans(policy, ...)                 # 同一网格 → 窗口计划
plans = [p for p in plans if p.window_id in kept_ids] # offset 只过滤
```

`test_plan_frame_plans_equals_legacy_plan_frames` 对 5 组参数（含 short-last-window 与
start/middle/end 三种位置）**逐字段比对**新旧实现，证明这次替换是纯重构。

### 2.2 视觉层不再知道"视频"（plan §3/§15）

```text
Controller → HeadUpAnalysisService → VideoFrameExtractor → QwenVisionClient
                     (pipeline)          (video/)              (vision/)
```

- `QwenVisionClient.analyze(image_path)` 只接受**图片路径**；
  `test_vision_client_only_accepts_image_paths` 用签名钉住；
- `test_vision_module_does_not_import_video_layer` 直接用源码检查断言
  **`vision/` 不 import `video/`、不出现 ffmpeg**——职责边界可执行地守住；
- 图像预处理（缩放/分块/data URL）随客户端进 `vision/`，因为它是"送模前的准备"而不是"视频处理"。

### 2.3 观测校验层（plan §12 的 `HeadUpObservationValidator`）

只回答"这次观测能不能用"，**不判 VALID/REVIEW/LOW_QUALITY**（那是 `analysis/quality` 的活）：

| 校验 | 结果 |
| --- | --- |
| 计数不自洽 / 负数 / 缺字段 | 抛 `ObservationInvalid`，不进统计 |
| 严格口径下返回旧版三字段 | 抛 `ObservationInvalid`（可配置放宽，供历史结果复现） |
| `visible_count == 0`（画面无人） | `NO_VISIBLE_PERSON` —— **不等于**"不可判读" |
| `visible > 0, evaluable == 0`（有人但都看不清） | `UNREADABLE_ALL_SUBJECTS` |
| 否则 | `OK`（可读，`unknownRatio` 一并给出） |

`is_statistically_usable()` 明确"必须可读且严格计数"——把"谁能进统计"的判断从散落的条件里收拢。

### 2.4 结果里能追溯"这些时间点是怎么来的"

`payload.generation` 新增：

```json
{
  "samplingPlan": {
    "policyVersion": "10s-window-midpoint-v0.2",
    "windowMs": 10000, "position": "middle", "sampleCount": 5,
    "filter": {"startOffsetMs": 0, "endOffsetMs": 0, "droppedCount": 0, "filteredOut": []},
    "points": [{"index": 0, "windowId": "win-001", "windowStartMs": 0,
                "windowEndMs": 10000, "sampleAtMs": 5000}, …]
  },
  "samplingPolicyVersion": "10s-window-midpoint-v0.2"
}
```

启用非零 offset 时版本自动变成 `…-v0.2+offset-10000-4000`（决策②的升版要求）。
offset 过滤到没有采样点时**显式报 `PROCESSING_FAILED`**，不静默产出空结果。

---

## 3. 测试结果

```text
python -m unittest discover -s tests -t .
Ran 247 tests in 58.2s
OK
```

本轮新增 18 个用例：

| 分组 | 覆盖 |
| --- | --- |
| 网格唯一来源 | 新旧实现逐字段等价（5 组参数）、offset 过滤不重排、默认 offset 为 0 |
| 视觉层独立 | 同类断言、`QwenVisionClient` 别名、签名只收图片、**不 import video/ffmpeg**、旧入口可用 |
| 观测校验 | 可读 / 画面无人 / 全不可判读三态、严格口径拒绝旧字段、可配置放宽、统计可用性 |
| 流水线接入 | `samplingPlan` 进结果、offset 减窗且 id 不重排、空采样点显式报错 |

### 3.1 真实模型端到端（重构不改行为）

| 指标 | FRAME-3 前（Round 4） | FRAME-3 后 | 结论 |
| --- | --- | --- | --- |
| 均值 | 0.7714 = 27/35 | **0.7714 = 27/35** | 一致 |
| 质量分布 | 4 VALID + 1 LOW_QUALITY | 4 VALID + 1 LOW_QUALITY | 一致 |
| 端到端耗时 | 16.2s | 16.2s | 一致 |
| `samplingPolicyVersion` | — | `10s-window-midpoint-v0.2` | 新增可追溯 |
| 采样点 | — | `[5000, 15000, 25000, 35000, 45000]` | 与冻结语义一致 |

---

## 4. 遗留问题

| # | 问题 | 计划 |
| --- | --- | --- |
| 1 | **review 仍是三次单图调用**，未实现 `analyzeWindow([三帧])` 一次多图请求（决策③） | **FRAME-4**（本轮明确不含） |
| 2 | `SamplingPolicy` 尚未驱动机器抽帧（pipeline 仍逐窗 `extract_frame`，批量路径只在 `video/` 里可用且默认关闭） | FRAME-4 一并切，避免两套抽帧路径并存过久 |
| 3 | `sampling_use_batch_extraction` 配置已加但未接线 | 同上 |
| 4 | `analysis/` 包未建（`quality.py` 仍在 pipeline 里） | FRAME-4 与 review 改造一起做 |
| 5 | `frames.py` 的 `plan_frames` 仍被 CLI/测试使用（保留兼容），未来应统一到 SamplingPolicy | FRAME-5/6 清理 |
| 6 | workspace 生命周期、任务状态机、`head_up_sample` 表 | **FRAME-5** |
| 7 | 真实 45 分钟视频未到，性能/成本仍是外推 | 等用户提供 |

---

## 5. 验收标准核对

| 验收标准（plan §12/§15 + 用户决策） | 结果 |
| --- | --- |
| 把长视频 URL 主路径替换为 FrameSample → 本地 JPG → Qwen | ✅ 本模块本来就是本地抽帧；本轮把采样与视觉层的边界正式固化 |
| 程序端继续计算 `head_up_rate` / `coverage`，保持严格口径 | ✅ 迁移后真实端到端数值完全不变（0.7714） |
| **`QwenVisionClient` 不应知道"视频"是什么** | ✅ 签名只收图片路径；源码检查断言不 import video/ffmpeg |
| **采样网格只由 SamplingPolicy 生成** | ✅ 流水线改用 `plan_frame_plans`，并与旧实现逐字段等价 |
| **offset 只过滤、不平移、默认 0、启用时升版** | ✅ 用例钉住 id 不重排与版本后缀 |
| 不破坏既有口径与测试 | ✅ 247 个用例全绿；冻结样例逐字段一致未受影响 |

**FRAME-3 验收通过。** 等指示继续 **FRAME-4**：把 review 改成 `analyzeWindow([t-500ms, t, t+500ms])`
的**单次多图请求**（决策③），并把质量判定搬到 `analysis/quality.py`、让 SamplingPolicy 驱动实际抽帧。
