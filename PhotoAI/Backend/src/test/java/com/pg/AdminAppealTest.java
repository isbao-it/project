package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
import com.pg.service.WeChatClient;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 封禁申诉接口测试：契约见 docs/接口增补_ADMIN.md §7（匿名提交 §7.1、管理员处理 §7.3）。
 *
 * 设计约束（与 AdminStatusLinkageTest 同口径）：
 * 1. 只通过 HTTP 打接口，不 import 任何尚未实现的控制器/服务类；实现缺失时表现为 404 或断言失败，
 *    而不是编译失败。
 * 2. 只连 db_pg_test_04，夹具统一 ap- 前缀，本类自建自清，不清空、不 TRUNCATE 共享表。
 * 3. 微信身份用 Mockito 打桩，绝不真的连微信服务器：code2Session 的返回值决定 openid，
 *    从而可控地构造"封禁用户 / 正常用户 / openid 不存在"三种分支。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminAppealTest {

    /** 测试库固定 db_pg_test_04，与其余管理端测试共用；加锁等待上限避免死锁挂死。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_04?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 身份样本：ADM 管理员，U1 被封禁的申诉人。 */
    private static final String ADM = "ap-adm";
    private static final String U1 = "ap-u1";

    /** 微信身份样本：U1 的 openid 与换取它的 code；NONE 用于"openid 查不到用户"分支。 */
    private static final String OPENID1 = "ap-openid-1";
    private static final String CODE1 = "ap-code-1";
    private static final String OPENID_NONE = "ap-openid-none";

    /** 申诉样本主键（管理员处理用例直接落库，避免依赖提交接口是否已实现）。 */
    private static final String A1 = "ap-a1";

    /** §7.1 与 §7.3 冻结的成功返回键集合，两处都恰为 {aid,status}。 */
    private static final Set<String> DATA_KEYS = new HashSet<>(Arrays.asList("aid", "status"));

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;

    /** 微信 code2session 客户端打桩：替换真实实现，用例里按需指定 code 返回哪个 openid。 */
    @MockBean
    private WeChatClient weChatClient;

    /**
     * 构造器注入全部测试依赖。
     * @param mvc 模拟 MVC 客户端
     * @param jdbc 真实 MySQL 访问
     * @param jwtUtils 测试令牌签发
     * @param objectMapper 请求体与响应解析
     */
    AdminAppealTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /** 每例先校验库名、清理 ap- 残留，再写入「1 管理员 + 1 被封禁用户」的最小夹具。 */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_04");
        cleanup();
        seedUser(ADM, "ap管理员", "管理员", "正常", null);
        seedUser(U1, "ap申诉人", "普通用户", "禁用", OPENID1);
    }

    /**
     * 按依赖顺序清理本类夹具。
     * appeal 表对 user 无外键，必须先删 appeal 再删 user，否则残留的申诉行会污染下一例的计数断言；
     * 只清理 ap- 前缀，绝不触碰 as- / ad- 前缀（由其它测试类各自负责）。
     */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM appeal WHERE aid LIKE 'ap-%' OR uid LIKE 'ap-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'ap-%'");
    }

    // ------------------------------------------------------------------
    // §7.1 匿名提交
    // ------------------------------------------------------------------

    /** §7.1：被封禁用户凭 code 换 openid 匿名提交成功，200 返回 {aid, status:"待处理"}，原文入库。 */
    @Test
    void ap_01_anonymousSubmitSuccess() throws Exception {
        when(weChatClient.code2Session(CODE1)).thenReturn(OPENID1);
        String content = "请求解封";

        JsonNode data = assertOk(perform(postAppeal(CODE1, content)));
        // 成功体恰为冻结的两个键，不多给 openid/uid 之类字段。
        assertThat(keysOf(data)).isEqualTo(DATA_KEYS);
        String aid = data.get("aid").asText();
        assertThat(aid).as("aid 必须非空").isNotEmpty();
        assertThat(data.get("status").asText()).isEqualTo("待处理");

        assertThat(appealCount(U1)).as("提交一次应且只应落一行").isEqualTo(1);
        // content 不做裁剪：入库值与提交的原文逐字相等。
        assertThat(contentOf(aid)).as("入库 content 必须是提交原文").isEqualTo(content);
        assertThat(handlerOf(aid)).as("新提交的申诉尚未有人处理").isNull();
    }

    /** §7.1：状态为「正常」的用户没有封禁可申诉，返回 400 invalid_request 且不落库。 */
    @Test
    void ap_02_normalUserCannotAppeal() throws Exception {
        when(weChatClient.code2Session(CODE1)).thenReturn(OPENID1);
        setUserStatus(U1, "正常");
        assertThat(statusOfUser(U1)).as("前置条件：账号已恢复正常").isEqualTo("正常");

        MvcResult result = perform(postAppeal(CODE1, "请求解封"));
        // 只断言 HTTP 状态与错误码：§7.1 的"文案同上"无法从响应体校验，全局异常处理器只回传错误码固定文案。
        assertError(result, 400, "invalid_request");
        assertThat(appealCount(U1)).as("校验失败不得写入申诉").isEqualTo(0);
    }

    /** §7.1：openid 查不到用户 → 404 not_found，且申诉表不新增。 */
    @Test
    void ap_03_unknownOpenidTreatedAsInvalid() throws Exception {
        when(weChatClient.code2Session(CODE1)).thenReturn(OPENID_NONE);

        // 契约修订（docs/接口增补_ADMIN.md §7.1）：未知 openid 与"未封禁"统一 400，
        // 不用 404，否则状态码会泄露某个微信账号是否注册过。
        MvcResult result = perform(postAppeal(CODE1, "请求解封"));
        assertError(result, 400, "invalid_request");
        assertThat(appealTotal()).as("查不到用户时申诉表整体不新增").isEqualTo(0);
    }

    /** §7.1：同一被封用户 24 小时内已有「待处理」申诉，第二次提交 409 conflict 且不重复落库。 */
    @Test
    void ap_04_duplicatePendingConflicts() throws Exception {
        when(weChatClient.code2Session(CODE1)).thenReturn(OPENID1);

        assertOk(perform(postAppeal(CODE1, "第一次申诉")));
        assertThat(appealCount(U1)).as("前置条件：已有一条待处理").isEqualTo(1);

        MvcResult second = perform(postAppeal(CODE1, "第二次申诉"));
        // 重复待处理的判定必须早于"同一 openid 每分钟 1 次"的限流判定，否则客户端拿到的是限流而非 conflict。
        assertError(second, 409, "conflict");
        assertThat(appealCount(U1)).as("冲突提交不得再落一行").isEqualTo(1);
    }

    /** §7.1：content 必填、1–200 个 Unicode 字符且不得全空白，三种非法输入逐个 400 且都不落库。 */
    @Test
    void ap_05_invalidContentRejected() throws Exception {
        when(weChatClient.code2Session(CODE1)).thenReturn(OPENID1);
        assertThat(appealCount(U1)).as("前置条件：无申诉").isEqualTo(0);

        String tooLong = repeat('申', 201);
        assertThat(tooLong).as("夹具自检：必须正好 201 个字符").hasSize(201);

        assertError(perform(postAppeal(CODE1, "")), 400, "invalid_request");
        assertError(perform(postAppeal(CODE1, "   ")), 400, "invalid_request");
        assertError(perform(postAppeal(CODE1, tooLong)), 400, "invalid_request");

        assertThat(appealCount(U1)).as("非法 content 一律不得新增申诉").isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // §7.3 管理员处理
    // ------------------------------------------------------------------

    /** §7.3：管理员「接受」在同一事务内把申诉置为已受理、写 handler/hct，并把用户恢复为正常。 */
    @Test
    void ap_06_adminAcceptReenables() throws Exception {
        seedAppeal(A1, U1, "请求解封");

        JsonNode data = assertOk(perform(handle(A1, "{\"result\":\"接受\"}")));
        assertThat(keysOf(data)).isEqualTo(DATA_KEYS);
        assertThat(data.get("aid").asText()).isEqualTo(A1);
        assertThat(data.get("status").asText()).isEqualTo("已受理");

        assertThat(statusOf(A1)).isEqualTo("已受理");
        assertThat(handlerOf(A1)).as("接受必须写入处理管理员").isEqualTo(ADM);
        assertThat(jdbc.queryForObject("SELECT hct FROM appeal WHERE aid = ?", String.class, A1))
                .as("接受必须写入处理时间").isNotNull();
        assertThat(statusOfUser(U1)).as("接受后账号解禁回正常").isEqualTo("正常");
    }

    /** §7.3：管理员「驳回」置为已驳回并入库 reason，账号状态保持禁用（驳回不解封）。 */
    @Test
    void ap_07_adminRejectKeepsDisabled() throws Exception {
        seedAppeal(A1, U1, "请求解封");

        JsonNode data = assertOk(perform(handle(A1, "{\"result\":\"驳回\",\"reason\":\"理由不足\"}")));
        assertThat(keysOf(data)).isEqualTo(DATA_KEYS);
        assertThat(data.get("status").asText()).isEqualTo("已驳回");

        assertThat(statusOf(A1)).isEqualTo("已驳回");
        assertThat(reasonOf(A1)).as("驳回理由必须入库").isEqualTo("理由不足");
        assertThat(handlerOf(A1)).isEqualTo(ADM);
        assertThat(statusOfUser(U1)).as("驳回后账号仍是禁用").isEqualTo("禁用");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 按任务书冻结的列集合插入用户；openid 唯一键要求每例各用户的 openid 互不重复。 */
    private void seedUser(String uid, String uname, String role, String status, String openid) {
        jdbc.update("INSERT INTO user (uid, uname, role, pwd, avatar, vote, status, openid) VALUES (?,?,?,?,?,?,?,?)",
                uid, uname, role, "ap-test-password", "/avatar/" + uid + ".png", 0, status, openid);
    }

    /** 直接落一条「待处理」申诉，让管理员处理用例不依赖提交接口是否已实现。 */
    private void seedAppeal(String aid, String uid, String content) {
        jdbc.update("INSERT INTO appeal (aid, uid, content, status, ct) VALUES (?,?,?,?,NOW())",
                aid, uid, content, "待处理");
    }

    private void setUserStatus(String uid, String status) {
        jdbc.update("UPDATE user SET status = ? WHERE uid = ?", status, uid);
    }

    // ------------------------------------------------------------------
    // 请求与断言辅助
    // ------------------------------------------------------------------

    /** 用 objectMapper 组装 body，避免手工转义空白串与长串。 */
    private MockHttpServletRequestBuilder postAppeal(String code, String content) throws Exception {
        return post("/appeal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        objectMapper.createObjectNode().put("code", code).put("content", content)));
    }

    private MockHttpServletRequestBuilder handle(String aid, String body) {
        return put("/admin/appeal/" + aid + "/handle")
                .header("Authorization", "Bearer " + jwtUtils.generateToken(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn();
    }

    private JsonNode assertOk(MvcResult result) throws Exception {
        JsonNode body = parseBody(result);
        assertThat(result.getResponse().getStatus()).as("期望 200，响应=%s", body).isEqualTo(200);
        assertThat(body.get("code").asText()).isEqualTo("ok");
        return body.get("data");
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        JsonNode body = parseBody(result);
        assertThat(result.getResponse().getStatus()).as("期望 %s，响应=%s", status, body).isEqualTo(status);
        assertThat(body.get("code").asText()).as("错误码必须为 %s", code).isEqualTo(code);
        assertThat(body.get("data").isNull()).as("失败时 data 必须为 null").isTrue();
    }

    private JsonNode parseBody(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return content.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    private Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private String statusOfUser(String uid) {
        return jdbc.queryForObject("SELECT status FROM user WHERE uid = ?", String.class, uid);
    }

    private int appealCount(String uid) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM appeal WHERE uid = ?", Integer.class, uid);
        return count == null ? 0 : count;
    }

    private int appealTotal() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM appeal WHERE aid LIKE 'ap-%'", Integer.class);
        return count == null ? 0 : count;
    }

    private String contentOf(String aid) {
        return jdbc.queryForObject("SELECT content FROM appeal WHERE aid = ?", String.class, aid);
    }

    private String statusOf(String aid) {
        return jdbc.queryForObject("SELECT status FROM appeal WHERE aid = ?", String.class, aid);
    }

    private String handlerOf(String aid) {
        return jdbc.queryForObject("SELECT handler FROM appeal WHERE aid = ?", String.class, aid);
    }

    private String reasonOf(String aid) {
        return jdbc.queryForObject("SELECT reason FROM appeal WHERE aid = ?", String.class, aid);
    }

    private String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
