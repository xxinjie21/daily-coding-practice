// 编译运行：mvn -q compile exec:java
//
// 这个程序在干嘛？
//   演示「Java 写文件到磁盘」这趟旅程里，数据到底停在哪一层。
//   原题把过程拆成 5 关：①用户态缓冲 → ②内核页缓存(Page Cache) → ③脏页回写(writeback)
//   → ④块设备层/磁盘控制器 → ⑤真正落盘。
//   用四个小实验，把「数据停在草稿本 / 停在前台 / 拿到磁盘回执 / 原子替换」这几档保熟程度演示出来：
//     实验一：BufferedOutputStream 写了却不 flush，文件还是 0 字节（数据停在你草稿本）。
//     实验二：直接 FileOutputStream 写，OS 立刻看得到文件大小（到了前台抽屉 Page Cache）。
//     实验三：FileChannel.force(true)，等价 fsync，强制把数据刷进磁盘并拿回执。
//     实验四：写临时文件 → force 落盘 → rename 原子替换（配置/关键数据更新的标准姿势）。
//
// 前置条件：纯 JDK 文件 IO，不需要中间件。测试文件都建在系统临时目录的子目录，跑完自动删除，不污染仓库。
//
// 生活比喻：你（Java）要把纸条存进保险柜（磁盘）。草稿本 = 用户态缓冲，前台 = Page Cache，
//           管理员定时巡房 = 脏页回写，保险柜回执 = 磁盘控制器确认。force(true) 就是当场送进保险柜并拿回执。

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

public class Demo {

    // 所有测试文件都放系统临时目录的子目录，跑完整个删掉
    static final Path WORK_DIR = Path.of(System.getProperty("java.io.tmpdir"), "demo-file-write");
    static final Path TEMP_FILE = WORK_DIR.resolve("demo_write_path_test.txt");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(WORK_DIR);
        System.out.println("===== Java 写文件到磁盘：数据停在哪一层？=====");
        try {
            experimentUserBuffer();
            experimentUnbuffered();
            experimentForceToDisk();
            experimentAtomicReplace();
        } finally {
            deleteRecursively(WORK_DIR);
            System.out.println("\n[清理] 临时工作目录已删除：" + WORK_DIR);
        }

        System.out.println("\n===== 结论 =====");
        System.out.println("write() 不保证落盘；close()/flush() 只到 Page Cache；");
        System.out.println("关键数据要 channel.force(true)（=fsync）才断电不丢，配 rename 原子替换才不会被读到半截。");
    }

    // 实验一：BufferedOutputStream 的 8KB 草稿本
    // 生活比喻：你往草稿本写，没交给前台，前台抽屉（文件）自然是空的。
    static void experimentUserBuffer() throws IOException {
        System.out.println("\n--- 实验一：BufferedOutputStream 写了但不 flush ---");
        Files.deleteIfExists(TEMP_FILE);

        // BufferedOutputStream 默认带 8192 字节用户态缓冲区，相当于你的草稿本
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(TEMP_FILE.toFile()))) {
            out.write(new byte[200]); // 200 字节远小于 8KB，只躺在用户态缓冲里

            // 还没 flush，OS 根本不知道有这些数据 -> 文件大小还是 0
            System.out.println("write 200 字节后、未 flush：文件大小 = " + Files.size(TEMP_FILE)
                    + " 字节（数据停在你草稿本，前台看不见）");

            // flush() 相当于把草稿本交给前台 -> 触发 write() 系统调用，数据进 Page Cache
            out.flush();
            System.out.println("flush() 之后：            文件大小 = " + Files.size(TEMP_FILE)
                    + " 字节（到了内核页缓存，OS 已能看到，但还没真进磁盘）");
        }
    }

    // 实验二：直接 FileOutputStream，没有用户态缓冲这一层
    // 生活比喻：你跳过草稿本，直接把纸条塞给前台，前台抽屉立刻变厚。
    static void experimentUnbuffered() throws IOException {
        System.out.println("\n--- 实验二：直接 FileOutputStream 写（无用户态缓冲）---");
        Files.deleteIfExists(TEMP_FILE);

        try (FileOutputStream out = new FileOutputStream(TEMP_FILE.toFile())) {
            // FileOutputStream 每次 write 都直接走 write() 系统调用，没有草稿本这一层
            out.write(new byte[150]);
            System.out.println("write 150 字节后（未 close）：文件大小 = " + Files.size(TEMP_FILE)
                    + " 字节（OS 立刻看得到，停在 Page Cache）");
        }
    }

    // 实验三：FileChannel.force(true) —— 等价 fsync，强制把数据刷进磁盘并拿「回执」
    // 生活比喻：让前台亲自把纸条送进保险柜，并拿回「已收到」回执，断电也不丢。
    static void experimentForceToDisk() throws IOException {
        System.out.println("\n--- 实验三：FileChannel.force(true) 强制落盘（=fsync）---");
        Files.deleteIfExists(TEMP_FILE);

        try (FileOutputStream fos = new FileOutputStream(TEMP_FILE.toFile());
             FileChannel channel = fos.getChannel()) {
            channel.write(ByteBuffer.wrap(new byte[300]));

            // force(true) 的 true 表示连文件元数据(大小/修改时间)一起刷，对应底层 fsync()；
            // 这一步会阻塞，直到磁盘控制器确认落盘。
            channel.force(true);
            System.out.println("channel.force(true) 执行完毕：数据已强制刷到磁盘并拿到确认（断电也不丢）");
        }
    }

    // 实验四：写临时文件 -> force -> rename 原子替换
    // 生活比喻：新纸条先写好、当场送进保险柜，再把保险柜上的旧标签换成新的——
    //           别人来看，要么看到旧纸条，要么看到新纸条，绝不会看到写一半的。
    static void experimentAtomicReplace() throws IOException {
        System.out.println("\n--- 实验四：临时文件 + force + rename 原子替换 ---");
        Path target = WORK_DIR.resolve("config.txt");
        Path temp = WORK_DIR.resolve("config.txt.tmp");
        Files.writeString(target, "old-config\n"); // 先造一个旧配置

        // 新内容写进临时文件，force 确保数据真落盘，否则 rename 后可能出现「有名字没内容」的文件
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap("new-config\n".getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        // ATOMIC_MOVE：同一个文件系统内改名是原子操作，读者不会看到写一半的中间状态
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("rename 后 config.txt = " + Files.readString(target).trim()
                + "（读者要么看到旧的整份，要么看到新的整份）");
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
