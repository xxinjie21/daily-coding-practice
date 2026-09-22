// 编译运行：javac -encoding UTF-8 --release 17 Demo.java && java Demo
//
// ============================================================================
// 这个程序在干嘛？
// ----------------------------------------------------------------------------
// 用一个"内存里的小型文件上传系统"，把原题里的整套思路跑一遍：
//
//   演示 1：大文件切片上传 + 断点续传（传一半断网，只补缺失的分片）
//   演示 2：秒传（同一个文件再传一次，靠 MD5 指纹直接复用，根本不用传）
//   演示 3：上传完成后，病毒扫描/缩略图/转码这些脏活丢给异步队列慢慢做
//   演示 4：安全防护——文件类型白名单 + 同一用户上传次数限流
//
// 三个"角色"都在这个文件里用最简单的内存结构模拟：
//   FakeObjectStorage -> 对象存储（真实场景是 MinIO / 阿里云 OSS）
//   MetadataTable     -> 数据库里的文件元信息表（只存信息，不存文件本身）
//   UploadService     -> 后端服务（收分片、记账、合并、校验）
//
// 运行后会打印每一步在做什么，可以直接对照题解.md 看。
// ============================================================================

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

public class Demo {

    /**
     * 每一片的大小。
     * 真实项目里一般是 5MB；这里为了演示方便，只用 12 字节，
     * 这样一小段文字就能被切成好几片，方便观察"断点续传"的效果。
     */
    static final int CHUNK_SIZE = 12;

