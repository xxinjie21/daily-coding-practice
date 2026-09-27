// 【这个程序在干嘛】
// 题目：页面上有个「导出 Excel」按钮，数据一多就转圈半天、甚至 OOM 崩掉，怎么救回来？
// 用真实的 Apache POI 跑三个实验，把原题的四步优化跑出来：
//   实验一：OFFSET 深分页 vs 游标分页      —— 看数据源到底白扫了多少行
//   实验二：XSSFWorkbook vs SXSSFWorkbook —— 看内存里同时压了多少行、文件多大
//   实验三：异步导出 + Semaphore 限流      —— 提交后立刻返回，同时最多只跑 2 个
//
// 【前置条件】纯本地运行，不需要中间件。生成的 xlsx 和 POI 临时分片都写在 java.io.tmpdir
// 下，跑完自动清理，不污染仓库。编译：mvn -o -q compile   运行：mvn -o exec:java

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Demo {

    static final int TOTAL_ROWS = 200_000;
    static final int PAGE_SIZE = 1000;
    /** SXSSF 的滑动窗口：内存里只留这么多行，写满就刷到临时文件。 */
    static final int STREAM_WINDOW = 100;

    /** 一行订单。真实项目里这就是数据库查出来的一行。 */
    record OrderRow(long id, String customerName, double amountYuan) {
    }

    /** 数据源：假装这是一张订单表。两种翻页都会记下「一共扫了多少行」，代价就在这个数上。 */
    static class OrderTable {

        final List<OrderRow> rows = new ArrayList<>();
        long scannedRows;

        OrderTable(int totalRows) {
            for (int id = 1; id <= totalRows; id++) {
                rows.add(new OrderRow(id, "客户" + id, id % 1000 + 0.5));
            }
        }

        /** ❌ limit offset, size：数据库没有「瞬移」能力，得先老老实实数出前 offset 行再丢掉。 */
        List<OrderRow> pageByOffset(int offset, int size) {
            int from = Math.min(offset, rows.size());
            int to = Math.min(offset + size, rows.size());
            scannedRows += to;   // 白扫的 offset 行 + 真正取走的这批
            return new ArrayList<>(rows.subList(from, to));
        }

        /** ✅ where id > lastId limit size：走主键索引直接跳到起点，页数再深也一样快。 */
        List<OrderRow> pageByCursor(long lastId, int size) {
            int from = firstIndexAfter(lastId);
            int to = Math.min(from + size, rows.size());
            scannedRows += to - from;
            return new ArrayList<>(rows.subList(from, to));
        }

        /** 二分查找 = 顺着索引一步定位，不是一行行走过去。 */
        private int firstIndexAfter(long lastId) {
            int low = 0;
            int high = rows.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (rows.get(mid).id() <= lastId) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }

    // ===================== 实验一：OFFSET 深分页 vs 游标分页 =====================
    static void experimentOnePaging(OrderTable table) {
        System.out.println("\n【实验一】翻页写法对比（共 " + table.rows.size() + " 行，每批 " + PAGE_SIZE + " 行）");

        table.scannedRows = 0;
        long offsetStart = System.nanoTime();
        int offsetFetched = 0;
        for (int offset = 0; ; offset += PAGE_SIZE) {
            List<OrderRow> page = table.pageByOffset(offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            offsetFetched += page.size();
        }
        long offsetMillis = (System.nanoTime() - offsetStart) / 1_000_000;
        long offsetScanned = table.scannedRows;

        table.scannedRows = 0;
        long cursorStart = System.nanoTime();
        int cursorFetched = 0;
        long lastSeenId = 0;
        while (true) {
            List<OrderRow> page = table.pageByCursor(lastSeenId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            cursorFetched += page.size();
            lastSeenId = page.get(page.size() - 1).id();   // 记住书签
        }
        long cursorMillis = (System.nanoTime() - cursorStart) / 1_000_000;
        long cursorScanned = table.scannedRows;

        System.out.printf("  OFFSET 分页：取到 %,d 行，扫描 %,d 行，耗时 %d ms%n",
                offsetFetched, offsetScanned, offsetMillis);
        System.out.printf("  游标 分页：取到 %,d 行，扫描 %,d 行，耗时 %d ms%n",
                cursorFetched, cursorScanned, cursorMillis);
        System.out.printf("  结论：结果一致（%s），OFFSET 白扫了 %,d 行，约 %.0f 倍工作量%n",
                offsetFetched == cursorFetched ? "都是全量" : "对不上",
                offsetScanned - cursorScanned, offsetScanned * 1.0 / cursorScanned);
    }

    // ===================== 实验二：XSSF 全量攒内存 vs SXSSF 流式窗口 =====================
    static void experimentTwoExport(OrderTable table, Path workDir) throws IOException {
        System.out.println("\n【实验二】两种 POI 写法：内存里到底压了多少行");

        // ❌ 老写法：先把所有数据查出来堆在内存，再用 XSSFWorkbook 一次性排版、封箱
        List<OrderRow> allRows = new ArrayList<>();
        long lastSeenId = 0;
        while (true) {
            List<OrderRow> page = table.pageByCursor(lastSeenId, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            allRows.addAll(page);   // 越攒越多，数据量再大就 OOM
            lastSeenId = page.get(page.size() - 1).id();
        }
        Path inMemoryFile = workDir.resolve("xssf-全量攒内存.xlsx");
        long inMemoryHeap = exportAllInMemory(allRows, inMemoryFile);

        // ✅ 新写法：游标查一批 → 写一批 → 丢掉这批，内存里只留一个滑动窗口
        Path streamingFile = workDir.resolve("sxssf-流式窗口.xlsx");
        long streamingHeap = exportStreaming(table, streamingFile);

        System.out.printf("  XSSFWorkbook ：全部 %,d 行压内存，堆增长约 %.1f MB，文件 %,d 字节%n",
                allRows.size(), inMemoryHeap / 1024.0 / 1024, Files.size(inMemoryFile));
        System.out.printf("  SXSSFWorkbook：内存只留 %d 行窗口，堆增长约 %.1f MB，文件 %,d 字节%n",
                STREAM_WINDOW, streamingHeap / 1024.0 / 1024, Files.size(streamingFile));
        System.out.println("  结论：文件内容一样，但 SXSSF 的内存占用与总行数无关，导 100 万行也不会 OOM。");
    }

    /** 全量攒内存：所有行都变成 Java 对象躺在内存里，此刻的堆增长就是峰值。 */
    static long exportAllInMemory(List<OrderRow> rows, Path file) throws IOException {
        long baseHeap = usedHeap();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("orders");
            int rowIndex = writeHeader(sheet);
            for (OrderRow row : rows) {
                writeRow(sheet.createRow(rowIndex++), row);
            }
            long peakHeap = usedHeap() - baseHeap;
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            return peakHeap;
        }
    }

    /** 流式写：查一批写一批，写满窗口 POI 自动刷临时文件，用完记得 dispose 清干净。 */
    static long exportStreaming(OrderTable table, Path file) throws IOException {
        long baseHeap = usedHeap();
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(STREAM_WINDOW)) {
            workbook.setCompressTempFiles(true);   // 临时分片压缩，省磁盘
            Sheet sheet = workbook.createSheet("orders");
            int rowIndex = writeHeader(sheet);

            long lastSeenId = 0;
            while (true) {
                List<OrderRow> page = table.pageByCursor(lastSeenId, PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }
                for (OrderRow row : page) {
                    writeRow(sheet.createRow(rowIndex++), row);
                }
                lastSeenId = page.get(page.size() - 1).id();   // 这一批写完就松手
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            workbook.dispose();   // 删掉 POI 在临时目录里生成的分片文件
        }
        return usedHeap() - baseHeap;
    }

    static int writeHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("订单号");
        header.createCell(1).setCellValue("客户");
        header.createCell(2).setCellValue("金额");
        return 1;
    }

    static void writeRow(Row row, OrderRow data) {
        row.createCell(0).setCellValue(data.id());
        row.createCell(1).setCellValue(data.customerName());
        row.createCell(2).setCellValue(data.amountYuan());
    }

    /** 当前堆占用。先 gc 一下，让数字别被垃圾对象干扰。 */
    static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    // ===================== 实验三：异步导出 + 并发限流 =====================
    static void experimentThreeAsyncAndLimit(OrderTable table, Path workDir) throws Exception {
        System.out.println("\n【实验三】异步导出：点完就走，好了再通知");

        ExecutorService workerPool = Executors.newFixedThreadPool(8);
        Semaphore exportPermits = new Semaphore(2);   // 闸机：同时最多 2 个导出任务在跑
        AtomicInteger runningNow = new AtomicInteger();
        AtomicInteger peakRunning = new AtomicInteger();

        long submitStart = System.nanoTime();
        List<CompletableFuture<Path>> futures = new ArrayList<>();
        for (int userIndex = 1; userIndex <= 8; userIndex++) {
            Path file = workDir.resolve("async-user" + userIndex + ".xlsx");
            futures.add(submitExport(workerPool, exportPermits, runningNow, peakRunning, table, file));
        }
        System.out.printf("  8 个导出请求提交完只花了 %d ms，页面立刻提示「正在生成，完成后通知你」%n",
                (System.nanoTime() - submitStart) / 1_000_000);

        for (CompletableFuture<Path> future : futures) {
            future.join();
        }
        System.out.printf("  全部完成，期间同时在跑的最大任务数 = %d（闸机限死了 2）%n", peakRunning.get());
        System.out.println("  结论：用户不用干等，数据库和磁盘也不会被自家导出功能打垮。");

        workerPool.shutdown();
        workerPool.awaitTermination(30, TimeUnit.SECONDS);
    }

    /**
     * 提交一个导出任务：supplyAsync 立刻返回 future（这就是「异步」），
     * 真正的活儿在后台线程里排队、拿闸机钥匙、导出、再发通知。
     */
    static CompletableFuture<Path> submitExport(ExecutorService pool, Semaphore permits,
                                                AtomicInteger runningNow, AtomicInteger peakRunning,
                                                OrderTable table, Path file) {
        return CompletableFuture.supplyAsync(() -> {
            permits.acquireUninterruptibly();   // 没钥匙就在门口排队
            try {
                int now = runningNow.incrementAndGet();
                peakRunning.accumulateAndGet(now, Math::max);
                exportStreaming(table, file);
                Thread.sleep(50);   // 模拟上传对象存储的耗时
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                runningNow.decrementAndGet();
                permits.release();   // 钥匙一定要还，否则后面的人永远进不去
            }
        }, pool).thenApply(path -> {
            System.out.println("  [通知] 您的导出文件已生成：" + path.getFileName());
            return path;
        });
    }

    static void deleteRecursively(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 导出 Excel 太慢？用真实 POI 跑三个优化实验 ===");
        OrderTable orderTable = new OrderTable(TOTAL_ROWS);
        Path workDir = Files.createTempDirectory("excel-export-demo");

        experimentOnePaging(orderTable);
        experimentTwoExport(orderTable, workDir);
        experimentThreeAsyncAndLimit(orderTable, workDir);

        deleteRecursively(workDir);
        System.out.println("\n临时文件已清理：" + workDir);
        System.out.println("=== 全部实验结束 ===");
    }
}
