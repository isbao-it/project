package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.servlet.http.Cookie;
import java.security.Key;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 卡06 收藏接口冻结测试（收藏分支）：T06-04..T06-30、T06-49、T06-51 收藏分支、T06-53、T06-55。
 * 分类分支由 CategoryTest 承接，同款分支由 SimilarTest 承接。
 * 只连 db_pg_test_06，夹具全部在本类内自建自清（uid/pid/fid 统一 c06- 前缀），
 * 不继承共享基座、不改 application-test.yml；幂等与唯一键收敛全部走真实 favorite.uk_uid_pid，
 * 并发用例夹具先提交、双线程栅栏同时起跑、结束后按真实行数断言，不使用 Mock 替身代替主业务检查。
 * 本分支无故障注入用例，故不引入任何测试替身，也不对 final 构造器注入字段做反射打桩。
 * 所有断言在实现缺失时因路由或目标行为缺失而红，不使用 @Disabled、不放松期望。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FavoriteTest {

    /** 测试库固定指向 db_pg_test_06，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_06?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 与 application-test.yml 相同的测试签名密钥，仅用于测试内签发过期 JWT。 */
    private static final String TEST_JWT_SECRET =
            "cGhvdG9haS1jYXJkMDEtdGVzdC1zZWNyZXQtb25seS0yMDI2MTAyMA==";

    private static final Logger log = LoggerFactory.getLogger(FavoriteTest.class);

    /** 测试身份：U/O 正常普通用户，A 正常管理员。 */
    private static final String U = "c06-u";
    private static final String O = "c06-o";
    private static final String A = "c06-a";
    /** 展示作品夹具。 */
    private static final String P1 = "c06-p1";
    private static final String P2 = "c06-p2";
    private static final String P3 = "c06-p3";
    private static final String P4 = "c06-p4";
    /** 非展示作品夹具 H1..H6：逐例待审核/隐藏/已驳回。 */
    private static final String H1 = "c06-h1";
    private static final String H2 = "c06-h2";
    private static final String H3 = "c06-h3";
    private static final String H4 = "c06-h4";
    private static final String H5 = "c06-h5";
    private static final String H6 = "c06-h6";
    /** 格式合法但不存在的作品。 */
    private static final String M = "c06-m";
    /** 标准分类夹具 C1。 */
    private static final String C1 = "c06-c1";
    /** 路径 pid 的冻结长度上限，超过即 400。 */
    private static final int PID_MAX_LENGTH = 36;
    /** 冻结分页上限之外的最小 size 样本。 */
    private static final String SIZE_OVER_LIMIT = "51";
    /** INT UNSIGNED 计数样本：超出 Java Integer 正数范围，服务端必须以 Long 输出原值。 */
    private static final long INT_UNSIGNED_SAMPLE = 2147483648L;
    /** 校验新建 favorite 行 ct 时允许的时钟窗口（毫秒）。 */
    private static final long CT_WINDOW_MILLIS = 600000L;
    /** 三收藏夹具的固定收藏时间：最新/并列/较早。 */
    private static final String CT_NEW = "2026-10-23 14:00:03";
    private static final String CT_TIE = "2026-10-23 14:00:02";
    private static final String CT_OLD = "2026-10-23 14:00:01";
    /** T06-49 冻结的固定作品时间及响应格式。 */
    private static final String FIXED_CWTIME = "2026-10-23 10:20:30";
    private static final String FIXED_CWTIME_ISO = "2026-10-23T10:20:30+08:00";
    /** T06-51 读前后逐行一致的快照表集合。 */
    private static final String[] SNAPSHOT_TABLES = {"pwork", "favorite", "pclist", "work_exif"};

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入全部测试依赖。
     * @param mvc 模拟 MVC 客户端
     * @param jdbc 真实 MySQL 访问
     * @param jwtUtils 测试令牌签发
     * @param objectMapper 响应解析
     */
    FavoriteTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入三个测试身份。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_06");
        cleanupFixtures();
        seedUser(U, "c06用户U", "普通用户", "19900000101");
        seedUser(O, "c06用户O", "普通用户", "19900000102");
        seedUser(A, "c06管理员A", "管理员", "19900000103");
        log.info("FavoriteTest 夹具准备完成 uid={}/{}/{}", U, O, A);
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'c06-%' OR uid LIKE 'c06-%' OR targetid LIKE 'c06-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'c06-%' OR uid LIKE 'c06-%' OR pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'c06-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'c06-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（收藏与取消收藏）
    // ------------------------------------------------------------------

    /** T06-04 首次收藏成功：仅新增一行，fid 非空、uid=U、pid=P1、ct 为创建时间，pwork 无收藏计数写入，data={favorited:true,count:1}。 */
    @Test
    void fav_04_postCreatesSingleRowAndExactCount() throws Exception {
        seedWork(P1, O, "展示", null);
        String before = pworkRowSnapshot(P1);
        assertFavoriteResult(postFavoriteAs(U, P1), true, 1L);
        assertThat(favoriteCount(P1)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT fid, ct FROM favorite WHERE uid=? AND pid=?", U, P1);
        String fid = (String) row.get("fid");
        assertThat(fid).isNotNull();
        assertThat(fid.trim()).isNotEmpty();
        Object rawCt = row.get("ct");
        assertThat(rawCt).isNotNull();
        long ctMillis = ctMillis(rawCt);
        long now = System.currentTimeMillis();
        assertThat(ctMillis).isGreaterThan(now - CT_WINDOW_MILLIS);
        assertThat(ctMillis).isLessThan(now + CT_WINDOW_MILLIS);
        assertThat(pworkRowSnapshot(P1)).isEqualTo(before);
    }

    /** T06-05 连续两次收藏均 200 且 count=1，行数仍 1、fid/ct 不变，不 409。 */
    @Test
    void fav_05_repeatPostIdempotentNo409() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        Map<String, Object> before = jdbc.queryForMap("SELECT fid, ct FROM favorite WHERE uid=? AND pid=?", U, P1);
        assertFavoriteResult(postFavoriteAs(U, P1), true, 1L);
        assertFavoriteResult(postFavoriteAs(U, P1), true, 1L);
        assertThat(favoriteCount(P1)).isEqualTo(1);
        Map<String, Object> after = jdbc.queryForMap("SELECT fid, ct FROM favorite WHERE uid=? AND pid=?", U, P1);
        assertThat((String) after.get("fid")).isEqualTo((String) before.get("fid"));
        assertThat(String.valueOf(after.get("ct"))).isEqualTo(String.valueOf(before.get("ct")));
    }

    /** T06-06 第二人收藏同一作品：count 只计 P1 且包含 U/O，不计其它作品的收藏，data={favorited:true,count:2}。 */
    @Test
    void fav_06_secondUserCountIncludesBothOnlyForPid() throws Exception {
        seedWork(P1, O, "展示", null);
        seedWork(P2, U, "展示", null);
        seedWork(P3, A, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        seedFavorite("c06-f2", U, P2, "2026-10-23 13:00:01");
        seedFavorite("c06-f3", O, P3, "2026-10-23 13:00:02");
        assertFavoriteResult(postFavoriteAs(O, P1), true, 2L);
        assertThat(favoriteCount(P1)).isEqualTo(2);
        assertThat(favoriteRowExists(U, P1)).isTrue();
        assertThat(favoriteRowExists(O, P1)).isTrue();
        assertThat(favoriteCount(P2)).isEqualTo(1);
        assertThat(favoriteCount(P3)).isEqualTo(1);
    }

    /** T06-07 取消收藏只删本人一行：O 的行与其它作品收藏保留，data={favorited:false,count:1}。 */
    @Test
    void fav_07_deleteRemovesOnlyOwnRow() throws Exception {
        seedWork(P1, O, "展示", null);
        seedWork(P2, U, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        seedFavorite("c06-f2", O, P1, "2026-10-23 13:00:01");
        seedFavorite("c06-f3", U, P2, "2026-10-23 13:00:02");
        assertFavoriteResult(deleteFavoriteAs(U, P1), false, 1L);
        assertThat(favoriteRowExists(U, P1)).isFalse();
        assertThat(favoriteRowExists(O, P1)).isTrue();
        assertThat(favoriteRowExists(U, P2)).isTrue();
        assertThat(favoriteRows()).isEqualTo(2);
    }

    /** T06-08 连续两次取消均 200 且 count=1：O 的行保留、计数不为负。 */
    @Test
    void fav_08_repeatDeleteKeepsOthersRow() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f2", O, P1, "2026-10-23 13:00:01");
        assertFavoriteResult(deleteFavoriteAs(U, P1), false, 1L);
        assertFavoriteResult(deleteFavoriteAs(U, P1), false, 1L);
        assertThat(favoriteRowExists(O, P1)).isTrue();
        assertThat(favoriteCount(P1)).isEqualTo(1);
    }

    /** T06-09 仅 U 一条时删两次均 200 且 count=0，最终无 U/P1 记录。 */
    @Test
    void fav_09_deleteTwiceToZero() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        assertFavoriteResult(deleteFavoriteAs(U, P1), false, 0L);
        assertFavoriteResult(deleteFavoriteAs(U, P1), false, 0L);
        assertThat(favoriteCount(P1)).isEqualTo(0);
        assertThat(favoriteRowExists(U, P1)).isFalse();
    }

    /** T06-10 两并发收藏：每个响应 favorited=true、count=1，真实唯一键收敛为一行，不冒 409/500。 */
    @Test
    void fav_10_concurrentPostSingleRow() throws Exception {
        seedWork(P1, O, "展示", null);
        String auth = token(U, "普通用户");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发栅栏等待超时");
                }
                return mvc.perform(post("/favorite/" + P1).header("Authorization", auth)).andReturn();
            };
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            log.info("fav_10 并发收藏起跑 pid={}", P1);
            start.countDown();
            assertFavoriteResult(first.get(30, TimeUnit.SECONDS), true, 1L);
            assertFavoriteResult(second.get(30, TimeUnit.SECONDS), true, 1L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(favoriteCount(P1)).isEqualTo(1);
        assertThat(favoriteRowExists(U, P1)).isTrue();
    }

    /** T06-11 两并发取消收藏：每个响应 favorited=false、count=0，只删一次，最终零行。 */
    @Test
    void fav_11_concurrentDeleteZeroRows() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        String auth = token(U, "普通用户");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发栅栏等待超时");
                }
                return mvc.perform(delete("/favorite/" + P1).header("Authorization", auth)).andReturn();
            };
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            log.info("fav_11 并发取消收藏起跑 pid={}", P1);
            start.countDown();
            assertFavoriteResult(first.get(30, TimeUnit.SECONDS), false, 0L);
            assertFavoriteResult(second.get(30, TimeUnit.SECONDS), false, 0L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(favoriteCount(P1)).isEqualTo(0);
        assertThat(favoriteRowExists(U, P1)).isFalse();
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（本人收藏列表）
    // ------------------------------------------------------------------

    /** T06-12 本人收藏分页：按收藏 ct 降序/fid 升序取 [P1,P2]，total=3 不是页长，O 的收藏不混入，作品 uid 是作者不是收藏者。 */
    @Test
    void fav_12_minePageShapeOrderAndAuthorUid() throws Exception {
        seedThreeFavoriteFixture();
        MvcResult result = mvc.perform(get("/favorite/mine").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "2")).andReturn();
        assertMinePage(result, 3L, P1, P2);
    }

    /** T06-13 11 条展示收藏：省略 query 按默认 page=1/size=10 取前 10 份且 total=11；pageNum/pageSize 不能当别名改变默认分页。 */
    @Test
    void fav_13_defaultPagingAndOldParamsIgnored() throws Exception {
        String[] order = seedElevenFavorites();
        String[] firstTen = new String[10];
        System.arraycopy(order, 0, firstTen, 0, 10);
        MvcResult defaults = mvc.perform(get("/favorite/mine")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertMinePage(defaults, 11L, firstTen);
        MvcResult oldParams = mvc.perform(get("/favorite/mine")
                .header("Authorization", token(U, "普通用户"))
                .param("pageNum", "2").param("pageSize", "1")).andReturn();
        assertMinePage(oldParams, 11L, firstTen);
    }

    /** T06-14 超页空页不是 404 且 total 仍为 3；page=2,size=2 精确返回 P3，页间不重复/遗漏。 */
    @Test
    void fav_14_pageBeyondAndSecondPage() throws Exception {
        seedThreeFavoriteFixture();
        assertMinePage(getMine(U, "999", "50"), 3L);
        assertMinePage(getMine(U, "2", "2"), 3L, P3);
    }

    /** T06-15 U 无收藏、O 有收藏时 U 得空页 total=0；管理员 A 也只查本人空页，不因管理员身份读取所有收藏。 */
    @Test
    void fav_15_emptyMineForUAndAdmin() throws Exception {
        seedWork(P1, U, "展示", null);
        seedFavorite("c06-f1", O, P1, "2026-10-23 13:00:00");
        assertMinePage(getMine(U, "1", "50"), 0L);
        assertMinePage(getMine(A, "1", "50"), 0L);
    }

    /** T06-16 list/total 同一过滤：非展示收藏不进列表也不进 total；非展示关系不删除；作者 U 与管理员 A 各执行。 */
    @Test
    void fav_16_visibilityFilterListAndTotalAuthorAndAdmin() throws Exception {
        seedWork(P1, U, "展示", null);
        seedWork(H1, U, "待审核", null);
        seedWork(H2, U, "隐藏", null);
        seedWork(H3, U, "已驳回", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:01");
        seedFavorite("c06-f2", U, H1, "2026-10-23 13:00:02");
        seedFavorite("c06-f3", U, H2, "2026-10-23 13:00:03");
        seedFavorite("c06-f4", U, H3, "2026-10-23 13:00:04");
        assertMinePage(getMine(U, "1", "50"), 1L, P1);
        assertThat(favoriteRowsOf(U)).isEqualTo(4);
        seedWork(P2, U, "展示", null);
        seedWork(H4, U, "待审核", null);
        seedWork(H5, U, "隐藏", null);
        seedWork(H6, U, "已驳回", null);
        seedFavorite("c06-f5", A, P2, "2026-10-23 13:00:01");
        seedFavorite("c06-f6", A, H4, "2026-10-23 13:00:02");
        seedFavorite("c06-f7", A, H5, "2026-10-23 13:00:03");
        seedFavorite("c06-f8", A, H6, "2026-10-23 13:00:04");
        assertMinePage(getMine(A, "1", "50"), 1L, P2);
        assertThat(favoriteRowsOf(A)).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（可见性与资源缺失）
    // ------------------------------------------------------------------

    /** T06-17 作者/管理员收藏非展示作品逐状态 404：无收藏写入；已有收藏的重复 POST 也逐状态 404 且关系原样保留。 */
    @Test
    void fav_17_postHiddenIs404AuthorAndAdmin() throws Exception {
        String[] hiddenPids = new String[]{H1, H2, H3};
        String[] statuses = new String[]{"待审核", "隐藏", "已驳回"};
        for (int i = 0; i < hiddenPids.length; i++) {
            String pid = hiddenPids[i];
            seedWork(pid, U, statuses[i], null);
            log.info("fav_17 无收藏样本 pid={} status={}", pid, statuses[i]);
            assertError(postFavoriteAs(U, pid), 404, "not_found");
            assertError(postFavoriteAs(A, pid), 404, "not_found");
            assertThat(favoriteCount(pid)).isEqualTo(0);
            seedFavorite("c06-fu-" + pid, U, pid, "2026-10-23 13:00:00");
            seedFavorite("c06-fa-" + pid, A, pid, "2026-10-23 13:00:01");
            Map<String, Object> uBefore = favoriteRow(U, pid);
            Map<String, Object> aBefore = favoriteRow(A, pid);
            log.info("fav_17 已有收藏重复样本 pid={}", pid);
            assertError(postFavoriteAs(U, pid), 404, "not_found");
            assertError(postFavoriteAs(A, pid), 404, "not_found");
            assertThat(favoriteCount(pid)).isEqualTo(2);
            assertThat(String.valueOf(favoriteRow(U, pid).get("fid"))).isEqualTo(String.valueOf(uBefore.get("fid")));
            assertThat(String.valueOf(favoriteRow(U, pid).get("ct"))).isEqualTo(String.valueOf(uBefore.get("ct")));
            assertThat(String.valueOf(favoriteRow(A, pid).get("fid"))).isEqualTo(String.valueOf(aBefore.get("fid")));
            assertThat(String.valueOf(favoriteRow(A, pid).get("ct"))).isEqualTo(String.valueOf(aBefore.get("ct")));
        }
    }

    /** T06-18 作者/管理员取消收藏非展示作品逐状态 404：已收藏/未收藏均执行；已有关系保留，不能先删再返回 404。 */
    @Test
    void fav_18_deleteHiddenIs404KeepsRelation() throws Exception {
        String[] hiddenPids = new String[]{H1, H2, H3};
        String[] statuses = new String[]{"待审核", "隐藏", "已驳回"};
        for (int i = 0; i < hiddenPids.length; i++) {
            String pid = hiddenPids[i];
            seedWork(pid, U, statuses[i], null);
            log.info("fav_18 未收藏样本 pid={} status={}", pid, statuses[i]);
            assertError(deleteFavoriteAs(U, pid), 404, "not_found");
            assertError(deleteFavoriteAs(A, pid), 404, "not_found");
            assertThat(favoriteCount(pid)).isEqualTo(0);
            seedFavorite("c06-fu-" + pid, U, pid, "2026-10-23 13:00:00");
            seedFavorite("c06-fa-" + pid, A, pid, "2026-10-23 13:00:01");
            Map<String, Object> uBefore = favoriteRow(U, pid);
            Map<String, Object> aBefore = favoriteRow(A, pid);
            log.info("fav_18 已收藏样本 pid={}", pid);
            assertError(deleteFavoriteAs(U, pid), 404, "not_found");
            assertError(deleteFavoriteAs(A, pid), 404, "not_found");
            assertThat(favoriteCount(pid)).isEqualTo(2);
            assertThat(String.valueOf(favoriteRow(U, pid).get("fid"))).isEqualTo(String.valueOf(uBefore.get("fid")));
            assertThat(String.valueOf(favoriteRow(U, pid).get("ct"))).isEqualTo(String.valueOf(uBefore.get("ct")));
            assertThat(String.valueOf(favoriteRow(A, pid).get("fid"))).isEqualTo(String.valueOf(aBefore.get("fid")));
            assertThat(String.valueOf(favoriteRow(A, pid).get("ct"))).isEqualTo(String.valueOf(aBefore.get("ct")));
        }
    }

    /** T06-19 收藏格式合法但不存在的作品 404 not_found，非格式 400，不写孤儿收藏。 */
    @Test
    void fav_19_postMissingWorkIs404() throws Exception {
        seedWork(P1, O, "展示", null);
        assertError(postFavoriteAs(U, M), 404, "not_found");
        assertThat(favoriteCount(M)).isEqualTo(0);
        assertThat(favoriteRows()).isEqualTo(0);
    }

    /** T06-20 取消收藏格式合法但不存在的作品 404 not_found，资源不存在优先于未收藏的幂等成功。 */
    @Test
    void fav_20_deleteMissingWorkIs404() throws Exception {
        seedWork(P1, O, "展示", null);
        assertError(deleteFavoriteAs(U, M), 404, "not_found");
        assertThat(favoriteCount(M)).isEqualTo(0);
        assertThat(favoriteRows()).isEqualTo(0);
    }

    /** T06-21 收藏非法 pid：URL 编码的 bad! 与 37 位逐例 400 invalid_request，不得截断 ID，记录不变。 */
    @Test
    void fav_21_postBadPidFormatsAre400() throws Exception {
        seedWork(P1, O, "展示", null);
        assertError(mvc.perform(post("/favorite/bad%21")
                .header("Authorization", token(U, "普通用户"))).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(post("/favorite/" + repeat("a", PID_MAX_LENGTH + 1))
                .header("Authorization", token(U, "普通用户"))).andReturn(), 400, "invalid_request");
        assertThat(favoriteRows()).isEqualTo(0);
    }

    /** T06-22 取消收藏非法 pid：URL 编码的 bad! 与 37 位逐例 400 invalid_request，记录不变。 */
    @Test
    void fav_22_deleteBadPidFormatsAre400() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        assertError(mvc.perform(delete("/favorite/bad%21")
                .header("Authorization", token(U, "普通用户"))).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(delete("/favorite/" + repeat("a", PID_MAX_LENGTH + 1))
                .header("Authorization", token(U, "普通用户"))).andReturn(), 400, "invalid_request");
        assertThat(favoriteRowExists(U, P1)).isTrue();
        assertThat(favoriteRows()).isEqualTo(1);
    }

    /** T06-23 本人收藏列表 page/size 越界或非整数逐例 400 invalid_request，data=null，每个样本独立不默认修正，记录不变。 */
    @Test
    void fav_23_invalidPagingIs400() throws Exception {
        seedThreeFavoriteFixture();
        String[] badPages = new String[]{"0", "-1", "1.5", "x"};
        String[] badSizes = new String[]{"0", "-1", SIZE_OVER_LIMIT, "1.5", "x"};
        for (String page : badPages) {
            log.info("fav_23 非法 page 样本 page={}", page);
            assertError(mvc.perform(get("/favorite/mine").header("Authorization", token(U, "普通用户"))
                    .param("page", page)).andReturn(), 400, "invalid_request");
        }
        for (String size : badSizes) {
            log.info("fav_23 非法 size 样本 size={}", size);
            assertError(mvc.perform(get("/favorite/mine").header("Authorization", token(U, "普通用户"))
                    .param("size", size)).andReturn(), 400, "invalid_request");
        }
        assertThat(favoriteRows()).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（凭证与角色）
    // ------------------------------------------------------------------

    /** T06-24 匿名收藏 401 unauthorized，data=null，不写库。 */
    @Test
    void fav_24_anonymousPostIs401NoWrite() throws Exception {
        seedWork(P1, O, "展示", null);
        assertError(mvc.perform(post("/favorite/" + P1)).andReturn(), 401, "unauthorized");
        assertThat(favoriteRows()).isEqualTo(0);
    }

    /** T06-25 匿名取消收藏 401 unauthorized，data=null，不删记录。 */
    @Test
    void fav_25_anonymousDeleteIs401NoDelete() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", O, P1, "2026-10-23 13:00:00");
        assertError(mvc.perform(delete("/favorite/" + P1)).andReturn(), 401, "unauthorized");
        assertThat(favoriteRowExists(O, P1)).isTrue();
        assertThat(favoriteRows()).isEqualTo(1);
    }

    /** T06-26 匿名读本人收藏 401 unauthorized，不是公开空列表。 */
    @Test
    void fav_26_anonymousMineIs401() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        assertError(mvc.perform(get("/favorite/mine")).andReturn(), 401, "unauthorized");
        assertThat(favoriteRows()).isEqualTo(1);
    }

    /** T06-27 无 Authorization 时使用 token Cookie 的 U 有效 JWT：收藏成功，收藏 uid 为 U。 */
    @Test
    void fav_27_cookieTokenWorks() throws Exception {
        seedWork(P1, O, "展示", null);
        String raw = jwtUtils.generateToken(U, "普通用户");
        assertFavoriteResult(mvc.perform(post("/favorite/" + P1)
                .cookie(new Cookie("token", raw))).andReturn(), true, 1L);
        assertThat(favoriteRowExists(U, P1)).isTrue();
        assertThat(favoriteCount(P1)).isEqualTo(1);
    }

    /** T06-28 坏 Authorization 加同一有效 Cookie 不回落 Cookie：Bearer 坏签名/过期逐例 401，记录不变。 */
    @Test
    void fav_28_badHeaderNoCookieFallback() throws Exception {
        seedWork(P1, O, "展示", null);
        Cookie tokenCookie = new Cookie("token", jwtUtils.generateToken(U, "普通用户"));
        assertError(mvc.perform(post("/favorite/" + P1)
                .header("Authorization", "Bearer " + corrupt(jwtUtils.generateToken(U, "普通用户")))
                .cookie(tokenCookie)).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(post("/favorite/" + P1)
                .header("Authorization", "Bearer " + expiredToken(U, "普通用户"))
                .cookie(tokenCookie)).andReturn(), 401, "unauthorized");
        assertThat(favoriteRows()).isEqualTo(0);
    }

    /** T06-29 签发后主体被禁用、被删除以及坏签名、过期 JWT 逐例 401 unauthorized，每次检查主体状态，关系不变。 */
    @Test
    void fav_29_disabledDeletedBadTokenSubjects401() throws Exception {
        seedWork(P1, O, "展示", null);
        seedFavorite("c06-f1", O, P1, "2026-10-23 13:00:00");
        String good = token(U, "普通用户");
        String badSignature = "Bearer " + corrupt(jwtUtils.generateToken(U, "普通用户"));
        String expired = "Bearer " + expiredToken(U, "普通用户");
        assertMinePage(mvc.perform(get("/favorite/mine")
                .header("Authorization", good)).andReturn(), 0L);
        log.info("fav_29 坏签名/过期样本");
        assertError(mvc.perform(get("/favorite/mine")
                .header("Authorization", badSignature)).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(get("/favorite/mine")
                .header("Authorization", expired)).andReturn(), 401, "unauthorized");
        jdbc.update("UPDATE user SET status='禁用' WHERE uid=?", U);
        log.info("fav_29 主体被禁用样本 uid={}", U);
        assertError(mvc.perform(get("/favorite/mine")
                .header("Authorization", good)).andReturn(), 401, "unauthorized");
        jdbc.update("DELETE FROM user WHERE uid=?", U);
        log.info("fav_29 主体被删除样本 uid={}", U);
        assertError(mvc.perform(get("/favorite/mine")
                .header("Authorization", good)).andReturn(), 401, "unauthorized");
        assertThat(favoriteRows()).isEqualTo(1);
        assertThat(favoriteRowExists(O, P1)).isTrue();
    }

    /** T06-30 真实签名但角色为空白、未知角色、首尾空格的普通用户逐例 403 forbidden，不 trim 角色。 */
    @Test
    void fav_30_badRolesAre403() throws Exception {
        seedWork(P1, O, "展示", null);
        for (String role : new String[]{"", "访客", " 普通用户 "}) {
            log.info("fav_30 非白名单角色样本 role=[{}]", role);
            assertError(mvc.perform(get("/favorite/mine")
                    .header("Authorization", token(U, role))).andReturn(), 403, "forbidden");
        }
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（边界与只读）
    // ------------------------------------------------------------------

    /** T06-49 三个计数各 2147483648 以 Long 原样输出、NULL 键保留、realLikeCount 只计真实帖子赞、cwtime 精确 +08:00、不输出 favorite fid/ct。 */
    @Test
    void fav_49_longCountersNullsAndRealLikes() throws Exception {
        seedWork(P1, null, "c06夹具标题", "c06夹具描述", "/static/images/pwork/c06-p1.jpg",
                INT_UNSIGNED_SAMPLE, INT_UNSIGNED_SAMPLE, "展示", FIXED_CWTIME, INT_UNSIGNED_SAMPLE, null);
        seedWork(P2, O, "展示", null);
        seedFavorite("c06-f1", U, P1, "2026-10-23 13:00:00");
        seedLike("c06-l1", U, P1, "帖子");
        seedLike("c06-l2", O, P1, "帖子");
        seedLike("c06-l3", O, P1, "评论");
        seedLike("c06-l4", U, P2, "帖子");
        MvcResult result = mvc.perform(get("/favorite/mine").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isEqualTo(1L);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        JsonNode item = list.get(0);
        assertKeys(item, "pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
                "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
        assertThat(item.get("pid").asText()).isEqualTo(P1);
        assertThat(item.get("uid").isNull()).isTrue();
        assertThat(item.get("wtitle").asText()).isEqualTo("c06夹具标题");
        assertThat(item.get("wdesc").asText()).isEqualTo("c06夹具描述");
        assertThat(item.get("vurl").asText()).isEqualTo("/static/images/pwork/c06-p1.jpg");
        assertCount(item.get("viewcount"), INT_UNSIGNED_SAMPLE);
        assertCount(item.get("votecount"), INT_UNSIGNED_SAMPLE);
        assertCount(item.get("likep"), INT_UNSIGNED_SAMPLE);
        assertThat(item.get("wstatus").asText()).isEqualTo("展示");
        assertThat(item.get("cwtime").asText()).isEqualTo(FIXED_CWTIME_ISO);
        assertThat(item.get("uname").isNull()).isTrue();
        assertThat(item.get("avatar").isNull()).isTrue();
        assertThat(item.get("pcname").isNull()).isTrue();
        assertCount(item.get("realLikeCount"), 2L);
    }

    /** T06-51 收藏分支：标准夹具下读本人收藏前后 pwork/favorite/pclist/work_exif 快照逐行相同，不触发浏览+1，total=本人展示收藏总数。 */
    @Test
    void fav_51_readOnlySnapshot() throws Exception {
        seedReadOnlyFixture();
        assertThat(favoriteRowsOf(U)).isEqualTo(4);
        Map<String, List<String>> before = snapshotTables();
        MvcResult result = mvc.perform(get("/favorite/mine").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn();
        assertMinePage(result, 3L, P1, P2, P3);
        Map<String, List<String>> after = snapshotTables();
        for (String table : SNAPSHOT_TABLES) {
            assertThat(after.get(table)).as("表 %s 读前后逐行一致", table).isEqualTo(before.get(table));
        }
        assertThat(favoriteRowsOf(U)).isEqualTo(4);
    }

    /** T06-53 PUT /favorite/P1 为 405 invalid_request，data=null，不误入 POST/DELETE、不写库。 */
    @Test
    void fav_53_putIs405NoWrite() throws Exception {
        seedWork(P1, O, "展示", null);
        assertError(mvc.perform(put("/favorite/" + P1)
                .header("Authorization", token(U, "普通用户"))).andReturn(), 405, "invalid_request");
        assertThat(favoriteRows()).isEqualTo(0);
    }

    /** T06-55 size 下限 1 与上限 50 都合法：两个样本 list 长度分别 1/3、total 均 3，值依 ct/fid 排序，不发明 pageNum/pageSize 的 HTTP 拒绝。 */
    @Test
    void fav_55_sizeOneAndFifty() throws Exception {
        seedThreeFavoriteFixture();
        assertMinePage(getMine(U, "1", "1"), 3L, P1);
        assertMinePage(getMine(U, "1", "50"), 3L, P1, P2, P3);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private void seedUser(String uid, String nickname, String role, String pnum) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status) VALUES (?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "c06-test-password", "/avatar/" + uid + ".png", "0", "正常");
    }

    private void seedWork(String pid, String authorUid, String status, String pcid) {
        seedWork(pid, authorUid, "c06标题" + pid, "c06描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                1, 1, status, "2026-10-23 09:00:00", 1, pcid);
    }

    private void seedWork(String pid, String authorUid, String title, String desc, String vurl,
                          long viewcount, long votecount, String status, String cwtime, long likep, String pcid) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, cwtime, likep, pcid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                pid, authorUid, title, desc, vurl, viewcount, votecount, status, cwtime, likep, pcid);
    }

    private void seedFavorite(String fid, String uid, String pid, String ct) {
        jdbc.update("INSERT IGNORE INTO favorite (fid, uid, pid, ct) VALUES (?,?,?,?)", fid, uid, pid, ct);
    }

    private void seedLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT IGNORE INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void seedExif(String pid, int focal, String aperture) {
        jdbc.update("INSERT INTO work_exif (pid, focal, aperture, stripped) VALUES (?,?,?,?)",
                pid, focal, aperture, 0);
    }

    private void seedCategory(String pcid, String pcname, int sort) {
        jdbc.update("INSERT INTO pclist (pcid, pcname, cds, sort) VALUES (?,?,?,?)",
                pcid, pcname, pcname + "测试分类说明", sort);
    }

    /** 三收藏夹具：U 收藏 P1/P2/P3，收藏 ct 为 最新/并列/并列 且 fid 使 P2 先于 P3，O 另有 P4 收藏；真实帖子赞与评论赞并存。 */
    private void seedThreeFavoriteFixture() {
        seedCategory(C1, "人像", 1);
        seedWork(P1, A, "c06标题" + P1, "c06描述" + P1, "/static/images/pwork/" + P1 + ".jpg",
                11, 2, "展示", "2026-10-23 09:00:03", 3, C1);
        seedWork(P2, O, "c06标题" + P2, "c06描述" + P2, "/static/images/pwork/" + P2 + ".jpg",
                12, 3, "展示", "2026-10-23 09:00:02", 5, null);
        seedWork(P3, A, "c06标题" + P3, "c06描述" + P3, "/static/images/pwork/" + P3 + ".jpg",
                13, 4, "展示", "2026-10-23 09:00:01", 7, C1);
        seedWork(P4, U, "c06标题" + P4, "c06描述" + P4, "/static/images/pwork/" + P4 + ".jpg",
                14, 5, "展示", "2026-10-23 09:00:04", 1, null);
        seedFavorite("c06-f1", U, P1, CT_NEW);
        seedFavorite("c06-f2", U, P2, CT_TIE);
        seedFavorite("c06-f3", U, P3, CT_TIE);
        seedFavorite("c06-f4", O, P4, CT_OLD);
        seedLike("c06-l1", U, P1, "帖子");
        seedLike("c06-l2", O, P1, "帖子");
        seedLike("c06-l3", O, P1, "评论");
        seedLike("c06-l4", U, P2, "帖子");
    }

    /** T06-51 标准有效夹具：U 收藏三条展示与一条隐藏，O 另有收藏；含分类、EXIF 与真实点赞行。 */
    private void seedReadOnlyFixture() {
        seedCategory(C1, "人像", 1);
        seedWork(P1, O, "c06标题" + P1, "c06描述" + P1, "/static/images/pwork/" + P1 + ".jpg",
                101, 11, "展示", "2026-10-23 09:00:03", 5, C1);
        seedWork(P2, U, "c06标题" + P2, "c06描述" + P2, "/static/images/pwork/" + P2 + ".jpg",
                102, 12, "展示", "2026-10-23 09:00:02", 7, null);
        seedWork(P3, A, "c06标题" + P3, "c06描述" + P3, "/static/images/pwork/" + P3 + ".jpg",
                103, 13, "展示", "2026-10-23 09:00:01", 9, C1);
        seedWork(P4, U, "c06标题" + P4, "c06描述" + P4, "/static/images/pwork/" + P4 + ".jpg",
                104, 14, "展示", "2026-10-23 09:00:04", 1, null);
        seedWork(H1, U, "c06标题" + H1, "c06描述" + H1, "/static/images/pwork/" + H1 + ".jpg",
                1, 1, "隐藏", "2026-10-23 09:00:00", 0, null);
        seedExif(P1, 50, "4.0");
        seedFavorite("c06-f1", U, P1, CT_NEW);
        seedFavorite("c06-f2", U, P2, CT_TIE);
        seedFavorite("c06-f3", U, P3, CT_TIE);
        seedFavorite("c06-f4", U, H1, CT_OLD);
        seedFavorite("c06-f5", O, P4, CT_NEW);
        seedLike("c06-l1", U, P1, "帖子");
        seedLike("c06-l2", O, P1, "帖子");
        seedLike("c06-l3", O, P1, "评论");
        seedLike("c06-l4", U, P3, "帖子");
    }

    /** 造 11 条 U 的展示收藏，收藏 ct 依次递增；返回冻结排序（最新在前）的 pid 数组。 */
    private String[] seedElevenFavorites() {
        String[] order = new String[11];
        for (int i = 1; i <= 11; i++) {
            String pid = String.format("c06-w%02d", i);
            seedWork(pid, O, "c06标题" + pid, "c06描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                    i, i, "展示", String.format("2026-10-23 12:00:%02d", i), i, null);
            seedFavorite(String.format("c06-f%02d", i), U, pid, String.format("2026-10-23 13:00:%02d", i));
            order[11 - i] = pid;
        }
        return order;
    }

    private MvcResult postFavoriteAs(String actor, String pid) throws Exception {
        return mvc.perform(post("/favorite/" + pid)
                .header("Authorization", token(actor, roleOf(actor)))).andReturn();
    }

    private MvcResult deleteFavoriteAs(String actor, String pid) throws Exception {
        return mvc.perform(delete("/favorite/" + pid)
                .header("Authorization", token(actor, roleOf(actor)))).andReturn();
    }

    private MvcResult getMine(String actor, String page, String size) throws Exception {
        return mvc.perform(get("/favorite/mine").header("Authorization", token(actor, roleOf(actor)))
                .param("page", page).param("size", size)).andReturn();
    }

    private String roleOf(String uid) {
        return A.equals(uid) ? "管理员" : "普通用户";
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    private String expiredToken(String uid, String role) {
        Date now = new Date();
        Key key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_JWT_SECRET));
        return Jwts.builder()
                .setSubject(uid)
                .claim("role", role)
                .setIssuedAt(new Date(now.getTime() - 7200000L))
                .setExpiration(new Date(now.getTime() - 3600000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    private String corrupt(String rawToken) {
        char last = rawToken.charAt(rawToken.length() - 1);
        char flipped = last == 'a' ? 'b' : 'a';
        return rawToken.substring(0, rawToken.length() - 1) + flipped;
    }

    private String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private int favoriteCount(String pid) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE pid=? AND fid LIKE 'c06-%'", Integer.class, pid);
        return rows == null ? 0 : rows;
    }

    private int favoriteRows() {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE fid LIKE 'c06-%' OR uid LIKE 'c06-%' OR pid LIKE 'c06-%'",
                Integer.class);
        return rows == null ? 0 : rows;
    }

    private int favoriteRowsOf(String uid) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE uid=? AND fid LIKE 'c06-%'", Integer.class, uid);
        return rows == null ? 0 : rows;
    }

    private boolean favoriteRowExists(String uid, String pid) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE uid=? AND pid=? AND fid LIKE 'c06-%'",
                Integer.class, uid, pid);
        return rows != null && rows > 0;
    }

    private Map<String, Object> favoriteRow(String uid, String pid) {
        return jdbc.queryForMap("SELECT fid, ct FROM favorite WHERE uid=? AND pid=?", uid, pid);
    }

    /**
     * JDBC 4.2 的 DATETIME 可能返回 LocalDateTime，也可能返回 Timestamp，统一取毫秒比较，不做强转。
     * @param rawCt favorite.ct 原始值
     * @return 该时间的毫秒数
     */
    private long ctMillis(Object rawCt) {
        if (rawCt instanceof Timestamp) {
            return ((Timestamp) rawCt).getTime();
        }
        if (rawCt instanceof LocalDateTime) {
            return ((LocalDateTime) rawCt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        throw new AssertionError("favorite.ct 类型未预期: " + rawCt.getClass());
    }

    /** 单行 pwork 快照：全部列规范化为可比较字符串，时间列兼容 Timestamp/LocalDateTime 再比较，不做强转。 */
    private String pworkRowSnapshot(String pid) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM pwork WHERE pid=?", pid);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            parts.add(entry.getKey() + "=" + String.valueOf(normalizeDbValue(entry.getValue())));
        }
        Collections.sort(parts);
        return String.join("|", parts);
    }

    /** 逐表读出全部行并规范化为可排序字符串行集合；时间列兼容 Timestamp/LocalDateTime 再比较，不做强转。 */
    private Map<String, List<String>> snapshotTables() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `" + table + "`");
            List<String> canonicalRows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    parts.add(entry.getKey() + "=" + String.valueOf(normalizeDbValue(entry.getValue())));
                }
                Collections.sort(parts);
                canonicalRows.add(String.join("|", parts));
            }
            Collections.sort(canonicalRows);
            snapshot.put(table, canonicalRows);
        }
        return snapshot;
    }

    private Object normalizeDbValue(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        if (value instanceof LocalDateTime) {
            return value;
        }
        if (value instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
        return value;
    }

    private void assertFavoriteResult(MvcResult result, boolean favorited, long count) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        JsonNode data = json.get("data");
        assertKeys(data, "favorited", "count");
        assertThat(data.get("favorited").asBoolean()).isEqualTo(favorited);
        assertThat(data.get("count").isIntegralNumber()).isTrue();
        assertThat(data.get("count").asLong()).isEqualTo(count);
    }

    /** 断言分页信封与逐项十四键 W 投影：total 与 list 长度按冻结值，值逐项与库内夹具比对。 */
    private void assertMinePage(MvcResult result, long expectedTotal, String... expectedPids) throws Exception {
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isEqualTo(expectedTotal);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(expectedPids.length);
        for (int i = 0; i < expectedPids.length; i++) {
            JsonNode item = list.get(i);
            assertThat(item.get("pid").asText()).isEqualTo(expectedPids[i]);
            assertWork(item);
        }
    }

    private void assertWork(JsonNode item) {
        assertKeys(item, "pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
                "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
        String pid = item.get("pid").asText();
        Map<String, Object> db = jdbc.queryForMap(
                "SELECT p.pid, p.uid, p.wtitle, p.wdesc, p.vurl, "
                        + "COALESCE(p.viewcount,0) AS viewcount, COALESCE(p.votecount,0) AS votecount, p.wstatus, "
                        + "DATE_FORMAT(p.cwtime,'%Y-%m-%dT%H:%i:%s+08:00') AS cwtime, COALESCE(p.likep,0) AS likep, "
                        + "u.uname, u.avatar, c.pcname, "
                        + "(SELECT COUNT(*) FROM liketable l WHERE l.targetid=p.pid AND l.targettype='帖子') AS realLikeCount "
                        + "FROM pwork p LEFT JOIN user u ON u.uid=p.uid LEFT JOIN pclist c ON c.pcid=p.pcid "
                        + "WHERE p.pid=?", pid);
        assertThat(item.get("pid").asText()).isEqualTo((String) db.get("pid"));
        assertNullableText(item.get("uid"), (String) db.get("uid"));
        assertThat(item.get("wtitle").asText()).isEqualTo((String) db.get("wtitle"));
        assertThat(item.get("wdesc").asText()).isEqualTo((String) db.get("wdesc"));
        assertThat(item.get("vurl").asText()).isEqualTo((String) db.get("vurl"));
        assertCount(item.get("viewcount"), (Number) db.get("viewcount"));
        assertCount(item.get("votecount"), (Number) db.get("votecount"));
        assertThat(item.get("wstatus").asText()).isEqualTo((String) db.get("wstatus"));
        assertThat(item.get("cwtime").asText()).isEqualTo((String) db.get("cwtime"));
        assertThat(item.get("cwtime").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+08:00");
        assertCount(item.get("likep"), (Number) db.get("likep"));
        assertNullableText(item.get("uname"), (String) db.get("uname"));
        assertNullableText(item.get("avatar"), (String) db.get("avatar"));
        assertNullableText(item.get("pcname"), (String) db.get("pcname"));
        assertCount(item.get("realLikeCount"), (Number) db.get("realLikeCount"));
    }

    private void assertCount(JsonNode node, Number expected) {
        assertThat(node.isIntegralNumber()).isTrue();
        assertThat(node.asLong()).isEqualTo(expected.longValue());
    }

    private void assertNullableText(JsonNode node, String expected) {
        if (expected == null) {
            assertThat(node.isNull()).isTrue();
        } else {
            assertThat(node.asText()).isEqualTo(expected);
        }
    }

    private JsonNode assertOk(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        assertThat(json.get("data").isNull()).isFalse();
        return json.get("data");
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("msg").asText()).isNotEmpty();
        assertThat(json.get("data").isNull()).isTrue();
    }

    private JsonNode parseBody(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentType()).isNotNull();
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("openid", "session_key", "AppSecret", "appsecret",
                "pwd", "password", "Bearer ", "Exception", "\tat ");
        return objectMapper.readTree(body);
    }

    private void assertTopLevelKeys(JsonNode json) {
        Set<String> keys = new HashSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("code", "msg", "data");
    }

    private void assertKeys(JsonNode node, String... expected) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder(expected);
    }
}
