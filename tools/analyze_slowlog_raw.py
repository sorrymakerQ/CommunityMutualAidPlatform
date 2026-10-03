"""按原始 SQL 文本精确聚合慢查询日志（不做归一化合并）。"""
import re
import sys
from collections import defaultdict

HEADER = re.compile(
    r"^# Query_time:\s*(?P<qt>[\d.]+)\s+Lock_time:\s*(?P<lt>[\d.]+)\s+"
    r"Rows_sent:\s*(?P<rs>\d+)\s+Rows_examined:\s*(?P<re>\d+)"
)
TIME_RE = re.compile(r"^# Time:\s*(?P<t>\S+)")


def parse(path):
    entries, cur = [], None
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\n")
            m = HEADER.match(line)
            if m:
                if cur:
                    entries.append(cur)
                cur = dict(qt=float(m.group("qt")), lt=float(m.group("lt")),
                           rs=int(m.group("rs")), re_=int(m.group("re")),
                           t=None, sql=[])
                continue
            if cur is None:
                continue
            m = TIME_RE.match(line)
            if m:
                cur["t"] = m.group("t")
            elif line.startswith("#") or line.startswith("SET timestamp") or line.startswith("use "):
                continue
            elif line.strip():
                cur["sql"].append(line.strip())
    if cur:
        entries.append(cur)
    for e in entries:
        e["sql"] = " ".join(e["sql"])
    return entries


def main():
    entries = parse(sys.argv[1])
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 20

    # 只统计非一次性语句（出现 >=2 次的原始文本）
    agg = defaultdict(lambda: dict(n=0, total=0.0, mx=0.0, rs=0, re_=0, first=None, last=None, sql=""))
    for e in entries:
        key = re.sub(r"\s+", " ", e["sql"])[:600]
        a = agg[key]
        a["n"] += 1
        a["total"] += e["qt"]
        a["mx"] = max(a["mx"], e["qt"])
        a["rs"] += e["rs"]
        a["re_"] += e["re_"]
        a["first"] = a["first"] or e["t"]
        a["last"] = e["t"]
        a["sql"] = key

    rep = [a for a in agg.values() if a["n"] >= 2]
    print(f"总记录 {len(entries)} 条，去重后 {len(agg)} 种，其中重复出现(>=2次) {len(rep)} 种\n")
    print("=" * 100)
    print("重复语句 —— 按累计耗时排序")
    print("=" * 100)
    for a in sorted(rep, key=lambda x: -x["total"])[:n]:
        print(f"\n次数={a['n']:<5} 累计={a['total']:>9.2f}s  平均={a['total']/a['n']:>7.3f}s  最大={a['mx']:>7.3f}s")
        print(f"  平均 examined={a['re_']//a['n']:,}  sent={a['rs']//a['n']:,}   首次={a['first']}  末次={a['last']}")
        print(f"  SQL: {a['sql'][:500]}")

    print("\n" + "=" * 100)
    print("一次性语句 —— 最慢 Top 15")
    print("=" * 100)
    once = [a for a in agg.values() if a["n"] == 1]
    for a in sorted(once, key=lambda x: -x["total"])[:15]:
        print(f"\n{a['total']:>9.3f}s  examined={a['re_']:,}  @ {a['last']}")
        print(f"  {a['sql'][:300]}")


if __name__ == "__main__":
    main()
