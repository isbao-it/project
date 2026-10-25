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
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * 卡07 关注流冻结测试（流分支）：T07-17..T07-22、T07-29、T07-34，
 * 及 T07-16、T07-23..T07-28、T07-30、T07-31 中 GET /pwork/following 入口的分支。
 * 关系入口的分支由 FollowTest 承接。
 * 只连 db_pg_test_07，夹具全部在本类内自建自清（uid/fid/pid 统一 f07- 前缀），
 * 用真实 pwork/follow/liketable 行验证「只含已关注作者的展示作品」的过滤、排序与十四键投影。
 * T07-34 的未知 SQL 故障通过测试连接持有 pwork 表写锁、配合连接级 lock_wait_timeout=2
 * 触发真实查询失败注入，不 Mock 任何实现类、不静态引用尚未合入的 Feed/FeedMapper。
 * 所有断言在实现缺失时因路由或目标行为缺失而红，不使用 @Disabled、不放松期望。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FeedTest {

    /** 测试库固定指向 db_pg_test_07，不改共享 application-test.yml；lock_wait_timeout 供 T07-34 故障注入。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_07?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    private static final Logger log = LoggerFactory.getLogger(FeedTest.class);

    /** 与 application-test.yml 相同的测试签名密钥，仅用于测试内签发过期 JWT。 */
    private static final String TEST_JWT_SECRET =
            "cGhvdG9haS1jYXJkMDEtdGVzdC1zZWNyZXQtb25seS0yMDI2MTAyMA==";

    private static final String U = "f07-u";
    private static final String V = "f07-v";
    private static final String O = "f07-o";
    private static final String A = "f07-a";
    /** 只关注 U 的作者，其展示作品不得进入 U 的关注流。 */
    private static final String X = "f07-x";

    /** 关注流夹具 S 的作品编号。 */
    private static final String P1 = "f07-p1";
    private static final String P2 = "f07-p2";
    private static final String P3 = "f07-p3";
    private static final String PH1 = "f07-ph1";
    private static final String PH2 = "f07-ph2";
    private static final String PH3 = "f07-ph3";
    private static final String PA = "f07-pa";
    private static final String PU = "f07-pu";
    private static final String PX = "f07-px";
    private static final String C1 = "f07-c1";

    /** 超出 int 范围的分页样本，冻结为 400。 */
    private static final String PAGE_OVERFLOW = "2147483648";
    /** 冻结分页上限之外的最小 size 样本。 */
    private static final String SIZE_OVER_LIMIT = "51";
    /** INT UNSIGNED 计数样本：超出 Java Integer 正数范围，服务端必须以 Long 输出原值。 */
    private static final long INT_UNSIGNED_SAMPLE = 2147483648L;
    /** T07-29 冻结的固定作品时间。 */
    private static final String FIXED_CWTIME = "2026-07-07 10:20:30";
    private static final String FIXED_CWTIME_ISO = "2026-07-07T10:20:30+08:00";
    /** T07-34 注入故障的专属标记，不得出现在任何响应正文中。 */
    private static final String FAULT_MARKER = "f07-sql-fault-7c91";

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
    FeedTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入五个测试身份。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_07");
        cleanupFixtures();
        seedUser(U, "f07用户U", "普通用户", "19900000101", "f07-bio-u");
        seedUser(V, "f07用户V", "普通用户", "19900000102", "f07-bio-v");
        seedUser(O, "f07用户O", "普通用户", "19900000103", "f07-bio-o");
        seedUser(A, "f07管理员A", "管理员", "19900000104", "f07-bio-a");
        seedUser(X, "f07用户X", "普通用户", "19900000105", "f07-bio-x");
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM comment WHERE cid LIKE 'f07-%' OR pid LIKE 'f07-%' OR uid LIKE 'f07-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'f07-%' OR uid LIKE 'f07-%' OR targetid LIKE 'f07-%'");
        jdbc.update("DELETE FROM follow WHERE fid LIKE 'f07-%' OR uid LIKE 'f07-%' OR tuid LIKE 'f07-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'f07-%' OR uid LIKE 'f07-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'f07-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'f07-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（关注流过滤、排序与投影）
    // ------------------------------------------------------------------

    /** T07-17 关注流只含已关注作者的展示作品：排除未关注 A、本人、只关注 U 的作者及三种非展示，total=3。 */
    @Test
    void fed_17_feedOnlyFollowedDisplayWorks() throws Exception {
        seedStreamS();
        MvcResult result = mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn();
        assertFeedPage(result, 3L, P1, P2, P3);
        // 九件夹具作品全部真实落库，证明排除是过滤结果而非夹具缺失。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pwork WHERE pid LIKE 'f07-%'", Long.class))
                .isEqualTo(9L);
    }

    /** T07-18 分页按 cwtime 降序/pid 升序：依次 [p1,p2]/[p3]/[]，total 均 3，页间不重不漏。 */
    @Test
    void fed_18_paginationOrderNoGap() throws Exception {
        seedStreamS();
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "2")).andReturn(), 3L, P1, P2);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "2").param("size", "2")).andReturn(), 3L, P3);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "999").param("size", "50")).andReturn(), 3L);
    }

    /** T07-19 11 件已关注作者展示作品：省略 query 按默认 page=1/size=10 取前 10 份且 total=11；pageNum/pageSize 不能当别名。 */
    @Test
    void fed_19_defaultPagingAndOldParamsIgnored() throws Exception {
        String[] order = seedElevenDisplayWorks();
        String[] firstTen = new String[10];
        System.arraycopy(order, 0, firstTen, 0, 10);
        MvcResult defaults = mvc.perform(get("/pwork/following")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertFeedPage(defaults, 11L, firstTen);
        MvcResult oldParams = mvc.perform(get("/pwork/following")
                .header("Authorization", token(U, "普通用户"))
                .param("pageNum", "2").param("pageSize", "1")).andReturn();
        assertFeedPage(oldParams, 11L, firstTen);
    }

    /** T07-20 无关注/只关注无作品用户/只关注三种非展示作品用户逐例空页 total=0，不热门补位、不 null、不 404。 */
    @Test
    void fed_20_emptyFeedNoFill() throws Exception {
        seedWork(PA, A, "f07作品A", "f07描述A", "/static/images/pwork/f07-a.jpg",
                1, 1, "展示", "2026-07-07 12:00:04", 1, null);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
        seedWork(PH1, V, "f07作品H1", "f07描述H1", "/static/images/pwork/f07-h1.jpg",
                1, 1, "待审核", "2026-07-07 12:00:01", 1, null);
        seedWork(PH2, V, "f07作品H2", "f07描述H2", "/static/images/pwork/f07-h2.jpg",
                1, 1, "隐藏", "2026-07-07 12:00:02", 1, null);
        seedWork(PH3, V, "f07作品H3", "f07描述H3", "/static/images/pwork/f07-h3.jpg",
                1, 1, "已驳回", "2026-07-07 12:00:03", 1, null);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
    }

    /** T07-21 取关后关注流立即为空：DELETE data 为剩余真实粉丝数，随即 GET 得空页，不依赖缓存过期。 */
    @Test
    void fed_21_unfollowImmediatelyEmptiesFeed() throws Exception {
        seedWork(P1, V, "f07作品一号", "f07描述一", "/static/images/pwork/f07-1.jpg",
                101, 11, "展示", "2026-07-07 12:00:03", 7, null);
        seedWork(P2, V, "f07作品二号", "f07描述二", "/static/images/pwork/f07-2.jpg",
                102, 12, "展示", "2026-07-07 12:00:02", 8, null);
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        seedFollow("f07-fs2", O, V, "2026-07-07 10:00:01");
        MvcResult deleted = mvc.perform(delete("/follow/" + V)
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertThat(deleted.getResponse().getStatus()).isEqualTo(200);
        JsonNode deleteJson = parseBody(deleted);
        assertTopLevelKeys(deleteJson);
        assertThat(deleteJson.get("code").asText()).isEqualTo("ok");
        JsonNode deleteData = deleteJson.get("data");
        assertKeys(deleteData, "following", "followers");
        assertThat(deleteData.get("following").asBoolean()).isFalse();
        assertThat(deleteData.get("followers").asLong()).isEqualTo(1L);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "10")).andReturn(), 0L);
    }

    /** T07-22 p1 逐例变待审核/隐藏/已驳回后查询流均为 [p2,p3] total=2；作者与管理员不绕过；读取不删关注关系。 */
    @Test
    void fed_22_hiddenWorksExcludedAllRoles() throws Exception {
        seedStreamS();
        for (String status : new String[]{"待审核", "隐藏", "已驳回"}) {
            jdbc.update("UPDATE pwork SET wstatus=? WHERE pid=?", status, P1);
            assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                    .param("page", "1").param("size", "50")).andReturn(), 2L, P2, P3);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE fid LIKE 'f07-%'", Long.class))
                .isEqualTo(3L);
        // 另组一：U 本人是非展示作品的作者，也不能让本人作品进入关注流。
        clearStreamFixtures();
        seedWork(P1, U, "f07作品一号", "f07描述一", "/static/images/pwork/f07-1.jpg",
                101, 11, "待审核", "2026-07-07 12:00:03", 7, null);
        seedWork(PU, U, "f07作品自", "f07描述自", "/static/images/pwork/f07-u.jpg",
                105, 15, "展示", "2026-07-07 12:00:05", 5, null);
        seedWork(P2, O, "f07作品二号", "f07描述二", "/static/images/pwork/f07-2.jpg",
                102, 12, "展示", "2026-07-07 12:00:02", 8, null);
        seedWork(P3, V, "f07作品三号", "f07描述三", "/static/images/pwork/f07-3.jpg",
                103, 13, "展示", "2026-07-07 12:00:02", 9, null);
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        seedFollow("f07-fs2", U, O, "2026-07-07 10:00:01");
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 2L, P2, P3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE fid LIKE 'f07-%'", Long.class))
                .isEqualTo(2L);
        // 另组二：管理员本人关注关系中同样只出展示作品，管理员不绕过可见性。
        clearStreamFixtures();
        seedWork(P1, V, "f07作品一号", "f07描述一", "/static/images/pwork/f07-1.jpg",
                101, 11, "隐藏", "2026-07-07 12:00:03", 7, null);
        seedWork(P2, O, "f07作品二号", "f07描述二", "/static/images/pwork/f07-2.jpg",
                102, 12, "展示", "2026-07-07 12:00:02", 8, null);
        seedWork(P3, V, "f07作品三号", "f07描述三", "/static/images/pwork/f07-3.jpg",
                103, 13, "展示", "2026-07-07 12:00:02", 9, null);
        seedWork(PA, A, "f07作品A", "f07描述A", "/static/images/pwork/f07-a.jpg",
                104, 14, "展示", "2026-07-07 12:00:05", 4, null);
        seedFollow("f07-fa1", A, V, "2026-07-07 10:00:00");
        seedFollow("f07-fa2", A, O, "2026-07-07 10:00:01");
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(A, "管理员"))
                .param("page", "1").param("size", "50")).andReturn(), 2L, P2, P3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE fid LIKE 'f07-%'", Long.class))
                .isEqualTo(2L);
    }

    /** T07-23（流分支）size=1、size=50 逐例：list 长度分别 1/3、total 均 3，每个元素完整 W。 */
    @Test
    void fed_23_sizeOneAndFifty() throws Exception {
        seedStreamS();
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "1")).andReturn(), 3L, P1);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 3L, P1, P2, P3);
    }

    /** T07-24（流分支）无 Authorization、无 Cookie 的 GET /pwork/following 401 unauthorized，data=null、不公开空页。 */
    @Test
    void fed_24_anonymousIs401() throws Exception {
        seedStreamS();
        int followBefore = followRows();
        assertError(mvc.perform(get("/pwork/following")).andReturn(), 401, "unauthorized");
        assertThat(followRows()).isEqualTo(followBefore);
    }

    /** T07-25（流分支）坏签名、过期 JWT，以及签发后主体被禁用/删除，逐例 401，关系不变。 */
    @Test
    void fed_25_badOrExpiredTokenAndDisabledDeletedSubject401() throws Exception {
        seedFollow("f07-ov", O, V, "2026-07-07 10:00:00");
        String good = token(U, "普通用户");
        String badSignature = corrupt(good);
        String expired = expiredToken(U, "普通用户");
        assertError(mvc.perform(get("/pwork/following")
                .header("Authorization", badSignature)).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(get("/pwork/following")
                .header("Authorization", expired)).andReturn(), 401, "unauthorized");
        jdbc.update("UPDATE user SET status='禁用' WHERE uid=?", U);
        assertError(mvc.perform(get("/pwork/following")
                .header("Authorization", good)).andReturn(), 401, "unauthorized");
        jdbc.update("DELETE FROM user WHERE uid=?", U);
        assertError(mvc.perform(get("/pwork/following")
                .header("Authorization", good)).andReturn(), 401, "unauthorized");
        assertThat(followRows()).isEqualTo(1);
        assertThat(followRowExists(O, V)).isTrue();
    }

    /** T07-26（流分支）坏 Authorization 加同一有效 Cookie 不回落 Cookie，GET /pwork/following 401。 */
    @Test
    void fed_26_badHeaderNoCookieFallback() throws Exception {
        seedStreamS();
        Cookie tokenCookie = new Cookie("token", jwtUtils.generateToken(U, "普通用户"));
        assertError(mvc.perform(get("/pwork/following")
                .header("Authorization", "Bearer not.a.valid.token").cookie(tokenCookie)).andReturn(),
                401, "unauthorized");
    }

    /** T07-27（流分支）query 额外 uid/role 不能覆盖身份；A 无本人关系为空页；空/未知/带空格角色逐例 403。 */
    @Test
    void fed_27_identityNotOverridableAndBadRoles403() throws Exception {
        seedStreamS();
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("uid", O).param("role", "管理员").param("page", "1").param("size", "50")).andReturn(),
                3L, P1, P2, P3);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(A, "管理员"))
                .param("page", "1").param("size", "50")).andReturn(), 0L);
        for (String role : new String[]{"", "访客", " 管理员 "}) {
            assertError(mvc.perform(get("/pwork/following")
                    .header("Authorization", token(U, role))).andReturn(), 403, "forbidden");
        }
    }

    /** T07-28（流分支）固定路径不被 /{pid} 吞掉：U 得完整 Page<W>，匿名 401 而不是按 ID 查询的 404。 */
    @Test
    void fed_28_fixedPathAndAnonymous() throws Exception {
        seedStreamS();
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 3L, P1, P2, P3);
        assertError(mvc.perform(get("/pwork/following")).andReturn(), 401, "unauthorized");
    }

    /** T07-29 三个计数均 2147483648 以 Long 原样输出、realLikeCount 只计真实帖子赞、cwtime 精确到夹具 +08:00 时间。 */
    @Test
    void fed_29_longCountersAndExactTime() throws Exception {
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        seedWork(P1, V, "f07作品一号", "f07描述一", "/static/images/pwork/f07-1.jpg",
                INT_UNSIGNED_SAMPLE, INT_UNSIGNED_SAMPLE, "展示", FIXED_CWTIME, INT_UNSIGNED_SAMPLE, null);
        seedWork(PA, A, "f07作品A", "f07描述A", "/static/images/pwork/f07-a.jpg",
                1, 1, "展示", "2026-07-07 12:00:05", 1, null);
        seedLike("f07-l1", U, P1, "帖子");
        seedLike("f07-l2", O, P1, "帖子");
        seedLike("f07-l3", O, P1, "评论");
        seedLike("f07-la", O, PA, "帖子");
        MvcResult result = mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").asLong()).isEqualTo(1L);
        JsonNode item = data.get("list").get(0);
        assertKeys(item, "pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
                "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
        assertThat(item.get("pid").asText()).isEqualTo(P1);
        assertThat(item.get("viewcount").isIntegralNumber()).isTrue();
        assertThat(item.get("viewcount").asLong()).isEqualTo(INT_UNSIGNED_SAMPLE);
        assertThat(item.get("votecount").isIntegralNumber()).isTrue();
        assertThat(item.get("votecount").asLong()).isEqualTo(INT_UNSIGNED_SAMPLE);
        assertThat(item.get("likep").isIntegralNumber()).isTrue();
        assertThat(item.get("likep").asLong()).isEqualTo(INT_UNSIGNED_SAMPLE);
        assertThat(item.get("realLikeCount").isIntegralNumber()).isTrue();
        assertThat(item.get("realLikeCount").asLong()).isEqualTo(2L);
        assertThat(item.get("cwtime").asText()).isEqualTo(FIXED_CWTIME_ISO);
    }

    /** T07-30（流分支）唯一已关注作者 avatar=NULL、作品 pcid=NULL：W 的 avatar/pcname 为 null 但键保留，total=1。 */
    @Test
    void fed_30_nullAvatarAndPcnameKept() throws Exception {
        jdbc.update("UPDATE user SET avatar=NULL WHERE uid=?", V);
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        seedWork(P1, V, "f07作品一号", "f07描述一", "/static/images/pwork/f07-1.jpg",
                101, 11, "展示", "2026-07-07 12:00:03", 7, null);
        MvcResult result = mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").asLong()).isEqualTo(1L);
        JsonNode item = data.get("list").get(0);
        assertKeys(item, "pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
                "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
        assertThat(item.get("pid").asText()).isEqualTo(P1);
        assertThat(item.has("avatar")).isTrue();
        assertThat(item.get("avatar").isNull()).isTrue();
        assertThat(item.has("pcname")).isTrue();
        assertThat(item.get("pcname").isNull()).isTrue();
        assertWork(item);
    }

    /** T07-31（流分支）读取关注流前后 follow、pwork 与点赞记录不变，不通过 detail 拼装导致浏览+1。 */
    @Test
    void fed_31_readDoesNotMutate() throws Exception {
        seedStreamS();
        int followBefore = followRows();
        long workRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pwork", Long.class);
        long viewcountBefore = jdbc.queryForObject("SELECT COALESCE(SUM(viewcount),0) FROM pwork", Long.class);
        long likeRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM liketable", Long.class);
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 3L, P1, P2, P3);
        assertThat(followRows()).isEqualTo(followBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pwork", Long.class)).isEqualTo(workRowsBefore);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(viewcount),0) FROM pwork", Long.class))
                .isEqualTo(viewcountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM liketable", Long.class)).isEqualTo(likeRowsBefore);
    }

    /** T07-16（流分支）/pwork/following 的 page/size 越界或非整数逐例 400 invalid_request，data=null，无查询写副作用。 */
    @Test
    void fed_16_invalidPagingIs400() throws Exception {
        seedStreamS();
        long viewcountBefore = jdbc.queryForObject("SELECT COALESCE(SUM(viewcount),0) FROM pwork", Long.class);
        String[] badPages = new String[]{"0", "-1", "1.5", "x", PAGE_OVERFLOW};
        String[] badSizes = new String[]{"0", "-1", SIZE_OVER_LIMIT, "1.5", "x", PAGE_OVERFLOW};
        for (String page : badPages) {
            assertError(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                    .param("page", page)).andReturn(), 400, "invalid_request");
        }
        for (String size : badSizes) {
            assertError(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                    .param("size", size)).andReturn(), 400, "invalid_request");
        }
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(viewcount),0) FROM pwork", Long.class))
                .isEqualTo(viewcountBefore);
        assertThat(followRows()).isEqualTo(3);
    }

    /** T07-34 受控注入关注流查询的未知 SQL 故障：500 internal_error，data=null，msg 不含 SQL/底层异常/用户输入；解锁后同入口恢复 200。 */
    @Test
    void fed_34_injectedSqlFaultIs500() throws Exception {
        seedStreamS();
        Connection lock = null;
        try {
            lock = jdbc.getDataSource().getConnection();
            Statement lockStatement = lock.createStatement();
            lockStatement.execute("LOCK TABLES pwork WRITE");
            lockStatement.close();
            log.info("已持有 pwork 表写锁注入关注流查询故障 pid={}", P1);
            MvcResult result = mvc.perform(get("/pwork/following")
                    .header("Authorization", token(U, "普通用户"))).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(500);
            JsonNode json = parseBody(result);
            assertTopLevelKeys(json);
            assertThat(json.get("code").asText()).isEqualTo("internal_error");
            assertThat(json.get("msg").asText()).isNotEmpty();
            assertThat(json.get("data").isNull()).isTrue();
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("SQL", "sql", "Lock", "lock", FAULT_MARKER, "f07-");
        } finally {
            if (lock != null) {
                Statement unlockStatement = null;
                try {
                    unlockStatement = lock.createStatement();
                    unlockStatement.execute("UNLOCK TABLES");
                } catch (Exception unlockFailure) {
                    log.info("释放 pwork 表锁失败，交由连接关闭兜底", unlockFailure);
                } finally {
                    if (unlockStatement != null) {
                        try {
                            unlockStatement.close();
                        } catch (Exception ignored) {
                            log.info("关闭解锁语句失败", ignored);
                        }
                    }
                    try {
                        lock.close();
                    } catch (Exception ignored) {
                        log.info("关闭锁连接失败", ignored);
                    }
                }
            }
        }
        // 主业务样本仍用实库：解锁后同一入口恢复 200，证明 500 只来自注入故障。
        assertFeedPage(mvc.perform(get("/pwork/following").header("Authorization", token(U, "普通用户"))
                .param("page", "1").param("size", "50")).andReturn(), 3L, P1, P2, P3);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private void seedUser(String uid, String nickname, String role, String pnum, String bio) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) VALUES (?,?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "test-password", "/avatar/" + uid + ".png", "0", "正常", bio);
    }

    private void seedWork(String pid, String authorUid, String title, String desc, String vurl,
                          long viewcount, long votecount, String status, String cwtime, long likep, String pcid) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, cwtime, likep, pcid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                pid, authorUid, title, desc, vurl, viewcount, votecount, status, cwtime, likep, pcid);
    }

    private void seedLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void seedFollow(String fid, String uid, String tuid, String ct) {
        jdbc.update("INSERT INTO follow (fid, uid, tuid, ct) VALUES (?,?,?,?)", fid, uid, tuid, ct);
    }

    /** 关注流夹具 S：U 只关注 V/O；结果只能是 p1/p2/p3，total=3，其余展示作品与三种非展示一律排除。 */
    private void seedStreamS() {
        jdbc.update("INSERT INTO pclist (pcid, pcname, cds, sort, ct) VALUES (?,?,'f07分类说明',0,NOW())",
                C1, "f07分类一");
        seedWork(P1, V, "f07作品一号", "f07描述一", "/static/images/pwork/f07-1.jpg",
                101, 11, "展示", "2026-07-07 12:00:03", 7, C1);
        seedWork(P2, O, "f07作品二号", "f07描述二", "/static/images/pwork/f07-2.jpg",
                102, 12, "展示", "2026-07-07 12:00:02", 8, null);
        seedWork(P3, V, "f07作品三号", "f07描述三", "/static/images/pwork/f07-3.jpg",
                103, 13, "展示", "2026-07-07 12:00:02", 9, null);
        seedWork(PH1, V, "f07作品H1", "f07描述H1", "/static/images/pwork/f07-h1.jpg",
                1, 1, "待审核", "2026-07-07 12:00:01", 1, null);
        seedWork(PH2, V, "f07作品H2", "f07描述H2", "/static/images/pwork/f07-h2.jpg",
                1, 1, "隐藏", "2026-07-07 12:00:01", 1, null);
        seedWork(PH3, V, "f07作品H3", "f07描述H3", "/static/images/pwork/f07-h3.jpg",
                1, 1, "已驳回", "2026-07-07 12:00:01", 1, null);
        seedWork(PA, A, "f07作品A", "f07描述A", "/static/images/pwork/f07-a.jpg",
                104, 14, "展示", "2026-07-07 12:00:04", 4, null);
        seedWork(PU, U, "f07作品U", "f07描述U", "/static/images/pwork/f07-u.jpg",
                105, 15, "展示", "2026-07-07 12:00:05", 5, null);
        seedWork(PX, X, "f07作品X", "f07描述X", "/static/images/pwork/f07-x.jpg",
                106, 16, "展示", "2026-07-07 12:00:06", 6, null);
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        seedFollow("f07-fs2", U, O, "2026-07-07 10:00:01");
        seedFollow("f07-fs3", X, U, "2026-07-07 10:00:02");
        seedLike("f07-l1", U, P1, "帖子");
        seedLike("f07-lc1", O, P1, "评论");
    }

    /** 清空流相关夹具行，供 T07-22 的各组用例在同一方法内重建。 */
    private void clearStreamFixtures() {
        jdbc.update("DELETE FROM follow WHERE fid LIKE 'f07-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'f07-%' OR targetid LIKE 'f07-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'f07-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'f07-%'");
    }

    /** 造 11 件已关注作者的展示作品，cwtime 依次递增；返回冻结排序（最新在前）的 pid 数组。 */
    private String[] seedElevenDisplayWorks() {
        seedFollow("f07-fs1", U, V, "2026-07-07 10:00:00");
        String[] order = new String[11];
        for (int i = 1; i <= 11; i++) {
            String pid = String.format("f07-w%02d", i);
            seedWork(pid, V, "f07作品W" + String.format("%02d", i), "f07描述W" + String.format("%02d", i),
                    "/static/images/pwork/f07-w" + String.format("%02d", i) + ".jpg",
                    i, i, "展示", String.format("2026-07-07 12:00:%02d", i), i, null);
            order[11 - i] = pid;
        }
        return order;
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

    private int followRows() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE fid LIKE 'f07-%'", Integer.class);
        return rows == null ? 0 : rows;
    }

    private boolean followRowExists(String uid, String tuid) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE uid=? AND tuid=? AND fid LIKE 'f07-%'",
                Integer.class, uid, tuid);
        return rows != null && rows > 0;
    }

    /** 断言分页信封与逐项十四键 W 投影：所有值与库内夹具逐项相等，cwtime 用与实现相同的 +08:00 格式回读比对。 */
    private void assertFeedPage(MvcResult result, long expectedTotal, String... expectedPids) throws Exception {
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
