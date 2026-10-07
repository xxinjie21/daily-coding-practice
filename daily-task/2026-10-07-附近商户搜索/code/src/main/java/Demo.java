import redis.clients.jedis.GeoCoordinate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.args.GeoUnit;
import redis.clients.jedis.params.GeoSearchParam;
import redis.clients.jedis.resps.GeoRadiusResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 演示「100 万商户里快速找出最近的 5 家」：粗筛 + 精算两步走。
 *
 * 三个演示：
 *   1. 基线：把商户全捞出来，逐条算球面距离（Haversine）再排序取 Top5 —— 数据量一大就顶不住。
 *   2. 优化：经纬度写进 Redis 的 GEO 结构，一条 GEOSEARCH 直接拿回最近的 5 家。
 *   3. 原理：看近邻商户的 geohash 前缀为什么一样 —— 这就是「粗筛」能砍掉绝大部分数据的根据。
 *
 * 前置条件（Git Bash）：
 *   cd daily-task/2026-10-07-附近商户搜索/code && docker compose up -d
 * 运行：
 *   mvn -q exec:java
 */
public class Demo {

    /** 演示数据量：原题是 100 万，这里按比例缩到 30 万，结论一致、跑得更快。 */
    static final int MERCHANT_COUNT = 300_000;
    static final String GEO_KEY = "merchants";
    static final int TOP = 5;
    /** 搜索半径（公里）：只找 5 公里内的店。 */
    static final double RADIUS_KM = 5;
    /** 北京国贸附近，当作「用户当前所在位置」。 */
    static final double USER_LON = 116.40;
    static final double USER_LAT = 39.90;

    /** 一个商户：编号 + 经纬度。 */
    record Merchant(String id, double lon, double lat) {}

    /** 一条命中的结果：商户编号 + 离用户多少公里。 */
    record Hit(String id, double km) {}

    public static void main(String[] args) {
        try (JedisPool pool = new JedisPool("127.0.0.1", 6380)) {
            Jedis jedis = pool.getResource();
            jedis.flushDB();

            List<Merchant> all = generateMerchants(MERCHANT_COUNT);
            loadIntoRedis(jedis, all);
            System.out.println("已把 " + jedis.zcard(GEO_KEY) + " 家商户写进 Redis 的 GEO 结构（底层是一个有序集合）");

            // 演示 1：老办法 —— 把数据全捞出来，自己逐条算距离
            System.out.println("\n【基线】全量遍历，自己算距离");
            long fetchStart = System.nanoTime();
            List<String> allIds = jedis.zrange(GEO_KEY, 0, -1);
            long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000;
            long scanStart = System.nanoTime();
            List<Hit> scanHits = topByFullScan(all);
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
            System.out.println("  取回全部 " + allIds.size() + " 个商户编号：" + fetchMs + " ms");
            System.out.println("  逐条算球面距离并排序：" + scanMs + " ms");
            System.out.println("  合计 " + (fetchMs + scanMs) + " ms");
            printHits(scanHits);

            // 演示 2：新办法 —— 一条 GEOSEARCH 交给 Redis
            long geoStart = System.nanoTime();
            List<Hit> geoHits = topByRedisGeo(jedis);
            long geoMs = (System.nanoTime() - geoStart) / 1_000_000;
            System.out.println("\n【优化】一条 GEOSEARCH，耗时 " + geoMs + " ms（含一次网络往返）");
            printHits(geoHits);

            // 演示 3：为什么能「粗筛」—— 近邻的 geohash 前缀一样，远处的不一样
            Merchant farthest = all.stream()
                    .max(Comparator.comparingDouble(m -> haversineKm(USER_LON, USER_LAT, m.lon(), m.lat())))
                    .orElseThrow();
            showGeoHashPrefix(jedis, geoHits, farthest.id());

            jedis.close();
        }
    }

