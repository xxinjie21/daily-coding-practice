// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 这个程序在干嘛？
 * 模拟「给一亿条数据的大表在线加索引」——重点是：加索引的过程中，业务不能停。
 *
 * 对应原题给出的两套思路：
 *   1) 直接 ALTER TABLE：会长时间锁表，业务瘫痪（反面教材，演示它为什么不行）；
 *   2) 影子表方案：这是 gh-ost / pt-online-schema-change 工具的底层原理——
 *      建一张带索引的新表（影子表） -> 后台搬历史数据 -> 搬运期间原表照常读写、变更记到 binlog
 *      -> 把增量重放到影子表追平 -> 最后做个毫秒级「改名」切换。业务全程不中断。
 *
 * 为了能在本机几秒跑完，这里只用 2000 行数据演示（真实是一亿行，流程一模一样，只是扫描更久）。
 */
public class Demo {

    // 一行订单（示意：真实表字段很多，这里只留演示要用的）
    // record 是 Java 16+ 的简洁写法，等于一个只读的小数据盒子，不用写 getter
    record Order(long id, long userId) {}

    /**
     * 「带索引的大表」：数据和二级索引分开存，
     * 模拟 MySQL 的「聚簇索引（按主键存整行）」+「二级索引（按别的列快速找）」。
     */
    static final class Table {
        // 聚簇索引：id -> 整行（用 LinkedHashMap 保持插入顺序，方便我们遍历搬数据）
        final Map<Long, Order> rows = new LinkedHashMap<>();
        // 二级索引：userId -> 这些 userId 对应的行 id 列表
        // 用 TreeMap 让查找有序，本质就是在模拟一棵 B+ 树索引
        final TreeMap<Long, List<Long>> userIdIndex = new TreeMap<>();

        // 往表里加一行，同时维护索引（这一步就相当于「建/更新索引」）
        void add(Order o) {
            rows.put(o.id, o);
            userIdIndex.computeIfAbsent(o.userId, k -> new ArrayList<>()).add(o.id);
        }

        // 按 userId 查（走索引）：直接去 userIdIndex 里拿，不用全表扫描
        List<Long> findByUserId(long userId) {
            return userIdIndex.getOrDefault(userId, Collections.emptyList());
        }
    }

    public static void main(String[] args) {
        // ---- 准备：一张「一亿行」的大表（这里用 2000 行代表）----
        int total = 2000;
        Table oldTable = new Table();
        for (long i = 1; i <= total; i++) {
            oldTable.add(new Order(i, i % 500)); // 每 500 个人循环，userId 分布在 0~499
        }
        System.out.println("原表已有 " + total + " 行（真实场景约 1 亿行），此时 userId 上【没有索引】。");

        // ===== 思路 1：直接 ALTER TABLE（反面教材）=====
        // 直接加索引 = 扫描整张表重建二级索引，期间表被锁，读写全卡住。
        // 这里用「行数 / 每秒能扫的行数」粗估锁表时长。
        long blockSeconds = estimateBlockSeconds(100_000_000); // 按真实一亿行估算
        System.out.println("\n[反面] 直接 ALTER TABLE ADD INDEX：");
        System.out.println("        要全表扫描一遍来建索引，真实一亿行约锁表 " + blockSeconds + " 秒（约"
                + (blockSeconds / 60) + " 分钟），这段时间业务全瘫痪。");

        // ===== 思路 2：影子表方案（gh-ost / pt-osc 的底层原理）=====
        System.out.println("\n[正解] 影子表方案：业务全程不中断，只在最后做一次毫秒级切换。");

        // ① 建一张结构相同、但【已经带好索引】的影子表
        System.out.println("  ① 建影子表（结构一样，userId 上已建好索引）。");
        Table shadow = new Table();

        // ② 把原表历史数据搬进影子表，顺手把索引建好（后台慢慢扫，不锁原表）
        System.out.println("  ② 后台把原表历史数据搬进影子表，并建好索引（不阻塞原表读写）。");
        for (Order o : oldTable.rows.values()) {
            shadow.add(o); // 这一步对应 gh-ost 的 copy 阶段
        }

        // ③ 搬运期间业务照常读写，这些「新变更」先记到「变更日志(binlog)」里
        //    binlog 就是数据库自己的流水账本：谁改了什么，全都记下来，保证一条不丢。
        System.out.println("  ③ 搬运期间业务照常读写，新变更先记到「变更日志(binlog)」里。");
        List<Order> binlog = new ArrayList<>();
        // 模拟搬运期间来了 3 笔新订单（其中 2 笔属于 userId=17，1 笔属于 233）
        binlog.add(new Order(total + 1, 17));
        binlog.add(new Order(total + 2, 17));
        binlog.add(new Order(total + 3, 233));
        // 说明：真实的更新/删除也是同样「记到 binlog、再重放」来处理，这里用插入演示最直观。

        // ④ 追平：把 binlog 里的增量重放到影子表，让两边完全一致
        System.out.println("  ④ 搬运完，把「变更日志」里的增量重放到影子表，追平差距。");
        for (Order o : binlog) {
            shadow.add(o); // 影子表补上搬运期间产生的新数据
        }

        // ⑤ 切流：最后只做一次很快的「改名」操作完成切换（毫秒级，不锁业务）
        //    在 MySQL 里就是 RENAME TABLE，原子操作；这里用引用切换表示「业务从此读新表」。
        System.out.println("  ⑤ 最后只做一个很快的「表改名」切换（毫秒级），业务几乎无感。");
        Table main = shadow; // 切换后：业务读 main，也就是那张带索引的影子表

        // ⑥ 校验：加完索引后，按 userId 查应该又快又准
        List<Long> r1 = main.findByUserId(17);  // 原有 4 行(17,517,1017,1517) + 新增 2 行
        List<Long> r2 = main.findByUserId(233); // 原有 4 行 + 新增 1 行
        System.out.println("  ⑥ 校验：userId=17 命中 " + r1.size() + " 行（含搬运期间新增的 2 笔）；");
        System.out.println("        userId=233 命中 " + r2.size() + " 行（含搬运期间新增的 1 笔）。");
        System.out.println("        => 索引生效，且搬运期间的新数据一条没丢，业务全程没停。");

        // 收尾提醒
        System.out.println("\n提醒：真实生产请用 gh-ost / pt-osc 这类成熟工具，上面的「建表/导数据/重放/切流」它都帮你做好了，");
        System.out.println("      还能看进度、限速、随时暂停；上线前务必先在备库演练一遍。");
    }

    // 粗估「直接 ALTER」锁表时长：行数 / 每秒能扫的行数（保守按 5 万行/秒算）
    static long estimateBlockSeconds(long rows) {
        long perSecond = 50_000;
        return Math.max(1, rows / perSecond);
    }
}
