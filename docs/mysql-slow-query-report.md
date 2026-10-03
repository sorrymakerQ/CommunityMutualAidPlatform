# MySQL 慢查询分析报告

- 分析对象：本机 MySQL 8.0.31（`localhost:3306`，库 `linlibang`）
- 慢日志：`D:/develop/mysql-8.0.31-winx64/data/QQQQSH-slow.log`
- 日志覆盖：2026-09-25 17:47 → 2026-10-02 14:08（约 7 天，中间重启过 3 次，日志为追加写入）
- 规模：**1808 条慢记录，累计 14742 s，累计扫描 6.45 亿行**
- 采集时间：2026-10-02

---

## 一、结论速览

| 优先级 | 问题 | 证据 | 影响 |
|---|---|---|---|
| P0 | 列表**深分页**（`LIMIT offset, size`） | 975 条 / 8790 s，占慢日志总耗时 **59.6%** | 单次最深扫描 215 万行、耗时 20 s；极端 offset 下 53 s |
| P0 | 接口 `page` 参数**没有上限** | `HelpRequestController.java:66` 只做 `Math.max(page,1)` | `page=100000` 即可从 HTTP 触发上述全表扫描 |
| P0 | 分页页缓存**读逻辑被注释掉**，写还在 | `HelpRequestServiceImpl.java:252-257`（读，已注释）vs `:268`（写，生效） | 每次列表请求都实打实查 MySQL：`selectPage` + `COUNT(*)`，缓存零收益还白占 Redis |
| P1 | `COUNT(*)` 统计总数 | 54 条 / 196 s，平均扫描 84.6 万行，单次最慢 8.2 s | 每个列表页都执行一次 |
| P1 | 过期求助清理查 `update_time`，但**该列无任何索引** | 107 条 / 370 s，`EXPLAIN` 走 `idx_status`，`filtered=3.33%` | 每次全量捞出 3.8 万行返回应用层 |
| P1 | 订单评价对账查询全量扫已完成订单 | 166 条 / 1306 s，平均 7.9 s，返回 29.99 万个 ID | 与 `tb_review`（仅 2 行）不匹配，无时间窗、无 LIMIT |
| P2 | `log_queries_not_using_indexes=ON` 污染慢日志 | 1808 条中有 359 条耗时 < 0.1 s | 慢日志失真，真正问题被淹没 |
| — | 批量造数/修数据的一次性语句 | 26 条 / 2489 s，最大单条 534 s | 非应用流量，但会在执行期间阻塞业务写入 |

> 说明：这批慢日志产生于**压测 + 百万级造数**环境（`tb_help_request` 199 万行、`tb_user` 197 万行、`tb_order` 98.8 万行），并非生产流量。因此"批量造数"类只作记录，重点看可复现的应用路径问题。

---

## 二、按成因分类的耗时占比

| 类别 | 条数 | 累计耗时 | 占比 | 最大单次 | 累计扫描行 |
|---|---:|---:|---:|---:|---:|
| ① 列表深分页 | 975 | 8790.3 s | 59.6% | 53.35 s | 3.84 亿 |
| ② 批量造数 UPDATE | 14 | 1825.0 s | 12.4% | 534.69 s | 2287 万 |
| ③ 订单评价对账查询 | 166 | 1305.7 s | 8.9% | 14.87 s | 4978 万 |
| ⑦ 其他（含一次性修数据 SELECT） | 470 | 1251.8 s | 8.5% | 394.36 s | 1.27 亿 |
| ② 批量造数 INSERT..SELECT | 12 | 664.5 s | 4.5% | 149.55 s | 1000 万 |
| ④ 过期求助清理 | 107 | 370.3 s | 2.5% | 11.54 s | 877 万 |
| ⑥ 锁等待/阻塞 | 11 | 338.9 s | 2.3% | 50.54 s | 0 |
| ⑤ 分页 COUNT(*) | 54 | 196.3 s | 1.3% | 8.18 s | 4523 万 |
| **合计** | **1809** | **14742.8 s** | 100% | — | 6.45 亿 |

耗时分布：`<0.1s` 359 条、`1-5s` 241 条、`5-30s` 1170 条、`>30s` 33 条。

---

## 三、逐项分析

### ① 列表深分页（P0，占 59.6%）

