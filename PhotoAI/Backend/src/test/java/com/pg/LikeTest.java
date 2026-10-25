package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
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
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 卡05 帖子点赞冻结测试（T05-18..T05-33）。
 * 只连 db_pg_test_05，夹具全部在本类内自建自清，不继承共享基座；
 * 点赞计数断言一律回读 liketable 行数与 pwork.likep，与 API count 三方核对。
 * T05-32/T05-33 的计数 UPDATE 故障通过测试连接持有 pwork 行锁注入，
 * 配合连接级 innodb_lock_wait_timeout=2 触发真实事务回滚，不 Mock 任何实现类。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LikeTest {

    /** 测试库固定指向 db_pg_test_05，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_05?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=innodb_lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    private static final Logger log = LoggerFactory.getLogger(LikeTest.class);

    private static final String U = "lk05-u";
    private static final String O = "lk05-o";
    private static final String A = "lk05-a";
    private static final String P1 = "lk05-p1";
    private static final String P2 = "lk05-p2";
    private static final String HIDDEN_PENDING = "lk05-ph1";
    private static final String HIDDEN_HIDDEN = "lk05-ph2";
    private static final String HIDDEN_REJECTED = "lk05-ph3";
    private static final String MISSING_PID = "lk05-nope";
    private static final String POST_TYPE = "帖子";
    private static final String COMMENT_TYPE = "评论";

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
    LikeTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
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
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_05");
        cleanupFixtures();
        seedUser(U, "lk05用户U", "普通用户", "19900000201");
        seedUser(O, "lk05用户O", "普通用户", "19900000202");
        seedUser(A, "lk05管理员A", "管理员", "19900000203");
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM comment WHERE pid LIKE 'lk05-%' OR uid LIKE 'lk05-%' OR cid LIKE 'lk05-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'lk05-%' OR uid LIKE 'lk05-%' OR targetid LIKE 'lk05-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'lk05-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'lk05-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例
    // ------------------------------------------------------------------

    /** T05-18 从 0 点赞成功：只落一行 (U,P1,帖子)，likep 变 1，响应 liked=true、count=1。 */
    @Test
    void like_18_postCreatesRowAndCount1() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = postLikeAs(U, P1);
        assertLikeResult(result, true, 1L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
        assertThat(likeRowExists(U, P1, POST_TYPE)).isTrue();
        assertThat(likepOf(P1)).isEqualTo(1L);
    }

    /** T05-19 已赞用户重复点赞仍 200 无 409：行数与 likep 不变，响应 liked=true、count=1。 */
    @Test
    void like_19_repeatPostIdempotent() throws Exception {
        seedWork(P1, U, "展示");
        seedLike("lk05-l1", U, P1, POST_TYPE);
        setLikep(P1, 1);
        MvcResult result = postLikeAs(U, P1);
        assertLikeResult(result, true, 1L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
        assertThat(likepOf(P1)).isEqualTo(1L);
    }

    /** T05-20 已赞用户取消：真实删除 1 行且 likep 只减 1，响应 liked=false、count=0。 */
    @Test
    void like_20_deleteRemovesRowAndDecrements() throws Exception {
        seedWork(P1, U, "展示");
        seedLike("lk05-l1", U, P1, POST_TYPE);
        setLikep(P1, 1);
        MvcResult result = deleteLikeAs(U, P1);
        assertLikeResult(result, false, 0L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P1)).isEqualTo(0L);
    }

    /** T05-21 未赞用户取消仍 200：不删除行、likep 不为负，响应 liked=false、count=0。 */
    @Test
    void like_21_deleteWithoutLikeIs200Zero() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = deleteLikeAs(U, P1);
        assertLikeResult(result, false, 0L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P1)).isEqualTo(0L);
    }

    /** T05-22 同一用户两并发点赞：两次都 200 无 409，真实唯一约束下只有一行，likep 只加 1。 */
    @Test
    void like_22_concurrentPostSingleRow() throws Exception {
        seedWork(P1, U, "展示");
        String auth = token(U, "普通用户");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> task = () -> {
                ready.countDown();
                start.await();
                return mvc.perform(post("/like/" + P1).header("Authorization", auth)).andReturn();
            };
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertLikeResult(first.get(30, TimeUnit.SECONDS), true, 1L);
            assertLikeResult(second.get(30, TimeUnit.SECONDS), true, 1L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
        assertThat(likeRowExists(U, P1, POST_TYPE)).isTrue();
        assertThat(likepOf(P1)).isEqualTo(1L);
    }

    /** T05-23 同一用户两并发取消：两次都 200，只删一次，likep 只减 1 且不为负。 */
    @Test
    void like_23_concurrentDeleteSingleDecrement() throws Exception {
        seedWork(P1, U, "展示");
        seedLike("lk05-l1", U, P1, POST_TYPE);
        setLikep(P1, 1);
        String auth = token(U, "普通用户");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> task = () -> {
                ready.countDown();
                start.await();
                return mvc.perform(delete("/like/" + P1).header("Authorization", auth)).andReturn();
            };
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertLikeResult(first.get(30, TimeUnit.SECONDS), false, 0L);
            assertLikeResult(second.get(30, TimeUnit.SECONDS), false, 0L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P1)).isEqualTo(0L);
    }

    /** T05-24 计数只算该作品帖子点赞：他人已赞 + 其它 targettype 记录不影响口径，P2 与原计数不变。 */
    @Test
    void like_24_countOnlyPostLikes() throws Exception {
        seedWork(P1, U, "展示");
        seedWork(P2, O, "展示");
        seedLike("lk05-l1", O, P1, POST_TYPE);
        seedLike("lk05-l2", O, P1, COMMENT_TYPE);
        setLikep(P1, 1);
        MvcResult result = postLikeAs(U, P1);
        assertLikeResult(result, true, 2L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(2);
        assertThat(likeRowExists(O, P1, POST_TYPE)).isTrue();
        assertThat(likeRowExists(U, P1, POST_TYPE)).isTrue();
        assertThat(likeRows(P1, COMMENT_TYPE)).isEqualTo(1);
        assertThat(likeRowExists(O, P1, COMMENT_TYPE)).isTrue();
        assertThat(likepOf(P1)).isEqualTo(2L);
        assertThat(likeRows(P2, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P2)).isEqualTo(0L);
    }

    /** T05-25 未赞用户取消不能删别人的行、不能减别人的赞：响应 liked=false、count=1。 */
    @Test
    void like_25_deleteDoesNotTouchOthers() throws Exception {
        seedWork(P1, U, "展示");
        seedLike("lk05-l1", O, P1, POST_TYPE);
        setLikep(P1, 1);
        MvcResult result = deleteLikeAs(U, P1);
        assertLikeResult(result, false, 1L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
        assertThat(likeRowExists(O, P1, POST_TYPE)).isTrue();
        assertThat(likeRowExists(U, P1, POST_TYPE)).isFalse();
        assertThat(likepOf(P1)).isEqualTo(1L);
    }

    /** T05-26 无凭证点赞 401 unauthorized，且不落库。 */
    @Test
    void like_26_postWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = mvc.perform(post("/like/" + P1)).andReturn();
        assertError(result, 401, "unauthorized");
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P1)).isEqualTo(0L);
    }

    /** T05-27 无凭证取消点赞 401 unauthorized，行与计数不变。 */
    @Test
    void like_27_deleteWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        seedLike("lk05-l1", U, P1, POST_TYPE);
        setLikep(P1, 1);
        MvcResult result = mvc.perform(delete("/like/" + P1)).andReturn();
        assertError(result, 401, "unauthorized");
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
        assertThat(likepOf(P1)).isEqualTo(1L);
    }

    /** T05-28 对非展示/不存在作品点赞，作者与管理员逐例 404；已有赞再点赞也 404，行与计数不变。 */
    @Test
    void like_28_postHiddenOrMissingIs404() throws Exception {
        seedWork(HIDDEN_PENDING, U, "待审核");
        seedWork(HIDDEN_HIDDEN, U, "隐藏");
        seedWork(HIDDEN_REJECTED, U, "已驳回");
        seedLike("lk05-lh1", U, HIDDEN_PENDING, POST_TYPE);
        setLikep(HIDDEN_PENDING, 1);
        String[] pids = new String[]{HIDDEN_PENDING, HIDDEN_HIDDEN, HIDDEN_REJECTED, MISSING_PID};
        for (String pid : pids) {
            assertError(postLikeAs(U, pid), 404, "not_found");
            assertError(postLikeAs(A, pid), 404, "not_found");
        }
        assertThat(likeRows(HIDDEN_PENDING, POST_TYPE)).isEqualTo(1);
        assertThat(likepOf(HIDDEN_PENDING)).isEqualTo(1L);
        assertThat(likeRows(HIDDEN_HIDDEN, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(HIDDEN_HIDDEN)).isEqualTo(0L);
        assertThat(likeRows(HIDDEN_REJECTED, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(HIDDEN_REJECTED)).isEqualTo(0L);
    }

    /** T05-29 对非展示/不存在作品取消点赞，作者与管理员逐例 404；已有赞取消也 404，行与计数不变。 */
    @Test
    void like_29_deleteHiddenOrMissingIs404() throws Exception {
        seedWork(HIDDEN_PENDING, U, "待审核");
        seedWork(HIDDEN_HIDDEN, U, "隐藏");
        seedWork(HIDDEN_REJECTED, U, "已驳回");
        seedLike("lk05-lh1", U, HIDDEN_PENDING, POST_TYPE);
        setLikep(HIDDEN_PENDING, 1);
        String[] pids = new String[]{HIDDEN_PENDING, HIDDEN_HIDDEN, HIDDEN_REJECTED, MISSING_PID};
        for (String pid : pids) {
            assertError(deleteLikeAs(U, pid), 404, "not_found");
            assertError(deleteLikeAs(A, pid), 404, "not_found");
        }
        assertThat(likeRows(HIDDEN_PENDING, POST_TYPE)).isEqualTo(1);
        assertThat(likepOf(HIDDEN_PENDING)).isEqualTo(1L);
        assertThat(likeRows(HIDDEN_HIDDEN, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(HIDDEN_HIDDEN)).isEqualTo(0L);
        assertThat(likeRows(HIDDEN_REJECTED, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(HIDDEN_REJECTED)).isEqualTo(0L);
    }

    /** T05-30 非法 pid 格式点赞 400 invalid_request。 */
    @Test
    void like_30_postBadIdIs400() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = mvc.perform(post("/like/bad%21")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertError(result, 400, "invalid_request");
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P1)).isEqualTo(0L);
    }

    /** T05-31 非法 pid 格式取消点赞 400 invalid_request。 */
    @Test
    void like_31_deleteBadIdIs400() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = mvc.perform(delete("/like/bad%21")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertError(result, 400, "invalid_request");
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
        assertThat(likepOf(P1)).isEqualTo(0L);
    }

    /** T05-32 点赞行写入后计数 UPDATE 故障：500 且行与 likep 一起回滚；恢复后下一正常请求只增 1。 */
    @Test
    void like_32_postCountFaultRollsBack() throws Exception {
        seedWork(P1, U, "展示");
        Connection lock = null;
        try {
            lock = jdbc.getDataSource().getConnection();
            lock.setAutoCommit(false);
            PreparedStatement ps = lock.prepareStatement("SELECT likep FROM pwork WHERE pid = ? FOR UPDATE");
            ps.setString(1, P1);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            log.info("已持有 pwork 行锁注入计数 UPDATE 故障 pid={}", P1);
            MvcResult result = postLikeAs(U, P1);
            assertError(result, 500, "internal_error");
            assertThat(likeRows(P1, POST_TYPE)).isEqualTo(0);
            assertThat(likepOf(P1)).isEqualTo(0L);
            rs.close();
            ps.close();
        } finally {
            if (lock != null) {
                lock.rollback();
                lock.close();
            }
        }
        MvcResult again = postLikeAs(U, P1);
        assertLikeResult(again, true, 1L);
        assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
        assertThat(likepOf(P1)).isEqualTo(1L);
    }

    /** T05-33 取消删除行后计数 UPDATE 故障：500 且赞行恢复、likep 原值保持。 */
    @Test
    void like_33_deleteCountFaultRollsBack() throws Exception {
        seedWork(P1, U, "展示");
        seedLike("lk05-l1", U, P1, POST_TYPE);
        setLikep(P1, 1);
        Connection lock = null;
        try {
            lock = jdbc.getDataSource().getConnection();
            lock.setAutoCommit(false);
            PreparedStatement ps = lock.prepareStatement("SELECT likep FROM pwork WHERE pid = ? FOR UPDATE");
            ps.setString(1, P1);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            log.info("已持有 pwork 行锁注入计数 UPDATE 故障 pid={}", P1);
            MvcResult result = deleteLikeAs(U, P1);
            assertError(result, 500, "internal_error");
            assertThat(likeRows(P1, POST_TYPE)).isEqualTo(1);
            assertThat(likeRowExists(U, P1, POST_TYPE)).isTrue();
            assertThat(likepOf(P1)).isEqualTo(1L);
            rs.close();
            ps.close();
        } finally {
            if (lock != null) {
                lock.rollback();
                lock.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private void seedUser(String uid, String nickname, String role, String pnum) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status) VALUES (?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "test-password", "/avatar/" + uid + ".png", "0", "正常");
    }

    private void seedWork(String pid, String authorUid, String status) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, likep) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                pid, authorUid, "测试作品" + pid, "测试作品描述", "/static/images/pwork/test.jpg", 0, 0, status, 0);
    }

    private void seedLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void setLikep(String pid, int likep) {
        jdbc.update("UPDATE pwork SET likep=? WHERE pid=?", likep, pid);
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    private String roleOf(String uid) {
        return A.equals(uid) ? "管理员" : "普通用户";
    }

    private MvcResult postLikeAs(String uid, String pid) throws Exception {
        return mvc.perform(post("/like/" + pid).header("Authorization", token(uid, roleOf(uid)))).andReturn();
    }

    private MvcResult deleteLikeAs(String uid, String pid) throws Exception {
        return mvc.perform(delete("/like/" + pid).header("Authorization", token(uid, roleOf(uid)))).andReturn();
    }

    private int likeRows(String targetid, String targettype) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM liketable WHERE targetid=? AND targettype=?",
                Integer.class, targetid, targettype);
        return rows == null ? 0 : rows;
    }

    private boolean likeRowExists(String uid, String targetid, String targettype) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM liketable WHERE uid=? AND targetid=? AND targettype=?",
                Integer.class, uid, targetid, targettype);
        return rows != null && rows > 0;
    }

    private long likepOf(String pid) {
        Long likep = jdbc.queryForObject("SELECT likep FROM pwork WHERE pid=?", Long.class, pid);
        assertThat(likep).as("作品 %s 不存在，无法回读 likep", pid).isNotNull();
        return likep;
    }

    private void assertLikeResult(MvcResult result, boolean liked, long count) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        JsonNode data = json.get("data");
        assertKeys(data, "liked", "count");
        assertThat(data.get("liked").asBoolean()).isEqualTo(liked);
        assertThat(data.get("count").isIntegralNumber()).isTrue();
        assertThat(data.get("count").asLong()).isEqualTo(count);
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
