// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   用一个小例子，演示「高可用数据同步系统」的 5 个核心容错机制，
//   全部严格对应原题给出的 5 条思路：
//   1) 断点续传：读取位点（checkpoint）和数据一起持久化，崩了从最后确认的位点恢复
//   2) 缓冲层：下游挂了，变更先进消息队列，恢复后再慢慢重放
//   3) 重试 + 指数退避 + 死信队列：下游偶尔抖就温和重试，彻底坏就进 DLQ 人工处理
//   4) 循环复制防护：消息带 sync-id 标记，自己发过的自己丢弃，避免 A->B->A 绕圈
//   5) 对账补数：定期比对源与目标，缺的补上
//   每个机制都跑一遍并打印结果，顺着注释就能看懂。

import java.util.*;

public class Demo {

    // ============ 机制1：位点持久化（断点续传的「小本子」） ============
    // CheckpointStore 好比搬运工随身带的小本子，记录「已经搬到第几车」。
    // 这里用 Map 模拟外部存储（真实场景是 MySQL / ZooKeeper / Kafka 的 offset）。
    static class CheckpointStore {
        // key=任务名，value=已经确认同步到的位置（第几条变更）
        private final Map<String, Long> positions = new HashMap<>();

        // 把位点写进「小本子」。真实场景里这一步要和"这批数据"一起原子提交，
        // 保证要么都成功、要么都不成功，绝不会只记了位点没写数据。
        void save(String job, long offset) {
            positions.put(job, offset);
        }

        // 重启后从「小本子」读出上次停在哪
        long resume(String job) {
            return positions.getOrDefault(job, 0L);
        }
    }

    // 模拟一次「同步任务」：从断点继续读源端变更，同步到目标，并随时记录位点。
    // 参数 target 代表目标库（上海仓），cp 是位点小本子。
    static void syncOnce(CheckpointStore cp, List<String> target) {
        final int TOTAL = 10; // 源端总共 10 条变更（好比 binlog 里的 10 条改动）
        long start = cp.resume("syncJob"); // 从断点继续，不重搬
        System.out.println("   从第 " + start + " 条继续读（断点续传）");
        for (long i = start; i < TOTAL; i++) {
            target.add("change#" + (i + 1));   // 把这条变更同步到目标库
            cp.save("syncJob", i + 1);          // 同步成功，立刻记位点（数据+位点原子提交）
            System.out.println("   同步第 " + (i + 1) + " 条 -> 目标库");
            if ((i + 1) == 5) {                 // 模拟搬到第 5 条时服务突然崩了
                System.out.println("   [崩在第 5 条后，但位点=5 已记下]");
                return;
            }
        }
    }

    static void scenario1() {
        CheckpointStore cp = new CheckpointStore();
        List<String> target = new ArrayList<>();
        System.out.println("   第1次运行（跑到第5条崩了）：");
        syncOnce(cp, target);
        System.out.println("   --- 服务重启，继续跑 ---");
        syncOnce(cp, target); // 从位点 5 恢复，只搬 6~10，不重不漏
        System.out.println("   结果：目标库共 " + target.size() + " 条，与源端 10 条一致："
                + (target.size() == 10));
    }

    // ============ 机制2：缓冲层（下游挂了，变更先进消息队列） ============
    static void scenario2() {
        Queue<String> buffer = new LinkedList<>(); // 中转仓库 = 消息队列（Kafka）
        List<String> target = new ArrayList<>();   // 上海仓（目标库）
        boolean targetUp = false;                  // 上海仓临时关门

        // 源端产生 6 条变更；上海仓关门时先堆在中转仓，货不丢
        for (int i = 1; i <= 6; i++) {
            String change = "change#" + i;
            if (!targetUp) {
                buffer.offer(change); // 关门 -> 堆中转仓
            } else {
                target.add(change);
            }
        }
        System.out.println("   上海仓关门期间，中转仓堆了 " + buffer.size() + " 条（货没丢）");

        // 上海仓恢复开门，慢慢把中转仓的货重放进去
        targetUp = true;
        while (!buffer.isEmpty()) {
            target.add(buffer.poll());
        }
        System.out.println("   上海仓开门后重放，目标库现有 " + target.size()
                + " 条，未丢失：" + (target.size() == 6));
    }

    // ============ 机制3：重试 + 指数退避 + 死信队列 ============
    // 模拟「往下游写一条」这个动作：可能成功，也可能失败。
    // permanentFail=true 表示下游彻底坏了；否则前 2 次抖一下、第 3 次成功。
    static boolean sendOne(boolean permanentFail, int[] jitterCounter) {
        if (permanentFail) {
            return false;
        }
        if (jitterCounter[0] < 2) { // 模拟网络抖动，前 2 次失败
            jitterCounter[0]++;
            return false;
        }
        return true;
    }

