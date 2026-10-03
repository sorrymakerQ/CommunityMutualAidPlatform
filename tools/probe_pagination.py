import re
from collections import Counter
HEADER=re.compile(r"^# Query_time:\s*([\d.]+)\s+Lock_time:\s*([\d.]+)\s+Rows_sent:\s*(\d+)\s+Rows_examined:\s*(\d+)")
entries=[];cur=None
for line in open(r"D:\develop\mysql-8.0.31-winx64\data\QQQQSH-slow.log",encoding="utf-8",errors="replace"):
    line=line.rstrip("\n")
    m=HEADER.match(line)
    if m:
        if cur: entries.append(cur)
        cur={"qt":float(m.group(1)),"re":int(m.group(4)),"rs":int(m.group(3)),"sql":[]}
        continue
    if cur is None: continue
    if line.startswith("#") or line.startswith("SET timestamp") or line.startswith("use "): continue
    if line.strip(): cur["sql"].append(line.strip())
if cur: entries.append(cur)
for e in entries: e["sql"]=" ".join(e["sql"]).rstrip(";").strip()

pat=re.compile(r"ORDER BY create_time DESC(?:,?\s*id DESC)?\s+LIMIT\s+([\d]+(?:\s*,\s*[\d]+)?)$",re.I)
hits=[e for e in entries if pat.search(e["sql"])]
offs=[]
for e in hits:
    v=pat.search(e["sql"]).group(1).replace(" ","")
    parts=v.split(",")
    off=int(parts[0]) if len(parts)==2 else 0
    offs.append((off,e["qt"],e["re"],e["rs"]))
print("匹配列表分页慢记录数:",len(hits))
print("带 offset 的:",sum(1 for o in offs if o[0]>0),"  纯 LIMIT n 的:",sum(1 for o in offs if o[0]==0))
if offs:
    print("offset: 最小 %d  最大 %d"%(min(o[0] for o in offs),max(o[0] for o in offs)))
    print("\noffset 分桶:")
    for lo,hi in [(0,1),(1,1000),(1000,10000),(10000,100000),(100000,10**9)]:
        sel=[o for o in offs if lo<=o[0]<hi]
        if sel:
            print("  offset %7d-%-9d: %4d 条  平均 %.2fs  平均 examined %s  平均 sent %s"%(
                lo,hi,len(sel),sum(s[1] for s in sel)/len(sel),
                f"{sum(s[2] for s in sel)//len(sel):,}",f"{sum(s[3] for s in sel)//len(sel):,}"))
    print("\n最深的 5 条:")
    for o in sorted(offs,key=lambda x:-x[0])[:5]:
        print("  offset=%-8d %.3fs examined=%s"%(o[0],o[1],f"{o[2]:,}"))
tot=sum(e["qt"] for e in hits)
print("\n这些列表分页语句累计耗时 %.1fs (占全部 %.1f%%)"%(tot, 100*tot/sum(e["qt"] for e in entries)))