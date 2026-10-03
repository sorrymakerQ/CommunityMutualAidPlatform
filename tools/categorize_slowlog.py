import re
HEADER=re.compile(r"^# Query_time:\s*([\d.]+)\s+Lock_time:\s*([\d.]+)\s+Rows_sent:\s*(\d+)\s+Rows_examined:\s*(\d+)")
entries=[];cur=None
for line in open(r"D:\develop\mysql-8.0.31-winx64\data\QQQQSH-slow.log",encoding="utf-8",errors="replace"):
    line=line.rstrip("\n")
    m=HEADER.match(line)
    if m:
        if cur: entries.append(cur)
        cur={"qt":float(m.group(1)),"lt":float(m.group(2)),"re":int(m.group(4)),"rs":int(m.group(3)),"sql":[]}
        continue
    if cur is None: continue
    if line.startswith("#") or line.startswith("SET timestamp") or line.startswith("use "): continue
    if line.strip(): cur["sql"].append(line.strip())
if cur: entries.append(cur)
for e in entries: e["sql"]=" ".join(e["sql"]).strip()

def cat(e):
    s=e["sql"].upper()
    if re.search(r"ORDER BY CREATE_TIME DESC(,?\s*ID DESC)?\s+LIMIT\s+\d+\s*,",s): return "① 列表深分页"
    if re.search(r"^(INSERT INTO|WITH RECURSIVE)",s) and ("SELECT" in s): return "② 批量造数 INSERT..SELECT"
    if re.search(r"^UPDATE (TB_USER|TB_HELP_REQUEST|TB_ORDER) (U |H |SET)",s) and "WHERE ID =" not in s: return "② 批量造数 UPDATE"
    if re.match(r"^(ALTER|CREATE INDEX|ANALYZE|OPTIMIZE)",s): return "② DDL/维护"
    if "TB_REVIEW R WHERE R.ORDER_ID" in s: return "③ 订单评价对账查询"
    if "H.UPDATE_TIME < DATE_SUB" in s: return "④ 过期求助清理"
    if s.startswith("SELECT COUNT(*) FROM TB_HELP_REQUEST WHERE STATUS"): return "⑤ 分页 COUNT(*)"
    if e["re"]==0 and e["qt"]>5: return "⑥ 锁等待/阻塞"
    return "⑦ 其他"

from collections import defaultdict
agg=defaultdict(lambda:[0,0.0,0.0,0])
for e in entries:
    c=cat(e); a=agg[c]; a[0]+=1; a[1]+=e["qt"]; a[2]=max(a[2],e["qt"]); a[3]+=e["re"]
tot=sum(e["qt"] for e in entries)
print("%-24s %6s %11s %10s %9s %14s"%("类别","条数","累计耗时s","占比","最大s","examined合计"))
print("-"*90)
for c,a in sorted(agg.items(),key=lambda kv:-kv[1][1]):
    print("%-24s %6d %11.1f %9.1f%% %9.2f %14s"%(c,a[0],a[1],100*a[1]/tot,a[2],f"{a[3]:,}"))
print("-"*90)
print("%-24s %6d %11.1f"%("合计",len(entries),tot))
print()
print("Lock_time > 0.1s 的记录数:",sum(1 for e in entries if e["lt"]>0.1))
print("examined=0 且耗时>5s 的记录:")
for e in sorted([x for x in entries if x["re"]==0 and x["qt"]>5],key=lambda x:-x["qt"])[:5]:
    print("   %.1fs lock=%.3fs  %s"%(e["qt"],e["lt"],e["sql"][:110]))