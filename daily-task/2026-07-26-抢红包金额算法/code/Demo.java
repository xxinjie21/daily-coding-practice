// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   演示"抢红包金额怎么算"这道题的两种经典算法 + 并发怎么不超发，一共三件事：
//   1) 二倍均值法：微信后来优化采用的主流思路，每人上限 = 剩余人均 * 2，公平又随机。
//   2) 线段切割法：把总额想象成一根绳子，随机切 N-1 刀，每段就是一个人的金额。
//   3) 并发原子性：100 个线程同时抢一个红包，用一把锁保证不超发、总额一分不差
//      （等价于真实系统里 Redis + Lua 脚本的原子性）。
//
// 重要约定：全程用"分"(整数)算钱，1 元 = 100 分。
//   因为浮点 double 有精度误差(0.1+0.2 都不等于 0.3)，发钱差一分都是事故，
//   所以内部一律用整数分，展示给人看时再换算成"元"。

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) {
        System.out.println("======== 抢红包金额计算 Demo ========");

        // 场景：发一个 100.00 元的红包，分给 10 个人抢
        int totalFen = 100 * 100; // 100 元换算成 10000 分
        int peopleCount = 10;

        System.out.println("\n【方法一】二倍均值法：100 元发给 10 个人");
        List<Integer> resultByAvg = splitByDoubleAverage(totalFen, peopleCount);
        printResult(resultByAvg, totalFen);

        System.out.println("\n【方法二】线段切割法：100 元发给 10 个人");
        List<Integer> resultByCut = splitByLineCut(totalFen, peopleCount);
        printResult(resultByCut, totalFen);

        System.out.println("\n【方法三】并发抢红包：100 个人同时抢一个 10 份、共 100 元的红包，验证不超发");
        concurrentGrabDemo(totalFen, peopleCount);

        System.out.println("\n======== Demo 结束 ========");
    }

    // =====================================================================
    // 方法一：二倍均值法（期望值控制法）—— 本题主推的实现
    // 核心公式：本次上限 = 剩余金额 / 剩余人数 * 2，然后在 [1分, 上限] 之间随机
    // =====================================================================
    static List<Integer> splitByDoubleAverage(int totalFen, int peopleCount) {
        List<Integer> amounts = new ArrayList<>();
        Random random = new Random();

        int leftAmount = totalFen;   // 还剩多少钱(分)
        int leftPeople = peopleCount; // 还剩多少人没抢

        // 前 count-1 个人都用二倍均值法随机抢
        for (int i = 0; i < peopleCount - 1; i++) {
            // 剩余人均金额的 2 倍，就是这个人能抢到的上限
            // 例：还剩 5000 分、9 个人 -> 上限 = 5000 / 9 * 2 ≈ 1111 分
            int max = leftAmount / leftPeople * 2;

            // 在 [1分, max] 之间随机取一个数。用 Math.max 兜底，保证最少抢到 1 分，
            // 避免有人抢到 0 元的尴尬。
            int amount = Math.max(1, random.nextInt(max) + 1);

            amounts.add(amount);
            leftAmount -= amount; // 账上钱变少
            leftPeople--;         // 少一个人
        }

        // 最后一个人不参与随机，直接把剩下的钱全给他。
        // 这样即使前面因为取整丢了几分，也能靠最后一人兜回来，保证总额一分不差。
        amounts.add(leftAmount);
        return amounts;
    }

    // =====================================================================
    // 方法二：线段切割法（切绳子）
    // 把总额当成一根绳子，随机取 count-1 个不重复的切点，排序后算相邻间距
    // =====================================================================
    static List<Integer> splitByLineCut(int totalFen, int peopleCount) {
        Random random = new Random();

        // 用 TreeSet 自动去重 + 自动排序，往里塞 count-1 个不重复的切点。
        // 切点范围是 [1, totalFen-1]，保证每段至少 1 分、不会切出 0。
        TreeSet<Integer> cutPoints = new TreeSet<>();
        while (cutPoints.size() < peopleCount - 1) {
            int point = random.nextInt(totalFen - 1) + 1; // 取 [1, totalFen-1]
            cutPoints.add(point);
        }

        // 相邻切点相减 = 每个人的金额。
        // 想象在数轴上标好 0、若干切点、totalFen，逐段量长度。
        List<Integer> amounts = new ArrayList<>();
        int previousPoint = 0; // 上一个切点，从起点 0 开始
        for (int point : cutPoints) {
            amounts.add(point - previousPoint); // 这一段的长度
            previousPoint = point;
        }
        // 最后一段：从最后一个切点到终点 totalFen
        amounts.add(totalFen - previousPoint);
        return amounts;
    }

    // =====================================================================
    // 方法三：并发抢红包，验证"不超发、总额对得上"
    // 用一把锁把"抢一份"的整个动作锁起来，等价于 Redis+Lua 的原子性：
    // 同一时刻只允许一个人在抢，抢完更新余额，下一个人才能进来。
    // =====================================================================
    static void concurrentGrabDemo(int totalFen, int packetCount) {
        // 先用二倍均值法把 10 份金额预先算好，放进"红包池"。
        // 真实系统就是发红包时当场算好、存进 Redis 列表，抢的时候直接弹一份。
        List<Integer> redPacketPool = splitByDoubleAverage(totalFen, packetCount);

        // 这把锁就是我们的"原子性保证"。谁拿到锁谁才能抢，抢完释放，杜绝两个人同时抢到同一份。
        final Object grabLock = new Object();

        // 用一个下标记录"红包池里下一份该发给谁"，被锁保护，不会算重。
        // 这里用普通 int 数组当作可变容器，因为它在 lambda 里要被修改。
        int[] nextIndex = {0};

        // 统计：一共成功抢到多少份、抢到的钱加起来是多少
        AtomicInteger grabbedCount = new AtomicInteger(0);
        AtomicInteger grabbedTotalFen = new AtomicInteger(0);

        // 模拟 100 个人同时来抢（远多于 10 份，制造激烈竞争）
        int robberCount = 100;
        List<Thread> robbers = new ArrayList<>();
        for (int i = 0; i < robberCount; i++) {
            final int robberId = i + 1;
            Thread robber = new Thread(() -> {
                synchronized (grabLock) { // 进门要排队，同一时刻只有一个人在里面抢
                    if (nextIndex[0] < redPacketPool.size()) {
                        int amount = redPacketPool.get(nextIndex[0]);
                        nextIndex[0]++; // 这份被领走了，指向下一份
                        grabbedCount.incrementAndGet();
                        grabbedTotalFen.addAndGet(amount);
                        System.out.println("  用户#" + robberId + " 抢到 " + fenToYuan(amount) + " 元");
                    }
                    // else：红包已被抢光，这个人啥也没抢到（真实场景会提示"手慢了"）
                }
            });
            robbers.add(robber);
        }

        // 一起开抢
        for (Thread robber : robbers) {
            robber.start();
        }
        // 等所有人都抢完，再统计结果
        for (Thread robber : robbers) {
            try {
                robber.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 校验：抢到的份数必须正好等于红包份数(不超发)，金额加起来必须正好等于总额
        System.out.println("  ---- 校验结果 ----");
        System.out.println("  红包份数 = " + packetCount + "，实际抢走份数 = " + grabbedCount.get()
                + (grabbedCount.get() == packetCount ? " (没有超发，正确)" : " (超发了！有 BUG)"));
        System.out.println("  总额 = " + fenToYuan(totalFen) + " 元，抢走金额合计 = "
                + fenToYuan(grabbedTotalFen.get()) + " 元"
                + (grabbedTotalFen.get() == totalFen ? " (一分不差，正确)" : " (对不上！有 BUG)"));
    }

    // ---------------------- 下面是几个小工具方法 ----------------------

    // 把结果打印出来，并顺手校验"加起来是不是正好等于总额"
    static void printResult(List<Integer> amounts, int totalFen) {
        int sum = 0;
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < amounts.size(); i++) {
            int amount = amounts.get(i);
            sum += amount;
            max = Math.max(max, amount);
            min = Math.min(min, amount);
            System.out.println("  第 " + (i + 1) + " 个人抢到 " + fenToYuan(amount) + " 元");
        }
        System.out.println("  合计 = " + fenToYuan(sum) + " 元（应为 " + fenToYuan(totalFen) + " 元）"
                + (sum == totalFen ? " 正好对上" : " 对不上！"));
        System.out.println("  最大 " + fenToYuan(max) + " 元，最小 " + fenToYuan(min) + " 元");
    }

    // 把"分"换算成"元"，保留两位小数，例如 2366 分 -> "23.66"
    static String fenToYuan(int fen) {
        return String.format("%d.%02d", fen / 100, fen % 100);
    }
}
