// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
import java.util.*;

/**
 * 这个程序演示一个真实踩过的坑：
 *   用 LIMIT offset, size 一页页搬数据时，如果搬的过程中别人又插入了数据，
 *   会「丢数据」或「重复搬」。
 * 然后演示用「游标分页」(cursor-based pagination) 怎么稳稳地不重不漏。
 *
 * 三个实验：
 *   实验1：复现文档说的场景——搬第1页后，前面插入一条新数据，再搬第2页，offset 会丢+重复。
 *   实验2：游标分页中途正常追加(新数据在末尾)，每条只搬一次。
 *   实验3：游标分页即使前面插了乱序数据，也不会重复搬（数据不脏）。
 */
public class Demo {

    // 一行数据：id 是主键(唯一)，ts 是时间(可能重复)。排序靠 (ts, id) 组合保证唯一稳定。
    static final class Row {
        final long id;
        final long ts;
        final String data;
        Row(long id, long ts, String data) {
            this.id = id;
            this.ts = ts;
            this.data = data;
        }
        public String toString() {
            return "Row{id=" + id + ",ts=" + ts + "}";
        }
    }

    // 模拟一张按 (ts, id) 排序的表；ts 相同时用 id 区分，顺序唯一稳定。
    static final class FakeTable {
        final List<Row> rows = new ArrayList<>();
        void insert(Row r) {
            rows.add(r);
        }
        // 返回按 (ts, id) 升序排序后的快照（模拟 ORDER BY ts, id）
        List<Row> ordered() {
            List<Row> copy = new ArrayList<>(rows);
            copy.sort(Comparator.comparingLong((Row r) -> r.ts).thenComparingLong(r -> r.id));
            return copy;
        }
    }

    public static void main(String[] args) {
        experimentOffsetLosesData();   // 实验1：offset 分页在「前面插数据」时会丢 + 重复
        experimentCursorCorrect();     // 实验2：游标分页中途正常追加，一条不重不漏
        experimentCursorNoDuplicate(); // 实验3：游标分页遇到插队，也「不会重复搬」
    }

    // ========== 实验1：复现文档场景——搬第1页后，前面插入一条新数据，再搬第2页 ==========
    static void experimentOffsetLosesData() {
        System.out.println("========== 实验1：LIMIT offset 分页，中途在前面插入数据 ==========");
        FakeTable table = new FakeTable();
        // 先放 1000 条，id=1..1000，ts=id（按 ts 升序就是按 id 升序）
        for (long i = 1; i <= 1000; i++) {
            table.insert(new Row(i, i, "d" + i));
        }

        // 第1页：LIMIT 0, 500 —— 拿到 id 1..500（在「插数据之前」基于旧表执行）
        List<Row> page1 = offsetPage(table, 0, 500);

        // 搬完第1页，别人往【最前面】塞了一条新数据（ts=0 最小，排到第 0 位）
        table.insert(new Row(9999, 0, "d-new"));

        // 第2页：LIMIT 500, 500 —— 基于「插数据之后」的新表执行
        List<Row> page2 = offsetPage(table, 500, 500);

        // 合并两页，看看对不对
        List<Long> got = new ArrayList<>();
        for (Row r : page1) got.add(r.id);
        for (Row r : page2) got.add(r.id);

        System.out.println("第1页拿到 " + page1.size() + " 条，第2页拿到 " + page2.size()
                + " 条，合计 " + got.size() + " 条（应该是 1001）");
        System.out.println("去重后只有 " + countDistinct(got) + " 个不同 id => 有数据丢了/重复了");
        System.out.println("重复出现的 id：" + findDuplicates(got));
        System.out.println(">>> 结论：offset 分页在「前面插数据」时会 丢数据 + 重复搬，数据对不上！");
        System.out.println();
    }

