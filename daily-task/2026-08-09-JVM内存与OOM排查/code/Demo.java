// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 面试题：「怎么分析 JVM 当前的内存占用情况？OOM 后怎么分析？」
//
// 原题给的排查手法是一串命令行工具：
//   1) jstat -gc <pid>            看各内存区的实时用量 + GC 次数和耗时
//   2) jmap -heap <pid>           看堆的配置和使用率（老年代长期 >70% 要警惕）
//   3) jmap -dump:...             抓堆快照，丢给 Eclipse MAT 看「主导集」找元凶
//   4) -XX:+HeapDumpOnOutOfMemoryError   OOM 时自动留下快照
//   5) -Xlog:gc*:file=gc.log      GC 日志回溯 OOM 前的征兆
//
// 命令行工具没法写进 Demo，但它们读的数据 Java 程序自己也能读到：
// java.lang.management 这套 MXBean 就是 jstat / jmap 的数据来源。
// 所以本 Demo 用「代码版」把上面 5 件事逐个演示一遍，全部是真实数据、真实分配：
//
//   实验1  代码版 jstat -gc      —— 打印各内存池用量 + GC 次数耗时
//   实验2  代码版 jmap -heap     —— 打印堆配置 + 老年代 70% 阈值判定
//   实验3  泄漏 vs 正常 对照实验 —— 真分配内存，看「Full GC 后降不降得下来」
//   实验4  代码版 jmap -dump     —— 真的生成一个 .hprof 快照文件再删掉
//   实验5  MAT 主导集迷你版      —— 手算 retained size，演示 MAT 怎么一眼锁定元凶
//   实验6  GC 日志解析           —— 从日志文本里自动判断「这是不是泄漏」
//
// 面向新手：变量名尽量写全、每步都有大白话注释，顺着注释读就能懂。
// ============================================================================

