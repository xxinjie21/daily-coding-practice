// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 面试题「设计一个文件上传系统」的可运行 Demo，把原题整套思路跑一遍：
//
//   演示 1：大文件切片上传 + 断点续传（传一半断网，只补缺失的分片）
//   演示 2：秒传（同一个文件再传一次，靠 MD5 指纹直接复用，根本不用传）
//   演示 3：上传完成后，病毒扫描/缩略图/转码这些脏活丢给队列异步做
//   演示 4：安全防护——文件类型白名单 + 同一用户上传次数限流
//
// 和「内存仿真版」的区别：这次全是真的。
//   * 分片真的落盘：每一片都写到 java.io.tmpdir 下的临时目录，合并时再读回来拼；
//   * 记账真的进 Redis（Jedis）：
//       已收到哪几片  -> Set（SADD / SMEMBERS），断点续传就靠它；
//       上传任务元信息 -> Hash，秒传索引 -> String（MD5 -> 存储路径）；
//       异步任务队列  -> List（RPUSH / LRANGE），真实项目这里换 Kafka；
//       上传限流      -> INCR + EXPIRE 的计数器。
//
// 前置条件：本机起一个 Redis（见同目录 docker-compose.yml，localhost:6379 无密码）。
// 运行：mvn -q compile exec:java
// ============================================================================

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import redis.clients.jedis.Jedis;

public class Demo {

    /** 每一片的大小。真实项目里一般 5MB；这里用 12 字节，一小段文字就能切出好几片。 */
    static final int CHUNK_SIZE = 12;

