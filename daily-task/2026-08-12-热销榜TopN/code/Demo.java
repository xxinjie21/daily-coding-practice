// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
// ----------------------------------------------------------------------
// 演示原题给出的方案：用 Redis 的 Sorted Set（有序集合）做「店铺热销 Top 50 榜单」。
// 因为不方便在这里真的连一台 Redis，所以我们用 Java 手写一个「迷你 Sorted Set」，
// 把 Redis 的 ZINCRBY / ZREVRANGE / ZREM / ZUNIONSTORE 这几条命令原样实现一遍，
// 行为和 Redis 一致，方便直接看到每一步发生了什么。
//
// 一共演示 6 个实验，对应题解文档里的几个要点：
//   实验1  基础用法：每卖一单 ZINCRBY +1，查榜单直接 ZREVRANGE 0 49
//   实验2  为什么快：10 万商品下，「全量排序」和「ZSET 取前 50」的耗时对比
//   实验3  大 key 的代价：一个 key 塞 10 万商品 vs 按类目分桶
//   实验4  日期维度：每天一个 key，用 ZUNIONSTORE 合成「近 7 天热销榜」
//   实验5  最常见的坑：退款不减分，榜单会虚高到排名错位
//   实验6  凌晨归档：昨天的榜单落库存档 + 给 key 设过期时间
// ----------------------------------------------------------------------

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

public class Demo {

    public static void main(String[] args) {
        experiment1BasicRank();
        experiment2WhyItIsFast();
        experiment3BigKeyVsBuckets();
        experiment4DailyKeyAndWeeklyMerge();
        experiment5RefundMustSubtract();
        experiment6ArchiveAtMidnight();
    }

    // ==================================================================
    // 实验 1：基础用法 —— 边卖边记，查榜单只看榜首几行
    // ==================================================================
    private static void experiment1BasicRank() {
        printTitle("实验1 基础用法：ZINCRBY 记销量，ZREVRANGE 取前 N 名");

        // 一张榜单就是一个 key，这里用店铺今天的销量榜
        RedisSortedSet salesRank = new RedisSortedSet();

        // 模拟一批成交流水：每成交一单，就给对应商品的分数 +1
        String[] soldItems = {
                "item_10086", "item_10086", "item_10086", "item_10086", "item_10086",
                "item_20001", "item_20001", "item_20001",
                "item_30002", "item_30002", "item_30002", "item_30002", "item_30002", "item_30002",
                "item_40003",
                "item_50004", "item_50004"
        };
        for (String itemId : soldItems) {
            // 对应 Redis：ZINCRBY sales_rank 1 item_10086
            salesRank.zincrby(itemId, 1);
        }

        System.out.println("一共成交 " + soldItems.length + " 单，榜单里有 " + salesRank.zcard() + " 个商品");
        System.out.println();

        // 对应 Redis：ZREVRANGE sales_rank 0 2 WITHSCORES（这里商品少，取前 3 名意思一下）
        System.out.println("RANK  ITEM_ID       SALES");
        List<ScoredMember> top3 = salesRank.zrevrangeWithScores(0, 2);
        int rank = 1;
        for (ScoredMember row : top3) {
            System.out.printf("%-5d %-13s %.0f%n", rank++, row.member(), row.score());
        }

        System.out.println();
        // 单查某个商品现在多少销量，走的是内部哈希表，O(1)，对应 Redis：ZSCORE
        System.out.println("单查 item_20001 当前销量（ZSCORE，O(1) 直接命中）= " + salesRank.zscore("item_20001"));
        // 单查某个商品排第几，对应 Redis：ZREVRANK
        System.out.println("单查 item_20001 当前排名（ZREVRANK，从 0 开始数）= " + salesRank.zrevrank("item_20001"));
    }

