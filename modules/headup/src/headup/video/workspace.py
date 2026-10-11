"""任务级临时目录（FRAME-5 / plan §6、§16）。

职责：给一次分析一个独立工作目录，并明确"什么时候删、什么时候留"。

```text
{workDir}/headup/{taskId}/
├── primary/            # 批量或逐点抽取的代表帧（文件名带 timestampMs）
├── review/000120000/   # 三帧复核（t-500ms / t / t+500ms）
└── task.json           # 任务元数据（时间、输入身份、策略版本、阶段）
```

生命周期（plan §16）：

```text
SUCCESS → 立即删除临时目录（不永久保存课堂截图）
FAILED  → 保留 failedTaskRetentionHours（默认 12h）便于调试，到期由 TTL 清理
```

TTL 清理是**幂等**的：可以随时调用，只删"已过期且标记为失败"的目录。
"""

from __future__ import annotations

import json
import hashlib
import re
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..wire import utc_now

TASK_META_FILENAME = "task.json"
PRIMARY_DIR = "primary"
REVIEW_DIR = "review"


@dataclass
class TaskWorkspace:
    """一个任务的临时目录句柄。"""

    root: Path
    task_id: str
    primary_dir: Path
    review_dir: Path
    meta_path: Path
    meta: dict[str, Any] = field(default_factory=dict)

    def review_dir_for(self, center_timestamp_ms: int) -> Path:
        """复核帧的子目录：按 centerTimestamp 命名（plan §6）。"""
        path = self.review_dir / f"{int(center_timestamp_ms):09d}"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def write_meta(self, **updates: Any) -> dict[str, Any]:
        self.meta.update(updates)
        self.meta["updatedAt"] = utc_now()
        self.meta_path.write_text(json.dumps(self.meta, ensure_ascii=False, indent=2),
                                  encoding="utf-8")
        return self.meta

    def frame_count(self) -> int:
        return sum(1 for path in self.root.rglob("*.jpg") if path.is_file())

    def size_bytes(self) -> int:
        return sum(path.stat().st_size for path in self.root.rglob("*") if path.is_file())


class FrameWorkspaceService:
    """创建 / 清理任务工作目录（唯一允许碰临时帧目录的地方）。"""

    def __init__(self, base_dir: str | Path, *, failed_retention_hours: float = 12.0,
                 keep_on_success: bool = False, retain_count: int = 0):
        self.base_dir = Path(base_dir) / "headup"
        self.failed_retention_hours = max(0.0, float(failed_retention_hours))
        self.keep_on_success = keep_on_success
        self.retain_count = max(0, int(retain_count))

    # -- 创建 -------------------------------------------------------------
    def create(self, task_id: str, **meta: Any) -> TaskWorkspace:
        original = str(task_id)
        safe_id = original if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", original) and ".." not in original else "task-" + hashlib.sha256(original.encode("utf-8")).hexdigest()[:32]
        root = self.base_dir / safe_id
        primary = root / PRIMARY_DIR
        review = root / REVIEW_DIR
        for path in (primary, review):
            path.mkdir(parents=True, exist_ok=True)
        workspace = TaskWorkspace(root=root, task_id=safe_id, primary_dir=primary,
                                  review_dir=review,
                                  meta_path=root / TASK_META_FILENAME)
        workspace.write_meta(taskId=safe_id, createdAt=utc_now(),
                             retentionHours=self.failed_retention_hours, **meta)
        return workspace

    # -- 生命周期 ---------------------------------------------------------
    def finish(self, workspace: TaskWorkspace, *, succeeded: bool, stage: str | None = None,
               error: str | None = None) -> dict[str, Any]:
        """结束一个任务：成功即删，失败按 TTL 保留。返回本次的清理记录。"""
        workspace.write_meta(outcome="SUCCESS" if succeeded else "FAILED",
                             finishedAt=utc_now(), stage=stage, error=error)
        record = {
            "taskId": workspace.task_id,
            "succeeded": succeeded,
            "frames": workspace.frame_count(),
            "bytes": workspace.size_bytes(),
            "retained": False,
        }
        if succeeded and not self.keep_on_success:
            shutil.rmtree(workspace.root, ignore_errors=True)
            record["cleanedUp"] = True
            return record
        if succeeded and self.keep_on_success:
            frames = sorted(path for path in workspace.root.rglob("*.jpg") if path.is_file())
            for path in frames[self.retain_count:]:
                path.unlink(missing_ok=True)
            for path in sorted(workspace.root.rglob("*"), reverse=True):
                if path.is_file() and path.name != TASK_META_FILENAME and path.suffix.lower() != ".jpg":
                    path.unlink(missing_ok=True)
                elif path.is_dir():
                    try: path.rmdir()
                    except OSError: pass
            record["retainedFrames"] = min(len(frames), self.retain_count)
        record["retained"] = True
        record["retentionHours"] = self.failed_retention_hours if not succeeded else None
        return record

    # -- TTL 清理 ---------------------------------------------------------
    def cleanup_expired(self, *, now: float | None = None) -> list[str]:
        """删除超过保留期的终态或崩溃孤儿目录；返回被删除的 taskId。"""
        if not self.base_dir.is_dir():
            return []
        deadline = (time.time() if now is None else now) - self.failed_retention_hours * 3600
        removed: list[str] = []
        for root in sorted(self.base_dir.iterdir()):
            if not root.is_dir():
                continue
            meta = self._read_meta(root)
            if root.stat().st_mtime > deadline:
                continue
            shutil.rmtree(root, ignore_errors=True)
            removed.append(root.name)
        return removed

    def list_tasks(self) -> list[dict[str, Any]]:
        if not self.base_dir.is_dir():
            return []
        tasks: list[dict[str, Any]] = []
        for root in sorted(self.base_dir.iterdir()):
            if not root.is_dir():
                continue
            meta = self._read_meta(root)
            tasks.append({"taskId": root.name, "meta": meta,
                          "bytes": sum(p.stat().st_size for p in root.rglob("*") if p.is_file())})
        return tasks

    @staticmethod
    def _read_meta(root: Path) -> dict[str, Any]:
        path = root / TASK_META_FILENAME
        if not path.is_file():
            return {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except ValueError:
            return {}
