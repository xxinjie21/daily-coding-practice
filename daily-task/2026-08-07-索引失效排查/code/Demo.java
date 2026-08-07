// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
/*
 * ============================================================================
 * 这个程序在干嘛？
 * ----------------------------------------------------------------------------
 * 我们在内存里手搓一张 20 万行的「订单表」，并给它建好三个索引：
 *   1) idx_create_time  —— 单列索引，按下单时间排序
 *   2) idx_phone        —— 单列索引，字段类型是字符串（varchar）
 *   3) idx_user_time    —— 联合索引 (user_id, create_time)
 *
 * 索引这里用 TreeMap 模拟。TreeMap 是「永远按 key 排好序的字典」，
 * 和 MySQL 的 B+ 树一样，支持「从某个值开始，顺着往后连续取一段」，
 * 这正是索引能快的根本原因：不用一行行翻，直接跳到该去的位置。
 *
 * 然后我们把原题说的几种「索引白建了」的写法一个个跑一遍，
 * 用真实的【扫描行数】和【耗时】证明它到底有没有生效，并输出一个
 * 简化版 EXPLAIN（type / key / Extra 三个关键字段）。
 *
 * 一共 7 个实验，全部对应原题里提到的方法：
 *   实验1  在索引字段上做函数运算       YEAR(create_time) = 2023
 *   实验2  隐式类型转换                 varchar 字段用数字去查
 *   实验3  联合索引的最左前缀原则       (a,b) 只查 b 用不上
 *   实验4  LIKE 以 % 开头               '%2345' vs '13800012%'
 *   实验5  Extra 字段解读               覆盖索引 / 回表 / filesort
 *   实验6  优化器主动放弃索引           小表全表扫反而更快
 *   实验7  慢查询日志 + pt-query-digest 线上怎么把坏 SQL 捞出来
 * ============================================================================
 */

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Demo {

    // ==========================================================================
    // 一、基础数据结构
    // ==========================================================================

    /** 一行订单。record 是 Java 16+ 的「只读小盒子」，等价于一个只有 getter 的类。 */
    record Order(long id, long userId, long createTimeMillis, String phone, String city) {
    }

    /**
     * 联合索引 (user_id, create_time) 的排序键。
     * 生活比喻：电话簿是按「省 - 市 - 人名」这个顺序排的。
     * 先比省，省一样才比市，市一样才比人名 —— 下面的 compareTo 就是这个规矩。
     */
    record UserTimeKey(long userId, long createTimeMillis) implements Comparable<UserTimeKey> {
        @Override
        public int compareTo(UserTimeKey other) {
            int byUserId = Long.compare(this.userId, other.userId);
            if (byUserId != 0) {
                return byUserId;      // 先按 user_id 排（这就是「最左」那一列）
            }
            return Long.compare(this.createTimeMillis, other.createTimeMillis); // 再按时间排
        }
    }

    /** 简化版 EXPLAIN 的一行输出，字段名和 MySQL 保持一致。 */
    record ExplainRow(String sql, String type, String usedIndex, String extra,
                      long scannedRows, long matchedRows, long costMicros) {

        void print() {
            System.out.println("  SQL   : " + sql);
            System.out.printf("  EXPLAIN -> type=%-6s key=%-15s Extra=%s%n", type, usedIndex, extra);
            System.out.printf("  实测    -> 扫描 %,d 行，返回 %,d 行，耗时 %,d 微秒%n",
                    scannedRows, matchedRows, costMicros);
        }
    }

    // ==========================================================================
    // 二、手搓的假订单表（带索引）
    // ==========================================================================

    static class FakeOrderTable {

        /** 主键表：id -> 完整的一行。相当于 MySQL 的聚簇索引（数据「正文」就存在这）。 */
        private final Map<Long, Order> primaryKeyTable = new HashMap<>();

        /** 全表扫描时的读取顺序，相当于磁盘上一页页往下翻。 */
        private final List<Long> physicalRowIds = new ArrayList<>();

        /** 二级索引里只存主键 id（和真实 MySQL 一样），要完整行还得「回表」。 */
        private final TreeMap<Long, List<Long>> indexCreateTime = new TreeMap<>();
        private final TreeMap<String, List<Long>> indexPhone = new TreeMap<>();
        private final TreeMap<UserTimeKey, List<Long>> indexUserTime = new TreeMap<>();

        /** 这条 SQL 到底「翻」了多少行 —— 判断索引有没有生效，看这个最实在。 */
        private long scannedRows;

        private final SlowQueryLog slowQueryLog = new SlowQueryLog(200); // 超过 200 微秒才记一笔

        FakeOrderTable(int rowCount) {
            long baseTime = millisOf(2016, 1, 1);
            long stepMillis = 1_578_096L;           // 每行往后挪约 26 分钟，20 万行正好铺满 10 年
            String[] cityPool = {"杭州", "北京", "上海", "广州", "深圳"};

            for (int i = 0; i < rowCount; i++) {
                long id = i;
                long userId = i % 500;                        // 每个用户平均 400 单
                long createTime = baseTime + i * stepMillis;
                String phone = "138" + String.format("%08d", i);   // 注意：这是字符串类型
                String city = cityPool[i % cityPool.length];

                Order order = new Order(id, userId, createTime, phone, city);
                primaryKeyTable.put(id, order);
                physicalRowIds.add(id);

                addToIndex(indexCreateTime, createTime, id);
                addToIndex(indexPhone, phone, id);
                addToIndex(indexUserTime, new UserTimeKey(userId, createTime), id);
            }
        }

        private <K> void addToIndex(TreeMap<K, List<Long>> index, K key, long rowId) {
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(rowId);
        }

        // ---- 下面这两个方法是「计费口」：每读一行就记一笔，最后好算账 ----

        /** 回表：拿着索引里的 id 去正文里翻出完整的一行，算一次读取。 */
        Order readRowByPrimaryKey(long rowId) {
            scannedRows++;
            return primaryKeyTable.get(rowId);
        }

        /** 在索引里读到一条记录，也算一次读取（但比回表便宜，因为不用跳着找）。 */
        void countIndexEntry() {
            scannedRows++;
        }

        void beginQuery() {
            scannedRows = 0;
        }

        ExplainRow finishQuery(String sql, String type, String usedIndex, String extra,
                               long matchedRows, long costMicros) {
            slowQueryLog.record(sql, costMicros);
            return new ExplainRow(sql, type, usedIndex, extra, scannedRows, matchedRows, costMicros);
        }

        List<Long> allRowIdsInPhysicalOrder() {
            return physicalRowIds;
        }

        int totalRows() {
            return physicalRowIds.size();
        }

        TreeMap<Long, List<Long>> indexCreateTime() {
            return indexCreateTime;
        }

        TreeMap<String, List<Long>> indexPhone() {
            return indexPhone;
        }

        TreeMap<UserTimeKey, List<Long>> indexUserTime() {
            return indexUserTime;
        }

        SlowQueryLog slowQueryLog() {
            return slowQueryLog;
        }
    }

    // ==========================================================================
    // 三、慢查询日志 + pt-query-digest 的简化版
    // ==========================================================================

    /**
     * 慢查询日志：像饭馆门口挂的小本子，只记「上菜超过 N 秒」的那几道菜。
     * MySQL 里对应参数 slow_query_long / long_query_time。
     */
    static class SlowQueryLog {

        record Entry(String fingerprint, long costMicros) {
        }

        private final long thresholdMicros;
        private final List<Entry> entries = new ArrayList<>();

        SlowQueryLog(long thresholdMicros) {
            this.thresholdMicros = thresholdMicros;
        }

        void record(String sql, long costMicros) {
            if (costMicros >= thresholdMicros) {
                entries.add(new Entry(fingerprint(sql), costMicros));
            }
        }

        void clear() {
            entries.clear();
        }

        /**
         * 把具体的值抹成问号，好让「同一句 SQL 的不同参数」能归成一类。
         * pt-query-digest 管这一步叫 fingerprint（指纹）。
         */
        static String fingerprint(String sql) {
            String result = sql.replaceAll("'[^']*'", "?");
            return result.replaceAll("\\b\\d+\\b", "?");
        }

        /** 按「总耗时」从大到小排，因为线上真正拖垮数据库的是总量，不是单次。 */
        void printDigest() {
            record Stat(String fingerprint, int count, long total, long max) {
            }
            Map<String, long[]> grouped = new HashMap<>();  // [次数, 总耗时, 最大耗时]
            for (Entry entry : entries) {
                long[] box = grouped.computeIfAbsent(entry.fingerprint(), k -> new long[3]);
                box[0] += 1;
                box[1] += entry.costMicros();
                box[2] = Math.max(box[2], entry.costMicros());
            }
            List<Stat> stats = new ArrayList<>();
            grouped.forEach((fp, box) -> stats.add(new Stat(fp, (int) box[0], box[1], box[2])));
            stats.sort(Comparator.comparingLong(Stat::total).reversed());

            System.out.println("  （calls=调用次数, total=总耗时, avg=平均, max=最大，单位都是微秒）");
            System.out.printf("  %-8s %-14s %-12s %-12s %s%n", "calls", "total", "avg", "max", "SQL");
            for (Stat stat : stats) {
                System.out.printf("  %-8d %-14d %-12d %-12d %s%n",
                        stat.count(), stat.total(), stat.total() / stat.count(), stat.max(), stat.fingerprint());
            }
        }
    }

    // ==========================================================================
    // 四、时间小工具
    // ==========================================================================

    private static final ZoneId ZONE = ZoneId.systemDefault();

    static long millisOf(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 0, 0).atZone(ZONE).toInstant().toEpochMilli();
    }

    static int yearOf(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE).getYear();
    }

    // ==========================================================================
    // 实验1：在索引字段上做函数运算
    // ==========================================================================

    /** 坏写法：WHERE YEAR(create_time) = 2023 —— 索引按「原始时间」排序，一算函数顺序就乱了。 */
    static ExplainRow queryByYearFunction(FakeOrderTable table, int year) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        for (long rowId : table.allRowIdsInPhysicalOrder()) {
            Order row = table.readRowByPrimaryKey(rowId);  // 只能一行行读出来
            if (yearOf(row.createTimeMillis()) == year) {  // 再对每一行算一次 YEAR()
                matched.add(row);
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE YEAR(create_time) = " + year,
                "ALL", "NULL", "Using where", matched.size(), costMicros);
    }

    /** 好写法：把函数从字段上「挪走」，改成时间区间，索引立刻能用。 */
    static ExplainRow queryByTimeRange(FakeOrderTable table, long fromMillis, long toMillis, String sql) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        // subMap 就是 B+ 树的「跳到起点，顺着叶子往后走到终点」，中间无关数据一眼都不看
        for (List<Long> rowIds : table.indexCreateTime().subMap(fromMillis, true, toMillis, false).values()) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                matched.add(table.readRowByPrimaryKey(rowId));   // 回表
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery(sql, "range", "idx_create_time", "Using index condition",
                matched.size(), costMicros);
    }

    // ==========================================================================
    // 实验2：隐式类型转换
    // ==========================================================================

    /**
     * 坏写法：phone 是 varchar，条件却传了个数字。
     * MySQL 的规矩是「字符串和数字比较时，把字符串转成数字」，
     * 于是等价于 WHERE CAST(phone AS SIGNED) = 13800012345 —— 又变成了在字段上做函数。
     */
    static ExplainRow queryPhoneAsNumber(FakeOrderTable table, long phoneAsNumber) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        for (long rowId : table.allRowIdsInPhysicalOrder()) {
            Order row = table.readRowByPrimaryKey(rowId);
            if (Long.parseLong(row.phone()) == phoneAsNumber) {   // 每行都转一次
                matched.add(row);
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE phone = " + phoneAsNumber,
                "ALL", "NULL", "Using where", matched.size(), costMicros);
    }

    /** 好写法：加上引号，类型对上了，索引直接命中。 */
    static ExplainRow queryPhoneAsString(FakeOrderTable table, String phone) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        List<Long> rowIds = table.indexPhone().get(phone);       // 等值查找，一步到位
        if (rowIds != null) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                matched.add(table.readRowByPrimaryKey(rowId));
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE phone = '" + phone + "'",
                "ref", "idx_phone", "NULL", matched.size(), costMicros);
    }

    // ==========================================================================
    // 实验3：联合索引的最左前缀原则
    // ==========================================================================

    /** 坏写法：联合索引 (user_id, create_time)，却只给 create_time 条件 —— 用不上。 */
    static ExplainRow queryTimeOnlySkippingLeftmost(FakeOrderTable table, long fromMillis, long toMillis, String sql) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        for (long rowId : table.allRowIdsInPhysicalOrder()) {
            Order row = table.readRowByPrimaryKey(rowId);
            if (row.createTimeMillis() >= fromMillis && row.createTimeMillis() < toMillis) {
                matched.add(row);
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery(sql, "ALL", "NULL", "Using where", matched.size(), costMicros);
    }

    /** 好写法之一：只给最左列 user_id，能用上索引的前半截。 */
    static ExplainRow queryByUserId(FakeOrderTable table, long userId) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        var range = table.indexUserTime().subMap(
                new UserTimeKey(userId, Long.MIN_VALUE), true,
                new UserTimeKey(userId, Long.MAX_VALUE), true);
        for (List<Long> rowIds : range.values()) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                matched.add(table.readRowByPrimaryKey(rowId));
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE user_id = " + userId,
                "ref", "idx_user_time", "NULL", matched.size(), costMicros);
    }

    /** 好写法之二：两列都给，索引吃满，扫描量最小。 */
    static ExplainRow queryByUserIdAndTimeRange(FakeOrderTable table, long userId,
                                                long fromMillis, long toMillis, String sql) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        var range = table.indexUserTime().subMap(
                new UserTimeKey(userId, fromMillis), true,
                new UserTimeKey(userId, toMillis), false);
        for (List<Long> rowIds : range.values()) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                matched.add(table.readRowByPrimaryKey(rowId));
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery(sql, "range", "idx_user_time", "NULL", matched.size(), costMicros);
    }

    // ==========================================================================
    // 实验4：LIKE 通配符的位置
    // ==========================================================================

    /** 坏写法：LIKE '%2345'。索引按开头排序，你却只告诉我结尾，没法定位。 */
    static ExplainRow queryPhoneLikeSuffix(FakeOrderTable table, String suffix) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        for (long rowId : table.allRowIdsInPhysicalOrder()) {
            Order row = table.readRowByPrimaryKey(rowId);
            if (row.phone().endsWith(suffix)) {
                matched.add(row);
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE phone LIKE '%" + suffix + "'",
                "ALL", "NULL", "Using where", matched.size(), costMicros);
    }

    /** 好写法：LIKE '13800012%'。开头确定 = 索引里一段连续区间，直接切出来。 */
    static ExplainRow queryPhoneLikePrefix(FakeOrderTable table, String prefix) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        // 「以 prefix 开头」等价于 prefix <= phone < prefix + 最大字符，正好是一段区间
        var range = table.indexPhone().subMap(prefix, true, prefix + '\uffff', false);
        for (List<Long> rowIds : range.values()) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                matched.add(table.readRowByPrimaryKey(rowId));
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE phone LIKE '" + prefix + "%'",
                "range", "idx_phone", "Using index condition", matched.size(), costMicros);
    }

    // ==========================================================================
    // 实验5：Extra 字段解读 —— 覆盖索引 / 回表 / filesort
    // ==========================================================================

    /** 只要 phone 这一列，索引里本来就有，压根不用回表 —— Extra 会显示 Using index。 */
    static ExplainRow queryCoveringIndex(FakeOrderTable table, String prefix) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<String> matchedPhones = new ArrayList<>();
        var range = table.indexPhone().subMap(prefix, true, prefix + '\uffff', false);
        for (Map.Entry<String, List<Long>> entry : range.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                table.countIndexEntry();
                matchedPhones.add(entry.getKey());   // 值就在索引里，不用去正文翻
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT phone FROM orders WHERE phone LIKE '" + prefix + "%'",
                "range", "idx_phone", "Using index", matchedPhones.size(), costMicros);
    }

    /** 多要一个 city 列，索引里没有，就得挨个回表 —— 扫描行数直接翻倍。 */
    static ExplainRow queryNeedingRowLookup(FakeOrderTable table, String prefix) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<String> result = new ArrayList<>();
        var range = table.indexPhone().subMap(prefix, true, prefix + '\uffff', false);
        for (List<Long> rowIds : range.values()) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                Order row = table.readRowByPrimaryKey(rowId);   // 回表
                result.add(row.phone() + "/" + row.city());
            }
        }

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT phone, city FROM orders WHERE phone LIKE '" + prefix + "%'",
                "range", "idx_phone", "NULL", result.size(), costMicros);
    }

    /** ORDER BY 的列不在索引顺序里，只能把结果捞出来自己再排一遍 —— Extra: Using filesort。 */
    static ExplainRow queryWithFilesort(FakeOrderTable table, long userId) {
        table.beginQuery();
        long startNano = System.nanoTime();

        List<Order> matched = new ArrayList<>();
        var range = table.indexUserTime().subMap(
                new UserTimeKey(userId, Long.MIN_VALUE), true,
                new UserTimeKey(userId, Long.MAX_VALUE), true);
        for (List<Long> rowIds : range.values()) {
            for (long rowId : rowIds) {
                table.countIndexEntry();
                matched.add(table.readRowByPrimaryKey(rowId));
            }
        }
        matched.sort(Comparator.comparing(Order::city));   // 这一下就是 filesort

        long costMicros = (System.nanoTime() - startNano) / 1000;
        return table.finishQuery("SELECT * FROM orders WHERE user_id = " + userId + " ORDER BY city",
                "ref", "idx_user_time", "Using filesort", matched.size(), costMicros);
    }

    // ==========================================================================
    // 实验6：优化器主动放弃索引（原题最后一段说的情况）
    // ==========================================================================

    /**
     * MySQL 优化器会算一笔账再决定走不走索引，思路很朴素：
     *   走索引  = 命中几行就要「跳着」读几次（还要回表），单次贵，按 1.0 计价；
     *   全表扫  = 从头到尾顺着读，单次便宜，按 0.2 计价。
     * 谁便宜走谁。所以表很小、或者命中比例很高时，全表扫反而赢。
     */
    static String chooseAccessPath(String scene, long totalRows, long estimatedMatchedRows) {
        double costUsingIndex = estimatedMatchedRows * 1.0;
        double costFullScan = totalRows * 0.2;
        boolean useIndex = costUsingIndex < costFullScan;

        // 中文在终端里是两格宽，混进 printf 的对齐格式会错位，所以中文单独一行
        System.out.println("  场景：" + scene);
        System.out.printf("    total_rows=%,-9d matched=%,-9d cost_index=%-10.1f cost_fullscan=%-10.1f%n",
                totalRows, estimatedMatchedRows, costUsingIndex, costFullScan);
        System.out.println("    优化器的选择 -> " + (useIndex ? "走索引 (key=idx_xxx)" : "全表扫描 (key=NULL)"));
        return useIndex ? "index" : "full";
    }

    // ==========================================================================
    // 主流程
    // ==========================================================================

    public static void main(String[] args) {
        System.out.println("正在造 20 万行订单数据并建索引……");
        FakeOrderTable table = new FakeOrderTable(200_000);
        warmUp(table);   // 先空跑几轮，让 JIT 热起来，免得第一次测出来虚高
        System.out.println("数据就绪：共 " + String.format("%,d", table.totalRows()) + " 行\n");

        long year2023Start = millisOf(2023, 1, 1);
        long year2024Start = millisOf(2024, 1, 1);

        // ---------------- 实验1 ----------------
        printTitle("实验1  在索引字段上做函数运算：YEAR(create_time) = 2023");
        System.out.println("[坏写法] 索引是按【原始时间值】排序的，一旦套上 YEAR()，顺序就不认识了");
        ExplainRow bad1 = queryByYearFunction(table, 2023);
        bad1.print();
        System.out.println("[好写法] 把函数从字段上挪走，改成一个时间区间");
        ExplainRow good1 = queryByTimeRange(table, year2023Start, year2024Start,
                "SELECT * FROM orders WHERE create_time >= '2023-01-01' AND create_time < '2024-01-01'");
        good1.print();
        printCompare(bad1, good1);

        // ---------------- 实验2 ----------------
        printTitle("实验2  隐式类型转换：varchar 字段用数字去查");
        System.out.println("[坏写法] phone 是字符串，条件给数字，MySQL 会把【每一行】转成数字再比");
        ExplainRow bad2 = queryPhoneAsNumber(table, 13800012345L);
        bad2.print();
        System.out.println("[好写法] 加个引号，类型对上，索引一步到位");
        ExplainRow good2 = queryPhoneAsString(table, "13800012345");
        good2.print();
        printCompare(bad2, good2);

        // ---------------- 实验3 ----------------
        printTitle("实验3  最左前缀原则：联合索引 idx_user_time (user_id, create_time)");
        System.out.println("（本实验只看这个联合索引，假装单列的 idx_create_time 不存在）");
        long juneStart = millisOf(2025, 6, 1);
        long julyStart = millisOf(2025, 7, 1);
        System.out.println("[坏写法] 跳过最左列 user_id，只给 create_time —— 相当于只知道市名去查电话簿");
        ExplainRow bad3 = queryTimeOnlySkippingLeftmost(table, juneStart, julyStart,
                "SELECT * FROM orders WHERE create_time >= '2025-06-01' AND create_time < '2025-07-01'");
        bad3.print();
        System.out.println("[好写法A] 只给最左列 user_id，索引前半截能用上");
        ExplainRow good3a = queryByUserId(table, 42);
        good3a.print();
        System.out.println("[好写法B] 两列都给，索引吃满，扫描量最小");
        ExplainRow good3b = queryByUserIdAndTimeRange(table, 42, juneStart, julyStart,
                "SELECT * FROM orders WHERE user_id = 42 AND create_time >= '2025-06-01' AND create_time < '2025-07-01'");
        good3b.print();
        printCompare(bad3, good3b);

        // ---------------- 实验4 ----------------
        printTitle("实验4  LIKE 通配符的位置");
        System.out.println("[坏写法] LIKE '%2345' —— 只告诉我结尾，索引按开头排序，没法定位");
        ExplainRow bad4 = queryPhoneLikeSuffix(table, "2345");
        bad4.print();
        System.out.println("[好写法] LIKE '13800012%' —— 开头确定，正好是索引里一段连续区间");
        ExplainRow good4 = queryPhoneLikePrefix(table, "13800012");
        good4.print();
        printCompare(bad4, good4);

        // ---------------- 实验5 ----------------
        printTitle("实验5  读懂 Extra：Using index / 回表 / Using filesort");
        System.out.println("[Extra: Using index] 要的列索引里全有，不用回表 —— 这叫【覆盖索引】，最省");
        ExplainRow covering = queryCoveringIndex(table, "13800012");
        covering.print();
        System.out.println("[Extra: NULL] 多要一个 city 列，索引里没有，只能挨个回表拿");
        ExplainRow lookup = queryNeedingRowLookup(table, "13800012");
        lookup.print();
        System.out.printf("  --> 只多要一列，扫描行数从 %,d 涨到 %,d（多出来的正是回表次数）%n",
                covering.scannedRows(), lookup.scannedRows());
        System.out.println("[Extra: Using filesort] ORDER BY 的列不在索引顺序里，捞出来还得自己再排一遍");
        ExplainRow filesort = queryWithFilesort(table, 42);
        filesort.print();
        System.out.println("  --> filesort 不是「用文件排序」的意思，是「索引帮不上忙，得额外排一次」");

        // ---------------- 实验6 ----------------
        printTitle("实验6  加了索引，优化器却不用 —— 这种情况一般不用管");
        chooseAccessPath("大表 + 命中很少", 200_000, 20);
        chooseAccessPath("大表 + 命中一半", 200_000, 100_000);
        chooseAccessPath("小表 200 行", 200, 120);
        System.out.println("  --> 小表里回表的「跳来跳去」比顺着读一遍还贵，优化器就放弃索引了，这是对的");

        // ---------------- 实验7 ----------------
        printTitle("实验7  线上排查：慢查询日志 + pt-query-digest");
        table.slowQueryLog().clear();
        System.out.println("模拟线上一段时间的流量：好写法跑 60 次，坏写法只跑 3 次……");
        for (int i = 0; i < 60; i++) {
            queryPhoneAsString(table, "13800012345");
            queryPhoneLikePrefix(table, "13800012");
            queryByUserIdAndTimeRange(table, i % 500, juneStart, julyStart,
                    "SELECT * FROM orders WHERE user_id = ? AND create_time >= ? AND create_time < ?");
        }
        for (int i = 0; i < 3; i++) {
            queryByYearFunction(table, 2023);
        }
        System.out.println("（慢日志阈值设为 200 微秒，只有超过的才会被记下来）\n");
        table.slowQueryLog().printDigest();
        System.out.println("\n  --> 好写法跑了 60 次，只有偶尔抖动的那几次擦线进了慢日志，总耗时可以忽略；");
        System.out.println("      坏写法只跑 3 次，却吃掉了慢日志里绝大部分时间。");
        System.out.println("      所以 pt-query-digest 是按【总耗时】而不是【单次耗时】排序的：先治榜首那条，收益最大。");

        printTitle("小结");
        System.out.println("  索引失效的四大常见写法：字段上套函数 / 隐式类型转换 / 跳过联合索引最左列 / LIKE 以 % 开头");
        System.out.println("  共同点都是一句话：把索引赖以排序的【原始值】给弄没了，B+ 树就没法定位了。");
        System.out.println("  排查三板斧：EXPLAIN 看 type+key+Extra -> 慢日志捞出坏 SQL -> digest 按总耗时排优先级。");
    }

    /** 先空跑几轮，让 JVM 的即时编译器把代码优化好，避免第一次测量虚高。 */
    static void warmUp(FakeOrderTable table) {
        long from = millisOf(2020, 1, 1);
        long to = millisOf(2020, 7, 1);
        for (int i = 0; i < 2; i++) {
            queryByYearFunction(table, 2020);
            queryByTimeRange(table, from, to, "warmup");
            queryPhoneAsNumber(table, 13800012345L);
            queryPhoneAsString(table, "13800012345");
            queryTimeOnlySkippingLeftmost(table, from, to, "warmup");
            queryByUserId(table, 7);
            queryByUserIdAndTimeRange(table, 7, from, to, "warmup");
            queryPhoneLikeSuffix(table, "2345");
            queryPhoneLikePrefix(table, "13800012");
            queryCoveringIndex(table, "13800012");
            queryNeedingRowLookup(table, "13800012");
            queryWithFilesort(table, 7);
        }
        table.slowQueryLog().clear();
    }

    static void printTitle(String title) {
        System.out.println();
        System.out.println("==========================================================================");
        System.out.println(" " + title);
        System.out.println("==========================================================================");
    }

    static void printCompare(ExplainRow bad, ExplainRow good) {
        long rowRatio = good.scannedRows() == 0 ? bad.scannedRows() : bad.scannedRows() / good.scannedRows();
        System.out.printf("  ==> 扫描行数少了 %d 倍（%,d -> %,d），耗时 %,d us -> %,d us%n",
                rowRatio, bad.scannedRows(), good.scannedRows(), bad.costMicros(), good.costMicros());
    }
}
