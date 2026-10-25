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
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
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
 * 卡07 关注接口冻结测试（关系分支）：T07-01..T07-15、T07-32、T07-33，
 * 及 T07-16、T07-23..T07-28、T07-30、T07-31 中 /follow/following、/follow/followers
 * 两个关系列表入口的分支。流入口 /pwork/following 的分支由 FeedTest 承接。
 * 只连 db_pg_test_07，夹具全部在本类内自建自清（uid/fid 统一 f07- 前缀），
 * 不继承共享基座、不改 application-test.yml；并发用例走真实 uk_uid_target 唯一键，
 * 不使用 Mock 替身代替幂等与分页。所有断言在实现缺失时因路由/行为缺失而红，
 * 不使用 @Disabled、不放松期望。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FollowTest {

    /** 测试库固定指向 db_pg_test_07，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_07?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 与 application-test.yml 相同的测试签名密钥，仅用于测试内签发过期 JWT。 */
    private static final String TEST_JWT_SECRET =
            "cGhvdG9haS1jYXJkMDEtdGVzdC1zZWNyZXQtb25seS0yMDI2MTAyMA==";

    private static final String U = "f07-u";
    private static final String V = "f07-v";
    private static final String O = "f07-o";
    private static final String A = "f07-a";
    /** 格式合法但不存在的目标用户。 */
    private static final String M = "f07-m";

    /** 路径 uid 的冻结长度上限，超过即 400。 */
    private static final int UID_MAX_LENGTH = 64;
    /** 超出 int 范围的分页样本，冻结为 400。 */
    private static final String PAGE_OVERFLOW = "2147483648";
    /** 冻结分页上限之外的最小 size 样本。 */
    private static final String SIZE_OVER_LIMIT = "51";
    /** 校验创建的 follow 行 ct 时允许的时钟窗口（毫秒）。 */
    private static final long CT_WINDOW_MILLIS = 600000L;

    /** 关系夹具 R 的固定 fid 与时间，保证本人关注/粉丝次序均为 V/O/A。 */
    private static final String R_UV = "f07-r1";
    private static final String R_UO = "f07-r2";
    private static final String R_UA = "f07-r3";
    private static final String R_VU = "f07-r4";
    private static final String R_OU = "f07-r5";
    private static final String R_AU = "f07-r6";
    private static final String CT_NEW = "2026-07-07 12:00:03";
    private static final String CT_TIE = "2026-07-07 12:00:02";

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
    FollowTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入四个测试身份。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_07");
        cleanupFixtures();
        seedUser(U, "f07用户U", "普通用户", "19900000101", "f07-bio-u");
        seedUser(V, "f07用户V", "普通用户", "19900000102", "f07-bio-v");
        seedUser(O, "f07用户O", "普通用户", "19900000103", "f07-bio-o");
        seedUser(A, "f07管理员A", "管理员", "19900000104", "f07-bio-a");
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM follow WHERE fid LIKE 'f07-%' OR uid LIKE 'f07-%' OR tuid LIKE 'f07-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'f07-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（关注与取关）
    // ------------------------------------------------------------------

    /** T07-01 从零关注成功：只新增一行，fid 非空、uid=U、tuid=V、ct 为创建时间，响应 following=true、followers=1。 */
    @Test
    void flw_01_postCreatesSingleRow() throws Exception {
        MvcResult result = postFollowAs(U, V);
        assertFollowResult(result, true, 1L);
        assertThat(followRows()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT fid, ct FROM follow WHERE uid=? AND tuid=?", U, V);
        String fid = (String) row.get("fid");
        assertThat(fid).isNotNull();
        assertThat(fid.trim()).isNotEmpty();
        // JDBC 4.2 的 DATETIME 可能返回 LocalDateTime，也可能返回 Timestamp，这里统一取毫秒
        Object rawCt = row.get("ct");
        assertThat(rawCt).isNotNull();
        long ctMillis;
        if (rawCt instanceof java.sql.Timestamp) {
            ctMillis = ((java.sql.Timestamp) rawCt).getTime();
        } else if (rawCt instanceof java.time.LocalDateTime) {
            ctMillis = ((java.time.LocalDateTime) rawCt).atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } else {
            throw new AssertionError("follow.ct 类型未预期: " + rawCt.getClass());
        }
        long now = System.currentTimeMillis();
        assertThat(ctMillis).isGreaterThan(now - CT_WINDOW_MILLIS);
        assertThat(ctMillis).isLessThan(now + CT_WINDOW_MILLIS);
    }

    /** T07-02 连续两次关注：每次 200 且 following=true、followers=1，行数仍 1、fid/ct 不变、不 409。 */
    @Test
    void flw_02_repeatPostIdempotent() throws Exception {
        seedFollow("f07-x1", U, V, "2026-07-07 11:00:00");
        Map<String, Object> before = jdbc.queryForMap("SELECT fid, ct FROM follow WHERE uid=? AND tuid=?", U, V);
        assertFollowResult(postFollowAs(U, V), true, 1L);
        assertFollowResult(postFollowAs(U, V), true, 1L);
        assertThat(followRows()).isEqualTo(1);
        Map<String, Object> after = jdbc.queryForMap("SELECT fid, ct FROM follow WHERE uid=? AND tuid=?", U, V);
        assertThat((String) after.get("fid")).isEqualTo((String) before.get("fid"));
        // JDBC 4.2 返回 LocalDateTime，不能强转 Timestamp；统一转 String 比较时间值
        assertThat(String.valueOf(after.get("ct"))).isEqualTo(String.valueOf(before.get("ct")));
    }

    /** T07-03 第二人关注同一目标：followers 只计 V 的全部粉丝（2），不是操作者 O 的关注数。 */
    @Test
    void flw_03_followersCountOnlyTargetFans() throws Exception {
        seedFollow(R_UV, U, V, CT_NEW);
        seedUser("f07-x", "f07用户X", "普通用户", "19900000105", "f07-bio-x");
        seedUser("f07-y", "f07用户Y", "普通用户", "19900000106", "f07-bio-y");
        seedUser("f07-x2", "f07用户X2", "普通用户", "19900000107", "f07-bio-x2");
        seedUser("f07-y2", "f07用户Y2", "普通用户", "19900000108", "f07-bio-y2");
        seedFollow("f07-xr", "f07-x", "f07-y", "2026-07-07 12:00:00");
        seedFollow("f07-x2r", "f07-x2", "f07-y2", "2026-07-07 12:00:00");
        assertFollowResult(postFollowAs(O, V), true, 2L);
        assertThat(followRowsBetween(null, V)).isEqualTo(2);
        assertThat(followRowExists(O, V)).isTrue();
        assertThat(followRowExists(U, V)).isTrue();
        assertThat(followRowExists("f07-x", "f07-y")).isTrue();
        assertThat(followRowExists("f07-x2", "f07-y2")).isTrue();
    }

    /** T07-04 取消关注只删本人一行：O→V 及其它关系保留，followers 降为 1。 */
    @Test
    void flw_04_deleteRemovesOnlyOwnRow() throws Exception {
        seedFollow(R_UV, U, V, CT_NEW);
        seedFollow("f07-ov", O, V, CT_TIE);
        seedUser("f07-x", "f07用户X", "普通用户", "19900000105", "f07-bio-x");
        seedUser("f07-y", "f07用户Y", "普通用户", "19900000106", "f07-bio-y");
        seedFollow("f07-xr", "f07-x", "f07-y", "2026-07-07 12:00:00");
        assertFollowResult(deleteFollowAs(U, V), false, 1L);
        assertThat(followRowExists(U, V)).isFalse();
        assertThat(followRowExists(O, V)).isTrue();
        assertThat(followRowExists("f07-x", "f07-y")).isTrue();
    }

    /** T07-05 连续两次取消：每次 200 且 followers=1，不删 O 的行、计数不为负。 */
    @Test
    void flw_05_repeatDeleteIdempotent() throws Exception {
        seedFollow("f07-ov", O, V, CT_TIE);
        assertFollowResult(deleteFollowAs(U, V), false, 1L);
        assertFollowResult(deleteFollowAs(U, V), false, 1L);
        assertThat(followRowExists(O, V)).isTrue();
        assertThat(followRowsBetween(null, V)).isEqualTo(1);
    }

    /** T07-06 目标存在但关系不存在时取消仍成功：逐例从仅 U 关注开始重复取消、从无人关注开始，最终零行。 */
    @Test
    void flw_06_deleteWithoutRelationSucceeds() throws Exception {
        assertFollowResult(deleteFollowAs(U, V), false, 0L);
        assertThat(followRows()).isEqualTo(0);
        seedFollow(R_UV, U, V, CT_NEW);
        assertFollowResult(deleteFollowAs(U, V), false, 0L);
        assertFollowResult(deleteFollowAs(U, V), false, 0L);
        assertThat(followRows()).isEqualTo(0);
    }

    /** T07-07 关注自己：U 与 A 逐例 400 invalid_request，data=null，无自关注行、其它关系与计数不变。 */
    @Test
    void flw_07_selfFollowIs400NoRows() throws Exception {
        seedFollow(R_VU, V, U, CT_NEW);
        assertError(postFollowAs(U, U), 400, "invalid_request");
        assertError(postFollowAs(A, A), 400, "invalid_request");
        assertThat(followRowExists(U, U)).isFalse();
        assertThat(followRowExists(A, A)).isFalse();
        assertThat(followRowExists(V, U)).isTrue();
        assertThat(followRows()).isEqualTo(1);
    }

    /** T07-08 目标合法但不存在：POST/DELETE 逐例 404 not_found，无孤儿关系、无任何删改。 */
    @Test
    void flw_08_missingTargetIs404BothMethods() throws Exception {
        seedFollow(R_UV, U, V, CT_NEW);
        assertError(postFollowAs(U, M), 404, "not_found");
        assertError(deleteFollowAs(U, M), 404, "not_found");
        assertThat(followRows()).isEqualTo(1);
        assertThat(followRowExists(U, V)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE uid=? OR tuid=?",
                Integer.class, M, M)).isEqualTo(0);
    }

    /** T07-09 非法 uid：URL 编码 bad! 与 65 位逐例 400；64 位合法不存在逐例 404；全部关系不变。 */
    @Test
    void flw_09_badUidFormatsAre400AndLongValidIs404() throws Exception {
        seedFollow(R_UV, U, V, CT_NEW);
        String badUid = "bad%21";
        String uidOverLength = repeat("a", UID_MAX_LENGTH + 1);
        String uidMaxLength = repeat("a", UID_MAX_LENGTH);
        assertError(postFollowAs(U, badUid), 400, "invalid_request");
        assertError(deleteFollowAs(U, badUid), 400, "invalid_request");
        assertError(postFollowAs(U, uidOverLength), 400, "invalid_request");
        assertError(deleteFollowAs(U, uidOverLength), 400, "invalid_request");
        assertError(postFollowAs(U, uidMaxLength), 404, "not_found");
        assertError(deleteFollowAs(U, uidMaxLength), 404, "not_found");
        assertThat(followRows()).isEqualTo(1);
        assertThat(followRowExists(U, V)).isTrue();
    }

    /** T07-10 两并发关注同一目标：每次 200 且 followers=1，真实唯一键收敛为一行，不冒 409/500。 */
    @Test
    void flw_10_concurrentPostSingleRow() throws Exception {
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
                return mvc.perform(post("/follow/" + V).header("Authorization", auth)).andReturn();
            };
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertFollowResult(first.get(30, TimeUnit.SECONDS), true, 1L);
            assertFollowResult(second.get(30, TimeUnit.SECONDS), true, 1L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(followRowsBetween(U, V)).isEqualTo(1);
        assertThat(followRowExists(U, V)).isTrue();
    }

    /** T07-11 两并发取消关注：每次 200 且 followers=0，只删一次，最终零行。 */
    @Test
    void flw_11_concurrentDeleteZeroRows() throws Exception {
        seedFollow(R_UV, U, V, CT_NEW);
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
                return mvc.perform(delete("/follow/" + V).header("Authorization", auth)).andReturn();
            };
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertFollowResult(first.get(30, TimeUnit.SECONDS), false, 0L);
            assertFollowResult(second.get(30, TimeUnit.SECONDS), false, 0L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(followRowsBetween(U, V)).isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（本人关系列表）
    // ------------------------------------------------------------------

    /** T07-12 本人关注列表分页：字段恰为 uid/nickname/avatar/bio，uid 取 tuid 对应用户，ct 降序/fid 升序，不混入粉丝。 */
    @Test
    void flw_12_followingPageShapeAndOrder() throws Exception {
        seedRelationR();
        MvcResult result = mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "2")).andReturn();
        assertFollowPage(result, 3L, V, O);
    }

    /** T07-13 本人粉丝列表分页：uid 取关注者 uid 而非本人/tuid/fid，ct 降序/fid 升序。 */
    @Test
    void flw_13_followersPageShapeAndOrder() throws Exception {
        seedRelationR();
        MvcResult result = mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "2")).andReturn();
        assertFollowPage(result, 3L, V, O);
    }

    /** T07-14 每方向 11 关系：省略 query 按默认 page=1/size=10 取前 10 份且 total=11；pageNum/pageSize 不能当别名改变默认分页。 */
    @Test
    void flw_14_defaultPagingAndOldParamsIgnored() throws Exception {
        String[] followingOrder = seedElevenFollowing();
        String[] followerOrder = seedElevenFollowers();
        MvcResult followingDefault = mvc.perform(get("/follow/following")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertFollowPage(followingDefault, 11L, firstTen(followingOrder));
        MvcResult followingOldParams = mvc.perform(get("/follow/following")
                .header("Authorization", token(U, "普通用户"))
                .param("pageNum", "2").param("pageSize", "1")).andReturn();
        assertFollowPage(followingOldParams, 11L, firstTen(followingOrder));
        MvcResult followersDefault = mvc.perform(get("/follow/followers")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertFollowPage(followersDefault, 11L, firstTen(followerOrder));
        MvcResult followersOldParams = mvc.perform(get("/follow/followers")
                .header("Authorization", token(U, "普通用户"))
                .param("pageNum", "2").param("pageSize", "1")).andReturn();
        assertFollowPage(followersOldParams, 11L, firstTen(followerOrder));
    }

    /** T07-15 两列表逐例：page=2,size=2 取第 3 项、超页空页、无该方向关系空页、管理员也只查本人。 */
    @Test
    void flw_15_pageBeyondAndEmptyDirections() throws Exception {
        seedRelationR();
        seedUser("f07-e1", "f07用户E1", "普通用户", "19900000105", "f07-bio-e1");
        // 例1：R 的 page=2,size=2，两方向都只剩 F(A)。
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "2").param("size", "2")).andReturn(), 3L, A);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "2").param("size", "2")).andReturn(), 3L, A);
        // 例2：超页空页，total 仍为全部过滤结果 3。
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "999").param("size", "50")).andReturn(), 3L);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "999").param("size", "50")).andReturn(), 3L);
        // 例3：E1 本人无该方向关系，但库中别人有关系，两方向都空页。
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token("f07-e1", "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token("f07-e1", "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        // 例4：移除 A 的本人关系后，管理员也只查本人，两方向都空页。
        jdbc.update("DELETE FROM follow WHERE fid IN (?,?)", R_UA, R_AU);
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(A, "管理员"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(A, "管理员"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
    }

    /** T07-16（关系分支）两列表入口的 page/size 越界或非整数逐例 400 invalid_request，data=null，无查询写副作用。 */
    @Test
    void flw_16_invalidPagingIs400BothLists() throws Exception {
        seedRelationR();
        String[] badPages = new String[]{"0", "-1", "1.5", "x", PAGE_OVERFLOW};
        String[] badSizes = new String[]{"0", "-1", SIZE_OVER_LIMIT, "1.5", "x", PAGE_OVERFLOW};
        for (String endpoint : new String[]{"/follow/following", "/follow/followers"}) {
            for (String page : badPages) {
                assertError(mvc.perform(get(endpoint).header("Authorization", token(U, "普通用户"))
                        .param("page", page)).andReturn(), 400, "invalid_request");
            }
            for (String size : badSizes) {
                assertError(mvc.perform(get(endpoint).header("Authorization", token(U, "普通用户"))
                        .param("size", size)).andReturn(), 400, "invalid_request");
            }
        }
        assertThat(followRows()).isEqualTo(6);
    }

    /** T07-23（关系分支）两列表逐例 size=1、size=50：list 长度分别 1/3、total 均 3，每个元素完整 F 并按各自排序。 */
    @Test
    void flw_23_sizeOneAndFiftyBothLists() throws Exception {
        seedRelationR();
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "1")).andReturn(), 3L, V);
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 3L, V, O, A);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "1")).andReturn(), 3L, V);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 3L, V, O, A);
    }

    /** T07-24（关系分支）无 Authorization、无 Cookie 的四个 follow 入口逐例 401 unauthorized，data=null、不写库、不 302。 */
    @Test
    void flw_24_anonymousIs401AllFollowEndpoints() throws Exception {
        seedRelationR();
        assertError(mvc.perform(post("/follow/" + V)).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(delete("/follow/" + V)).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(get("/follow/following")).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(get("/follow/followers")).andReturn(), 401, "unauthorized");
        assertThat(followRows()).isEqualTo(6);
    }

    /** T07-25（关系分支）坏签名、过期 JWT，以及签发后主体被禁用/删除，四个入口逐例 401，关系不变。 */
    @Test
    void flw_25_badOrExpiredTokenAndDisabledDeletedSubject401() throws Exception {
        seedFollow("f07-ov", O, V, CT_TIE);
        String good = token(U, "普通用户");
        String badSignature = corrupt(good);
        String expired = expiredToken(U, "普通用户");
        String[] endpoints = new String[]{"/follow/" + V, "/follow/" + V, "/follow/following", "/follow/followers"};
        String[] methods = new String[]{"POST", "DELETE", "GET", "GET"};
        for (int i = 0; i < endpoints.length; i++) {
            assertError(perform(methods[i], endpoints[i], badSignature), 401, "unauthorized");
            assertError(perform(methods[i], endpoints[i], expired), 401, "unauthorized");
        }
        jdbc.update("UPDATE user SET status='禁用' WHERE uid=?", U);
        for (int i = 0; i < endpoints.length; i++) {
            assertError(perform(methods[i], endpoints[i], good), 401, "unauthorized");
        }
        jdbc.update("DELETE FROM user WHERE uid=?", U);
        for (int i = 0; i < endpoints.length; i++) {
            assertError(perform(methods[i], endpoints[i], good), 401, "unauthorized");
        }
        assertThat(followRows()).isEqualTo(1);
        assertThat(followRowExists(O, V)).isTrue();
    }

    /** T07-26（关系分支）仅 Cookie 携带有效 JWT 关注成功；坏 Authorization 加同一有效 Cookie 不回落，四入口逐例 401。 */
    @Test
    void flw_26_cookieTokenWorksAndBadHeaderNoFallback() throws Exception {
        String raw = jwtUtils.generateToken(U, "普通用户");
        Cookie tokenCookie = new Cookie("token", raw);
        assertFollowResult(mvc.perform(post("/follow/" + V).cookie(tokenCookie)).andReturn(), true, 1L);
        assertThat(followRowExists(U, V)).isTrue();
        MvcResult[] results = new MvcResult[]{
                mvc.perform(post("/follow/" + V).header("Authorization", "Bearer not.a.valid.token")
                        .cookie(tokenCookie)).andReturn(),
                mvc.perform(delete("/follow/" + V).header("Authorization", "Bearer not.a.valid.token")
                        .cookie(tokenCookie)).andReturn(),
                mvc.perform(get("/follow/following").header("Authorization", "Bearer not.a.valid.token")
                        .cookie(tokenCookie)).andReturn(),
                mvc.perform(get("/follow/followers").header("Authorization", "Bearer not.a.valid.token")
                        .cookie(tokenCookie)).andReturn()
        };
        for (MvcResult result : results) {
            assertError(result, 401, "unauthorized");
        }
        assertThat(followRowsBetween(U, V)).isEqualTo(1);
    }

    /** T07-27（关系分支）query 额外 uid/role 不能覆盖身份；A 无本人关系为空页；空/未知/带空格角色逐例 403。 */
    @Test
    void flw_27_identityNotOverridableAndBadRoles403() throws Exception {
        seedFollow(R_UV, U, V, CT_NEW);
        seedFollow(R_UO, U, O, CT_TIE);
        seedFollow(R_VU, V, U, CT_NEW);
        seedFollow(R_OU, O, U, CT_TIE);
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("uid", O).param("role", "管理员").param("page", "1").param("size", "10")).andReturn(), 2L, V, O);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("uid", O).param("role", "管理员").param("page", "1").param("size", "10")).andReturn(), 2L, V, O);
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(A, "管理员"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(A, "管理员"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        for (String role : new String[]{"", "访客", " 管理员 "}) {
            assertError(mvc.perform(get("/follow/following")
                    .header("Authorization", token(U, role))).andReturn(), 403, "forbidden");
            assertError(mvc.perform(get("/follow/followers")
                    .header("Authorization", token(U, role))).andReturn(), 403, "forbidden");
        }
    }

    /** T07-28（关系分支）固定路径不被 /{uid} 吞掉：U 得完整 Page，匿名逐例 401 而不是按 ID 查询的 404。 */
    @Test
    void flw_28_fixedPathsAndAnonymous() throws Exception {
        seedRelationR();
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 3L, V, O, A);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 3L, V, O, A);
        assertError(mvc.perform(get("/follow/following")).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(get("/follow/followers")).andReturn(), 401, "unauthorized");
    }

    /** T07-30（关系分支）另一方 uname/avatar/bio 全 NULL：两列表各一条且四键齐全、nullable 值不丢键。 */
    @Test
    void flw_30_nullProfileFieldsKept() throws Exception {
        seedUser("f07-n", "f07用户N", "普通用户", "19900000105", "f07-bio-n");
        seedUserNullProfile("f07-vn", "普通用户", "19900000106");
        seedUserNullProfile("f07-wn", "普通用户", "19900000107");
        seedFollow("f07-nv", "f07-n", "f07-vn", CT_NEW);
        seedFollow("f07-wn", "f07-wn", "f07-n", CT_TIE);
        MvcResult following = mvc.perform(get("/follow/following").header("Authorization", token("f07-n", "普通用户"))
                .param("page", "1").param("size", "10")).andReturn();
        JsonNode followingData = assertOk(following);
        assertKeys(followingData, "list", "total");
        assertThat(followingData.get("total").asLong()).isEqualTo(1L);
        JsonNode followingList = followingData.get("list");
        assertThat(followingList.isArray()).isTrue();
        assertThat(followingList.size()).isEqualTo(1);
        JsonNode followingItem = followingList.get(0);
        assertKeys(followingItem, "uid", "nickname", "avatar", "bio");
        assertThat(followingItem.get("uid").asText()).isEqualTo("f07-vn");
        assertThat(followingItem.get("nickname").isNull()).isTrue();
        assertThat(followingItem.get("avatar").isNull()).isTrue();
        assertThat(followingItem.get("bio").isNull()).isTrue();
        MvcResult followers = mvc.perform(get("/follow/followers").header("Authorization", token("f07-n", "普通用户"))
                .param("page", "1").param("size", "10")).andReturn();
        JsonNode followersData = assertOk(followers);
        assertKeys(followersData, "list", "total");
        assertThat(followersData.get("total").asLong()).isEqualTo(1L);
        JsonNode followersList = followersData.get("list");
        assertThat(followersList.isArray()).isTrue();
        assertThat(followersList.size()).isEqualTo(1);
        JsonNode followersItem = followersList.get(0);
        assertKeys(followersItem, "uid", "nickname", "avatar", "bio");
        assertThat(followersItem.get("uid").asText()).isEqualTo("f07-wn");
        assertThat(followersItem.get("nickname").isNull()).isTrue();
        assertThat(followersItem.get("avatar").isNull()).isTrue();
        assertThat(followersItem.get("bio").isNull()).isTrue();
    }

    /** T07-31（关系分支）顺序读取两个关系列表：读取前后 follow 与 pwork/点赞记录不变，不触发浏览+1、不清历史关系。 */
    @Test
    void flw_31_readsDoNotMutate() throws Exception {
        seedRelationR();
        int followBefore = followRows();
        long workRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pwork", Long.class);
        long viewcountBefore = jdbc.queryForObject("SELECT COALESCE(SUM(viewcount),0) FROM pwork", Long.class);
        long likeRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM liketable", Long.class);
        assertFollowPage(mvc.perform(get("/follow/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 3L, V, O, A);
        assertFollowPage(mvc.perform(get("/follow/followers").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 3L, V, O, A);
        assertThat(followRows()).isEqualTo(followBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pwork", Long.class)).isEqualTo(workRowsBefore);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(viewcount),0) FROM pwork", Long.class))
                .isEqualTo(viewcountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM liketable", Long.class)).isEqualTo(likeRowsBefore);
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（边界方法）
    // ------------------------------------------------------------------

    /** T07-32 DELETE 自己不扩展 400：无自关注行时取关自己 200，V/O 两位原粉丝全部保留。 */
    @Test
    void flw_32_deleteSelfKeepsFans() throws Exception {
        seedFollow(R_VU, V, U, CT_NEW);
        seedFollow(R_OU, O, U, CT_TIE);
        assertFollowResult(deleteFollowAs(U, U), false, 2L);
        assertThat(followRowExists(V, U)).isTrue();
        assertThat(followRowExists(O, U)).isTrue();
        assertThat(followRowsBetween(null, U)).isEqualTo(2);
        assertThat(followRowExists(U, U)).isFalse();
    }

    /** T07-33 PUT /follow/{uid} 为 405 invalid_request，data=null，关系不变，不能伪装 200 或执行关注。 */
    @Test
    void flw_33_putIs405() throws Exception {
        seedRelationR();
        MvcResult result = mvc.perform(put("/follow/" + V)
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertError(result, 405, "invalid_request");
        assertThat(followRows()).isEqualTo(6);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private void seedUser(String uid, String nickname, String role, String pnum, String bio) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) VALUES (?,?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "test-password", "/avatar/" + uid + ".png", "0", "正常", bio);
    }

    private void seedUserNullProfile(String uid, String role, String pnum) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) "
                        + "VALUES (?,NULL,?,?,?,NULL,'0','正常',NULL)",
                uid, pnum, role, "test-password");
    }

    private void seedFollow(String fid, String uid, String tuid, String ct) {
        jdbc.update("INSERT INTO follow (fid, uid, tuid, ct) VALUES (?,?,?,?)", fid, uid, tuid, ct);
    }

    /** 关系夹具 R：本人关注次序 V/O/A，粉丝次序 V/O/A，均由 ct 降序/fid 升序决定。 */
    private void seedRelationR() {
        seedFollow(R_UV, U, V, CT_NEW);
        seedFollow(R_UO, U, O, CT_TIE);
        seedFollow(R_UA, U, A, CT_TIE);
        seedFollow(R_VU, V, U, CT_NEW);
        seedFollow(R_OU, O, U, CT_TIE);
        seedFollow(R_AU, A, U, CT_TIE);
    }

    /** 造 11 个被 U 关注的用户，ct 依次递增；返回冻结排序（最新在前）的 uid 数组。 */
    private String[] seedElevenFollowing() {
        String[] order = new String[11];
        for (int i = 1; i <= 11; i++) {
            String uid = String.format("f07-e%02d", i);
            seedUser(uid, "f07用户E" + String.format("%02d", i), "普通用户",
                    String.format("199000003%02d", i), "f07-bio-e" + String.format("%02d", i));
            seedFollow(uid + "-r", U, uid, String.format("2026-07-07 12:00:%02d", i));
            order[11 - i] = uid;
        }
        return order;
    }

    /** 造 11 个关注 U 的用户，ct 依次递增；返回冻结排序（最新在前）的 uid 数组。 */
    private String[] seedElevenFollowers() {
        String[] order = new String[11];
        for (int i = 1; i <= 11; i++) {
            String uid = String.format("f07-g%02d", i);
            seedUser(uid, "f07用户G" + String.format("%02d", i), "普通用户",
                    String.format("199000004%02d", i), "f07-bio-g" + String.format("%02d", i));
            seedFollow(uid + "-r", uid, U, String.format("2026-07-07 12:00:%02d", i));
            order[11 - i] = uid;
        }
        return order;
    }

    private String[] firstTen(String[] order) {
        String[] firstTen = new String[10];
        System.arraycopy(order, 0, firstTen, 0, 10);
        return firstTen;
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

    private MvcResult postFollowAs(String actor, String target) throws Exception {
        return mvc.perform(post("/follow/" + target)
                .header("Authorization", token(actor, roleOf(actor)))).andReturn();
    }

    private MvcResult deleteFollowAs(String actor, String target) throws Exception {
        return mvc.perform(delete("/follow/" + target)
                .header("Authorization", token(actor, roleOf(actor)))).andReturn();
    }

    private MvcResult perform(String method, String path, String authorization) throws Exception {
        if ("POST".equals(method)) {
            return mvc.perform(post(path).header("Authorization", authorization)).andReturn();
        }
        if ("DELETE".equals(method)) {
            return mvc.perform(delete(path).header("Authorization", authorization)).andReturn();
        }
        return mvc.perform(get(path).header("Authorization", authorization)).andReturn();
    }

    private String roleOf(String uid) {
        return A.equals(uid) ? "管理员" : "普通用户";
    }

    private String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private int followRows() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE uid LIKE 'f07-%' OR tuid LIKE 'f07-%'", Integer.class);
        return rows == null ? 0 : rows;
    }

    private int followRowsBetween(String uid, String tuid) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE (uid=? OR ? IS NULL) AND (tuid=? OR ? IS NULL) ",
                Integer.class, uid, uid, tuid, tuid);
        return rows == null ? 0 : rows;
    }

    private boolean followRowExists(String uid, String tuid) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE uid=? AND tuid=? ",
                Integer.class, uid, tuid);
        return rows != null && rows > 0;
    }

    private void assertFollowResult(MvcResult result, boolean following, long followers) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        JsonNode data = json.get("data");
        assertKeys(data, "following", "followers");
        assertThat(data.get("following").asBoolean()).isEqualTo(following);
        assertThat(data.get("followers").isIntegralNumber()).isTrue();
        assertThat(data.get("followers").asLong()).isEqualTo(followers);
    }

    private void assertFollowPage(MvcResult result, long expectedTotal, String... expectedUids) throws Exception {
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isEqualTo(expectedTotal);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(expectedUids.length);
        for (int i = 0; i < expectedUids.length; i++) {
            assertFollowUser(list.get(i), expectedUids[i]);
        }
    }

    private void assertFollowUser(JsonNode item, String uid) {
        assertKeys(item, "uid", "nickname", "avatar", "bio");
        assertThat(item.get("uid").asText()).isEqualTo(uid);
        Map<String, Object> profile = jdbc.queryForMap("SELECT uname, avatar, bio FROM user WHERE uid=?", uid);
        assertNullableText(item.get("nickname"), (String) profile.get("uname"));
        assertNullableText(item.get("avatar"), (String) profile.get("avatar"));
        assertNullableText(item.get("bio"), (String) profile.get("bio"));
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