    // ==================================================================
    // 实验 2：为什么 ZSET 快 —— 和「查询时才临时全量排序」比一比
    // ==================================================================
    private static void experiment2WhyItIsFast() {
        printTitle("实验2 为什么快：10 万商品，查 Top 50 的两种做法耗时对比");

        final int itemCount = 100_000;
        Random random = new Random(20260812);

        // 做法一的数据源：把「每个商品卖了多少」当成一张数据库表，查询时才排序
        Map<String, Integer> salesTable = new LinkedHashMap<>(itemCount * 2);
        // 做法二的数据源：同一批数据，但边写边维护成一张有序榜单
        RedisSortedSet salesRank = new RedisSortedSet();

        long writeStartNanos = System.nanoTime();
        for (int i = 0; i < itemCount; i++) {
            String itemId = "item_" + i;
            // 真实店铺的销量是「二八分布」：绝大多数商品卖得很少，少数爆款遥遥领先。
            // 这里用三次方把随机数压向低位，造出这种长尾效果，榜首才不会一堆商品并列同分。
            int sales = (int) Math.round(Math.pow(random.nextDouble(), 3) * 50_000);
            salesTable.put(itemId, sales);
            salesRank.zincrby(itemId, sales);
        }
        long writeCostNanos = System.nanoTime() - writeStartNanos;
        long writeCostMicros = writeCostNanos / 1000;

        // 先热身，让 JIT 把两段代码都编译好，否则第一次跑出来的数字虚高、对比不公平
        for (int i = 0; i < 5; i++) {
            topNByFullSort(salesTable, 50);
            salesRank.zrevrangeWithScores(0, 49);
        }

        // 做法一：把 10 万条全捞出来排序，再取前 50（相当于 SQL 的 order by ... limit 50）
        long fullSortStart = System.nanoTime();
        List<ScoredMember> byFullSort = topNByFullSort(salesTable, 50);
        long fullSortMicros = (System.nanoTime() - fullSortStart) / 1000;

        // 做法二：榜单本来就是排好序的，直接从榜首往下数 50 行
        long zsetStart = System.nanoTime();
        List<ScoredMember> byZset = salesRank.zrevrangeWithScores(0, 49);
        long zsetMicros = (System.nanoTime() - zsetStart) / 1000;

        System.out.println("写入阶段：" + itemCount + " 个商品的销量灌进榜单，共耗时 " + writeCostMicros
                + " us，平均每次 ZINCRBY 约 " + (writeCostNanos / itemCount) + " ns");
        System.out.println("（排序的活儿就是分摊在这一步里的：每次成交多花不到 2 微秒，换来查询时几乎不花钱）");
        System.out.println();

        System.out.println("METHOD                      TOUCHED_ROWS   COST_MICROS");
        System.out.printf("%-27s %-14d %d%n", "full-sort-then-limit-50", itemCount, fullSortMicros);
        System.out.printf("%-27s %-14d %d%n", "zset-zrevrange-0-49", 50, zsetMicros);
        System.out.println();
        System.out.println("两种做法算出来的 Top 50 是否完全一致：" + byFullSort.equals(byZset));
        if (zsetMicros > 0) {
            System.out.println("ZSET 快了约 " + (fullSortMicros / Math.max(zsetMicros, 1)) + " 倍");
        } else {
            System.out.println("ZSET 耗时已经小到测不出来（不足 1 us）");
        }
        System.out.println();
        System.out.println("榜首 3 名：");
        System.out.println("RANK  ITEM_ID       SALES");
        for (int i = 0; i < 3; i++) {
            ScoredMember row = byZset.get(i);
            System.out.printf("%-5d %-13s %.0f%n", i + 1, row.member(), row.score());
        }
        System.out.println();
        System.out.println("关键点：ZSET 并没有比 Java 的排序算法更聪明，它只是把排序提前做掉了。");
        System.out.println("全量排序的耗时会随商品数量一起涨，ZSET 取前 50 永远只碰 50 行。");
    }

    /** 做法一：把整张表捞出来，全量排序后取前 N（数据量一大就慢在这儿）。 */
    private static List<ScoredMember> topNByFullSort(Map<String, Integer> salesTable, int topN) {
        List<ScoredMember> all = new ArrayList<>(salesTable.size());
        for (Map.Entry<String, Integer> entry : salesTable.entrySet()) {
            all.add(new ScoredMember(entry.getKey(), entry.getValue()));
        }
        // 排序规则要和 ZSET 保持一致：先按分数从高到低，分数相同按成员名字典序
        all.sort(Comparator.comparingDouble(ScoredMember::score).reversed()
                .thenComparing(ScoredMember::member));
        return new ArrayList<>(all.subList(0, Math.min(topN, all.size())));
    }

