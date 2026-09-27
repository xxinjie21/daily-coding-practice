/*
 * 最大在线人数：一堆登录日志，怎么求出峰值和它撑了多久
 * ----------------------------------------------------------------------------
 * 题目：日志是 {用户id, 登录时间, 登出时间}（秒），求同一时刻最多有多少人在线、这个峰值连续维持了多久。
 * 思路：把每条日志拆成两个「门口事件」（登录 +1、登出 -1），按时间排队后从头扫一遍，用计数器记峰值。
 *
 * 四个方法：scanLikeOriginalAnswer（原题原样写法，用来暴露它的两个坑）/ scanByEvents（修正后的事件扫描线，
 * 本题正解）/ countByDiffArray（差分数组，一天只有 86400 秒，可以不排序）/ bruteForce（笨办法，当标准答案对拍）。
 * 无任何外部依赖，`mvn -q compile && mvn -q exec:java` 直接跑。
 */

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class Demo {

    /** 一条登录日志：谁、几点进来的、几点走的。时间是「今天的第几秒」，区间左闭右开 [进, 出)。 */
    record LoginLog(int userId, int loginTime, int logoutTime) {
    }

    /** 一个「门口事件」：不关心是谁，只关心「哪一秒，人数变化了多少」。 */
    record Event(int time, int delta) {
    }

    /** 最终答案：最高同时在线人数 + 这个人数连续维持了几秒。 */
    record PeakResult(int maxOnline, int longestDuration) {
        @Override
        public String toString() {
            return "最大在线 " + maxOnline + " 人，最长维持 " + longestDuration + " 秒";
        }
    }

    /**
     * 把 n 条日志变成 2n 个事件，并按时间排好队。排序规则有个讲究：同一秒里先处理 -1（出）再处理 +1（进）。
     * 因为登出那一秒人已经不算在线了，先算进门会出现「A 还没走 B 就进来了」的假重叠，人数虚高。
     */
    static List<Event> buildSortedEvents(List<LoginLog> logs) {
        List<Event> events = new ArrayList<>(logs.size() * 2);
        for (LoginLog log : logs) {
            events.add(new Event(log.loginTime(), 1));
            events.add(new Event(log.logoutTime(), -1));
        }
        events.sort(Comparator.comparingInt(Event::time).thenComparingInt(Event::delta));
        return events;
    }

    /**
     * 原题参考答案的原样写法：算「最大在线人数」是对的，但算「最长维持时间」有两个坑 ——
     * 同一秒一进一出会把峰值拦腰截断；一天里两段等高的峰值只认第一段。
     */
    static PeakResult scanLikeOriginalAnswer(List<LoginLog> logs) {
        int online = 0;
        int maxOnline = 0;
        int maxDuration = 0;
        int currentMaxStart = -1;
        for (Event event : buildSortedEvents(logs)) {
            online += event.delta();
            if (online > maxOnline) {
                maxOnline = online;
                currentMaxStart = event.time();
            } else if (online < maxOnline && currentMaxStart != -1) {
                maxDuration = Math.max(maxDuration, event.time() - currentMaxStart);
                currentMaxStart = -1;
            }
        }
        return new PeakResult(maxOnline, maxDuration);
    }

    /**
     * 修正后的事件扫描线（本题正解）。思路和原题一样，只补了两处：
     *   补丁1：同一秒的所有事件打包成一次净变化再判断，否则中间会闪现一个「少一个人」的假瞬间；
     *   补丁2：人数「回到」峰值时也要重新开始计时，否则一天里第二次冲到同样高度时压根没在计时。
     */
    static PeakResult scanByEvents(List<LoginLog> logs) {
        List<Event> events = buildSortedEvents(logs);
        int online = 0;
        int maxOnline = 0;
        int longestDuration = 0;
        int peakStartTime = -1;   // -1 表示「当前不在峰值上」

        int index = 0;
        while (index < events.size()) {
            int currentTime = events.get(index).time();
            int netChange = 0;
            while (index < events.size() && events.get(index).time() == currentTime) {
                netChange += events.get(index).delta();
                index++;
            }
            online += netChange;

            if (online > maxOnline) {
                maxOnline = online;
                peakStartTime = currentTime;
                longestDuration = 0;
            } else if (online == maxOnline && peakStartTime == -1) {
                peakStartTime = currentTime;
            } else if (online < maxOnline && peakStartTime != -1) {
                longestDuration = Math.max(longestDuration, currentTime - peakStartTime);
                peakStartTime = -1;
            }
        }
        return new PeakResult(maxOnline, longestDuration);
    }

    /**
     * 差分数组：一天满打满算 86400 秒，时间范围固定且已知，那还排什么序？直接摆格子，登录那一格 +1、
     * 登出那一格 -1，最后从左到右累加一遍，每格的累加值就是那一秒的真实在线人数。复杂度 O(n + 86400)。
     */
    static PeakResult countByDiffArray(List<LoginLog> logs) {
        if (logs.isEmpty()) {
            return new PeakResult(0, 0);
        }
        int[] changeAtSecond = new int[lastSecondOf(logs) + 2];
        for (LoginLog log : logs) {
            changeAtSecond[log.loginTime()] += 1;
            changeAtSecond[log.logoutTime()] -= 1;
        }
        return scanPerSecondCounts(changeAtSecond, true);
    }

    /** 笨办法：每条日志覆盖的每一秒都 +1 数一遍。数据量一大就慢死，但绝对不会错，正好拿来当「参考答案」对拍。 */
    static PeakResult bruteForce(List<LoginLog> logs) {
        if (logs.isEmpty()) {
            return new PeakResult(0, 0);
        }
        int[] onlineEachSecond = new int[lastSecondOf(logs) + 1];
        for (LoginLog log : logs) {
            // 区间是左闭右开 [登录, 登出)：登出那一秒人已经走了，不算在线
            for (int second = log.loginTime(); second < log.logoutTime(); second++) {
                onlineEachSecond[second]++;
            }
        }
        return scanPerSecondCounts(onlineEachSecond, false);
    }

    /** 扫一遍每秒的人数，记下峰值和最长连续维持秒数。deltas=true 表示传进来的是差分数组，要先累加。 */
    private static PeakResult scanPerSecondCounts(int[] counts, boolean deltas) {
        int online = 0;
        int maxOnline = 0;
        int longest = 0;
        int runSeconds = 0;
        for (int value : counts) {
            online += deltas ? value : 0;
            int current = deltas ? online : value;
            if (current > maxOnline) {
                maxOnline = current;
                runSeconds = 0;
                longest = 0;
            }
            if (maxOnline > 0 && current == maxOnline) {
                runSeconds++;
                longest = Math.max(longest, runSeconds);
            } else {
                runSeconds = 0;
            }
        }
        return new PeakResult(maxOnline, longest);
    }

    private static int lastSecondOf(List<LoginLog> logs) {
        return logs.stream().mapToInt(LoginLog::logoutTime).max().orElse(0);
    }

    static void experimentWalkThrough() {
        title("实验一：5 条日志，手把手走一遍时间轴");
        List<LoginLog> logs = List.of(
                new LoginLog(1, 0, 10), new LoginLog(2, 3, 8), new LoginLog(3, 5, 12),
                new LoginLog(4, 8, 15), new LoginLog(5, 20, 25));
        System.out.println("拆成事件流后，一秒一秒地扫（原始区间左闭右开，登出那一秒不算在线）：");
        List<Event> events = buildSortedEvents(logs);
        int online = 0;
        int index = 0;
        while (index < events.size()) {
            int currentTime = events.get(index).time();
            int netChange = 0;
            int comeIn = 0;
            while (index < events.size() && events.get(index).time() == currentTime) {
                int delta = events.get(index).delta();
                netChange += delta;
                if (delta > 0) {
                    comeIn++;
                }
                index++;
            }
            online += netChange;
            System.out.printf("  第 %2d 秒：进 %d 人 出 %d 人 -> 在线 %d 人  %s%n",
                    currentTime, comeIn, comeIn - netChange, online, "#".repeat(Math.max(online, 0)));
        }
        System.out.println("肉眼可见：第 5 秒冲到 3 人，一直撑到第 10 秒才掉下来，所以维持了 5 秒。");
        System.out.println("  正解算出来 : " + scanByEvents(logs));
        System.out.println("  笨办法验证 : " + bruteForce(logs));
    }

    static void experimentTwoBugs() {
        title("实验二、三：原题参考答案的两个坑");
        List<LoginLog> swapSameSecond = List.of(
                new LoginLog(1, 0, 10), new LoginLog(2, 3, 8), new LoginLog(3, 5, 12),
                new LoginLog(4, 8, 15), new LoginLog(5, 20, 25));
        System.out.println("坑1 —— 第 8 秒用户2 离开的同一秒用户4 进来了，在线人数纹丝不动，一直是 3 人：");
        System.out.println("  原版 : " + scanLikeOriginalAnswer(swapSameSecond) + "   <-- 只记了 8-5=3 秒");
        System.out.println("  正解 : " + scanByEvents(swapSameSecond));
        System.out.println("  原因：原版一个事件一个事件地处理，先算 -1 就出现了『在线 2 人』的假瞬间。");

        List<LoginLog> twoEqualPeaks = List.of(
                new LoginLog(1, 0, 100), new LoginLog(2, 0, 100),
                new LoginLog(3, 200, 400), new LoginLog(4, 200, 400));
        System.out.println("坑2 —— 上午 [0,100) 有 2 人，下午 [200,400) 又有 2 人，下午这段明显更长：");
        System.out.println("  原版 : " + scanLikeOriginalAnswer(twoEqualPeaks) + "   <-- 只认了上午那段");
        System.out.println("  正解 : " + scanByEvents(twoEqualPeaks));
        System.out.println("  原因：原版只在『破纪录』时才记起点，下午再次冲到 2 人时不算破纪录，它就没开始计时。");
        System.out.println("  两个坑的共同修法：同秒打包算净变化；等于峰值时也重新计时。");
    }

    static void experimentPerformance() {
        title("实验四：50 万条日志，事件扫描 vs 差分数组");
        List<LoginLog> logs = randomLogs(500_000, 86400, 20260808L);

        long start = System.nanoTime();
        PeakResult scanResult = scanByEvents(logs);
        long scanCostMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        PeakResult diffResult = countByDiffArray(logs);
        long diffCostMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("  事件扫描线（要排序 100 万个事件）: " + scanResult + "，耗时 " + scanCostMs + " ms");
        System.out.println("  差分数组（86401 个格子累加）      : " + diffResult + "，耗时 " + diffCostMs + " ms");
        System.out.println("  两者答案一致？ " + (scanResult.equals(diffResult) ? "一致" : "不一致，有 BUG"));
        System.out.println("  提醒：差分数组的前提是『时间范围有限且已知』；跨年、精确到毫秒时格子开不下，还得用事件扫描。");
    }

    static void experimentRandomCheck() {
        title("实验五：200 轮随机数据对拍");
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
        System.out.println("  随机造数据 + 笨办法当标准答案对拍，是验证这类算法最省事的办法。");
    }

    /** 造一批随机日志：随机时间进来，随机待一会儿（最长 1 小时）再走。 */
    static List<LoginLog> randomLogs(int count, int daySeconds, long seed) {
        Random random = new Random(seed);
        List<LoginLog> logs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int loginTime = random.nextInt(daySeconds);
            int staySeconds = 1 + random.nextInt(Math.min(3600, daySeconds));
            int logoutTime = Math.min(daySeconds, loginTime + staySeconds);
            logs.add(new LoginLog(i, loginTime, Math.max(logoutTime, loginTime + 1)));
        }
        return logs;
    }

    /** JIT 热身：不热身直接计时，第一个跑的方法会被冤枉成「很慢」。 */
    static void warmUp() {
        List<LoginLog> logs = randomLogs(20_000, 86400, 1L);
        for (int i = 0; i < 3; i++) {
            scanByEvents(logs);
            countByDiffArray(logs);
            scanLikeOriginalAnswer(logs);
        }
    }

    static void title(String text) {
        System.out.println();
        System.out.println("======================================================================");
        System.out.println(text);
        System.out.println("======================================================================");
    }

    public static void main(String[] args) {
        warmUp();
        experimentWalkThrough();
        experimentTwoBugs();
        experimentPerformance();
        experimentRandomCheck();
        System.out.println("\n=== 一句话总结 ===");
        System.out.println("把「一段一段的在线区间」拆成「门口的进出事件」，排好队扫一遍，计数器的最高点就是最大在线人数；");
        System.out.println("时间范围固定的话，差分数组连排序都能省。");
    }
}
