// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
// -----------------------------------------------------------------------------
// 模拟"把 A 库的数据同步到 B 库，搬到一半程序崩了"这个场景，
// 并按原题给出的思路，演示 4 个保证数据一致性的手段：
//   ① 状态追踪：把"搬到哪儿了"写进 checkpoint 进度表（这里用一个 Map 模拟 MySQL 的进度表）
//   ② 分批提交：每 1000 条一批、每批一个独立事务，失败最多丢一批
//   ③ 幂等写入：目标库用 upsert（有则更新、无则插入），同一条数据重复写也不会多一行
//   ④ 校验对账：同步完算两边的 checksum（数据指纹），对得上才算真的一致
//
// 演示流程：
//   第 1 轮：同步跑到第 3 批时"程序崩溃"（模拟断电），此时目标库只有一部分数据
//   第 2 轮：重启，读进度表断点续传，把剩下的搬完
//   第 3 轮：故意再跑一遍已经搬过的数据，验证 upsert 的幂等性（总行数不会变多）
//   最后：checksum 对账，确认源库和目标库完全一致
// -----------------------------------------------------------------------------

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Demo {

    /** 一行订单数据。record 是 Java 16+ 的"只读小数据类"，省去一堆 getter。 */
    record OrderRow(long orderNo, String status, int amount) {
    }

    /** 自定义异常：用来模拟"同步任务跑到一半崩了" */
    static class SyncCrashException extends RuntimeException {
        SyncCrashException(String message) {
            super(message);
        }
    }

    // =========================================================================
    // 源库：数据的老家，只读不写
    // =========================================================================
    static class SourceDatabase {
        private final List<OrderRow> allRows = new ArrayList<>();

        SourceDatabase(int totalRowCount) {
            for (long orderNo = 1; orderNo <= totalRowCount; orderNo++) {
                allRows.add(new OrderRow(orderNo, "PAID", (int) (orderNo * 3 % 500)));
            }
        }

        /**
         * 按主键往后读一批。这就是真实同步任务里的
         * "SELECT * FROM orders WHERE order_no > ? ORDER BY order_no LIMIT ?"
         */
        List<OrderRow> readBatchAfter(long lastSyncedOrderNo, int batchSize) {
            List<OrderRow> batch = new ArrayList<>();
            for (OrderRow row : allRows) {
                if (row.orderNo() > lastSyncedOrderNo) {
                    batch.add(row);
                    if (batch.size() == batchSize) {
                        break;
                    }
                }
            }
            return batch;
        }

        int rowCount() {
            return allRows.size();
        }

        long checksum() {
            return computeChecksum(allRows);
        }
    }

    // =========================================================================
    // 目标库：新家。写入走 upsert，天生防重复
    // =========================================================================
    static class TargetDatabase {
        // key = 业务唯一键 order_no，value = 整行数据。
        // 用 Map 就天然表达了"同一个唯一键只会有一行"，这正是 upsert 的效果。
        private final Map<Long, OrderRow> rowsByOrderNo = new LinkedHashMap<>();

        // 统计用：这次一共执行了多少次写入动作（含重复写的）
        private int writeAttemptCount = 0;

        /**
         * 幂等写入 = MySQL 的 INSERT ... ON DUPLICATE KEY UPDATE。
         * 大白话：这个订单号已经在了就把内容覆盖一遍，不在才新增一行。
         * 所以同一批数据重放 10 次，表里还是那么多行。
         */
        void upsert(OrderRow row) {
            writeAttemptCount++;
            rowsByOrderNo.put(row.orderNo(), row);
        }

        int rowCount() {
            return rowsByOrderNo.size();
        }

        int writeAttemptCount() {
            return writeAttemptCount;
        }

        long checksum() {
            return computeChecksum(new ArrayList<>(rowsByOrderNo.values()));
        }
    }

    // =========================================================================
    // 进度表（checkpoint）：贴在门上的便利贴，记录"搬到第几本书了"
    // 真实项目里就是 MySQL 的一张 sync_checkpoint 表或者 Redis 的一个 key。
    // 关键点：它存在于任务进程之外，所以程序崩了它还在。
    // =========================================================================
    static class CheckpointStore {
        private final Map<String, Long> sourcePositionByTask = new LinkedHashMap<>();
        private final Map<String, String> statusByTask = new LinkedHashMap<>();

        long loadSourcePosition(String taskName) {
            return sourcePositionByTask.getOrDefault(taskName, 0L);
        }

        String loadStatus(String taskName) {
            return statusByTask.getOrDefault(taskName, "NEVER_RUN");
        }

        void save(String taskName, long sourcePosition, String status) {
            sourcePositionByTask.put(taskName, sourcePosition);
            statusByTask.put(taskName, status);
        }
    }

    // =========================================================================
    // 同步任务本体
    // =========================================================================
    static class SyncTask {
        private static final String TASK_NAME = "order_sync";
        private static final int BATCH_SIZE = 1000;

        private final SourceDatabase source;
        private final TargetDatabase target;
        private final CheckpointStore checkpointStore;

        SyncTask(SourceDatabase source, TargetDatabase target, CheckpointStore checkpointStore) {
            this.source = source;
            this.target = target;
            this.checkpointStore = checkpointStore;
        }

        /**
         * 跑一次同步。
         *
         * @param crashAtBatchNumber 跑到第几批时"人为崩溃"，传 0 表示不崩、一路跑完
         * @param forceRestartFromZero true = 无视进度从头再跑一遍（用来验证幂等）
         */
        void run(int crashAtBatchNumber, boolean forceRestartFromZero) {
            // —— 启动前先看便利贴：上次跑到哪了 ——
            long lastSyncedOrderNo = forceRestartFromZero ? 0L : checkpointStore.loadSourcePosition(TASK_NAME);
            String lastStatus = checkpointStore.loadStatus(TASK_NAME);
            System.out.println("  启动前读进度表：上次状态=" + lastStatus + "，已同步到 order_no=" + lastSyncedOrderNo);

            if (!forceRestartFromZero && "SUCCESS".equals(lastStatus)) {
                System.out.println("  上次已经跑成功了，本次无需重跑。");
                return;
            }
            checkpointStore.save(TASK_NAME, lastSyncedOrderNo, "RUNNING");

            int batchNumber = 0;
            while (true) {
                // 1) 从断点位置往后读一批（每批 1000 条，不一次性梭哈 10 万条）
                List<OrderRow> batch = source.readBatchAfter(lastSyncedOrderNo, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break; // 没有更多数据，搬完了
                }
                batchNumber++;

                // 2) 模拟"这一批写到一半程序崩了"
                //    注意：崩的时候这一批还没提交、进度也没更新，所以整批都算没搬过。
                if (crashAtBatchNumber > 0 && batchNumber == crashAtBatchNumber) {
                    checkpointStore.save(TASK_NAME, lastSyncedOrderNo, "FAILED");
                    throw new SyncCrashException("第 " + batchNumber + " 批写入时程序崩溃（模拟断电）");
                }

                // 3) 本批独立事务写入目标库，全部走 upsert（幂等）
                for (OrderRow row : batch) {
                    target.upsert(row);
                }
                long batchLastOrderNo = batch.get(batch.size() - 1).orderNo();

                // 4) 关键顺序：**先写完目标库，再更新进度表**。
                //    反过来的话，进度更新完就崩，这批数据会被静悄悄跳过 → 永久丢失。
                lastSyncedOrderNo = batchLastOrderNo;
                checkpointStore.save(TASK_NAME, lastSyncedOrderNo, "RUNNING");
                System.out.println("  第 " + batchNumber + " 批提交成功（" + batch.size()
                        + " 条），进度更新到 order_no=" + lastSyncedOrderNo);
            }

            checkpointStore.save(TASK_NAME, lastSyncedOrderNo, "SUCCESS");
            System.out.println("  本轮同步完成，状态置为 SUCCESS。");
        }
    }

    // =========================================================================
    // 对账用的 checksum：把一堆数据揉成一个"指纹"数字。
    // 两边指纹一样，基本就能认定内容一致（真实项目常用 pt-table-checksum 等工具）。
    // =========================================================================
    static long computeChecksum(List<OrderRow> rows) {
        long checksum = 0L;
        for (OrderRow row : rows) {
            // 简单地把每行内容折算成数字累加，顺序无关，够演示用
            checksum += row.orderNo() * 31L + row.status().hashCode() + row.amount();
        }
        return checksum;
    }

    public static void main(String[] args) {
        final int totalRowCount = 5000; // 源库一共 5000 条待同步数据

        SourceDatabase source = new SourceDatabase(totalRowCount);
        TargetDatabase target = new TargetDatabase();
        CheckpointStore checkpointStore = new CheckpointStore();
        SyncTask syncTask = new SyncTask(source, target, checkpointStore);

        System.out.println("源库共有 " + source.rowCount() + " 条数据，每批 1000 条同步。");
        System.out.println();

        // ---------------- 第 1 轮：跑到第 3 批时崩溃 ----------------
        System.out.println("【第 1 轮】正常启动，但会在第 3 批崩溃");
        try {
            syncTask.run(3, false);
        } catch (SyncCrashException crash) {
            System.out.println("  ✗ 崩了：" + crash.getMessage());
        }
        System.out.println("  崩溃后目标库行数 = " + target.rowCount() + "（预期 2000，即前两批）");
        System.out.println();

        // ---------------- 第 2 轮：重启，断点续传 ----------------
        System.out.println("【第 2 轮】重启任务，读进度表断点续传");
        syncTask.run(0, false);
        System.out.println("  目标库行数 = " + target.rowCount() + "（预期 " + totalRowCount + "）");
        System.out.println();

        // ---------------- 第 3 轮：故意整个重放，验证幂等 ----------------
        System.out.println("【第 3 轮】故意无视进度、把 5000 条全量重放一遍，验证 upsert 幂等");
        int rowCountBeforeReplay = target.rowCount();
        syncTask.run(0, true);
        int rowCountAfterReplay = target.rowCount();
        System.out.println("  重放前行数 = " + rowCountBeforeReplay + "，重放后行数 = " + rowCountAfterReplay);
        System.out.println("  累计写入动作次数 = " + target.writeAttemptCount()
                + "（写了很多次，但行数没变多，这就是幂等）");
        System.out.println();

        // ---------------- 最后：checksum 对账 ----------------
        System.out.println("【对账】比对源库与目标库的 checksum");
        long sourceChecksum = source.checksum();
        long targetChecksum = target.checksum();
        System.out.println("  源库   checksum = " + sourceChecksum);
        System.out.println("  目标库 checksum = " + targetChecksum);
        System.out.println();

        // ---------------- 自动校验，全部通过才算演示成功 ----------------
        boolean rowCountMatched = target.rowCount() == source.rowCount();
        boolean idempotentKept = rowCountBeforeReplay == rowCountAfterReplay;
        boolean checksumMatched = sourceChecksum == targetChecksum;

        System.out.println("校验结果：");
        System.out.println("  行数一致（没漏数据）      : " + (rowCountMatched ? "通过" : "失败"));
        System.out.println("  重放后行数不变（幂等）    : " + (idempotentKept ? "通过" : "失败"));
        System.out.println("  checksum 一致（内容相同） : " + (checksumMatched ? "通过" : "失败"));

        if (rowCountMatched && idempotentKept && checksumMatched) {
            System.out.println();
            System.out.println("全部通过：任务中途崩溃 + 重复重放，最终两边数据依然完全一致。");
        } else {
            throw new IllegalStateException("演示校验未通过，逻辑有问题");
        }
    }
}
