import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import redis.clients.jedis.Jedis;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 演示「几百万行 Excel 导入数据库」的四个关键点：
 *   1. 流式解析 —— 用 POI 的 XSSFReader（SAX 事件模式）边读边处理，内存不随行数增长
 *   2. 攒批入库 —— 每 500 行拼成一条 INSERT ... VALUES(...),(...)，而不是一行一次往返
 *   3. MQ 削峰 —— 解析线程把「一批」打成一条消息投到 RabbitMQ，入库线程按自己的节奏消费
 *   4. 进度反馈 —— 每批入库后 HINCRBY 到 Redis，前端随时能查到「搬了多少」
 *
 * 前置条件（先起中间件）：
 *   cd code && docker compose up -d      # MySQL 3307 / Redis 6380 / RabbitMQ 5673
 */
public class Demo {

    static final int TOTAL_ROWS = 100_000;   // 演示用 10 万行；真实场景几百万行是同一套写法
    static final int BATCH_SIZE = 500;       // 每批 500 行：批太小往返多，批太大单条 SQL 过长
    static final String EXCEL_FILE = "big-orders.xlsx";
    static final String QUEUE = "excel-import";
    static final String PROGRESS_KEY = "import:progress";

    public static void main(String[] args) throws Exception {
        generateBigExcel(EXCEL_FILE);

        // 消费线程写进度、主线程读进度，各自用一个 Jedis（单连接不能两个线程混用）
        Jedis progressWriter = new Jedis("127.0.0.1", 6380);
        Jedis progressReader = new Jedis("127.0.0.1", 6380);
        Connection mysql = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3307/demo?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "root");
        initTable(mysql);
        progressWriter.del(PROGRESS_KEY);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(5673);
        com.rabbitmq.client.Connection amqp = factory.newConnection();
        Channel channel = amqp.createChannel();
        channel.queueDeclare(QUEUE, false, false, false, null);

