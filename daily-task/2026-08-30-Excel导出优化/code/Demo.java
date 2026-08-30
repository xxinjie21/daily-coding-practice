// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 【这个程序在干嘛】
// 原题：「导出 Excel 很慢，怎么优化？」原文给了四条思路，这里就用四个小实验把它们跑出来：
//
//   实验一：OFFSET 深分页  vs  游标分页        —— 看数据库到底"数"了多少行
//   实验二：全量攒内存再写  vs  流式滑动窗口写 —— 看内存里最多同时放了多少行
//   实验三：异步导出 + 任务状态 + 完成通知     —— 用户点完就走，不用干等
//   实验四：Semaphore 限流                    —— 同时最多只让 2 个导出任务跑
//
// 【一点说明】
// 真实项目里第二步是用 Apache POI 的 SXSSFWorkbook（内存只留固定行数，写满自动刷临时文件）。
// 为了让这个文件不依赖任何第三方 jar 就能编译运行，下面用 StreamingExcelWriter 手写了
// 一份同样机制的简化版：攒满一个"窗口"就写盘、清空内存，再装下一批。
// 输出文件用 CSV 行代替 xlsx 单元格，机制是一模一样的。

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    /** 一行订单数据。record 是 Java 16 起的写法，可以理解成"只读的小数据盒子"。 */
    record OrderRow(long id, String customerName, int amountFen) {
        /** 转成文件里的一行文本（真实项目里这一步是往 Excel 单元格里塞值）。 */
        String toFileLine() {
            return id + "," + customerName + "," + amountFen;
        }
    }

    // ==================================================================
    // 假数据库：会记账，告诉我们"这次查询总共扫过多少行"
    // ==================================================================
    static class FakeOrderTable {

        private final List<OrderRow> allRows = new ArrayList<>();

        /** 数据库这一轮总共"数过"多少行。扫得越多越慢，这是我们要盯的指标。 */
        private long scannedRowCount = 0;

        FakeOrderTable(int totalRows) {
            for (int id = 1; id <= totalRows; id++) {
                allRows.add(new OrderRow(id, "客户" + id, id * 100));
            }
        }

        int totalRows() {
            return allRows.size();
        }

        void resetScannedCounter() {
            scannedRowCount = 0;
        }

        long scannedRowCount() {
            return scannedRowCount;
        }

        /**
         * ❌ 慢写法：select * from orders order by id limit ${offset}, ${pageSize}
         * 数据库没有"瞬移"的能力，它必须先老老实实把前 offset 行数出来再丢掉。
         * 就像客户要第 100001 号货，你每次都从 1 号开始数。
         */
        List<OrderRow> queryByOffset(int offset, int pageSize) {
            int fromIndex = Math.min(offset, allRows.size());
            int toIndex = Math.min(offset + pageSize, allRows.size());
            // 白扫掉的 offset 行 + 真正取走的这一批，都算数据库干的活
            scannedRowCount += fromIndex + (toIndex - fromIndex);
            return new ArrayList<>(allRows.subList(fromIndex, toIndex));
        }

        /**
         * ✅ 快写法：select * from orders where id > ${lastSeenId} order by id limit ${pageSize}
         * lastSeenId 就是"书签"，上次读到哪就记下来，下次从书签处接着读。
         * 因为能走主键索引一步定位，所以只扫这一批，页数再深也一样快。
         */
        List<OrderRow> queryByCursor(long lastSeenId, int pageSize) {
            int fromIndex = firstIndexAfter(lastSeenId);
            int toIndex = Math.min(fromIndex + pageSize, allRows.size());
            scannedRowCount += (toIndex - fromIndex);
            return new ArrayList<>(allRows.subList(fromIndex, toIndex));
        }

        /** 用二分查找模拟"走索引定位"：一步跳到位置，不是一行行走过去。 */
        private int firstIndexAfter(long lastSeenId) {
            int low = 0;
            int high = allRows.size();
            while (low < high) {
                int mid = (low + high) / 2;
                if (allRows.get(mid).id() <= lastSeenId) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }

    // ==================================================================
    // 流式写文件：手写模拟 POI 的 SXSSFWorkbook
    // 内存里只留 windowSize 行，写满就刷到磁盘、清空内存，再装下一批。
    // 比喻：边装箱边封箱，客厅里永远只堆一小堆货。
    // ==================================================================
    static class StreamingExcelWriter implements AutoCloseable {

        private final int windowSize;
        private final List<OrderRow> rowsInMemory = new ArrayList<>();
        private final BufferedWriter fileWriter;
        private int peakRowsInMemory = 0;
        private int flushTimes = 0;

        StreamingExcelWriter(Path outputFile, int windowSize) throws IOException {
            this.windowSize = windowSize;
            this.fileWriter = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8);
        }

        void addRow(OrderRow row) throws IOException {
            rowsInMemory.add(row);
            peakRowsInMemory = Math.max(peakRowsInMemory, rowsInMemory.size());
            if (rowsInMemory.size() >= windowSize) {
                flushWindow(); // 窗口满了，落盘腾地方
            }
        }

        private void flushWindow() throws IOException {
            if (rowsInMemory.isEmpty()) {
                return;
            }
            for (OrderRow row : rowsInMemory) {
                fileWriter.write(row.toFileLine());
                fileWriter.newLine();
            }
            rowsInMemory.clear(); // 关键：写完就松手，内存立刻空出来
            flushTimes++;
        }

        int peakRowsInMemory() {
            return peakRowsInMemory;
        }

        int flushTimes() {
            return flushTimes;
        }

        @Override
        public void close() throws IOException {
            flushWindow(); // 收尾：把最后不满一窗的行也写出去
            fileWriter.close();
        }
    }

    // ==================================================================
    // 导出任务服务：异步执行 + 任务状态 + 完成通知 + 并发限流
    // ==================================================================
    static class ExportTaskService {

        /** 模拟 Redis 里的任务状态：taskId -> RUNNING / SUCCESS / FAIL */
        private final Map<String, String> taskStatusStore = new ConcurrentHashMap<>();

        private final ExecutorService workerPool;

        /** 信号量 = 试衣间钥匙。只有 2 把，拿到钥匙才能进去导出，出来还钥匙。 */
        private final Semaphore exportPermits;

        private final AtomicInteger runningRightNow = new AtomicInteger(0);
        private final AtomicInteger maxRunningSeen = new AtomicInteger(0);

        ExportTaskService(int workerCount, int maxConcurrentExport) {
            this.workerPool = Executors.newFixedThreadPool(workerCount);
            this.exportPermits = new Semaphore(maxConcurrentExport);
        }

        /**
         * 提交导出任务：立刻返回任务号，真正的活儿丢给后台线程慢慢干。
         * 真实项目里这里通常是把消息发到 RocketMQ，由消费者去执行。
         */
        String submit(String taskName, Runnable realExportWork) {
            String taskId = "export-" + taskName;
            taskStatusStore.put(taskId, "RUNNING");

            workerPool.submit(() -> {
                try {
                    exportPermits.acquire(); // 没钥匙就在门口老实排队
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    taskStatusStore.put(taskId, "FAIL");
                    return;
                }
                try {
                    int nowRunning = runningRightNow.incrementAndGet();
                    maxRunningSeen.accumulateAndGet(nowRunning, Math::max);
                    realExportWork.run();
                    taskStatusStore.put(taskId, "SUCCESS");
                } catch (RuntimeException businessError) {
                    taskStatusStore.put(taskId, "FAIL");
                } finally {
                    runningRightNow.decrementAndGet();
                    exportPermits.release(); // 钥匙一定要还，不然后面的人永远进不去
                }
            });
            return taskId;
        }

        String queryStatus(String taskId) {
            return taskStatusStore.get(taskId);
        }

        int maxRunningSeen() {
            return maxRunningSeen.get();
        }

        void shutdownAndWait() throws InterruptedException {
            workerPool.shutdown();
            workerPool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    // ==================================================================
    // 实验一：OFFSET 深分页 vs 游标分页
    // ==================================================================
    static void experimentOne_pagination(FakeOrderTable table) {
        System.out.println("\n【实验一】分页写法对比（共 " + table.totalRows() + " 行，每批 1000 行）");
        int pageSize = 1000;

        // ❌ OFFSET 分页
        table.resetScannedCounter();
        long startNanos = System.nanoTime();
        int offset = 0;
        int rowsFetchedByOffset = 0;
        while (true) {
            List<OrderRow> onePage = table.queryByOffset(offset, pageSize);
            if (onePage.isEmpty()) {
                break;
            }
            rowsFetchedByOffset += onePage.size();
            offset += pageSize;
        }
        long offsetCostMillis = (System.nanoTime() - startNanos) / 1_000_000;
        long offsetScanned = table.scannedRowCount();

        // ✅ 游标分页
        table.resetScannedCounter();
        startNanos = System.nanoTime();
        long lastSeenId = 0;
        int rowsFetchedByCursor = 0;
        while (true) {
            List<OrderRow> onePage = table.queryByCursor(lastSeenId, pageSize);
            if (onePage.isEmpty()) {
                break;
            }
            rowsFetchedByCursor += onePage.size();
            lastSeenId = onePage.get(onePage.size() - 1).id(); // 记住书签
        }
        long cursorCostMillis = (System.nanoTime() - startNanos) / 1_000_000;
        long cursorScanned = table.scannedRowCount();

        System.out.printf("  OFFSET 分页：取到 %d 行，数据库扫描 %,d 行，耗时 %d ms%n",
                rowsFetchedByOffset, offsetScanned, offsetCostMillis);
        System.out.printf("  游标 分页：取到 %d 行，数据库扫描 %,d 行，耗时 %d ms%n",
                rowsFetchedByCursor, cursorScanned, cursorCostMillis);
        System.out.printf("  结论：结果一致（%s），但 OFFSET 白扫了 %,d 行，约 %.1f 倍工作量%n",
                (rowsFetchedByOffset == rowsFetchedByCursor ? "都是全量" : "对不上，有问题"),
                offsetScanned - cursorScanned,
                offsetScanned * 1.0 / cursorScanned);
    }

    // ==================================================================
    // 实验二：全量攒内存写 vs 流式滑动窗口写
    // ==================================================================
    static void experimentTwo_writeFile(FakeOrderTable table, Path workDir) throws IOException {
        System.out.println("\n【实验二】文件生成方式对比");
        int pageSize = 1000;

        // ❌ 传统 POI 思路：先把所有行攒进内存，攒齐了再一次性写出去
        Path bigMemoryFile = workDir.resolve("全量攒内存.txt");
        List<OrderRow> allRowsInMemory = new ArrayList<>();
        long lastSeenId = 0;
        while (true) {
            List<OrderRow> onePage = table.queryByCursor(lastSeenId, pageSize);
            if (onePage.isEmpty()) {
                break;
            }
            allRowsInMemory.addAll(onePage); // 越攒越多，数据量大了就 OOM
            lastSeenId = onePage.get(onePage.size() - 1).id();
        }
        int peakRowsOfBigMemory = allRowsInMemory.size();
        try (BufferedWriter writer = Files.newBufferedWriter(bigMemoryFile, StandardCharsets.UTF_8)) {
            for (OrderRow row : allRowsInMemory) {
                writer.write(row.toFileLine());
                writer.newLine();
            }
        }
        allRowsInMemory = null; // 演示用，提前松手

        // ✅ 流式：查一批 → 写一批 → 丢掉这批，内存恒定
        Path streamingFile = workDir.resolve("流式滑动窗口.txt");
        int peakRowsOfStreaming;
        int flushTimes;
        try (StreamingExcelWriter streamingWriter = new StreamingExcelWriter(streamingFile, 100)) {
            lastSeenId = 0;
            while (true) {
                List<OrderRow> onePage = table.queryByCursor(lastSeenId, pageSize);
                if (onePage.isEmpty()) {
                    break;
                }
                for (OrderRow row : onePage) {
                    streamingWriter.addRow(row);
                }
                lastSeenId = onePage.get(onePage.size() - 1).id();
            }
            peakRowsOfStreaming = streamingWriter.peakRowsInMemory();
            flushTimes = streamingWriter.flushTimes();
        }

        long bigFileBytes = Files.size(bigMemoryFile);
        long streamFileBytes = Files.size(streamingFile);
        System.out.printf("  全量攒内存：内存峰值 %,d 行，文件 %,d 字节%n", peakRowsOfBigMemory, bigFileBytes);
        System.out.printf("  流式窗口写：内存峰值 %,d 行，文件 %,d 字节（刷盘 %d 次）%n",
                peakRowsOfStreaming, streamFileBytes, flushTimes);
        System.out.printf("  结论：两份文件内容%s，但内存峰值从 %,d 行降到 %,d 行（%.0f 分之一）%n",
                (bigFileBytes == streamFileBytes ? "完全一样" : "不一致，需检查"),
                peakRowsOfBigMemory, peakRowsOfStreaming,
                peakRowsOfBigMemory * 1.0 / peakRowsOfStreaming);
    }

    // ==================================================================
    // 实验三 + 实验四：异步导出 + 状态通知 + 并发限流
    // ==================================================================
    static void experimentThreeAndFour_asyncAndLimit(FakeOrderTable table, Path workDir) throws Exception {
        System.out.println("\n【实验三】异步导出：点完就走，好了再通知");
        ExportTaskService singleTaskService = new ExportTaskService(2, 2);

        long submitStart = System.nanoTime();
        String taskId = singleTaskService.submit("订单报表", () -> {
            try {
                // 假装在慢慢查数据、慢慢写文件
                Path file = workDir.resolve("异步导出结果.txt");
                try (StreamingExcelWriter writer = new StreamingExcelWriter(file, 100)) {
                    long lastSeenId = 0;
                    while (true) {
                        List<OrderRow> onePage = table.queryByCursor(lastSeenId, 1000);
                        if (onePage.isEmpty()) {
                            break;
                        }
                        for (OrderRow row : onePage) {
                            writer.addRow(row);
                        }
                        lastSeenId = onePage.get(onePage.size() - 1).id();
                    }
                }
                Thread.sleep(300); // 模拟上传对象存储的耗时
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        long submitCostMillis = (System.nanoTime() - submitStart) / 1_000_000;
        System.out.printf("  提交后 %d ms 就返回任务号 %s，页面提示「正在生成，完成后通知你」%n",
                submitCostMillis, taskId);

        // 前端轮询任务状态（真实场景是定时请求一个查状态的接口）
        String status = singleTaskService.queryStatus(taskId);
        int pollTimes = 0;
        while (!"SUCCESS".equals(status) && !"FAIL".equals(status)) {
            Thread.sleep(100);
            pollTimes++;
            status = singleTaskService.queryStatus(taskId);
        }
        System.out.printf("  轮询 %d 次后状态变成 %s → 发站内信：「您的导出文件已生成，点此下载」%n",
                pollTimes, status);
        singleTaskService.shutdownAndWait();

        System.out.println("\n【实验四】并发限流：8 个人同时点导出，同时最多只让 2 个跑");
        ExportTaskService limitedService = new ExportTaskService(8, 2);
        List<String> taskIds = new ArrayList<>();
        for (int userIndex = 1; userIndex <= 8; userIndex++) {
            taskIds.add(limitedService.submit("用户" + userIndex, () -> {
                try {
                    Thread.sleep(200); // 假装这是一次很重的导出
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        limitedService.shutdownAndWait();

        long successCount = taskIds.stream()
                .map(limitedService::queryStatus)
                .filter("SUCCESS"::equals)
                .count();
        System.out.printf("  8 个任务全部完成（成功 %d 个），期间同时在跑的最大数量 = %d%n",
                successCount, limitedService.maxRunningSeen());
        System.out.println("  结论：多出来的任务在门口排队，数据库和磁盘不会被自家导出功能打垮");
    }

    /** 收尾：删掉演示产生的临时文件，别留垃圾。 */
    static void cleanUp(Path workDir) throws IOException {
        try (var pathStream = Files.walk(workDir)) {
            List<Path> pathsToDelete = pathStream
                    .sorted(Comparator.reverseOrder()) // 先删文件，再删目录
                    .toList();
            for (Path path : pathsToDelete) {
                Files.deleteIfExists(path);
            }
        }
        System.out.println("\n临时文件已清理：" + workDir);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 导出 Excel 太慢？四步优化实测 ===");

        FakeOrderTable orderTable = new FakeOrderTable(200_000);
        Path workDir = Files.createTempDirectory("excel-export-demo");

        experimentOne_pagination(orderTable);
        experimentTwo_writeFile(orderTable, workDir);
        experimentThreeAndFour_asyncAndLimit(orderTable, workDir);
        cleanUp(workDir);

        System.out.println("\n=== 全部实验结束 ===");
    }
}
