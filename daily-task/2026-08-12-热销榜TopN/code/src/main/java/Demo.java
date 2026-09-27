import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ZParams;
import redis.clients.jedis.resps.Tuple;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 这个程序在干嘛？
 * 演示「店铺热销 Top 50 榜单」怎么用 Redis 的 Sorted Set（有序集合，简称 ZSET）实现，全部是真 Jedis 调用。
 * 核心思路：边卖边记——每成交一单就 ZINCRBY +1，把「排序」分摊到每次成交上；
 *           查询时直接 ZREVRANGE 取前 50 行，不用等到查询才把全量数据捞出来排序。
 *
 * 前置条件：本机 6379 上有 Redis（见 docker-compose.yml），无密码。
 * 演示 6 件事：
 *   1) 基础用法：ZINCRBY 记销量、ZREVRANGE 取前 N、ZSCORE / ZREVRANK 单查
 *   2) 为什么快：10 万商品下，「全量取回再排序」和「ZREVRANGE 取前 50」的耗时对比
 *   3) 大 key 的代价：一个 key 塞 5 万商品 vs 按类目分桶，以及 ZUNIONSTORE 合成总榜
 *   4) 日期维度：每天一个 key，ZUNIONSTORE（可带 WEIGHTS）合成近 7 天热销榜
 *   5) 最常见的坑：退款不减分会让榜单虚高到排名错位；ZREMRANGEBYSCORE 清僵尸成员
 *   6) 凌晨归档：昨天的榜单落库存档 + 给 key 设过期时间，别让 key 无限堆积
 */
public class Demo {

    static final String HOST = "localhost";
    static final int PORT = 6379;

    public static void main(String[] args) {
        try (Jedis jedis = new Jedis(HOST, PORT)) {
            jedis.flushDB();   // 演示用：每次跑之前清干净，避免上次的数据干扰
            experiment1BasicRank(jedis);
            experiment2WhyItIsFast(jedis);
            experiment3BigKeyVsBuckets(jedis);
            experiment4DailyKeyAndWeeklyMerge(jedis);
            experiment5RefundMustSubtract(jedis);
            experiment6ArchiveAtMidnight(jedis);
            System.out.println("\n提醒：Redis 是榜单，MySQL 才是账本。");
            System.out.println("      Redis 可能重启丢数据、可能漏加一分，稳妥做法是每天凌晨用真实订单把榜单重建一遍。");
        }
    }

    /** 实验1：基础用法——边卖边记，查榜单只看榜首几行。 */
    private static void experiment1BasicRank(Jedis jedis) {
        printTitle("实验1 基础用法：ZINCRBY 记销量，ZREVRANGE 取前 N 名");
        String rankKey = "sales_rank:today";
        String[] soldItems = {
                "item_10086", "item_10086", "item_10086", "item_10086", "item_10086",
                "item_20001", "item_20001", "item_20001",
                "item_30002", "item_30002", "item_30002", "item_30002", "item_30002", "item_30002",
                "item_40003", "item_50004", "item_50004"
        };
        for (String itemId : soldItems) {
            jedis.zincrby(rankKey, 1, itemId);   // 对应 Redis 命令：ZINCRBY sales_rank:today 1 item_10086
        }
        System.out.println("一共成交 " + soldItems.length + " 单，榜单里有 " + jedis.zcard(rankKey) + " 个商品");
        printRankTable(jedis, rankKey, 3);
        // ZSCORE 走内部哈希表，给成员名就能 O(1) 拿到分数；ZREVRANK 拿排名，从 0 开始数
        System.out.println("单查 item_20001 当前销量（ZSCORE，O(1) 直接命中）= " + jedis.zscore(rankKey, "item_20001"));
        System.out.println("单查 item_20001 当前排名（ZREVRANK，从 0 开始）= " + jedis.zrevrank(rankKey, "item_20001"));
    }