        try {
            Thread consumer = new Thread(() -> consumeAndInsert(channel, mysql, progressWriter));
            consumer.setDaemon(true);   // 守护线程，避免 exec:java 等非守护线程永久阻塞
            consumer.start();

            long start = System.currentTimeMillis();
            int parsed = streamReadAndPublish(EXCEL_FILE, channel);
            long parseMs = System.currentTimeMillis() - start;

            waitUntilDone(progressReader, TOTAL_ROWS, 180_000);
            Thread.sleep(300);   // 等最后一批的 ack 落地，再关连接

            System.out.println("流式解析并投递：" + parsed + " 行，耗时 " + parseMs + "ms（内存占用平稳）");
            System.out.println("数据库实际入库：" + countRows(mysql) + " 行");
            System.out.println("Redis 进度：" + progressReader.hgetAll(PROGRESS_KEY));
        } finally {
            channel.close();
            amqp.close();
            mysql.close();
            progressReader.close();
            progressWriter.close();
        }
    }

    /** 1) 用 SXSSFWorkbook 流式写样本文件：内存里只留 100 行，其余滚到临时文件，才造得出「大文件」 */
    static void generateBigExcel(String path) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             FileOutputStream out = new FileOutputStream(path)) {
            Sheet sheet = workbook.createSheet("orders");
            String[] columns = {"orderId", "userId", "amount", "status"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            for (int i = 1; i <= TOTAL_ROWS; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("NO" + i);
                row.createCell(1).setCellValue(1000 + i % 1000);
                row.createCell(2).setCellValue(10.5 + i % 500);
                row.createCell(3).setCellValue(i % 2 == 0 ? "PAID" : "NEW");
            }
            workbook.write(out);   // close() 会自动 dispose 掉临时文件
        }
    }

    /** 2) SAX 事件模式流式读：读满一批就发一条消息，不把整张表读进内存 */
    static int streamReadAndPublish(String path, Channel channel) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(path, PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings sharedStrings = reader.getSharedStringsTable();

            BatchPublisher publisher = new BatchPublisher(channel);
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(
                    reader.getStylesTable(), sharedStrings, publisher, false));

            try (InputStream sheetStream = ((XSSFReader.SheetIterator) reader.getSheetsData()).next()) {
                parser.parse(new InputSource(sheetStream));
            }
            publisher.flushRemainder();
            return publisher.parsedRows;
        }
    }

    /** 3) 入库线程：收到一批就一条批量 INSERT，成功后把「已处理行数」累加进 Redis */
    static void consumeAndInsert(Channel channel, Connection mysql, Jedis progress) {
        try {
            channel.basicQos(1);   // 一次只推一批，消息不堆在客户端内存里
            channel.basicConsume(QUEUE, false, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope,
                                           AMQP.BasicProperties props, byte[] body) throws IOException {
                    List<String[]> rows = decode(new String(body, StandardCharsets.UTF_8));
                    try {
                        insertBatch(mysql, rows);
                        progress.hincrBy(PROGRESS_KEY, "done", rows.size());
                        channel.basicAck(envelope.getDeliveryTag(), false);
                    } catch (Exception e) {
                        System.out.println("本批入库失败，消息重回队列等待重试：" + e.getMessage());
                        channel.basicNack(envelope.getDeliveryTag(), false, true);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一条 INSERT 塞进一整批：网络往返从 500 次压成 1 次 */
    static void insertBatch(Connection mysql, List<String[]> rows) throws SQLException {
        String placeholders = String.join(", ", Collections.nCopies(rows.size(), "(?, ?, ?, ?)"));
        String sql = "INSERT INTO orders(order_id, user_id, amount, status) VALUES " + placeholders;
        try (PreparedStatement ps = mysql.prepareStatement(sql)) {
            int index = 1;
            for (String[] row : rows) {
                ps.setString(index++, row[0]);
                ps.setInt(index++, Integer.parseInt(row[1]));
                ps.setDouble(index++, Double.parseDouble(row[2]));
                ps.setString(index++, row[3]);
            }
            ps.executeUpdate();
        }
    }

    /** 攒批 + 投递：Excel 里的每一批在 MQ 里就是一条消息，队列天然起到削峰缓冲作用 */
    static class BatchPublisher implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Channel channel;
        private final List<String[]> batch = new ArrayList<>(BATCH_SIZE);
        private final List<String> currentRow = new ArrayList<>(4);
        private boolean firstRow = true;   // 首行是表头，跳过
        int parsedRows = 0;

        BatchPublisher(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void startRow(int rowNum) {
            currentRow.clear();
        }

        @Override
        public void endRow(int rowNum) {
            if (firstRow) {
                firstRow = false;
                return;
            }
            batch.add(currentRow.toArray(new String[0]));
            parsedRows++;
            if (batch.size() >= BATCH_SIZE) {
                publishBatch();
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            currentRow.add(formattedValue);
        }

        private void publishBatch() {
            if (batch.isEmpty()) {
                return;
            }
            try {
                channel.basicPublish("", QUEUE, null,
                        encode(batch).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            batch.clear();
        }

        void flushRemainder() {
            publishBatch();   // 最后不足一批的尾巴也要发出去
        }
    }

    /** 一批行拼成一段文本：每行一条记录、字段用逗号分隔（演示用，生产可换 JSON / Protobuf） */
    static String encode(List<String[]> rows) {
        StringBuilder text = new StringBuilder();
        for (String[] row : rows) {
            text.append(String.join(",", row)).append('\n');
        }
        return text.toString();
    }

    static List<String[]> decode(String text) {
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                rows.add(line.split(","));
            }
        }
        return rows;
    }

    static void initTable(Connection mysql) throws SQLException {
        try (Statement st = mysql.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("CREATE TABLE orders ("
                    + "order_id VARCHAR(32) PRIMARY KEY, user_id INT, "
                    + "amount DECIMAL(10,2), status VARCHAR(16))");
        }
    }

    static long countRows(Connection mysql) throws SQLException {
        try (Statement st = mysql.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 轮询 Redis 进度直到全部入库：模拟前端那个「导入进度条」 */
    static void waitUntilDone(Jedis reader, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String done = reader.hget(PROGRESS_KEY, "done");
            if (done != null && Integer.parseInt(done) >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        System.out.println("等待超时，仍有数据未入库");
    }
}
