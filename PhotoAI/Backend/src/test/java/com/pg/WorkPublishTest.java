package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.myutils.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 卡03 作品发布冻结测试（T03-01..T03-37）。
 * 只连 db_pg_test_03，夹具全部在本类内自建自清，不继承共享基座；
 * MsgSecCheck 属本卡新接口，接口未合入时跳过替身安装、由路由缺失自然红，
 * 合入后通过运行时反射把 PworkServiceImpl 注入的 MsgSecCheck 字段替换为测试替身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WorkPublishTest {

    /** 测试库固定指向 db_pg_test_03，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_03?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=innodb_lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    private static final Logger log = LoggerFactory.getLogger(WorkPublishTest.class);

    private static final String U = "wp03-u";
    private static final String O = "wp03-o";
    private static final String A = "wp03-a";
    private static final String C1 = "wp03-c1";
    private static final String C2 = "wp03-c2";
    private static final String P1 = "wp03-p1";
    private static final String P2 = "wp03-p2";
    private static final String P3 = "wp03-p3";
    private static final String P_OLD = "wp03-pold";
    private static final String P_PENDING = "wp03-ph1";
    private static final String P_HIDDEN = "wp03-ph2";
    private static final String P_REJECTED = "wp03-ph3";
    private static final String MISSING = "wp03-missing";
    private static final String POST_TYPE = "帖子";
    private static final String COMMENT_TYPE = "评论";
    private static final String BASE_IMAGE = "/uploads/work/safe.jpg";
    private static final String TIME_REGEX = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+08:00";
    private static final String ID_REGEX = "[A-Za-z0-9_-]{1,36}";
    private static final String SEP = "\u0001";
    private static final String[] WORK_KEYS = {"pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
            "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount"};
    private static final String[] MINE_WORK_KEYS = {"pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
            "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount", "reason"};
    private static final String[] EXIF_KEYS = {"focal", "aperture", "shutter", "iso", "lens", "camera"};
    private static final String[] AUTHOR_KEYS = {"uid", "nickname", "avatar"};

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    private Object msgSecCheckStub;

    /** 文本安全检查替身的行为由这个 Answer 决定，避免对 mock 打桩（master 上还没有 MsgSecCheck 类型）。 */
    private org.mockito.stubbing.Answer<Object> secCheckAnswer = invocation -> null;
    private final List<String> secCheckCalls = new ArrayList<>();

    /**
     * 构造器注入全部测试依赖。
     * @param mvc 模拟 MVC 客户端
     * @param jdbc 真实 MySQL 访问
     * @param jwtUtils 测试令牌签发
     * @param objectMapper 请求构造与响应解析
     * @param applicationContext 用于反射定位作品服务
     */
    WorkPublishTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper,
                    ApplicationContext applicationContext) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入三个测试身份与两个分类。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_03");
        cleanupFixtures();
        seedUser(U, "wp03用户U", "普通用户", "19900000301");
        seedUser(O, "wp03用户O", "普通用户", "19900000302");
        seedUser(A, "wp03管理员A", "管理员", "19900000303");
        seedCategory(C1, "风光");
        seedCategory(C2, "人像");
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM work_embedding WHERE pid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'wp03-%' OR pid LIKE 'wp03-%')");
        jdbc.update("DELETE FROM work_exif WHERE pid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'wp03-%' OR pid LIKE 'wp03-%')");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'wp03-%' OR uid LIKE 'wp03-%' OR pid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'wp03-%' OR pid LIKE 'wp03-%')");
        jdbc.update("DELETE FROM comment WHERE cid LIKE 'wp03-%' OR uid LIKE 'wp03-%' OR pid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'wp03-%' OR pid LIKE 'wp03-%')");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'wp03-%' OR uid LIKE 'wp03-%' OR targetid IN "
                + "(SELECT pid FROM pwork WHERE uid LIKE 'wp03-%' OR pid LIKE 'wp03-%')");
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'wp03-%' OR pid LIKE 'wp03-%' OR uadmin LIKE 'wp03-%'");
        jdbc.update("DELETE FROM pwork WHERE uid LIKE 'wp03-%' OR pid LIKE 'wp03-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'wp03-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'wp03-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例
    // ------------------------------------------------------------------

    /** T03-01 公开列表缺省参数：只统计展示作品，条目14键无 reason，时间为ISO秒+08:00，计数非负整数。 */
    @Test
    void wp_01_listDefaultOnlyDisplayed() throws Exception {
        seedWork(P1, U, "展示", 1, 2, 3, C1, "2026-10-01 08:00:00");
        seedWork("wp03-s2", O, "展示", 4, 5, 6, C2, "2026-10-02 08:00:00");
        seedWork(P_PENDING, U, "待审核");
        seedWork(P_HIDDEN, U, "隐藏");
        seedWork(P_REJECTED, U, "已驳回");
        seedLike("wp03-l1", O, P1, POST_TYPE);
        MvcResult result = mvc.perform(get("/pwork/list")).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isGreaterThanOrEqualTo(2L);
        for (JsonNode item : list) {
            assertWorkItem(item);
            assertThat(item.get("wstatus").asText()).isEqualTo("展示");
            assertThat(item.has("reason")).isFalse();
        }
        assertThat(findItemOrNull(list, P_PENDING)).isNull();
        assertThat(findItemOrNull(list, P_HIDDEN)).isNull();
        assertThat(findItemOrNull(list, P_REJECTED)).isNull();
        JsonNode p1 = findItemByPid(list, P1);
        assertThat(p1.get("uid").asText()).isEqualTo(U);
        assertThat(p1.get("wtitle").asText()).isEqualTo("作品" + P1);
        assertThat(p1.get("uname").asText()).isEqualTo("wp03用户U");
        assertThat(p1.get("avatar").asText()).isEqualTo("/avatar/" + U + ".png");
        assertThat(p1.get("pcname").asText()).isEqualTo("风光");
        assertThat(p1.get("viewcount").asLong()).isEqualTo(1L);
        assertThat(p1.get("votecount").asLong()).isEqualTo(2L);
        assertThat(p1.get("likep").asLong()).isEqualTo(3L);
        assertThat(p1.get("realLikeCount").asLong()).isEqualTo(1L);
        JsonNode s2 = findItemByPid(list, "wp03-s2");
        assertThat(s2.get("uid").asText()).isEqualTo(O);
        assertThat(s2.get("pcname").asText()).isEqualTo("人像");
    }

    /** T03-02 列表 tab=latest 时间降序/pid 升序、hot 按 likep+votecount 降序/pid 升序，category 按 pcid 过滤，total 是过滤总数。 */
    @Test
    void wp_02_listTabOrderingAndCategory() throws Exception {
        // 时间取 2099 年压过库内自带样例作品，保证 latest 降序第一页就是本例夹具
        seedWork("wp03-a1", U, "展示", 0, 800, 900, C1, "2099-01-01 10:00:00");
        seedWork("wp03-a2", O, "展示", 0, 2, 0, C1, "2099-01-02 10:00:00");
        seedWork("wp03-a3", U, "展示", 0, 0, 0, C2, "2099-01-03 10:00:00");
        seedWork("wp03-a4", O, "展示", 0, 0, 0, C2, "2099-01-04 10:00:00");
        seedWork("wp03-a5", U, "展示", 0, 900, 999, null, "2099-01-05 10:00:00");
        seedWork(P_PENDING, U, "待审核");
        MvcResult latest = mvc.perform(get("/pwork/list")
                .param("tab", "latest").param("page", "1").param("size", "2")).andReturn();
        JsonNode latestData = assertOk(latest);
        assertThat(latestData.get("total").isIntegralNumber()).isTrue();
        assertThat(latestData.get("total").asLong()).isGreaterThanOrEqualTo(5L);
        assertThat(filterPids(collectPids(latestData.get("list")),
                "wp03-a1", "wp03-a2", "wp03-a3", "wp03-a4", "wp03-a5"))
                .containsExactly("wp03-a5", "wp03-a4");
        for (JsonNode item : latestData.get("list")) {
            assertWorkItem(item);
        }
        MvcResult hot = mvc.perform(get("/pwork/list")
                .param("tab", "hot").param("page", "1").param("size", "2")).andReturn();
        JsonNode hotData = assertOk(hot);
        assertThat(hotData.get("total").isIntegralNumber()).isTrue();
        assertThat(hotData.get("total").asLong()).isEqualTo(latestData.get("total").asLong());
        assertThat(filterPids(collectPids(hotData.get("list")),
                "wp03-a1", "wp03-a2", "wp03-a3", "wp03-a4", "wp03-a5"))
                .containsExactly("wp03-a5", "wp03-a1");
        MvcResult cat = mvc.perform(get("/pwork/list")
                .param("category", C1).param("page", "1").param("size", "50")).andReturn();
        JsonNode catData = assertOk(cat);
        assertThat(catData.get("total").asLong()).isEqualTo(2L);
        assertThat(filterPids(collectPids(catData.get("list")), "wp03-a1", "wp03-a2"))
                .containsExactlyInAnyOrder("wp03-a1", "wp03-a2");
    }

    /** T03-03 大页码返回空页：list 是 [] 不是 null，total 保持过滤总数不清零。 */
    @Test
    void wp_03_farPageEmptyListKeepsTotal() throws Exception {
        seedWork(P1, U, "展示");
        seedWork("wp03-s2", O, "展示");
        seedWork(P_PENDING, U, "待审核");
        MvcResult first = mvc.perform(get("/pwork/list").param("page", "1").param("size", "10")).andReturn();
        JsonNode firstData = assertOk(first);
        MvcResult far = mvc.perform(get("/pwork/list").param("page", "999").param("size", "10")).andReturn();
        JsonNode farData = assertOk(far);
        assertThat(farData.get("list").isArray()).isTrue();
        assertThat(farData.get("list").size()).isEqualTo(0);
        assertThat(farData.get("total").asLong()).isEqualTo(firstData.get("total").asLong());
    }

    /** T03-04 非法 query 逐例 400 invalid_request：page/size 越界或非整数、tab 枚举、category 格式。 */
    @Test
    void wp_04_invalidQueryIs400() throws Exception {
        seedWork(P1, U, "展示");
        MvcResult[] cases = new MvcResult[]{
                mvc.perform(get("/pwork/list").param("page", "0")).andReturn(),
                mvc.perform(get("/pwork/list").param("size", "0")).andReturn(),
                mvc.perform(get("/pwork/list").param("size", "51")).andReturn(),
                mvc.perform(get("/pwork/list").param("page", "1.5")).andReturn(),
                mvc.perform(get("/pwork/list").param("size", "x")).andReturn(),
                mvc.perform(get("/pwork/list").param("tab", "bad")).andReturn(),
                mvc.perform(get("/pwork/list").param("category", "bad!")).andReturn()
        };
        for (MvcResult result : cases) {
            assertError(result, 400, "invalid_request");
        }
    }

    /** T03-05 详情展示作品：work/exif/author 三键，浏览原子+1 返回加后值；两并发 GET 最终 n+2 且响应 n+1/n+2。 */
    @Test
    void wp_05_detailShowsExifAuthorAndCountsView() throws Exception {
        seedWork(P1, U, "展示", 7, 0, 0, C1, "2026-10-01 08:00:00");
        seedExif(P1, 35, "2.8", "1/250", 100, "镜头", "相机");
        MvcResult first = mvc.perform(get("/pwork/" + P1)).andReturn();
        JsonNode firstData = assertOk(first);
        assertDetailShape(firstData, P1);
        assertThat(firstData.get("work").get("viewcount").asLong()).isEqualTo(8L);
        assertThat(viewcountOf(P1)).isEqualTo(8L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Long> task = () -> {
                ready.countDown();
                start.await();
                MvcResult result = mvc.perform(get("/pwork/" + P1)).andReturn();
                JsonNode data = assertOk(result);
                assertDetailShape(data, P1);
                return data.get("work").get("viewcount").asLong();
            };
            Future<Long> firstView = pool.submit(task);
            Future<Long> secondView = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(Arrays.asList(firstView.get(30, TimeUnit.SECONDS), secondView.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(9L, 10L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(viewcountOf(P1)).isEqualTo(10L);
    }

    /** T03-06 历史展示作品无 EXIF/作者：uid/uname/avatar/pcname 键不消失且为 null；2147483648 计数不溢出仍为 JSON 整数。 */
    @Test
    void wp_06_oldWorkNullKeysAndBigCounters() throws Exception {
        seedWork(P_OLD, null, "展示", 2147483648L, 2147483648L, 2147483648L, null, "2026-01-01 08:00:00");
        MvcResult result = mvc.perform(get("/pwork/" + P_OLD)).andReturn();
        JsonNode data = assertOk(result);
        assertKeys(data, "work", "exif", "author");
        JsonNode work = data.get("work");
        assertWorkItem(work);
        assertThat(work.get("pid").asText()).isEqualTo(P_OLD);
        assertThat(work.get("uid").isNull()).isTrue();
        assertThat(work.get("uname").isNull()).isTrue();
        assertThat(work.get("avatar").isNull()).isTrue();
        assertThat(work.get("pcname").isNull()).isTrue();
        assertThat(work.get("viewcount").isIntegralNumber()).isTrue();
        assertThat(work.get("viewcount").asLong()).isEqualTo(2147483649L);
        assertThat(work.get("votecount").asLong()).isEqualTo(2147483648L);
        assertThat(work.get("likep").asLong()).isEqualTo(2147483648L);
        assertThat(data.get("exif").isNull()).isTrue();
        assertThat(data.get("author").isNull()).isTrue();
    }

    /** T03-07 非展示三种状态与合法不存在 pid 逐例 404 not_found，浏览计数不变。 */
    @Test
    void wp_07_hiddenOrMissingDetailIs404() throws Exception {
        seedWork(P_PENDING, U, "待审核", 5, 0, 0);
        seedWork(P_HIDDEN, U, "隐藏", 5, 0, 0);
        seedWork(P_REJECTED, U, "已驳回", 5, 0, 0);
        for (String pid : new String[]{P_PENDING, P_HIDDEN, P_REJECTED, MISSING}) {
            assertError(mvc.perform(get("/pwork/" + pid)).andReturn(), 404, "not_found");
        }
        assertThat(viewcountOf(P_PENDING)).isEqualTo(5L);
        assertThat(viewcountOf(P_HIDDEN)).isEqualTo(5L);
        assertThat(viewcountOf(P_REJECTED)).isEqualTo(5L);
    }

    /** T03-08 详情非法 pid 格式 400 invalid_request。 */
    @Test
    void wp_08_detailBadIdIs400() throws Exception {
        assertError(mvc.perform(get("/pwork/bad%21")).andReturn(), 400, "invalid_request");
    }

    /** T03-09 mine 本人四状态+reason：仅 U 作品、total 按过滤计数、reason 按最近日志规则、审核历史保留。 */
    @Test
    void wp_09_mineOwnWorksWithReasonRules() throws Exception {
        seedWork("wp03-m1", U, "展示", 0, 0, 0);
        seedWork("wp03-m2", U, "待审核", 0, 0, 0);
        seedWork("wp03-m3", U, "已驳回", 0, 0, 0);
        seedWork("wp03-m4", U, "隐藏", 0, 0, 0);
        seedWork("wp03-m5", U, "待审核", 0, 0, 0);
        seedWork("wp03-o1", O, "展示", 0, 0, 0);
        seedAudit("wp03-aid1", "wp03-m4", "reject", "理由一", A, "2026-10-01 10:00:00");
        seedAudit("wp03-aid2", "wp03-m4", "reject", "理由二", A, "2026-10-01 10:00:00");
        seedAudit("wp03-aid3", "wp03-m5", "reject", "旧理由", A, "2026-10-01 09:00:00");
        seedAudit("wp03-aid4", "wp03-m5", "approve", null, A, "2026-10-01 11:00:00");
        seedAudit("wp03-aid5", "wp03-m2", "approve", null, A, "2026-10-01 10:00:00");
        seedAudit("wp03-aid6", "wp03-m3", "reject", "构图不佳", A, "2026-10-01 10:00:00");
        MvcResult all = mvc.perform(get("/pwork/mine").header("Authorization", token(U, "普通用户"))).andReturn();
        JsonNode allData = assertOk(all);
        assertThat(allData.get("total").asLong()).isEqualTo(5L);
        assertThat(allData.get("list").size()).isEqualTo(5);
        for (JsonNode item : allData.get("list")) {
            assertMineWorkItem(item);
            assertThat(item.get("uid").asText()).isEqualTo(U);
        }
        assertThat(reasonOf(allData.get("list"), "wp03-m1")).isNull();
        assertThat(reasonOf(allData.get("list"), "wp03-m2")).isNull();
        assertThat(reasonOf(allData.get("list"), "wp03-m3")).isEqualTo("构图不佳");
        assertThat(reasonOf(allData.get("list"), "wp03-m4")).isEqualTo("理由二");
        assertThat(reasonOf(allData.get("list"), "wp03-m5")).isNull();
        assertThat(mineTotal(U, "待审核")).isEqualTo(2L);
        assertThat(mineTotal(U, "展示")).isEqualTo(1L);
        assertThat(mineTotal(U, "隐藏")).isEqualTo(1L);
        assertThat(mineTotal(U, "已驳回")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM waudit WHERE aid LIKE 'wp03-%'")).isEqualTo(6L);
    }

    /** T03-10 mine 无凭证 401 unauthorized。 */
    @Test
    void wp_10_mineWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        assertError(mvc.perform(get("/pwork/mine")).andReturn(), 401, "unauthorized");
    }

    /** T03-11 mine 非法 query 逐例 400：status 枚举、page/size 越界或非整数。 */
    @Test
    void wp_11_mineInvalidQueryIs400() throws Exception {
        seedWork(P1, U, "展示");
        String auth = token(U, "普通用户");
        MvcResult[] cases = new MvcResult[]{
                mvc.perform(get("/pwork/mine").header("Authorization", auth).param("status", "bad")).andReturn(),
                mvc.perform(get("/pwork/mine").header("Authorization", auth).param("page", "0")).andReturn(),
                mvc.perform(get("/pwork/mine").header("Authorization", auth).param("size", "51")).andReturn(),
                mvc.perform(get("/pwork/mine").header("Authorization", auth).param("page", "x")).andReturn()
        };
        for (MvcResult result : cases) {
            assertError(result, 400, "invalid_request");
        }
    }

    /** T03-12 发布基础四字段 201：data 只有 pid/待审核，uid=U、pcid=C1、无 EXIF 行、embedding pending/NULL。 */
    @Test
    void wp_12_publishBaseline201() throws Exception {
        installMsgSecCheckMock();
        MvcResult result = publishRaw(baseBody());
        JsonNode data = assertCreated(result);
        assertKeys(data, "pid", "status");
        String pid = data.get("pid").asText();
        assertThat(pid).matches(ID_REGEX);
        assertThat(data.get("status").asText()).isEqualTo("待审核");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT uid, wtitle, wdesc, vurl, pcid, wstatus, viewcount, votecount, likep FROM pwork WHERE pid=?",
                pid);
        assertThat(row.get("uid")).isEqualTo(U);
        assertThat(row.get("wtitle")).isEqualTo("山海");
        assertThat(row.get("wdesc")).isEqualTo("");
        assertThat(row.get("vurl")).isEqualTo(BASE_IMAGE);
        assertThat(row.get("pcid")).isEqualTo(C1);
        assertThat(row.get("wstatus")).isEqualTo("待审核");
        assertThat(((Number) row.get("viewcount")).longValue()).isEqualTo(0L);
        assertThat(((Number) row.get("votecount")).longValue()).isEqualTo(0L);
        assertThat(((Number) row.get("likep")).longValue()).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM work_exif WHERE pid=?", pid)).isEqualTo(0L);
        Map<String, Object> embedding = jdbc.queryForMap("SELECT status, vec FROM work_embedding WHERE pid=?", pid);
        assertThat(embedding.get("status")).isEqualTo("pending");
        assertThat(embedding.get("vec")).isNull();
    }

    /** T03-13 发布带六项 EXIF 201：按值落库且新 pid 一致，身份取 U、状态由服务端设为待审核，embedding pending/NULL。 */
    @Test
    void wp_13_publishWithExifStoresSixValues() throws Exception {
        installMsgSecCheckMock();
        Map<String, Object> exif = new LinkedHashMap<>();
        exif.put("focal", 35);
        exif.put("aperture", 2.8);
        exif.put("shutter", "1/250");
        exif.put("iso", 100);
        exif.put("lens", "镜头");
        exif.put("camera", "相机");
        String pid = publishAndGetPid(bodyWithExif(exif));
        Map<String, Object> row = jdbc.queryForMap("SELECT uid, wstatus FROM pwork WHERE pid=?", pid);
        assertThat(row.get("uid")).isEqualTo(U);
        assertThat(row.get("wstatus")).isEqualTo("待审核");
        Map<String, Object> exifRow = jdbc.queryForMap(
                "SELECT focal, aperture, shutter, iso, lens, camera FROM work_exif WHERE pid=?", pid);
        assertThat(exifRow.get("focal")).isEqualTo(35);
        assertThat(new BigDecimal(String.valueOf(exifRow.get("aperture"))).compareTo(new BigDecimal("2.8")))
                .isEqualTo(0);
        assertThat(exifRow.get("shutter")).isEqualTo("1/250");
        assertThat(exifRow.get("iso")).isEqualTo(100);
        assertThat(exifRow.get("lens")).isEqualTo("镜头");
        assertThat(exifRow.get("camera")).isEqualTo("相机");
        Map<String, Object> embedding = jdbc.queryForMap("SELECT status, vec FROM work_embedding WHERE pid=?", pid);
        assertThat(embedding.get("status")).isEqualTo("pending");
        assertThat(embedding.get("vec")).isNull();
    }

    /** T03-14 EXIF 空对象/全 null/边界最大值三例 201：缺项存 NULL、边界不截断、仅六键投影不保存 gps/serial。 */
    @Test
    void wp_14_publishExifNullAndBoundary() throws Exception {
        installMsgSecCheckMock();
        String pid1 = publishAndGetPid(bodyWithExif(new LinkedHashMap<String, Object>()));
        assertExifRowAllNull(jdbc.queryForMap(
                "SELECT focal, aperture, shutter, iso, lens, camera FROM work_exif WHERE pid=?", pid1));
        Map<String, Object> exif2 = new LinkedHashMap<>();
        exif2.put("focal", null);
        exif2.put("aperture", null);
        exif2.put("shutter", null);
        exif2.put("iso", null);
        exif2.put("lens", null);
        exif2.put("camera", null);
        String pid2 = publishAndGetPid(bodyWithExif(exif2));
        assertExifRowAllNull(jdbc.queryForMap(
                "SELECT focal, aperture, shutter, iso, lens, camera FROM work_exif WHERE pid=?", pid2));
        Map<String, Object> exif3 = new LinkedHashMap<>();
        exif3.put("focal", 32767);
        exif3.put("aperture", 99.9);
        exif3.put("shutter", repeat("1/", 8));
        exif3.put("iso", 32767);
        exif3.put("lens", repeat("镜", 64));
        exif3.put("camera", repeat("相", 64));
        exif3.put("gps", "N39.9 E116.4");
        exif3.put("serial", "SN-PRIVATE");
        String pid3 = publishAndGetPid(bodyWithExif(exif3));
        Map<String, Object> row3 = jdbc.queryForMap(
                "SELECT focal, aperture, shutter, iso, lens, camera FROM work_exif WHERE pid=?", pid3);
        assertThat(row3.get("focal")).isEqualTo(32767);
        assertThat(new BigDecimal(String.valueOf(row3.get("aperture"))).compareTo(new BigDecimal("99.9")))
                .isEqualTo(0);
        assertThat(row3.get("shutter")).isEqualTo(repeat("1/", 8));
        assertThat(row3.get("iso")).isEqualTo(32767);
        assertThat(row3.get("lens")).isEqualTo(repeat("镜", 64));
        assertThat(row3.get("camera")).isEqualTo(repeat("相", 64));
    }

    /** T03-15 exif 为 null/数组/字符串三例 400 invalid_request，pwork/EXIF/embedding 无新行。 */
    @Test
    void wp_15_publishExifWrongTypeIs400() throws Exception {
        installMsgSecCheckMock();
        long[] before = tableCounts();
        for (String body : new String[]{
                bodyWithExif(null),
                bodyWithExif(new Object[]{}),
                bodyWithExif("x")
        }) {
            assertError(publishRaw(body), 400, "invalid_request");
        }
        assertTableCountsUnchanged(before);
    }

    /** T03-16 focal/iso 十例非法值逐例 400：0/负数/超上限/小数/字符串数字。 */
    @Test
    void wp_16_publishFocalIsoOutOfRangeIs400() throws Exception {
        installMsgSecCheckMock();
        long[] before = tableCounts();
        String[] cases = new String[]{
                exifBody("focal", 0), exifBody("focal", -1), exifBody("focal", 32768),
                exifBody("focal", 1.5), exifBody("focal", "35"),
                exifBody("iso", 0), exifBody("iso", -1), exifBody("iso", 32768),
                exifBody("iso", 1.5), exifBody("iso", "100")
        };
        for (String body : cases) {
            assertError(publishRaw(body), 400, "invalid_request");
        }
        assertTableCountsUnchanged(before);
    }

    /** T03-17 aperture 五例非法值 400：0/负数/超上限/两位小数/字符串数字，不静默舍入。 */
    @Test
    void wp_17_publishApertureInvalidIs400() throws Exception {
        installMsgSecCheckMock();
        long[] before = tableCounts();
        String[] cases = new String[]{
                exifBody("aperture", 0), exifBody("aperture", -1), exifBody("aperture", 100),
                exifBody("aperture", 2.85), exifBody("aperture", "2.8")
        };
        for (String body : cases) {
            assertError(publishRaw(body), 400, "invalid_request");
        }
        assertTableCountsUnchanged(before);
    }

    /** T03-18 shutter/lens/camera 超长或类型错误六例 400。 */
    @Test
    void wp_18_publishTextExifInvalidIs400() throws Exception {
        installMsgSecCheckMock();
        long[] before = tableCounts();
        String[] cases = new String[]{
                exifBody("shutter", repeat("1", 17)), exifBody("shutter", 1),
                exifBody("lens", repeat("镜", 65)), exifBody("lens", 1),
                exifBody("camera", repeat("相", 65)), exifBody("camera", 1)
        };
        for (String body : cases) {
            assertError(publishRaw(body), 400, "invalid_request");
        }
        assertTableCountsUnchanged(before);
    }

    /** T03-19 标题100/描述255/图片255 字符边界 201，标题原文保留。 */
    @Test
    void wp_19_publishBoundaryLengths201() throws Exception {
        installMsgSecCheckMock();
        String title100 = repeat("山", 100);
        String desc255 = repeat("晨", 255);
        String image255 = "/uploads/work/" + repeat("a", 240);
        Map<String, Object> body = baseMap();
        body.put("title", title100);
        body.put("desc", desc255);
        body.put("imageUrl", image255);
        String pid = publishAndGetPid(writeJson(body));
        Map<String, Object> row = jdbc.queryForMap("SELECT wtitle, wdesc, vurl FROM pwork WHERE pid=?", pid);
        assertThat(row.get("wtitle")).isEqualTo(title100);
        assertThat(row.get("wdesc")).isEqualTo(desc255);
        assertThat(row.get("vurl")).isEqualTo(image255);
    }

    /** T03-20 发布逐字段替换 18 例全部 400 invalid_request 且不落库，其它字段保持基准 B。 */
    @Test
    void wp_20_publishFieldReplacementsAre400() throws Exception {
        installMsgSecCheckMock();
        long[] before = tableCounts();
        List<String> bodies = new ArrayList<>();
        bodies.add(writeJson(copyWithout(baseMap(), "title")));
        bodies.add(writeJson(replace(baseMap(), "title", null)));
        bodies.add(writeJson(replace(baseMap(), "title", 123)));
        bodies.add(writeJson(replace(baseMap(), "title", "   ")));
        bodies.add(writeJson(replace(baseMap(), "title", repeat("山", 101))));
        bodies.add(writeJson(copyWithout(baseMap(), "desc")));
        bodies.add(writeJson(replace(baseMap(), "desc", null)));
        bodies.add(writeJson(replace(baseMap(), "desc", 123)));
        bodies.add(writeJson(replace(baseMap(), "desc", repeat("晨", 256))));
        bodies.add(writeJson(copyWithout(baseMap(), "imageUrl")));
        bodies.add(writeJson(replace(baseMap(), "imageUrl", null)));
        bodies.add(writeJson(replace(baseMap(), "imageUrl", 123)));
        bodies.add(writeJson(replace(baseMap(), "imageUrl", "   ")));
        bodies.add(writeJson(replace(baseMap(), "imageUrl", repeat("i", 256))));
        bodies.add(writeJson(copyWithout(baseMap(), "categoryId")));
        bodies.add(writeJson(replace(baseMap(), "categoryId", null)));
        bodies.add(writeJson(replace(baseMap(), "categoryId", 123)));
        bodies.add(writeJson(replace(baseMap(), "categoryId", "bad!")));
        for (String body : bodies) {
            assertError(publishRaw(body), 400, "invalid_request");
        }
        assertTableCountsUnchanged(before);
    }

    /** T03-21 无凭证发布 401 unauthorized 且不落库。 */
    @Test
    void wp_21_publishWithoutTokenIs401() throws Exception {
        long[] before = tableCounts();
        assertError(mvc.perform(post("/pwork")
                .contentType(MediaType.APPLICATION_JSON).content(baseBody())).andReturn(), 401, "unauthorized");
        assertTableCountsUnchanged(before);
    }

    /** T03-22 文本安全拒绝 422 content_rejected：检查调用携带 uid=U 且标题、描述都被检查，三表不落库。 */
    @Test
    void wp_22_publishRejectedContentIs422() throws Exception {
        installMsgSecCheckMock();
        ErrorCode rejected = errorCodeIfPresent("CONTENT_REJECTED");
        if (msgSecCheckStub != null && rejected != null) {
            stubRejectAfterTitleAndDesc(rejected, "山海", "被拒描述");
        }
        long[] before = tableCounts();
        Map<String, Object> body = baseMap();
        body.put("desc", "被拒描述");
        assertError(publishRaw(writeJson(body)), 422, "content_rejected");
        assertTableCountsUnchanged(before);
        if (msgSecCheckStub != null && rejected != null) {
            assertThat(secCheckCalls).contains(U + SEP + "山海", U + SEP + "被拒描述");
            for (String call : secCheckCalls) {
                assertThat(call).startsWith(U + SEP);
            }
        }
    }

    /** T03-23 文本安全不可用/超时两例 503 service_unavailable，无放行降级，三表不落库。 */
    @Test
    void wp_23_publishSecurityUnavailableIs503() throws Exception {
        installMsgSecCheckMock();
        ErrorCode unavailable = errorCodeIfPresent("SERVICE_UNAVAILABLE");
        if (msgSecCheckStub != null && unavailable != null) {
            stubThrowOnEveryCall(unavailable);
        }
        long[] before = tableCounts();
        assertError(publishRaw(baseBody()), 503, "service_unavailable");
        assertError(publishRaw(baseBody()), 503, "service_unavailable");
        assertTableCountsUnchanged(before);
    }

    /** T03-24 注入 embedding 写入故障：真实事务 500 internal_error 且 pwork/EXIF/embedding 全部回滚。 */
    @Test
    void wp_24_publishEmbeddingFaultRollsBackAll() throws Exception {
        installMsgSecCheckMock();
        long[] before = tableCounts();
        Connection lock = null;
        try {
            lock = jdbc.getDataSource().getConnection();
            lock.setAutoCommit(false);
            lock.prepareStatement("SELECT * FROM work_embedding FOR UPDATE").executeQuery().close();
            log.info("已锁定 work_embedding 全表间隙注入写入故障");
            Map<String, Object> exif = new LinkedHashMap<>();
            exif.put("focal", 35);
            exif.put("aperture", 2.8);
            exif.put("shutter", "1/250");
            exif.put("iso", 100);
            exif.put("lens", "镜头");
            exif.put("camera", "相机");
            assertError(publishRaw(bodyWithExif(exif)), 500, "internal_error");
        } finally {
            if (lock != null) {
                lock.rollback();
                lock.close();
            }
        }
        assertTableCountsUnchanged(before);
    }

    /** T03-25 作者编辑原展示/隐藏/已驳回逐例 200：四字段全量覆盖、uid 不变、状态回待审核。 */
    @Test
    void wp_25_authorUpdateResetsToPending() throws Exception {
        installMsgSecCheckMock();
        for (String status : new String[]{"展示", "隐藏", "已驳回"}) {
            jdbc.update("DELETE FROM pwork WHERE pid=?", P1);
            seedWorkFull(P1, U, status, "原标题" + status, "原描述", "/old.jpg", 3, 2, 1, C2, "2026-10-01 08:00:00");
            MvcResult result = putAs(U, P1, baseBody());
            JsonNode data = assertOk(result);
            assertKeys(data, "pid", "status");
            assertThat(data.get("pid").asText()).isEqualTo(P1);
            assertThat(data.get("status").asText()).isEqualTo("待审核");
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT uid, wtitle, wdesc, vurl, pcid, wstatus FROM pwork WHERE pid=?", P1);
            assertThat(row.get("uid")).isEqualTo(U);
            assertThat(row.get("wtitle")).isEqualTo("山海");
            assertThat(row.get("wdesc")).isEqualTo("");
            assertThat(row.get("vurl")).isEqualTo(BASE_IMAGE);
            assertThat(row.get("pcid")).isEqualTo(C1);
            assertThat(row.get("wstatus")).isEqualTo("待审核");
        }
    }

    /** T03-26 非作者 O 与管理员 A 编辑都 403 forbidden：管理员不自动获得编辑权，作品不变。 */
    @Test
    void wp_26_updateByOtherOrAdminIs403() throws Exception {
        installMsgSecCheckMock();
        seedWorkFull(P1, U, "展示", "原标题", "原描述", "/old.jpg", 0, 0, 0, C1, null);
        assertError(putAs(O, P1, baseBody()), 403, "forbidden");
        assertError(putAs(A, P1, baseBody()), 403, "forbidden");
        assertWorkRowUnchanged(P1, "原标题", "原描述", "/old.jpg", C1, "展示");
    }

    /** T03-27 编辑合法不存在 pid 404 not_found。 */
    @Test
    void wp_27_updateMissingIs404() throws Exception {
        installMsgSecCheckMock();
        assertError(putAs(U, MISSING, baseBody()), 404, "not_found");
    }

    /** T03-28 无凭证编辑 401 unauthorized。 */
    @Test
    void wp_28_updateWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        assertError(mvc.perform(put("/pwork/" + P1)
                .contentType(MediaType.APPLICATION_JSON).content(baseBody())).andReturn(), 401, "unauthorized");
    }

    /** T03-29 编辑缺任一字段或越界五例 400：全量更新缺一不可、不能部分更新，原行不变。 */
    @Test
    void wp_29_updateMissingFieldIs400() throws Exception {
        installMsgSecCheckMock();
        seedWorkFull(P1, U, "展示", "原标题", "原描述", "/old.jpg", 0, 0, 0, C1, null);
        List<String> bodies = new ArrayList<>();
        bodies.add(writeJson(copyWithout(baseMap(), "title")));
        bodies.add(writeJson(copyWithout(baseMap(), "desc")));
        bodies.add(writeJson(copyWithout(baseMap(), "imageUrl")));
        bodies.add(writeJson(copyWithout(baseMap(), "categoryId")));
        Map<String, Object> over = baseMap();
        over.put("title", repeat("山", 101));
        over.put("desc", repeat("晨", 256));
        over.put("imageUrl", repeat("i", 256));
        over.put("categoryId", 123);
        bodies.add(writeJson(over));
        for (String body : bodies) {
            assertError(putAs(U, P1, body), 400, "invalid_request");
        }
        assertWorkRowUnchanged(P1, "原标题", "原描述", "/old.jpg", C1, "展示");
    }

    /** T03-30 编辑非法 pid 格式 400 invalid_request。 */
    @Test
    void wp_30_updateBadIdIs400() throws Exception {
        assertError(putAs(U, "bad%21", baseBody()), 400, "invalid_request");
    }

    /** T03-31 编辑文本安全拒绝 422 content_rejected，原字段与状态均不变。 */
    @Test
    void wp_31_updateRejectedContentIs422() throws Exception {
        installMsgSecCheckMock();
        ErrorCode rejected = errorCodeIfPresent("CONTENT_REJECTED");
        if (msgSecCheckStub != null && rejected != null) {
            stubThrowOnEveryCall(rejected);
        }
        seedWorkFull(P1, U, "展示", "原标题", "原描述", "/old.jpg", 0, 0, 0, C1, null);
        assertError(putAs(U, P1, baseBody()), 422, "content_rejected");
        assertWorkRowUnchanged(P1, "原标题", "原描述", "/old.jpg", C1, "展示");
    }

    /** T03-32 编辑文本安全不可用 503 service_unavailable，原字段与状态均不变。 */
    @Test
    void wp_32_updateSecurityUnavailableIs503() throws Exception {
        installMsgSecCheckMock();
        ErrorCode unavailable = errorCodeIfPresent("SERVICE_UNAVAILABLE");
        if (msgSecCheckStub != null && unavailable != null) {
            stubThrowOnEveryCall(unavailable);
        }
        seedWorkFull(P1, U, "展示", "原标题", "原描述", "/old.jpg", 0, 0, 0, C1, null);
        assertError(putAs(U, P1, baseBody()), 503, "service_unavailable");
        assertWorkRowUnchanged(P1, "原标题", "原描述", "/old.jpg", C1, "展示");
    }

    /** T03-33 作者与管理员删除各 200 data=null：pwork 及从属/帖子点赞清除，其它类型与其它 pid 点赞保留。 */
    @Test
    void wp_33_deleteCascadesAndClearsPostLikes() throws Exception {
        seedWorkFull(P1, U, "展示", "作品一", "描述一", "/one.jpg", 0, 0, 0, C1, null);
        seedWorkFull(P3, U, "展示", "作品三", "描述三", "/three.jpg", 0, 0, 0, C2, null);
        seedWorkFull(P2, O, "展示", "作品二", "描述二", "/two.jpg", 0, 0, 0, C2, null);
        seedExif(P1, 35, "2.8", "1/250", 100, "镜头", "相机");
        seedEmbedding(P1);
        seedComment("wp03-c1", U, P1, "评论一");
        seedFavorite("wp03-f1", O, P1);
        seedLike("wp03-l1", U, P1, POST_TYPE);
        seedLike("wp03-l2", O, P1, POST_TYPE);
        seedLike("wp03-l3", O, P1, COMMENT_TYPE);
        seedLike("wp03-l4", U, P2, POST_TYPE);
        assertOkNoData(deleteAs(U, P1));
        assertThat(count("SELECT COUNT(*) FROM pwork WHERE pid=?", P1)).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM work_exif WHERE pid=?", P1)).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM work_embedding WHERE pid=?", P1)).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM comment WHERE pid=?", P1)).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM favorite WHERE pid=?", P1)).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM liketable WHERE targetid=? AND targettype=?", P1, POST_TYPE))
                .isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM liketable WHERE lid=?", "wp03-l3")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM liketable WHERE lid=?", "wp03-l4")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM pwork WHERE pid=?", P2)).isEqualTo(1L);
        assertOkNoData(deleteAs(A, P3));
        assertThat(count("SELECT COUNT(*) FROM pwork WHERE pid=?", P3)).isEqualTo(0L);
    }

    /** T03-34 非作者 O 删除 403 forbidden，作品保留。 */
    @Test
    void wp_34_deleteByOtherIs403() throws Exception {
        seedWork(P1, U, "展示");
        assertError(deleteAs(O, P1), 403, "forbidden");
        assertThat(count("SELECT COUNT(*) FROM pwork WHERE pid=?", P1)).isEqualTo(1L);
    }

    /** T03-35 删除合法不存在 pid 404 not_found。 */
    @Test
    void wp_35_deleteMissingIs404() throws Exception {
        assertError(deleteAs(U, MISSING), 404, "not_found");
    }

    /** T03-36 删除非法 pid 格式 400 invalid_request。 */
    @Test
    void wp_36_deleteBadIdIs400() throws Exception {
        assertError(deleteAs(U, "bad%21"), 400, "invalid_request");
    }

    /** T03-37 无凭证删除 401 unauthorized，作品保留。 */
    @Test
    void wp_37_deleteWithoutTokenIs401() throws Exception {
        seedWork(P1, U, "展示");
        assertError(mvc.perform(delete("/pwork/" + P1)).andReturn(), 401, "unauthorized");
        assertThat(count("SELECT COUNT(*) FROM pwork WHERE pid=?", P1)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private void seedUser(String uid, String nickname, String role, String pnum) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status) VALUES (?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "test-password", "/avatar/" + uid + ".png", "0", "正常");
    }

    private void seedCategory(String pcid, String pcname) {
        jdbc.update("INSERT INTO pclist (pcid, pcname, sort) VALUES (?,?,?)", pcid, pcname, 1);
    }

    private void seedWork(String pid, String uid, String status) {
        seedWork(pid, uid, status, 0, 0, 0);
    }

    private void seedWork(String pid, String uid, String status, long view, long vote, long like) {
        seedWork(pid, uid, status, view, vote, like, null, null);
    }

    private void seedWork(String pid, String uid, String status, long view, long vote, long like,
                          String pcid, String cwtime) {
        seedWorkFull(pid, uid, status, "作品" + pid, "描述" + pid, "/static/images/pwork/test.jpg",
                view, vote, like, pcid, cwtime);
    }

    private void seedWorkFull(String pid, String uid, String status, String title, String desc, String vurl,
                              long view, long vote, long like, String pcid, String cwtime) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, likep, pcid, cwtime) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,COALESCE(?,NOW()))",
                pid, uid, title, desc, vurl, view, vote, status, like, pcid, cwtime);
    }

    private void seedExif(String pid, Integer focal, String aperture, String shutter, Integer iso,
                          String lens, String camera) {
        jdbc.update("INSERT INTO work_exif (pid, focal, aperture, shutter, iso, lens, camera) VALUES (?,?,?,?,?,?,?)",
                pid, focal, aperture, shutter, iso, lens, camera);
    }

    private void seedEmbedding(String pid) {
        jdbc.update("INSERT INTO work_embedding (pid, vec) VALUES (?,?)", pid, new byte[]{1, 2, 3});
    }

    private void seedComment(String cid, String uid, String pid, String content) {
        jdbc.update("INSERT INTO comment (cid, uid, pid, comcontent, likecom) VALUES (?,?,?,?,?)",
                cid, uid, pid, content, "0");
    }

    private void seedFavorite(String fid, String uid, String pid) {
        jdbc.update("INSERT INTO favorite (fid, uid, pid) VALUES (?,?,?)", fid, uid, pid);
    }

    private void seedLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void seedAudit(String aid, String pid, String act, String reason, String uadmin, String ct) {
        jdbc.update("INSERT INTO waudit (aid, pid, act, reason, uadmin, ct) VALUES (?,?,?,?,?,?)",
                aid, pid, act, reason, uadmin, ct);
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    private String roleOf(String uid) {
        return A.equals(uid) ? "管理员" : "普通用户";
    }

    private Map<String, Object> baseMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "山海");
        body.put("desc", "");
        body.put("imageUrl", BASE_IMAGE);
        body.put("categoryId", C1);
        return body;
    }

    private String baseBody() throws Exception {
        return writeJson(baseMap());
    }

    private Map<String, Object> copyWithout(Map<String, Object> source, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove(key);
        return copy;
    }

    private Map<String, Object> replace(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }

    private String bodyWithExif(Object exif) throws Exception {
        Map<String, Object> body = baseMap();
        body.put("exif", exif);
        return writeJson(body);
    }

    private String exifBody(String key, Object value) throws Exception {
        Map<String, Object> exif = new LinkedHashMap<>();
        exif.put(key, value);
        return bodyWithExif(exif);
    }

    private String writeJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private MvcResult publishRaw(String body) throws Exception {
        return mvc.perform(post("/pwork").header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String publishAndGetPid(String body) throws Exception {
        JsonNode data = assertCreated(publishRaw(body));
        assertKeys(data, "pid", "status");
        assertThat(data.get("status").asText()).isEqualTo("待审核");
        return data.get("pid").asText();
    }

    private MvcResult putAs(String uid, String pid, String body) throws Exception {
        return mvc.perform(put("/pwork/" + pid).header("Authorization", token(uid, roleOf(uid)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult deleteAs(String uid, String pid) throws Exception {
        return mvc.perform(delete("/pwork/" + pid).header("Authorization", token(uid, roleOf(uid)))).andReturn();
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

    private JsonNode assertCreated(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
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

    private void assertWorkItem(JsonNode item) {
        assertKeys(item, WORK_KEYS);
        assertWorkFields(item);
    }

    private void assertMineWorkItem(JsonNode item) {
        assertKeys(item, MINE_WORK_KEYS);
        assertWorkFields(item);
        assertNullableText(item.get("reason"));
    }

    private void assertWorkFields(JsonNode item) {
        assertThat(item.get("pid").isTextual()).isTrue();
        assertThat(item.get("pid").asText()).isNotEmpty();
        assertThat(item.get("wtitle").isTextual()).isTrue();
        assertThat(item.get("wdesc").isTextual()).isTrue();
        assertThat(item.get("vurl").isTextual()).isTrue();
        assertThat(item.get("wstatus").asText()).isIn("待审核", "展示", "隐藏", "已驳回");
        assertThat(item.get("viewcount").isIntegralNumber()).isTrue();
        assertThat(item.get("votecount").isIntegralNumber()).isTrue();
        assertThat(item.get("likep").isIntegralNumber()).isTrue();
        assertThat(item.get("realLikeCount").isIntegralNumber()).isTrue();
        assertThat(item.get("cwtime").asText()).matches(TIME_REGEX);
        assertNullableText(item.get("uid"));
        assertNullableText(item.get("uname"));
        assertNullableText(item.get("avatar"));
        assertNullableText(item.get("pcname"));
    }

    private void assertNullableText(JsonNode node) {
        assertThat(node.isNull() || node.isTextual()).isTrue();
    }

    private void assertDetailShape(JsonNode data, String pid) {
        assertKeys(data, "work", "exif", "author");
        JsonNode work = data.get("work");
        assertWorkItem(work);
        assertThat(work.get("pid").asText()).isEqualTo(pid);
        assertThat(work.get("wstatus").asText()).isEqualTo("展示");
        JsonNode exif = data.get("exif");
        assertKeys(exif, EXIF_KEYS);
        assertThat(exif.get("focal").asInt()).isEqualTo(35);
        assertThat(exif.get("aperture").asDouble()).isEqualTo(2.8);
        assertThat(exif.get("shutter").asText()).isEqualTo("1/250");
        assertThat(exif.get("iso").asInt()).isEqualTo(100);
        assertThat(exif.get("lens").asText()).isEqualTo("镜头");
        assertThat(exif.get("camera").asText()).isEqualTo("相机");
        JsonNode author = data.get("author");
        assertKeys(author, AUTHOR_KEYS);
        assertThat(author.get("uid").asText()).isEqualTo(U);
        assertThat(author.get("nickname").asText()).isEqualTo("wp03用户U");
        assertThat(author.get("avatar").asText()).isEqualTo("/avatar/" + U + ".png");
    }

    private JsonNode findItemByPid(JsonNode list, String pid) {
        for (JsonNode item : list) {
            if (pid.equals(item.get("pid").asText())) {
                return item;
            }
        }
        throw new AssertionError("列表缺少 pid=" + pid);
    }

    private JsonNode findItemOrNull(JsonNode list, String pid) {
        for (JsonNode item : list) {
            if (pid.equals(item.get("pid").asText())) {
                return item;
            }
        }
        return null;
    }

    private List<String> collectPids(JsonNode list) {
        List<String> pids = new ArrayList<>();
        for (JsonNode item : list) {
            pids.add(item.get("pid").asText());
        }
        return pids;
    }

    private List<String> filterPids(List<String> pids, String... keep) {
        Set<String> wanted = new HashSet<>(Arrays.asList(keep));
        List<String> kept = new ArrayList<>();
        for (String pid : pids) {
            if (wanted.contains(pid)) {
                kept.add(pid);
            }
        }
        return kept;
    }

    private String reasonOf(JsonNode list, String pid) {
        JsonNode reason = findItemByPid(list, pid).get("reason");
        return reason.isNull() ? null : reason.asText();
    }

    private long mineTotal(String uid, String status) throws Exception {
        MvcResult result = mvc.perform(get("/pwork/mine").header("Authorization", token(uid, roleOf(uid)))
                .param("status", status).param("page", "1").param("size", "50")).andReturn();
        JsonNode data = assertOk(result);
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        return data.get("total").asLong();
    }

    private String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private void assertWorkRowUnchanged(String pid, String title, String desc, String vurl,
                                        String pcid, String status) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT wtitle, wdesc, vurl, pcid, wstatus FROM pwork WHERE pid=?", pid);
        assertThat(row.get("wtitle")).isEqualTo(title);
        assertThat(row.get("wdesc")).isEqualTo(desc);
        assertThat(row.get("vurl")).isEqualTo(vurl);
        assertThat(row.get("pcid")).isEqualTo(pcid);
        assertThat(row.get("wstatus")).isEqualTo(status);
    }

    private void assertExifRowAllNull(Map<String, Object> row) {
        assertThat(row.get("focal")).isNull();
        assertThat(row.get("aperture")).isNull();
        assertThat(row.get("shutter")).isNull();
        assertThat(row.get("iso")).isNull();
        assertThat(row.get("lens")).isNull();
        assertThat(row.get("camera")).isNull();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private long[] tableCounts() {
        return new long[]{
                count("SELECT COUNT(*) FROM pwork"),
                count("SELECT COUNT(*) FROM work_exif"),
                count("SELECT COUNT(*) FROM work_embedding")
        };
    }

    private void assertTableCountsUnchanged(long[] before) {
        assertThat(tableCounts()).containsExactly(before);
    }

    private long viewcountOf(String pid) {
        Number value = jdbc.queryForObject("SELECT viewcount FROM pwork WHERE pid=?", Number.class, pid);
        return value == null ? 0L : value.longValue();
    }

    // ------------------------------------------------------------------
    // MsgSecCheck 反射替身（本卡接口未合入时跳过，由路由缺失自然红）
    // ------------------------------------------------------------------

    private boolean installMsgSecCheckMock() throws Exception {
        secCheckCalls.clear();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            if (!name.toLowerCase(Locale.ROOT).contains("pwork")) {
                continue;
            }
            Object bean = applicationContext.getBean(name);
            Object target = AopTestUtils.getUltimateTargetObject(bean);
            if (!target.getClass().getSimpleName().startsWith("PworkServiceImpl")) {
                continue;
            }
            Field field = findMsgSecCheckField(target.getClass());
            if (field == null) {
                log.info("PworkServiceImpl 未注入 MsgSecCheck 字段，发布未按契约接入文本安全检查，"
                        + "跳过替身安装，由 HTTP 断言自然红 bean={}", name);
                return false;
            }
            field.setAccessible(true);
            msgSecCheckStub = mock(field.getType(), invocation -> secCheckAnswer.answer(invocation));
            field.set(target, msgSecCheckStub);
            log.info("已把 PworkServiceImpl 的 MsgSecCheck 替换为测试替身 bean={} target={}",
                    name, target.getClass().getSimpleName());
            return true;
        }
        log.info("PworkServiceImpl 尚未合入，跳过 MsgSecCheck 替身安装，路由缺失会自然红");
        return false;
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

    private void stubThrowOnEveryCall(final ErrorCode code) {
        if (msgSecCheckStub == null) {
            return;
        }
        secCheckAnswer = invocation -> {
            secCheckCalls.add(invocation.getArgument(0) + SEP + invocation.getArgument(1));
            throw new BizException(code, code.getStatus());
        };
    }

    private void stubRejectAfterTitleAndDesc(final ErrorCode code, final String title, final String desc) {
        if (msgSecCheckStub == null) {
            return;
        }
        secCheckAnswer = invocation -> {
            String uid = invocation.getArgument(0);
            String content = invocation.getArgument(1);
            secCheckCalls.add(uid + SEP + content);
            if (secCheckCalls.contains(U + SEP + title) && secCheckCalls.contains(U + SEP + desc)) {
                throw new BizException(code, code.getStatus());
            }
            return null;
        };
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