    // 真正执行「投递」：最多重试 5 次，每次失败后等的时间翻倍（1,2,4,8 秒…），
    // 全失败则返回 "DLQ" 表示进死信队列交人工处理。
    static String deliver(String change, boolean permanentFail, int[] jitter) {
        int attempt = 0;
        long backoff = 1; // 退避基数（秒）
        while (attempt < 5) {
            attempt++;
            if (sendOne(permanentFail, jitter)) {
                return "OK";
            }
            if (attempt < 5) {
                System.out.println("     第" + attempt + "次发送失败，退避 " + backoff
                        + "s 后重试（不猛拍门，避免压垮下游）");
                backoff *= 2; // 指数退避
            }
        }
        return "DLQ"; // 超过最大重试次数 -> 死信队列
    }

    static void scenario3() {
        List<String> dlq = new ArrayList<>(); // 死信队列（人工处理区）

        // 笔1：下游只是偶尔抖，退避重试后成功
        System.out.println("   笔1（下游偶尔抖动）：");
        String r1 = deliver("change#1", false, new int[]{0});
        System.out.println("   结果：" + (r1.equals("OK") ? "成功（退避重试生效）" : "进死信队列"));

        // 笔2：下游彻底坏，5 次都失败，进死信队列等人工
        System.out.println("   笔2（下游彻底坏）：");
        String r2 = deliver("change#2", true, new int[]{0});
        if (r2.equals("DLQ")) {
            dlq.add("change#2");
        }
        System.out.println("   结果：" + (dlq.isEmpty() ? "成功" : "进死信队列 DLQ：" + dlq + "（交人工处理）"));
    }

    // ============ 机制4：循环复制防护（sync-id 标记） ============
    static void scenario4() {
        String mySyncId = "A"; // 我是 A 仓
        // 收到的"线上消息"，每条带 syncId 标记（谁发出的）。
        // 注意里面混了 A 自己发出又被绕回来的货，必须丢弃，否则会 A->B->A 无限绕圈。
        String[] incoming = {"A:change1", "B:change2", "A:change3", "B:change4"};
        List<String> applied = new ArrayList<>();
        for (String msg : incoming) {
            String[] parts = msg.split(":", 2);
            String syncId = parts[0];
            String payload = parts[1];
            if (syncId.equals(mySyncId)) {
                System.out.println("   丢弃自己发过的 " + payload + "（防 A->B->A 绕圈）");
                continue;
            }
            applied.add(payload); // 别人的货才落库
        }
        System.out.println("   A 实际落库（不含自己回声）：" + applied);
    }

    // ============ 机制5：对账补数（最后一道防线） ============
    static void scenario5() {
        // 源端有 10 条
        Set<String> source = new TreeSet<>();
        for (int i = 1; i <= 10; i++) {
            source.add("row" + i);
        }
        // 目标端少了几条（模拟同步中途静默遗漏）
        Set<String> target = new TreeSet<>(source);
        target.remove("row7");
        target.remove("row9");
        System.out.println("   源端 " + source.size() + " 条，目标端 " + target.size() + " 条");

        // 对账：找出目标缺的
        List<String> missing = new ArrayList<>();
        for (String r : source) {
            if (!target.contains(r)) {
                missing.add(r);
            }
        }
        System.out.println("   对账发现缺：" + missing);
        target.addAll(missing); // 补数
        System.out.println("   补数后目标端 " + target.size() + " 条，与源端一致："
                + target.equals(source));
    }

    public static void main(String[] args) {
        System.out.println("==== 高可用数据同步系统 · 5 大容错机制演示 ====");

        System.out.println();
        System.out.println("【机制1】断点续传：崩了也能从最后确认的位点继续搬，不重不漏");
        scenario1();

        System.out.println();
        System.out.println("【机制2】缓冲层：下游挂了，变更先堆消息队列，恢复后重放");
        scenario2();

        System.out.println();
        System.out.println("【机制3】重试 + 指数退避 + 死信队列：抖一下就温和重试，彻底坏就进 DLQ");
        scenario3();

        System.out.println();
        System.out.println("【机制4】循环复制防护：带 sync-id 标记，自己发过的自己丢");
        scenario4();

        System.out.println();
        System.out.println("【机制5】对账补数：定期比对源与目标，缺的补上");
        scenario5();

        System.out.println();
        System.out.println("==== 5 大机制演示完毕，全部符合预期 ====");
    }
}
