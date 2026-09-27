// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 面试题：怎么分析 JVM 当前的内存占用？OOM 之后怎么分析？
//
// 平时我们靠命令行工具查内存（jstat / jmap / jcmd / GC 日志），
// 但这些工具读的都是 JVM 内部同一份统计数据。这个 Demo 就用 JDK 自带的
// 管理接口（java.lang.management + com.sun.management）把那份数据直接读出来，
// 一共演示 5 件事：
//
//   1) 看整体内存：堆用多少、非堆用多少                  —— 相当于 jstat -gc
//   2) 看每块内存区的明细：Eden / Survivor / Old / 元空间 —— 相当于 jmap -heap
//   3) 看 GC 干了多少次、一共花了多少时间                —— 相当于 GC 日志里的 YGC / FGC
//   4) 模拟「缓存只进不出」让内存慢慢涨，并给出预警        —— 老年代 > 70% 要警惕
//   5) 抓一份堆快照（heap dump）到临时目录               —— 相当于 jmap -dump:format=b,file=heap.hprof <pid>
//
// 最后还会打印当前进程 pid 和几条可以直接复制粘贴的真机命令。
//
// 想亲眼看「老年代超过 70% 触发预警」那一幕，用小堆跑：
//   mvn -q compile exec:java -Dexec.args="" -Dexec.jvmArgs="-Xmx64m"
// ============================================================================

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.management.HotSpotDiagnosticMXBean;

public class Demo {

    // 故意放在 static 上，模拟「缓存只进不出」：只要程序还活着，这些对象就一直被引用，
    // GC 想收也收不掉。线上的内存泄漏，十有八九就是这么来的。
    private static final List<byte[]> simulatedCache = new ArrayList<>();

    /** 最多往缓存里塞多少 MB（防止这个 Demo 把你自己机器的内存也吃光）。 */
    private static final int MAX_SIMULATED_MB = 32;

    /** 接住临时垃圾的计算结果，防止编译器把没用的分配优化掉。 */
    private static long garbageChecksum;

    public static void main(String[] args) {
        printPidAndRealCommands(ProcessHandle.current().pid());
        printOverallMemory();  // 第 1 步：整体
        printMemoryPools();    // 第 2 步：分区明细
        warmUpSomeGarbage();   // 第 3 步的准备：先造点垃圾，让 GC 统计有数字可看
        printGcStats();        // 第 3 步：GC 统计
        simulateSlowLeak();    // 第 4 步：模拟内存增长 + 预警
        printGcStats();        // 再看看 GC 次数有没有变多
        dumpHeapSnapshot();    // 第 5 步：抓堆快照
        printOverallMemory();  // 最后再看一眼整体
    }

    /** 打印当前进程 pid，并给出几条「对着真机再敲一遍」的命令。我们读的和它们读的是同一份数据。 */
    private static void printPidAndRealCommands(long pid) {
        System.out.println("==================================================");
        System.out.println("当前 Java 进程 pid = " + pid + "（用 jps 命令也能看到它）");
        System.out.println("你可以另开一个终端，对着这个 pid 敲：");
        System.out.println("  jstat -gc " + pid + "        # 每秒刷一次各区容量与 GC 情况");
        System.out.println("  jmap -heap " + pid + "       # 看堆的配置与各区实际使用量");
        System.out.println("  jmap -dump:format=b,file=heap.hprof " + pid + "   # 手动抓堆快照");
        System.out.println("  jcmd " + pid + " GC.heap_info        # 更轻量的内存概览");
        System.out.println("==================================================");
    }

    /** 第 1 步：整体内存。相当于先看「仓库总容量」和「已经堆了多少货」。 */
    private static void printOverallMemory() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();       // 堆：对象主要住这儿
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage(); // 非堆：类元数据、线程栈等

