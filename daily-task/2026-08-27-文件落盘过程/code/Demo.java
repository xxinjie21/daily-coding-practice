// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// 这个程序在干嘛？
//   演示「Java 写文件到磁盘」这趟旅程里，数据到底停在哪一层。
//   原题把过程拆成 5 关：①用户态缓冲 → ②内核页缓存(Page Cache) →
//   ③脏页回写(writeback) → ④块设备层/磁盘控制器 → ⑤真正落盘。
//   我们用三个小实验，把「数据停在草稿本 / 停在前台 / 拿到磁盘回执」这三档
//   保熟程度，用肉眼可见的文件大小变化演示出来：
//     实验一：BufferedOutputStream 写了却不 flush，文件还是 0 字节（停在你草稿本）。
//     实验二：直接 FileOutputStream 写，OS 立刻看得到文件大小（到了前台抽屉）。
//     实验三：FileChannel.force(true)，等价于 fsync，强制把数据刷进保险柜并拿回执。
//
// 全程在系统临时目录建一个测试文件，跑完自动删除，不污染仓库。

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public class Demo {

    // 测试用的临时文件路径（放在系统临时目录，跑完删掉）
    private static final Path TEMP_FILE = Path.of(System.getProperty("java.io.tmpdir"), "demo_write_path_test.txt");

    public static void main(String[] args) throws IOException {
        System.out.println("===== Java 写文件到磁盘：数据停在哪一层？=====");

        try {
            experiment1_userBuffer();   // 用户态缓冲：写了不 flush，文件还是空的
            experiment2_unbuffered();    // 无缓冲直写：OS 立刻看得到
            experiment3_forceToDisk();   // force(true)：强制落盘 + 拿回执
        } finally {
            // 无论如何都清理临时文件，保持环境干净
            Files.deleteIfExists(TEMP_FILE);
            System.out.println("\n[清理] 临时测试文件已删除：" + TEMP_FILE);
        }

        System.out.println("\n===== 结论 =====");
        System.out.println("write() 不保证落盘；close()/flush() 只到 Page Cache；");
        System.out.println("关键数据要 channel.force(true)（=fsync）才断电不丢，但别滥用。");
    }

    // 实验一：BufferedOutputStream 的 8KB 草稿本
    // 生活比喻：你往草稿本写，没交给前台，前台抽屉（文件）自然是空的。
    private static void experiment1_userBuffer() throws IOException {
        System.out.println("\n--- 实验一：BufferedOutputStream 写了但不 flush ---");
        // 先确保是干净文件
        Files.deleteIfExists(TEMP_FILE);

        // BufferedOutputStream 默认带一个 8192 字节(8KB)的用户态缓冲区，相当于你的草稿本
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(TEMP_FILE.toFile()))) {
            // 写 200 字节，远小于 8KB 缓冲区，数据只躺在「用户态缓冲」里
            out.write(new byte[200]);

            // 关键观察：还没 flush，OS 根本不知道有这些数据 → 文件大小还是 0
            long beforeFlush = Files.size(TEMP_FILE);
            System.out.println("write 200 字节后、未 flush：文件大小 = " + beforeFlush + " 字节（数据停在你草稿本，前台看不见）");

            // flush() 相当于把草稿本交给前台 → 触发 write() 系统调用，数据进 Page Cache
            out.flush();
            long afterFlush = Files.size(TEMP_FILE);
            System.out.println("flush() 之后：            文件大小 = " + afterFlush + " 字节（到了内核页缓存，OS 已能看到，但还没真进磁盘）");
        }
    }

    // 实验二：直接 FileOutputStream，没有用户态缓冲这一层
    // 生活比喻：你跳过草稿本，直接把纸条塞给前台，前台抽屉立刻变厚。
    private static void experiment2_unbuffered() throws IOException {
        System.out.println("\n--- 实验二：直接 FileOutputStream 写（无用户态缓冲）---");
        Files.deleteIfExists(TEMP_FILE);

        try (FileOutputStream out = new FileOutputStream(TEMP_FILE.toFile())) {
            // FileOutputStream 每次 write 都直接走 write() 系统调用，没有草稿本这一层
            out.write(new byte[150]);
            long size = Files.size(TEMP_FILE);
            System.out.println("write 150 字节后（未 close）：文件大小 = " + size + " 字节（OS 立刻看得到，停在 Page Cache）");
        }
        // try-with-resources 结束时自动 close()，close 会顺手 flush 一遍
    }

    // 实验三：FileChannel.force(true) —— 等价于 fsync，强制把数据刷进磁盘并拿「回执」
    // 生活比喻：让前台亲自把纸条送进保险柜，并拿回「已收到」回执，断电也不丢。
    private static void experiment3_forceToDisk() throws IOException {
        System.out.println("\n--- 实验三：FileChannel.force(true) 强制落盘（=fsync）---");
        Files.deleteIfExists(TEMP_FILE);

        try (FileOutputStream fos = new FileOutputStream(TEMP_FILE.toFile());
             FileChannel channel = fos.getChannel()) {

            channel.write(java.nio.ByteBuffer.wrap(new byte[300]));

            // force(true) 的 true 表示连文件元数据(大小/修改时间)一起刷；
            // 对应底层 fsync() 系统调用。这一步会阻塞，直到磁盘控制器确认落盘。
            channel.force(true);
            System.out.println("channel.force(true) 执行完毕：数据已强制刷到磁盘并拿到确认（断电也不丢）");
        }
    }
}