    // ==================================================================
    // 实验 3：大 key 的代价，以及按类目分桶怎么救
    // ==================================================================
    private static void experiment3BigKeyVsBuckets() {
        printTitle("实验3 别把所有商品塞一个 key：大 key vs 按类目分桶");

        final int itemCount = 100_000;
        String[] categories = {"electronics", "clothing", "food", "book"};
        Random random = new Random(7);

        // 方案 A：全店商品挤在一个 key 里
        RedisSortedSet oneBigKey = new RedisSortedSet();
        // 方案 B：按类目拆成 4 个 key，key 名形如 sales_rank:electronics
        Map<String, RedisSortedSet> buckets = new LinkedHashMap<>();
        for (String category : categories) {
            buckets.put("sales_rank:" + category, new RedisSortedSet());
        }

        for (int i = 0; i < itemCount; i++) {
            String category = categories[i % categories.length];
            String itemId = "item_" + i;
            int sales = random.nextInt(5000);
            oneBigKey.zincrby(itemId, sales);
            buckets.get("sales_rank:" + category).zincrby(itemId, sales);
        }

        System.out.println("KEY                          MEMBERS");
        System.out.printf("%-28s %d%n", "sales_rank (all in one)", oneBigKey.zcard());
        for (Map.Entry<String, RedisSortedSet> entry : buckets.entrySet()) {
            System.out.printf("%-28s %d%n", entry.getKey(), entry.getValue().zcard());
        }
        System.out.println();

        // 热身，避免首次调用的 JIT 编译耗时混进来
        for (int i = 0; i < 3; i++) {
            oneBigKey.zrangeAll();
            buckets.get("sales_rank:electronics").zrangeAll();
        }

        // 危险操作：ZRANGE key 0 -1 把一个 key 里的东西全取出来。
        // Redis 执行命令是单线程的 —— 这条命令跑多久，其他所有请求就得在门口排队多久。
        long bigKeyStart = System.nanoTime();
        int bigKeyRows = oneBigKey.zrangeAll().size();
        long bigKeyMicros = (System.nanoTime() - bigKeyStart) / 1000;

        long bucketStart = System.nanoTime();
        int bucketRows = buckets.get("sales_rank:electronics").zrangeAll().size();
        long bucketMicros = (System.nanoTime() - bucketStart) / 1000;

        System.out.println("模拟一次手滑的 ZRANGE key 0 -1（把整个 key 全量取出）：");
        System.out.println("TARGET_KEY                   ROWS_RETURNED  BLOCKING_MICROS");
        System.out.printf("%-28s %-14d %d%n", "sales_rank (all in one)", bigKeyRows, bigKeyMicros);
        System.out.printf("%-28s %-14d %d%n", "sales_rank:electronics", bucketRows, bucketMicros);
        System.out.println();
        System.out.println("这段时间里 Redis 干不了别的事，所有其他命令都在排队。");
        System.out.println("拆桶之后单个 key 小了 4 倍，最坏情况下的阻塞时间也跟着小了 4 倍。");
        System.out.println();

        // 要全店总榜怎么办？把几个桶用 ZUNIONSTORE 合起来就行（下面实验 4 会详细演示）
        RedisSortedSet shopWideRank = RedisSortedSet.zunionstore(new ArrayList<>(buckets.values()), null);
        System.out.println("需要全店总榜时，把 4 个桶 ZUNIONSTORE 合成一张即可，合并后成员数 = "
                + shopWideRank.zcard());
        ScoredMember champion = shopWideRank.zrevrangeWithScores(0, 0).get(0);
        System.out.printf("全店销冠：%s，销量 %.0f%n", champion.member(), champion.score());
    }

