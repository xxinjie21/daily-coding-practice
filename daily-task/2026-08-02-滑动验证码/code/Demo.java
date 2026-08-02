// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   用纯 Java 把「滑动验证码」的服务端逻辑跑一遍，看看它是怎么分辨"人"和"机器"的。
//   注意：这里不画图（画图是前端 Canvas 的活儿），我们只演示后端最关键的那部分——
//   拿到一串鼠标轨迹后，服务端怎么判决。
//
// 演示原题给出的这几个方法：
//   1）出题时缺口位置随机，答案只留在服务端（绝不下发给前端）
//   2）一次性 token（好比排队小票），用完即废、5 分钟过期 —— 防重放
//   3）三层校验：token 有效？落点准不准？轨迹像不像人？
//   4）行为规则按原题：轨迹点 < 10 个判机器；平均速度 > 50px/100ms 可疑；轨迹过于平滑可疑
//   5）风控：同一 IP 短时间内失败超过 5 次就封禁
//
// 一共跑 7 个场景：
//   真人通过 / 机器狂拖被拦 / 机器放慢仍被拦 / 位置拖错 / 重放小票 / 小票过期 / IP 连续失败被封

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class Demo {

    public static void main(String[] args) {
        CaptchaServer server = new CaptchaServer();

        System.out.println("=========== 滑动验证码服务端演示 ===========\n");

        场景1_真人拖动应该通过(server);
        场景2_机器匀速拖动应该被拦(server);
        场景3_进阶机器人放慢速度仍被拦(server);
        场景4_位置拖错应该失败(server);
        场景5_重放同一张小票应该被拦(server);
        场景6_小票过期应该失败(server);
        场景7_同一IP连续失败应该被封(server);

        System.out.println("=========== 演示结束 ===========");
    }

    // ------------------------------------------------------------------
    // 场景 1：模拟真人拖动 —— 会犹豫、会手抖、会变速，应该通过
    // ------------------------------------------------------------------
    private static void 场景1_真人拖动应该通过(CaptchaServer server) {
        printTitle("场景 1：真人拖动（有犹豫、有手抖、有变速）");

        String userIp = "10.0.0.1";
        Challenge challenge = server.createChallenge(userIp);
        System.out.println("服务端出题完成，小票 token = " + shortToken(challenge.token()));
        System.out.println("（真实缺口位置只有服务端知道，绝不会随接口下发给前端）");

        // 前端采集到的轨迹：真人当然是"拖到看起来对齐"就松手，会有一两像素误差
        List<TrackPoint> humanTrack = TrackGenerator.makeHumanTrack(server.peekAnswerForDemo(challenge.token()));
        printTrackBrief("真人轨迹", humanTrack);

        VerifyResult result = server.verify(challenge.token(), lastX(humanTrack), humanTrack, userIp);
        printResult(result);
    }

    // ------------------------------------------------------------------
    // 场景 2：模拟机器脚本 —— 位置算得比人还准，但拖得又快又直，应该被拦
    // ------------------------------------------------------------------
    private static void 场景2_机器匀速拖动应该被拦(CaptchaServer server) {
        printTitle("场景 2：机器脚本（位置算得极准，但匀速直线）");

        String userIp = "10.0.0.2";
        Challenge challenge = server.createChallenge(userIp);

        // 机器用图像识别算出了缺口，位置一点不差 —— 说明"只看位置"根本拦不住它
        List<TrackPoint> robotTrack = TrackGenerator.makeRobotTrack(server.peekAnswerForDemo(challenge.token()));
        printTrackBrief("机器轨迹", robotTrack);

        VerifyResult result = server.verify(challenge.token(), lastX(robotTrack), robotTrack, userIp);
        printResult(result);
        System.out.println("→ 划重点：它的落点完全正确，但还是没过。拦住它的是【行为】不是【位置】。");
    }

    // ------------------------------------------------------------------
    // 场景 3：聪明一点的机器人 —— 知道拖太快会被抓，于是放慢到人的速度
    //         但它还是"匀速"的，节奏整齐得反常，被第三条规则拦下
    // ------------------------------------------------------------------
    private static void 场景3_进阶机器人放慢速度仍被拦(CaptchaServer server) {
        printTitle("场景 3：进阶机器人（故意放慢速度，躲过测速这一关）");

        String userIp = "10.0.0.9";
        Challenge challenge = server.createChallenge(userIp);

        // 每步间隔 40 毫秒，均速降到人的水平；点数也够多。速度这关它过了。
        List<TrackPoint> slowRobotTrack =
                TrackGenerator.makeRobotTrack(server.peekAnswerForDemo(challenge.token()), 40);
        printTrackBrief("放慢版机器轨迹", slowRobotTrack);

        VerifyResult result = server.verify(challenge.token(), lastX(slowRobotTrack), slowRobotTrack, userIp);
        printResult(result);
        System.out.println("→ 速度这关它过了，但每一步都精确间隔 40ms、y 恒为 0，太整齐反而露馅。");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 场景 4：真人但没拼准 —— 差太远，位置这关就过不了
    // ------------------------------------------------------------------
    private static void 场景4_位置拖错应该失败(CaptchaServer server) {
        printTitle("场景 4：真人拖歪了（差 20 像素）");

        String userIp = "10.0.0.3";
        Challenge challenge = server.createChallenge(userIp);

        int wrongTarget = server.peekAnswerForDemo(challenge.token()) - 20;
        List<TrackPoint> track = TrackGenerator.makeHumanTrack(wrongTarget);
        printTrackBrief("真人轨迹（但拖歪了）", track);

        VerifyResult result = server.verify(challenge.token(), lastX(track), track, userIp);
        printResult(result);
    }

    // ------------------------------------------------------------------
    // 场景 5：重放攻击 —— 把一次成功的请求原样再发一遍，小票已作废，应该被拦
    // ------------------------------------------------------------------
    private static void 场景5_重放同一张小票应该被拦(CaptchaServer server) {
        printTitle("场景 5：重放攻击（把刚才成功的请求原样再发一次）");

        String userIp = "10.0.0.4";
        Challenge challenge = server.createChallenge(userIp);
        List<TrackPoint> track = TrackGenerator.makeHumanTrack(server.peekAnswerForDemo(challenge.token()));

        System.out.println("第一次提交：");
        printResult(server.verify(challenge.token(), lastX(track), track, userIp));

        System.out.println("第二次提交（完全一样的 token + 完全一样的轨迹）：");
        printResult(server.verify(challenge.token(), lastX(track), track, userIp));
        System.out.println("→ 小票用完即废，所以录屏重放没有意义。");
    }

    // ------------------------------------------------------------------
    // 场景 6：小票过期 —— 出题后放着不动超过 5 分钟，Redis 里那条已经没了
    // ------------------------------------------------------------------
    private static void 场景6_小票过期应该失败(CaptchaServer server) {
        printTitle("场景 6：小票过期（出题后 6 分钟才来提交）");

        String userIp = "10.0.0.5";
        Challenge challenge = server.createChallenge(userIp);
        List<TrackPoint> track = TrackGenerator.makeHumanTrack(server.peekAnswerForDemo(challenge.token()));

        // 不真的等 6 分钟，直接把这张小票的创建时间往前调 —— 相当于让时钟快进
        server.fastForwardForDemo(challenge.token(), 6 * 60 * 1000L);
        System.out.println("（时钟快进 6 分钟）");

        printResult(server.verify(challenge.token(), lastX(track), track, userIp));
    }

    // ------------------------------------------------------------------
    // 场景 7：风控 —— 同一个 IP 反复用机器脚本试，失败超过 5 次就封
    // ------------------------------------------------------------------
    private static void 场景7_同一IP连续失败应该被封(CaptchaServer server) {
        printTitle("场景 7：同一 IP 用机器脚本反复试（风控封禁）");

        String attackerIp = "66.66.66.66";
        for (int attempt = 1; attempt <= 7; attempt++) {
            Challenge challenge = server.createChallenge(attackerIp);
            if (challenge == null) {
                System.out.println("第 " + attempt + " 次：连题都不给出了 —— 该 IP 已被风控封禁");
                continue;
            }
            List<TrackPoint> robotTrack =
                    TrackGenerator.makeRobotTrack(server.peekAnswerForDemo(challenge.token()));
            VerifyResult result = server.verify(challenge.token(), lastX(robotTrack), robotTrack, attackerIp);
            System.out.println("第 " + attempt + " 次：" + (result.passed() ? "通过" : "失败") + " —— " + result.reason());
        }
        System.out.println("→ 失败 5 次后直接闭门谢客，机器没法无限次试错。");
        System.out.println();
    }

    // ================== 下面是服务端本体 ==================

    /**
     * 验证码服务端。
     * 真实项目里出的题会存在 Redis，这里用一个 Map 代替，效果一样，方便单文件跑起来。
     */
    static class CaptchaServer {

        /** 图片总宽度（像素），滑块能拖动的范围 */
        private static final int IMAGE_WIDTH = 300;
        /** 允许的落点误差：差 5 像素以内都算拼对了，人眼本来就没那么精确 */
        private static final int POSITION_TOLERANCE = 5;
        /** 小票有效期 5 分钟，过期作废 */
        private static final long TOKEN_ALIVE_MILLIS = 5 * 60 * 1000L;
        /** 同一个 IP 失败超过这个数就封禁 */
        private static final int MAX_FAIL_PER_IP = 5;

        /** 相当于 Redis：token -> 这道题的答案和创建时间 */
        private final Map<String, ChallengeRecord> issuedChallenges = new HashMap<>();
        /** 每个 IP 累计失败了几次 */
        private final Map<String, Integer> failCountByIp = new HashMap<>();

        private final Random random = new Random(20260802L); // 固定种子，方便每次运行结果可复现

        /**
         * 出题：随机决定缺口位置，生成一张一次性小票。
         * 返回给前端的东西里【不包含】正确答案。
         */
        Challenge createChallenge(String clientIp) {
            if (isBlocked(clientIp)) {
                return null; // 已被风控拉黑，连题都不出
            }
            // 缺口位置每次随机（原题第 1 点：不能用固定模板，否则机器存一份就破了）
            int gapX = 80 + random.nextInt(IMAGE_WIDTH - 80 - 40);
            String token = UUID.randomUUID().toString();

            issuedChallenges.put(token, new ChallengeRecord(gapX, System.currentTimeMillis()));

            // 只把小票和图片宽度给前端，答案留在服务端
            return new Challenge(token, IMAGE_WIDTH);
        }

        /**
         * 校验：三层依次过关，任何一层不过都算失败。
         *
         * @param token       前端回传的小票
         * @param releaseX    用户松手时滑块停在哪
         * @param track       整串鼠标轨迹（关键！不是只传落点）
         * @param clientIp    请求来源 IP，用于风控计数
         */
        VerifyResult verify(String token, int releaseX, List<TrackPoint> track, String clientIp) {
            if (isBlocked(clientIp)) {
                return new VerifyResult(false, "该 IP 已被风控封禁");
            }

            // ---- 第 1 层：小票有效吗？（原题第 5 点：一次性 token 防重放）----
            ChallengeRecord record = issuedChallenges.get(token);
            if (record == null) {
                recordFail(clientIp);
                return new VerifyResult(false, "小票不存在或已被用过（疑似重放）");
            }
            if (System.currentTimeMillis() - record.createdAt() > TOKEN_ALIVE_MILLIS) {
                issuedChallenges.remove(token);
                recordFail(clientIp);
                return new VerifyResult(false, "小票已过期（超过 5 分钟）");
            }
            // 不管后面判没判过，小票都立刻作废 —— 这就是"用完即废"
            issuedChallenges.remove(token);

            // ---- 第 2 层：位置拼对了吗？----
            int offset = Math.abs(releaseX - record.gapX());
            if (offset > POSITION_TOLERANCE) {
                recordFail(clientIp);
                return new VerifyResult(false, "位置不对，差了 " + offset + " 像素（容差 " + POSITION_TOLERANCE + "）");
            }

            // ---- 第 3 层：这串轨迹像人拖的吗？（原题第 3 点，真正的防线）----
            BehaviorJudgement judgement = BehaviorAnalyzer.judge(track);
            if (!judgement.looksHuman()) {
                recordFail(clientIp);
                return new VerifyResult(false, "位置虽对，但行为可疑：" + judgement.reason());
            }

            return new VerifyResult(true, "位置准确（差 " + offset + "px），且行为特征像真人");
        }

        /** 演示用：偷看一下答案，好让我们能"模拟"用户把滑块拖到正确位置。真实服务端没有这个方法。 */
        int peekAnswerForDemo(String token) {
            return issuedChallenges.get(token).gapX();
        }

        /** 演示用：把某张小票的创建时间往前拨，模拟"过了很久才提交" */
        void fastForwardForDemo(String token, long millis) {
            ChallengeRecord old = issuedChallenges.get(token);
            issuedChallenges.put(token, new ChallengeRecord(old.gapX(), old.createdAt() - millis));
        }

        private void recordFail(String clientIp) {
            failCountByIp.merge(clientIp, 1, Integer::sum);
        }

        private boolean isBlocked(String clientIp) {
            return failCountByIp.getOrDefault(clientIp, 0) >= MAX_FAIL_PER_IP;
        }
    }

    /**
     * 行为分析器：光看落点没用，得看"拖的过程"。
     * 原题给了三条朴素规则，这里原样实现。
     */
    static class BehaviorAnalyzer {

        /** 规则一：轨迹点太少 = 机器（人拖 100 多像素，鼠标事件怎么也得几十个） */
        private static final int MIN_TRACK_POINTS = 10;
        /** 规则二：平均速度上限 50 像素 / 100 毫秒，超了就可疑 */
        private static final double MAX_SPEED_PX_PER_100MS = 50.0;
        /** 规则三：相邻两步的时间间隔波动太小 = 太平滑 = 机器 */
        private static final double MIN_INTERVAL_STD_DEV = 3.0;

        static BehaviorJudgement judge(List<TrackPoint> track) {
            // ---- 规则一：点数 ----
            if (track.size() < MIN_TRACK_POINTS) {
                return new BehaviorJudgement(false, "轨迹只有 " + track.size() + " 个点，人不可能一步到位");
            }

            TrackPoint first = track.get(0);
            TrackPoint last = track.get(track.size() - 1);
            long totalMillis = last.timeMillis() - first.timeMillis();
            int totalDistance = Math.abs(last.x() - first.x());

            if (totalMillis <= 0) {
                return new BehaviorJudgement(false, "总耗时为 0，时间戳明显是伪造的");
            }

            // ---- 规则二：平均速度 ----
            double speedPer100ms = totalDistance * 100.0 / totalMillis;
            if (speedPer100ms > MAX_SPEED_PX_PER_100MS) {
                return new BehaviorJudgement(false,
                        String.format("平均速度 %.1f px/100ms，超过阈值 %.0f（手甩不了这么快）",
                                speedPer100ms, MAX_SPEED_PX_PER_100MS));
            }

            // ---- 规则三：是不是太"匀"了 ----
            // 思路：算出每两步之间的时间间隔，看这些间隔的标准差。
            // 人：28、35、31、47、22……忽快忽慢，标准差大。
            // 机器：10、10、10、10……整齐得反常，标准差接近 0。
            double intervalStdDev = standardDeviationOfIntervals(track);
            if (intervalStdDev < MIN_INTERVAL_STD_DEV) {
                return new BehaviorJudgement(false,
                        String.format("每一步间隔标准差只有 %.2f ms，整齐得不像人手（阈值 %.0f）",
                                intervalStdDev, MIN_INTERVAL_STD_DEV));
            }

            // 补充规则：真人握鼠标一定会上下抖一点，y 全程纹丝不动很可疑
            boolean hasVerticalShake = track.stream().anyMatch(point -> point.y() != 0);
            if (!hasVerticalShake) {
                return new BehaviorJudgement(false, "全程 y 坐标一动不动，是标准直线，真人手会抖");
            }

            return new BehaviorJudgement(true,
                    String.format("耗时 %d ms，%d 个点，均速 %.1f px/100ms，间隔标准差 %.2f ms",
                            totalMillis, track.size(), speedPer100ms, intervalStdDev));
        }

        /** 算相邻两点时间间隔的标准差 —— 用来衡量"节奏是否忽快忽慢" */
        private static double standardDeviationOfIntervals(List<TrackPoint> track) {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < track.size(); i++) {
                intervals.add(track.get(i).timeMillis() - track.get(i - 1).timeMillis());
            }
            double average = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double sumOfSquaredDiff = 0;
            for (long interval : intervals) {
                double diff = interval - average;
                sumOfSquaredDiff += diff * diff;
            }
            return Math.sqrt(sumOfSquaredDiff / intervals.size());
        }
    }

    /**
     * 轨迹生成器：模拟前端采集到的鼠标数据。
     * 真实环境里这些点是浏览器 mousemove 事件产生的，这里我们自己造两种典型样本。
     */
    static class TrackGenerator {

        private static final Random random = new Random(999L);

        /**
         * 造一串"真人"轨迹：起步慢、中间快、快到位再慢下来找准，中途还停顿一下，y 轴小幅抖动。
         */
        static List<TrackPoint> makeHumanTrack(int targetX) {
            List<TrackPoint> track = new ArrayList<>();
            long currentTime = 0;
            int currentX = 0;

            track.add(new TrackPoint(0, 0, 0)); // 按下滑块的那一刻

            while (currentX < targetX) {
                int remaining = targetX - currentX;

                // 三段式速度：开头小步试探，中间大步推，快到了再小步微调
                int step;
                if (currentX < targetX * 0.2) {
                    step = 2 + random.nextInt(4);        // 起步：2~5 px
                } else if (remaining > 20) {
                    step = 6 + random.nextInt(7);        // 中段：6~12 px
                } else {
                    step = 1 + random.nextInt(3);        // 收尾：1~3 px，慢慢对齐
                }
                step = Math.min(step, remaining);
                currentX += step;

                // 每一步花的时间忽长忽短（人手的节奏本来就不稳）
                currentTime += 18 + random.nextInt(35);

                // 偶尔犹豫一下：手停在半路想了想
                if (random.nextInt(12) == 0) {
                    currentTime += 120 + random.nextInt(200);
                }

                // y 轴小抖动：-2 ~ +2 像素，握鼠标不可能走绝对水平线
                int shakeY = random.nextInt(5) - 2;

                track.add(new TrackPoint(currentX, shakeY, currentTime));
            }
            return track;
        }

        /**
         * 造一串"机器"轨迹：图像识别算出缺口后，匀速直线一把推过去。
         * 位置绝对精确，正因为太精确、太整齐，反而暴露了自己。
         */
        static List<TrackPoint> makeRobotTrack(int targetX) {
            return makeRobotTrack(targetX, 10); // 默认每步 10 毫秒，快得离谱
        }

        /**
         * 同上，但可以指定每一步花多少毫秒。
         * 把它调大（比如 40ms），机器就能把平均速度压到人的水平、躲过测速那一关，
         * 可它依然是"每步一模一样"的匀速直线，会栽在第三条规则上。
         */
        static List<TrackPoint> makeRobotTrack(int targetX, int millisPerStep) {
            List<TrackPoint> track = new ArrayList<>();
            int stepCount = 12;                    // 故意给够 12 个点，绕过"点数太少"这条规则
            int stepDistance = targetX / stepCount;

            for (int i = 0; i <= stepCount; i++) {
                int x = (i == stepCount) ? targetX : i * stepDistance;
                long time = (long) i * millisPerStep;  // 每步固定间隔，完美匀速
                track.add(new TrackPoint(x, 0, time)); // y 永远是 0，笔直一条线
            }
            return track;
        }
    }

    // ================== 数据结构 ==================

    /** 一个鼠标轨迹点：横坐标、纵坐标、距离开始拖动过了多少毫秒 */
    record TrackPoint(int x, int y, long timeMillis) {}

    /** 服务端存着的题目记录：正确答案 + 出题时间 */
    record ChallengeRecord(int gapX, long createdAt) {}

    /** 发给前端的东西：只有小票和图宽，【没有答案】 */
    record Challenge(String token, int imageWidth) {}

    /** 最终校验结论 */
    record VerifyResult(boolean passed, String reason) {}

    /** 行为分析的结论 */
    record BehaviorJudgement(boolean looksHuman, String reason) {}

    // ================== 打印辅助 ==================

    private static void printTitle(String title) {
        System.out.println("----- " + title + " -----");
    }

    private static void printResult(VerifyResult result) {
        System.out.println((result.passed() ? "[通过] " : "[拦截] ") + result.reason());
        System.out.println();
    }

    private static void printTrackBrief(String label, List<TrackPoint> track) {
        StringBuilder preview = new StringBuilder();
        int showCount = Math.min(5, track.size());
        for (int i = 0; i < showCount; i++) {
            TrackPoint point = track.get(i);
            preview.append(String.format("(x=%d,y=%d,t=%d) ", point.x(), point.y(), point.timeMillis()));
        }
        System.out.println(label + "：共 " + track.size() + " 个点，前几个是 " + preview + "...");
    }

    private static int lastX(List<TrackPoint> track) {
        return track.get(track.size() - 1).x();
    }

    private static String shortToken(String token) {
        return token.substring(0, 8) + "...";
    }
}
