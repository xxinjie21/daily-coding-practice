/*
 * 线上 CPU Load 飙高排查 —— 用 JDK 自带的管理 API，把线上那套「四步走」在自己身上演一遍。
 *
 * 每一步都标注了它等价于线上敲的那条命令：
 *   ① 看整体负载          ≈ top                       → load average 与核数
 *   ② 找出最耗 CPU 的线程   ≈ top -H -p <PID>           → ThreadMXBean 算 CPU 时间增量
 *   ③ 线程号转 16 进制      ≈ printf "%x\n" <TID>       → Long.toHexString
 *   ④ 抓线程调用栈         ≈ jstack <PID> | grep <hex>  → ThreadInfo.getStackTrace
 *   ⑤ 看 GC 频率与耗时      ≈ jstat -gcutil <PID> 1000  → GarbageCollectorMXBean
 *   ⑥ 真调一次 JDK 工具     ≈ jcmd <PID> Thread.print   → ProcessBuilder 起真实 jcmd
 *
 * 为了让 CPU 真的忙起来，程序故意起了两个「坏线程」（都是原题点名的坑）：
 *   busy-loop-thread      ：while(true) 空转，里面没有 sleep
 *   regex-backtrack-thread：(a+)+$ 这种会「回溯爆炸」的正则
 *
 * 前置条件：无。纯 JDK，不需要任何中间件，直接 `mvn exec:java` 就能跑。
 */
