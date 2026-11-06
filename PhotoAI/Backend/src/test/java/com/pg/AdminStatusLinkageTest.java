package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 管理端 v2.1 联动行为测试：契约见 docs/接口增补_ADMIN.md §5.2（禁用用户的作品联动）
 * 与 §5.3（审核通过遇到被禁用作者）。
 *
 * 设计约束（与 AdminApiTest 同口径）：
 * 1. 只通过 HTTP 打接口，不 import 任何尚未实现的类；实现缺失时表现为 404 或断言失败，
 *    而不是编译失败。
 * 2. 只连 db_pg_test_04，夹具统一 as- 前缀（不使用 ad-，那条前缀由 AdminApiTest 负责清理），
 *    本类自建自清，不清空、不 TRUNCATE 共享表。
 * 3. 夹具的 cwtime 全部落在未来时间，避免与库里既有种子数据相互干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminStatusLinkageTest {

    /** 测试库固定 db_pg_test_04，与其余管理端测试共用；加锁等待上限避免死锁挂死。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_04?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 身份样本：ADM 管理员，U 待禁用作者，D 无作品对照用户，O 其它用户。 */
    private static final String ADM = "as-adm";
    private static final String U = "as-u";
    private static final String D = "as-d";
    private static final String O = "as-o";

    /** 作品样本：W1..W4 属于 U，四状态各一件；W5 属于 O 且为展示。 */
    private static final String W1 = "as-w1";
    private static final String W2 = "as-w2";
    private static final String W3 = "as-w3";
    private static final String W4 = "as-w4";
    private static final String W5 = "as-w5";

    /** 契约冻结的键集合。 */
    private static final Set<String> STATUS_DATA_KEYS = new HashSet<>(Arrays.asList("uid", "status"));
    private static final Set<String> DECIDE_KEYS = new HashSet<>(Arrays.asList("pid", "status"));

    /** §5.3 冻结的驳回文案与审核日志口径。 */
    private static final String AUTO_REJECT_MSG = "作者账号已被禁用，已自动驳回";
    private static final String AUTO_REJECT_REASON = "作者账号已被禁用";

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
    AdminStatusLinkageTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /** 每例先校验库名、清理本类残留，再写入 4 用户 + 5 作品的标准夹具。 */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_04");
        cleanup();
        seedUser(ADM, "as管理员", "管理员", "正常");
        seedUser(U, "as作者", "普通用户", "正常");
        seedUser(D, "as待禁用", "普通用户", "正常");
        seedUser(O, "as用户O", "普通用户", "正常");
        seedWork(W1, U, "展示", "2099-04-01 00:00:01");
        seedWork(W2, U, "待审核", "2099-04-01 00:00:02");
        seedWork(W3, U, "隐藏", "2099-04-01 00:00:03");
        seedWork(W4, U, "已驳回", "2099-04-01 00:00:04");
        seedWork(W5, O, "展示", "2099-04-01 00:00:05");
    }

    /**
     * 按外键依赖顺序清理本类夹具。
     * 测试库对 user 有 RESTRICT 外键（comment/favorite/follow/liketable），必须先把子表清空再删用户；
     * 只清理 as- 前缀，绝不触碰 ad- 前缀（由 AdminApiTest 自己负责）。
     */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'as-%' OR pid LIKE 'as-%' OR uadmin LIKE 'as-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'as-%' OR uid LIKE 'as-%' OR targetid LIKE 'as-%'");
        jdbc.update("DELETE FROM follow WHERE fid LIKE 'as-%' OR uid LIKE 'as-%' OR tuid LIKE 'as-%'");
        jdbc.update("DELETE FROM comment WHERE cid LIKE 'as-%' OR uid LIKE 'as-%' OR pid LIKE 'as-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'as-%' OR uid LIKE 'as-%' OR pid LIKE 'as-%'");
        jdbc.update("DELETE FROM vote WHERE vid LIKE 'as-%' OR uid LIKE 'as-%' OR pid LIKE 'as-%'");
        jdbc.update("DELETE FROM pcatogory WHERE pcid LIKE 'as-%' OR uid LIKE 'as-%' OR pid LIKE 'as-%'");
        jdbc.update("DELETE FROM work_embedding WHERE pid LIKE 'as-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'as-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'as-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'as-%'");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void seedUser(String uid, String uname, String role, String status) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                uid, uname, null, role, "as-test-password", "/avatar/" + uid + ".png", "0", status, "as-bio-" + uid);
    }

    private void seedWork(String pid, String uid, String status, String cwtime) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, cwtime, likep) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                pid, uid, "as标题" + pid, "as描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                7, 3, status, cwtime, 2);
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    // ------------------------------------------------------------------
    // §5.2 禁用联动
    // ------------------------------------------------------------------

    /** §5.2：禁用只把目标用户的"展示"作品改为"隐藏"，其余状态与其它用户一律不动。 */
    @Test
    void as_01_disableUserHidesVisibleWorks() throws Exception {
        // 对照：先禁用名下无作品的 AS-D，AS-U 的作品必须完全不受牵连（联动只作用于目标用户）。
        assertOk(perform(put("/admin/user/" + D + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"禁用\"}")));
        assertThat(statusOf(W1)).as("禁用他人不得影响 AS-U 的展示作品").isEqualTo("展示");

        JsonNode data = assertOk(perform(put("/admin/user/" + U + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"禁用\"}")));
        // 成功返回仍为 {uid,status}，不加字段。
        assertThat(keysOf(data)).isEqualTo(STATUS_DATA_KEYS);
        assertThat(data.get("uid").asText()).isEqualTo(U);
        assertThat(data.get("status").asText()).isEqualTo("禁用");
        assertThat(userStatus(U)).isEqualTo("禁用");

        assertThat(statusOf(W1)).as("展示必须联动改为隐藏").isEqualTo("隐藏");
        assertThat(statusOf(W2)).as("待审核保持原状").isEqualTo("待审核");
        assertThat(statusOf(W3)).as("隐藏保持原状").isEqualTo("隐藏");
        assertThat(statusOf(W4)).as("已驳回保持原状").isEqualTo("已驳回");
        assertThat(statusOf(W5)).as("他人作品不受影响").isEqualTo("展示");
    }

    /** §5.2：解禁不自动恢复作品，AS-W1 在禁用→启用后仍为"隐藏"。 */
    @Test
    void as_02_enableDoesNotRestoreWorks() throws Exception {
        putStatus(U, "禁用");
        assertThat(statusOf(W1)).isEqualTo("隐藏");

        JsonNode data = assertOk(putStatusResult(U, "正常"));
        assertThat(data.get("uid").asText()).isEqualTo(U);
        assertThat(data.get("status").asText()).isEqualTo("正常");
        assertThat(userStatus(U)).as("账号已恢复正常").isEqualTo("正常");
        assertThat(statusOf(W1)).as("解禁不得自动恢复作品").isEqualTo("隐藏");
        assertThat(statusOf(W2)).isEqualTo("待审核");
        assertThat(statusOf(W4)).isEqualTo("已驳回");
    }

    // ------------------------------------------------------------------
    // §5.3 审核通过遇到被禁用作者
    // ------------------------------------------------------------------

    /** §5.3：作者被禁用时通过审核自动驳回，返回 409 固定文案，并只追加一条 reject 日志。 */
    @Test
    void as_03_approveDisabledAuthorAutoRejects() throws Exception {
        putStatus(U, "禁用");
        assertThat(auditCount(W2)).as("前置条件：AS-W2 无审核日志").isEqualTo(0);

        MvcResult result = perform(put("/admin/work/" + W2 + "/approve")
                .header("Authorization", token(ADM, "管理员")));
        JsonNode body = parseBody(result);
        assertThat(result.getResponse().getStatus()).as("期望 409，响应=%s", body).isEqualTo(409);
        assertThat(body.get("code").asText()).isEqualTo("author_disabled");
        assertThat(body.get("data").isNull()).as("失败时 data 必须为 null").isTrue();

        assertThat(statusOf(W2)).as("待审核必须自动改为已驳回").isEqualTo("已驳回");
        assertThat(statusOf(W1)).as("联动隐藏不受审核影响").isEqualTo("隐藏");

        List<String> reasons = jdbc.queryForList(
                "SELECT reason FROM waudit WHERE pid = ? AND act = 'reject'"
                        + " AND reason = ? AND uadmin = ?",
                String.class, W2, AUTO_REJECT_REASON, ADM);
        assertThat(reasons).as("必须且只能新增一条指定口径的 reject 日志").hasSize(1);
        assertThat(auditCount(W2)).as("不得追加其它审核日志（如同一条 pass）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM waudit WHERE pid = ? AND act = 'pass'", Integer.class, W2)).isEqualTo(0);
    }

    /** §5.3：作者状态正常时通过审核走原逻辑，返回 200 且 data 恰为 {pid,status:"展示"}。 */
    @Test
    void as_04_approveNormalAuthorStillWorks() throws Exception {
        assertThat(userStatus(U)).as("前置条件：作者正常").isEqualTo("正常");

        JsonNode data = assertOk(perform(put("/admin/work/" + W2 + "/approve")
                .header("Authorization", token(ADM, "管理员"))));
        assertThat(keysOf(data)).isEqualTo(DECIDE_KEYS);
        assertThat(data.get("pid").asText()).isEqualTo(W2);
        assertThat(data.get("status").asText()).isEqualTo("展示");
        assertThat(statusOf(W2)).isEqualTo("展示");
        assertThat(statusOf(W1)).as("作者正常时不得触发作品联动").isEqualTo("展示");
    }

    /** §5.2/§5：管理员禁用自己 403，且不产生任何作品联动副作用。 */
    @Test
    void as_05_selfDisableForbiddenNoSideEffect() throws Exception {
        MvcResult result = perform(put("/admin/user/" + ADM + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"禁用\"}"));
        assertError(result, 403, "forbidden");

        assertThat(userStatus(ADM)).as("管理员自身状态不得改变").isEqualTo("正常");
        // 管理员名下若无展示作品则跳过该项断言；有则必须保持原状态。
        List<String> mine = jdbc.queryForList(
                "SELECT wstatus FROM pwork WHERE uid = ? AND wstatus = '展示'", String.class, ADM);
        if (!mine.isEmpty()) {
            assertThat(mine).as("自我禁用被拒后自己的展示作品不得被隐藏")
                    .containsOnly("展示");
        }
        assertThat(statusOf(W1)).as("自我禁用不得牵连他人作品").isEqualTo("展示");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 走接口禁用/启用用户并要求成功，用于把前置条件收敛成一次调用。 */
    private void putStatus(String uid, String status) throws Exception {
        assertOk(putStatusResult(uid, status));
    }

    private MvcResult putStatusResult(String uid, String status) throws Exception {
        return perform(put("/admin/user/" + uid + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
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

    private String statusOf(String pid) {
        return jdbc.queryForObject("SELECT wstatus FROM pwork WHERE pid = ?", String.class, pid);
    }

    private String userStatus(String uid) {
        return jdbc.queryForObject("SELECT status FROM user WHERE uid = ?", String.class, uid);
    }

    private int auditCount(String pid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM waudit WHERE pid = ?", Integer.class, pid);
    }
}
