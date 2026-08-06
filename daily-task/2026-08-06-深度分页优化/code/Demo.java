// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   用一张「假的订单表」（100 万行，放在内存数组里）来演示：为什么 MySQL 的
//   LIMIT 1000000, 20 会慢到崩，以及原题给出的几种救法分别怎么起作用。
//
// 演示的方法（全部来自原题）：
//   实验一：LIMIT offset, size —— 浅分页 vs 深分页，看「扫了多少行」和「花了多久」
//   实验二：游标分页 where id > 上一页最后一个id —— 翻到第 5 万页依然稳如老狗
//   实验三：延迟关联 —— 必须支持「跳到第 N 页」时的折中办法，把回表次数打下来
//   实验四：排序字段有重复值时的坑 —— 游标只带时间戳会漏数据，要带 (时间, id)
//   实验五：热点数据放 Redis ZSET，按 score 排序后分页取（Feed 流常见做法）
//
// 说明：真实 MySQL 的慢来自「磁盘/内存里一行行读数据页」，这里用「构造一个订单对象」
//      来模拟读一行的成本，所以耗时的相对差距是真实跑出来的，不是写死的数字。

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class Demo {

    public static void main(String[] args) {
        FakeOrderTable table = new FakeOrderTable(1_000_000);

        // 先空跑几次让 JVM 的即时编译器（JIT）热身，否则第一次跑会被"预热成本"干扰，
        // 就像刚起步的汽车油耗虚高，测出来的数据不准。
        warmUp(table);

        experiment1_offsetPagingIsSlow(table);
        experiment2_cursorPagingIsFast(table);
        experiment3_deferredJoin(table);
        experiment4_duplicateSortValueTrap();
        experiment5_redisZSetPaging();

        System.out.println();
        System.out.println("=== 一句话结论 ===");
        System.out.println("能改成「加载更多」就用游标分页；必须跳页就用延迟关联；热点榜单直接扔 Redis ZSET。");
    }

    private static void warmUp(FakeOrderTable table) {
        for (int round = 0; round < 3; round++) {
            table.queryByLimitOffset(50_000, 20);
            table.queryByCursor(50_000L, 20);
            table.queryByDeferredJoin(50_000, 20);
        }
        table.resetCounters();
    }

    // ------------------------------------------------------------------
    // 实验一：LIMIT offset, size 为什么越翻越慢
    // ------------------------------------------------------------------
    private static void experiment1_offsetPagingIsSlow(FakeOrderTable table) {
        System.out.println("=== 实验一：LIMIT offset, size —— 翻得越深越慢 ===");
        System.out.println("数据库拿到 LIMIT 1000000, 20 时，并不会「一步跳到第 100 万行」，");
        System.out.println("它只会老老实实从头数：数满 100 万行，全部丢掉，再取后面 20 行。");
        System.out.println();

        int[] offsetsToTest = {0, 10_000, 500_000, 1_000_000 - 20};
        System.out.printf("%-14s %-14s %-12s %s%n", "offset", "实际扫描行数", "有效返回", "耗时(ms)");
        for (int offset : offsetsToTest) {
            table.resetCounters();
            long startNano = System.nanoTime();
            List<Order> page = table.queryByLimitOffset(offset, 20);
            long costMillis = (System.nanoTime() - startNano) / 1_000_000;
            System.out.printf("%-14d %-14d %-12d %d%n",
                    offset, table.getScannedRowCount(), page.size(), costMillis);
        }
        System.out.println();
        System.out.println("看最后一行：为了拿 20 条数据，白白扫了近 100 万行 —— 这就是深度分页的病根。");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 实验二：游标分页（原题主推方案）
    // ------------------------------------------------------------------
    private static void experiment2_cursorPagingIsFast(FakeOrderTable table) {
        System.out.println("=== 实验二：游标分页 where id > 上一页最后一个 id ===");
        System.out.println("换个思路：不告诉数据库「跳过多少行」，而是告诉它「从哪个 id 之后开始拿」。");
        System.out.println("主键索引是棵有序的树，找一个 id 就像查字典翻到某个字，直接定位，不用从 A 数起。");
        System.out.println();

        // 先模拟用户已经翻到很后面（第 5 万页附近）：拿到上一页最后一条的 id 当游标
        long cursorId = table.getIdAtPosition(999_960);

        table.resetCounters();
        long startNano = System.nanoTime();
        List<Order> page = table.queryByCursor(cursorId, 20);
        long costMicros = (System.nanoTime() - startNano) / 1_000;

        System.out.println("游标值（上一页最后一条 id）：" + cursorId);
        System.out.println("实际扫描行数：" + table.getScannedRowCount() + " 行（不管翻到第几页，永远是这么多）");
        System.out.println("本页返回：" + page.size() + " 条，耗时：" + costMicros + " 微秒");
        System.out.println();

        // 演示「加载更多」按钮连续点 3 次的效果
        System.out.println("模拟前端「加载更多」连点 3 次：");
        long movingCursor = 0L;   // 第一页从 0 开始
        for (int pageNo = 1; pageNo <= 3; pageNo++) {
            List<Order> onePage = table.queryByCursor(movingCursor, 5);
            System.out.printf("  第 %d 批：%s%n", pageNo, formatIds(onePage));
            movingCursor = onePage.get(onePage.size() - 1).id();  // 记住本页最后一条 id
        }
        System.out.println();

        System.out.println("【易错点】主键往往有空洞（删过数据、分库分表用了雪花 id），");
        System.out.println("所以千万别用「页码 × 每页条数」去算 id。看看这张表实际的 id：");
        System.out.printf("  第 1 行 id=%d，第 21 行 id=%d，第 41 行 id=%d —— 明显不是 1、21、41%n",
                table.getIdAtPosition(0), table.getIdAtPosition(20), table.getIdAtPosition(40));
        System.out.println("  正确做法只有一个：把上一页真实的最后一条 id 带上来。");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 实验三：延迟关联（必须支持跳页时的折中）
    // ------------------------------------------------------------------
    private static void experiment3_deferredJoin(FakeOrderTable table) {
        System.out.println("=== 实验三：延迟关联 —— 产品经理非要「跳到第 N 页」怎么办 ===");
        System.out.println("思路：先只在索引上扫 id（索引很窄，扫起来快），拿到这 20 个 id 之后，");
        System.out.println("再拿这 20 个 id 去取完整数据。术语叫「回表」——");
        System.out.println("回表 = 索引里只记了 id 和排序字段，其他字段得再跑一趟主表取，像去仓库提货。");
        System.out.println();
        System.out.println("对应 SQL 长这样：");
        System.out.println("  SELECT o.* FROM orders o");
        System.out.println("  JOIN ( SELECT id FROM orders ORDER BY id LIMIT 999980, 20 ) t ON o.id = t.id;");
        System.out.println();

        int deepOffset = 999_980;

        table.resetCounters();
        long start1 = System.nanoTime();
        table.queryByLimitOffset(deepOffset, 20);
        long cost1 = (System.nanoTime() - start1) / 1_000_000;
        long rowsRead1 = table.getFullRowReadCount();

        table.resetCounters();
        long start2 = System.nanoTime();
        List<Order> page = table.queryByDeferredJoin(deepOffset, 20);
        long cost2 = (System.nanoTime() - start2) / 1_000_000;
        long rowsRead2 = table.getFullRowReadCount();

        System.out.printf("%-22s %-16s %s%n", "写法", "回表取完整行次数", "耗时(ms)");
        System.out.printf("%-22s %-16d %d%n", "普通 LIMIT offset", rowsRead1, cost1);
        System.out.printf("%-22s %-16d %d%n", "延迟关联", rowsRead2, cost2);
        System.out.println("返回条数：" + page.size() + "（结果完全一样，只是取数路径变省了）");
        System.out.println();
        System.out.println("注意：延迟关联只是「少搬点货」，索引上那 100 万行还是要数一遍，");
        System.out.println("所以它比游标分页还是慢，属于「实在要跳页时的次优解」。");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 实验四：排序字段有重复值时，游标必须带上 id
    // ------------------------------------------------------------------
    private static void experiment4_duplicateSortValueTrap() {
        System.out.println("=== 实验四：按创建时间排序时的隐藏坑 ===");
        System.out.println("场景：订单列表按创建时间倒序。但同一秒可能下了好几单，时间戳一模一样。");
        System.out.println("如果游标只带时间戳，翻页时就会「跨过」和边界同时间的那几条 —— 数据凭空消失。");
        System.out.println();

        // 造 6 条订单，其中 3 条创建时间完全相同（都是 1000）
        List<Order> orders = new ArrayList<>();
        orders.add(new Order(101, 1000, "张三", 50));
        orders.add(new Order(102, 1000, "李四", 60));   // 时间和上面一样
        orders.add(new Order(103, 1000, "王五", 70));   // 时间还是一样
        orders.add(new Order(104, 2000, "赵六", 80));
        orders.add(new Order(105, 3000, "钱七", 90));
        orders.add(new Order(106, 4000, "孙八", 99));

        int pageSize = 2;

        // 错误写法：只用时间戳当游标
        System.out.println("【错误写法】游标只带时间戳：where createTime > 上一页最后的时间");
        List<Order> wrongResult = new ArrayList<>();
        long lastTime = -1;
        for (int round = 0; round < 3; round++) {
            List<Order> onePage = new ArrayList<>();
            for (Order order : orders) {
                if (order.createTime() > lastTime && onePage.size() < pageSize) {
                    onePage.add(order);
                }
            }
            if (onePage.isEmpty()) {
                break;
            }
            System.out.println("  第 " + (round + 1) + " 页：" + formatIds(onePage));
            wrongResult.addAll(onePage);
            lastTime = onePage.get(onePage.size() - 1).createTime();
        }
        System.out.println("  一共翻出 " + wrongResult.size() + " 条（表里其实有 " + orders.size() + " 条）"
                + (wrongResult.size() < orders.size() ? "  <-- 漏数据了！id=103 被吃掉了" : ""));
        System.out.println();

        // 正确写法：(时间, id) 组合游标
        System.out.println("【正确写法】游标带 (时间, id)：where createTime > t OR (createTime = t AND id > lastId)");
        List<Order> rightResult = new ArrayList<>();
        long cursorTime = -1;
        long cursorId = -1;
        for (int round = 0; round < 5; round++) {
            List<Order> onePage = new ArrayList<>();
            for (Order order : orders) {
                boolean afterCursor = order.createTime() > cursorTime
                        || (order.createTime() == cursorTime && order.id() > cursorId);
                if (afterCursor && onePage.size() < pageSize) {
                    onePage.add(order);
                }
            }
            if (onePage.isEmpty()) {
                break;
            }
            System.out.println("  第 " + (round + 1) + " 页：" + formatIds(onePage));
            rightResult.addAll(onePage);
            Order last = onePage.get(onePage.size() - 1);
            cursorTime = last.createTime();
            cursorId = last.id();
        }
        System.out.println("  一共翻出 " + rightResult.size() + " 条  "
                + (rightResult.size() == orders.size() ? "<-- 一条不漏，正确" : "<-- 还是有问题"));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 实验五：热点数据丢进 Redis ZSET 分页（Feed 流做法）
    // ------------------------------------------------------------------
    private static void experiment5_redisZSetPaging() {
        System.out.println("=== 实验五：热点榜单用 Redis ZSET 分页 ===");
        System.out.println("ZSET = Redis 的「有序集合」，可以理解成一个自动按分数排好队的排行榜，");
        System.out.println("每个成员挂一个 score（分数），Redis 帮你一直维持顺序，按名次取一段特别快。");
        System.out.println("适合首页热榜、Feed 流这种「数据量不大但访问巨多」的场景。");
        System.out.println();

        FakeRedisZSet hotFeed = new FakeRedisZSet();
        // 假装这是最近 1000 条热门内容，score 用发布时间戳
        for (int i = 1; i <= 1000; i++) {
            hotFeed.zadd("feed:hot", "内容#" + i, 1_700_000_000L + i);
        }

        System.out.println("榜单总量：" + hotFeed.zcard("feed:hot") + " 条");
        long start = System.nanoTime();
        List<String> page3 = hotFeed.zrevrange("feed:hot", 20, 10);  // 第 3 页，每页 10 条
        long costMicros = (System.nanoTime() - start) / 1_000;
        System.out.println("按分数从高到低取第 3 页（跳过 20 条，取 10 条）：");
        System.out.println("  " + page3);
        System.out.println("  耗时：" + costMicros + " 微秒，全程没碰数据库");
        System.out.println();
        System.out.println("为什么这里可以放心用 offset？因为总量只有 1000 条且全在内存，");
        System.out.println("跟 MySQL 扫 100 万行磁盘数据完全是两码事。数据量一大，同样得回到游标那套。");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------
    private static String formatIds(List<Order> orders) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("id=").append(orders.get(i).id());
        }
        return builder.append("]").toString();
    }

    // ==================================================================
    // 一行订单数据。record 是 Java 16+ 的简写，等价于「只读的小数据类」。
    // ==================================================================
    record Order(long id, long createTime, String buyerName, int amountYuan) {
    }

    // ==================================================================
    // 假的订单表：模拟 MySQL 的聚簇索引（数据按主键 id 有序存放）
    //
    // 为了让「慢」变得可测量，这里刻意区分两种成本：
    //   扫描一行索引  = 只看一眼 id 数组，很便宜（对应 MySQL 读窄索引）
    //   回表取完整行  = 构造出一个 Order 对象，比较贵（对应 MySQL 读整行数据页）
    // ==================================================================
    static class FakeOrderTable {

        private final long[] sortedIds;          // 主键，从小到大排好，且故意留空洞
        private final long[] createTimes;
        private final int[] amounts;

        private long scannedRowCount;            // 本次查询「数」过多少行
        private long fullRowReadCount;           // 本次查询「回表取完整行」多少次

        FakeOrderTable(int rowCount) {
            sortedIds = new long[rowCount];
            createTimes = new long[rowCount];
            amounts = new int[rowCount];
            long currentId = 1000;
            for (int i = 0; i < rowCount; i++) {
                // 每行 id 往前跳 1~3，制造删除留下的空洞：id 不等于行号
                currentId += 1 + (i % 3);
                sortedIds[i] = currentId;
                createTimes[i] = 1_700_000_000L + i;
                amounts[i] = 10 + (i % 500);
            }
        }

        /** 对应 SQL：SELECT * FROM orders ORDER BY id LIMIT offset, size —— 老实从头数 */
        List<Order> queryByLimitOffset(int offset, int size) {
            List<Order> result = new ArrayList<>(size);
            for (int position = 0; position < offset + size && position < sortedIds.length; position++) {
                scannedRowCount++;
                // 关键：MySQL 在这一步会把整行读出来再丢掉，白花力气，所以这里也照样构造对象
                Order row = readFullRow(position);
                if (position >= offset) {
                    result.add(row);
                }
            }
            return result;
        }

        /** 对应 SQL：SELECT * FROM orders WHERE id > ? ORDER BY id LIMIT size —— 直接定位 */
        List<Order> queryByCursor(long lastId, int size) {
            int startPosition = binarySearchFirstGreaterThan(lastId);
            List<Order> result = new ArrayList<>(size);
            for (int position = startPosition; position < startPosition + size && position < sortedIds.length; position++) {
                scannedRowCount++;
                result.add(readFullRow(position));
            }
            return result;
        }

        /** 对应延迟关联：先在索引上只扫 id，最后才对这 size 个 id 回表 */
        List<Order> queryByDeferredJoin(int offset, int size) {
            List<Long> targetIds = new ArrayList<>(size);
            for (int position = 0; position < offset + size && position < sortedIds.length; position++) {
                scannedRowCount++;
                if (position >= offset) {
                    targetIds.add(sortedIds[position]);   // 只碰 id 数组，不构造完整对象
                }
            }
            List<Order> result = new ArrayList<>(targetIds.size());
            for (long id : targetIds) {
                int position = binarySearchFirstGreaterThan(id - 1);
                result.add(readFullRow(position));
            }
            return result;
        }

        long getIdAtPosition(int position) {
            return sortedIds[position];
        }

        long getScannedRowCount() {
            return scannedRowCount;
        }

        long getFullRowReadCount() {
            return fullRowReadCount;
        }

        void resetCounters() {
            scannedRowCount = 0;
            fullRowReadCount = 0;
        }

        /** 把一行完整数据读出来（模拟回表的开销） */
        private Order readFullRow(int position) {
            fullRowReadCount++;
            String buyerName = "买家" + (position % 10000);   // 拼字符串模拟读一个变长字段
            return new Order(sortedIds[position], createTimes[position], buyerName, amounts[position]);
        }

        /** 二分查找：找到第一个 id 大于 targetId 的位置，模拟 B+ 树按主键定位 */
        private int binarySearchFirstGreaterThan(long targetId) {
            int low = 0;
            int high = sortedIds.length;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (sortedIds[mid] > targetId) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            return low;
        }
    }

    // ==================================================================
    // 假的 Redis ZSET：用 TreeMap 按 score 排序，够演示分页取数即可
    // ==================================================================
    static class FakeRedisZSet {

        // key -> (score+成员 组成的有序队列)
        private final TreeMap<String, TreeMap<ScoredMember, Boolean>> database = new TreeMap<>();

        void zadd(String key, String member, long score) {
            database.computeIfAbsent(key, k -> new TreeMap<>())
                    .put(new ScoredMember(score, member), Boolean.TRUE);
        }

        int zcard(String key) {
            TreeMap<ScoredMember, Boolean> zset = database.get(key);
            return zset == null ? 0 : zset.size();
        }

        /** 对应 ZREVRANGE key start stop：按分数从高到低，跳过 skip 个，取 count 个 */
        List<String> zrevrange(String key, int skip, int count) {
            List<String> result = new ArrayList<>(count);
            TreeMap<ScoredMember, Boolean> zset = database.get(key);
            if (zset == null) {
                return result;
            }
            int skipped = 0;
            for (ScoredMember item : zset.descendingKeySet()) {
                if (skipped++ < skip) {
                    continue;
                }
                result.add(item.member());
                if (result.size() == count) {
                    break;
                }
            }
            return result;
        }

        /** 分数在前、成员在后，保证同分数的成员也有稳定顺序 */
        record ScoredMember(long score, String member) implements Comparable<ScoredMember> {
            @Override
            public int compareTo(ScoredMember other) {
                int byScore = Long.compare(this.score, other.score);
                return byScore != 0 ? byScore : this.member.compareTo(other.member);
            }
        }
    }
}
