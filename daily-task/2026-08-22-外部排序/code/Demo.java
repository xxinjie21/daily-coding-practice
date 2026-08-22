// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛：
//   用一个小例子演示「500G 数据只有 4G 内存怎么排序」——也就是外部排序（External Sort）。
// 核心就两板斧：
//   ① 分治：把装不进内存的大文件，剁成能塞进内存的小块，每块单独排好序（快排），当成「已排序小文件」；
//   ② 多路归并：把所有「内部已排好序」的小文件，用「最小堆」一次一个挑出全局最小，拼成最终有序大文件。
// 真实场景里小块是磁盘文件、读写靠缓冲区；这里用内存数组模拟「磁盘文件」，把算法本身讲清楚。

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

public class Demo {

    // 最小堆里的「比较单元」：记录这个值是多少、它来自第几个小文件（原题里就叫 Element）。
    // 就像 125 个队伍各举着一个当前最小号码牌，我们比较的是「号码」谁最小，
    // fileIndex 只是记住这个号码属于哪支队伍，取走后好去那队补下一个。
    record Element(int value, int fileIndex) {
    }

    public static void main(String[] args) {
        // —— 模拟原始大文件：一共 5000 个数，但内存一次只能装 1000 个 ——
        // 真实 500G 不可能在演示里真造出来，这里用「总量小、内存上限也小」的等比例缩小来演示同样的算法。
        final int TOTAL = 5000;
        final int MEM_LIMIT = 1000; // 模拟「4G 内存只能处理约 1000 个数的小块」
        int[] bigFile = new int[TOTAL];
        Random rnd = new Random(42); // 固定种子，结果可复现，方便对照
        for (int i = 0; i < TOTAL; i++) {
            bigFile[i] = rnd.nextInt(1_000_000);
        }

        System.out.println("=== 外部排序小例子（共 " + TOTAL + " 个数，内存上限 " + MEM_LIMIT + "）===");

        // —— 第①步：分治。把大文件按内存上限切成若干小块，每块读进内存排好序，当成「已排序小文件」——
        // 真实里是写回磁盘文件；这里用 List<Integer> 模拟一个「小文件」（块内已有序）。
        List<List<Integer>> sortedChunks = new ArrayList<>();
        for (int start = 0; start < TOTAL; start += MEM_LIMIT) {
            int end = Math.min(start + MEM_LIMIT, TOTAL);
            int[] chunk = Arrays.copyOfRange(bigFile, start, end);
            Arrays.sort(chunk); // 内存里能装下，直接快排（Java 对 int[] 用双轴快排）
            List<Integer> sortedChunk = new ArrayList<>();
            for (int v : chunk) {
                sortedChunk.add(v);
            }
            sortedChunks.add(sortedChunk);
        }
        System.out.println("第①步：切成 " + sortedChunks.size() + " 个已排序小块（每块最多 " + MEM_LIMIT + " 个）");

        // —— 第②步：多路归并。用一个「最小堆」同时从所有小块里挑最小的 ——
        // 每个小块维护一个「读到第几个」的指针（就像每个人手里翻到第几页）。
        int[] pointers = new int[sortedChunks.size()];
        // 最小堆：堆顶永远是「当前所有小块里最小的那个数」。
        PriorityQueue<Element> minHeap = new PriorityQueue<>(Comparator.comparingInt(Element::value));

        // 先把每个小块当前的「第一个数」放进堆里（每队先举一张号码牌）。
        for (int i = 0; i < sortedChunks.size(); i++) {
            if (!sortedChunks.get(i).isEmpty()) {
                minHeap.offer(new Element(sortedChunks.get(i).get(0), i));
                pointers[i] = 1; // 这个小块已经用掉第 0 个，指针指到下一个
            }
        }

        List<Integer> result = new ArrayList<>();
        while (!minHeap.isEmpty()) {
            Element top = minHeap.poll();     // 拿出当前全局最小
            result.add(top.value);            // 写进最终文件
            int fi = top.fileIndex();         // 它来自第 fi 个小块
            // 如果那个小块还有剩的，把它的下一个数补进堆（保持堆里始终每队一个代表）。
            if (pointers[fi] < sortedChunks.get(fi).size()) {
                minHeap.offer(new Element(sortedChunks.get(fi).get(pointers[fi]), fi));
                pointers[fi]++;
            }
        }
        System.out.println("第②步：多路归并完成，用最小堆一次一个挑出全局最小，共 " + result.size() + " 个数");

        // —— 校验：和直接用 Arrays.sort 排整个数组的标准结果对比，必须一模一样 ——
        int[] expected = bigFile.clone();
        Arrays.sort(expected);
        boolean ok = result.size() == expected.length;
        for (int i = 0; i < expected.length && ok; i++) {
            if (!result.get(i).equals(expected[i])) {
                ok = false;
            }
        }
        System.out.println("校验：" + (ok ? "结果一致，外部排序正确" : "结果不一致，有 bug！"));

        // —— 一句提醒 ——
        // 真实 500G 数据无法一次性读进 4G 内存，正是「分块排序 + 多路归并」存在的意义；
        // 最小堆把「每轮从所有小块头里找最小」从 O(块数) 降到 O(log 块数)，数据越大越关键。
        System.out.println("提醒：块内必须先排好序，多路归并才有意义；别试图一次性把 500G 读进内存。");
    }
}