    // ==================================================================
    // 实验 4：每天一个 key + ZUNIONSTORE 合成近 7 天热销榜
    // ==================================================================
    private static void experiment4DailyKeyAndWeeklyMerge() {
        printTitle("实验4 日期维度：每天一个 key，ZUNIONSTORE 合成周榜");

        String[] dayKeys = {
                "sales_rank:20260806", "sales_rank:20260807", "sales_rank:20260808",
                "sales_rank:20260809", "sales_rank:20260810", "sales_rank:20260811",
                "sales_rank:20260812"
        };
        // 三个商品，走势不一样：老爆款在退烧，新品在冲榜，平销品一直不温不火
        Map<String, int[]> dailySalesOfItem = new LinkedHashMap<>();
        dailySalesOfItem.put("item_old_hit", new int[]{300, 260, 220, 180, 140, 100, 60});   // 一路下滑
        dailySalesOfItem.put("item_new_star", new int[]{10, 20, 40, 90, 180, 320, 500});     // 一路上冲
        dailySalesOfItem.put("item_steady", new int[]{150, 150, 150, 150, 150, 150, 150});   // 稳定

        List<RedisSortedSet> weekRanks = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < dayKeys.length; dayIndex++) {
            RedisSortedSet oneDay = new RedisSortedSet();
            for (Map.Entry<String, int[]> entry : dailySalesOfItem.entrySet()) {
                oneDay.zincrby(entry.getKey(), entry.getValue()[dayIndex]);
            }
            weekRanks.add(oneDay);
        }

        System.out.println("每天一个 key，互不干扰：");
        System.out.println("DAY_KEY                ITEM_OLD_HIT  ITEM_NEW_STAR  ITEM_STEADY");
        for (int i = 0; i < dayKeys.length; i++) {
            RedisSortedSet oneDay = weekRanks.get(i);
            System.out.printf("%-22s %-13.0f %-14.0f %.0f%n",
                    dayKeys[i],
                    oneDay.zscore("item_old_hit"),
                    oneDay.zscore("item_new_star"),
                    oneDay.zscore("item_steady"));
        }
        System.out.println();

        // 玩法一：7 天分数直接相加，谁 7 天卖得最多谁第一
        RedisSortedSet plainWeekRank = RedisSortedSet.zunionstore(weekRanks, null);
        System.out.println("玩法一：ZUNIONSTORE 7 天等权相加（谁总量高谁第一）");
        printRankTable(plainWeekRank, 3);
        System.out.println();

