#!/usr/bin/env python3
"""Calculate gateway metrics from server.log and print a Markdown table.

Usage:
    python scripts/calc_metrics.py logs/server.log
    python scripts/calc_metrics.py logs/server.log --queue-limit 10 --output logs/metrics.md
"""

from __future__ import annotations

import argparse
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple

KV_RE = re.compile(r"(\w+)=([^\s]+)")


@dataclass
class ReqState:
    start_ts: Optional[datetime] = None
    done_ts: Optional[datetime] = None
    error_ts: Optional[datetime] = None
    error_code: Optional[str] = None

    def terminal_ts(self) -> Optional[datetime]:
        if self.done_ts and self.error_ts:
            return min(self.done_ts, self.error_ts)
        return self.done_ts or self.error_ts

    def terminal_type(self) -> Optional[str]:
        if self.done_ts and self.error_ts:
            return "DONE" if self.done_ts <= self.error_ts else "ERROR"
        if self.done_ts:
            return "DONE"
        if self.error_ts:
            return "ERROR"
        return None


def parse_ts(value: str) -> Optional[datetime]:
    """Parse ISO-8601 timestamp used by gateway logs."""
    try:
        if value.endswith("Z"):
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            return dt.replace(tzinfo=timezone.utc)
        return dt
    except Exception:
        return None


def read_text_auto(path: Path) -> str:
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig", errors="ignore")
    if raw.startswith(b"\xff\xfe"):
        return raw.decode("utf-16", errors="ignore")
    if raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16", errors="ignore")

    # Redirected PowerShell output may be UTF-16 without BOM.
    nul_ratio = raw.count(b"\x00") / max(len(raw), 1)
    if nul_ratio > 0.10:
        try:
            return raw.decode("utf-16-le", errors="ignore")
        except Exception:
            pass

    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            return raw.decode(enc)
        except Exception:
            continue
    return raw.decode("utf-8", errors="ignore")


def percentile(values: List[float], p: float) -> float:
    if not values:
        return 0.0
    if len(values) == 1:
        return values[0]
    xs = sorted(values)
    rank = (p / 100.0) * (len(xs) - 1)
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return xs[low]
    frac = rank - low
    return xs[low] * (1.0 - frac) + xs[high] * frac


def safe_div(n: float, d: float) -> float:
    return n / d if d else 0.0


def parse_log(path: Path) -> Dict[str, object]:
    text = read_text_auto(path)
    states: Dict[str, ReqState] = defaultdict(ReqState)

    qlens: List[int] = []
    token_count = 0
    all_ts: List[datetime] = []
    worker_ids = set()
    inflight_peaks = 0
    error_codes = Counter()

    # For peak connection approximation: +1 at route, -1 at first terminal.
    timeline: List[Tuple[datetime, int]] = []

    for line in text.splitlines():
        if "event=" not in line:
            continue
        kv = dict(KV_RE.findall(line))
        event = kv.get("event")
        req = kv.get("req")
        ts = parse_ts(kv.get("ts", ""))
        if ts:
            all_ts.append(ts)

        if event == "route" and req:
            st = states[req]
            if ts and st.start_ts is None:
                st.start_ts = ts
                timeline.append((ts, +1))
            worker = kv.get("worker")
            if worker:
                worker_ids.add(worker)
            if "inflight" in kv:
                try:
                    inflight_peaks = max(inflight_peaks, int(kv["inflight"]))
                except ValueError:
                    pass

        elif event == "token":
            token_count += 1
            if "qlen" in kv:
                try:
                    qlens.append(int(kv["qlen"]))
                except ValueError:
                    pass
            if req and kv.get("type") == "DONE":
                st = states[req]
                if ts and st.done_ts is None:
                    st.done_ts = ts
                    timeline.append((ts, -1))

        elif event == "error" and req:
            st = states[req]
            code = kv.get("code", "UNKNOWN")
            error_codes[code] += 1
            if st.error_code is None:
                st.error_code = code
            if ts and st.error_ts is None:
                st.error_ts = ts
                timeline.append((ts, -1))

    req_ids = [rid for rid, st in states.items() if st.start_ts is not None]
    total = len(req_ids)
    done = 0
    err = 0
    done_lat_ms: List[float] = []
    terminal_lat_ms: List[float] = []

    for rid in req_ids:
        st = states[rid]
        if st.done_ts is not None:
            done += 1
        if st.error_ts is not None:
            err += 1

        if st.start_ts and st.done_ts:
            done_lat_ms.append((st.done_ts - st.start_ts).total_seconds() * 1000.0)

        tts = st.terminal_ts()
        if st.start_ts and tts:
            terminal_lat_ms.append((tts - st.start_ts).total_seconds() * 1000.0)

    timeline.sort(key=lambda x: x[0])
    current_conn = 0
    peak_conn = 0
    for _, delta in timeline:
        current_conn += delta
        if current_conn > peak_conn:
            peak_conn = current_conn

    duration_s = 0.0
    if len(all_ts) >= 2:
        duration_s = max((max(all_ts) - min(all_ts)).total_seconds(), 0.0)

    return {
        "total": total,
        "done": done,
        "error": err,
        "completion_rate": safe_div(done, total) * 100.0,
        "error_rate": safe_div(err, total) * 100.0,
        "avg_done_ms": safe_div(sum(done_lat_ms), len(done_lat_ms)),
        "avg_terminal_ms": safe_div(sum(terminal_lat_ms), len(terminal_lat_ms)),
        "p95_terminal_ms": percentile(terminal_lat_ms, 95.0),
        "avg_queue_ratio": safe_div(sum(qlens), len(qlens)) if qlens else 0.0,
        "max_qlen": max(qlens) if qlens else 0,
        "token_count": token_count,
        "throughput_msg_s": safe_div(token_count, duration_s),
        "duration_s": duration_s,
        "workers_observed": len(worker_ids),
        "peak_inflight": inflight_peaks,
        "peak_connections": peak_conn,
        "error_codes": error_codes,
    }


