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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 管理端 v3 分类新增测试：契约见 docs/接口增补_ADMIN.md §8.1（POST /admin/category）
 * 与 §8.2（GET /category/list 行为不变）。
 *
 * 设计约束（与 AdminStatusLinkageTest 同口径）：
 * 1. 只通过 HTTP 打接口，不 import 任何尚未实现的类；实现缺失时表现为 404 或断言失败，
 *    而不是编译失败。
 * 2. 只连 db_pg_test_04，夹具统一 ac- 前缀（as-/ad- 由其它测试类负责），本类自建自清，
 *    不清空、不 TRUNCATE 共享表。
 * 3. pclist 无外键依赖，直接按 pcname 前缀删除；本类不写 pwork，也不触碰他人分类行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminCategoryTest {

    /** 测试库固定 db_pg_test_04，与其余管理端测试共用；加锁等待上限避免死锁挂死。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_04?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 身份样本：ADM 管理员，U 普通用户（越权对照）。 */
    private static final String ADM = "ac-adm";
    private static final String U = "ac-u";

    /** §8.1 冻结的成功返回键集合。 */
    private static final Set<String> CATEGORY_DATA_KEYS =
            new HashSet<>(Arrays.asList("pcid", "pcname", "sort"));

    /** 非法入参样本：空串、全空白、21 个字符（超 1–20 上限）。 */
    private static final String NAME_EMPTY = "";
    private static final String NAME_BLANK = "     ";
    private static final String NAME_TOO_LONG = "ac" + tail(19);

    /** 本类通过接口新建出来的 pcid，用于兜底清理（实现漏判时不留残行）。 */
    private final List<String> createdPcids = new ArrayList<String>();

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
    AdminCategoryTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /** 每例先校验库名、清理本类残留，再写入管理员与普通用户两个身份夹具。 */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_04");
        cleanup();
        seedUser(ADM, "ac管理员", "管理员", "正常");
        seedUser(U, "ac用户", "普通用户", "正常");
    }

    /** 每例结束按 ac- 前缀自清，绝不触碰 as-/ad- 前缀的其它测试夹具。 */
    @AfterEach
    void tearDown() {
        cleanup();
    }

    /**
     * 清理本类夹具：pclist 无外键，直接按 pcname 前缀删除；再按记录的 pcid 兜底删除，
     * 覆盖"全空白/空串"这类不含 ac 前缀的非法样本；用户按 uid 前缀删除。
     */
    private void cleanup() {
        jdbc.update("DELETE FROM pclist WHERE pcname LIKE 'ac%'");
        jdbc.update("DELETE FROM pclist WHERE pcname = ?", NAME_EMPTY);
        jdbc.update("DELETE FROM pclist WHERE pcname = ?", NAME_BLANK);
        for (String pcid : createdPcids) {
            jdbc.update("DELETE FROM pclist WHERE pcid = ?", pcid);
        }
        createdPcids.clear();
        jdbc.update("DELETE FROM user WHERE uid LIKE 'ac-%'");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void seedUser(String uid, String uname, String role, String status) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                uid, uname, null, role, "ac-test-password", "/avatar/" + uid + ".png", "0", status, "ac-bio-" + uid);
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    /** 生成 n 个字符的填充串（Java 8 无 String.repeat）。 */
    private static String tail(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // §8.1 分类新增
    // ------------------------------------------------------------------

    /** §8.1 + §8.2：新增分类返回 200 且 data 恰为 {pcid,pcname,sort}，pcname 与请求一致，并能从公开的 /category/list 查到同一个 pcid。 */
    @Test
    void ac_01_createCategoryAppearsInList() throws Exception {
        JsonNode data = assertOk(postCategory(ADM, "{\"pcname\":\"ac新分类\"}"));

        // 成功返回恰三键，不夹带 cds/ct 等额外字段。
        assertThat(keysOf(data)).isEqualTo(CATEGORY_DATA_KEYS);
        assertThat(data.get("pcname").asText()).isEqualTo("ac新分类");
        assertThat(data.get("pcid").isTextual()).as("pcid 必须是字符串").isTrue();
        // §8.1：pcid 由服务端生成 36 位（UUID 去横线）。
        String pcid = data.get("pcid").asText();
        assertThat(pcid).as("pcid 必须是服务端生成的 36 位串").hasSize(36);
        trackPcid(pcid);
        assertThat(data.get("sort").isIntegralNumber()).as("sort 必须是非负 JSON 整数").isTrue();
        assertThat(data.get("sort").asLong()).isNotNegative();

        // 库里确实落了一行，且与返回体一致。
        assertThat(jdbc.queryForObject("SELECT pcid FROM pclist WHERE pcname = ?", String.class, "ac新分类"))
                .isEqualTo(pcid);

        // §8.2：既有只读列表行为不变，新分类自动出现在 list 中且 pcid 一致。
        JsonNode list = assertOk(perform(get("/category/list")));
        boolean found = false;
        for (JsonNode item : list.get("list")) {
            if ("ac新分类".equals(item.get("pcname").asText())) {
                found = true;
                assertThat(item.get("pcid").asText()).as("列表项 pcid 必须与新增返回一致").isEqualTo(pcid);
            }
        }
        assertThat(found).as("新增分类必须能在 GET /category/list 中找到").isTrue();
    }

    /** §8.1：字面同名重复新增返回 409 conflict，库里该名字仍只有 1 行。 */
    @Test
    void ac_02_duplicateNameConflicts() throws Exception {
        JsonNode first = assertOk(postCategory(ADM, "{\"pcname\":\"ac重复\"}"));
        trackPcid(first.get("pcid").asText());

        assertError(perform(post("/admin/category")
                        .header("Authorization", token(ADM, "管理员"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pcname\":\"ac重复\"}")),
                409, "conflict");

        assertThat(nameCount("ac重复")).as("重名被拒后该 pcname 仍只能有 1 行").isEqualTo(1);
    }

    /** §8.1：pcname 空串/全空白/21 字符/缺失一律 400 invalid_request，且不向 pclist 写入任何行。 */
    @Test
    void ac_03_invalidNameRejected() throws Exception {
        int baseline = totalRowCount();

        // 四个非法 body：空串、全空白、超 20 个 Unicode 字符、完全没有 pcname 字段。
        List<String> bodies = new ArrayList<String>();
        bodies.add("{\"pcname\":\"\"}");
        bodies.add("{\"pcname\":\"" + NAME_BLANK + "\"}");
        bodies.add("{\"pcname\":\"" + NAME_TOO_LONG + "\"}");
        bodies.add("{\"cds\":\"ac说明\"}");

        for (String body : bodies) {
            assertError(perform(post("/admin/category")
                            .header("Authorization", token(ADM, "管理员"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)),
                    400, "invalid_request");
        }

        assertThat(totalRowCount()).as("非法入参不得写入任何分类行（基线 %s）", baseline).isEqualTo(baseline);
        assertThat(nameCount(NAME_EMPTY)).isEqualTo(0);
        assertThat(nameCount(NAME_BLANK)).isEqualTo(0);
        assertThat(nameCount(NAME_TOO_LONG)).isEqualTo(0);
    }

    /** §8.1：匿名 401 unauthorized、普通用户 403 forbidden，两种越权都不写入数据。 */
    @Test
    void ac_04_permissionMatrix() throws Exception {
        int baseline = totalRowCount();

        // 匿名：不带 Authorization。
        assertError(perform(post("/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pcname\":\"ac越权\"}")),
                401, "unauthorized");

        // 已认证但非管理员。
        assertError(perform(post("/admin/category")
                        .header("Authorization", token(U, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pcname\":\"ac越权\"}")),
                403, "forbidden");

        assertThat(nameCount("ac越权")).as("越权请求不得产生分类行").isEqualTo(0);
        assertThat(totalRowCount()).as("越权请求前后 pclist 行数不变（基线 %s）", baseline).isEqualTo(baseline);
    }

    /** §8.1：sort 取当前 MAX(sort)+10 追加，连续新建 3 个分类时 sort 严格递增、增量均为正，且三个 pcid 互不相同。 */
    @Test
    void ac_05_sortIsAppended() throws Exception {
        JsonNode c1 = assertOk(postCategory(ADM, "{\"pcname\":\"ac排序甲\"}"));
        JsonNode c2 = assertOk(postCategory(ADM, "{\"pcname\":\"ac排序乙\"}"));
        JsonNode c3 = assertOk(postCategory(ADM, "{\"pcname\":\"ac排序丙\"}"));
        trackPcid(c1.get("pcid").asText());
        trackPcid(c2.get("pcid").asText());
        trackPcid(c3.get("pcid").asText());

        long s1 = c1.get("sort").asLong();
        long s2 = c2.get("sort").asLong();
        long s3 = c3.get("sort").asLong();
        assertThat(s2).as("第二条 sort 必须大于第一条").isGreaterThan(s1);
        assertThat(s3).as("第三条 sort 必须大于第二条").isGreaterThan(s2);
        assertThat(s2 - s1).as("相邻增量必须为正").isPositive();
        assertThat(s3 - s2).as("末项与前项的差值为正").isPositive();

        Set<String> pcids = new HashSet<String>();
        pcids.add(c1.get("pcid").asText());
        pcids.add(c2.get("pcid").asText());
        pcids.add(c3.get("pcid").asText());
        assertThat(pcids).as("服务端生成的 pcid 必须互不相同").hasSize(3);

        // 三个名字互不相同，重名校验不应误伤追加逻辑。
        assertThat(nameCount("ac排序甲") + nameCount("ac排序乙") + nameCount("ac排序丙"))
                .as("三次新建共 3 行").isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 以管理员身份 POST /admin/category，返回 MvcResult。 */
    private MvcResult postCategory(String adminUid, String body) throws Exception {
        return perform(post("/admin/category")
                .header("Authorization", token(adminUid, "管理员"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn();
    }

    private void trackPcid(String pcid) {
        if (pcid != null && !pcid.isEmpty()) {
            createdPcids.add(pcid);
        }
    }

    /** 按 pcname 精确统计行数，用于"未写入/只写一行"断言。 */
    private int nameCount(String pcname) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pclist WHERE pcname = ?", Integer.class, pcname);
    }

    /** 全表行数基线（只读，不做任何删除）。 */
    private int totalRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pclist", Integer.class);
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
}