    /** 实验2：为什么 ZSET 快——和「查询时才临时全量排序」比一比。 */
    private static void experiment2WhyItIsFast(Jedis jedis) {
        printTitle("实验2 为什么快：10 万商品，查 Top 50 的两种做法");
        final int itemCount = 100_000;
        String rankKey = "sales_rank:big";
        Random random = new Random(20260812);

        // 10 万次 ZINCRBY 用 Pipeline 攒成一批发出去；不攒的话光等 10 万次网络往返就够呛
        long writeStart = System.nanoTime();
        Pipeline pipeline = jedis.pipelined();
        for (int i = 0; i < itemCount; i++) {
            // 真实销量是「二八分布」：绝大多数商品卖得少，少数爆款遥遥领先。
            // 用三次方把随机数压向低位，造出长尾效果，榜首才不会一堆并列同分。
            int sales = (int) Math.round(Math.pow(random.nextDouble(), 3) * 50_000);
            pipeline.zincrby(rankKey, sales, "item_" + i);
        }
        pipeline.sync();
        System.out.println("写入阶段：" + itemCount + " 个商品的销量灌进榜单，共耗时 "
                + (System.nanoTime() - writeStart) / 1000 + " us（排序的活儿就是分摊在这一步里的）。");

        // 做法一：把 10 万条全取回来再排序，相当于 SQL 的 order by ... limit 50
        long fullSortStart = System.nanoTime();
        List<Tuple> allRows = jedis.zrangeWithScores(rankKey, 0, -1);
        allRows.sort(Comparator.comparingDouble(Tuple::getScore).reversed());
        List<Tuple> byFullSort = allRows.subList(0, 50);
        long fullSortMicros = (System.nanoTime() - fullSortStart) / 1000;

        // 做法二：榜单本来就是排好序的，直接从榜首往下数 50 行
        long zsetStart = System.nanoTime();
        List<Tuple> byZset = jedis.zrevrangeWithScores(rankKey, 0, 49);
        long zsetMicros = (System.nanoTime() - zsetStart) / 1000;

        System.out.println();
        System.out.println("METHOD                      TOUCHED_ROWS   COST_MICROS");
        System.out.printf("%-27s %-14d %d%n", "full-sort-then-limit-50", itemCount, fullSortMicros);
        System.out.printf("%-27s %-14d %d%n", "zset-zrevrange-0-49", 50, zsetMicros);
        System.out.println("两种做法算出来的 Top 50 是否完全一致：" + byFullSort.equals(byZset));
        System.out.println("差距的本质不是「Redis 比 Java 快」，而是排序提前做完了：");
        System.out.println("全量排序要碰 10 万条，ZREVRANGE 永远只碰 50 条。");
    }

    /** 实验3：大 key 的代价，以及按类目分桶怎么救。 */
    private static void experiment3BigKeyVsBuckets(Jedis jedis) {
        printTitle("实验3 别把所有商品塞一个 key：大 key vs 按类目分桶");
        final int itemCount = 50_000;
        String[] categories = {"electronics", "clothing", "food", "book"};
        String bigKey = "sales_rank:all";

        Pipeline pipeline = jedis.pipelined();
        for (int i = 0; i < itemCount; i++) {
            String itemId = "item_" + i;
            pipeline.zincrby(bigKey, 1, itemId);
            pipeline.zincrby("sales_rank:" + categories[i % categories.length], 1, itemId);
        }
        pipeline.sync();

        System.out.println("KEY                          MEMBERS");
        System.out.printf("%-28s %d%n", bigKey + " (all in one)", jedis.zcard(bigKey));
        for (String category : categories) {
            System.out.printf("%-28s %d%n", "sales_rank:" + category, jedis.zcard("sales_rank:" + category));
        }
        System.out.println();

        // 手滑执行 ZRANGE key 0 -1 把整个 key 全量取出：Redis 处理命令是单线程的，
        // 这条命令跑多久，其他所有请求就得在门口排队多久。
        long bigKeyStart = System.nanoTime();
        int bigKeyRows = jedis.zrange(bigKey, 0, -1).size();
        long bigKeyMicros = (System.nanoTime() - bigKeyStart) / 1000;
        long bucketStart = System.nanoTime();
        int bucketRows = jedis.zrange("sales_rank:electronics", 0, -1).size();
        long bucketMicros = (System.nanoTime() - bucketStart) / 1000;

        System.out.println("模拟一次手滑的 ZRANGE key 0 -1（把整个 key 全量取出）：");
        System.out.println("TARGET_KEY                   ROWS_RETURNED  BLOCKING_MICROS");
        System.out.printf("%-28s %-14d %d%n", bigKey + " (all in one)", bigKeyRows, bigKeyMicros);
        System.out.printf("%-28s %-14d %d%n", "sales_rank:electronics", bucketRows, bucketMicros);
        System.out.println("拆桶之后单个 key 小了 4 倍，最坏情况下的阻塞时间也跟着小了 4 倍。");

        // 要全店总榜？把几个桶 ZUNIONSTORE 合成一张即可（实验 4 还会用到它）
        jedis.zunionstore("sales_rank:shop_total",
                "sales_rank:electronics", "sales_rank:clothing", "sales_rank:food", "sales_rank:book");
        System.out.println("需要全店总榜时，把 4 个桶 ZUNIONSTORE 合成一张，合并后成员数 = "
                + jedis.zcard("sales_rank:shop_total"));
    }

