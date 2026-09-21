// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 用一个"内存里的小数据库"来演示：一张单表数据量很大时，除了分库分表，
// 还能怎么优化。程序一共演示 3 招（都是原题里给出的思路）：
//
//   第 1 招：分页别用大 offset，改用"游标翻页"（where id > 上一页最后一条的 id）
//   第 2 招：大字段（比如一长段备注/个人简介）从主表里拆出去，单独放扩展表
//   第 3 招：冷热数据分离，老数据归档，主表只留最近的热点数据
//
// 运行后会把每一招的"前后对比数字"打印出来，让你直观看到差在哪。
// 代码里的 List<Order> 就相当于数据库里的一张表，Order 对象就是一行数据。
// ============================================================================

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Demo {

    /** 订单表的一行：id、金额、下单日期，外加一个"大字段"备注 */
    record Order(long id, double amount, LocalDate orderDate, String remark) {
    }

    /** 拆表后的"瘦身版订单行"：只保留日常查询要用的字段 */
    record OrderLite(long id, double amount) {
    }

    /** 拆表后的"备注扩展表行"：用 orderId 跟主表关联 */
    record OrderRemark(long orderId, String remark) {
    }

    /** 翻页结果：这一页的数据 + 为了拿到它一共扫了多少行 */
    record PageResult(List<Order> rows, long scannedRows) {
    }

    public static void main(String[] args) {
        System.out.println("========== 单表数据量大，不拆库拆表的优化演示 ==========");

        demoCursorPaging();      // 第 1 招：游标翻页
        demoSplitBigColumn();    // 第 2 招：大字段拆表
        demoHotColdSplit();      // 第 3 招：冷热数据分离
    }

    // ------------------------------------------------------------------------
    // 第 1 招：分页别用大 offset，改用游标翻页
    //
    // 生活比喻：一本书要找第 5000 页的内容。
    //   - offset 翻页 = 从第 1 页开始一页页数过去，数到第 5000 页才开始看；
    //   - 游标翻页 = 你手上夹了个书签（上一页最后一条的 id），直接翻到书签后面接着看。
    // 数过去的那些页就是"白扫的行"，数据越多越慢。
    // ------------------------------------------------------------------------
    private static void demoCursorPaging() {
        System.out.println("\n---------- 第 1 招：offset 翻页 vs 游标翻页 ----------");

        int totalRows = 200_000;      // 模拟表里有 20 万行
        int pageSize = 10;            // 每页 10 条
        int targetPage = 5000;        // 我们想看第 5000 页
        int offset = (targetPage - 1) * pageSize;   // 第 5000 页前面的行数 = 49990

        List<Order> orders = buildOrders(totalRows, 730);

        // 方式一：老写法 LIMIT offset, size
        PageResult offsetPage = pageByOffset(orders, offset, pageSize);

        // 方式二：游标翻页 WHERE id > 上一页最后一条的 id LIMIT size
        long lastIdOfPreviousPage = orders.get(offset - 1).id();   // 书签：上一页最后一条的 id
        PageResult cursorPage = pageByCursor(orders, lastIdOfPreviousPage, pageSize);

        System.out.println("表里一共 " + totalRows + " 行，想看第 " + targetPage + " 页（每页 " + pageSize + " 条）");
        System.out.println("LIMIT " + offset + ", " + pageSize + "  →  扫了 " + offsetPage.scannedRows() + " 行");
        System.out.println("WHERE id > " + lastIdOfPreviousPage + " LIMIT " + pageSize
                + "  →  扫了 " + cursorPage.scannedRows() + " 行");
        System.out.println("两页拿到的第一条数据是不是同一条？"
                + (offsetPage.rows().get(0).id() == cursorPage.rows().get(0).id() ? "是，结果一样" : "不是"));
        System.out.println("结论：结果一模一样，但游标翻页少扫了 "
                + (offsetPage.scannedRows() - cursorPage.scannedRows()) + " 行。");
    }

    /** 模拟数据库执行 LIMIT offset, size：老老实实从第一行数到 offset，前面这些行都白扫了 */
    private static PageResult pageByOffset(List<Order> orders, int offset, int size) {
        long scannedRows = 0;

        // 前面 offset 行读了但不要 —— 这就是"大 offset 慢"的根源
        for (int index = 0; index < offset && index < orders.size(); index++) {
            scannedRows++;
        }

        List<Order> page = new ArrayList<>();
        for (int index = offset; index < offset + size && index < orders.size(); index++) {
            page.add(orders.get(index));
            scannedRows++;
        }
        return new PageResult(page, scannedRows);
    }

    /** 模拟游标翻页：id 上有索引（相当于书的目录），能直接跳到起点，不用数前面那些行 */
    private static PageResult pageByCursor(List<Order> orders, long lastId, int size) {
        long scannedRows = 0;

        // 用二分查找模拟"顺着索引直接定位"：每次只比较 1 行，几十万行也就比十几次
        int low = 0;
        int high = orders.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            scannedRows++;   // 每次比较只看了 1 行
            if (orders.get(middle).id() <= lastId) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int startIndex = low;   // 第一条 id > lastId 的位置，就是这一页的起点

        List<Order> page = new ArrayList<>();
        for (int index = startIndex; index < startIndex + size && index < orders.size(); index++) {
            page.add(orders.get(index));
            scannedRows++;
        }
        return new PageResult(page, scannedRows);
    }

    // ------------------------------------------------------------------------
    // 第 2 招：大字段拆表
    //
    // 生活比喻：一行数据像"一个文件袋"。你在袋子里塞了一份很厚的合同（大字段），
    // 那么哪怕你只是想看一眼袋子上的标签（金额），也得先把整个袋子搬过来。
    // 把合同单独放一个柜子（扩展表），主表的袋子就轻了，日常查询快很多。
    // ------------------------------------------------------------------------
    private static void demoSplitBigColumn() {
        System.out.println("\n---------- 第 2 招：大字段拆表 ----------");

        int rowCount = 50_000;
        String longRemark = "客户备注：".repeat(10);   // 模拟一个 text 大字段（很长的备注）

        List<Order> fatTable = new ArrayList<>();        // 没拆表：主表里直接带大字段
        List<OrderLite> slimTable = new ArrayList<>();   // 拆表后：主表只留必要字段
        List<OrderRemark> remarkTable = new ArrayList<>();

        LocalDate today = LocalDate.of(2026, 9, 21);
        for (int i = 0; i < rowCount; i++) {
            long id = i + 1;
            double amount = 10 + (i % 90);
            fatTable.add(new Order(id, amount, today, longRemark));
            slimTable.add(new OrderLite(id, amount));
            remarkTable.add(new OrderRemark(id, longRemark));
        }

        // 场景：只统计"所有订单金额之和"，这个查询根本用不到备注
        long bytesOnFatTable = 0;
        double totalOnFatTable = 0;
        for (Order order : fatTable) {
            bytesOnFatTable += estimateBytes(order);   // 每一行都要把大字段一起搬过来
            totalOnFatTable += order.amount();
        }

        long bytesOnSlimTable = 0;
        double totalOnSlimTable = 0;
        for (OrderLite order : slimTable) {
            bytesOnSlimTable += estimateBytes(order);  // 只搬 id 和金额
            totalOnSlimTable += order.amount();
        }

        System.out.println("订单数：" + rowCount + "，每行备注长度：" + longRemark.length() + " 个字符");
        System.out.println("不拆表：扫主表搬运约 " + bytesOnFatTable + " 字节，合计金额 " + totalOnFatTable);
        System.out.println("拆表后：扫主表搬运约 " + bytesOnSlimTable + " 字节，合计金额 " + totalOnSlimTable);
        System.out.println("结论：金额算出来一样，但拆表后搬运的数据量只有原来的 "
                + (bytesOnSlimTable * 100 / bytesOnFatTable) + "%。");
        System.out.println("（要查备注时，再拿 orderId 去扩展表 remarkTable 里取，那里现在有 "
                + remarkTable.size() + " 行）");
    }

    /** 粗略估算一行数据"搬过来"要多少字节：id 和金额各算 8 字节，字符串按 2 字节/字符算 */
    private static long estimateBytes(Order order) {
        return 8 + 8 + (long) order.remark().length() * 2;
    }

    /** 同上，瘦身版订单行没有大字段 */
    private static long estimateBytes(OrderLite order) {
        return 8 + 8;
    }

    // ------------------------------------------------------------------------
    // 第 3 招：冷热数据分离
    //
    // 生活比喻：衣柜里当季的衣服挂在最顺手的位置，过季的打包塞到床底箱子。
    // 订单表也一样：最近 3 个月天天被查（热数据），一年前的基本没人看（冷数据），
    // 把老的挪到"归档表/大数据平台"，主表立刻瘦下来。
    // ------------------------------------------------------------------------
    private static void demoHotColdSplit() {
        System.out.println("\n---------- 第 3 招：冷热数据分离 ----------");

        LocalDate today = LocalDate.of(2026, 9, 21);
        int totalRows = 36_500;        // 约两年、每天 50 单
        int hotDays = 90;              // 最近 90 天算热点数据

        List<Order> allOrders = buildOrders(totalRows, 730);
        List<Order> hotTable = new ArrayList<>();
        List<Order> archiveTable = new ArrayList<>();

        LocalDate hotLine = today.minusDays(hotDays);   // 这条线之后的算"热"
        for (Order order : allOrders) {
            if (!order.orderDate().isBefore(hotLine)) {
                hotTable.add(order);
            } else {
                archiveTable.add(order);
            }
        }

        System.out.println("归档前：主表 " + allOrders.size() + " 行");
        System.out.println("归档后：主表 " + hotTable.size() + " 行（最近 " + hotDays
                + " 天），归档表 " + archiveTable.size() + " 行（" + hotDays + " 天以前）");
        System.out.println("结论：日常查询只在 " + hotTable.size() + " 行里找，扫描量降到原来的 "
                + (hotTable.size() * 100 / allOrders.size()) + "%。");
        System.out.println("冷数据不是删掉，需要查历史时再去归档表/大数据平台里捞。");
    }

    /**
     * 造一批订单数据（相当于往表里插数据）
     *
     * @param rowCount    总共造多少行
     * @param daysSpread  这些订单分散在最近多少天里
     */
    private static List<Order> buildOrders(int rowCount, int daysSpread) {
        List<Order> orders = new ArrayList<>(rowCount);
        LocalDate today = LocalDate.of(2026, 9, 21);

        for (int i = 0; i < rowCount; i++) {
            long id = i + 1;                                   // id 从 1 开始，自增整型
            double amount = 10 + (i % 90);                     // 金额随便造，10 ~ 99
            LocalDate orderDate = today.minusDays(i % daysSpread);  // 下单日期
            String remark = "备注" + id;                        // 普通小字段
            orders.add(new Order(id, amount, orderDate, remark));
        }
        return orders;
    }
}