import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Demo {

    public static void main(String[] args) {
        System.out.println("=========== JVM 内存分析与 OOM 排查 · 六个实验 ===========");
        System.out.println("当前进程 pid = " + ProcessHandle.current().pid()
                + "（线上就是拿这个 pid 去执行 jstat / jmap）");

        experiment1_jstatStyleOverview();
        experiment2_jmapHeapStyleReport();
        experiment3_leakVersusNormal();
        experiment4_dumpHeapByCode();
        experiment5_miniDominatorTree();
        experiment6_parseGcLog();

        // 回头再看一眼 GC 统计，和实验1 开头那张全 0 的表对比 —— 这就是 jstat 的正确用法：看差值
        printTitle("收尾：再看一次 GC 统计（对比实验1 开头的全 0）");
        printGcStatistics();
        System.out.println("次数涨上来了，是因为实验3 我们主动喊了很多次 Full GC。");
        System.out.println("线上如果没人喊 System.gc()，Full GC 次数却蹭蹭涨，那就得查了。");

        System.out.println();
        System.out.println("=========== 全部实验结束 ===========");
    }

    // =======================================================================
    // 实验1：代码版 jstat -gc
    // -----------------------------------------------------------------------
    // jstat -gc 打出来的那一大排数字，本质就是「每个内存池现在用了多少」
    // 加上「垃圾回收器一共跑了几次、花了多少毫秒」。
    // JVM 把这些数据通过 MemoryPoolMXBean / GarbageCollectorMXBean 暴露出来，
    // 我们直接读就行，不用真去敲命令。
    //
    // 名词大白话：
    //   内存池(MemoryPool) —— JVM 把堆切成几个抽屉分类放对象。
    //     Eden      新对象出生的地方，像「新生儿病房」，绝大多数对象活不过一次 GC
    //     Survivor  从 Eden 活下来的对象暂住区，像「观察病房」
    //     Old Gen   老年代，反复活下来的对象才搬进来，像「长期住户楼」
    //     Metaspace 元空间，放类的信息（不在堆里，在本地内存）
    // =======================================================================
    private static void experiment1_jstatStyleOverview() {
        printTitle("实验1：代码版 jstat -gc —— 各内存池用量 + GC 统计");

        System.out.println("[堆内存池]  max 显示 - 表示这块区域大小是 GC 动态调整的，没有固定上限");
        System.out.printf("%-34s %12s %12s %12s %8s%n", "POOL", "USED(MB)", "COMMIT(MB)", "MAX(MB)", "USED%");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                printPoolRow(pool);
            }
        }

        System.out.println();
        System.out.println("[非堆内存池]  元空间等，OOM 有时是死在这里，别只盯着堆");
        System.out.printf("%-34s %12s %12s %12s %8s%n", "POOL", "USED(MB)", "COMMIT(MB)", "MAX(MB)", "USED%");
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.NON_HEAP) {
                printPoolRow(pool);
            }
        }

        System.out.println();
        System.out.println("[GC 统计]  这就是 jstat 里的 YGC/YGCT/FGC/FGCT 四列");
        printGcStatistics();
        System.out.println("刚启动所以全是 0，这很正常 —— jstat 的价值不在某一眼的数字，而在于「差值」。");
        System.out.println("实际排查要连着看 30 秒以上：GC 次数飞涨且每次 GC 后用量降不回去 = 有鬼；");
        System.out.println("次数多但用量能降下来 = 只是业务忙。（程序最后会再打一次，看看跑完实验后涨了多少）");
    }

    /** 打印 GC 次数与累计耗时，程序开头和结尾各打一次，方便对比差值。 */
    private static void printGcStatistics() {
        System.out.printf("%-34s %10s %12s%n", "COLLECTOR", "COUNT", "TIME(ms)");
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf("%-34s %10d %12d%n",
                    collector.getName(), collector.getCollectionCount(), collector.getCollectionTime());
        }
    }

    /** 把一个内存池打成一行，单位换算成 MB，方便肉眼看。 */
    private static void printPoolRow(MemoryPoolMXBean pool) {
        MemoryUsage usage = pool.getUsage();
        if (usage == null) {
            return;
        }
        long maxBytes = usage.getMax();
        // max 为 -1 表示「没设上限」，比如元空间默认不限，打个横杠更直观
        String maxText = (maxBytes < 0) ? "-" : String.valueOf(toMegaBytes(maxBytes));
        String usedPercentText = (maxBytes <= 0)
                ? "-"
                : String.format("%.1f%%", usage.getUsed() * 100.0 / maxBytes);
        System.out.printf("%-34s %12d %12d %12s %8s%n",
                pool.getName(), toMegaBytes(usage.getUsed()), toMegaBytes(usage.getCommitted()),
                maxText, usedPercentText);
    }

    // =======================================================================
    // 实验2：代码版 jmap -heap
    // -----------------------------------------------------------------------
    // jmap -heap 关心的是「整个堆的家底」：最大能长到多大、现在用了多少、
    // 尤其是老年代用了百分之多少。原题的经验值：老年代长期超 70% 就该警惕。
    // 为什么盯老年代？因为老年代里的对象是「反复活下来的」，
    // 正常业务对象早该被回收掉；它一直涨，说明有东西被谁攥着不放。
    // =======================================================================
    private static void experiment2_jmapHeapStyleReport() {
        printTitle("实验2：代码版 jmap -heap —— 堆家底 + 老年代 70% 告警");

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();

        System.out.println("堆  : init=" + toMegaBytes(heapUsage.getInit()) + "MB"
                + "  used=" + toMegaBytes(heapUsage.getUsed()) + "MB"
                + "  committed=" + toMegaBytes(heapUsage.getCommitted()) + "MB"
                + "  max=" + toMegaBytes(heapUsage.getMax()) + "MB");
        System.out.println("非堆: used=" + toMegaBytes(nonHeapUsage.getUsed()) + "MB"
                + "  committed=" + toMegaBytes(nonHeapUsage.getCommitted()) + "MB");
        System.out.println("（没写 -Xmx 时，max 默认约等于物理内存的 1/4，所以这台机器上是上面这个值）");

        MemoryPoolMXBean oldGenerationPool = findOldGenerationPool();
        if (oldGenerationPool == null) {
            System.out.println("没找到老年代内存池（某些 GC 不分代），跳过阈值判定。");
            return;
        }
        MemoryUsage oldUsage = oldGenerationPool.getUsage();
        long oldMax = oldUsage.getMax() > 0 ? oldUsage.getMax() : heapUsage.getMax();
        double oldUsedPercent = oldUsage.getUsed() * 100.0 / oldMax;
        System.out.printf("老年代 [%s] 使用率 = %.2f%%%n", oldGenerationPool.getName(), oldUsedPercent);
        System.out.println(oldUsedPercent > 70
                ? ">>> 告警：老年代已过 70%，再涨就要 Full GC 甚至 OOM 了，赶紧抓 dump！"
                : ">>> 正常：老年代还很宽裕，暂时不用管。");
    }

    // =======================================================================
    // 实验3：泄漏 vs 正常，对照实验（本 Demo 的核心）
    // -----------------------------------------------------------------------
    // 判断内存泄漏最硬的证据，不是「内存涨了」——业务忙的时候内存本来就会涨。
    // 真正的证据是原题那句：「连续几次 Full GC 后内存还是下不来」。
    //
    // 生活比喻：房间乱不代表你是囤积癖；但每次大扫除完地上还是那么多东西，
    //          说明有人把垃圾锁进柜子里，扫地阿姨(GC)扔不掉。
    //
    // 所以我们做 A/B 两组，各分配同样多的内存，唯一区别是「留没留引用」：
    //   A 组（泄漏）：把对象塞进一个 static 集合 —— 相当于锁进柜子，GC 不敢扔
    //   B 组（正常）：对象用完就撒手 —— GC 一扫就干净
    // 每轮都主动 System.gc() 触发一次 Full GC，再量「GC 之后还剩多少」。
    // =======================================================================

    /** A 组用的「柜子」：static 引用是最常见的泄漏源头，写业务时切记。 */
    private static final List<byte[]> LEAKING_CACHE = new ArrayList<>();

    private static void experiment3_leakVersusNormal() {
        printTitle("实验3：泄漏 vs 正常 —— 看 Full GC 之后内存降不降得下来");

        int rounds = 6;
        int blocksPerRound = 8000;     // 每轮 8000 个 1KB 的小对象 ≈ 8MB
        int blockSizeBytes = 1024;

        System.out.println("[A 组 · 泄漏] 每轮分配 8MB 并塞进 static 集合（模拟缓存只进不出）");
        System.out.println("列含义：ALIVE_AFTER_GC = Full GC 之后还活着的堆内存（相对起点）；OLD_GEN = 老年代当前用量");
        System.out.printf("%-8s %18s %14s%n", "ROUND", "ALIVE_AFTER_GC(MB)", "OLD_GEN(MB)");
        long leakBaseline = usedHeapAfterFullGc();
        for (int round = 1; round <= rounds; round++) {
            for (int i = 0; i < blocksPerRound; i++) {
                LEAKING_CACHE.add(new byte[blockSizeBytes]);   // 关键：加进集合 = 被攥住了
            }
            long aliveAfterGc = usedHeapAfterFullGc();
            System.out.printf("%-8d %18d %14d%n", round,
                    toMegaBytes(aliveAfterGc - leakBaseline), toMegaBytes(usedOfOldGeneration()));
        }

        // 中场：证明「GC 不是无能，是我们自己攥着不放」——手一松，内存立刻就回来了
        System.out.println();
        long oldGenBeforeClear = usedOfOldGeneration();
        LEAKING_CACHE.clear();                 // 相当于线上把那个泄漏的缓存清掉
        usedHeapAfterFullGc();
        System.out.println("[中场] 把 A 组那个 static 柜子清空，再 Full GC 一次：");
        System.out.println("       老年代 " + toMegaBytes(oldGenBeforeClear) + "MB -> "
                + toMegaBytes(usedOfOldGeneration()) + "MB");
        System.out.println("       说明这几十 MB 一直不降，不是 GC 无能，是我们自己攥着引用不放。");

        System.out.println();
        System.out.println("[B 组 · 正常] 同样每轮分配 8MB，但用完就撒手，不留引用");
        System.out.printf("%-8s %18s %14s%n", "ROUND", "ALIVE_AFTER_GC(MB)", "OLD_GEN(MB)");
        long normalBaseline = usedHeapAfterFullGc();
        for (int round = 1; round <= rounds; round++) {
            List<byte[]> temporaryBuffer = new ArrayList<>();
            for (int i = 0; i < blocksPerRound; i++) {
                temporaryBuffer.add(new byte[blockSizeBytes]);
            }
            temporaryBuffer = null;    // 撒手：这一坨立刻变成垃圾
            long aliveAfterGc = usedHeapAfterFullGc();
            System.out.printf("%-8d %18d %14d%n", round,
                    toMegaBytes(aliveAfterGc - normalBaseline), toMegaBytes(usedOfOldGeneration()));
        }

        System.out.println();
        System.out.println("结论：A 组 ALIVE_AFTER_GC 一路往上爬（每轮 +8MB 左右），B 组一直贴着 0 不动。");
        System.out.println("     线上就是靠这条曲线定性的：内存涨了不可怕，Full GC 之后降不回去才可怕。");
    }

    // =======================================================================
    // 实验4：代码版 jmap -dump
    // -----------------------------------------------------------------------
    // jmap -dump:format=b,file=heap.hprof <pid> 是从外面抓快照；
    // JVM 也允许程序自己抓，用的是 HotSpotDiagnosticMXBean.dumpHeap()。
    // 很多公司的监控 agent 就是这么做的：发现老年代超阈值，自动抓一份存档。
    //
    // live=true 表示「只抓还活着的对象」，会先做一次 Full GC，文件小很多。
    // 抓出来的 .hprof 文件，用 Eclipse MAT 或 JVisualVM 打开就能看谁占了内存。
    // =======================================================================
    private static void experiment4_dumpHeapByCode() {
        printTitle("实验4：代码版 jmap -dump —— 程序自己生成 .hprof 快照");

        File dumpFile = new File(System.getProperty("java.io.tmpdir"), "demo-heap-" + System.nanoTime() + ".hprof");
        try {
            HotSpotDiagnosticMXBean diagnosticBean =
                    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            long startMillis = System.currentTimeMillis();
            diagnosticBean.dumpHeap(dumpFile.getAbsolutePath(), true);   // true = 只抓存活对象
            long costMillis = System.currentTimeMillis() - startMillis;

            System.out.println("快照已生成：" + dumpFile.getAbsolutePath());
            System.out.println("文件大小 = " + toMegaBytes(dumpFile.length()) + " MB，耗时 = " + costMillis + " ms");
            System.out.println("提醒：抓 dump 会 STW（Stop The World，全世界暂停），");
            System.out.println("     几个 G 的堆可能卡住服务好几秒，线上务必先把这台机器摘出负载均衡再抓。");
        } catch (Exception e) {
            System.out.println("生成快照失败（可能是非 HotSpot 虚拟机）：" + e);
        } finally {
            // Demo 不留垃圾文件，抓完就删；真实排查当然要留着丢给 MAT
            if (dumpFile.exists() && dumpFile.delete()) {
                System.out.println("（演示完毕，快照文件已删除）");
            }
        }

        System.out.println();
        System.out.println("OOM 自动抓拍的启动参数（新项目上线第一天就该加上，是后悔药）：");
        System.out.println("  -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dumps");
        System.out.println("不加的话，OOM 一发生进程就没了，什么线索都不剩，只能干等它再炸一次。");
    }

    // =======================================================================
    // 实验5：MAT「主导集 / Dominator Tree」迷你版
    // -----------------------------------------------------------------------
    // 拿到 hprof 之后，MAT 最有用的功能就是主导树。它回答一个问题：
    //   「如果把某个对象干掉，能顺带释放多少内存？」——这叫 retained size（保留大小）。
    //
    // 对比一下两个概念：
    //   shallow size   这个对象自己占几个字节（一个 Map 对象本身就几十字节）
    //   retained size  干掉它能连带回收多少（那个 Map 连着的 100 万条数据全算上）
    // 所以看 shallow 永远找不到元凶，看 retained 一眼就能看到。
    //
    // 生活比喻：一个书架(shallow)本身没多重，但你把书架搬走，
    //          上面 500 本书也跟着走了，这 500 本书就是它的 retained。
    //          注意：如果某本书同时也被旁边的桌子压着(被两个根引用)，
    //          那搬走书架它也不会消失，就不算进书架的 retained。
    //
    // 下面用一个小对象图手算 retained size，算法就是最朴素的定义：
    //   「屏蔽掉候选对象后，从 GC Root 还能不能走到某节点」——走不到的，就归它。
    // =======================================================================
    private static void experiment5_miniDominatorTree() {
        printTitle("实验5：MAT 主导集迷你版 —— 谁才是真正的内存元凶");

        // 建一张小小的对象引用图（数字是 shallow size，单位 KB，方便看）
        Map<String, ObjectNode> objectGraph = new LinkedHashMap<>();
        addNode(objectGraph, "SessionCache",       1);     // 一个 static Map，自己没多大
        addNode(objectGraph, "UserSession x100万", 400_000);   // 但它挂着 100 万个会话 ≈ 400MB
        addNode(objectGraph, "ConfigHolder",       1);
        addNode(objectGraph, "ConfigItem x2000",   2_000);
        addNode(objectGraph, "SharedDictionary",   50_000);    // 被两边共同引用的字典
        addNode(objectGraph, "ThreadPool",         120);
        addNode(objectGraph, "PendingTask x300",   3_000);

        link(objectGraph, "SessionCache", "UserSession x100万");
        link(objectGraph, "SessionCache", "SharedDictionary");
        link(objectGraph, "ConfigHolder", "ConfigItem x2000");
        link(objectGraph, "ConfigHolder", "SharedDictionary");   // 注意：字典有两个爹
        link(objectGraph, "ThreadPool", "PendingTask x300");

        // GC Root：真实世界里就是静态变量、活跃线程栈上的局部变量等
        List<String> gcRoots = List.of("SessionCache", "ConfigHolder", "ThreadPool");

        System.out.printf("%-22s %14s %16s%n", "OBJECT", "shallow(KB)", "retained(KB)");
        for (String rootName : gcRoots) {
            long retainedKb = calculateRetainedSize(objectGraph, gcRoots, rootName);
            System.out.printf("%-22s %14d %16d%n",
                    rootName, objectGraph.get(rootName).shallowSizeKb, retainedKb);
        }

        System.out.println();
        System.out.println("看出来了吧：三个对象自己都只有 1KB 上下（shallow 差不多），");
        System.out.println("但 SessionCache 的 retained 是 40 万 KB —— 这就是 MAT 直接把它顶到第一行的原因。");
        System.out.println("SharedDictionary 那 5 万 KB 谁也不算，因为两个根都能到它，删谁都释放不掉，");
        System.out.println("MAT 里这种会单独归到「共享」部分，也提醒你：光删一个引用没用。");
    }

    // =======================================================================
    // 实验6：GC 日志解析 —— 让日志自己说话
    // -----------------------------------------------------------------------
    // 加了 -Xlog:gc*:file=gc.log 之后，每次 GC 都会留一行，格式类似：
    //   [12.345s][info][gc] GC(7) Pause Full (System.gc()) 1800M->1750M(2048M) 520.123ms
    // 关键就是箭头两边：GC 前用了多少 -> GC 后剩多少（括号里是堆总大小）。
    //
    // 判定规则很朴素：连着几次 Full GC，箭头右边的数字一直往上走、降不下来，
    // 就是泄漏；如果每次都能唰地降回低位，那只是业务量大而已。
    // =======================================================================
    private static void experiment6_parseGcLog() {
        printTitle("实验6：GC 日志解析 —— 自动判断这是泄漏还是正常");

        String leakingGcLog = """
                [10.101s][info][gc] GC(3) Pause Full (Ergonomics) 1600M->1180M(2048M) 480.221ms
                [25.510s][info][gc] GC(6) Pause Full (Ergonomics) 1780M->1520M(2048M) 610.774ms
                [41.902s][info][gc] GC(9) Pause Full (Ergonomics) 1900M->1810M(2048M) 733.118ms
                [58.334s][info][gc] GC(12) Pause Full (Ergonomics) 2020M->1990M(2048M) 902.556ms
                """;

        String healthyGcLog = """
                [11.010s][info][gc] GC(4) Pause Full (System.gc()) 1500M->300M(2048M) 260.412ms
                [33.220s][info][gc] GC(8) Pause Full (System.gc()) 1620M->312M(2048M) 271.905ms
                [55.480s][info][gc] GC(13) Pause Full (System.gc()) 1710M->298M(2048M) 255.330ms
                """;

        analyzeGcLog("疑似泄漏的日志", leakingGcLog);
        System.out.println();
        analyzeGcLog("健康的日志", healthyGcLog);
    }

    /** 把日志里每次 Full GC 的「回收后剩余」抽出来，看它是不是一路走高。 */
    private static void analyzeGcLog(String label, String gcLogText) {
        // 正则抓 "1600M->1180M(2048M)" 这一段
        Pattern fullGcPattern = Pattern.compile("Pause Full.*?(\\d+)M->(\\d+)M\\((\\d+)M\\)");
        List<Integer> afterGcList = new ArrayList<>();

        System.out.println("【" + label + "】  列含义：BEFORE=GC 前用量，AFTER=GC 后残留，FREED=这次回收掉多少");
        System.out.printf("%-8s %12s %12s %12s%n", "NO", "BEFORE(M)", "AFTER(M)", "FREED(M)");
        for (String line : gcLogText.split("\n")) {
            Matcher matcher = fullGcPattern.matcher(line);
            if (matcher.find()) {
                int beforeMb = Integer.parseInt(matcher.group(1));
                int afterMb = Integer.parseInt(matcher.group(2));
                afterGcList.add(afterMb);
                System.out.printf("%-8d %12d %12d %12d%n",
                        afterGcList.size(), beforeMb, afterMb, beforeMb - afterMb);
            }
        }

        // 判定：只要「GC 后剩余」是单调上升的，就报泄漏
        boolean keepsRising = true;
        for (int i = 1; i < afterGcList.size(); i++) {
            if (afterGcList.get(i) <= afterGcList.get(i - 1)) {
                keepsRising = false;
                break;
            }
        }
        System.out.println(keepsRising && afterGcList.size() >= 3
                ? ">>> 判定：连续 Full GC 后残留一路走高 —— 内存泄漏，去抓 dump 找元凶。"
                : ">>> 判定：每次都能回落到低位 —— 只是业务压力大，不是泄漏，考虑调大堆或降分配速率。");
    }

    // ================================ 小工具 ================================

    /** 触发一次 Full GC，然后返回「还活着的堆内存」。这是泄漏判定最可靠的指标。 */
    private static long usedHeapAfterFullGc() {
        // System.gc() 只是「建议」JVM 回收，连喊两次并稍等一下，让它真的动手
        System.gc();
        sleepQuietly(120);
        System.gc();
        sleepQuietly(120);
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** 找到老年代那个内存池。不同 GC 名字不一样，所以按关键字模糊匹配。 */
    private static MemoryPoolMXBean findOldGenerationPool() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            if (pool.getType() == MemoryType.HEAP && (name.contains("Old") || name.contains("Tenured"))) {
                return pool;
            }
        }
        return null;
    }

    private static long usedOfOldGeneration() {
        MemoryPoolMXBean oldPool = findOldGenerationPool();
        return oldPool == null ? 0L : oldPool.getUsage().getUsed();
    }

    private static long toMegaBytes(long bytes) {
        return bytes / 1024 / 1024;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("---------------------------------------------------------------");
        System.out.println(title);
        System.out.println("---------------------------------------------------------------");
    }

    // ---------------- 实验5 用到的迷你对象图 ----------------

    /** 对象图里的一个节点：名字 + 自己占多大 + 引用了谁。 */
    private static class ObjectNode {
        final String name;
        final long shallowSizeKb;
        final List<String> references = new ArrayList<>();

        ObjectNode(String name, long shallowSizeKb) {
            this.name = name;
            this.shallowSizeKb = shallowSizeKb;
        }
    }

    private static void addNode(Map<String, ObjectNode> graph, String name, long shallowSizeKb) {
        graph.put(name, new ObjectNode(name, shallowSizeKb));
    }

    private static void link(Map<String, ObjectNode> graph, String fromName, String toName) {
        graph.get(fromName).references.add(toName);
    }

    /**
     * 算 retained size：假装把 candidate 删掉，从其它 GC Root 出发还能走到的节点都不算它的；
     * 走不到的那些（只有它一个人攥着）加起来，就是删掉它能释放的内存。
     */
    private static long calculateRetainedSize(Map<String, ObjectNode> graph, List<String> gcRoots, String candidate) {
        // 第一步：候选还在时，从它出发能碰到的所有对象
        Set<String> reachableFromCandidate = new HashSet<>();
        walk(graph, candidate, reachableFromCandidate);

        // 第二步：把候选整个屏蔽掉，看别的根还能走到哪些
        Set<String> reachableWithoutCandidate = new HashSet<>();
        for (String root : gcRoots) {
            if (!root.equals(candidate)) {
                walk(graph, root, reachableWithoutCandidate);
            }
        }

        // 第三步：只有候选能到、别人到不了的，才是它的「独占家当」
        long retainedKb = 0;
        for (String name : reachableFromCandidate) {
            if (!reachableWithoutCandidate.contains(name)) {
                retainedKb += graph.get(name).shallowSizeKb;
            }
        }
        return retainedKb;
    }

    /** 深度优先遍历，把从 start 能走到的节点都收进 visited。 */
    private static void walk(Map<String, ObjectNode> graph, String start, Set<String> visited) {
        if (!visited.add(start)) {
            return;   // 走过了就别绕圈
        }
        for (String next : graph.get(start).references) {
            walk(graph, next, visited);
        }
    }
}
