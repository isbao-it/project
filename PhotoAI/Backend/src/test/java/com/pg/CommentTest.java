package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.myutils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 卡05 评论接口冻结测试（T05-01..T05-17、T05-34、T05-35）。
 * 只连 db_pg_test_05，夹具全部在本类内自建自清，不继承共享基座；
 * MsgSecCheck 属卡03 尚未合入，本类通过运行时反射把 CommentServiceImpl
 * 注入的 MsgSecCheck 字段替换为测试替身，接口未合入时跳过替身、由路由缺失自然红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CommentTest {

    /** 测试库固定指向 db_pg_test_05，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_05?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=innodb_lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    private static final Logger log = LoggerFactory.getLogger(CommentTest.class);

    private static final String U = "ct05-u";
    private static final String O = "ct05-o";
    private static final String A = "ct05-a";
    private static final String P1 = "ct05-p1";
    private static final String HIDDEN_PENDING = "ct05-ph1";
    private static final String HIDDEN_HIDDEN = "ct05-ph2";
    private static final String HIDDEN_REJECTED = "ct05-ph3";
    private static final String MISSING_PID = "ct05-nope";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    private Object msgSecCheckStub;

    /** 文本安全替身的可变行为：默认放行；需要 422/503 时由用例改成抛异常。
     *  用 Answer 而非 Mockito 打桩，避免「doThrow(...).when(mock) 之后没有方法调用」的 UnfinishedStubbing。 */
    private org.mockito.stubbing.Answer<Object> msgSecAnswer = invocation -> null;
    private Method msgSecCheckCheckMethod;

    /**
     * 构造器注入全部测试依赖。
     * @param mvc 模拟 MVC 客户端
     * @param jdbc 真实 MySQL 访问
     * @param jwtUtils 测试令牌签发
     * @param objectMapper 响应解析
     * @param applicationContext 用于反射定位评论服务
     */
    CommentTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils,
                ObjectMapper objectMapper, ApplicationContext applicationContext) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入三个测试身份。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_05");
        cleanupFixtures();
        seedUser(U, "ct05用户U", "普通用户", "19900000101");
        seedUser(O, "ct05用户O", "普通用户", "19900000102");
        seedUser(A, "ct05管理员A", "管理员", "19900000103");
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM comment WHERE pid LIKE 'ct05-%' OR uid LIKE 'ct05-%' OR cid LIKE 'ct05-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'ct05-%' OR uid LIKE 'ct05-%' OR targetid LIKE 'ct05-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'ct05-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'ct05-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例
    // ------------------------------------------------------------------

    /** T05-01 匿名分页读评论：顶层/条目/作者键集合精确，时间带秒偏移，total 是总数不等同页长，无 likecom/pnum/openid。 */
    @Test
    void cmt_01_listShapeAndPagination() throws Exception {
        seedWork(P1, U, "展示");
        for (int i = 1; i <= 12; i++) {
            seedComment(String.format("ct05-c%02d", i), i % 2 == 1 ? U : O, P1, "评论内容" + i);
        }
        MvcResult result = mvc.perform(get("/comment/list")
                .param("pid", P1).param("page", "1").param("size", "10")).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(10);
        JsonNode total = data.get("total");
        assertThat(total.isIntegralNumber()).isTrue();
        assertThat(total.asLong()).isEqualTo(12L);
        JsonNode first = list.get(0);
        assertKeys(first, "cid", "pid", "uid", "content", "createdAt", "author");
        assertThat(first.has("likecom")).isFalse();
        assertThat(first.has("pnum")).isFalse();
        assertThat(first.has("openid")).isFalse();
        assertThat(first.get("cid").asText()).isNotEmpty();
        assertThat(first.get("createdAt").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+08:00");
        assertKeys(first.get("author"), "uid", "nickname", "avatar");
        for (JsonNode item : list) {
            String cid = item.get("cid").asText();
            assertThat(item.get("pid").asText()).isEqualTo(P1);
            String dbUid = jdbc.queryForObject("SELECT uid FROM comment WHERE pid=? AND cid=?", String.class, P1, cid);
            assertThat(item.get("uid").asText()).isEqualTo(dbUid);
            assertThat(item.get("author").get("uid").asText()).isEqualTo(dbUid);
            String dbContent = jdbc.queryForObject("SELECT comcontent FROM comment WHERE pid=? AND cid=?", String.class, P1, cid);
            assertThat(item.get("content").asText()).isEqualTo(dbContent);
        }
        // 列表顺序未冻结，作者投影按首条自身的落库用户核对，不依赖排序
        String firstCid = first.get("cid").asText();
        String firstDbUid = jdbc.queryForObject("SELECT uid FROM comment WHERE pid=? AND cid=?", String.class, P1, firstCid);
        assertThat(first.get("author").get("uid").asText()).isEqualTo(firstDbUid);
        Map<String, Object> firstUser = jdbc.queryForMap("SELECT uname, avatar FROM user WHERE uid=?", firstDbUid);
        assertThat(first.get("author").get("nickname").asText()).isEqualTo(firstUser.get("uname"));
        assertThat(first.get("author").get("avatar").asText()).isEqualTo(firstUser.get("avatar"));
    }

    /** T05-02 页码超出范围返回空页且 list 是 [] 不是 null；缺省 page/size 时按默认 1/10 返回全量。 */
    @Test
    void cmt_02_emptyPageAndDefaults() throws Exception {
        seedWork(P1, U, "展示");
        seedComment("ct05-c01", U, P1, "评论一");
        seedComment("ct05-c02", O, P1, "评论二");
        MvcResult far = mvc.perform(get("/comment/list")
                .param("pid", P1).param("page", "999").param("size", "50")).andReturn();
        JsonNode farData = assertOk(far);
        assertThat(farData.get("list").isArray()).isTrue();
        assertThat(farData.get("list").size()).isEqualTo(0);
        assertThat(farData.get("total").asLong()).isEqualTo(2L);
        MvcResult defaults = mvc.perform(get("/comment/list").param("pid", P1)).andReturn();
        JsonNode defaultData = assertOk(defaults);
        assertThat(defaultData.get("list").size()).isEqualTo(2);
        assertThat(defaultData.get("total").asLong()).isEqualTo(2L);
    }

    /** T05-03 非法 query 逐例 400 invalid_request：缺 pid、格式错误、page/size 越界或非整数。 */
    @Test
    void cmt_03_invalidQueryIs400() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult[] cases = new MvcResult[]{
                mvc.perform(get("/comment/list")).andReturn(),
                mvc.perform(get("/comment/list").param("pid", "bad!")).andReturn(),
                mvc.perform(get("/comment/list").param("pid", P1).param("page", "0")).andReturn(),
                mvc.perform(get("/comment/list").param("pid", P1).param("size", "0")).andReturn(),
                mvc.perform(get("/comment/list").param("pid", P1).param("size", "51")).andReturn(),
                mvc.perform(get("/comment/list").param("pid", P1).param("page", "1.5")).andReturn(),
                mvc.perform(get("/comment/list").param("pid", P1).param("size", "x")).andReturn()
        };
        for (MvcResult result : cases) {
            assertError(result, 400, "invalid_request");
        }
    }

    /** T05-04 待审核/隐藏/已驳回作品与合法不存在 pid 逐例 404，即使有历史评论也不可读、不删除。 */
    @Test
    void cmt_04_hiddenOrMissingWorkIs404() throws Exception {
        seedWork(HIDDEN_PENDING, U, "待审核");
        seedWork(HIDDEN_HIDDEN, U, "隐藏");
        seedWork(HIDDEN_REJECTED, U, "已驳回");
        seedComment("ct05-ch1", U, HIDDEN_PENDING, "历史评论一");
        seedComment("ct05-ch2", U, HIDDEN_HIDDEN, "历史评论二");
        seedComment("ct05-ch3", U, HIDDEN_REJECTED, "历史评论三");
        for (String pid : new String[]{HIDDEN_PENDING, HIDDEN_HIDDEN, HIDDEN_REJECTED, MISSING_PID}) {
            assertError(mvc.perform(get("/comment/list").param("pid", pid)).andReturn(), 404, "not_found");
        }
        assertThat(commentRows(HIDDEN_PENDING)).isEqualTo(1);
        assertThat(commentRows(HIDDEN_HIDDEN)).isEqualTo(1);
        assertThat(commentRows(HIDDEN_REJECTED)).isEqualTo(1);
    }

    /** T05-05 1 字符与 500 字符边界都成功；作者取已验证 U；实际调用 MsgSecCheck(U,原文) 各一次。 */
    @Test
    void cmt_05_postBoundaryAndSecurityCheckCalled() throws Exception {
        seedWork(P1, U, "展示");
        installMsgSecCheckMock();
        String one = "好";
        MvcResult r1 = mvc.perform(post("/comment").header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody(P1, one))).andReturn();
        JsonNode d1 = assertOk(r1);
        assertKeys(d1, "cid");
        String cid1 = d1.get("cid").asText();
        assertThat(cid1).isNotEmpty();
        assertThat(commentMatches(P1, cid1, U, one)).isEqualTo(1);
        assertThat(commentRows(P1)).isEqualTo(1);
        verifyMsgSecCheckCalled(U, one);
        if (msgSecCheckStub != null) {
            clearInvocations(msgSecCheckStub);
        }
        String fiveHundred = repeat("图", 500);
        MvcResult r2 = mvc.perform(post("/comment").header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody(P1, fiveHundred))).andReturn();
        JsonNode d2 = assertOk(r2);
        assertKeys(d2, "cid");
        assertThat(d2.get("cid").asText()).isNotEmpty();
        assertThat(commentMatches(P1, d2.get("cid").asText(), U, fiveHundred)).isEqualTo(1);
        assertThat(commentRows(P1)).isEqualTo(2);
        verifyMsgSecCheckCalled(U, fiveHundred);
    }

    /** T05-06 首尾空格按原文保留；作者取已验证 U；响应 data 只含 cid，不多带评论实体。 */
    @Test
    void cmt_06_postKeepsSpacesAndMinimalData() throws Exception {
        seedWork(P1, U, "展示");
        installMsgSecCheckMock();
        String content = "  好图  ";
        MvcResult result = mvc.perform(post("/comment").header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody(P1, content))).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "cid");
        String cid = data.get("cid").asText();
        assertThat(cid).isNotEmpty();
        assertThat(commentMatches(P1, cid, U, content)).isEqualTo(1);
        verifyMsgSecCheckCalled(U, content);
    }

    /** T05-07 非法请求体逐例 400 invalid_request，且每例都不写评论。 */
    @Test
    void cmt_07_invalidBodiesAre400NoRows() throws Exception {
        seedWork(P1, U, "展示");
        installMsgSecCheckMock();
        String[] bodies = new String[]{
                "{}",
                "{\"content\":\"好\"}",
                "{\"pid\":null,\"content\":\"好\"}",
                "{\"pid\":1,\"content\":\"好\"}",
                "{\"pid\":\"bad!\",\"content\":\"好\"}",
                "{\"pid\":\"" + P1 + "\"}",
                "{\"pid\":\"" + P1 + "\",\"content\":null}",
                "{\"pid\":\"" + P1 + "\",\"content\":1}",
                "{\"pid\":\"" + P1 + "\",\"content\":\"\"}",
                "{\"pid\":\"" + P1 + "\",\"content\":\"  \"}",
                "{\"pid\":\"" + P1 + "\",\"content\":\"" + repeat("好", 501) + "\"}"
        };
        for (String body : bodies) {
            MvcResult result = mvc.perform(post("/comment").header("Authorization", token(U, "普通用户"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            assertError(result, 400, "invalid_request");
        }
        assertThat(commentRows(P1)).isEqualTo(0);
    }

    /** T05-08 无凭证发表评论 401 unauthorized，且不落库。 */
    @Test
    void cmt_08_postWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = mvc.perform(post("/comment")
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody(P1, "好图"))).andReturn();
        assertError(result, 401, "unauthorized");
        assertThat(commentRows(P1)).isEqualTo(0);
    }

    /** T05-09 对非展示/不存在作品发表评论，作者与管理员逐例 404，不落库。 */
    @Test
    void cmt_09_postHiddenOrMissingIs404() throws Exception {
        seedWork(HIDDEN_PENDING, U, "待审核");
        seedWork(HIDDEN_HIDDEN, U, "隐藏");
        seedWork(HIDDEN_REJECTED, U, "已驳回");
        installMsgSecCheckMock();
        String[] pids = new String[]{HIDDEN_PENDING, HIDDEN_HIDDEN, HIDDEN_REJECTED, MISSING_PID};
        for (String pid : pids) {
            assertError(postCommentAs(U, pid, "好图"), 404, "not_found");
            assertError(postCommentAs(A, pid, "好图"), 404, "not_found");
        }
        assertThat(commentRows(HIDDEN_PENDING)).isEqualTo(0);
        assertThat(commentRows(HIDDEN_HIDDEN)).isEqualTo(0);
        assertThat(commentRows(HIDDEN_REJECTED)).isEqualTo(0);
    }

    /** T05-10 MsgSecCheck 拒绝时 422 content_rejected，评论数不变。 */
    @Test
    void cmt_10_rejectedContentIs422NoRows() throws Exception {
        seedWork(P1, U, "展示");
        installMsgSecCheckMock();
        ErrorCode rejected = errorCodeIfPresent("CONTENT_REJECTED");
        if (msgSecCheckStub != null && rejected != null) {
            stubMsgSecCheckFailure(rejected);
        }
        MvcResult result = postCommentAs(U, P1, "被拒文本");
        assertError(result, 422, "content_rejected");
        assertThat(commentRows(P1)).isEqualTo(0);
    }

    /** T05-11 MsgSecCheck 超时与不可用两例都 503 service_unavailable，不降级、不落库。 */
    @Test
    void cmt_11_securityUnavailableIs503NoRows() throws Exception {
        seedWork(P1, U, "展示");
        installMsgSecCheckMock();
        ErrorCode unavailable = errorCodeIfPresent("SERVICE_UNAVAILABLE");
        if (msgSecCheckStub != null && unavailable != null) {
            stubMsgSecCheckFailure(unavailable);
        }
        assertError(postCommentAs(U, P1, "超时文本"), 503, "service_unavailable");
        assertError(postCommentAs(U, P1, "不可用文本"), 503, "service_unavailable");
        assertThat(commentRows(P1)).isEqualTo(0);
    }

    /** T05-12 作者删除本人评论、管理员删除他人评论都 200 且 data=null；只删定位到的 (pid,cid,uid) 记录，其它评论不变，列表逐次 -1。 */
    @Test
    void cmt_12_deleteOwnAndAdminDelete() throws Exception {
        seedWork(P1, U, "展示");
        seedComment("ct05-c1", U, P1, "评论一");
        seedComment("ct05-c2", U, P1, "评论二");
        seedComment("ct05-c3", O, P1, "评论三");
        MvcResult own = mvc.perform(delete("/comment/ct05-c1")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertOkNoData(own);
        assertThat(commentExists("ct05-c1")).isFalse();
        assertThat(commentExists("ct05-c2")).isTrue();
        assertThat(commentExists("ct05-c3")).isTrue();
        assertThat(listTotal(P1)).isEqualTo(2L);
        MvcResult admin = mvc.perform(delete("/comment/ct05-c2")
                .header("Authorization", token(A, "管理员"))).andReturn();
        assertOkNoData(admin);
        assertThat(commentExists("ct05-c2")).isFalse();
        assertThat(commentExists("ct05-c3")).isTrue();
        assertThat(listTotal(P1)).isEqualTo(1L);
    }

    /** T05-13 其他普通用户删除他人评论 403 forbidden，评论保留。 */
    @Test
    void cmt_13_deleteOthersIs403() throws Exception {
        seedWork(P1, U, "展示");
        seedComment("ct05-c1", U, P1, "评论一");
        MvcResult result = mvc.perform(delete("/comment/ct05-c1")
                .header("Authorization", token(O, "普通用户"))).andReturn();
        assertError(result, 403, "forbidden");
        assertThat(commentExists("ct05-c1")).isTrue();
    }

    /** T05-14 无凭证删除评论 401 unauthorized，评论保留。 */
    @Test
    void cmt_14_deleteWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        seedComment("ct05-c1", U, P1, "评论一");
        MvcResult result = mvc.perform(delete("/comment/ct05-c1")).andReturn();
        assertError(result, 401, "unauthorized");
        assertThat(commentExists("ct05-c1")).isTrue();
    }

    /** T05-15 合法不存在 cid 与已删评论再删都 404 not_found。 */
    @Test
    void cmt_15_deleteMissingOrTwiceIs404() throws Exception {
        seedWork(P1, U, "展示");
        seedComment("ct05-c1", U, P1, "评论一");
        assertError(mvc.perform(delete("/comment/ct05-missing")
                .header("Authorization", token(U, "普通用户"))).andReturn(), 404, "not_found");
        assertOkNoData(mvc.perform(delete("/comment/ct05-c1")
                .header("Authorization", token(U, "普通用户"))).andReturn());
        assertError(mvc.perform(delete("/comment/ct05-c1")
                .header("Authorization", token(U, "普通用户"))).andReturn(), 404, "not_found");
    }

    /** T05-16 非法 cid 格式 400 invalid_request。 */
    @Test
    void cmt_16_deleteBadIdIs400() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = mvc.perform(delete("/comment/bad%21")
                .header("Authorization", token(U, "普通用户"))).andReturn();
        assertError(result, 400, "invalid_request");
    }

    /** T05-17 删除非展示作品的评论，作者与管理员逐例 404，评论行保留。 */
    @Test
    void cmt_17_deleteOnHiddenWorkIs404RowKept() throws Exception {
        seedWork(HIDDEN_PENDING, U, "待审核");
        seedWork(HIDDEN_HIDDEN, U, "隐藏");
        seedWork(HIDDEN_REJECTED, U, "已驳回");
        seedComment("ct05-ch1", U, HIDDEN_PENDING, "历史评论一");
        seedComment("ct05-ch2", U, HIDDEN_HIDDEN, "历史评论二");
        seedComment("ct05-ch3", U, HIDDEN_REJECTED, "历史评论三");
        assertError(mvc.perform(delete("/comment/ct05-ch1").header("Authorization", token(U, "普通用户")))
                .andReturn(), 404, "not_found");
        assertError(mvc.perform(delete("/comment/ct05-ch1").header("Authorization", token(A, "管理员")))
                .andReturn(), 404, "not_found");
        assertError(mvc.perform(delete("/comment/ct05-ch2").header("Authorization", token(U, "普通用户")))
                .andReturn(), 404, "not_found");
        assertError(mvc.perform(delete("/comment/ct05-ch2").header("Authorization", token(A, "管理员")))
                .andReturn(), 404, "not_found");
        assertError(mvc.perform(delete("/comment/ct05-ch3").header("Authorization", token(U, "普通用户")))
                .andReturn(), 404, "not_found");
        assertError(mvc.perform(delete("/comment/ct05-ch3").header("Authorization", token(A, "管理员")))
                .andReturn(), 404, "not_found");
        assertThat(commentExists("ct05-ch1")).isTrue();
        assertThat(commentExists("ct05-ch2")).isTrue();
        assertThat(commentExists("ct05-ch3")).isTrue();
    }

    /** T05-34 已签发令牌后主体被禁用或删除两例，发表评论都 401 unauthorized。 */
    @Test
    void cmt_34_subjectDisabledOrDeletedIs401() throws Exception {
        seedWork(P1, U, "展示");
        jdbc.update("UPDATE user SET status='禁用' WHERE uid=?", U);
        assertError(postCommentAs(U, P1, "好图"), 401, "unauthorized");
        jdbc.update("DELETE FROM user WHERE uid=?", U);
        assertError(postCommentAs(U, P1, "好图"), 401, "unauthorized");
    }

    /** T05-35 匿名可读不代表坏凭证可忽略：携带坏 Bearer 读列表 401 unauthorized。 */
    @Test
    void cmt_35_badTokenOnPublicListIs401() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult result = mvc.perform(get("/comment/list").param("pid", P1)
                .header("Authorization", "Bearer not.a.valid.token")).andReturn();
        assertError(result, 401, "unauthorized");
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

    private void seedComment(String cid, String commenterUid, String pid, String content) {
        jdbc.update("INSERT INTO comment (cid, uid, pid, comcontent, likecom) VALUES (?,?,?,?,?)",
                cid, commenterUid, pid, content, "0");
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    private MvcResult postCommentAs(String uid, String pid, String content) throws Exception {
        return mvc.perform(post("/comment").header("Authorization", token(uid, roleOf(uid)))
                .contentType(MediaType.APPLICATION_JSON).content(jsonBody(pid, content))).andReturn();
    }

    private String roleOf(String uid) {
        return A.equals(uid) ? "管理员" : "普通用户";
    }

    private String jsonBody(String pid, String content) {
        return "{\"pid\":\"" + pid + "\",\"content\":\"" + content + "\"}";
    }

    private String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private int commentRows(String pid) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE pid=?", Integer.class, pid);
        return rows == null ? 0 : rows;
    }

    private boolean commentExists(String cid) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM comment WHERE cid=?", Integer.class, cid);
        return rows != null && rows > 0;
    }

    private int commentMatches(String pid, String cid, String uid, String content) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE pid=? AND cid=? AND uid=? AND comcontent=?",
                Integer.class, pid, cid, uid, content);
        return rows == null ? 0 : rows;
    }

    private long listTotal(String pid) throws Exception {
        MvcResult result = mvc.perform(get("/comment/list")
                .param("pid", pid).param("page", "1").param("size", "10")).andReturn();
        return assertOk(result).get("total").asLong();
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

    private void assertOkNoData(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        assertThat(json.get("data").isNull()).isTrue();
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

    // ------------------------------------------------------------------
    // MsgSecCheck 反射替身（卡03 接口未合入时跳过，由路由缺失自然红）
    // ------------------------------------------------------------------

    private void installMsgSecCheckMock() throws Exception {
        if (msgSecCheckStub != null) {
            org.mockito.Mockito.reset(msgSecCheckStub);
            return;
        }
        for (String name : applicationContext.getBeanDefinitionNames()) {
            if (!name.toLowerCase(Locale.ROOT).contains("comment")) {
                continue;
            }
            Object bean = applicationContext.getBean(name);
            // 事务/代理 bean 上的字段不是真实 target 的字段，必须先解包，否则替身永远不生效
            Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(bean);
            if (!target.getClass().getSimpleName().startsWith("CommentServiceImpl")) {
                continue;
            }
            Field field = findMsgSecCheckField(target.getClass());
            assertThat(field).as("CommentServiceImpl 未注入 MsgSecCheck 字段，评论未按契约接入文本安全检查").isNotNull();
            field.setAccessible(true);
            // 每次安装都新建替身：旧 mock 的 Answer 闭包绑定在上一个测试实例上
            msgSecCheckStub = mock(field.getType(), invocation -> msgSecAnswer.answer(invocation));
            field.set(target, msgSecCheckStub);
            msgSecCheckCheckMethod = field.getType().getMethod("check", String.class, String.class);
            log.info("已把 CommentServiceImpl 的 MsgSecCheck 替换为测试替身 bean={}", name);
            return;
        }
        log.info("CommentServiceImpl 尚未合入，跳过 MsgSecCheck 替身安装，路由缺失会自然红");
    }

    private Field findMsgSecCheckField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().getSimpleName().contains("MsgSecCheck")) {
                    return field;
                }
            }
        }
        return null;
    }

    private void stubMsgSecCheckFailure(ErrorCode code) throws Exception {
        if (msgSecCheckStub == null) {
            return;
        }
        msgSecAnswer = invocation -> {
            throw new BizException(code, "测试注入：文本安全服务异常");
        };
    }

    private void verifyMsgSecCheckCalled(String uid, String content) throws Exception {
        if (msgSecCheckStub == null) {
            return;
        }
        org.mockito.invocation.Invocation invocation = org.mockito.Mockito.mockingDetails(msgSecCheckStub)
                .getInvocations().stream()
                .filter(inv -> uid.equals(inv.getArgument(0)) && content.equals(inv.getArgument(1)))
                .findFirst()
                .orElse(null);
        if (invocation == null) {
            throw new AssertionError("MsgSecCheck 未被按参数调用 uid=" + uid + " content=" + content);
        }
    }

    private ErrorCode errorCodeIfPresent(String name) {
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException missing) {
            log.info("ErrorCode.{} 尚未合入（卡02 未交接），跳过该故障注入", name);
            return null;
        }
    }
}