        // 玩法二：给每天加权重，越近的日子权重越大，做出「越近越热」的效果
        // 对应 Redis：ZUNIONSTORE dst 7 k1 ... k7 WEIGHTS 0.2 0.3 0.4 0.6 0.8 1.0 1.2
        double[] weightsFromOldToNew = {0.2, 0.3, 0.4, 0.6, 0.8, 1.0, 1.2};
        RedisSortedSet weightedWeekRank = RedisSortedSet.zunionstore(weekRanks, weightsFromOldToNew);
        System.out.println("玩法二：带 WEIGHTS 加权（越近的日子分量越重，榜单更能反映当下的热度）");
        printRankTable(weightedWeekRank, 3);
        System.out.println();
        System.out.println("注意两张榜的第一名不一样：等权相加时正在退烧的老爆款还能靠历史存量占位，");
        System.out.println("加权之后，最近几天猛冲的新品才是真正的『当下最热』。");
    }

    // ==================================================================
    // 实验 5：退款不减分 —— 最容易被忽略的一个坑
    // ==================================================================
    private static void experiment5RefundMustSubtract() {
        printTitle("实验5 常见坑：退款不减分，榜单会虚高到排名错位");

        // 场景：A 商品搞了个夸张的营销活动，卖了 500 单但退了 480 单（实际只留下 20 单）
        //       B 商品老老实实卖了 300 单，一单没退
        int soldA = 500, refundedA = 480;
        int soldB = 300, refundedB = 0;

        // 错误做法：只在成交时 +1，退款时什么都不做
        RedisSortedSet wrongRank = new RedisSortedSet();
        for (int i = 0; i < soldA; i++) {
            wrongRank.zincrby("item_A_marketing", 1);
        }
        for (int i = 0; i < soldB; i++) {
            wrongRank.zincrby("item_B_honest", 1);
        }

        // 正确做法：成交 ZINCRBY +1，退款 ZINCRBY -1
        RedisSortedSet rightRank = new RedisSortedSet();
        for (int i = 0; i < soldA; i++) {
            rightRank.zincrby("item_A_marketing", 1);
        }
        for (int i = 0; i < refundedA; i++) {
            rightRank.zincrby("item_A_marketing", -1);
        }
        for (int i = 0; i < soldB; i++) {
            rightRank.zincrby("item_B_honest", 1);
        }
        for (int i = 0; i < refundedB; i++) {
            rightRank.zincrby("item_B_honest", -1);
        }

        System.out.println("真实情况：A 卖 " + soldA + " 退 " + refundedA + "（净 " + (soldA - refundedA)
                + "），B 卖 " + soldB + " 退 " + refundedB + "（净 " + (soldB - refundedB) + "）");
        System.out.println();
        System.out.println("只加不减的榜单（错的）：");
        printRankTable(wrongRank, 2);
        System.out.println();
        System.out.println("退款也减分的榜单（对的）：");
        printRankTable(rightRank, 2);
        System.out.println();
        System.out.println("同一批订单，两张榜的第一名完全相反 —— 错的那张会把一个刷单商品推上首页。");
        System.out.println();

        // 再补一个小细节：净销量掉到 0 及以下的商品，顺手 ZREM 踢出去，别攒僵尸成员
        RedisSortedSet rankWithZombies = new RedisSortedSet();
        rankWithZombies.zincrby("item_normal", 42);
        rankWithZombies.zincrby("item_all_refunded", 5);
        rankWithZombies.zincrby("item_all_refunded", -5);   // 卖 5 单全退了，净 0
        rankWithZombies.zincrby("item_over_refunded", 3);
        rankWithZombies.zincrby("item_over_refunded", -4);  // 跨天退款，可能减成负数

        System.out.println("清理前，榜单成员数 = " + rankWithZombies.zcard() + "（含 0 分和负分的僵尸成员）");
        printRankTable(rankWithZombies, 3);
        int removed = rankWithZombies.removeMembersWithScoreAtMost(0);
        System.out.println("执行 ZREMRANGEBYSCORE -inf 0，清掉 " + removed + " 个，剩余成员数 = "
                + rankWithZombies.zcard());
    }

    // ==================================================================
    // 实验 6：凌晨归档 —— 落库存档 + 给 key 设过期时间
    // ==================================================================
    private static void experiment6ArchiveAtMidnight() {
        printTitle("实验6 凌晨归档：昨天的榜单落库存档，Redis 里的 key 到期自动消失");

        // 假装 Redis 里现在攒了 5 天的榜单 key
        Map<String, RedisSortedSet> redisKeySpace = new LinkedHashMap<>();
        Map<String, Integer> ttlDaysOfKey = new LinkedHashMap<>();
        String[] dayKeys = {
                "sales_rank:20260808", "sales_rank:20260809", "sales_rank:20260810",
                "sales_rank:20260811", "sales_rank:20260812"
        };
        Random random = new Random(2026);
        for (String dayKey : dayKeys) {
            RedisSortedSet oneDay = new RedisSortedSet();
            for (int i = 0; i < 200; i++) {
                oneDay.zincrby("item_" + i, 1 + random.nextInt(999));
            }
            redisKeySpace.put(dayKey, oneDay);
            ttlDaysOfKey.put(dayKey, -1);   // -1 表示没设过期时间，会一直躺在内存里
        }

        String today = "sales_rank:20260812";
        System.out.println("凌晨定时任务开跑，今天是 " + today + "，把它之前的 key 全部归档。");
        System.out.println();
        System.out.println("DAY_KEY                MEMBERS  ARCHIVED_ROWS  TTL_DAYS");

        int archivedTotal = 0;
        for (String dayKey : dayKeys) {
            RedisSortedSet oneDay = redisKeySpace.get(dayKey);
            if (dayKey.equals(today)) {
                // 今天的榜单还在实时更新，不动它
                System.out.printf("%-22s %-8d %-14s %d%n", dayKey, oneDay.zcard(), "(today,skip)",
                        ttlDaysOfKey.get(dayKey));
                continue;
            }
            // 第一步：把这一天的完整榜单写进 MySQL 存档表（这里只统计行数，不真连库）
            int rows = archiveToDatabase(dayKey, oneDay);
            archivedTotal += rows;
            // 第二步：给 Redis 里的 key 设 30 天过期，到期它自己消失，不用人工清理
            ttlDaysOfKey.put(dayKey, 30);
            System.out.printf("%-22s %-8d %-14d %d%n", dayKey, oneDay.zcard(), rows, ttlDaysOfKey.get(dayKey));
        }

        System.out.println();
        System.out.println("本次共归档 " + archivedTotal + " 行到 MySQL 的 sales_rank_archive 表。");
        System.out.println("对应代码：redisTemplate.expire(dayKey, Duration.ofDays(30));");
        System.out.println();
        System.out.println("为什么必须归档：每天一个 key，一年就是 365 个，不设过期时间内存只涨不降。");
        System.out.println("而且 Redis 只是榜单、MySQL 才是账本 —— 归档时顺便可以拿数据库的真实订单");
        System.out.println("把榜单重建一遍，把中途可能漏加漏减的分数校准回来。");
    }

    /** 把某一天的榜单整个写进数据库存档表（这里只做演示，返回写入行数）。 */
    private static int archiveToDatabase(String dayKey, RedisSortedSet oneDayRank) {
        List<ScoredMember> allRows = oneDayRank.zrangeAll();
        // 真实场景这里是一条 batch insert：
        // insert into sales_rank_archive(stat_date, item_id, sales) values (?,?,?), (?,?,?) ...
        return allRows.size();
    }

    // ==================================================================
    // 下面是「迷你 Redis Sorted Set」的实现，把 ZSET 的几条命令原样搬过来
    // ==================================================================

    /** 榜单里的一行：成员 + 分数。record 是 Java 16 引入的「只读小数据类」，省去一堆样板代码。 */
    record ScoredMember(String member, double score) {
    }

    /**
     * 迷你版 Redis Sorted Set。
     *
     * Redis 的实现是「跳表 + 哈希表」两个结构配合：
     *   - 哈希表负责「给成员名，秒查分数」，O(1)；
     *   - 跳表负责「按分数排好序」，插入和按名次取都是 O(log N)。
     * 这里用 Java 的 HashMap + TreeSet 达到同样效果：
     *   TreeSet 底层是红黑树，和跳表一样是有序结构，复杂度也是 O(log N)，
     *   区别只在实现细节，对理解这道题没有影响。
     */
    static class RedisSortedSet {

        /** 成员 -> 分数，负责 O(1) 查分（相当于 Redis 里的那个哈希表）。 */
        private final Map<String, Double> scoreOfMember = new HashMap<>();

        /** 按「分数从高到低、同分按成员名升序」排好的成员（相当于 Redis 里的跳表）。 */
        private final TreeSet<String> rankedMembers = new TreeSet<>((left, right) -> {
            double leftScore = scoreOfMember.getOrDefault(left, 0.0);
            double rightScore = scoreOfMember.getOrDefault(right, 0.0);
            if (leftScore != rightScore) {
                return Double.compare(rightScore, leftScore);   // 分数高的排前面
            }
            return left.compareTo(right);                        // 同分时按名字排，保证顺序稳定
        });

        /**
         * 对应 ZINCRBY：给成员的分数加上 delta（可以是负数，用来处理退款），返回加完之后的新分数。
         *
         * 注意这里的顺序很关键：必须「先把成员从有序结构里摘出来，再改分数，最后放回去」。
         * 因为有序结构是靠分数决定位置的，你要是先偷偷把分数改了，它就在树里找不着原来那个位置了，
         * 好比先把书的书脊标签撕了再去书架上找这本书。
         */
        double zincrby(String member, double delta) {
            double newScore = scoreOfMember.getOrDefault(member, 0.0) + delta;
            rankedMembers.remove(member);       // 1. 先摘出来（此时分数还是旧的，能正确定位）
            scoreOfMember.put(member, newScore); // 2. 再改分数
            rankedMembers.add(member);           // 3. 放回去，它会自己滑到新的名次上
            return newScore;
        }

        /** 对应 ZSCORE：查某个成员当前多少分，走哈希表，O(1)。不存在返回 0。 */
        double zscore(String member) {
            return scoreOfMember.getOrDefault(member, 0.0);
        }

        /** 对应 ZCARD：榜单里有多少个成员。 */
        int zcard() {
            return rankedMembers.size();
        }

        /** 对应 ZREVRANK：某个成员从高到低排第几（从 0 开始数），不在榜返回 -1。 */
        int zrevrank(String member) {
            if (!scoreOfMember.containsKey(member)) {
                return -1;
            }
            int position = 0;
            for (String current : rankedMembers) {
                if (current.equals(member)) {
                    return position;
                }
                position++;
            }
            return -1;
        }

        /**
         * 对应 ZREVRANGE start stop WITHSCORES：从分数最高的开始，取第 start 名到第 stop 名（都从 0 开始数）。
         *
         * 关键在于：成员本来就是排好序的，我们只需要顺着往下数 stop-start+1 行就停，
         * 完全不用碰后面那几万条数据 —— 这就是查 Top 50 快的根本原因。
         */
        List<ScoredMember> zrevrangeWithScores(int start, int stop) {
            List<ScoredMember> result = new ArrayList<>();
            int position = 0;
            for (String member : rankedMembers) {
                if (position > stop) {
                    break;                       // 够 50 个了就收手，剩下的一眼都不看
                }
                if (position >= start) {
                    result.add(new ScoredMember(member, scoreOfMember.get(member)));
                }
                position++;
            }
            return result;
        }

        /** 对应 ZRANGE key 0 -1：把整个 key 全量取出来。数据一多就是灾难，这里专门留着做反面演示。 */
        List<ScoredMember> zrangeAll() {
            List<ScoredMember> result = new ArrayList<>(rankedMembers.size());
            for (String member : rankedMembers) {
                result.add(new ScoredMember(member, scoreOfMember.get(member)));
            }
            return result;
        }

        /**
         * 对应 ZREMRANGEBYSCORE key -inf maxScore：把分数不高于 maxScore 的成员踢出榜单，返回踢掉几个。
         * 用来清理「卖出去又全退了」留下的 0 分或负分僵尸成员。
         */
        int removeMembersWithScoreAtMost(double maxScore) {
            List<String> doomed = new ArrayList<>();
            for (Map.Entry<String, Double> entry : scoreOfMember.entrySet()) {
                if (entry.getValue() <= maxScore) {
                    doomed.add(entry.getKey());
                }
            }
            for (String member : doomed) {
                rankedMembers.remove(member);        // 同样要先从有序结构里摘掉
                scoreOfMember.remove(member);
            }
            return doomed.size();
        }

        /**
         * 对应 ZUNIONSTORE：把多张榜单叠加成一张新榜，同一个成员的分数相加。
         *
         * @param sources 要合并的几张榜单（比如近 7 天、或者几个类目桶）
         * @param weights 每张榜的权重，传 null 表示都算 1.0。
         *                权重的用处：让最近几天的销量分量更重，做出「越近越热」的榜单。
         */
        static RedisSortedSet zunionstore(List<RedisSortedSet> sources, double[] weights) {
            RedisSortedSet merged = new RedisSortedSet();
            for (int i = 0; i < sources.size(); i++) {
                double weight = (weights == null) ? 1.0 : weights[i];
                RedisSortedSet source = sources.get(i);
                for (Map.Entry<String, Double> entry : source.scoreOfMember.entrySet()) {
                    merged.zincrby(entry.getKey(), entry.getValue() * weight);
                }
            }
            return merged;
        }
    }

    // ==================================================================
    // 一些打印用的小工具
    // ==================================================================

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("==================================================================");
        System.out.println(title);
        System.out.println("==================================================================");
    }

    private static void printRankTable(RedisSortedSet rank, int topN) {
        System.out.println("RANK  MEMBER                 SCORE");
        List<ScoredMember> rows = rank.zrevrangeWithScores(0, topN - 1);
        int position = 1;
        for (ScoredMember row : rows) {
            System.out.printf("%-5d %-22s %.1f%n", position++, row.member(), row.score());
        }
    }
}