**日志中的语句形态**

```sql
SELECT * FROM tb_help_request
WHERE status = 1 AND is_deleted = 0
ORDER BY create_time DESC LIMIT 11400, 200;
```

- 共 975 条，其中 **969 条带 offset**
- offset 分布：最大 **1,000,000**，`10000-100000` 区间 592 条、`>100000` 区间 284 条
- 平均 `Rows_examined` 37.7 万，平均只返回 200 行 → **扫描/返回比约 1900:1**

**执行计划**

```
type=range  key=idx_cat_status_del_time  rows=997356
Extra: Using index condition; Backward index scan
```

索引本身是对的（`idx_cat_status_del_time` = `status, is_deleted, create_time, category_id`），问题出在 `LIMIT offset, size` 本身：MySQL 必须沿索引**逐条走过前 offset 条**，且因为是 `SELECT *`，每条都要回表取 766 MB 宽表里的 `description` 等字段。offset 越大越慢。

**为什么说这是"可达的应用路径"而不是纯脚本行为**

- 日志里 `size=200`，但接口把 `size` 卡在 50（`HelpRequestController.java:67`），所以这些记录来自**直连数据库的分页脚本**，不是 HTTP 接口。
- 但 `HelpRequestController.java:66` 对 `page` **只做了 `Math.max(page, 1)`，没有上限**；`HelpRequestServiceImpl.java:478` 直接算 `offset = (pageNum - 1) * pageSize`。
- `HelpRequestMapper.java:168` 的 `search()` 用的正是 `LIMIT #{offset}, #{size}` 偏移分页。
- 结论：**只要请求 `page=100000&size=50`，就能通过接口复现同样的百万行扫描。**

**建议**

1. 给 `page` 加上限（如 100 页），或改为游标分页。
2. 把 `search()`（`HelpRequestMapper.java:198-204`）的偏移分页改成游标分页。
3. ⚠️ 改写游标分页时**不要**直接用行构造器比较——日志里有一条现成的反例：

   ```sql
   SELECT id,title FROM tb_help_request
   WHERE status=1 AND is_deleted=0 AND (create_time,id) < ('2025-09-26 05:59:27', 2019168)
   ORDER BY create_time DESC, id DESC LIMIT 10;
   -- 实测 394.36 s，扫描 2,000,101 行 —— 行构造器无法走索引区间，退化成全表扫描
   ```

   应改写成能走索引的等价形式：

   ```sql
   WHERE status=1 AND is_deleted=0
     AND (create_time < ? OR (create_time = ? AND id < ?))
   ORDER BY create_time DESC, id DESC LIMIT 10
   ```

   并且索引要补上 `id` 作为并列排序列（现有索引第 4 列是 `category_id`，对 `id` 排序无帮助）：

   ```sql
   ALTER TABLE tb_help_request ADD KEY idx_keyset (status, is_deleted, create_time, id);
   ```

4. 注意 `tb_help_request.create_time` 的区分度只有 20250，并列值极多，`ORDER BY create_time DESC` 缺少 `id` 兜底会导致**翻页结果重复/漏行**，建议排序列统一补 `id DESC`。

### ② 批量造数 / 修数据（一次性，非应用流量）

单条最慢 Top：

| 耗时 | 语句 |
|---:|---|
| 534.69 s | `UPDATE tb_help_request SET address_id=..., address_detail=CONCAT(...) `（全表 200 万行随机更新） |
| 244.14 s | `UPDATE tb_user SET address_id = 388 + FLOOR(RAND()*2544)` |
| 198.28 s | `UPDATE tb_user u LEFT JOIN tb_address a ON a.full_name=u.address SET u.address_id=a.id` |
| 193.88 s | `UPDATE tb_help_request h LEFT JOIN tb_address a ...` |
| 149.55 s | `INSERT INTO tb_help_request ... WITH RECURSIVE seq(n) ...`（100 万行） |

这些是压测前的数据准备，属于**预期内**的慢。提醒：执行期间会长时间持有行锁/产生大量 undo，期间业务写入会被阻塞（见 ⑥）。

### ③ 订单评价对账查询（P1）

来源：`ReviewMapper.java:32-35`

