// 抢红包金额算法 Demo（纯算法，无需中间件）
//
// 演示三件事：
//   1) 二倍均值法：每人上限 = 剩余金额 / 剩余人数 × 2，在 [1分, 上限] 随机，最后一人兜底拿光。
//   2) 线段切割法：把总额当成一根绳子随机切 N-1 刀，每段就是一个人的金额。
//   3) 并发抢红包：100 个线程抢 10 份，用锁保证不超发、总额一分不差。
//
// 约定：全程用"分"（整数）算钱。浮点 double 有精度误差（0.1+0.2≠0.3），发钱差一分都是事故，
//       所以内部一律用整数分，展示时再换算成"元"。1 元 = 100 分。

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    public static void main(String[] args) {
        int totalFen = 100 * 100; // 发一个 100.00 元的红包
        int peopleCount = 10;     // 分给 10 个人

        System.out.println("======== 抢红包金额计算 Demo ========");
        System.out.println("\n【方法一】二倍均值法：100 元发给 10 个人");
        printResult(splitByDoubleAverage(totalFen, peopleCount), totalFen);

        System.out.println("\n【方法二】线段切割法：100 元发给 10 个人");
        printResult(splitByLineCut(totalFen, peopleCount), totalFen);

        System.out.println("\n【方法三】并发抢红包：100 人同时抢 10 份、共 100 元的红包");
        concurrentGrabDemo(totalFen, peopleCount);
    }

    // 二倍均值法：每次只决定"当前这个人能拿多少"，保证前面拿完后面仍够分
    static List<Integer> splitByDoubleAverage(int totalFen, int peopleCount) {
        List<Integer> amounts = new ArrayList<>();
        Random random = new Random();
        int leftAmount = totalFen;
        int leftPeople = peopleCount;

        // 前 count-1 人随机，最后一人兜底
        for (int i = 0; i < peopleCount - 1; i++) {
            // 上限 = 剩余人均 × 2。随机值平均约为上限一半，正好等于当时人均，
            // 所以前面抢完后面仍大体均衡，不会"前面吃饱后面饿"。
            int max = leftAmount / leftPeople * 2;
            if (leftPeople == 2) {
                max = Math.max(1, Math.min(max, leftAmount - 1)); // 至少给最后一人留 1 分
            }
            int amount = Math.max(1, random.nextInt(max) + 1); // [1分, max]，最少抢到 1 分
            amounts.add(amount);
            leftAmount -= amount;
            leftPeople--;
        }

        // 最后一人不随机，直接拿走账上剩余全部，保证总额一分不差
        amounts.add(leftAmount);
        return amounts;
    }

    // 线段切割法：在 [1, totalFen-1] 取 count-1 个不重复切点，排序后相邻相减即每人金额
    static List<Integer> splitByLineCut(int totalFen, int peopleCount) {
        Random random = new Random();
        // TreeSet 自动去重 + 排序，保证每段至少 1 分
        TreeSet<Integer> cutPoints = new TreeSet<>();
        while (cutPoints.size() < peopleCount - 1) {
            cutPoints.add(random.nextInt(totalFen - 1) + 1);
        }

        List<Integer> amounts = new ArrayList<>();
        int previousPoint = 0;
        for (int point : cutPoints) {
            amounts.add(point - previousPoint);
            previousPoint = point;
        }
        amounts.add(totalFen - previousPoint); // 最后一段到终点
        return amounts;
    }

    // 并发抢红包：用一把锁把"抢一份"整个动作锁起来，同一时刻只有一个人能进，
    // 抢完更新下标，下一个人才能进——等价于真实系统里 Redis + Lua 脚本的原子性。
    static void concurrentGrabDemo(int totalFen, int packetCount) {
        List<Integer> pool = splitByDoubleAverage(totalFen, packetCount); // 发红包时先算好 10 份
        Object lock = new Object();
        int[] nextIndex = {0}; // 红包池里下一份发给谁，被锁保护
        AtomicInteger grabbedCount = new AtomicInteger(0);
        AtomicInteger grabbedTotalFen = new AtomicInteger(0);

        int robberCount = 100; // 100 人抢 10 份，制造激烈竞争
        List<Thread> robbers = new ArrayList<>();
        for (int i = 0; i < robberCount; i++) {
            int robberId = i + 1;
            robbers.add(new Thread(() -> {
                synchronized (lock) { // 进门排队，杜绝两人抢到同一份
                    if (nextIndex[0] >= pool.size()) {
                        return; // 已抢光，真实场景会提示"手慢了"
                    }
                    int amount = pool.get(nextIndex[0]++);
                    grabbedCount.incrementAndGet();
                    grabbedTotalFen.addAndGet(amount);
                    System.out.println("  用户#" + robberId + " 抢到 " + fenToYuan(amount) + " 元");
                }
            }));
        }

        robbers.forEach(Thread::start);
        robbers.forEach(robber -> {
            try {
                robber.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        System.out.println("  ---- 校验结果 ----");
        System.out.println("  红包份数 = " + packetCount + "，实际抢走份数 = " + grabbedCount.get()
                + (grabbedCount.get() == packetCount ? "（没有超发）" : "（超发了！）"));
        System.out.println("  总额 = " + fenToYuan(totalFen) + " 元，抢走合计 = " + fenToYuan(grabbedTotalFen.get())
                + " 元" + (grabbedTotalFen.get() == totalFen ? "（一分不差）" : "（对不上！）"));
    }

    // 打印结果并顺手校验总额是否对得上
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

    // 分换算成元，保留两位小数，例如 2366 分 -> "23.66"
    static String fenToYuan(int fen) {
        return String.format("%d.%02d", fen / 100, fen % 100);
    }
}
