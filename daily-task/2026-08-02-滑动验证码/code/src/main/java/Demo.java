// 编译运行：mvn -q compile exec:java
//
// 把「滑动验证码」的服务端逻辑跑一遍：不画图（画图是前端 Canvas 的活），只演示后端拿到一串鼠标
// 轨迹后怎么判决。用真实 Redis（Jedis）存两样东西：
//   captcha:answer:<token> —— 这道题的正确答案 gapX，TTL 5 分钟，绝不随接口下发给前端
//   captcha:fail:<ip>      —— 这个 IP 累计失败几次，TTL 5 分钟，用来风控封禁
// 三层校验：① 小票有效吗（GETDEL 取出即删，防重放）② 落点准不准（误差 5px 内）③ 轨迹像不像人。
// 行为规则：点数 < 10 判机器；均速 > 50px/100ms 可疑；间隔整齐得反常、y 全程不动，也判机器。
// 七个场景：真人通过 / 机器匀速被拦 / 放慢的机器人仍被拦 / 位置拖错 / 重放 / 过期 / IP 被封。
//
// 前置条件：本机 6379 有 Redis。在本目录执行 `docker compose up -d` 即可。

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class Demo {

    static final String REDIS_HOST = "localhost";
    static final int REDIS_PORT = 6379;

    public static void main(String[] args) {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            clearLeftovers(jedis);
            CaptchaServer server = new CaptchaServer(jedis);
            System.out.println("=========== 滑动验证码服务端演示 ===========");

            runCase(server, "10.0.0.1", "场景 1：真人拖动（有犹豫、有手抖、有变速）", false, 0, 0);
            runCase(server, "10.0.0.2", "场景 2：机器脚本（位置极准，但匀速直线）", true, 10, 0);
            runCase(server, "10.0.0.9", "场景 3：进阶机器人（放慢到人的速度，仍是匀速）", true, 40, 0);
            runCase(server, "10.0.0.3", "场景 4：真人拖歪了（差 20 像素）", false, 0, -20);

            scenario5_replay(server);
            scenario6_expired(server);
            scenario7_blockedIp(server);
        }
    }

    /** 清掉上一轮遗留的 key（真实环境用 KEYS 扫描是大忌，这里只是本地演示图省事） */
    static void clearLeftovers(Jedis jedis) {
        Set<String> keys = jedis.keys("captcha:*");
        if (!keys.isEmpty()) {
            jedis.del(keys.toArray(new String[0]));
        }
    }

    /** 一个标准场景：出题 → 造轨迹 → 提交 → 打印结论。xError 是故意拖偏的像素 */
    static void runCase(CaptchaServer server, String ip, String title, boolean robot, int millisPerStep,
                        int xError) {
        printTitle(title);
        Challenge challenge = server.createChallenge(ip);
        int targetX = server.peekAnswerForDemo(challenge.token()) + xError;
        List<TrackPoint> track = robot
                ? TrackGenerator.robot(targetX, millisPerStep)
                : TrackGenerator.human(targetX);
        System.out.println("  轨迹：共 " + track.size() + " 个点，落点 x=" + lastX(track));
        printResult(server.verify(challenge.token(), lastX(track), track, ip));
    }

    /** 场景 5：重放 —— 小票用完即废，原样再发一遍必然落空 */
    static void scenario5_replay(CaptchaServer server) {
        printTitle("场景 5：重放攻击（把刚才成功的请求原样再发一次）");
        String ip = "10.0.0.4";
        Challenge challenge = server.createChallenge(ip);
        int targetX = server.peekAnswerForDemo(challenge.token());
        List<TrackPoint> track = TrackGenerator.human(targetX);
        System.out.println("  第一次提交：");
        printResult(server.verify(challenge.token(), lastX(track), track, ip));
        System.out.println("  第二次提交（完全一样的 token + 完全一样的轨迹）：");
        printResult(server.verify(challenge.token(), lastX(track), track, ip));
    }

    /** 场景 6：小票过期 —— 不用真等 5 分钟，把 TTL 压到 100 毫秒即可 */
    static void scenario6_expired(CaptchaServer server) {
        printTitle("场景 6：小票过期（TTL 到点，Redis 自动删除）");
        String ip = "10.0.0.5";
        Challenge challenge = server.createChallenge(ip);
        int targetX = server.peekAnswerForDemo(challenge.token());
        System.out.println("  出题时 TTL = " + server.ttlSeconds(challenge.token()) + " 秒");
        server.shrinkTtlForDemo(challenge.token());
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        List<TrackPoint> track = TrackGenerator.human(targetX);
        printResult(server.verify(challenge.token(), lastX(track), track, ip));
    }

    /** 场景 7：风控 —— 同一 IP 反复用机器脚本试，失败 5 次后连题都不给出了 */
    static void scenario7_blockedIp(CaptchaServer server) {
        printTitle("场景 7：同一 IP 用机器脚本反复试（风控封禁）");
        String attackerIp = "66.66.66.66";
        for (int attempt = 1; attempt <= 7; attempt++) {
            Challenge challenge = server.createChallenge(attackerIp);
            if (challenge == null) {
                System.out.println("  第 " + attempt + " 次：连题都不给出了 —— 该 IP 已被风控封禁");
                continue;
            }
            int targetX = server.peekAnswerForDemo(challenge.token());
            List<TrackPoint> track = TrackGenerator.robot(targetX, 10);
            System.out.println("  第 " + attempt + " 次："
                    + server.verify(challenge.token(), targetX, track, attackerIp).reason());
        }
        System.out.println("  → 失败 5 次后直接闭门谢客，机器没法无限次试错。");
    }

    /** 验证码服务端。答案只写进 Redis，接口返回给前端的只有 token。 */
    static class CaptchaServer {

        static final int IMAGE_WIDTH = 300;          // 图片宽度，滑块能拖动的范围
        static final int POSITION_TOLERANCE = 5;     // 落点误差 5 像素内算拼对，人眼没那么精确
        static final int TOKEN_TTL_SECONDS = 300;    // 小票有效期 5 分钟
        static final int MAX_FAIL_PER_IP = 5;        // 同一 IP 失败超过这个数就封禁

        final Jedis jedis;
        final Random random = new Random(20260802L); // 固定种子，方便结果可复现

        CaptchaServer(Jedis jedis) {
            this.jedis = jedis;
        }

        /** 出题：缺口位置每次随机（固定模板会被机器存下来比对），答案只留在 Redis */
        Challenge createChallenge(String clientIp) {
            if (isBlocked(clientIp)) {
                return null;
            }
            int gapX = 80 + random.nextInt(IMAGE_WIDTH - 120);
            String token = UUID.randomUUID().toString();
            jedis.set(answerKey(token), String.valueOf(gapX), SetParams.setParams().ex(TOKEN_TTL_SECONDS));
            return new Challenge(token);
        }

        /** 三层校验，任何一层不过都算失败 */
        VerifyResult verify(String token, int releaseX, List<TrackPoint> track, String clientIp) {
            if (isBlocked(clientIp)) {
                return new VerifyResult(false, "该 IP 已被风控封禁");
            }

            // 第 1 层：GETDEL 把答案取出来顺手删掉 —— 小票用完即废，录屏重放必然落空
            String answer = jedis.getDel(answerKey(token));
            if (answer == null) {
                recordFail(clientIp);
                return new VerifyResult(false, "小票不存在、已过期或已被用过（疑似重放）");
            }

            // 第 2 层：位置拼对了吗
            int offset = Math.abs(releaseX - Integer.parseInt(answer));
            if (offset > POSITION_TOLERANCE) {
                recordFail(clientIp);
                return new VerifyResult(false, "位置不对，差了 " + offset + " 像素（容差 " + POSITION_TOLERANCE + "）");
            }

            // 第 3 层：这串轨迹像人拖的吗。位置可以算准，手感很难伪造
            String suspicion = BehaviorAnalyzer.check(track);
            if (suspicion != null) {
                recordFail(clientIp);
                return new VerifyResult(false, "位置虽对，但行为可疑：" + suspicion);
            }
            return new VerifyResult(true, "位置准确（差 " + offset + "px），且行为特征像真人");
        }

        /** 演示用：偷看答案，好让我们能模拟用户把滑块拖到正确位置。真实服务端没有这个方法 */
        int peekAnswerForDemo(String token) {
            return Integer.parseInt(jedis.get(answerKey(token)));
        }

        /** 演示用：把 TTL 压到 100 毫秒，等价于「出题后已经过了 5 分钟」 */
        void shrinkTtlForDemo(String token) {
            jedis.pexpire(answerKey(token), 100);
        }

        long ttlSeconds(String token) {
            return jedis.ttl(answerKey(token));
        }

        void recordFail(String clientIp) {
            String key = "captcha:fail:" + clientIp;
            jedis.incr(key);
            jedis.expire(key, TOKEN_TTL_SECONDS);    // 5 分钟内不再失败就自动清零
        }

        boolean isBlocked(String clientIp) {
            String value = jedis.get("captcha:fail:" + clientIp);
            return value != null && Integer.parseInt(value) >= MAX_FAIL_PER_IP;
        }

        static String answerKey(String token) {
            return "captcha:answer:" + token;
        }
    }

    /** 行为分析器：光看落点没用，得看「拖的过程」。返回 null 表示像真人，否则返回可疑原因。 */
    static class BehaviorAnalyzer {

        static final int MIN_TRACK_POINTS = 10;               // 人拖 100 多像素，怎么也得几十个点
        static final double MAX_SPEED_PX_PER_100MS = 50.0;    // 平均速度上限
        static final double MIN_INTERVAL_STD_DEV = 3.0;       // 间隔波动小于它 = 太平滑 = 机器

        static String check(List<TrackPoint> track) {
            if (track.size() < MIN_TRACK_POINTS) return "轨迹只有 " + track.size() + " 个点，人不可能一步到位";

            TrackPoint first = track.get(0);
            TrackPoint last = track.get(track.size() - 1);
            long totalMillis = last.timeMillis() - first.timeMillis();
            if (totalMillis <= 0) return "总耗时为 0，时间戳明显是伪造的";

            double speed = Math.abs(last.x() - first.x()) * 100.0 / totalMillis;
            if (speed > MAX_SPEED_PX_PER_100MS) {
                return String.format("平均速度 %.1f px/100ms，超过阈值 %.0f（手甩不了这么快）",
                        speed, MAX_SPEED_PX_PER_100MS);
            }
            // 人：28、35、31、47、22……忽快忽慢；机器：10、10、10、10……整齐得反常
            double stdDev = intervalStdDev(track);
            if (stdDev < MIN_INTERVAL_STD_DEV) {
                return String.format("每一步间隔标准差只有 %.2f ms，整齐得不像人手（阈值 %.0f）",
                        stdDev, MIN_INTERVAL_STD_DEV);
            }
            if (track.stream().noneMatch(point -> point.y() != 0)) return "全程 y 坐标一动不动，是标准直线，真人手会抖";
            return null;
        }

        /** 相邻两点时间间隔的标准差，用来衡量「节奏是否忽快忽慢」 */
        static double intervalStdDev(List<TrackPoint> track) {
            int intervalCount = track.size() - 1;
            double sum = 0;
            for (int i = 1; i < track.size(); i++) sum += track.get(i).timeMillis() - track.get(i - 1).timeMillis();
            double average = sum / intervalCount;

            double sumOfSquaredDiff = 0;
            for (int i = 1; i < track.size(); i++) {
                double diff = (track.get(i).timeMillis() - track.get(i - 1).timeMillis()) - average;
                sumOfSquaredDiff += diff * diff;
            }
            return Math.sqrt(sumOfSquaredDiff / intervalCount);
        }
    }

    /** 轨迹生成器：真实环境里这些点是浏览器 mousemove 事件产生的，这里造两种典型样本 */
    static class TrackGenerator {

        static final Random RANDOM = new Random(999L);

        /** 真人：起步慢、中间快、快到位再慢下来找准，中途会犹豫，y 轴小幅抖动 */
        static List<TrackPoint> human(int targetX) {
            List<TrackPoint> track = new ArrayList<>();
            track.add(new TrackPoint(0, 0, 0));

            int currentX = 0;
            long currentTime = 0;
            while (currentX < targetX) {
                int remaining = targetX - currentX;
                int step = currentX < targetX * 0.2 ? 2 + RANDOM.nextInt(4)   // 起步：小步试探
                        : remaining > 20 ? 6 + RANDOM.nextInt(7)             // 中段：大步推
                        : 1 + RANDOM.nextInt(3);                             // 收尾：慢慢对齐
                currentX += Math.min(step, remaining);

                currentTime += 18 + RANDOM.nextInt(35);                      // 每步耗时忽长忽短
                if (RANDOM.nextInt(12) == 0) {
                    currentTime += 120 + RANDOM.nextInt(200);                // 偶尔犹豫一下
                }
                track.add(new TrackPoint(currentX, RANDOM.nextInt(5) - 2, currentTime));
            }
            return track;
        }

        /** 机器：图像识别算准缺口后匀速直线推过去，每步间隔一模一样、y 恒为 0 */
        static List<TrackPoint> robot(int targetX, int millisPerStep) {
            List<TrackPoint> track = new ArrayList<>();
            int stepCount = 12;   // 故意给够 12 个点，绕过「点数太少」这条规则
            for (int i = 0; i <= stepCount; i++) {
                int x = (i == stepCount) ? targetX : i * (targetX / stepCount);
                track.add(new TrackPoint(x, 0, (long) i * millisPerStep));
            }
            return track;
        }
    }

    /** 一个鼠标轨迹点：横坐标、纵坐标、距离开始拖动过了多少毫秒 */
    record TrackPoint(int x, int y, long timeMillis) {}

    /** 发给前端的东西：只有小票，【没有答案】 */
    record Challenge(String token) {}

    record VerifyResult(boolean passed, String reason) {}

    static void printTitle(String title) {
        System.out.println("\n----- " + title + " -----");
    }

    static void printResult(VerifyResult result) {
        System.out.println((result.passed() ? "  [通过] " : "  [拦截] ") + result.reason() + "\n");
    }

    static int lastX(List<TrackPoint> track) {
        return track.get(track.size() - 1).x();
    }
}