    /** 允许上传的文件后缀（白名单）。不在名单里的，一律拒绝。 */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "png", "pdf", "mp4", "zip");

    /** 同一个用户每分钟最多发起几次上传（限流用） */
    static final int MAX_UPLOADS_PER_MINUTE = 3;

    public static void main(String[] args) {
        System.out.println("========== 文件上传系统核心流程演示 ==========");

        UploadService uploadService = new UploadService();

        demoResumeUpload(uploadService);   // 演示 1：分片上传 + 断点续传 + 合并校验
        demoInstantUpload(uploadService);  // 演示 2：秒传
        demoAsyncTasks(uploadService);     // 演示 3：异步任务
        demoSecurity(uploadService);       // 演示 4：白名单 + 限流
    }

    // ========================================================================
    // 演示 1：大文件切片上传 + 断点续传
    //
    // 生活比喻：搬一个两米高的大衣柜。
    //   一趟拉整个柜子 -> 电梯塞不下（网关限制大小），路上磕了要全部重来（网络中断）。
    //   拆成木板分批搬   -> 装得下，掉了一块补一块，到地方再拼起来。
    // ========================================================================
    private static void demoResumeUpload(UploadService uploadService) {
        System.out.println("\n---------- 演示 1：切片上传 + 断点续传 ----------");

        // 造一个"大文件"。真实场景是从磁盘读一个 mp4，这里用一段文字代替。
        byte[] fileBytes = "这是一个模拟的大文件内容，用来演示分片上传和断点续传。"
                .getBytes(StandardCharsets.UTF_8);
        String uploadId = "upload-1001";
        String fileName = "产品宣传片.mp4";

        // 第 1 步：前端切片，并算出整个文件的 MD5 指纹
        System.out.println("源文件大小 = " + fileBytes.length + " 字节，指纹 MD5 = " + md5Hex(fileBytes));

        // 第 2 步：创建上传任务，服务端把元信息登记进"数据库"
        FileMeta meta = uploadService.createTask(uploadId, fileName, fileBytes);
        System.out.println("创建上传任务：" + uploadId + "，共切成 " + meta.totalChunks() + " 片，"
                + "每片 " + CHUNK_SIZE + " 字节");

        // 第 3 步：前端先传前 3 片，然后网络断了
        int uploadedBeforeBreak = 3;
        for (int chunkIndex = 0; chunkIndex < uploadedBeforeBreak; chunkIndex++) {
            uploadService.uploadChunk(uploadId, chunkIndex, slice(fileBytes, chunkIndex));
        }
        System.out.println("已上传 " + uploadedBeforeBreak + " 片，此时网络断了……");

        // 第 4 步：断点续传——前端先问服务端"我还差哪几片？"
        List<Integer> missingChunks = uploadService.findMissingChunks(uploadId);
        System.out.println("服务端回答：还差这几片 -> " + missingChunks);

        // 第 5 步：只补传缺失的分片，前面传过的不用再传
        for (int chunkIndex : missingChunks) {
            uploadService.uploadChunk(uploadId, chunkIndex, slice(fileBytes, chunkIndex));
        }
        System.out.println("补传完成，分片已全部到齐。");

        // 第 6 步：通知服务端合并 + 校验（真实场景由对象存储的 multipart 接口完成）
        byte[] mergedBytes = uploadService.completeUpload(uploadId);
        System.out.println("合并完成：大小 " + mergedBytes.length + " 字节，"
                + "和源文件一模一样？" + Arrays.equals(mergedBytes, fileBytes));
        System.out.println("文件状态已更新为：" + uploadService.findMeta(uploadId).status());
    }

    // ========================================================================
    // 演示 2：秒传
    //
    // 生活比喻：图书馆里已经有一本《红楼梦》，第二个人来借的时候，
    //   管理员不需要再买一本，直接指路"在 3 号架"就行了。
    //   这里"书名"就是 MD5 指纹。
    // ========================================================================
    private static void demoInstantUpload(UploadService uploadService) {
        System.out.println("\n---------- 演示 2：秒传（同一个文件再传一次） ----------");

        byte[] sameFileBytes = "这是一个模拟的大文件内容，用来演示分片上传和断点续传。"
                .getBytes(StandardCharsets.UTF_8);

        // 上传前先算指纹，拿指纹去"数据库"里查有没有人传过
        FileMeta alreadyExists = uploadService.findByMd5(sameFileBytes);
        if (alreadyExists != null) {
            System.out.println("指纹命中！文件早就有人传过了，直接复用：");
            System.out.println("   存储路径 = " + alreadyExists.storagePath());
            System.out.println("   结论：这次上传一个字节都不用传，用户瞬间看到「上传成功」。");
        } else {
            System.out.println("指纹没命中，需要老老实实分片上传。");
        }
    }

    // ========================================================================
    // 演示 3：异步任务
    //
    // 生活比喻：装修队长把家具搬进屋（上传）就算交付了，
    //   至于"擦灰、拍照、录系统"这些慢活，写进待办清单让后面的人慢慢做。
    //   用户不用站在门口等这些事做完。
    // ========================================================================
    private static void demoAsyncTasks(UploadService uploadService) {
        System.out.println("\n---------- 演示 3：上传成功后的脏活累活丢给队列 ----------");

        List<String> tasks = uploadService.takeAllAsyncTasks();
        if (tasks.isEmpty()) {
            System.out.println("队列里暂时没有待处理任务。");
            return;
        }
        System.out.println("从队列里取出 " + tasks.size() + " 个任务（真实场景是 Kafka 的消费者在拉）：");
        for (String task : tasks) {
            System.out.println("   处理中…… " + task);
        }
        System.out.println("注意：这些慢活都不占用用户的上传请求，用户早就看到「上传成功」了。");
    }

    // ========================================================================
    // 演示 4：安全防护
    //   ① 白名单：只放行允许的后缀，挡掉 .jsp / .exe 这种危险文件
    //   ② 限流：同一个用户单位时间内上传次数要封顶，防刷
    // ========================================================================
    private static void demoSecurity(UploadService uploadService) {
        System.out.println("\n---------- 演示 4：文件类型白名单 + 上传限流 ----------");

        System.out.println("① 白名单校验（只允许 " + ALLOWED_EXTENSIONS + "）：");
        String[] testFileNames = {"头像.jpg", "木马.jsp", "合同.pdf", "病毒.exe"};
        for (String testFileName : testFileNames) {
            boolean allowed = uploadService.isAllowedFileType(testFileName);
            System.out.println("   文件 " + testFileName + " -> " + (allowed ? "允许上传" : "拒绝（不在白名单里）"));
        }

        System.out.println("② 限流校验（同一用户每分钟最多 " + MAX_UPLOADS_PER_MINUTE + " 次）：");
        String userId = "user-9527";
        for (int attempt = 1; attempt <= 5; attempt++) {
            boolean passed = uploadService.tryAcquireUploadQuota(userId);
            System.out.println("   用户第 " + attempt + " 次发起上传 -> " + (passed ? "放行" : "被限流拦截"));
        }
    }

    // ========================================================================
    // 小工具：把完整文件切成第 index 片
    // 真实场景是前端用 Blob.slice() 切片，这里用数组截取来模拟。
    // ========================================================================
    private static byte[] slice(byte[] fileBytes, int index) {
        int from = index * CHUNK_SIZE;
        int to = Math.min(from + CHUNK_SIZE, fileBytes.length);
        return Arrays.copyOfRange(fileBytes, from, to);
    }

    // ========================================================================
    // 小工具：算 MD5 指纹
    // MD5 可以理解成文件的"指纹"——内容改一个字节，指纹就完全不同。
    // 它有两个用途：① 秒传去重（拿指纹查有没有传过）② 上传后校验文件有没有损坏。
    // 注意：MD5 已被证明能被构造出"不同文件却指纹相同"，生产环境做安全校验建议用 SHA-256。
    // ========================================================================
    static String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hexText = new StringBuilder();
            for (byte b : hashBytes) {
                hexText.append(String.format("%02x", b));
            }
            return hexText.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 MD5 算法", e);
        }
    }

    // ========================================================================
    // 表结构：文件元信息（数据库里真正存的东西）
    // 注意：这里存的都是"关于文件的信息"，文件本身在对象存储里。
    // 就像仓库登记本上写的是"3 号仓、A 区、大衣柜"，而不是把衣柜画在纸上。
    // ========================================================================
    record FileMeta(String fileId,
                    String fileName,
                    long fileSize,
                    int totalChunks,
                    String md5,
                    String storagePath,
                    String status) {
    }

    // ========================================================================
    // 角色一：对象存储（真实场景 = MinIO / 阿里云 OSS）
    // 它有两个抽屉：
    //   chunkBox  -> 临时放"还没拼起来的分片"
    //   objectBox -> 放"已经合并好的完整文件"
    // ========================================================================
    static class FakeObjectStorage {

        /** uploadId -> (分片序号 -> 分片内容) */
        private final Map<String, Map<Integer, byte[]>> chunkBox = new HashMap<>();

        /** storagePath -> 合并后的完整文件内容 */
        private final Map<String, byte[]> objectBox = new HashMap<>();

        /** 收下一片 */
        void saveChunk(String uploadId, int chunkIndex, byte[] chunkData) {
            chunkBox.computeIfAbsent(uploadId, key -> new HashMap<>()).put(chunkIndex, chunkData);
        }

        /** 查这次上传任务已经收到了哪几片（断点续传就靠它） */
        Set<Integer> findUploadedChunkIndexes(String uploadId) {
            return new TreeSet<>(chunkBox.getOrDefault(uploadId, Map.of()).keySet());
        }

        /** 把所有分片按顺序拼成一个完整文件（真实场景由对象存储的 multipart 接口完成） */
        byte[] mergeChunks(String uploadId, String storagePath, int totalChunks) {
            Map<Integer, byte[]> uploadedChunks = chunkBox.getOrDefault(uploadId, Map.of());
            ByteArrayOutputStream mergedStream = new ByteArrayOutputStream();
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                byte[] chunkData = uploadedChunks.get(chunkIndex);
                if (chunkData == null) {
                    throw new IllegalStateException("第 " + chunkIndex + " 片还没传上来，不能合并");
                }
                mergedStream.write(chunkData, 0, chunkData.length);
            }
            byte[] mergedBytes = mergedStream.toByteArray();
            objectBox.put(storagePath, mergedBytes);
            return mergedBytes;
        }

        /** 合并完成后，临时分片就没用了，清掉省空间 */
        void cleanChunks(String uploadId) {
            chunkBox.remove(uploadId);
        }

        /** 从对象存储里把完整文件取出来 */
        byte[] download(String storagePath) {
            return objectBox.get(storagePath);
        }
    }

    // ========================================================================
    // 角色二：数据库里的元信息表
    // 除了按 fileId 查，还额外维护了一份"MD5 -> fileId"的索引，专门给秒传用。
    // ========================================================================
    static class MetadataTable {

        private final Map<String, FileMeta> metaByFileId = new LinkedHashMap<>();

        /** MD5 指纹 -> fileId，这就是"秒传"能一秒查到的原因 */
        private final Map<String, String> fileIdByMd5 = new HashMap<>();

        void save(FileMeta meta) {
            metaByFileId.put(meta.fileId(), meta);
            fileIdByMd5.put(meta.md5(), meta.fileId());
        }

        FileMeta findByFileId(String fileId) {
            return metaByFileId.get(fileId);
        }

        FileMeta findByMd5(String md5) {
            String fileId = fileIdByMd5.get(md5);
            return fileId == null ? null : metaByFileId.get(fileId);
        }

        /** 只改状态字段。record 是不可变的，所以这里重新造一条记录覆盖掉。 */
        void updateStatus(String fileId, String newStatus) {
            FileMeta old = metaByFileId.get(fileId);
            metaByFileId.put(fileId, new FileMeta(old.fileId(), old.fileName(), old.fileSize(),
                    old.totalChunks(), old.md5(), old.storagePath(), newStatus));
        }
    }

    // ========================================================================
    // 角色三：后端上传服务
    // 前端要打交道的就是它：创建任务 / 传分片 / 查缺失分片 / 通知合并
    // ========================================================================
    static class UploadService {

        private final FakeObjectStorage objectStorage = new FakeObjectStorage();
        private final MetadataTable metadataTable = new MetadataTable();

        /** 异步任务队列。真实场景是 Kafka，这里用内存队列代替。 */
        private final Queue<String> asyncTaskQueue = new ArrayDeque<>();

        /** 每个用户已经用掉的"上传次数"（简化版限流计数器） */
        private final Map<String, Integer> uploadCountPerUser = new HashMap<>();

        /**
         * 第 1 步：创建上传任务，把元信息登记进数据库。
         * 生活比喻：装修队长先在本子上登记"要搬一个大衣柜，预计 7 块板"。
         */
        FileMeta createTask(String uploadId, String fileName, byte[] fileBytes) {
            int totalChunks = (fileBytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE; // 向上取整
            String md5 = md5Hex(fileBytes);
            String storagePath = "objects/" + md5 + "_" + fileName;
            FileMeta meta = new FileMeta(uploadId, fileName, fileBytes.length, totalChunks,
                    md5, storagePath, "上传中");
            metadataTable.save(meta);
            return meta;
        }

        /** 第 2 步：收下一片分片，存进对象存储 */
        void uploadChunk(String uploadId, int chunkIndex, byte[] chunkData) {
            objectStorage.saveChunk(uploadId, chunkIndex, chunkData);
        }

        /**
         * 第 3 步：查这次任务还缺哪几片。
         * 这就是"断点续传"的核心：前端上传前先问一句，只补缺失的那些。
         */
        List<Integer> findMissingChunks(String uploadId) {
            FileMeta meta = metadataTable.findByFileId(uploadId);
            Set<Integer> uploadedIndexes = objectStorage.findUploadedChunkIndexes(uploadId);
            List<Integer> missingChunks = new ArrayList<>();
            for (int chunkIndex = 0; chunkIndex < meta.totalChunks(); chunkIndex++) {
                if (!uploadedIndexes.contains(chunkIndex)) {
                    missingChunks.add(chunkIndex);
                }
            }
            return missingChunks;
        }

        /**
         * 第 4 步：合并 + 校验 + 投递异步任务。
         * 生活比喻：木板全到齐了，拼成柜子；再对着清单检查一遍有没有磕坏；
         *          最后把"擦灰、拍照"写进待办清单，交给后面的人。
         */
        byte[] completeUpload(String uploadId) {
            FileMeta meta = metadataTable.findByFileId(uploadId);

            // 先确认分片齐了，不齐不许合并
            List<Integer> missingChunks = findMissingChunks(uploadId);
            if (!missingChunks.isEmpty()) {
                throw new IllegalStateException("还有分片没传完：" + missingChunks);
            }

            // 合并分片（真实场景：调对象存储的 completeMultipartUpload 接口）
            byte[] mergedBytes = objectStorage.mergeChunks(uploadId, meta.storagePath(), meta.totalChunks());

            // 校验：合并后的指纹必须和上传前登记的一致，否则说明文件在传输中损坏了
            String mergedMd5 = md5Hex(mergedBytes);
            if (!mergedMd5.equals(meta.md5())) {
                metadataTable.updateStatus(uploadId, "校验失败");
                throw new IllegalStateException("合并后的 MD5 对不上，文件可能损坏了");
            }

            // 校验通过，状态改成"已就绪"，临时分片清掉
            metadataTable.updateStatus(uploadId, "已就绪");
            objectStorage.cleanChunks(uploadId);

            // 把后续慢活丢进队列，不让用户干等
            asyncTaskQueue.add("病毒扫描：" + meta.fileName());
            asyncTaskQueue.add("生成缩略图：" + meta.fileName());
            asyncTaskQueue.add("视频转码：" + meta.fileName());

            return mergedBytes;
        }

        /**
         * 秒传检查：拿 MD5 指纹去数据库里查，查到就直接复用，不用再传。
         * 生活比喻：图书馆已经有这本书了，第二个人来借，管理员直接指路就行。
         */
        FileMeta findByMd5(byte[] fileBytes) {
            return metadataTable.findByMd5(md5Hex(fileBytes));
        }

        /** 按任务号查元信息（演示里用来看看状态有没有变） */
        FileMeta findMeta(String uploadId) {
            return metadataTable.findByFileId(uploadId);
        }

        /** 模拟消费者把队列里的异步任务全部取走处理 */
        List<String> takeAllAsyncTasks() {
            List<String> tasks = new ArrayList<>();
            while (!asyncTaskQueue.isEmpty()) {
                tasks.add(asyncTaskQueue.poll());
            }
            return tasks;
        }

        /**
         * 白名单校验：后缀不在允许名单里的，直接拒绝。
         * 为什么要这么做？因为如果允许上传 .jsp / .php / .exe，
         * 攻击者就能把恶意脚本传上来，再想办法让它被执行。
         */
        boolean isAllowedFileType(String fileName) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
                return false; // 没有后缀名，也当成不安全
            }
            String extension = fileName.substring(dotIndex + 1).toLowerCase();
            return ALLOWED_EXTENSIONS.contains(extension);
        }

        /**
         * 简化版限流：同一个用户每分钟最多发起 MAX_UPLOADS_PER_MINUTE 次上传。
         * 真实项目里会用 Redis 的计数器 + 过期时间来做，这里用内存 Map 演示思路。
         */
        boolean tryAcquireUploadQuota(String userId) {
            int usedTimes = uploadCountPerUser.getOrDefault(userId, 0);
            if (usedTimes >= MAX_UPLOADS_PER_MINUTE) {
                return false;
            }
            uploadCountPerUser.put(userId, usedTimes + 1);
            return true;
        }
    }
}