```sql
SELECT o.id FROM tb_order o WHERE o.status = 3 AND (
  NOT EXISTS (SELECT 1 FROM tb_review r WHERE r.order_id=o.id AND r.from_user_id=o.publisher_id)
  OR NOT EXISTS (SELECT 1 FROM tb_review r WHERE r.order_id=o.id AND r.from_user_id=o.helper_id));
```

**执行计划**

```
o: type=ref key=idx_status rows=496300
r: type=eq_ref key=uk_order_from (Using index)   ← 两个子查询都走了覆盖索引，已经是最优计划
```

- 166 条 / 1306 s，平均 **7.9 s**，最慢 14.87 s，`Rows_sent` 平均 29.99 万。
- 计划本身没毛病：单行成本已经最低（`uk_order_from` 覆盖索引）。**慢是因为基数**——每次都要遍历全部 `status=3` 的订单（30 万行）× 2 次索引探测。
- `tb_review` 只有 **2 行**，而 `tb_order` 有 30 万条已完成订单 → 这个查询必然返回约 30 万个"缺评价"ID。
- 该方法在 `backend/src/main/java` 下**没有任何调用方**（只有声明），属于对账用的手写 SQL。
- `tb_order.finish_time` **没有索引**（`schema.sql:184-188`）。

**建议**

1. 加时间窗，只对账最近 N 天：`AND o.finish_time >= NOW() - INTERVAL 7 DAY`，并为 `finish_time` 建索引。
2. 改用两表 `LEFT JOIN` 反连接写法，减少 DEPENDENT SUBQUERY 的逐行探测开销：
   ```sql
   SELECT o.id FROM tb_order o
   LEFT JOIN tb_review r1 ON r1.order_id=o.id AND r1.from_user_id=o.publisher_id
   LEFT JOIN tb_review r2 ON r2.order_id=o.id AND r2.from_user_id=o.helper_id
   WHERE o.status=3 AND (r1.id IS NULL OR r2.id IS NULL);
   ```
3. 若确需全量，必须**分批 + LIMIT**，不要一次返回 30 万 ID。
4. 顺带排查数据问题：`tb_review` 仅 2 行 vs 30 万已完成订单，数据本身不自洽。

### ④ 过期求助清理（P1）

```sql
SELECT h.* FROM tb_help_request h
WHERE h.status = 2 AND h.is_deleted = 0
  AND h.update_time < DATE_SUB(NOW(), INTERVAL 1 DAY)
  AND NOT EXISTS (SELECT 1 FROM tb_order o WHERE o.help_id=h.id AND o.status IN (1,2,3));
```

**执行计划**

```
h: type=ref key=idx_status rows=150430 filtered=3.33% Extra: Using where
o: type=ref key=uk_help_helper_seq Extra: Using where; Not exists
```

- 107 条 / 370 s，平均 3.46 s，最慢 11.54 s，**平均返回 38,683 行**。
- 关键缺陷：**`tb_help_request.update_time` 上没有任何索引**（已用 `information_schema.STATISTICS` 确认）。优化器只能选 2 字节的 `idx_status`，把 `update_time < ...` 当作 `Using where` 在 Server 层逐行过滤，`filtered` 只有 3.33%。现有复合索引 `idx_cat_status_del_time` 的第 3 列是 `create_time` 而不是 `update_time`，用不上。
- 且没有 `LIMIT`/分批，一次把 3.8 万行拉回应用层。

**建议**

```sql
ALTER TABLE tb_help_request ADD KEY idx_expire (status, is_deleted, update_time);
```

并改为分批处理（`LIMIT 500` 循环）或直接用集合更新语句，避免把 3.8 万行搬到应用内存。

### ⑤ 分页 COUNT(*)（P1）

```sql
SELECT COUNT(*) FROM tb_help_request WHERE status = 1 AND is_deleted = 0;   -- 54 条 / 196 s
```

- 平均扫描 **84.6 万行**，最慢 8.18 s，`Rows_sent` 恒为 1。
- 调用点：`HelpRequestServiceImpl.java:261`（`selectCount`），每个列表页请求执行一次。
- 更值得关注的是：**页缓存的读取分支被注释掉了**（`HelpRequestServiceImpl.java:252-257`），只有写回还在（`:268`）。也就是说缓存对读路径完全无效，每个列表请求都真实付出 `selectPage` + `COUNT(*)` 两次查询的代价，却还在往 Redis 写数据。