    /** 以用户位置为中心，向东西南北各铺开约 20 公里，随机撒点当商户。 */
    static List<Merchant> generateMerchants(int count) {
        Random random = new Random(42); // 固定种子，每次跑数据一样，方便对照
        List<Merchant> merchants = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double lon = USER_LON + (random.nextDouble() - 0.5) * 0.4;
            double lat = USER_LAT + (random.nextDouble() - 0.5) * 0.4;
            merchants.add(new Merchant("m" + i, lon, lat));
        }
        return merchants;
    }

    /** 分批 GEOADD 写入 Redis，避免单条命令塞太多数据。 */
    static void loadIntoRedis(Jedis jedis, List<Merchant> merchants) {
        int batchSize = 5000;
        for (int start = 0; start < merchants.size(); start += batchSize) {
            Map<String, GeoCoordinate> batch = new HashMap<>();
            for (int i = start; i < Math.min(start + batchSize, merchants.size()); i++) {
                Merchant m = merchants.get(i);
                batch.put(m.id(), new GeoCoordinate(m.lon(), m.lat()));
            }
            jedis.geoadd(GEO_KEY, batch);
        }
    }

    /** 基线做法：每家都算一遍球面距离，用大顶堆维护「最近的 5 家」。 */
    static List<Hit> topByFullScan(List<Merchant> merchants) {
        // 大顶堆：堆顶永远是「当前最远的那家」，堆一超过 5 个就把最远的踢掉
        PriorityQueue<Hit> heap = new PriorityQueue<>(Comparator.comparingDouble(Hit::km).reversed());
        for (Merchant m : merchants) {
            heap.offer(new Hit(m.id(), haversineKm(USER_LON, USER_LAT, m.lon(), m.lat())));
            if (heap.size() > TOP) {
                heap.poll();
            }
        }
        List<Hit> hits = new ArrayList<>(heap);
        hits.sort(Comparator.comparingDouble(Hit::km));
        return hits;
    }

    /** 优化做法：一条 GEOSEARCH，粗筛、精算、排序、取前 5 全在 Redis 内部完成。 */
    static List<Hit> topByRedisGeo(Jedis jedis) {
        GeoSearchParam param = GeoSearchParam.geoSearchParam()
                .fromLonLat(USER_LON, USER_LAT)
                .byRadius(RADIUS_KM, GeoUnit.KM)
                .withDist()
                .asc()
                .count(TOP);
        List<Hit> hits = new ArrayList<>();
        for (GeoRadiusResponse response : jedis.geosearch(GEO_KEY, param)) {
            hits.add(new Hit(response.getMemberByString(), response.getDistance()));
        }
        return hits;
    }

    /** 把结果打印成人看得懂的样子。 */
    static void printHits(List<Hit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            System.out.println("  " + (i + 1) + ". " + hit.id()
                    + "  距离 " + String.format("%.3f", hit.km()) + " km");
        }
    }

    /** 展示「近邻的 geohash 前缀一样、远处的不一样」，解释粗筛为什么成立。 */
    static void showGeoHashPrefix(Jedis jedis, List<Hit> nearby, String farId) {
        String[] ids = nearby.stream().map(Hit::id).toArray(String[]::new);
        List<String> hashes = jedis.geohash(GEO_KEY, ids);
        System.out.println("\n【原理】geohash 把经纬度压成一串字符，前缀越像说明离得越近：");
        for (int i = 0; i < ids.length; i++) {
            System.out.println("  附近 " + ids[i] + " -> " + hashes.get(i));
        }
        System.out.println("  很远的 " + farId + " -> " + jedis.geohash(GEO_KEY, farId).get(0));
        System.out.println("  附近这 5 家的公共前缀是「" + commonPrefix(hashes) + "」，"
                + "说明它们落在同一小片格子里；远处那家的前缀一看就不一样。");
        System.out.println("  Redis 只翻这片格子附近的数据，剩下的「谁更近」再按球面距离精算一遍。");
    }

    /** 求一组字符串的公共前缀长度。 */
    static String commonPrefix(List<String> values) {
        String prefix = values.get(0);
        for (String value : values) {
            int i = 0;
            while (i < prefix.length() && i < value.length() && prefix.charAt(i) == value.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, i);
        }
        return prefix;
    }

    /** 球面距离公式（Haversine）：地球是圆的，不能拿平面直角坐标直接算。 */
    static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadiusKm * Math.asin(Math.sqrt(a));
    }
}