        System.out.println();
        System.out.println("【1】整体内存（Heap 堆 / Non-Heap 非堆）");
        System.out.println("  堆   已用 " + toMb(heap.getUsed()) + " MB，上限 "
                + (heap.getMax() < 0 ? "无上限" : toMb(heap.getMax()) + " MB"));
        System.out.println("  非堆 已用 " + toMb(nonHeap.getUsed()) + " MB（类元数据、线程栈这些不算在堆里）");
        System.out.println("  → 堆的已用量一直贴着上限走，就说明离 OOM 不远了。");
    }

    /** 第 2 步：分区明细。相当于 jmap -heap 里那几行：Eden、Survivor、Old、Metaspace。 */
    private static void printMemoryPools() {
        System.out.println();
        System.out.println("【2】各内存区明细（年轻代 Eden/Survivor、老年代 Old、元空间 Metaspace）");
        System.out.printf("  %-22s %-10s %10s %10s %10s%n", "内存区", "类型", "已用(MB)", "已提交(MB)", "上限(MB)");

        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) {
                continue; // 某些虚拟机对个别内存区不提供使用量，跳过就好
            }
            System.out.printf("  %-22s %-10s %10s %10s %10s%n",
                    pool.getName(), pool.getType(),
                    toMb(usage.getUsed()), toMb(usage.getCommitted()), toMb(usage.getMax()));
        }
        System.out.println("  → 重点盯名字里带 Old 的那一行：已用/上限长期超过 70%，就是 OOM 的前兆。");
        System.out.println("  → 上限那列显示 - 的，表示这个区没设上限（比如元空间默认就是不限）。");
    }

    /** 第 3 步的准备：先制造一批「短命垃圾」，否则刚启动时 GC 统计全是 0，看不出趋势。 */
    private static void warmUpSomeGarbage() {
        System.out.println();
        System.out.println("（第 3 步前的准备）先制造一批临时垃圾，好让 GC 统计有数字可看");

        long checksum = 0;
        for (int i = 0; i < 64; i++) {
            byte[] temporary = new byte[1024 * 1024]; // 1MB，循环一圈就没人引用了
            checksum += temporary[0];                 // 用一下，防止编译器把这次分配优化掉
        }
        garbageChecksum += checksum;
        System.out.println("  已制造 64.0 MB 短命垃圾（它们会被年轻代 GC 很快收走）");
    }

    /** 第 3 步：GC 统计。次数只涨不歇、耗时越来越长，就是内存压力大的信号。 */
    private static void printGcStats() {
        System.out.println();
        System.out.println("【3】GC 统计（回收次数 + 累计耗时）");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.println("  " + gc.getName() + "：回收 " + gc.getCollectionCount()
                    + " 次，累计耗时 " + gc.getCollectionTime() + " ms");
        }
        System.out.println("  → 连续几次 Full GC 之后内存还是下不来，基本可以判定有内存泄漏了。");
    }

    /**
     * 第 4 步：模拟「缓存只进不出」。每轮往 static 列表里塞 1MB 数组，边塞边看老年代使用率；
     * 一旦超过 70% 就打印预警（这就是 jmap -heap 里那条经验阈值）。
     */
    private static void simulateSlowLeak() {
        System.out.println();
        System.out.println("【4】模拟内存慢慢涨（每轮往 static 缓存里塞 1MB，而且永不释放）");

        while (simulatedCache.size() < MAX_SIMULATED_MB) {
            simulatedCache.add(new byte[1024 * 1024]); // 1MB 的数组，故意不释放
            int allocatedMb = simulatedCache.size();
            double oldGenRatio = oldGenUsedRatio();

            if (allocatedMb % 8 == 0) { // 每塞 8MB 报一次进度，方便直观看到「内存确实在涨」
                System.out.println("  已塞入 " + allocatedMb + " MB，老年代使用率 "
                        + String.format("%.1f", oldGenRatio * 100) + "%");
            }
            if (oldGenRatio >= 0.70) {
                System.out.println("  [警告] 第 " + allocatedMb + " MB：老年代使用率已达 "
                        + String.format("%.1f", oldGenRatio * 100) + "%，超过 70% 警戒线！");
                System.out.println("     线上到这一步就该抓 dump 分析了，别等它真的 OOM。");
                break;
            }
        }

        long totalBytes = 0; // 汇总还活着的 byte[]，模拟 MAT 里「谁占的内存最多」那张榜
        for (byte[] block : simulatedCache) {
            totalBytes += block.length;
        }
        long oldGenMax = findOldGenMaxBytes();
        String limitText = oldGenMax > 0 ? "，老年代上限 " + toMb(oldGenMax) + " MB" : "";
        System.out.println("  当前缓存里有 " + simulatedCache.size() + " 个对象，合计约 "
                + toMb(totalBytes) + " MB" + limitText);
        System.out.println("  → Eclipse MAT 的 Dominator Tree 就是干这件事：按「谁占得多」从大到小排榜。");
        System.out.println("  → 本机默认堆上限很大（比如 8GB），32MB 的缓存占比自然很小；");
        System.out.println("     想亲眼看到那条 70% 警戒线被触发，用小堆跑：java -Xmx64m Demo");
    }

    /**
     * 第 5 步：抓一份堆快照。等价于命令行 jmap -dump:format=b,file=heap.hprof <pid>，
     * 这里用 HotSpotDiagnosticMXBean.dumpHeap 以代码方式实现。
     */
    private static void dumpHeapSnapshot() {
        System.out.println();
        System.out.println("【5】抓堆快照（heap dump）");

        // 文件名带时间戳，保证不会和已有文件重名（dumpHeap 要求目标文件不存在）
        Path dumpFile = Path.of(System.getProperty("java.io.tmpdir"),
                "demo-heap-" + System.currentTimeMillis() + ".hprof");

        try {
            HotSpotDiagnosticMXBean diagnosticBean =
                    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (diagnosticBean == null) {
                System.out.println("  当前 JVM 不提供 HotSpotDiagnosticMXBean（非 HotSpot 虚拟机），跳过。");
                return;
            }
            // 第二个参数 true 表示只 dump 还活着的对象，相当于 jmap 的 :live 选项
            diagnosticBean.dumpHeap(dumpFile.toString(), true);
            System.out.println("  已生成 " + dumpFile);
            System.out.println("  文件大小 " + toMb(Files.size(dumpFile)) + " MB");
            System.out.println("  → 用 Eclipse MAT 或 JVisualVM 打开它，看 Dominator Tree 找出「谁占得最多」。");
        } catch (Exception e) {
            System.out.println("  抓快照失败（不影响前面的分析）：" + e); // 比如磁盘空间不够
        } finally {
            try {
                // 演示用的文件，看完就删，别把磁盘弄脏
                if (Files.deleteIfExists(dumpFile)) {
                    System.out.println("  演示结束，已删除临时 dump 文件。");
                }
            } catch (Exception ignored) {
                // 删不掉也不影响主流程
            }
        }
    }

    /**
     * 找出「老年代」那个池子。不同垃圾回收器名字不一样
     * （PS Old Gen / G1 Old Gen / Tenured Gen），所以按关键字匹配。
     */
    private static MemoryPoolMXBean findOldGenPool() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            if (name.contains("Old") || name.contains("Tenured")) {
                return pool;
            }
        }
        return null;
    }

    /** 老年代使用率 = 已用 / 上限。取不到老年代信息时，退而用整个堆的使用率代替。 */
    private static double oldGenUsedRatio() {
        MemoryPoolMXBean oldGen = findOldGenPool();
        if (oldGen != null && oldGen.getUsage() != null && oldGen.getUsage().getMax() > 0) {
            return (double) oldGen.getUsage().getUsed() / oldGen.getUsage().getMax();
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0.0;
    }

    /** 老年代的上限（字节）。拿不到就返回 -1，toMb 会显示成「-」。 */
    private static long findOldGenMaxBytes() {
        MemoryPoolMXBean oldGen = findOldGenPool();
        return oldGen != null && oldGen.getUsage() != null ? oldGen.getUsage().getMax() : -1;
    }

    /** 把字节数换成 MB，读起来更直观；传进来负数表示「没设上限」，显示成短横。 */
    private static String toMb(long bytes) {
        return bytes < 0 ? "-" : String.format("%.1f", bytes / 1024.0 / 1024.0);
    }
}