    // 用 offset 取第 offset 行开始的 size 行（模拟 SELECT ... LIMIT offset, size）
    static List<Row> offsetPage(FakeTable t, int offset, int size) {
        List<Row> all = t.ordered();
        List<Row> page = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + size, all.size()); i++) {
            page.add(all.get(i));
        }
        return page;
    }

    // ========== 实验2：游标分页——中途在【末尾】正常追加数据，每条只搬一次 ==========
    static void experimentCursorCorrect() {
        System.out.println("========== 实验2：游标分页，中途正常追加(新数据在最后) ==========");
        FakeTable table = new FakeTable();
        for (long i = 1; i <= 1000; i++) {
            table.insert(new Row(i, i, "d" + i));
        }

        // 游标：记住「上一批最后一条的 (ts, id)」，初始为负无穷
        long lastTs = -1, lastId = -1;
        List<Long> got = new ArrayList<>();
        boolean appended = false;
        for (int batch = 1; batch <= 10; batch++) {
            // 游标取「(ts,id) 严格大于游标」的前 500 行
            List<Row> page = cursorPage(table, lastTs, lastId, 500);
            if (page.isEmpty()) break;
            for (Row r : page) got.add(r.id);

            // 搬完第1页后，模拟「新数据落到表末尾」(ts 更大，符合真实 newest 在末尾)
            if (!appended) {
                table.insert(new Row(1001, 1001, "d1001"));
                table.insert(new Row(1002, 1002, "d1002"));
                appended = true;
            }
            // 把本批最后一条当接力棒，传给下一页
            Row last = page.get(page.size() - 1);
            lastTs = last.ts;
            lastId = last.id;
        }

        System.out.println("游标分页共搬 " + got.size() + " 条，去重后 " + countDistinct(got)
                + " 条，重复 id：" + findDuplicates(got));
        System.out.println(">>> 结论：游标顺着 (ts,id) 接力，每批严格「比上一条更新」，新追加的也能捞到，不重不漏。");
        System.out.println();
    }

    // ========== 实验3：游标分页遇到【前面】插乱序数据，也「不会重复搬」 ==========
    static void experimentCursorNoDuplicate() {
        System.out.println("========== 实验3：游标分页，中途在前面插入乱序数据 ==========");
        FakeTable table = new FakeTable();
        for (long i = 1; i <= 1000; i++) {
            table.insert(new Row(i, i, "d" + i));
        }

        long lastTs = -1, lastId = -1;
        List<Long> got = new ArrayList<>();
        boolean inserted = false;
        for (int batch = 1; batch <= 10; batch++) {
            List<Row> page = cursorPage(table, lastTs, lastId, 500);
            if (page.isEmpty()) break;
            for (Row r : page) got.add(r.id);

            // 搬完第1页，别人往【最前面】塞了一条乱序数据（ts=0）
            if (!inserted) {
                table.insert(new Row(9999, 0, "d-new"));
                inserted = true;
            }
            Row last = page.get(page.size() - 1);
            lastTs = last.ts;
            lastId = last.id;
        }

        System.out.println("游标分页 + 前面插数据：拿到 " + got.size() + " 条，去重后 "
                + countDistinct(got) + " 条，重复 id：" + findDuplicates(got));
        System.out.println(">>> 结论：游标即使遇到插队，也『不会重复搬』；那条乱序插队的会被本批跳过，");
        System.out.println("    等下次全量/CDC 补，数据不脏。offset 在这种场景下会又丢又重，差距就在这。");
        System.out.println();
    }

    // 游标取「(ts, id) 严格大于 (lastTs, lastId)」的前 size 行，按 (ts, id) 升序
    // 等价于 SQL: WHERE (ts > lastTs) OR (ts = lastTs AND id > lastId) ORDER BY ts, id LIMIT size
    static List<Row> cursorPage(FakeTable t, long lastTs, long lastId, int size) {
        List<Row> all = t.ordered();
        List<Row> page = new ArrayList<>();
        for (Row r : all) {
            // 游标条件：比上一条「更靠后」
            boolean after = (r.ts > lastTs) || (r.ts == lastTs && r.id > lastId);
            if (after) {
                page.add(r);
                if (page.size() >= size) break;
            }
        }
        return page;
    }

    // 下面两个是小工具，用来检查「去重后有几个」「哪些重复了」
    static int countDistinct(List<Long> list) {
        return new HashSet<>(list).size();
    }

    static List<Long> findDuplicates(List<Long> list) {
        Set<Long> seen = new HashSet<>();
        List<Long> dup = new ArrayList<>();
        for (Long x : list) {
            if (!seen.add(x) && !dup.contains(x)) {
                dup.add(x);
            }
        }
        return dup;
    }
}