**建议**

1. 恢复缓存读取分支（同时保留随机 TTL 防雪崩的设计）。
2. 若必须精确计数，为 `COUNT` 建窄索引 `(status, is_deleted)`，或维护计数表；也可以接受近似总数（`information_schema.TABLES.TABLE_ROWS` 或 Redis 计数器）。

### ⑥ 锁等待（非查询本身问题）

11 条记录 `Rows_examined=0` 却耗时数十秒，`Lock_time` 精确落在 50.5 s：

```
50.535s lock=50.535s  UPDATE tb_help_request SET update_time=NOW(), view_count=82 WHERE id=2686166;
```

这正是 `innodb_lock_wait_timeout`（默认 50 s）超时。成因是 ② 中那批全表 `UPDATE`（如 534 s 的那条）长时间持有行锁，把业务侧按主键更新的请求全部堵死。与索引无关，属于造数/修数据期间的副作用。

---

## 四、配置层问题

| 项 | 当前值 | 评价 |
|---|---|---|
| `slow_query_log` | ON | 正常 |
| `log_output` | FILE | 正常 |
| `long_query_time` | 1.000000 | 合理 |
| `log_queries_not_using_indexes` | **ON** | ⚠️ 建议改 OFF。1808 条记录中有 359 条耗时 < 0.1 s，还混入了 IntelliJ IDEA 的元数据查询（`/* ApplicationName=IntelliJ IDEA ... */`），慢日志严重失真 |
| 日志滚动 | 无 | 日志跨 3 次重启追加写入，已 640 KB / 1808 条。建议定期轮转 |

```sql
SET GLOBAL log_queries_not_using_indexes = OFF;   -- 想彻底生效需写进 my.ini
```

---

## 五、建议的整改清单

**代码改动（不需改库）**

1. `HelpRequestController.java:66,95,123` — 给 `page` 加上限（如 100），并返回明确错误码。
2. `HelpRequestServiceImpl.java:252-257` — 恢复分页缓存读取。
3. `HelpRequestMapper.java:168` — `search()` 改游标分页。
4. `ReviewMapper.java:32-35` — 对账查询加时间窗 + 分批。

**DDL（建议在低峰执行，200 万行表加索引会锁表较久，MySQL 8.0 默认 `ALGORITHM=INPLACE` 但仍需评估）**

```sql
-- ① 清理无用/重复索引（idx_status 已被复合索引最左前缀覆盖）
ALTER TABLE tb_help_request DROP INDEX idx_status;

-- ② 游标分页专用索引
ALTER TABLE tb_help_request ADD KEY idx_keyset (status, is_deleted, create_time, id);

-- ③ 过期求助清理
ALTER TABLE tb_help_request ADD KEY idx_expire (status, is_deleted, update_time);

-- ④ 对账查询时间窗
ALTER TABLE tb_order ADD KEY idx_status_finish (status, finish_time);
```

**实例配置**

```sql
SET GLOBAL log_queries_not_using_indexes = OFF;
```

---

## 六、复现与工具

本次分析新增了四个可复用脚本（纯 Python，无第三方依赖）：

| 脚本 | 用途 |
|---|---|
| `tools/analyze_slowlog.py` | 慢日志总览：耗时分布、最慢 Top N、按归一化语句聚合、高扫描低产出检测 |
| `tools/analyze_slowlog_raw.py` | 按**原始 SQL 文本**精确聚合，区分"重复语句"与"一次性语句" |
| `tools/categorize_slowlog.py` | 按成因分类并统计耗时占比 |
| `tools/probe_pagination.py` | 统计 `LIMIT offset,size` 的 offset 分布与耗时关系 |

```powershell
python tools/analyze_slowlog.py "D:\develop\mysql-8.0.31-winx64\data\QQQQSH-slow.log" --top 12
python tools/analyze_slowlog_raw.py "D:\develop\mysql-8.0.31-winx64\data\QQQQSH-slow.log" 12
python tools/categorize_slowlog.py
python tools/probe_pagination.py
```

> 注：`analyze_slowlog.py` 的归一化会把 `LIMIT a,b` 和 `LIMIT n` 合并成同一类，需要区分深分页时请用 `analyze_slowlog_raw.py`。