import java.io.BufferedReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class Demo {

    /** 坏线程的开关：置成 false 就收工，别让演示程序一直烧 CPU */
    private static final AtomicBoolean running = new AtomicBoolean(true);

    /** 一行排行数据：线程名 + 线程号 + 采样窗口里吃掉了多少纳秒 CPU */
    private record ThreadCpu(String name, long tid, long nanos) { }

    public static void main(String[] args) throws Exception {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        // 线程 CPU 计时默认可能是关的，不打开就一直读到 -1
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }

        startHotThreads();
        Thread.sleep(800);   // 先让坏线程热起来，否则采样窗口里它们还没开始吃 CPU

        step1SystemLoad();
        long[] hotTids = step2RankThreadsByCpu(threadBean);
        step3And4HexIdAndStack(threadBean, hotTids);
        step5GcStats();
        step6RealJcmd(hotTids);

        running.set(false);
        System.out.println("\n[收工] 已通知两个坏线程退出，演示结束。");
    }

    /** 故意造两个坏线程，让 CPU 真的忙起来 */
    private static void startHotThreads() {
        Thread busyLoop = new Thread(() -> {
            long spins = 0;
            while (running.get()) {
                spins++;   // 原题点名的坑之一：while(true) 里没有 sleep，纯空转把一个核烧满
            }
        }, "busy-loop-thread");

        // 原题点名的坑之二：(a+)+$ 遇到「一长串 a 后面跟个不匹配字符」，会指数级回溯
        Pattern evilRegex = Pattern.compile("(a+)+$");
        String evilInput = "aaaaaaaaaaaaaaaaaaaaaaaaaa!";   // 26 个 a + 一个感叹号
        Thread regexThread = new Thread(() -> {
            while (running.get()) {
                evilRegex.matcher(evilInput).matches();
            }
        }, "regex-backtrack-thread");

        busyLoop.setDaemon(true);
        regexThread.setDaemon(true);
        busyLoop.start();
        regexThread.start();
        System.out.println("已启动 2 个坏线程：busy-loop-thread（空转）、regex-backtrack-thread（正则回溯）");
    }

    /** ① 等价 top 顶部那行：先看 load average 和核数，判断机器到底忙不忙 */
    private static void step1SystemLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        int cores = os.getAvailableProcessors();
        double load = os.getSystemLoadAverage();   // 只有 Linux 才取得到，Windows 返回 -1

        System.out.println("① 整体负载（等价 top）");
        System.out.println("   机器核数        : " + cores);
        if (load < 0) {
            System.out.println("   load average(1m): 本系统取不到（load average 是 Linux 概念，Windows 没有）");
        } else {
            System.out.println("   load average(1m): " + String.format("%.2f", load)
                    + "  → 平均每核 " + String.format("%.2f", load / cores));
        }
        System.out.println("   怎么判断        : load 长期大于核数就是有线程在排队；再看是 us 高还是 wa 高");
    }

    /** ② 等价 top -H -p <PID>：按「一段时间内吃掉的 CPU」给线程排序，找出元凶 */
    private static long[] step2RankThreadsByCpu(ThreadMXBean bean) throws InterruptedException {
        long selfTid = Thread.currentThread().getId();
        long[] ids = bean.getAllThreadIds();
        long[] before = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            before[i] = bean.getThreadCpuTime(ids[i]);
        }

        Thread.sleep(1500);   // CPU 占用看的是「增量」，窗口太短会把瞬时抖动误判成热点

        List<ThreadCpu> ranked = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            long delta = bean.getThreadCpuTime(ids[i]) - before[i];
            ThreadInfo info = bean.getThreadInfo(ids[i]);
            if (delta > 0 && info != null && ids[i] != selfTid) {
                ranked.add(new ThreadCpu(info.getThreadName(), ids[i], delta));
            }
        }
        ranked.sort((a, b) -> Long.compare(b.nanos(), a.nanos()));

        System.out.println("\n② 线程 CPU 排行（等价 top -H -p <PID>，采样窗口 1.5s）");
        int topN = Math.min(3, ranked.size());
        long[] hotTids = new long[topN];
        for (int i = 0; i < topN; i++) {
            ThreadCpu t = ranked.get(i);
            hotTids[i] = t.tid();
            System.out.println("   " + (i + 1) + ". tid=" + t.tid()
                    + "  占用 " + (t.nanos() / 1_000_000) + "ms  " + t.name());
        }
        if (topN == 0) {
            System.out.println("   没采到活跃线程（线程都在等锁或等 IO 也会这样）");
        }
        return hotTids;
    }

    /** ③④ 等价 printf "%x\n" <TID> 再 jstack | grep：线程号转 16 进制，然后看它的调用栈 */
    private static void step3And4HexIdAndStack(ThreadMXBean bean, long[] hotTids) {
        if (hotTids.length == 0) {
            System.out.println("\n③④ 没有热点线程可分析，跳过");
            return;
        }
        long tid = hotTids[0];
        String hexTid = Long.toHexString(tid);   // Linux 上这个 tid 就是 OS 线程号，和 jstack 的 nid 对得上

        System.out.println("\n③ 线程号转 16 进制（等价 printf \"%x\\n\" " + tid + "）");
        System.out.println("   tid=" + tid + "  →  hex=" + hexTid + "，jstack 里会显示成 nid=0x" + hexTid);

        ThreadInfo info = bean.getThreadInfo(tid, 10);
        System.out.println("\n④ 元凶线程的调用栈（等价 jstack <PID> | grep -A 50 " + hexTid + "）");
        if (info == null) {
            System.out.println("   线程已经结束，抓不到栈了");
            return;
        }
        System.out.println("   线程名: " + info.getThreadName() + "   状态: " + info.getThreadState());
        for (StackTraceElement frame : info.getStackTrace()) {
            System.out.println("   at " + frame);
        }
        System.out.println("   看栈顶几帧：停在业务代码 → 死循环/正则回溯；停在 GC 相关 → 去看第⑤步");
    }

    /** ⑤ 等价 jstat -gcutil <PID> 1000：GC 次数和耗时涨得快，说明 CPU 是被 GC 吃掉的 */
    private static void step5GcStats() throws InterruptedException {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long countBefore = totalGcCount(gcBeans);
        long timeBefore = totalGcTime(gcBeans);

        Thread.sleep(1000);   // jstat 后面那个 1000 就是「每秒打一次」

        long countDelta = totalGcCount(gcBeans) - countBefore;
        long timeDelta = totalGcTime(gcBeans) - timeBefore;

        System.out.println("\n⑤ GC 情况（等价 jstat -gcutil <PID> 1000，观察 1 秒）");
        System.out.println("   这 1 秒内 GC 次数: " + countDelta + " 次，耗时: " + timeDelta + "ms");
        if (timeDelta > 500) {
            System.out.println("   判断: GC 耗时超过半秒 → 负载高很可能是频繁 Full GC，去查内存泄漏");
        } else {
            System.out.println("   判断: GC 很轻 → 负载高不是 GC 造成的，回到第④步的调用栈继续查业务代码");
        }
    }

    private static long totalGcCount(List<GarbageCollectorMXBean> beans) {
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            total += Math.max(0, bean.getCollectionCount());   // 取不到时返回 -1，按 0 算
        }
        return total;
    }

    private static long totalGcTime(List<GarbageCollectorMXBean> beans) {
        long total = 0;
        for (GarbageCollectorMXBean bean : beans) {
            total += Math.max(0, bean.getCollectionTime());
        }
        return total;
    }

    /** ⑥ 真的去调一次 JDK 自带工具 jcmd，抓自己进程的线程栈，再按 16 进制线程号过滤 */
    private static void step6RealJcmd(long[] hotTids) {
        String exeName = System.getProperty("os.name").toLowerCase().contains("win") ? "jcmd.exe" : "jcmd";
        Path jcmd = Paths.get(System.getProperty("java.home"), "bin", exeName);
        long pid = ProcessHandle.current().pid();

        System.out.println("\n⑥ 调真实 JDK 工具（等价 jcmd " + pid + " Thread.print）");
        if (!Files.isExecutable(jcmd) || hotTids.length == 0) {
            System.out.println("   本机没有可用的 jcmd 或没有热点线程，跳过这一步（前面几步结论不受影响）");
            return;
        }
        ThreadInfo hotInfo = ManagementFactory.getThreadMXBean().getThreadInfo(hotTids[0]);
        if (hotInfo == null) {
            System.out.println("   热点线程已经结束了，跳过这一步");
            return;
        }
        // Linux 上 tid 就是 OS 线程号，可以直接 grep nid=0x<hex>；
        // Windows 的 nid 是另一套编号，和 tid 对不上，所以这里按线程名过滤，两个平台都管用
        String threadName = hotInfo.getThreadName();
        System.out.println("   过滤条件: 线程名 \"" + threadName + "\""
                + "（Linux 上也可以直接 grep nid=0x" + Long.toHexString(hotTids[0]) + "）");

        try {
            Process process = new ProcessBuilder(jcmd.toString(), String.valueOf(pid), "Thread.print")
                    .redirectErrorStream(true).start();

            // jcmd 的线程栈有几十 KB，必须边跑边读；等它退出再读会把管道写满，双方一起卡死
            List<String> targetLines = new ArrayList<>();
            Thread reader = new Thread(() -> collectTargetThreadLines(process, threadName, targetLines));
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.out.println("   jcmd 20 秒没返回，跳过（线上也要给工具加超时，别把排查搞成事故）");
                return;
            }
            reader.join(3000);
            if (targetLines.isEmpty()) {
                System.out.println("   线程栈里没找到 \"" + threadName + "\"（线程可能刚好结束了）");
                return;
            }
            for (String line : targetLines) {
                System.out.println("   " + line);
            }
        } catch (Exception e) {
            System.out.println("   jcmd 调用失败：" + e.getMessage() + "，跳过这一步");
        }
    }

    /** 一边把输出读干（防管道写满），一边挑出目标线程那段，最多留 12 行 */
    private static void collectTargetThreadLines(Process process, String threadName, List<String> sink) {
        String headerMark = "\"" + threadName + "\"";   // 线程栈里每段都以 "线程名" #N 开头
        boolean matched = false;
        try (BufferedReader reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(headerMark)) {
                    matched = true;
                }
                if (matched && sink.size() < 12) {
                    sink.add(line);
                }
            }
        } catch (Exception ignored) {
            // 进程被强杀时读流会报错，静默收场即可
        }
    }
}