def fmt_ms(ms: float) -> str:
    if ms >= 1000.0:
        return f"{ms / 1000.0:.2f}s"
    return f"{ms:.1f}ms"


def render_markdown(metrics: Dict[str, object], queue_limit: int) -> str:
    avg_queue_ratio_pct = safe_div(float(metrics["avg_queue_ratio"]), queue_limit) * 100.0
    max_queue_ratio_pct = safe_div(float(metrics["max_qlen"]), queue_limit) * 100.0

    lines = []
    lines.append("## 指标表")
    lines.append("")
    lines.append("| 指标 | 值 | 说明 |")
    lines.append("|------|-----|------|")
    lines.append(
        f"| 请求完成率 | {metrics['completion_rate']:.2f}% | DONE请求数/总请求数 ({metrics['done']}/{metrics['total']}) |"
    )
    lines.append(
        f"| 平均响应时间 | {fmt_ms(float(metrics['avg_terminal_ms']))} | 从START到首个终态(DONE或ERROR)的平均时长 |"
    )
    lines.append(
        f"| 错误率 | {metrics['error_rate']:.2f}% | ERROR请求数/总请求数 ({metrics['error']}/{metrics['total']}) |"
    )
    lines.append(
        f"| 队列使用率 | {avg_queue_ratio_pct:.2f}% | 平均 qlen/{queue_limit}，最大={max_queue_ratio_pct:.2f}% |"
    )
    lines.append(
        f"| p95延迟 | {fmt_ms(float(metrics['p95_terminal_ms']))} | START到首个终态时延的95分位 |"
    )
    lines.append(
        f"| 活跃线程数 | {metrics['workers_observed']} (观测) | route日志中出现的 worker 去重计数 |"
    )
    lines.append(
        f"| 消息吞吐量 | {metrics['throughput_msg_s']:.2f} msg/s | token日志总数/日志时间窗口({metrics['duration_s']:.2f}s) |"
    )
    lines.append(
        f"| 连接数 | 峰值约 {metrics['peak_connections']} | 由 route(+1)/终态(-1) 近似估算 |"
    )

    codes: Counter = metrics["error_codes"]  # type: ignore[assignment]
    if codes:
        lines.append("")
        lines.append("### 错误码分布")
        lines.append("")
        lines.append("| 错误码 | 次数 |")
        lines.append("|--------|------|")
        for code, cnt in codes.most_common():
            lines.append(f"| {code} | {cnt} |")

    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description="从 server.log 计算网关验证指标并输出 Markdown 表")
    parser.add_argument("server_log", help="server.log 路径")
    parser.add_argument("--queue-limit", type=int, default=10, help="队列容量（Main默认10，Gateway默认32）")
    parser.add_argument("--output", help="可选，输出 Markdown 文件路径")
    args = parser.parse_args()

    log_path = Path(args.server_log)
    if not log_path.exists():
        raise SystemExit(f"log file not found: {log_path}")

    metrics = parse_log(log_path)
    md = render_markdown(metrics, args.queue_limit)

    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(md, encoding="utf-8")
        print(f"wrote: {out}")
    else:
        print(md)


if __name__ == "__main__":
    main()

