// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo

/*
 * ============================================================
 * 这个程序在干嘛？
 * ============================================================
 * 题目：一天的日志里有一堆 {用户id, 登录时间, 登出时间}（单位：秒），
 *      求「同一时刻最多有多少人在线」，以及「这个最高人数连续维持了多久」。
 *
 * 按原题给的思路来做：把每条日志拆成两个「门口事件」——
 *      登录那一刻 +1 人，登出那一刻 -1 人；
 *      把所有事件按时间排好队，从头扫一遍，用一个计数器记录当前在线人数。
 *
 * 本程序演示 4 个方法：
 *   1. scanLikeOriginalAnswer —— 原题参考答案的原样写法（用来暴露它的两个坑）
 *   2. scanByEvents           —— 修正后的事件扫描线（推荐，本题正解）
 *   3. countByDiffArray       —— 差分数组（一天只有 86400 秒，可以不排序，更快）
 *   4. bruteForce             —— 笨办法逐秒数人头，只用来当「标准答案」校验前面三个
 *
 * 5 个实验：
 *   实验一：5 条日志走一遍时间轴，把「事件扫描」这件事看明白
 *   实验二：坑 1 —— 同一秒有人走也有人来，原版写法会把峰值时段拦腰截断
 *   实验三：坑 2 —— 一天里出现两段一样高的峰值，原版写法只认第一段
 *   实验四：50 万条日志，事件扫描 vs 差分数组 的真实耗时对比
 *   实验五：200 轮随机数据对拍，验证正解和差分数组跟笨办法答案完全一致
 * ============================================================
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class Demo {

    // ========================================================
    // 数据结构：三个小盒子
    // ========================================================

    /** 一条登录日志：谁、几点进来的、几点走的。时间是「今天的第几秒」，0 ~ 86400。 */
    record LoginLog(int userId, int loginTime, int logoutTime) {
    }

    /**
     * 一个「门口事件」。
     * 想象网吧门口有个刷卡机：有人刷卡进来记 +1，有人刷卡出去记 -1。
     * 我们不关心是谁，只关心「哪一秒，人数变化了多少」。
     */
    record Event(int time, int delta) {
    }

    /** 最终答案：最高同时在线人数 + 这个人数连续维持了几秒。 */
    record PeakResult(int maxOnline, int longestDuration) {
        @Override
        public String toString() {
            return "最大在线 " + maxOnline + " 人，最长维持 " + longestDuration + " 秒";
        }
    }

    // ========================================================
    // 公共步骤：把日志摊平成一条按时间排好队的「事件流」
    // ========================================================

    /**
     * 把 n 条日志变成 2n 个事件，并按时间从早到晚排好队。
     * 排序规则里有个讲究：时间相同时，先处理 -1（出），再处理 +1（进）。
     * 为什么？因为登出那一秒人已经不算在线了，
     * 如果先处理进的，就会出现「A 还没走 B 就进来了」的假重叠，人数虚高一个。
     */
    private static List<Event> buildSortedEvents(List<LoginLog> logs) {
        List<Event> events = new ArrayList<>(logs.size() * 2);
        for (LoginLog log : logs) {
            events.add(new Event(log.loginTime(), 1));   // 进门，人数 +1
            events.add(new Event(log.logoutTime(), -1)); // 出门，人数 -1
        }
        events.sort(Comparator
                .comparingInt(Event::time)        // 先按时间排
                .thenComparingInt(Event::delta)); // 同一秒里，-1 排在 +1 前面
        return events;
    }

    // ========================================================
    // 方法一：原题参考答案的原样写法（保留原貌，用于对照）
    // ========================================================

    /**
     * 原题给的代码，一字不改地照搬过来。
     * 它算「最大在线人数」是对的，但算「最长维持时间」有两个坑，
     * 实验二和实验三会把这两个坑演示出来。
     */
    static PeakResult scanLikeOriginalAnswer(List<LoginLog> logs) {
        List<Event> events = buildSortedEvents(logs);

        int online = 0;           // 当前在线人数
        int maxOnline = 0;        // 历史最高在线人数
        int maxDuration = 0;      // 峰值维持的最长时间
        int currentMaxStart = -1; // 当前这段峰值是从哪一秒开始的，-1 表示「现在不在峰值上」

        for (Event event : events) {
            online += event.delta;

            if (online > maxOnline) {
                // 破纪录了，从这一秒开始重新计时
                maxOnline = online;
                currentMaxStart = event.time;
            } else if (online < maxOnline && currentMaxStart != -1) {
                // 从峰值上掉下来了，结算这一段维持了多久
                maxDuration = Math.max(maxDuration, event.time - currentMaxStart);
                currentMaxStart = -1;
            }
        }
        return new PeakResult(maxOnline, maxDuration);
    }

    // ========================================================
    // 方法二：修正后的事件扫描线（本题正解）
    // ========================================================

    /**
     * 思路跟原题完全一样（拆事件 + 排序 + 扫一遍计数），只补了两处：
     *
     * 补丁 1：同一秒的所有事件要「打包一起算」。
     *        比如 8 秒时 A 走了、B 来了，人数其实没变。
     *        如果一个一个事件处理，中间会闪现一个「少一个人」的瞬间，
     *        程序就误以为峰值断了。打包成一次净变化就没这问题。
     *
     * 补丁 2：人数「回到」峰值时，要重新开始计时。
     *        原版只在 online > maxOnline（破纪录）时才记起点，
     *        所以一天里第二次冲到同样高度时，它压根没在计时。
     */
    static PeakResult scanByEvents(List<LoginLog> logs) {
        List<Event> events = buildSortedEvents(logs);

        int online = 0;
        int maxOnline = 0;
        int longestDuration = 0;
        int peakStartTime = -1; // -1 表示「当前不在峰值上」

        int index = 0;
        while (index < events.size()) {
            int currentTime = events.get(index).time();

            // 【补丁 1】把同一秒发生的所有进出合并成一次净变化
            int netChange = 0;
            while (index < events.size() && events.get(index).time() == currentTime) {
                netChange += events.get(index).delta();
                index++;
            }
            online += netChange;

            if (online > maxOnline) {
                // 破纪录：之前记的维持时间全部作废，从这一秒重新开始
                maxOnline = online;
                peakStartTime = currentTime;
                longestDuration = 0;
            } else if (online == maxOnline && peakStartTime == -1) {
                // 【补丁 2】又爬回峰值了，重新开始计时
                peakStartTime = currentTime;
            } else if (online < maxOnline && peakStartTime != -1) {
                // 掉下峰值：结算这一段，跟历史最长比一比
                longestDuration = Math.max(longestDuration, currentTime - peakStartTime);
                peakStartTime = -1;
            }
        }
        return new PeakResult(maxOnline, longestDuration);
    }

    // ========================================================
    // 方法三：差分数组（数据量特别大时更快）
    // ========================================================

    /**
     * 一天满打满算只有 86400 秒，与其排序 2n 个事件，
     * 不如直接摆 86400 个格子，一个格子代表一秒。
     *
     * 做法：登录那一格 +1，登出那一格 -1（先不管中间），
     *      最后从左到右做一次「累加」，每个格子的累加值就是那一秒的在线人数。
     * 这招叫差分数组，好比记账时只记「今天比昨天多几人」，
     * 想知道某天的总人数，从第一天一路加过来就行。
     *
     * 复杂度 O(n + 86400)，不用排序，比 O(n log n) 更省。
     */
    static PeakResult countByDiffArray(List<LoginLog> logs) {
        if (logs.isEmpty()) {
            return new PeakResult(0, 0);
        }
        // 真实场景直接写死 86401 即可，这里为了方便测试按数据算一下边界
        int lastSecond = 0;
        for (LoginLog log : logs) {
            lastSecond = Math.max(lastSecond, log.logoutTime());
        }

        int[] changeAtSecond = new int[lastSecond + 2];
        for (LoginLog log : logs) {
            changeAtSecond[log.loginTime()] += 1;  // 这一秒开始，多一个人
            changeAtSecond[log.logoutTime()] -= 1; // 这一秒开始，少一个人
        }

        int online = 0;
        int maxOnline = 0;
        int longestDuration = 0;
        int currentRunSeconds = 0; // 峰值已经连续维持了几秒

        for (int second = 0; second <= lastSecond; second++) {
            online += changeAtSecond[second]; // 累加，得到这一秒的真实在线人数

            if (online > maxOnline) {
                maxOnline = online;
                longestDuration = 0;
                currentRunSeconds = 0;
            }
            if (maxOnline > 0 && online == maxOnline) {
                currentRunSeconds++;
                longestDuration = Math.max(longestDuration, currentRunSeconds);
            } else {
                currentRunSeconds = 0;
            }
        }
        return new PeakResult(maxOnline, longestDuration);
    }

    // ========================================================
    // 方法四：笨办法（只当标准答案用，别在生产上跑）
    // ========================================================

    /**
     * 最直白的做法：每条日志覆盖的每一秒都去 +1 数一遍。
     * 数据量一大就会慢死（一个人挂 8 小时就要循环 28800 次），
     * 但胜在绝对不会错，正好拿来给前面几个方法当「参考答案」对拍。
     */
    static PeakResult bruteForce(List<LoginLog> logs) {
        if (logs.isEmpty()) {
            return new PeakResult(0, 0);
        }
        int lastSecond = 0;
        for (LoginLog log : logs) {
            lastSecond = Math.max(lastSecond, log.logoutTime());
        }

        int[] onlineEachSecond = new int[lastSecond + 1];
        for (LoginLog log : logs) {
            // 注意区间是左闭右开 [登录, 登出)：登出那一秒人已经走了，不算在线
            for (int second = log.loginTime(); second < log.logoutTime(); second++) {
                onlineEachSecond[second]++;
            }
        }

        int maxOnline = 0;
        for (int count : onlineEachSecond) {
            maxOnline = Math.max(maxOnline, count);
        }

        int longestDuration = 0;
        int currentRunSeconds = 0;
        if (maxOnline > 0) {
            for (int count : onlineEachSecond) {
                if (count == maxOnline) {
                    currentRunSeconds++;
                    longestDuration = Math.max(longestDuration, currentRunSeconds);
                } else {
                    currentRunSeconds = 0;
                }
            }
        }
        return new PeakResult(maxOnline, longestDuration);
    }

    // ========================================================
    // 实验一：5 条日志走一遍时间轴
    // ========================================================

    private static void experimentWalkThrough() {
        System.out.println("=== 实验一：5 条日志，手把手走一遍时间轴 ===");
        List<LoginLog> logs = List.of(
                new LoginLog(1, 0, 10),
                new LoginLog(2, 3, 8),
                new LoginLog(3, 5, 12),
                new LoginLog(4, 8, 15),
                new LoginLog(5, 20, 25)
        );
        System.out.println("原始日志（左闭右开，登出那一秒不算在线）：");
        for (LoginLog log : logs) {
            System.out.println("  用户" + log.userId() + "  在线区间 [" + log.loginTime()
                    + ", " + log.logoutTime() + ")");
        }

        System.out.println("拆成事件流后，一秒一秒地扫：");
        List<Event> events = buildSortedEvents(logs);
        int online = 0;
        int index = 0;
        while (index < events.size()) {
            int currentTime = events.get(index).time();
            int netChange = 0;
            int comeIn = 0;
            int goOut = 0;
            while (index < events.size() && events.get(index).time() == currentTime) {
                int delta = events.get(index).delta();
                netChange += delta;
                if (delta > 0) {
                    comeIn++;
                } else {
                    goOut++;
                }
                index++;
            }
            online += netChange;
            String bar = "#".repeat(Math.max(online, 0));
            System.out.printf("  第 %2d 秒：进 %d 人 出 %d 人 -> 在线 %d 人  %s%n",
                    currentTime, comeIn, goOut, online, bar);
        }

        System.out.println("肉眼可见：第 5 秒冲到 3 人，一直撑到第 10 秒才掉下来，所以维持了 5 秒。");
        System.out.println("  正解算出来 : " + scanByEvents(logs));
        System.out.println("  笨办法验证 : " + bruteForce(logs));
        System.out.println();
    }

    // ========================================================
    // 实验二：坑 1 —— 同一秒有人走也有人来
    // ========================================================

    private static void experimentSameSecondSwap() {
        System.out.println("=== 实验二：坑 1，同一秒『一个走、一个来』 ===");
        // 还是实验一那份数据：第 8 秒用户2 走、用户4 来，人数其实没变，一直是 3 人
        List<LoginLog> logs = List.of(
                new LoginLog(1, 0, 10),
                new LoginLog(2, 3, 8),
                new LoginLog(3, 5, 12),
                new LoginLog(4, 8, 15),
                new LoginLog(5, 20, 25)
        );
        System.out.println("第 8 秒：用户2 离开的同一秒，用户4 进来了，在线人数其实纹丝不动。");
        System.out.println("  原题原版写法 : " + scanLikeOriginalAnswer(logs) + "   <-- 维持时间偏小");
        System.out.println("  修正后的正解 : " + scanByEvents(logs));
        System.out.println("  笨办法(标准答案) : " + bruteForce(logs));
        System.out.println("原因：原版一个事件一个事件地处理，先算 -1 就出现了『在线 2 人』的假瞬间，");
        System.out.println("     程序以为峰值断在第 8 秒，只记了 8-5=3 秒。把同一秒打包算净变化就好了。");
        System.out.println();
    }

    // ========================================================
    // 实验三：坑 2 —— 一天里两段一样高的峰值
    // ========================================================

    private static void experimentTwoEqualPeaks() {
        System.out.println("=== 实验三：坑 2，一天出现两段一样高的峰值 ===");
        // 上午 0~100 秒有 2 人；下午 200~400 秒又有 2 人，第二段明显更长
        List<LoginLog> logs = List.of(
                new LoginLog(1, 0, 100),
                new LoginLog(2, 0, 100),
                new LoginLog(3, 200, 400),
                new LoginLog(4, 200, 400)
        );
        System.out.println("上午段：2 人在线，[0, 100)   共 100 秒");
        System.out.println("下午段：2 人在线，[200, 400) 共 200 秒  <-- 这段才是最长的");
        System.out.println("  原题原版写法 : " + scanLikeOriginalAnswer(logs) + "   <-- 只认了上午那段");
        System.out.println("  修正后的正解 : " + scanByEvents(logs));
        System.out.println("  笨办法(标准答案) : " + bruteForce(logs));
        System.out.println("原因：原版只在『破纪录』时才记起点，下午再次冲到 2 人时不算破纪录，");
        System.out.println("     它就没开始计时。加一句『等于峰值也要重新计时』即可。");
        System.out.println();
    }

    // ========================================================
    // 实验四：50 万条日志，两种正确解法的耗时对比
    // ========================================================

    private static void experimentPerformance() {
        System.out.println("=== 实验四：50 万条日志，事件扫描 vs 差分数组 ===");
        int logCount = 500_000;
        List<LoginLog> logs = randomLogs(logCount, 86400, 20260808L);

        long start = System.nanoTime();
        PeakResult scanResult = scanByEvents(logs);
        long scanCostMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        PeakResult diffResult = countByDiffArray(logs);
        long diffCostMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("  事件扫描线（要排序 " + (logCount * 2) + " 个事件）: "
                + scanResult + "，耗时 " + scanCostMs + " ms");
        System.out.println("  差分数组（86401 个格子累加）        : "
                + diffResult + "，耗时 " + diffCostMs + " ms");
        System.out.println("  两者答案一致？ " + (scanResult.equals(diffResult) ? "一致" : "不一致，有 BUG"));
        if (diffCostMs > 0) {
            System.out.printf("  差分数组快约 %.1f 倍（省掉了排序）%n", scanCostMs * 1.0 / diffCostMs);
        } else {
            System.out.println("  差分数组耗时不足 1 ms，排序那步的开销全省了");
        }
        System.out.println("  提醒：差分数组能用的前提是『时间范围有限且已知』（一天 86400 秒）。");
        System.out.println("       如果时间跨度是几年、精确到毫秒，格子开不下，还得回去用事件扫描。");
        System.out.println();
    }

    // ========================================================
    // 实验五：随机对拍，证明正解真的没写错
    // ========================================================

    private static void experimentRandomCheck() {
        System.out.println("=== 实验五：200 轮随机数据对拍 ===");
        Random random = new Random(42);
        int rounds = 200;
        int scanWrong = 0;
        int diffWrong = 0;
        int originalWrong = 0;

        for (int round = 0; round < rounds; round++) {
            List<LoginLog> logs = randomLogs(1 + random.nextInt(60), 200, random.nextLong());
            PeakResult expected = bruteForce(logs);
            if (!scanByEvents(logs).equals(expected)) {
                scanWrong++;
            }
            if (!countByDiffArray(logs).equals(expected)) {
                diffWrong++;
            }
            if (!scanLikeOriginalAnswer(logs).equals(expected)) {
                originalWrong++;
            }
        }
        System.out.println("  修正后的事件扫描 : 错 " + scanWrong + " / " + rounds + " 轮");
        System.out.println("  差分数组         : 错 " + diffWrong + " / " + rounds + " 轮");
        System.out.println("  原题原版写法     : 错 " + originalWrong + " / " + rounds + " 轮  <-- 就是那两个坑");
        System.out.println("  说明：随机造数据 + 笨办法当标准答案对拍，是验证这类算法最省事的办法。");
        System.out.println();
    }

    // ========================================================
    // 工具：造随机日志
    // ========================================================

    /** 造一批随机日志：随机时间进来，随机待一会儿（最长 1 小时）再走。 */
    private static List<LoginLog> randomLogs(int count, int daySeconds, long seed) {
        Random random = new Random(seed);
        List<LoginLog> logs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int loginTime = random.nextInt(daySeconds);
            int staySeconds = 1 + random.nextInt(Math.min(3600, daySeconds));
            int logoutTime = Math.min(daySeconds, loginTime + staySeconds);
            if (logoutTime <= loginTime) {
                logoutTime = loginTime + 1; // 兜底，保证至少在线 1 秒
            }
            logs.add(new LoginLog(i, loginTime, logoutTime));
        }
        return logs;
    }

    /**
     * JIT 热身：Java 刚启动时代码是「解释执行」的，跑几遍之后才会被编译成机器码提速。
     * 不热身直接计时，第一个跑的方法会被冤枉成「很慢」。
     */
    private static void warmUp() {
        List<LoginLog> logs = randomLogs(20_000, 86400, 1L);
        for (int i = 0; i < 3; i++) {
            scanByEvents(logs);
            countByDiffArray(logs);
            scanLikeOriginalAnswer(logs);
        }
    }

    public static void main(String[] args) {
        warmUp();

        experimentWalkThrough();
        experimentSameSecondSwap();
        experimentTwoEqualPeaks();
        experimentPerformance();
        experimentRandomCheck();

        System.out.println("=== 一句话总结 ===");
        System.out.println("把『一段一段的在线区间』拆成『门口的进出事件』，排好队扫一遍，");
        System.out.println("计数器的最高点就是最大在线人数；时间范围固定的话，差分数组连排序都能省。");
    }
}
