"""交付 fixture 自校验（仅标准库，可脱离主项目仓库运行）。

它检查 `docs/fixtures/head-up-module-v0.1/` 下的样例是否满足契约 §4 + 抬头专用文档 §3：

- 信封字段、`timeBase=MEDIA_START_MS`、`revision` 与 `status` 对应关系；
- `processedRanges` 有序不重叠且落在 `analysisRange` 内，部分处理必须带 `PARTIAL_PROCESSING`；
- `windows` 有序不重叠、恰好覆盖 `processedRanges`、`sampleAtMs` 落在窗口内；
- 三态质量语义：VALID（分母>0、0<=分子<=分母）/ NO_SUBJECTS（固定 0/0）/ LOW_QUALITY（两数 null + 原因）；
- COUNTS 模式 `headUpRatio` 必须 null；RATIO_ONLY 模式人数必须 null 且比例在 [0,1]；
- `head-up-real-success.json` 的 `statistics` 与 `head-up-real-stats.json` 一致；
- `http-run-record.json` 记录的 202/200 与 jobId 复用结论。

它不是完整 JSON Schema 校验器，也不代表真实模型精度验收。运行：

    python docs/fixtures/head-up-module-v0.1/verify_head_up_fixtures.py
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FAILURES: list[str] = []
CHECKED = 0


def check(ok: bool, message: str) -> None:
    if not ok:
        FAILURES.append(message)


def is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def union_size(spans: list[tuple[int, int]]) -> int:
    total, end = 0, -1
    for a, b in sorted(spans):
        total += max(0, b - max(a, end))
        end = max(end, b)
    return total


def overlap(a: tuple[int, int], b: tuple[int, int]) -> int:
    return max(0, min(a[1], b[1]) - max(a[0], b[0]))


def verify_envelope(name: str, env: dict) -> dict:
    global CHECKED
    CHECKED += 1
    for field in ("schemaVersion", "clientRequestId", "jobId", "moduleType", "providerId",
                  "moduleVersion", "classSessionId", "mediaId", "mediaSha256", "durationMs",
                  "timeBase", "analysisRange", "status", "revision", "createdAt", "updatedAt",
                  "payload", "error"):
        check(field in env, f"{name}: 缺少 {field}")
    check(env.get("schemaVersion") == "1.0", f"{name}: schemaVersion 必须是 1.0")
    check(env.get("moduleType") == "HEAD_UP", f"{name}: moduleType 必须是 HEAD_UP")
    check(env.get("timeBase") == "MEDIA_START_MS", f"{name}: timeBase 必须是 MEDIA_START_MS")
    sha = env.get("mediaSha256", "")
    check(isinstance(sha, str) and len(sha) == 64 and all(c in "0123456789abcdef" for c in sha),
          f"{name}: mediaSha256 必须是 64 位小写十六进制")
    duration = env.get("durationMs")
    check(is_int(duration) and duration > 0, f"{name}: durationMs 必须是正整数")
    for field in ("createdAt", "updatedAt"):
        value = env.get(field, "")
        check(isinstance(value, str) and value.endswith("Z"), f"{name}: {field} 必须是 UTC Z")
    rng = env.get("analysisRange") or {}
    a, b = rng.get("startMs"), rng.get("endMs")
    check(is_int(a) and is_int(b) and isinstance(duration, int) and 0 <= a < b <= duration,
          f"{name}: analysisRange 非法")
    status = env.get("status")
    check(status in ("QUEUED", "RUNNING", "SUCCEEDED", "FAILED"), f"{name}: status 非法")
    expected_revision = 1 if status in ("SUCCEEDED", "FAILED") else 0
    check(env.get("revision") == expected_revision, f"{name}: revision 与 status 不匹配")

    if status != "SUCCEEDED":
        check(env.get("payload") is None, f"{name}: 非 SUCCEEDED 的 payload 必须为 null")
        if status == "FAILED":
            error = env.get("error") or {}
            for field in ("code", "message", "retryable"):
                check(field in error, f"{name}: FAILED 的 error 缺少 {field}")
            check(isinstance(error.get("retryable"), bool), f"{name}: retryable 必须是布尔")
        else:
            check(env.get("error") is None, f"{name}: 非 FAILED 的 error 必须为 null")
        return {"bounds": (a, b), "value_mode": None}

    check(env.get("error") is None, f"{name}: SUCCEEDED 的 error 必须为 null")
    payload = env.get("payload") or {}
    for field in ("definitionId", "denominatorDefinition", "sampleMode", "valueMode",
                  "windowMs", "processedRanges", "windows", "warnings"):
        check(field in payload, f"{name}: payload 缺少 {field}")
    check(bool(payload.get("definitionId")), f"{name}: definitionId 不能为空")
    check(bool(payload.get("denominatorDefinition")), f"{name}: denominatorDefinition 不能为空")
    check(payload.get("sampleMode") == "REPRESENTATIVE_FRAME",
          f"{name}: sampleMode 必须是 REPRESENTATIVE_FRAME")
    value_mode = payload.get("valueMode")
    check(value_mode in ("COUNTS", "RATIO_ONLY"), f"{name}: valueMode 非法")
    window_ms = payload.get("windowMs")
    check(is_int(window_ms) and window_ms > 0, f"{name}: windowMs 非法")
    check(isinstance(payload.get("warnings"), list)
          and all(isinstance(w, str) for w in payload["warnings"]),
          f"{name}: warnings 必须是字符串数组")

    processed: list[tuple[int, int]] = []
    for item in payload.get("processedRanges") or []:
        pa, pb = item.get("startMs"), item.get("endMs")
        check(is_int(pa) and is_int(pb) and a <= pa < pb <= b,
              f"{name}: processedRanges 区间非法或在 analysisRange 外")
        processed.append((pa, pb))
    check(bool(processed), f"{name}: processedRanges 不能为空")
    check(processed == sorted(processed), f"{name}: processedRanges 必须有序")
    check(all(x[1] <= y[0] for x, y in zip(processed, processed[1:])),
          f"{name}: processedRanges 不得重叠")
    if union_size(processed) < (b - a):
        check("PARTIAL_PROCESSING" in payload["warnings"],
              f"{name}: 部分处理必须带 PARTIAL_PROCESSING")

    seen: set[str] = set()
    spans: list[tuple[int, int]] = []
    for index, row in enumerate(payload.get("windows") or []):
        tag = f"{name}: windows[{index}]"
        for field in ("id", "startMs", "endMs", "sampleAtMs", "quality", "qualityReason",
                      "headUpCount", "validObservedCount", "headUpRatio"):
            check(field in row, f"{tag} 缺少 {field}")
        wid = row.get("id")
        check(isinstance(wid, str) and wid and wid not in seen, f"{tag} id 为空或重复")
        seen.add(wid)
        wa, wb = row.get("startMs"), row.get("endMs")
        check(is_int(wa) and is_int(wb) and a <= wa < wb <= b, f"{tag} 区间非法")
        check(wb - wa <= window_ms, f"{tag} 超过 windowMs")
        check(sum(overlap((wa, wb), p) for p in processed) == wb - wa,
              f"{tag} 未被 processedRanges 覆盖")
        spans.append((wa, wb))
        quality = row.get("quality")
        check(quality in ("VALID", "NO_SUBJECTS", "LOW_QUALITY"), f"{tag} quality 非法")
        if quality != "VALID":
            check(isinstance(row.get("qualityReason"), str) and bool(row["qualityReason"]),
                  f"{tag} 非 VALID 必须给 qualityReason")
        sample = row.get("sampleAtMs")
        if sample is None:
            check(quality == "LOW_QUALITY", f"{tag} sampleAtMs 只能为 LOW_QUALITY 的 null")
        else:
            check(is_int(sample) and wa <= sample < wb, f"{tag} sampleAtMs 必须落在窗口内")
        up, denominator, rate = (row.get("headUpCount"), row.get("validObservedCount"),
                                 row.get("headUpRatio"))
        if value_mode == "COUNTS":
            check(rate is None, f"{tag} COUNTS 下 headUpRatio 必须为 null")
            if quality == "VALID":
                check(is_int(up) and is_int(denominator) and denominator > 0
                      and 0 <= up <= denominator, f"{tag} VALID 需要 0<=分子<=分母 且分母>0")
            elif quality == "NO_SUBJECTS":
                check(is_int(up) and is_int(denominator) and up == 0 and denominator == 0,
                      f"{tag} NO_SUBJECTS 必须固定 0/0")
            else:
                check(up is None and denominator is None, f"{tag} LOW_QUALITY 两个计数必须为 null")
        else:
            check(up is None and denominator is None, f"{tag} RATIO_ONLY 人数必须为 null")
            if quality == "VALID":
                check(isinstance(rate, (int, float)) and not isinstance(rate, bool)
                      and 0.0 <= float(rate) <= 1.0, f"{tag} headUpRatio 必须在 [0,1]")
            else:
                check(rate is None, f"{tag} 非 VALID 的 headUpRatio 必须为 null")

    check(spans == sorted(spans), f"{name}: windows 必须按 startMs 升序")
    check(all(x[1] <= y[0] for x, y in zip(spans, spans[1:])), f"{name}: windows 不得重叠")
    check(union_size(spans) == union_size(processed),
          f"{name}: windows 必须恰好覆盖 processedRanges")
    return {"bounds": (a, b), "value_mode": value_mode}


def compute_stats(payload: dict, duration_ms: int) -> dict:
    """stats-v2（v0.2 Round 2）：课程均值 = SUM(up)/SUM(evaluable)，只含 VALID 窗口。

    同时输出 v1（时长加权）对照值，便于核对口径切换。
    """
    windows = payload["windows"]
    counts_mode = payload["valueMode"] == "COUNTS"
    rates: list[float | None] = []
    rates_all: list[float | None] = []
    lengths: list[int] = []
    sum_up = 0
    sum_evaluable = 0
    for row in windows:
        lengths.append(row["endMs"] - row["startMs"])
        if counts_mode:
            denominator = row["validObservedCount"]
            raw = (row["headUpCount"] / denominator) if denominator else None
        else:
            raw = row["headUpRatio"]
        rates_all.append(raw)
        if row["quality"] == "VALID":
            rates.append(raw)
            if counts_mode and denominator:
                sum_up += int(row["headUpCount"])
                sum_evaluable += int(denominator)
        else:
            rates.append(None)
    pairs = [(r, d) for r, d in zip(rates, lengths) if r is not None]
    valid_ms = sum(d for _r, d in pairs)
    v1_mean = (sum(r * d for r, d in pairs) / valid_ms) if valid_ms else None
    if counts_mode:
        v2_mean = (sum_up / sum_evaluable) if sum_evaluable else None
    else:
        v2_mean = (sum(r for r, _d in pairs) / len(pairs)) if pairs else None
    processed_ms = union_size([(r["startMs"], r["endMs"]) for r in payload["processedRanges"]])
    quality_counts: dict[str, int] = {}
    for row in windows:
        quality_counts[row["quality"]] = quality_counts.get(row["quality"], 0) + 1
    return {
        "statisticsVersion": "stats-v2",
        "durationMs": duration_ms,
        "headUp": {
            "processedDurationMs": processed_ms,
            "processedCoverage": processed_ms / duration_ms,
            "validDurationMs": valid_ms,
            "validCoverage": valid_ms / duration_ms,
            "meanRate": v2_mean,
            "aggregation": "sum_ratio",
            "aggregatedWindowCount": len(pairs),
            "aggregatedDurationMs": valid_ms,
            "v1DurationWeightedMeanRate": v1_mean,
            "sumHeadUpCount": sum_up if counts_mode else None,
            "sumEvaluableCount": sum_evaluable if counts_mode else None,
            "windowRates": rates,
            "windowRatesAll": rates_all,
            "qualityCounts": quality_counts,
        },
    }


def _stats_match(recomputed: dict, stored: dict, *, check_v1: bool) -> bool:
    """比对重算结果与存档统计：v2 字段必须一致；v1 字段按需比对（老样例可能没有）。"""
    a, b = recomputed["headUp"], stored.get("headUp", {})
    if a["meanRate"] != b.get("meanRate") and not (
            a["meanRate"] is not None and b.get("meanRate") is not None
            and math.isclose(a["meanRate"], b["meanRate"], rel_tol=1e-12)):
        return False
    if a["windowRates"] != b.get("windowRates"):
        return False
    if a["validDurationMs"] != b.get("validDurationMs"):
        return False
    if check_v1 and "v1DurationWeightedMeanRate" in b:
        v1_a, v1_b = a["v1DurationWeightedMeanRate"], b["v1DurationWeightedMeanRate"]
        if v1_a is None or v1_b is None:
            return v1_a == v1_b
        if not math.isclose(v1_a, v1_b, rel_tol=1e-12):
            return False
    return True


def main() -> int:
    json_files = sorted(ROOT.glob("*.json"))
    envelopes = {}
    for path in json_files:
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, dict) and data.get("moduleType") == "HEAD_UP" and "status" in data:
            envelopes[path.name] = data
            verify_envelope(path.name, data)

    print(f"已校验 {CHECKED} 个 HEAD_UP 信封；失败 {len(FAILURES)}")
    for message in FAILURES:
        print("  FAIL " + message)

    # 真实媒体结果与同目录 stats 必须一致（用独立实现重算，防止样例自说自话）
    real = envelopes.get("head-up-real-success.json")
    stats_path = ROOT / "head-up-real-stats.json"
    if real and stats_path.is_file():
        if real["status"] == "SUCCEEDED":
            recomputed = compute_stats(real["payload"], real["durationMs"])
            stored = json.loads(stats_path.read_text(encoding="utf-8"))
            same = _stats_match(recomputed, stored, check_v1=False)
            print("真实结果与 stats 文件一致:", same)
            check(same, "head-up-real-stats.json 与 head-up-real-success.json 不一致")
        else:
            print(f"真实媒体结果为 {real['status']}（{real.get('error', {}).get('code')}），"
                  "跳过统计比对")

    # 真实媒体请求的 hash/时长必须与结果一致（防止交付样例自相矛盾）
    request_path = ROOT / "head-up-real-request.json"
    if request_path.is_file() and real:
        request = json.loads(request_path.read_text(encoding="utf-8"))
        check(request["media"]["sha256"] == real["mediaSha256"],
              "head-up-real-request.json 的 hash 与结果不一致")
        check(request["media"]["durationMs"] == real["durationMs"],
              "head-up-real-request.json 的时长与结果不一致")
        check(request["clientRequestId"] == real["clientRequestId"],
              "head-up-real-request.json 的 clientRequestId 与结果不一致")
        check("example.invalid" in request["media"]["sourceUrl"]
              or request["media"]["sourceUrl"].startswith("<"),
              "真实媒体请求样例不得包含可用下载地址")

    # 合成样例必须与演示请求一一对应
    for request_name, result_name in (("head-up-request.json", "head-up-success.json"),
                                      ("head-up-ratio-request.json",
                                       "head-up-ratio-success.json")):
        request_path = ROOT / request_name
        if not (request_path.is_file() and result_name in envelopes):
            continue
        request = json.loads(request_path.read_text(encoding="utf-8"))
        result = envelopes[result_name]
        for key in ("schemaVersion", "clientRequestId", "moduleType", "classSessionId", "mediaId"):
            check(request[key] == result[key], f"{request_name}: {key} 与 {result_name} 不一致")
        check(request["parameters"]["windowMs"] == result["payload"]["windowMs"],
              f"{request_name}: windowMs 与结果不一致")
        check(request["parameters"]["outputMode"] == result["payload"]["valueMode"],
              f"{request_name}: outputMode 与结果不一致")
        check(request["parameters"]["definitionId"] == result["payload"]["definitionId"],
              f"{request_name}: definitionId 与结果不一致")
        check(request["analysisRange"] == result["analysisRange"],
              f"{request_name}: analysisRange 与结果不一致")

    # 合成样例的固定数值（与主项目 expected-statistics.json 相同）
    counts = envelopes.get("head-up-success.json")
    ratio = envelopes.get("head-up-ratio-success.json")
    if counts and ratio:
        rates = [(w["headUpCount"] / w["validObservedCount"]) if w["quality"] == "VALID" else None
                 for w in counts["payload"]["windows"]]
        check(rates == [0.75, 0.6, 0.0, None, None, 0.5], f"合成窗口比例不符: {rates}")
        check([w["headUpRatio"] for w in ratio["payload"]["windows"]] == rates,
              "比例模式与计数模式的窗口数值不等价")
        stats = compute_stats(counts["payload"], counts["durationMs"])
        check(math.isclose(stats["headUp"]["meanRate"], 0.4625, rel_tol=1e-12),
              f"合成平均抬头率应为 0.4625，实际 {stats['headUp']['meanRate']}")
        check(math.isclose(stats["headUp"]["validCoverage"], 2 / 3, rel_tol=1e-12),
              "合成有效窗口覆盖应为 2/3")
        check(stats["headUp"]["processedCoverage"] == 1.0, "合成处理覆盖应为 1")

    # HTTP 联调记录
    record_path = ROOT / "http-run-record.json"
    if record_path.is_file():
        record = json.loads(record_path.read_text(encoding="utf-8"))
        check(record.get("postStatus") == 202, "HTTP 首次提交应为 202")
        check(record.get("replayStatus") == 200, "同键重提应为 200")
        check(record.get("replayReusedJobId") is True, "同键重提必须复用同一 jobId")

    # 真实模型样例：请求与结果必须一致，统计必须能独立复算，且素材必须标明非真实课堂录像
    real_request_path = ROOT / "head-up-realclassroom-request.json"
    real_result = envelopes.get("head-up-realclassroom-success.json")
    real_stats_path = ROOT / "head-up-realclassroom-stats.json"
    if real_request_path.is_file() and real_result and real_stats_path.is_file():
        request = json.loads(real_request_path.read_text(encoding="utf-8"))
        check(request["media"]["sha256"] == real_result["mediaSha256"],
              "head-up-realclassroom-request.json 的 hash 与结果不一致")
        check(request["media"]["durationMs"] == real_result["durationMs"],
              "head-up-realclassroom-request.json 的时长与结果不一致")
        check(request["parameters"]["windowMs"] == real_result["payload"]["windowMs"],
              "真实样例的 windowMs 与结果不一致")
        check(request["parameters"]["definitionId"] == real_result["payload"]["definitionId"],
              "真实样例的 definitionId 与结果不一致")
        if real_result["status"] == "SUCCEEDED":
            recomputed = compute_stats(real_result["payload"], real_result["durationMs"])
            stored = json.loads(real_stats_path.read_text(encoding="utf-8"))
            same = _stats_match(recomputed, stored, check_v1=True)
            check(same, "head-up-realclassroom-stats.json 与结果不一致")
            print("真实课堂场景样例可独立复算:", same)
            # v0.2 Round 2：均值口径必须可解释（SUM 比 + 只含 VALID）
            check(stored.get("statisticsVersion") == "stats-v2",
                  "真实课堂样例的统计版本应为 stats-v2")
            check(stored["headUp"].get("aggregation") == "sum_ratio",
                  "真实课堂样例的聚合方式应为 sum_ratio（SUM(up)/SUM(evaluable)）")
            check(stored["headUp"].get("aggregatedWindowCount")
                  == sum(1 for w in real_result["payload"]["windows"]
                         if w["quality"] == "VALID"),
                  "aggregatedWindowCount 必须等于 VALID 窗口数")

    run_record_path = ROOT / "realclassroom-run-record.json"
    if run_record_path.is_file():
        run_record = json.loads(run_record_path.read_text(encoding="utf-8"))
        check("非真实课堂录像" in run_record["media"]["note"],
              "真实场景样例必须标明素材不是真实课堂录像")
        check(bool(run_record.get("visionModel")), "运行记录必须写明实际使用的视觉模型")
        check(bool(run_record.get("promptVersion")), "运行记录必须写明提示词版本")
        check(run_record.get("credentialsConfigured") is True,
              "真实模型样例必须记录当时确实配置了凭证")
        check(len(run_record.get("perWindowNotes") or []) == len(real_result["payload"]["windows"])
              if real_result and real_result["status"] == "SUCCEEDED" else True,
              "运行记录的逐窗判读条数必须与结果窗口数一致")

    if FAILURES:
        print("\nFAILED")
        return 1
    print("\nOK：交付 fixture 满足契约形状与固定数值")
    print("注意：")
    print("  - 合成样例是人工编造的观测值，不代表真实模型精度；")
    print("  - head-up-real-success.json 来自无凭证时的降级路径（全部窗口不可判读）；")
    print("  - head-up-realclassroom-success.json 是真实 Qwen-VL 对"
          "「CC 授权课堂照片拼接视频」的输出，不是真实课堂录像，也不构成精度证据；")
    print("  - 单帧判读存在 ±1 人量级抖动，均值应视为 ±3 个百分点量级的估计值"
          "（见同目录 测试记录-真实模型.md）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
