"""用官方 verify_contract.py 的校验函数验证本模块生成的信封。

设计取舍：不把两份 fixture 混在同一个目录里跑（那会让 association 规则
拿官方 media 去比我们的 hash 而假失败），而是 import 官方模块、
直接调用它的 envelope() / association() / request_matches()。

这样验证的是"同一个校验实现"，而不是我重写一遍规则。
"""
import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODULE_FIXTURES = Path(sys.argv[1]).resolve()


def locate_official_verifier():
    """找到官方 verify_contract.py。

    这个文件<b>不属于本模块</b>——它由主项目下发，是判断契约合规的唯一权威实现。
    因此本脚本不复制它、不重写它，只按固定顺序去找原件：
    本目录副本 → docs/fixtures/ → 工作区里的官方基线目录。

    为什么坚持用原件：复制一份进来就会留下一份会**悄悄变旧**的校验器。
    主项目改了规则之后，这里仍然拿着旧规则打印"PASS"，
    而 PASS 恰恰是最不会被人复查的结论。
    """
    workspace = HERE.parent.parent.parent.parent
    candidates = [
        HERE / "verify_contract.py",                       # 本目录副本（可选）
        HERE.parent.parent / "verify_contract.py",         # docs/fixtures/
        workspace / ".work" / "fixture-verify" / "verify_contract.py",  # 官方基线目录
    ]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise SystemExit(
        "找不到官方 verify_contract.py。已查找:\n  "
        + "\n  ".join(str(c) for c in candidates)
        + "\n请把官方校验器放到 docs/fixtures/ 下，或复制到本目录。"
    )


VERIFIER = locate_official_verifier()
print(f"使用官方校验器: {VERIFIER}")

spec = importlib.util.spec_from_file_location("official_verify", VERIFIER)
official = importlib.util.module_from_spec(spec)
spec.loader.exec_module(official)

success = json.loads((MODULE_FIXTURES / "transcription-success.json").read_text(encoding="utf-8"))
failed = json.loads((MODULE_FIXTURES / "transcription-failed.json").read_text(encoding="utf-8"))
request = json.loads((MODULE_FIXTURES / "transcription-request.json").read_text(encoding="utf-8"))

# 1) 信封级不变量（官方 envelope 规则）
official.envelope(success)
official.envelope(failed)
print("PASS envelope(): 成功与失败信封都满足官方不变量")

# 2) 身份关联：成功信封必须与请求里的 media 一致。
#    注意失败信封的 mediaSha256 与请求<b>故意不同</b>——它失败的正是"hash 不符"，
#    所以不能拿请求的 hash 去套它。这里只校验它的课堂/媒体身份仍与请求一致。
media_view = {
    "classSessionId": request["classSessionId"],
    "mediaId": request["mediaId"],
    "mediaSha256": request["media"]["sha256"],
    "durationMs": request["media"]["durationMs"],
}
official.association(success, media_view)
identity = {k: media_view[k] for k in ("classSessionId", "mediaId", "durationMs")}
failed_view = dict(identity, mediaSha256=failed["mediaSha256"])
official.association(failed, failed_view)
print("PASS association(): 成功信封与请求一致；失败信封身份一致且 hash 如实记录为不匹配值")

# 失败样例的 error.code 必须是契约列举的任务失败码
TASK_CODES = {"MEDIA_URL_EXPIRED", "MEDIA_HASH_MISMATCH", "UNSUPPORTED_MEDIA",
              "DECODE_FAILED", "PROCESSING_FAILED"}
assert failed["error"]["code"] in TASK_CODES, "失败码越界: " + str(failed["error"]["code"])
assert failed["error"]["retryable"] is False, "hash 不符不应可重试"
assert "lvh.me" not in failed["error"]["message"], "错误信息不得包含媒体 URL"
print("PASS 失败码 " + failed["error"]["code"] + " 属于契约任务失败码，且不含媒体 URL")

# 3) 请求与成功结果成对（官方 request_matches 规则）
official.request_matches(request, success)
print("PASS request_matches(): 请求与成功结果逐键对上")

# 4) 负例：官方脚本必须拒绝被篡改的样例（证明校验真的在跑）
import copy

def expect_reject(label, mutate, base=None, validator=official.envelope):
    bad = copy.deepcopy(success if base is None else base)
    mutate(bad)
    try:
        validator(bad)
    except (ValueError, TypeError, KeyError):
        print("PASS 负例被拒: " + label)
        return
    raise AssertionError("负例未被拒绝: " + label)

expect_reject("revision 改成 2", lambda d: d.__setitem__("revision", 2))
expect_reject("SUCCEEDED 但 payload=null", lambda d: d.__setitem__("payload", None))
expect_reject("SUCCEEDED 但 error 非空",
              lambda d: d.__setitem__("error", {"code": "X", "message": "y", "retryable": False}))
expect_reject("segment 越出 processedRanges",
              lambda d: d["payload"]["segments"][0].__setitem__("endMs", 60001))
expect_reject("segment id 重复",
              lambda d: d["payload"]["segments"][1].__setitem__("id", "seg-001"))
expect_reject("schemaVersion 改成 2.0", lambda d: d.__setitem__("schemaVersion", "2.0"))
expect_reject("timeBase 改错", lambda d: d.__setitem__("timeBase", "CLIP_START_MS"))
# 失败样例必须以它自己为基底：拿成功信封去置 error=null 恰好仍是合法成功信封
expect_reject("失败样例缺 error", lambda d: d.__setitem__("error", None), base=failed)

print("ALL PASS: 本模块生成的 fixture 通过官方校验函数")