    /** 实验4：每天一个 key + ZUNIONSTORE 合成近 7 天热销榜。 */
    private static void experiment4DailyKeyAndWeeklyMerge(Jedis jedis) {
        printTitle("实验4 日期维度：每天一个 key，ZUNIONSTORE 合成周榜");
        String[] dayKeys = {
                "sales_rank:20260806", "sales_rank:20260807", "sales_rank:20260808",
                "sales_rank:20260809", "sales_rank:20260810", "sales_rank:20260811",
                "sales_rank:20260812"
        };
        // 三个商品走势不一样：老爆款在退烧，新品在冲榜，平销品一直不温不火
        Map<String, int[]> dailySalesOfItem = new LinkedHashMap<>();
        dailySalesOfItem.put("item_old_hit", new int[]{300, 260, 220, 180, 140, 100, 60});
        dailySalesOfItem.put("item_new_star", new int[]{10, 20, 40, 90, 180, 320, 500});
        dailySalesOfItem.put("item_steady", new int[]{150, 150, 150, 150, 150, 150, 150});

        for (int dayIndex = 0; dayIndex < dayKeys.length; dayIndex++) {
            for (Map.Entry<String, int[]> entry : dailySalesOfItem.entrySet()) {
                // 每天一个新 key，天然隔离；当天卖了多少就写多少分
                jedis.zadd(dayKeys[dayIndex], entry.getValue()[dayIndex], entry.getKey());
            }
        }

        System.out.println("每天一个 key，互不干扰（三个商品 7 天的销量）：");
        for (String dayKey : dayKeys) {
            System.out.printf("  %-22s old=%-5.0f new=%-5.0f steady=%.0f%n", dayKey,
                    jedis.zscore(dayKey, "item_old_hit"),
                    jedis.zscore(dayKey, "item_new_star"),
                    jedis.zscore(dayKey, "item_steady"));
        }
        System.out.println();

        // 玩法一：7 天分数直接相加，谁 7 天卖得最多谁第一
        jedis.zunionstore("sales_rank:week7", dayKeys);
        System.out.println("玩法一：ZUNIONSTORE 7 天等权相加（谁总量高谁第一）");
        printRankTable(jedis, "sales_rank:week7", 3);

        // 玩法二：给每天加权重，越近的日子权重越大，做出「越近越热」的效果
        // 对应 Redis：ZUNIONSTORE dst 7 k1 ... k7 WEIGHTS 0.2 0.3 0.4 0.6 0.8 1.0 1.2
        ZParams weights = new ZParams().weights(0.2, 0.3, 0.4, 0.6, 0.8, 1.0, 1.2);
        jedis.zunionstore("sales_rank:week7_weighted", weights, dayKeys);
        System.out.println("玩法二：带 WEIGHTS 加权（越近的日子分量越重）");
        printRankTable(jedis, "sales_rank:week7_weighted", 3);
        System.out.println("等权时正在退烧的老爆款还能靠历史存量占位，加权后最近猛冲的新品才是真正的『当下最热』。");
    }

