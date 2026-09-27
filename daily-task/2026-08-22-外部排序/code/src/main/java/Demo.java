// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   演示「500G 数据只有 4G 内存怎么排序」——也就是外部排序（External Sort）。
//   真实场景里原始数据和中间结果都是磁盘文件，这里就用真实文件 IO 演示：
//     ① 分治：把装不进内存的大文件，按内存上限剁成能塞进内存的小块，每块读进内存排好序写回磁盘；
//     ② 多路归并：用「最小堆」从所有已排序小文件里一次一个挑出全局最小，拼成最终有序大文件；
//     ③ 校验：和 Arrays.sort 的标准答案对比，必须一模一样。
//
//   真实 500G 不可能在演示里造出来，这里用「总量小、内存上限也小」的等比例缩小来演示同样的算法。
//
// 前置条件：纯 JDK 文件 IO，不需要中间件。所有临时文件都建在系统临时目录，跑完自动删除，不污染仓库。
//
// 生活比喻：仓库装不下所有卡片，先把卡片分成能摊桌上的小堆、每堆排好，再像洗多副扑克牌一样并成一大叠。

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

public class Demo {

    static final int TOTAL = 5000;      // 原始大文件一共多少个数
    static final int MEM_LIMIT = 1000;  // 模拟内存上限：一次最多能装这么多
    static final int NO_VALUE = -1;     // 读文件到末尾的哨兵（数据都是非负数，不会冲突）
    static final int BUFFER = 1 << 16;  // 64KB 缓冲区，减少系统调用次数

    // 所有临时文件都放系统临时目录的子目录，跑完整个删掉
    static final Path WORK_DIR = Path.of(System.getProperty("java.io.tmpdir"), "demo-external-sort");

    /** 最小堆里的「比较单元」：记录值是多少、来自第几个小文件（取走后好去那队补下一个）。 */
    record Node(int value, int chunkIndex) {}

    public static void main(String[] args) throws IOException {
        Files.createDirectories(WORK_DIR);
        try {
            Path bigFile = WORK_DIR.resolve("big.txt");
            Path resultFile = WORK_DIR.resolve("sorted.txt");

            generateBigFile(bigFile);
            System.out.println("=== 外部排序演示（共 " + TOTAL + " 个数，内存上限 " + MEM_LIMIT + "）===");
            System.out.println("原始大文件：" + bigFile);

            List<Path> chunks = splitAndSort(bigFile);  // 第①步：分块 + 块内排序
            mergeChunks(chunks, resultFile);            // 第②步：多路归并
            verify(resultFile);                         // 第③步：校验结果
        } finally {
            deleteRecursively(WORK_DIR);
            System.out.println("\n[清理] 临时工作目录已删除：" + WORK_DIR);
        }
    }

    /** 造一个「装不进内存」的大文件：5000 个随机数，固定种子方便复现。 */
    static void generateBigFile(Path file) throws IOException {
        Random rnd = new Random(42);
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            for (int i = 0; i < TOTAL; i++) {
                out.write(Integer.toString(rnd.nextInt(1_000_000)));
                out.newLine();
            }
        }
    }

    /**
     * 第①步：分治。按内存上限把大文件切成若干小块，每块读进内存排序后写回磁盘。
     * 关键：块内必须先排好序，后面的多路归并才有意义。
     */
    static List<Path> splitAndSort(Path bigFile) throws IOException {
        List<Path> chunks = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(bigFile)) {
            while (true) {
                int[] buffer = new int[MEM_LIMIT];
                int count = 0;
                while (count < MEM_LIMIT) {
                    int value = readInt(in);
                    if (value == NO_VALUE) {
                        break;
                    }
                    buffer[count++] = value;
                }
                if (count == 0) {
                    break;
                }
                Arrays.sort(buffer, 0, count); // 内存里装得下，直接快排
                Path chunk = WORK_DIR.resolve("chunk-" + chunks.size() + ".txt");
                writeChunk(chunk, buffer, count);
                chunks.add(chunk);
            }
        }
        System.out.println("第①步：切成 " + chunks.size() + " 个已排序小块（每块最多 " + MEM_LIMIT + " 个）");
        return chunks;
    }

    static void writeChunk(Path chunk, int[] values, int count) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(chunk)) {
            for (int i = 0; i < count; i++) {
                out.write(Integer.toString(values[i]));
                out.newLine();
            }
        }
    }

    /**
     * 第②步：多路归并。最小堆里始终每个小文件放一个「当前最小的数」，
     * 堆顶就是全局最小；取走后，从它所属文件补下一个数进堆。
     */
    static void mergeChunks(List<Path> chunks, Path resultFile) throws IOException {
        List<BufferedReader> readers = new ArrayList<>();
        try {
            PriorityQueue<Node> heap = new PriorityQueue<>(Comparator.comparingInt(Node::value));
            for (int i = 0; i < chunks.size(); i++) {
                readers.add(Files.newBufferedReader(chunks.get(i)));
                int first = readInt(readers.get(i)); // 每队先举一张号码牌
                if (first != NO_VALUE) {
                    heap.offer(new Node(first, i));
                }
            }

            long written = 0;
            try (BufferedWriter out = new BufferedWriter(new FileWriter(resultFile.toFile()), BUFFER)) {
                while (!heap.isEmpty()) {
                    Node top = heap.poll();
                    out.write(Integer.toString(top.value()));
                    out.write('\n');
                    written++;
                    int next = readInt(readers.get(top.chunkIndex())); // 那队补下一个
                    if (next != NO_VALUE) {
                        heap.offer(new Node(next, top.chunkIndex()));
                    }
                }
            }
            System.out.println("第②步：多路归并完成，共写出 " + written + " 个数 -> " + resultFile);
        } finally {
            for (BufferedReader reader : readers) {
                reader.close();
            }
        }
    }

    /** 第③步：把结果文件和「直接 Arrays.sort 整个数组」的标准答案逐个数对比。 */
    static void verify(Path resultFile) throws IOException {
        int[] expected = new int[TOTAL];
        Random rnd = new Random(42); // 同一个种子，重造一份原始数据
        for (int i = 0; i < TOTAL; i++) {
            expected[i] = rnd.nextInt(1_000_000);
        }
        Arrays.sort(expected);

        int index = 0;
        boolean ok = true;
        try (BufferedReader in = Files.newBufferedReader(resultFile)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (index >= expected.length || Integer.parseInt(line) != expected[index]) {
                    ok = false;
                    break;
                }
                index++;
            }
        }
        ok = ok && index == expected.length;
        System.out.println("第③步：校验 -> " + (ok ? "结果与 Arrays.sort 标准答案完全一致，外部排序正确" : "结果不一致，有 bug！"));
    }

    /** 读一行当一个数；读到文件末尾返回 NO_VALUE。 */
    static int readInt(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        return line == null ? NO_VALUE : Integer.parseInt(line);
    }

    /** 递归删除临时目录（子文件先删，目录后删）。 */
    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