    /** 允许上传的文件后缀（白名单）。不在名单里的一律拒绝。 */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "png", "pdf", "mp4", "zip");

    /** 同一个用户每分钟最多发起几次上传（限流用）。 */
    static final int MAX_UPLOADS_PER_MINUTE = 3;

    /** 分片文件的落脚点：真的写到磁盘上的临时目录。 */
    static final Path TMP_DIR = Path.of(System.getProperty("java.io.tmpdir"), "daily-upload");

    /** 异步任务队列的 Redis key。 */
    static final String TASK_QUEUE_KEY = "queue:upload:tasks";

    public static void main(String[] args) throws IOException {
        System.out.println("========== 文件上传系统核心流程演示（分片真实落盘 + Redis 记账） ==========");

        // Jedis 实现了 Closeable，用 try-with-resources 保证连接一定关掉
        try (Jedis redis = new Jedis("localhost", 6379)) {
            redis.flushDB(); // 演示用：每次从干净状态开始（生产上千万别这么干）

            Path sourceFile = createSourceFile();
            String uploadId = "upload-1001";
            String fileName = "产品宣传片.mp4";

            demoResumeUpload(redis, uploadId, fileName, sourceFile);
            demoInstantUpload(redis, sourceFile);
            demoAsyncTasks(redis);
            demoSecurity(redis);
        }
    }

    // ========================================================================
    // 演示 1：切片上传 + 断点续传 + 合并校验
    //   生活比喻：搬一个两米高的大衣柜。整柜塞不进电梯（网关限制大小），
    //   路上磕了要全部重来（网络中断）；拆成木板分批搬，掉了一块补一块就行。
    // ========================================================================
    private static void demoResumeUpload(Jedis redis, String uploadId, String fileName, Path sourceFile)
            throws IOException {
        System.out.println("\n---------- 演示 1：切片上传 + 断点续传 ----------");

        byte[] fileBytes = Files.readAllBytes(sourceFile);
        String md5 = md5Hex(fileBytes);
        int totalChunks = (fileBytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE; // 向上取整
        String storagePath = "objects/" + md5 + "_" + fileName;
        String metaKey = metaKey(uploadId);

        // 第 1 步：创建上传任务，把元信息登记进 Redis（真实项目这里是一张 MySQL 表）
        System.out.println("源文件 " + sourceFile + "，大小 " + fileBytes.length + " 字节，指纹 MD5 = " + md5);
        redis.hset(metaKey, Map.of(
                "fileName", fileName,
                "fileSize", String.valueOf(fileBytes.length),
                "totalChunks", String.valueOf(totalChunks),
                "md5", md5,
                "storagePath", storagePath,
                "status", "上传中"));
        System.out.println("创建上传任务 " + uploadId + "，共切成 " + totalChunks + " 片，每片 " + CHUNK_SIZE + " 字节");

        // 第 2 步：前端先传前 3 片，然后网络断了
        int uploadedBeforeBreak = 3;
        for (int chunkIndex = 0; chunkIndex < uploadedBeforeBreak; chunkIndex++) {
            uploadChunk(redis, uploadId, chunkIndex, fileBytes);
        }
        System.out.println("已上传 " + uploadedBeforeBreak + " 片（分片文件真的落到了 " + TMP_DIR + "），此时网络断了……");

        // 第 3 步：断点续传——前端先问服务端「我还差哪几片？」
        List<Integer> missingChunks = findMissingChunks(redis, uploadId, totalChunks);
        System.out.println("服务端 SMEMBERS 查已传分片集合，回答：还差这几片 -> " + missingChunks);

        // 第 4 步：只补传缺失的分片，前面传过的不用再传
        for (int chunkIndex : missingChunks) {
            uploadChunk(redis, uploadId, chunkIndex, fileBytes);
        }
        System.out.println("补传完成，分片已全部到齐。");

        // 第 5 步：合并 + 校验（真实场景由对象存储的 multipart 接口完成）
        Path mergedFile = mergeChunks(uploadId, storagePath, totalChunks);
        byte[] mergedBytes = Files.readAllBytes(mergedFile);
        boolean intact = md5Hex(mergedBytes).equals(md5); // 指纹对不上就说明传输中文件坏了
        redis.hset(metaKey, "status", intact ? "已就绪" : "校验失败");
        redis.del(chunkKey(uploadId)); // 合并完临时分片就没用了，清掉省空间
        System.out.println("合并完成：" + mergedFile + "，大小 " + mergedBytes.length
                + " 字节，和源文件一模一样？" + intact);
        System.out.println("文件状态已更新为：" + redis.hget(metaKey, "status"));

        // 第 6 步：登记秒传索引（MD5 -> 存储路径，真实项目是表上的一条唯一索引）
        redis.set("file:md5:" + md5, storagePath);

        // 第 7 步：脏活累活丢进队列，别让用户干等
        redis.rpush(TASK_QUEUE_KEY, "病毒扫描：" + fileName, "生成缩略图：" + fileName, "视频转码：" + fileName);
    }

    // ========================================================================
    // 演示 2：秒传
    //   生活比喻：图书馆已经有《红楼梦》了，第二个人来借，管理员直接指路就行，
    //   不用再买一本。「书名」就是这里的 MD5 指纹。
    // ========================================================================
    private static void demoInstantUpload(Jedis redis, Path sourceFile) throws IOException {
        System.out.println("\n---------- 演示 2：秒传（同一个文件再传一次） ----------");

        String storagePath = redis.get("file:md5:" + md5Hex(Files.readAllBytes(sourceFile)));
        if (storagePath == null) {
            System.out.println("指纹没命中，需要老老实实分片上传。");
            return;
        }
        System.out.println("指纹命中！文件早就有人传过了，直接复用：");
        System.out.println("   存储路径 = " + storagePath);
        System.out.println("   结论：这次上传一个字节都不用传，用户瞬间看到「上传成功」。");
    }

    // ========================================================================
    // 演示 3：异步任务
    //   生活比喻：装修队长把家具搬进屋（上传）就算交付了，至于「擦灰、拍照、
    //   录系统」这些慢活，写进待办清单让后面的人慢慢做，用户不用站在门口等。
    // ========================================================================
    private static void demoAsyncTasks(Jedis redis) {
        System.out.println("\n---------- 演示 3：上传成功后的脏活累活丢给队列 ----------");

        List<String> tasks = redis.lrange(TASK_QUEUE_KEY, 0, -1);
        if (tasks.isEmpty()) {
            System.out.println("队列里暂时没有待处理任务。");
            return;
        }
        System.out.println("从 Redis List 取出 " + tasks.size() + " 个任务（真实场景是 Kafka 的消费者在拉）：");
        for (String task : tasks) {
            System.out.println("   处理中…… " + task);
        }
        System.out.println("注意：这些慢活都不占用用户的上传请求，用户早就看到「上传成功」了。");
    }

    // ========================================================================
    // 演示 4：安全防护
    //   ① 白名单：只放行允许的后缀，挡掉 .jsp / .exe 这种能被执行的危险文件
    //   ② 限流：同一个用户单位时间内上传次数封顶，防刷
    // ========================================================================
    private static void demoSecurity(Jedis redis) {
        System.out.println("\n---------- 演示 4：文件类型白名单 + 上传限流 ----------");

        System.out.println("① 白名单校验（只允许 " + ALLOWED_EXTENSIONS + "）：");
        for (String testFileName : new String[]{"头像.jpg", "木马.jsp", "合同.pdf", "病毒.exe"}) {
            String verdict = isAllowedFileType(testFileName) ? "允许上传" : "拒绝（不在白名单里）";
            System.out.println("   文件 " + testFileName + " -> " + verdict);
        }

        System.out.println("② 限流校验（同一用户每分钟最多 " + MAX_UPLOADS_PER_MINUTE + " 次，Redis 计数器）：");
        String userId = "user-9527";
        for (int attempt = 1; attempt <= 5; attempt++) {
            String verdict = tryAcquireUploadQuota(redis, userId) ? "放行" : "被限流拦截";
            System.out.println("   用户第 " + attempt + " 次发起上传 -> " + verdict);
        }
    }

    // ========================================================================
    // 以下是小工具与 Redis 记账逻辑
    // ========================================================================

    /** 造一个「大文件」：真实场景是从磁盘读一个 mp4，这里把一段文字写到临时文件里。 */
    private static Path createSourceFile() throws IOException {
        Files.createDirectories(TMP_DIR);
        Path sourceFile = TMP_DIR.resolve("source.mp4");
        Files.writeString(sourceFile, "这是一个模拟的大文件内容，用来演示分片上传和断点续传。", StandardCharsets.UTF_8);
        return sourceFile;
    }

    /** 收下一片：分片内容真的写到磁盘，同时在 Redis 的 Set 里记一笔「这片我收到了」。 */
    private static void uploadChunk(Jedis redis, String uploadId, int chunkIndex, byte[] fileBytes)
            throws IOException {
        int from = chunkIndex * CHUNK_SIZE;
        int to = Math.min(from + CHUNK_SIZE, fileBytes.length);
        Path chunkFile = chunkPath(uploadId, chunkIndex);
        Files.createDirectories(chunkFile.getParent());
        Files.write(chunkFile, Arrays.copyOfRange(fileBytes, from, to));
        redis.sadd(chunkKey(uploadId), String.valueOf(chunkIndex));
    }

    /** 断点续传的核心：拿「已收到哪几片」和总数一比，就知道还缺谁。 */
    private static List<Integer> findMissingChunks(Jedis redis, String uploadId, int totalChunks) {
        Set<String> uploadedChunks = redis.smembers(chunkKey(uploadId));
        List<Integer> missingChunks = new ArrayList<>();
        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            if (!uploadedChunks.contains(String.valueOf(chunkIndex))) {
                missingChunks.add(chunkIndex);
            }
        }
        return missingChunks;
    }

    /** 按序号把分片文件拼成一个完整文件（真实场景由对象存储的 multipart 接口完成）。 */
    private static Path mergeChunks(String uploadId, String storagePath, int totalChunks) throws IOException {
        Path mergedFile = TMP_DIR.resolve(storagePath);
        Files.createDirectories(mergedFile.getParent());
        try (OutputStream out = Files.newOutputStream(mergedFile)) {
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                out.write(Files.readAllBytes(chunkPath(uploadId, chunkIndex)));
            }
        }
        return mergedFile;
    }

    /**
     * 限流：INCR 自增计数，第一次调用时顺手挂上 60 秒过期。
     * 这就是最经典的 Redis 计数器限流——「到点自动清零」是 Redis 的原生能力。
     */
    private static boolean tryAcquireUploadQuota(Jedis redis, String userId) {
        String quotaKey = "quota:upload:" + userId;
        long usedTimes = redis.incr(quotaKey);
        if (usedTimes == 1) {
            redis.expire(quotaKey, 60);
        }
        return usedTimes <= MAX_UPLOADS_PER_MINUTE;
    }

    /** 白名单校验：后缀不在允许名单里就直接拒绝。没有后缀名的也当成不安全。 */
    private static boolean isAllowedFileType(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(fileName.substring(dotIndex + 1).toLowerCase());
    }

    /**
     * 算 MD5 指纹：内容改一个字节，指纹就完全不同。
     * 两个用途：① 秒传去重（拿指纹查有没有传过）② 上传后校验文件有没有损坏。
     * 注意：MD5 已被证明能构造出「不同文件却指纹相同」，做安全校验建议换 SHA-256。
     */
    private static String md5Hex(byte[] data) {
        try {
            byte[] hashBytes = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder hexText = new StringBuilder();
            for (byte b : hashBytes) {
                hexText.append(String.format("%02x", b));
            }
            return hexText.toString();
        } catch (Exception e) {
            throw new IllegalStateException("当前 JDK 不支持 MD5 算法", e);
        }
    }

    private static String metaKey(String uploadId) {
        return "upload:" + uploadId + ":meta";
    }

    private static String chunkKey(String uploadId) {
        return "upload:" + uploadId + ":chunks";
    }

    private static Path chunkPath(String uploadId, int chunkIndex) {
        return TMP_DIR.resolve(uploadId).resolve("chunk-" + chunkIndex);
    }
}