    /** 实验5：退款不减分——最容易被忽略的一个坑。 */
    private static void experiment5RefundMustSubtract(Jedis jedis) {
        printTitle("实验5 常见坑：退款不减分，榜单会虚高到排名错位");
        // A 商品搞了个夸张的营销活动，卖了 500 单但退了 480 单；B 商品老老实实卖了 300 单，一单没退
        int soldA = 500, refundedA = 480;
        int soldB = 300, refundedB = 0;
        String wrongKey = "sales_rank:wrong";
        String rightKey = "sales_rank:right";

        // 错误做法：只在成交时 +1，退款什么都不做
        jedis.zadd(wrongKey, soldA, "item_A_marketing");
        jedis.zadd(wrongKey, soldB, "item_B_honest");

        // 正确做法：成交 ZINCRBY +1，退款 ZINCRBY -1
        Pipeline pipeline = jedis.pipelined();
        for (int i = 0; i < soldA; i++) {
            pipeline.zincrby(rightKey, 1, "item_A_marketing");
        }
        for (int i = 0; i < refundedA; i++) {
            pipeline.zincrby(rightKey, -1, "item_A_marketing");
        }
        for (int i = 0; i < soldB; i++) {
            pipeline.zincrby(rightKey, 1, "item_B_honest");
        }
        for (int i = 0; i < refundedB; i++) {
            pipeline.zincrby(rightKey, -1, "item_B_honest");
        }
        pipeline.sync();

        System.out.println("真实情况：A 卖 " + soldA + " 退 " + refundedA + "（净 " + (soldA - refundedA)
                + "），B 卖 " + soldB + " 退 " + refundedB + "（净 " + (soldB - refundedB) + "）");
        System.out.println("只加不减的榜单（错的）：");
        printRankTable(jedis, wrongKey, 2);
        System.out.println("退款也减分的榜单（对的）：");
        printRankTable(jedis, rightKey, 2);
        System.out.println("同一批订单，两张榜的第一名完全相反——错的那张会把刷单商品推上首页。");

        // 净销量掉到 0 及以下的商品，顺手 ZREMRANGEBYSCORE 踢出去，别攒 0 分/负分的僵尸成员
        String zombieKey = "sales_rank:zombie";
        jedis.zadd(zombieKey, 42, "item_normal");
        jedis.zincrby(zombieKey, 5, "item_all_refunded");
        jedis.zincrby(zombieKey, -5, "item_all_refunded");    // 卖 5 单全退了，净 0
        jedis.zincrby(zombieKey, 3, "item_over_refunded");
        jedis.zincrby(zombieKey, -4, "item_over_refunded");   // 跨天退款，可能减成负数
        System.out.println("清理前，榜单成员数 = " + jedis.zcard(zombieKey) + "（含 0 分和负分的僵尸成员）");
        long removed = jedis.zremrangeByScore(zombieKey, Double.NEGATIVE_INFINITY, 0);
        System.out.println("执行 ZREMRANGEBYSCORE -inf 0，清掉 " + removed + " 个，剩余成员数 = "
                + jedis.zcard(zombieKey));
    }

    /** 实验6：凌晨归档——落库存档 + 给 key 设过期时间。 */
    private static void experiment6ArchiveAtMidnight(Jedis jedis) {
        printTitle("实验6 凌晨归档：昨天的榜单落库存档，Redis 里的 key 到期自动消失");
        String[] dayKeys = {"sales_rank:20260810", "sales_rank:20260811", "sales_rank:20260812"};
        Random random = new Random(2026);
        Pipeline pipeline = jedis.pipelined();
        for (String dayKey : dayKeys) {
            for (int i = 0; i < 200; i++) {
                pipeline.zadd(dayKey, 1 + random.nextInt(999), "item_" + i);
            }
        }
        pipeline.sync();

        String today = "sales_rank:20260812";
        for (String dayKey : dayKeys) {
            if (dayKey.equals(today)) {
                continue;   // 今天的榜单还在实时更新，不动它
            }
            // 真实场景：先把这一天的完整榜单 batch insert 进 MySQL 存档表，再给 key 设 30 天过期
            jedis.expire(dayKey, 30 * 24 * 3600L);
            System.out.println("  " + dayKey + " 已归档 " + jedis.zcard(dayKey) + " 行到 MySQL，"
                    + "并设 30 天过期（当前 TTL=" + jedis.ttl(dayKey) + "s）");
        }
        System.out.println("  为什么必须归档：每天一个 key，一年就是 365 个，不设过期时间内存只涨不降。");
        System.out.println("  Redis 只是榜单、MySQL 才是账本——归档时顺便拿真实订单把榜单重建一遍最稳妥。");
    }

    /** ZREVRANGE key 0 (topN-1) WITHSCORES 的封装：从榜首往下取前 topN 名。 */
    private static void printRankTable(Jedis jedis, String rankKey, int topN) {
        System.out.println("RANK  MEMBER                 SCORE");
        List<Tuple> rows = jedis.zrevrangeWithScores(rankKey, 0, topN - 1);
        int position = 1;
        for (Tuple row : rows) {
            System.out.printf("%-5d %-22s %.1f%n", position++, row.getElement(), row.getScore());
        }
    }

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("==================================================================");
        System.out.println(title);
        System.out.println("==================================================================");
    }
}
