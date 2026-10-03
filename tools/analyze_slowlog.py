"""解析 MySQL 慢查询日志，按耗时/频次归类，输出可读报告。

用法:
    python analyze_slowlog.py <slow.log> [--top N] [--json out.json]
"""
import json
import re
import statistics
import sys
from collections import defaultdict

HEADER = re.compile(
    r"^# Query_time:\s*(?P<qt>[\d.]+)\s+Lock_time:\s*(?P<lt>[\d.]+)\s+"
    r"Rows_sent:\s*(?P<rs>\d+)\s+Rows_examined:\s*(?P<re>\d+)"
)
TIME_RE = re.compile(r"^# Time:\s*(?P<t>\S+)")
USER_RE = re.compile(r"^# User@Host:\s*(?P<u>.*?)\s+Id:\s*(?P<id>\d+)")
TS_RE = re.compile(r"^SET timestamp=(\d+);")


def normalize(sql: str) -> str:
    """粗归一化：压空白、去字面量，便于同类语句聚合。"""
    s = re.sub(r"\s+", " ", sql).strip().rstrip(";")
    s = re.sub(r"'(?:[^'\\]|\\.)*'", "?", s)
    s = re.sub(r"\b\d+\b", "?", s)
    s = re.sub(r"\?(\s*,\s*\?)+", "?", s)
    return s


def parse(path):
    entries = []
    cur = None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            m = HEADER.match(line)
            if m:
                if cur:
                    entries.append(cur)
                cur = {
                    "query_time": float(m.group("qt")),
                    "lock_time": float(m.group("lt")),
                    "rows_sent": int(m.group("rs")),
                    "rows_examined": int(m.group("re")),
                    "time": None,
                    "user": None,
                    "sql": [],
                }
                continue
            if cur is None:
                continue
            m = TIME_RE.match(line)
            if m:
                cur["time"] = m.group("t")
                continue
            m = USER_RE.match(line)
            if m:
                cur["user"] = m.group("u")
                continue
            if TS_RE.match(line) or line.startswith("#"):
                continue
            if line.strip():
                cur["sql"].append(line.strip())
    if cur:
        entries.append(cur)

    for e in entries:
        e["sql"] = " ".join(e["sql"])
        e["norm"] = normalize(e["sql"])
    return entries


def main():
    path = sys.argv[1]
    top = 10
    if "--top" in sys.argv:
        top = int(sys.argv[sys.argv.index("--top") + 1])

    entries = parse(path)
    if not entries:
        print("未解析到慢查询记录")
        return

    times = [e["time"] for e in entries if e["time"]]
    total_q = sum(e["query_time"] for e in entries)

    print("=" * 78)
    print("慢查询日志总览")
    print("=" * 78)
    print(f"文件            : {path}")
    print(f"记录条数        : {len(entries)}")
    print(f"时间范围        : {min(times) if times else '?'}  ->  {max(times) if times else '?'}")
    print(f"总耗时          : {total_q:.2f} s")
    print(f"平均 / 中位耗时 : {total_q/len(entries):.3f} s / {statistics.median([e['query_time'] for e in entries]):.3f} s")
    print(f"最长单条        : {max(e['query_time'] for e in entries):.3f} s")
    print(f"Rows_examined 合计: {sum(e['rows_examined'] for e in entries):,}")

    buckets = [(0, 0.1), (0.1, 0.5), (0.5, 1), (1, 5), (5, 30), (30, float("inf"))]
    print("\n耗时分布:")
    for lo, hi in buckets:
        n = sum(1 for e in entries if lo <= e["query_time"] < hi)
        if n:
            label = f"{lo}-{hi}s" if hi != float("inf") else f">{lo}s"
            bar = "#" * min(60, n)
            print(f"  {label:>10} : {n:>4}  {bar}")

    print("\n" + "=" * 78)
    print(f"最慢 Top {top} 单条")
    print("=" * 78)
    for i, e in enumerate(sorted(entries, key=lambda x: -x["query_time"])[:top], 1):
        print(f"\n[{i}] {e['query_time']:.3f}s  lock={e['lock_time']:.3f}s  "
              f"examined={e['rows_examined']:,}  sent={e['rows_sent']:,}  @ {e['time']}")
        print(f"    {e['sql'][:400]}")

    agg = defaultdict(lambda: {"n": 0, "total": 0.0, "max": 0.0, "exam": 0, "sample": ""})
    for e in entries:
        a = agg[e["norm"]]
        a["n"] += 1
        a["total"] += e["query_time"]
        a["max"] = max(a["max"], e["query_time"])
        a["exam"] += e["rows_examined"]
        if not a["sample"] or len(e["sql"]) < len(a["sample"]):
            a["sample"] = e["sql"]

    ranked = sorted(agg.items(), key=lambda kv: -kv[1]["total"])
    print("\n" + "=" * 78)
    print("按累计耗时排序（同类语句聚合）")
    print("=" * 78)
    print(f"{'次数':>5} {'累计s':>9} {'最大s':>8} {'均examined':>11}  语句")
    for norm, a in ranked[:top]:
        print(f"{a['n']:>5} {a['total']:>9.2f} {a['max']:>8.3f} {a['exam']//a['n']:>11,}  {norm[:120]}")

    print("\n" + "=" * 78)
    print("按执行次数排序")
    print("=" * 78)
    for norm, a in sorted(agg.items(), key=lambda kv: -kv[1]["n"])[:top]:
        print(f"{a['n']:>5} 次  累计 {a['total']:>8.2f}s  最大 {a['max']:>7.3f}s  {norm[:100]}")

    print("\n" + "=" * 78)
    print("高扫描低产出（rows_examined 大而 rows_sent 小，索引可疑）")
    print("=" * 78)
    suspicious = [e for e in entries
                  if e["rows_examined"] >= 1000 and e["rows_sent"] <= 10]
    if not suspicious:
        print("  无")
    for e in sorted(suspicious, key=lambda x: -x["rows_examined"])[:top]:
        ratio = e["rows_examined"] / max(1, e["rows_sent"])
        print(f"\n  examined={e['rows_examined']:,} sent={e['rows_sent']} "
              f"ratio={ratio:,.0f}x time={e['query_time']:.3f}s @ {e['time']}")
        print(f"  {e['sql'][:300]}")

    if "--json" in sys.argv:
        out = sys.argv[sys.argv.index("--json") + 1]
        payload = {
            "file": path,
            "count": len(entries),
            "total_seconds": round(total_q, 3),
            "entries": [
                {k: e[k] for k in ("time", "query_time", "lock_time", "rows_sent", "rows_examined", "sql")}
                for e in entries
            ],
            "digests": [
                {"norm": n, "count": a["n"], "total": round(a["total"], 3),
                 "max": a["max"], "avg_examined": a["exam"] // a["n"], "sample": a["sample"]}
                for n, a in ranked
            ],
        }
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
        print(f"\n[JSON 已写入 {out}]")


if __name__ == "__main__":
    main()
