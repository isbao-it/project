package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
import com.pg.service.MsgSecCheck;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.servlet.http.Cookie;
import java.security.Key;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 卡04 后台审核与用户管理冻结测试：T04-01..T04-30 全部断言与参数样本映射。
 * 只连 db_pg_test_04（root/root），夹具全部在本类内自建自清（uid/pid/aid 统一 c04- 前缀），
 * 不继承共享基座、不改 application-test.yml、不 TRUNCATE 共享表。
 * 故障注入（T04-08/T04-27 的 500）用临时重命名 waudit 表实现：冻结要求测试不得静态引用
 * 尚未实现的 AuditMapper 业务类，故不通过 @MockBean 替换尚不存在的 Mapper，而是让真实
 * 条件 UPDATE 完成后、插日志时数据库抛出真实未知异常，结束恢复；替身（MsgSecCheck）按
 * 要求用 @MockBean，不对任何 final 构造器注入字段做反射打桩。
 * 并发用例（T04-07/T04-21）夹具先提交，双线程栅栏同起跑，用真实条件更新/保护锁竞争，
 * 结束后按真实库状态断言。所有断言在实现缺失时因路由/行为缺失自然红，无 @Disabled。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuditApiTest {

    /** 测试库固定指向 db_pg_test_04，不改共享 application-test.yml；加锁等待上限避免死锁挂死。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_04?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 与 application-test.yml 相同的测试签名密钥，仅用于测试内签发过期 JWT。 */
    private static final String TEST_JWT_SECRET =
            "cGhvdG9haS1jYXJkMDEtdGVzdC1zZWNyZXQtb25seS0yMDI2MTAyMA==";

    private static final Logger log = LoggerFactory.getLogger(AuditApiTest.class);

    /** 测试身份：A/B 正常管理员，U 作品作者，O 另一普通用户，D 禁用用户，E 空资料用户，N 非 11 位号码用户。 */
    private static final String A = "c04-a";
    private static final String B = "c04-b";
    private static final String U = "c04-u";
    private static final String O = "c04-o";
    private static final String D = "c04-d";
    private static final String E = "c04-e";
    private static final String N = "c04-n";
    /** 格式合法但不存在的身份样本。 */
    private static final String M = "c04-m";
    /** 最后管理员保护夹具：L/L1/L2 数据库正常管理员，C/C1/C2 持管理员 claim 但数据库为普通用户。 */
    private static final String L = "c04-l";
    private static final String L1 = "c04-l1";
    private static final String L2 = "c04-l2";
    private static final String C = "c04-c";
    private static final String C1 = "c04-c1";
    private static final String C2 = "c04-c2";

    /** 标准作品夹具：U 有待审 P1/P2、展示 P3、隐藏 P4、已驳回 P5；O 有待审 P6。 */
    private static final String P1 = "c04-p1";
    private static final String P2 = "c04-p2";
    private static final String P3 = "c04-p3";
    private static final String P4 = "c04-p4";
    private static final String P5 = "c04-p5";
    private static final String P6 = "c04-p6";
    /** 审核事务/输入边界、历史日志、删除保护、并发审核与汇总断言的分支夹具。 */
    private static final String G1 = "c04-g1";
    private static final String G2 = "c04-g2";
    private static final String G3 = "c04-g3";
    private static final String R1 = "c04-r1";
    private static final String R2 = "c04-r2";
    private static final String R3 = "c04-r3";
    private static final String R4 = "c04-r4";
    private static final String R5 = "c04-r5";
    private static final String H1 = "c04-h1";
    private static final String H2 = "c04-h2";
    private static final String H3 = "c04-h3";
    private static final String H4 = "c04-h4";
    private static final String W27 = "c04-w27";
    private static final String W28 = "c04-w28";
    private static final String W29 = "c04-w29";

    /** 路径 pid 的冻结长度上限，超过即 400。 */
    private static final int PID_MAX_LENGTH = 36;
    /** 路径 uid 的冻结长度上限，超过即 400。 */
    private static final int UID_MAX_LENGTH = 64;
    /** 超出 int 范围的分页样本，冻结为 400。 */
    private static final String PAGE_OVERFLOW = "2147483648";
    /** 冻结分页上限之外的最小 size 样本。 */
    private static final String SIZE_OVER_LIMIT = "51";
    /** 校验新写 waudit 行 ct 时允许的时钟窗口（毫秒）。 */
    private static final long CT_WINDOW_MILLIS = 600000L;
    /** INT UNSIGNED 计数样本：超出 Java Integer 正数范围，服务端必须以 Long 输出原值。 */
    private static final long INT_UNSIGNED_SAMPLE = 2147483648L;
    /** 故障注入期间 waudit 的临时表名。 */
    private static final String WAUDIT_BACKUP = "waudit_c04_bak";

    /** Work 完整十四键。 */
    private static final String[] WORK_KEYS = new String[]{"pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcname",
            "realLikeCount"};
    /** AdminUser 完整八键。 */
    private static final String[] ADMIN_USER_KEYS = new String[]{"uid", "nickname", "avatar", "bio",
            "role", "status", "pnum", "createdAt"};

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper;

    /** 文本安全检查替身：审核与用户管理不得触发发布/评论的文本检查，T04-29 用 verifyNoInteractions 验证。 */
    @MockBean
    private MsgSecCheck msgSecCheck;

    /**
     * 构造器注入全部测试依赖。
     * @param mvc 模拟 MVC 客户端
     * @param jdbc 真实 MySQL 访问
     * @param jwtUtils 测试令牌签发
     * @param objectMapper 响应解析
     */
    AuditApiTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /**
     * 每个用例前校验库名、恢复故障注入残留并清空本类夹具，再写入标准夹具。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_04");
        // 上一次故障注入若在恢复前中断，先补回真实表，再开始本用例。
        if (tableExists(WAUDIT_BACKUP)) {
            jdbc.execute("RENAME TABLE " + WAUDIT_BACKUP + " TO waudit");
        }
        cleanupFixtures();
        seedStandardFixture();
        log.info("AuditApiTest 夹具准备完成 uid={}/{}/{}/{}/{}/{}/{}", A, B, U, O, D, E, N);
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'c04-%' OR pid LIKE 'c04-%' OR uadmin LIKE 'c04-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'c04-%' OR uid LIKE 'c04-%' OR targetid LIKE 'c04-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'c04-%' OR uid LIKE 'c04-%' OR pid LIKE 'c04-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'c04-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c04-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'c04-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'c04-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（待审核分页）
    // ------------------------------------------------------------------

    /** T04-01 待审分页只含待审核：含 U/O 两位作者的四状态样本，客户端 status/wstatus 参数不能覆盖固定过滤，total 也只计待审。 */
    @Test
    void aud_01_pendingFiltersOnlyPendingAcrossAuthors() throws Exception {
        MvcResult result = mvc.perform(get("/admin/work/pending").header("Authorization", token(A, "管理员"))
                .param("status", "展示").param("wstatus", "隐藏")).andReturn();
        assertPendingPage(result, 3L, P1, P2, P6);
        JsonNode list = assertOk(result).get("list");
        for (JsonNode item : list) {
            assertThat(item.get("wstatus").asText()).isEqualTo("待审核");
        }
        // 含不同作者待审（U 与 O），展示/隐藏/已驳回不得混入。
        Set<String> pids = new HashSet<>();
        list.forEach(item -> pids.add(item.get("pid").asText()));
        assertThat(pids).contains(P1, P6);
        assertThat(pids).doesNotContain(P3, P4, P5);
    }

    /** T04-02 待审 11 条分页样本：默认与旧参数 10 项/total=11，size=1/50 边界、超页空页、无待审空页、非法 page/size 逐例 400、页间无重复遗漏且排序稳定。 */
    @Test
    void aud_02_pendingPagingDefaultsBoundariesStable() throws Exception {
        // 移除标准待审样本，只留本用例 11 条；前置校验库内无其它待审。
        jdbc.update("DELETE FROM pwork WHERE pid IN (?,?,?)", P1, P2, P6);
        assertThat(pendingCount()).isEqualTo(0);
        String[] order = seedElevenPending();
        assertPendingPage(getPending(A, null, null), 11L, firstTen(order));
        assertPendingPage(mvc.perform(get("/admin/work/pending").header("Authorization", token(A, "管理员"))
                .param("pageNum", "2").param("pageSize", "1")).andReturn(), 11L, firstTen(order));
        assertPendingPage(getPending(A, "1", "1"), 11L, order[0]);
        assertPendingPage(getPending(A, "1", "50"), 11L, order);
        assertPendingPage(getPending(A, "999", "50"), 11L);
        // 排序稳定：连续两次默认读取顺序一致，但冻结未指定排序键，不杜撰具体次序。
        List<String> first = pendingPids(getPending(A, null, null));
        List<String> second = pendingPids(getPending(A, null, null));
        assertThat(second).isEqualTo(first);
        // 页间无重复遗漏：size=4 遍历三页恰好覆盖 11 条。
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 3; page++) {
            for (String pid : pendingPids(getPending(A, String.valueOf(page), "4"))) {
                assertThat(seen.add(pid)).as("页码 %s 重复出现 pid=%s", page, pid).isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(order);
        // 无待审空页 total=0。
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c04-w%'");
        assertThat(pendingCount()).isEqualTo(0);
        assertPendingPage(getPending(A, null, null), 0L);
        // 非法 page/size 逐例 400 invalid_request。
        for (String page : new String[]{"0", "-1", "1.5", "x", PAGE_OVERFLOW}) {
            assertError(mvc.perform(get("/admin/work/pending").header("Authorization", token(A, "管理员"))
                    .param("page", page)).andReturn(), 400, "invalid_request");
        }
        for (String size : new String[]{"0", "-1", SIZE_OVER_LIMIT, "1.5", "x", PAGE_OVERFLOW}) {
            assertError(mvc.perform(get("/admin/work/pending").header("Authorization", token(A, "管理员"))
                    .param("size", size)).andReturn(), 400, "invalid_request");
        }
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（审核事务与输入）
    // ------------------------------------------------------------------

    /** T04-03 通过待审作品：data 恰为 {pid,status=展示}，只改 P1.wstatus，waudit 恰新增 1 条 pass 日志（aid 唯一非空、reason=NULL、uadmin=A、ct 为本次时间），历史日志保留。 */
    @Test
    void aud_03_approveAppendsSinglePassLog() throws Exception {
        int before = auditRows();
        assertPidStatus(approveAs(A, P1), P1, "展示");
        assertThat(statusOfWork(P1)).isEqualTo("展示");
        assertThat(statusOfWork(P2)).isEqualTo("待审核");
        assertThat(statusOfWork(P3)).isEqualTo("展示");
        assertThat(statusOfWork(P4)).isEqualTo("隐藏");
        assertThat(statusOfWork(P5)).isEqualTo("已驳回");
        assertThat(statusOfWork(P6)).isEqualTo("待审核");
        assertThat(auditRows()).isEqualTo(before + 1);
        Map<String, Object> fresh = jdbc.queryForMap(
                "SELECT aid, act, reason, uadmin, ct FROM waudit WHERE pid=? AND aid<>?", P1, "c04-h-p1");
        String aid = (String) fresh.get("aid");
        assertThat(aid).isNotNull();
        assertThat(aid.trim()).isNotEmpty();
        assertThat((String) fresh.get("act")).isEqualTo("pass");
        assertThat(fresh.get("reason")).isNull();
        assertThat((String) fresh.get("uadmin")).isEqualTo(A);
        long ct = ctMillis(fresh.get("ct"));
        long now = System.currentTimeMillis();
        assertThat(ct).isGreaterThan(now - CT_WINDOW_MILLIS);
        assertThat(ct).isLessThan(now + CT_WINDOW_MILLIS);
        // 历史日志原样保留。
        Map<String, Object> history = jdbc.queryForMap("SELECT act, reason, uadmin FROM waudit WHERE aid=?", "c04-h-p1");
        assertThat((String) history.get("act")).isEqualTo("reject");
        assertThat((String) history.get("reason")).isEqualTo("旧驳回理由");
        assertThat((String) history.get("uadmin")).isEqualTo(A);
        // 其它字段不动。
        assertThat(viewcountOfWork(P1)).isEqualTo(5);
    }

    /** T04-04 驳回待审作品：data 恰为 {pid,status=已驳回}，新增唯一 reject 日志 reason 逐字等于请求、uadmin=A，状态与日志同时生效。 */
    @Test
    void aud_04_rejectWritesReasonLog() throws Exception {
        String reason = "构图不符合要求";
        int before = auditRows();
        assertPidStatus(rejectAs(A, P2, reason), P2, "已驳回");
        assertThat(statusOfWork(P2)).isEqualTo("已驳回");
        assertThat(auditRows()).isEqualTo(before + 1);
        Map<String, Object> fresh = jdbc.queryForMap(
                "SELECT act, reason, uadmin FROM waudit WHERE pid=? AND aid NOT LIKE 'c04-h-%'", P2);
        assertThat((String) fresh.get("act")).isEqualTo("reject");
        assertThat((String) fresh.get("reason")).isEqualTo(reason);
        assertThat((String) fresh.get("uadmin")).isEqualTo(A);
    }

    /** T04-05 驳回理由边界：1 字符、200 字符（含补充平面 Unicode，按字符数）、带首尾空格均 200 且原文保留；缺失 body/键、null、空串、纯空白、201 字符、number/array/object 逐例 400 且零写入。 */
    @Test
    void aud_05_reasonLengthAndBodyValidation() throws Exception {
        seedWork(R1, U, "待审核", "2026-10-23 11:00:04");
        seedWork(R2, U, "待审核", "2026-10-23 11:00:05");
        seedWork(R3, U, "待审核", "2026-10-23 11:00:06");
        seedWork(R4, U, "待审核", "2026-10-23 11:00:07");
        seedWork(R5, U, "待审核", "2026-10-23 11:00:08");
        // 合法样本：1 字符。
        assertPidStatus(rejectAs(A, R1, "构"), R1, "已驳回");
        assertThat(logReasonOf(R1)).isEqualTo("构");
        // 合法样本：199 ASCII + 1 补充平面字符 = 200 个 Unicode 字符。
        String unicode200 = repeat("a", 199) + "\uD83D\uDE00";
        assertPidStatus(rejectAs(A, R2, unicode200), R2, "已驳回");
        assertThat(logReasonOf(R2)).isEqualTo(unicode200);
        // 合法样本：带首尾空格但含正文，原文保留不 trim。
        String spaced = "  构图  ";
        assertPidStatus(rejectAs(A, R3, spaced), R3, "已驳回");
        assertThat(logReasonOf(R3)).isEqualTo(spaced);
        // 201 字符逐例 400（200 ASCII + 1 补充平面、纯 ASCII 201）。
        String unicode201 = repeat("a", 200) + "\uD83D\uDE00";
        assertError(rejectAs(A, R4, unicode201), 400, "invalid_request");
        assertError(rejectAs(A, R5, repeat("a", 201)), 400, "invalid_request");
        assertThat(statusOfWork(R4)).isEqualTo("待审核");
        assertThat(statusOfWork(R5)).isEqualTo("待审核");
        assertThat(auditRowsOf(R4)).isEqualTo(0);
        assertThat(auditRowsOf(R5)).isEqualTo(0);
        // 非法 body 逐例 400，P1 状态与日志零增量。
        int p1Logs = auditRowsOf(P1);
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"x\":1}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":null}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":123}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":[\"a\"]}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":{\"a\":1}}")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))).andReturn(), 400, "invalid_request");
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
        assertThat(auditRowsOf(P1)).isEqualTo(p1Logs);
    }

    /** T04-06 重复与交叉决策 409：首次通过/驳回后同决策、改另一决策均 409；对展示/隐藏/已驳回执行 approve 与合法 reject 均 409；幂等为零额外效果，不是重复返回 200。 */
    @Test
    void aud_06_repeatAndCrossDecisionConflict() throws Exception {
        String reason = "首次驳回理由";
        assertPidStatus(approveAs(A, P1), P1, "展示");
        assertError(approveAs(A, P1), 409, "conflict");
        assertError(rejectAs(A, P1, "换一种理由"), 409, "conflict");
        assertPidStatus(rejectAs(A, P2, reason), P2, "已驳回");
        assertError(rejectAs(A, P2, "第二次驳回"), 409, "conflict");
        assertError(approveAs(A, P2), 409, "conflict");
        for (String pid : new String[]{P3, P4, P5}) {
            assertError(approveAs(A, pid), 409, "conflict");
            assertError(rejectAs(A, pid, "合法理由"), 409, "conflict");
        }
        // 最终状态及首次日志保持，后续零增量、不覆盖 reason。
        assertThat(statusOfWork(P1)).isEqualTo("展示");
        assertThat(statusOfWork(P2)).isEqualTo("已驳回");
        assertThat(auditRowsOf(P1)).isEqualTo(2);
        assertThat(auditRowsOf(P2)).isEqualTo(1);
        assertThat(logReasonOf(P2)).isEqualTo(reason);
        assertThat(auditRowsOf(P3)).isEqualTo(0);
        assertThat(auditRowsOf(P4)).isEqualTo(0);
        assertThat(auditRowsOf(P5)).isEqualTo(1);
    }

    /** T04-07 两个管理员并发审核同一新待审：approve/approve、reject/reject、approve/reject 每组恰一个 200 一个 409，最终只新增 1 条日志且与胜者请求逐值一致，无 500、无双成功或日志双写。 */
    @Test
    void aud_07_concurrentAuditSingleWinner() throws Exception {
        seedWork(G1, U, "待审核", "2026-10-23 11:00:01");
        seedWork(G2, U, "待审核", "2026-10-23 11:00:02");
        seedWork(G3, U, "待审核", "2026-10-23 11:00:03");
        String reason = "并发驳回理由";
        // 组 1：approve/approve。
        MvcResult[] group1 = runConcurrent(approveRequest(A, G1), approveRequest(B, G1));
        int winner1 = assertOneWinsOneConflict(group1);
        assertPidStatus(group1[winner1], G1, "展示");
        assertThat(statusOfWork(G1)).isEqualTo("展示");
        assertThat(auditRowsOf(G1)).isEqualTo(1);
        Map<String, Object> log1 = jdbc.queryForMap("SELECT act, reason, uadmin FROM waudit WHERE pid=?", G1);
        assertThat((String) log1.get("act")).isEqualTo("pass");
        assertThat(log1.get("reason")).isNull();
        assertThat((String) log1.get("uadmin")).isEqualTo(winner1 == 0 ? A : B);
        // 组 2：reject/reject。
        MvcResult[] group2 = runConcurrent(rejectRequest(A, G2, reason), rejectRequest(B, G2, reason));
        int winner2 = assertOneWinsOneConflict(group2);
        assertPidStatus(group2[winner2], G2, "已驳回");
        assertThat(statusOfWork(G2)).isEqualTo("已驳回");
        assertThat(auditRowsOf(G2)).isEqualTo(1);
        Map<String, Object> log2 = jdbc.queryForMap("SELECT act, reason, uadmin FROM waudit WHERE pid=?", G2);
        assertThat((String) log2.get("act")).isEqualTo("reject");
        assertThat((String) log2.get("reason")).isEqualTo(reason);
        assertThat((String) log2.get("uadmin")).isEqualTo(winner2 == 0 ? A : B);
        // 组 3：approve/reject 混合，胜者决定最终状态与日志。
        MvcResult[] group3 = runConcurrent(approveRequest(A, G3), rejectRequest(B, G3, reason));
        int winner3 = assertOneWinsOneConflict(group3);
        boolean approveWon = winner3 == 0;
        assertPidStatus(group3[winner3], G3, approveWon ? "展示" : "已驳回");
        assertThat(statusOfWork(G3)).isEqualTo(approveWon ? "展示" : "已驳回");
        assertThat(auditRowsOf(G3)).isEqualTo(1);
        Map<String, Object> log3 = jdbc.queryForMap("SELECT act, reason, uadmin FROM waudit WHERE pid=?", G3);
        assertThat((String) log3.get("act")).isEqualTo(approveWon ? "pass" : "reject");
        assertThat((String) log3.get("reason")).isEqualTo(approveWon ? null : reason);
        assertThat((String) log3.get("uadmin")).isEqualTo(approveWon ? A : B);
    }

    /** T04-08 真实状态 UPDATE 后插日志失败：approve/reject 各一例 500 internal_error，作品回到待审核、历史日志与行数不变、无孤立状态/日志，不把失败吞成 200/409。 */
    @Test
    void aud_08_logInsertFailureRollsBack() throws Exception {
        int before = auditRows();
        jdbc.execute("RENAME TABLE waudit TO " + WAUDIT_BACKUP);
        try {
            assertError(approveAs(A, P1), 500, "internal_error");
            assertThat(statusOfWork(P1)).isEqualTo("待审核");
            assertError(rejectAs(A, P2, "合法驳回理由"), 500, "internal_error");
            assertThat(statusOfWork(P2)).isEqualTo("待审核");
            Integer backupRows = jdbc.queryForObject("SELECT COUNT(*) FROM " + WAUDIT_BACKUP, Integer.class);
            assertThat(backupRows).isEqualTo(before);
        } finally {
            jdbc.execute("RENAME TABLE " + WAUDIT_BACKUP + " TO waudit");
        }
        assertThat(auditRows()).isEqualTo(before);
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
        assertThat(statusOfWork(P2)).isEqualTo("待审核");
        assertThat(logReasonOf(P1)).isEqualTo("旧驳回理由");
    }

    /** T04-09 不存在与非法 ID：合法不存在 404、编码 bad! 与超长 400、64 位合法不存在 uid 404、pid 只接受 1–36 位且不转 number，全部零写入。 */
    @Test
    void aud_09_missingAndMalformedIds() throws Exception {
        int before = auditRows();
        String badPid = "bad%21";
        String pidOver = repeat("a", PID_MAX_LENGTH + 1);
        String badUid = "bad%21";
        String uidOver = repeat("a", UID_MAX_LENGTH + 1);
        String uidMax = repeat("a", UID_MAX_LENGTH);
        // approve/reject 的 pid 样本。
        assertError(approveAs(A, M), 404, "not_found");
        assertError(approveAs(A, badPid), 400, "invalid_request");
        assertError(approveAs(A, pidOver), 400, "invalid_request");
        assertError(approveAs(A, "123"), 404, "not_found");
        assertError(rejectAs(A, M, "合法理由"), 404, "not_found");
        assertError(rejectAs(A, badPid, "合法理由"), 400, "invalid_request");
        assertError(rejectAs(A, pidOver, "合法理由"), 400, "invalid_request");
        assertError(rejectAs(A, "123", "合法理由"), 404, "not_found");
        // status/delete 的 uid 样本。
        assertError(statusAs(A, M, "禁用"), 404, "not_found");
        assertError(statusAs(A, badUid, "禁用"), 400, "invalid_request");
        assertError(statusAs(A, uidOver, "禁用"), 400, "invalid_request");
        assertError(statusAs(A, uidMax, "禁用"), 404, "not_found");
        assertError(deleteUserAs(A, M), 404, "not_found");
        assertError(deleteUserAs(A, badUid), 400, "invalid_request");
        assertError(deleteUserAs(A, uidOver), 400, "invalid_request");
        assertError(deleteUserAs(A, uidMax), 404, "not_found");
        // 零写入。
        assertThat(auditRows()).isEqualTo(before);
        assertThat(userExists(U)).isTrue();
        assertThat(statusOf(U)).isEqualTo("正常");
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（权限）
    // ------------------------------------------------------------------

    /** T04-10 U/O 正常普通用户有效 JWT 请求六管理入口逐例 403，自报 role=管理员、uid=A、uadmin=A 不能放行，零写入且不 302。 */
    @Test
    void aud_10_normalUserForbiddenAllAdminRoutes() throws Exception {
        int before = auditRows();
        for (String actor : new String[]{U, O}) {
            for (MockHttpServletRequestBuilder request : adminRequests(token(actor, "普通用户"), null)) {
                assertError(mvc.perform(request).andReturn(), 403, "forbidden");
            }
        }
        // 自报身份字段不能覆盖验证身份。
        assertError(mvc.perform(get("/admin/work/pending").header("Authorization", token(U, "普通用户"))
                .param("role", "管理员").param("uid", A)).andReturn(), 403, "forbidden");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/approve").header("Authorization", token(U, "普通用户"))
                .param("role", "管理员").param("uid", A)).andReturn(), 403, "forbidden");
        assertError(mvc.perform(put("/admin/work/" + P1 + "/reject").header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"合法理由\",\"role\":\"管理员\",\"uid\":\"" + A + "\",\"uadmin\":\"" + A + "\"}")
        ).andReturn(), 403, "forbidden");
        assertError(mvc.perform(get("/admin/user/list").header("Authorization", token(U, "普通用户"))
                .param("role", "管理员").param("uid", A)).andReturn(), 403, "forbidden");
        assertError(mvc.perform(put("/admin/user/" + U + "/status").header("Authorization", token(U, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"禁用\",\"role\":\"管理员\",\"uid\":\"" + A + "\"}")
        ).andReturn(), 403, "forbidden");
        assertError(mvc.perform(delete("/admin/user/" + U).header("Authorization", token(U, "普通用户"))
                .param("role", "管理员").param("uid", A)).andReturn(), 403, "forbidden");
        // 零写入：本人作品、本人账号均不能因自报字段放行。
        assertThat(auditRows()).isEqualTo(before);
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
        assertThat(statusOfWork(P2)).isEqualTo("待审核");
        assertThat(statusOf(U)).isEqualTo("正常");
        assertThat(userExists(U)).isTrue();
    }

    /** T04-11 无 Authorization 且无 Cookie 的六入口逐例 401 unauthorized，data=null，非空页伪成功、非 302，目标与日志不变。 */
    @Test
    void aud_11_anonymousUnauthorizedAllAdminRoutes() throws Exception {
        int before = auditRows();
        for (MockHttpServletRequestBuilder request : adminRequests(null, null)) {
            assertError(mvc.perform(request).andReturn(), 401, "unauthorized");
        }
        assertThat(auditRows()).isEqualTo(before);
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
        assertThat(statusOf(U)).isEqualTo("正常");
        assertThat(userExists(U)).isTrue();
    }

    /** T04-12 坏签名/过期/主体不存在/主体禁用/坏头加有效 Cookie 逐例 401；合法签名空/未知/带空格角色逐例 403；仅 Cookie 有效管理员 200 且返回真实分页。 */
    @Test
    void aud_12_badCredentialsRolesAndCookieOnly() throws Exception {
        int before = auditRows();
        String good = jwtUtils.generateToken(A, "管理员");
        String[] variants = new String[]{
                "Bearer " + corrupt(good),
                "Bearer " + expiredToken(A, "管理员"),
                token("c04-ghost", "管理员"),
                token(D, "普通用户")
        };
        for (String variant : variants) {
            for (MockHttpServletRequestBuilder request : adminRequests(variant, null)) {
                assertError(mvc.perform(request).andReturn(), 401, "unauthorized");
            }
        }
        // 坏 Authorization 头不能回退 Cookie。
        Cookie validCookie = new Cookie("token", jwtUtils.generateToken(A, "管理员"));
        for (MockHttpServletRequestBuilder request : adminRequests("Bearer not.a.valid.token", validCookie)) {
            assertError(mvc.perform(request).andReturn(), 401, "unauthorized");
        }
        // 合法签名但角色不在白名单。
        for (String role : new String[]{"", "访客", " 管理员 "}) {
            for (MockHttpServletRequestBuilder request : adminRequests(token(A, role), null)) {
                assertError(mvc.perform(request).andReturn(), 403, "forbidden");
            }
        }
        // 仅 Cookie 有效管理员成功返回真实 Page<Work>。
        MvcResult cookieOnly = mvc.perform(get("/admin/work/pending").cookie(validCookie)).andReturn();
        assertPendingPage(cookieOnly, 3L, P1, P2, P6);
        assertThat(auditRows()).isEqualTo(before);
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
        assertThat(userExists(U)).isTrue();
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（用户列表与状态）
    // ------------------------------------------------------------------

    /** T04-13 用户列表八键 AU：nickname=uname、pnum 管理员视角完整返回且 null 保持 null、createdAt 精确 +08:00、无 pwd/openid 等额外键、total 与库内实际一致。 */
    @Test
    void aud_13_adminUserListShapeAndPhonePolicy() throws Exception {
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM user", Long.class);
        assertThat(total).as("前置：隔离库用户总数应可单页容纳").isLessThanOrEqualTo(50);
        JsonNode data = assertOk(mvc.perform(get("/admin/user/list")
                .header("Authorization", token(A, "管理员")).param("page", "1").param("size", "50")).andReturn());
        assertKeys(data, "list", "total");
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isEqualTo(total);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo((int) total);
        for (JsonNode item : list) {
            assertAdminUser(item, item.get("uid").asText());
        }
    }

    /** T04-14 用户列表 keyword：省略/空串无筛选、命中部分 uname、无命中空页、50 字符合法 51 字符 400；匹配集 11 条时分页边界与非法 page/size 逐例 400。 */
    @Test
    void aud_14_userListKeywordAndPaging() throws Exception {
        long allUsers = jdbc.queryForObject("SELECT COUNT(*) FROM user", Long.class);
        // 省略 keyword。
        assertUserPage(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("size", "50")).andReturn(), allUsers, (int) Math.min(allUsers, 50));
        // 空串 = 无筛选。
        assertUserPage(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("keyword", "").param("size", "50")).andReturn(), allUsers, (int) Math.min(allUsers, 50));
        // 命中部分 uname（昵称字段名不能误当数据库 nickname 列，查询目标是 uname）。
        assertUserMembers(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("keyword", "c04管理员").param("size", "50")).andReturn(), 2L, A, B);
        // 无命中空页。
        assertUserMembers(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("keyword", "c04不存在xx")).andReturn(), 0L);
        // 50 字符合法、51 字符 400。
        assertThat(assertOk(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("keyword", repeat("k", 50))).andReturn()).get("total").asLong()).isEqualTo(0L);
        assertError(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("keyword", repeat("k", 51))).andReturn(), 400, "invalid_request");
        // 匹配集 11 条分页样本。
        String[] members = seedElevenKeywordUsers();
        assertUserMembers(getUserList(A, "c04kw", null, null), 11L, firstTen(members));
        assertUserMembers(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                .param("keyword", "c04kw").param("pageNum", "2").param("pageSize", "1")).andReturn(),
                11L, firstTen(members));
        assertUserMembers(getUserList(A, "c04kw", "1", "1"), 11L, members[0]);
        assertUserMembers(getUserList(A, "c04kw", "1", "50"), 11L, members);
        assertUserMembers(getUserList(A, "c04kw", "999", "50"), 11L);
        // 页间无重复遗漏。
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            for (JsonNode item : assertOk(getUserList(A, "c04kw", String.valueOf(page), "6")).get("list")) {
                assertThat(seen.add(item.get("uid").asText())).isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(members);
        // 非法 page/size 逐例 400。
        for (String page : new String[]{"0", "-1", "1.5", "x", PAGE_OVERFLOW}) {
            assertError(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                    .param("page", page)).andReturn(), 400, "invalid_request");
        }
        for (String size : new String[]{"0", "-1", SIZE_OVER_LIMIT, "1.5", "x", PAGE_OVERFLOW}) {
            assertError(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))
                    .param("size", size)).andReturn(), 400, "invalid_request");
        }
    }

    /** T04-15 旧接口与脱敏口径：U 管理列表 403；/user/info、/user/{uid} 保持旧字段与旧时间口径，11 位遮 138****0005、非 11 位为 ****、null 保持 null，管理员读全号；公开对象无 pnum。 */
    @Test
    void aud_15_legacyProfileMasking() throws Exception {
        assertError(mvc.perform(get("/admin/user/list").header("Authorization", token(U, "普通用户")))
                .andReturn(), 403, "forbidden");
        // U 查本人 /user/info：SelfUserCompat 十键，pnum 遮盖。
        JsonNode uInfo = assertOk(mvc.perform(get("/user/info").header("Authorization", token(U, "普通用户")))
                .andReturn());
        assertKeys(uInfo, "uid", "nickname", "avatar", "bio", "role", "uname", "pnum", "vote", "uct", "status");
        assertThat(uInfo.get("uid").asText()).isEqualTo(U);
        assertThat(uInfo.get("pnum").asText()).isEqualTo(mask11(pnumOf(U)));
        assertThat(uInfo.get("uct").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        // U 查本人 /user/{uid}：LegacyUser 八键，同样遮盖。
        JsonNode uSelf = assertOk(mvc.perform(get("/user/" + U).header("Authorization", token(U, "普通用户")))
                .andReturn());
        assertKeys(uSelf, "uid", "uname", "pnum", "role", "avatar", "vote", "uct", "status");
        assertThat(uSelf.get("pnum").asText()).isEqualTo(mask11(pnumOf(U)));
        // A 查 /user/U：管理员可读全号。
        JsonNode aView = assertOk(mvc.perform(get("/user/" + U).header("Authorization", token(A, "管理员")))
                .andReturn());
        assertThat(aView.get("pnum").asText()).isEqualTo(pnumOf(U));
        // 非 11 位号码：****；null：保持 null。
        JsonNode nInfo = assertOk(mvc.perform(get("/user/info").header("Authorization", token(N, "普通用户")))
                .andReturn());
        assertThat(nInfo.get("pnum").asText()).isEqualTo("****");
        JsonNode eInfo = assertOk(mvc.perform(get("/user/info").header("Authorization", token(E, "普通用户")))
                .andReturn());
        assertThat(eInfo.get("pnum").isNull()).isTrue();
        // 公开 Work 作者字段无 pnum。
        MvcResult detail = mvc.perform(get("/pwork/" + P3)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(detail.getResponse().getContentAsString()).doesNotContain("pnum");
        JsonNode detailJson = parseBody(detail);
        assertKeys(detailJson.get("data"), "work", "exif", "author");
        assertKeys(detailJson.get("data").get("author"), "uid", "nickname", "avatar");
    }

    /** T04-16 改用户状态只改 status：data 恰为 {uid,status}，禁用/正常往返，不改 role、作品状态、审核日志，不擅自隐藏禁用作者作品。 */
    @Test
    void aud_16_statusChangeTouchesOnlyStatus() throws Exception {
        String p3Before = pworkRowSnapshot(P3);
        int before = auditRows();
        assertUidStatus(statusAs(A, U, "禁用"), U, "禁用");
        assertThat(statusOf(U)).isEqualTo("禁用");
        assertThat(roleOf(U)).isEqualTo("普通用户");
        // 契约变更（docs/接口增补_ADMIN.md §5.2，2026-11-06 用户拍板）：
        // 禁用用户时其"展示"作品必须同事务改为"隐藏"，因此这里不再断言整行快照不变。
        assertThat(statusOfWork(P3)).as("禁用后展示作品应变隐藏").isEqualTo("隐藏");
        assertThat(pworkRowSnapshot(P3)).as("wstatus 由展示变隐藏，整行快照应有差异")
                .isNotEqualTo(p3Before);
        assertThat(auditRows()).as("禁用不写审核日志").isEqualTo(before);
        assertUidStatus(statusAs(A, U, "正常"), U, "正常");
        assertThat(statusOf(U)).isEqualTo("正常");
        assertThat(statusOfWork(P3)).as("解禁不自动恢复作品").isEqualTo("隐藏");
        assertThat(auditRows()).isEqualTo(before);
    }

    /** T04-17 禁用即刻生效：U 与 B 的旧 JWT 立即 401，不等 JWT 过期；重新启用后复用合法未过期 JWT 恢复 200，身份仍取已签名 claim。 */
    @Test
    void aud_17_disableImmediate401ReenableRestores() throws Exception {
        String uJwt = token(U, "普通用户");
        String bJwt = token(B, "管理员");
        assertUidStatus(statusAs(A, U, "禁用"), U, "禁用");
        assertError(mvc.perform(get("/user/info").header("Authorization", uJwt)).andReturn(), 401, "unauthorized");
        assertUidStatus(statusAs(A, B, "禁用"), B, "禁用");
        assertError(mvc.perform(get("/admin/work/pending").header("Authorization", bJwt))
                .andReturn(), 401, "unauthorized");
        assertUidStatus(statusAs(A, U, "正常"), U, "正常");
        assertUidStatus(statusAs(A, B, "正常"), B, "正常");
        assertOk(mvc.perform(get("/user/info").header("Authorization", uJwt)).andReturn());
        assertPendingPage(mvc.perform(get("/admin/work/pending").header("Authorization", bJwt))
                .andReturn(), 3L, P1, P2, P6);
    }

    /** T04-18 状态输入严格枚举：缺 body/键、null、空串、空白、带空格、其它字符串、number/array/object 逐例 400，原状态不变，无隐式 trim/类型强转。 */
    @Test
    void aud_18_statusInputStrictEnumNoTrim() throws Exception {
        String[] bodies = new String[]{
                "{\"x\":1}",
                "{\"status\":null}",
                "{\"status\":\"\"}",
                "{\"status\":\"   \"}",
                "{\"status\":\" 禁用 \"}",
                "{\"status\":\"正常 \"}",
                "{\"status\":\"其它\"}",
                "{\"status\":123}",
                "{\"status\":[\"禁用\"]}",
                "{\"status\":{\"a\":1}}"
        };
        for (String body : bodies) {
            assertError(mvc.perform(put("/admin/user/" + U + "/status")
                    .header("Authorization", token(A, "管理员"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 400, "invalid_request");
        }
        assertError(mvc.perform(put("/admin/user/" + U + "/status")
                .header("Authorization", token(A, "管理员"))).andReturn(), 400, "invalid_request");
        assertThat(statusOf(U)).isEqualTo("正常");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（保护与删除）
    // ------------------------------------------------------------------

    /** T04-19 自删/自禁 403：有 B 与仅 A 两个场景均 403，自己且最后一人仍按 403；自身状态设为正常 200 且返回 {uid,status=正常}。 */
    @Test
    void aud_19_selfOperationsForbiddenSelfNormalAllowed() throws Exception {
        int before = auditRows();
        assertError(deleteUserAs(A, A), 403, "forbidden");
        assertError(statusAs(A, A, "禁用"), 403, "forbidden");
        assertThat(statusOf(A)).isEqualTo("正常");
        // 仅 A 一个正常管理员时仍按 403 而非 409。
        jdbc.update("DELETE FROM user WHERE uid=?", B);
        assertThat(normalAdminCount()).isEqualTo(1);
        assertError(deleteUserAs(A, A), 403, "forbidden");
        assertError(statusAs(A, A, "禁用"), 403, "forbidden");
        assertThat(statusOf(A)).isEqualTo("正常");
        assertUidStatus(statusAs(A, A, "正常"), A, "正常");
        assertThat(statusOf(A)).isEqualTo("正常");
        assertThat(auditRows()).isEqualTo(before);
        assertThat(userExists(A)).isTrue();
    }

    /** T04-20 最后正常管理员保护：仅 L 时 C（合法管理员 claim、数据库普通/正常）删除/禁用 L 均 409；有替代正常管理员时可禁用或删除其中非本人一个，禁用管理员不计入替代人数。 */
    @Test
    void aud_20_lastAdminProtected() throws Exception {
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'c04-%' OR pid LIKE 'c04-%' OR uadmin LIKE 'c04-%'");
        jdbc.update("DELETE FROM user WHERE uid IN (?,?)", A, B);
        assertThat(normalAdminCount()).as("前置：独立 04 库应无其它正常管理员").isEqualTo(0);
        seedAdmin(L, "c04末位管理员L", "19900000106");
        seedUser(C, "c04降级操作者C", "普通用户", "19900000107", "正常", null, "/avatar/c04-c.png");
        assertError(deleteUserAs(C, L), 409, "conflict");
        assertError(statusAs(C, L, "禁用"), 409, "conflict");
        assertThat(userExists(L)).isTrue();
        assertThat(statusOf(L)).isEqualTo("正常");
        assertThat(roleOf(L)).isEqualTo("管理员");
        assertThat(normalAdminCount()).isEqualTo(1);
        // 有替代正常管理员：L1 禁用 L2 后剩 2 个正常管理员（L、L1）。
        seedAdmin(L1, "c04管理员L1", "19900000108");
        seedAdmin(L2, "c04管理员L2", "19900000109");
        assertUidStatus(statusAs(L1, L2, "禁用"), L2, "禁用");
        assertThat(normalAdminCount()).isEqualTo(2);
        assertThat(statusOf(L2)).isEqualTo("禁用");
        // 有替代正常管理员：L1 删除 L2 后仍剩 2 个正常管理员（L、L1）。
        jdbc.update("UPDATE user SET status='正常' WHERE uid=?", L2);
        assertDeleted(deleteUserAs(L1, L2));
        assertThat(userExists(L2)).isFalse();
        assertThat(normalAdminCount()).isEqualTo(2);
    }

    /** T04-21 最后管理员并发保护：C1/C2 同时操作 L1/L2，禁用/禁用、删除/删除、禁用/删除每组恰一个 200 一个 409，最终数据库正常管理员数=1，被拒目标不变，无双成功归零、无死锁 500。 */
    @Test
    void aud_21_concurrentLastAdminSingleWinner() throws Exception {
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'c04-%' OR pid LIKE 'c04-%' OR uadmin LIKE 'c04-%'");
        jdbc.update("DELETE FROM user WHERE uid IN (?,?)", A, B);
        assertThat(normalAdminCount()).as("前置：独立 04 库应无其它正常管理员").isEqualTo(0);
        seedAdmin(L1, "c04管理员L1", "19900000108");
        seedAdmin(L2, "c04管理员L2", "19900000109");
        seedUser(C1, "c04操作者C1", "普通用户", "19900000110", "正常", null, "/avatar/c04-c1.png");
        seedUser(C2, "c04操作者C2", "普通用户", "19900000111", "正常", null, "/avatar/c04-c2.png");
        // 组 1：禁用/禁用。
        MvcResult[] group1 = runConcurrent(statusRequest(C1, L1, "禁用"), statusRequest(C2, L2, "禁用"));
        int winner1 = assertOneWinsOneConflict(group1);
        assertThat(statusOf(winner1 == 0 ? L1 : L2)).isEqualTo("禁用");
        assertThat(statusOf(winner1 == 0 ? L2 : L1)).isEqualTo("正常");
        assertThat(normalAdminCount()).isEqualTo(1);
        jdbc.update("UPDATE user SET status='正常' WHERE uid IN (?,?)", L1, L2);
        // 组 2：删除/删除。
        MvcResult[] group2 = runConcurrent(deleteUserRequest(C1, L1), deleteUserRequest(C2, L2));
        int winner2 = assertOneWinsOneConflict(group2);
        String deleted = winner2 == 0 ? L1 : L2;
        String kept = winner2 == 0 ? L2 : L1;
        assertThat(userExists(deleted)).isFalse();
        assertThat(userExists(kept)).isTrue();
        assertThat(statusOf(kept)).isEqualTo("正常");
        assertThat(normalAdminCount()).isEqualTo(1);
        seedAdmin(deleted, deleted.equals(L1) ? "c04管理员L1" : "c04管理员L2",
                deleted.equals(L1) ? "19900000108" : "19900000109");
        // 组 3：禁用/删除混合。
        MvcResult[] group3 = runConcurrent(statusRequest(C1, L1, "禁用"), deleteUserRequest(C2, L2));
        int winner3 = assertOneWinsOneConflict(group3);
        if (winner3 == 0) {
            assertThat(statusOf(L1)).isEqualTo("禁用");
            assertThat(userExists(L2)).isTrue();
            assertThat(statusOf(L2)).isEqualTo("正常");
        } else {
            assertThat(userExists(L2)).isFalse();
            assertThat(statusOf(L1)).isEqualTo("正常");
        }
        assertThat(normalAdminCount()).isEqualTo(1);
    }

    /** T04-22 有作品的用户删除 409：待审核/展示/隐藏/已驳回逐状态均拒绝，U、作品、从属行、审核历史原样保留，不级联删作品、不只检查展示作品、不为删除清空其它 RESTRICT 关系。 */
    @Test
    void aud_22_deleteUserWithWorksConflict() throws Exception {
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c04-%'");
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'c04-%' OR pid LIKE 'c04-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'c04-%' OR targetid LIKE 'c04-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'c04-%' OR pid LIKE 'c04-%'");
        String[] statuses = new String[]{"待审核", "展示", "隐藏", "已驳回"};
        for (int i = 0; i < statuses.length; i++) {
            String q = String.format("c04-q%d", i + 1);
            seedWork(q, U, statuses[i], "2026-10-23 13:00:00");
            seedAudit("c04-qa" + (i + 1), q, "reject", "Q理由" + (i + 1), A, "2026-10-20 12:00:00");
            seedLike("c04-ql" + (i + 1), U, q, "帖子");
            seedFavorite("c04-qf" + (i + 1), U, q, "2026-10-23 12:00:00");
            String workBefore = pworkRowSnapshot(q);
            assertError(deleteUserAs(A, U), 409, "conflict");
            assertThat(userExists(U)).isTrue();
            assertThat(pworkRowSnapshot(q)).isEqualTo(workBefore);
            assertThat(auditRowsOf(q)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM liketable WHERE targetid=? AND targettype='帖子'", Integer.class, q))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorite WHERE pid=?", Integer.class, q))
                    .isEqualTo(1);
        }
    }

    /** T04-23 删除无作品的非本人普通用户：首次 200 且 data=null 只删 O，旧 JWT 立即 401，再次删除 404，不是删除幂等 200 私有约定。 */
    @Test
    void aud_23_deleteUserSuccessThenGone() throws Exception {
        jdbc.update("DELETE FROM pwork WHERE pid=?", P6);
        String oJwt = token(O, "普通用户");
        assertDeleted(deleteUserAs(A, O));
        assertThat(userExists(O)).isFalse();
        assertThat(userExists(A)).isTrue();
        assertThat(userExists(U)).isTrue();
        assertThat(userExists(D)).isTrue();
        assertThat(userExists(E)).isTrue();
        assertThat(userExists(N)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pwork WHERE pid LIKE 'c04-%'", Integer.class))
                .isEqualTo(5);
        assertThat(auditRows()).isEqualTo(2);
        assertError(mvc.perform(get("/user/info").header("Authorization", oJwt)).andReturn(), 401, "unauthorized");
        assertError(deleteUserAs(A, O), 404, "not_found");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（跨卡查询）
    // ------------------------------------------------------------------

    /** T04-24 真实驳回后 mine 可见：U 的 Page<MineWork> 中 P2.wstatus=已驳回且 reason 等于本次原文，O 结果不含 P2，公开 Work 不得新增 reason，不是 Mock mine 服务。 */
    @Test
    void aud_24_rejectVisibleInMineWithReason() throws Exception {
        String reason = "作品构图需要改进";
        assertPidStatus(rejectAs(A, P2, reason), P2, "已驳回");
        JsonNode uMine = assertOk(mvc.perform(get("/pwork/mine").header("Authorization", token(U, "普通用户"))
                .param("status", "已驳回")).andReturn());
        assertKeys(uMine, "list", "total");
        assertThat(uMine.get("total").asLong()).isEqualTo(2L);
        JsonNode uList = uMine.get("list");
        assertThat(uList.isArray()).isTrue();
        assertThat(uList.size()).isEqualTo(2);
        assertThat(uList.get(0).get("pid").asText()).isEqualTo(P2);
        assertThat(uList.get(1).get("pid").asText()).isEqualTo(P5);
        for (JsonNode item : uList) {
            assertMineWork(item);
        }
        assertThat(findItem(uMine, P2).get("reason").asText()).isEqualTo(reason);
        assertThat(findItem(uMine, P5).get("reason").asText()).isEqualTo("P5驳回理由");
        // O 的 mine 不含 P2。
        JsonNode oMine = assertOk(mvc.perform(get("/pwork/mine").header("Authorization", token(O, "普通用户")))
                .andReturn());
        assertThat(oMine.get("total").asLong()).isEqualTo(1L);
        assertThat(oMine.get("list").get(0).get("pid").asText()).isEqualTo(P6);
        assertThat(findItem(oMine, P6).get("reason").isNull()).isTrue();
        assertThat(oMine.get("list").get(0).has("pid")).isTrue();
        // 公开列表条目只出十四键 W，无 reason。
        JsonNode pub = assertOk(mvc.perform(get("/pwork/list")).andReturn());
        for (JsonNode item : pub.get("list")) {
            assertWork(item);
        }
    }

    /** T04-25 mine 最近日志规则：ct 不同取最新、ct 相同 aid 取降序较大、最新 pass 或无日志 reason=null 且键存在、不倒找最近非空；03 合法重提为待审后真实审核不覆盖旧日志。 */
    @Test
    void aud_25_mineReasonLatestRuleAndResubmit() throws Exception {
        seedWork(H1, U, "已驳回", "2026-10-23 10:00:07");
        seedAudit("c04-h1-a", H1, "reject", "旧理由H1", A, "2026-10-20 09:00:00");
        seedAudit("c04-h1-b", H1, "reject", "最新理由H1", A, "2026-10-20 09:01:00");
        seedWork(H2, U, "已驳回", "2026-10-23 10:00:08");
        seedAudit("c04-h2-b", H2, "reject", "并列大aid理由", A, "2026-10-20 09:00:00");
        seedAudit("c04-h2-a", H2, "reject", "并列小aid理由", A, "2026-10-20 09:00:00");
        seedWork(H3, U, "展示", "2026-10-23 10:00:09");
        seedAudit("c04-h3-old", H3, "reject", "历史驳回理由", A, "2026-10-20 09:00:00");
        seedAudit("c04-h3-pass", H3, "pass", null, A, "2026-10-20 09:01:00");
        seedWork(H4, U, "已驳回", "2026-10-23 10:00:10");
        JsonNode mine = assertOk(mvc.perform(get("/pwork/mine").header("Authorization", token(U, "普通用户")))
                .andReturn());
        assertThat(mine.get("total").asLong()).isEqualTo(9L);
        assertThat(mine.get("list").size()).isEqualTo(9);
        for (JsonNode item : mine.get("list")) {
            assertMineWork(item);
        }
        assertThat(findItem(mine, H1).get("reason").asText()).isEqualTo("最新理由H1");
        assertThat(findItem(mine, H2).get("reason").asText()).isEqualTo("并列大aid理由");
        assertThat(findItem(mine, H3).get("reason").isNull()).isTrue();
        assertThat(findItem(mine, H4).get("reason").isNull()).isTrue();
        assertThat(findItem(mine, P1).get("reason").asText()).isEqualTo("旧驳回理由");
        assertThat(findItem(mine, P5).get("reason").asText()).isEqualTo("P5驳回理由");
        assertThat(findItem(mine, P2).get("reason").isNull()).isTrue();
        assertThat(findItem(mine, P3).get("reason").isNull()).isTrue();
        assertThat(findItem(mine, P4).get("reason").isNull()).isTrue();
        // 03 合法重提为待审后真实审核，不覆盖旧日志。
        seedCategory("c04-cat", "重提分类", 1);
        assertPidStatus(resubmitAs(U, H4, "H4新标题", "H4新描述"), H4, "待审核");
        assertPidStatus(rejectAs(A, H4, "重提后驳回理由"), H4, "已驳回");
        assertPidStatus(resubmitAs(U, H3, "H3新标题", "H3新描述"), H3, "待审核");
        assertPidStatus(approveAs(A, H3), H3, "展示");
        assertThat(auditRowsOf(H3)).isEqualTo(3);
        assertThat(auditRowsOf(H4)).isEqualTo(1);
        Map<String, Object> h3Old = jdbc.queryForMap("SELECT act, reason FROM waudit WHERE aid=?", "c04-h3-old");
        assertThat((String) h3Old.get("act")).isEqualTo("reject");
        assertThat((String) h3Old.get("reason")).isEqualTo("历史驳回理由");
        JsonNode mineAfter = assertOk(mvc.perform(get("/pwork/mine").header("Authorization", token(U, "普通用户")))
                .andReturn());
        assertThat(findItem(mineAfter, H4).get("reason").asText()).isEqualTo("重提后驳回理由");
        assertThat(findItem(mineAfter, H3).get("reason").isNull()).isTrue();
    }

    /** T04-26 公开可见性跟随审核：待审/驳回详情 404 且列表不含，通过后详情 200 且列表可见；列表 total 只计展示；作者 mine 仍可见四状态；详情浏览 +1 沿用 03 而非审核副作用。 */
    @Test
    void aud_26_publicVisibilityFollowsAudit() throws Exception {
        long shown = jdbc.queryForObject("SELECT COUNT(*) FROM pwork WHERE wstatus='展示'", Long.class);
        JsonNode pubBefore = assertOk(mvc.perform(get("/pwork/list")).andReturn());
        assertThat(pubBefore.get("total").asLong()).isEqualTo(shown);
        assertThat(containsPid(pubBefore, P1)).isFalse();
        assertThat(containsPid(pubBefore, P2)).isFalse();
        assertThat(containsPid(pubBefore, P3)).isTrue();
        assertError(mvc.perform(get("/pwork/" + P1)).andReturn(), 404, "not_found");
        assertThat(viewcountOfWork(P1)).isEqualTo(5);
        // 审核本身不增加浏览。
        assertPidStatus(approveAs(A, P1), P1, "展示");
        assertThat(viewcountOfWork(P1)).isEqualTo(5);
        JsonNode detail = assertOk(mvc.perform(get("/pwork/" + P1)).andReturn());
        assertKeys(detail, "work", "exif", "author");
        assertThat(detail.get("work").get("wstatus").asText()).isEqualTo("展示");
        assertThat(detail.get("work").get("viewcount").asLong()).isEqualTo(6L);
        JsonNode pubAfter = assertOk(mvc.perform(get("/pwork/list")).andReturn());
        assertThat(containsPid(pubAfter, P1)).isTrue();
        assertThat(pubAfter.get("total").asLong()).isEqualTo(shown + 1);
        // 驳回后详情 404、列表不可见。
        assertPidStatus(rejectAs(A, P2, "驳回理由P2"), P2, "已驳回");
        assertError(mvc.perform(get("/pwork/" + P2)).andReturn(), 404, "not_found");
        assertThat(viewcountOfWork(P2)).isEqualTo(1);
        JsonNode pubFinal = assertOk(mvc.perform(get("/pwork/list")).andReturn());
        assertThat(containsPid(pubFinal, P2)).isFalse();
        // 作者 mine 仍可见四状态。
        JsonNode mine = assertOk(mvc.perform(get("/pwork/mine").header("Authorization", token(U, "普通用户")))
                .andReturn());
        assertThat(mine.get("total").asLong()).isEqualTo(5L);
        Set<String> pids = new HashSet<>();
        mine.get("list").forEach(item -> pids.add(item.get("pid").asText()));
        assertThat(pids).containsExactlyInAnyOrder(P1, P2, P3, P4, P5);
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（共享响应/页面/副作用）
    // ------------------------------------------------------------------

    /** T04-27 六接口成功与 400/401/403/404/409/500 信封汇总：Long 不溢出（INT UNSIGNED 原样输出、realLikeCount 只计帖子赞）、AU 含 nullable 资料、错误无顶层 total/success/status/message、无 SQL 或敏感信息。 */
    @Test
    void aud_27_envelopesCountersAndSafeErrors() throws Exception {
        seedWork(W27, U, "待审核", "2026-10-23 14:00:00", INT_UNSIGNED_SAMPLE, INT_UNSIGNED_SAMPLE,
                INT_UNSIGNED_SAMPLE);
        seedLike("c04-l27-1", U, W27, "帖子");
        seedLike("c04-l27-2", O, W27, "帖子");
        seedLike("c04-l27-3", O, W27, "评论");
        seedWork(W28, U, "待审核", "2026-10-23 14:00:01");
        seedWork(W29, U, "待审核", "2026-10-23 14:00:02");
        // 成功：待审分页 Long 计数与真实帖子赞。
        JsonNode pending = assertOk(mvc.perform(get("/admin/work/pending")
                .header("Authorization", token(A, "管理员")).param("size", "50")).andReturn());
        JsonNode w27 = findItem(pending, W27);
        assertCount(w27.get("viewcount"), INT_UNSIGNED_SAMPLE);
        assertCount(w27.get("votecount"), INT_UNSIGNED_SAMPLE);
        assertCount(w27.get("likep"), INT_UNSIGNED_SAMPLE);
        assertCount(w27.get("realLikeCount"), 2L);
        // 成功：审核/状态/删除。
        assertPidStatus(approveAs(A, W28), W28, "展示");
        assertPidStatus(rejectAs(A, W29, "汇总驳回理由"), W29, "已驳回");
        assertUidStatus(statusAs(A, U, "禁用"), U, "禁用");
        assertUidStatus(statusAs(A, U, "正常"), U, "正常");
        seedUser("c04-x", "c04待删用户X", "普通用户", "19900000112", "正常", null, "/avatar/c04-x.png");
        assertDeleted(deleteUserAs(A, "c04-x"));
        // 成功：用户列表含 nullable 资料八键。
        JsonNode userList = assertOk(mvc.perform(get("/admin/user/list")
                .header("Authorization", token(A, "管理员")).param("size", "50")).andReturn());
        assertAdminUser(findItem(userList, E), E);
        // 错误信封汇总。
        assertError(mvc.perform(get("/admin/work/pending").header("Authorization", token(A, "管理员"))
                .param("page", "0")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(get("/admin/work/pending")).andReturn(), 401, "unauthorized");
        assertError(mvc.perform(get("/admin/work/pending").header("Authorization", token(U, "普通用户")))
                .andReturn(), 403, "forbidden");
        assertError(approveAs(A, M), 404, "not_found");
        assertError(approveAs(A, P3), 409, "conflict");
        int before = auditRows();
        jdbc.execute("RENAME TABLE waudit TO " + WAUDIT_BACKUP);
        try {
            assertError(approveAs(A, W27), 500, "internal_error");
        } finally {
            jdbc.execute("RENAME TABLE " + WAUDIT_BACKUP + " TO waudit");
        }
        assertThat(auditRows()).isEqualTo(before);
        assertThat(statusOfWork(W27)).isEqualTo("待审核");
    }

    /** T04-28 审核页面授权：A 200 且解析 admin/audit，U 与匿名 302 至 /login/page，API 仍为 401/403 JSON。 */
    @Test
    void aud_28_adminAuditPageAuth() throws Exception {
        MvcResult aPage = mvc.perform(get("/page/admin/audit").header("Authorization", token(A, "管理员")))
                .andReturn();
        assertThat(aPage.getResponse().getStatus()).isEqualTo(200);
        assertThat(aPage.getModelAndView()).isNotNull();
        assertThat(aPage.getModelAndView().getViewName()).isEqualTo("admin/audit");
        MvcResult uPage = mvc.perform(get("/page/admin/audit").header("Authorization", token(U, "普通用户")))
                .andReturn();
        assertThat(uPage.getResponse().getStatus()).isEqualTo(302);
        assertThat(uPage.getResponse().getRedirectedUrl()).isEqualTo("/login/page");
        MvcResult anonPage = mvc.perform(get("/page/admin/audit")).andReturn();
        assertThat(anonPage.getResponse().getStatus()).isEqualTo(302);
        assertThat(anonPage.getResponse().getRedirectedUrl()).isEqualTo("/login/page");
        assertError(mvc.perform(get("/admin/work/pending").header("Authorization", token(U, "普通用户")))
                .andReturn(), 403, "forbidden");
        assertError(mvc.perform(get("/admin/work/pending")).andReturn(), 401, "unauthorized");
    }

    /** T04-29 读取零副作用、审核副作用精确：读待审/用户分页前后作品/用户/日志不变；合法审核只改 wstatus 与新增日志，uadmin 仍为已验证 A，客户端字段不能改操作者，不触发发布或 MsgSecCheck。 */
    @Test
    void aud_29_readsAndAuditSideEffects() throws Exception {
        int auditBefore = auditRows();
        Map<String, List<String>> before = snapshotTables(new String[]{"pwork", "user", "waudit", "work_exif",
                "work_embedding", "liketable", "favorite"});
        Map<String, String> worksBeforeWithoutStatus = new HashMap<>();
        for (String pid : new String[]{P1, P2, P3, P4, P5, P6}) {
            worksBeforeWithoutStatus.put(pid, pworkSnapshotWithoutStatus(pid));
        }
        assertPendingPage(mvc.perform(get("/admin/work/pending").header("Authorization", token(A, "管理员")))
                .andReturn(), 3L, P1, P2, P6);
        assertOk(mvc.perform(get("/admin/user/list").header("Authorization", token(A, "管理员"))).andReturn());
        assertThat(snapshotTables(new String[]{"pwork", "user", "waudit", "work_exif", "work_embedding",
                "liketable", "favorite"})).isEqualTo(before);
        // 合法审核带自报 uadmin/uid/role，操作者仍为已验证 A。
        assertPidStatus(mvc.perform(put("/admin/work/" + P1 + "/approve").header("Authorization", token(A, "管理员"))
                .param("uadmin", C).param("uid", U).param("role", "管理员")).andReturn(), P1, "展示");
        assertPidStatus(mvc.perform(put("/admin/work/" + P2 + "/reject").header("Authorization", token(A, "管理员"))
                .param("uadmin", C).param("role", "管理员")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"T04-29驳回理由\"}"))
                .andReturn(), P2, "已驳回");
        // 除 P1/P2 的 wstatus 与新增两条日志外，其余逐字段一致。
        for (String pid : new String[]{P1, P2, P3, P4, P5, P6}) {
            assertThat(pworkSnapshotWithoutStatus(pid)).as("作品 %s 除 wstatus 外不得被审核改动", pid)
                    .isEqualTo(worksBeforeWithoutStatus.get(pid));
        }
        assertThat(statusOfWork(P1)).isEqualTo("展示");
        assertThat(statusOfWork(P2)).isEqualTo("已驳回");
        assertThat(snapshotTable("user")).isEqualTo(before.get("user"));
        assertThat(snapshotTable("work_exif")).isEqualTo(before.get("work_exif"));
        assertThat(snapshotTable("work_embedding")).isEqualTo(before.get("work_embedding"));
        assertThat(snapshotTable("liketable")).isEqualTo(before.get("liketable"));
        assertThat(snapshotTable("favorite")).isEqualTo(before.get("favorite"));
        assertThat(auditRows()).isEqualTo(auditBefore + 2);
        List<String> oldLogs = before.get("waudit");
        for (String old : oldLogs) {
            assertThat(snapshotTable("waudit")).contains(old);
        }
        Map<String, Object> p1Log = jdbc.queryForMap(
                "SELECT act, reason, uadmin FROM waudit WHERE pid=? AND act='pass'", P1);
        assertThat((String) p1Log.get("uadmin")).isEqualTo(A);
        Map<String, Object> p2Log = jdbc.queryForMap(
                "SELECT act, reason, uadmin FROM waudit WHERE pid=? AND act='reject'", P2);
        assertThat((String) p2Log.get("uadmin")).isEqualTo(A);
        assertThat((String) p2Log.get("reason")).isEqualTo("T04-29驳回理由");
        verifyNoInteractions(msgSecCheck);
    }

    /** T04-30 错误方法 405：POST 现有 approve/reject 路径、POST 用户 status、PUT 用户删除精确路径逐例 405 invalid_request，目标与日志零变更。 */
    @Test
    void aud_30_wrongMethod405() throws Exception {
        int before = auditRows();
        assertError(mvc.perform(post("/admin/work/" + P1 + "/approve")
                .header("Authorization", token(A, "管理员"))).andReturn(), 405, "invalid_request");
        assertError(mvc.perform(post("/admin/work/" + P1 + "/reject")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"合法理由\"}"))
                .andReturn(), 405, "invalid_request");
        assertError(mvc.perform(post("/admin/user/" + U + "/status")
                .header("Authorization", token(A, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"禁用\"}"))
                .andReturn(), 405, "invalid_request");
        assertError(mvc.perform(put("/admin/user/" + U)
                .header("Authorization", token(A, "管理员"))).andReturn(), 405, "invalid_request");
        assertThat(statusOfWork(P1)).isEqualTo("待审核");
        assertThat(statusOfWork(P2)).isEqualTo("待审核");
        assertThat(statusOf(U)).isEqualTo("正常");
        assertThat(userExists(U)).isTrue();
        assertThat(auditRows()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    /** 标准夹具：七个身份与六件作品，P1/P5 带历史审核日志。 */
    private void seedStandardFixture() {
        seedUser(A, "c04管理员A", "管理员", "19900000101", "正常", "c04-bio-a", "/avatar/c04-a.png");
        seedUser(B, "c04管理员B", "管理员", "19900000102", "正常", "c04-bio-b", "/avatar/c04-b.png");
        seedUser(U, "c04作者U", "普通用户", "19900000103", "正常", "c04-bio-u", "/avatar/c04-u.png");
        seedUser(O, "c04用户O", "普通用户", "19900000104", "正常", "c04-bio-o", "/avatar/c04-o.png");
        seedUser(D, "c04禁用D", "普通用户", "19900000105", "禁用", "c04-bio-d", "/avatar/c04-d.png");
        seedUser(E, null, "普通用户", null, "正常", null, null);
        seedUser(N, "c04短号N", "普通用户", "123", "正常", "c04-bio-n", "/avatar/c04-n.png");
        seedWork(P1, U, "待审核", "2026-10-23 10:00:01", 5, 2, 3);
        seedWork(P2, U, "待审核", "2026-10-23 10:00:06", 1, 1, 0);
        seedWork(P3, U, "展示", "2026-10-23 10:00:03", 11, 2, 3);
        seedWork(P4, U, "隐藏", "2026-10-23 10:00:04", 1, 1, 0);
        seedWork(P5, U, "已驳回", "2026-10-23 10:00:05", 1, 1, 0);
        seedWork(P6, O, "待审核", "2026-10-23 10:00:02", 1, 1, 0);
        seedAudit("c04-h-p1", P1, "reject", "旧驳回理由", A, "2026-10-20 09:00:00");
        seedAudit("c04-h-p5", P5, "reject", "P5驳回理由", A, "2026-10-20 09:00:00");
    }

    private void seedUser(String uid, String uname, String role, String pnum, String status, String bio, String avatar) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) VALUES (?,?,?,?,?,?,?,?,?)",
                uid, uname, pnum, role, "c04-test-password", avatar, "0", status, bio);
    }

    private void seedAdmin(String uid, String uname, String pnum) {
        seedUser(uid, uname, "管理员", pnum, "正常", null, "/avatar/" + uid + ".png");
    }

    private void seedWork(String pid, String authorUid, String status, String cwtime) {
        seedWork(pid, authorUid, status, cwtime, 1, 1, 0);
    }

    private void seedWork(String pid, String authorUid, String status, String cwtime,
                          long viewcount, long votecount, long likep) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, cwtime, likep, pcid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                pid, authorUid, "c04标题" + pid, "c04描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                viewcount, votecount, status, cwtime, likep, null);
    }

    private void seedAudit(String aid, String pid, String act, String reason, String uadmin, String ct) {
        jdbc.update("INSERT INTO waudit (aid, pid, act, reason, uadmin, ct) VALUES (?,?,?,?,?,?)",
                aid, pid, act, reason, uadmin, ct);
    }

    private void seedLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT IGNORE INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void seedFavorite(String fid, String uid, String pid, String ct) {
        jdbc.update("INSERT IGNORE INTO favorite (fid, uid, pid, ct) VALUES (?,?,?,?)", fid, uid, pid, ct);
    }

    private void seedCategory(String pcid, String pcname, int sort) {
        jdbc.update("INSERT INTO pclist (pcid, pcname, cds, sort) VALUES (?,?,?,?)",
                pcid, pcname, pcname + "测试分类说明", sort);
    }

    /** 造 11 条待审作品，cwtime 依次递增；返回冻结样本集合（不杜撰排序键，只用于集合断言）。 */
    private String[] seedElevenPending() {
        String[] pids = new String[11];
        for (int i = 1; i <= 11; i++) {
            pids[i - 1] = String.format("c04-w%02d", i);
            seedWork(pids[i - 1], U, "待审核", String.format("2026-10-23 12:00:%02d", i));
        }
        return pids;
    }

    /** 造 11 个命中关键词的用户，uname 统一 c04kw 前缀。 */
    private String[] seedElevenKeywordUsers() {
        String[] uids = new String[11];
        for (int i = 1; i <= 11; i++) {
            uids[i - 1] = String.format("c04-k%02d", i);
            seedUser(uids[i - 1], String.format("c04kw用户%02d", i), "普通用户",
                    String.format("199000002%02d", i), "正常", null, "/avatar/" + uids[i - 1] + ".png");
        }
        return uids;
    }

    private String[] firstTen(String[] values) {
        String[] firstTen = new String[10];
        System.arraycopy(values, 0, firstTen, 0, 10);
        return firstTen;
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

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

    /** 六管理入口请求集合；authorization 为 null 时不带 Authorization 头。 */
    private List<MockHttpServletRequestBuilder> adminRequests(String authorization, Cookie cookie) throws Exception {
        List<MockHttpServletRequestBuilder> requests = new ArrayList<>();
        requests.add(withAuth(get("/admin/work/pending"), authorization, cookie));
        requests.add(withAuth(put("/admin/work/" + P1 + "/approve"), authorization, cookie));
        requests.add(withAuth(put("/admin/work/" + P1 + "/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"合法理由\"}"), authorization, cookie));
        requests.add(withAuth(get("/admin/user/list"), authorization, cookie));
        requests.add(withAuth(put("/admin/user/" + U + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"禁用\"}"), authorization, cookie));
        requests.add(withAuth(delete("/admin/user/" + U), authorization, cookie));
        return requests;
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String authorization,
                                                  Cookie cookie) {
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (cookie != null) {
            request.cookie(cookie);
        }
        return request;
    }

    private MvcResult approveAs(String actor, String pid) throws Exception {
        return mvc.perform(approveRequest(actor, pid)).andReturn();
    }

    private MockHttpServletRequestBuilder approveRequest(String actor, String pid) {
        return put("/admin/work/" + pid + "/approve").header("Authorization", token(actor, "管理员"));
    }

    private MvcResult rejectAs(String actor, String pid, String reason) throws Exception {
        return mvc.perform(rejectRequest(actor, pid, reason)).andReturn();
    }

    private MockHttpServletRequestBuilder rejectRequest(String actor, String pid, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return put("/admin/work/" + pid + "/reject").header("Authorization", token(actor, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private MvcResult statusAs(String actor, String targetUid, String status) throws Exception {
        return mvc.perform(statusRequest(actor, targetUid, status)).andReturn();
    }

    private MockHttpServletRequestBuilder statusRequest(String actor, String targetUid, String status) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        return put("/admin/user/" + targetUid + "/status").header("Authorization", token(actor, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private MvcResult deleteUserAs(String actor, String targetUid) throws Exception {
        return mvc.perform(deleteUserRequest(actor, targetUid)).andReturn();
    }

    private MockHttpServletRequestBuilder deleteUserRequest(String actor, String targetUid) {
        return delete("/admin/user/" + targetUid).header("Authorization", token(actor, "管理员"));
    }

    private MvcResult getPending(String actor, String page, String size) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/work/pending")
                .header("Authorization", token(actor, "管理员"));
        if (page != null) {
            request.param("page", page);
        }
        if (size != null) {
            request.param("size", size);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult getUserList(String actor, String keyword, String page, String size) throws Exception {
        MockHttpServletRequestBuilder request = get("/admin/user/list")
                .header("Authorization", token(actor, "管理员")).param("keyword", keyword);
        if (page != null) {
            request.param("page", page);
        }
        if (size != null) {
            request.param("size", size);
        }
        return mvc.perform(request).andReturn();
    }

    /** 03 既有编辑接口重提作品为待审核。 */
    private MvcResult resubmitAs(String actor, String pid, String title, String desc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("desc", desc);
        body.put("imageUrl", "/static/images/pwork/" + pid + ".jpg");
        body.put("categoryId", "c04-cat");
        return mvc.perform(put("/pwork/" + pid).header("Authorization", token(actor, "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn();
    }

    /** 双线程栅栏同起跑，返回两组真实响应；夹具已提交，各请求走独立连接与事务。 */
    private MvcResult[] runConcurrent(MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> firstFuture = pool.submit(concurrentTask(first, ready, start));
            Future<MvcResult> secondFuture = pool.submit(concurrentTask(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new MvcResult[]{firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    /** 并发任务：就绪后等栅栏统一起跑，返回真实 HTTP 响应。 */
    private Callable<MvcResult> concurrentTask(MockHttpServletRequestBuilder request, CountDownLatch ready,
                                               CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发栅栏等待超时");
            }
            return mvc.perform(request).andReturn();
        };
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    /** 并发组断言：恰一个 200、另一个 409，返回胜者下标。 */
    private int assertOneWinsOneConflict(MvcResult[] results) throws Exception {
        int winner = results[0].getResponse().getStatus() == 200 ? 0 : 1;
        assertThat(results[winner].getResponse().getStatus()).as("并发组应恰有一个成功").isEqualTo(200);
        JsonNode ok = parseBody(results[winner]);
        assertTopLevelKeys(ok);
        assertThat(ok.get("code").asText()).isEqualTo("ok");
        assertError(results[1 - winner], 409, "conflict");
        return winner;
    }

    /** 待审分页断言：total 与成员按冻结集合，不杜撰冻结未指定的排序键。 */
    private void assertPendingPage(MvcResult result, long expectedTotal, String... expectedPids) throws Exception {
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isEqualTo(expectedTotal);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(expectedPids.length);
        Set<String> pids = new HashSet<>();
        for (JsonNode item : list) {
            assertWork(item);
            assertThat(item.get("wstatus").asText()).isEqualTo("待审核");
            pids.add(item.get("pid").asText());
        }
        assertThat(pids).containsExactlyInAnyOrder(expectedPids);
    }

    private List<String> pendingPids(MvcResult result) throws Exception {
        List<String> pids = new ArrayList<>();
        for (JsonNode item : assertOk(result).get("list")) {
            pids.add(item.get("pid").asText());
        }
        return pids;
    }

    /** 用户分页断言：total 与 list 长度按冻结值，成员不按顺序断言。 */
    private void assertUserPage(MvcResult result, long expectedTotal, int expectedSize) throws Exception {
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").isIntegralNumber()).isTrue();
        assertThat(data.get("total").asLong()).isEqualTo(expectedTotal);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(expectedSize);
        for (JsonNode item : list) {
            assertAdminUser(item, item.get("uid").asText());
        }
    }

    private void assertUserMembers(MvcResult result, long expectedTotal, String... expectedUids) throws Exception {
        JsonNode data = assertOk(result);
        assertKeys(data, "list", "total");
        assertThat(data.get("total").asLong()).isEqualTo(expectedTotal);
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(expectedUids.length);
        Set<String> uids = new HashSet<>();
        for (JsonNode item : list) {
            assertAdminUser(item, item.get("uid").asText());
            uids.add(item.get("uid").asText());
        }
        assertThat(uids).containsExactlyInAnyOrder(expectedUids);
    }

    /** 审核/发布响应断言：data 恰为 pid/status 两键。 */
    private void assertPidStatus(MvcResult result, String pid, String status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        JsonNode data = json.get("data");
        assertKeys(data, "pid", "status");
        assertThat(data.get("pid").asText()).isEqualTo(pid);
        assertThat(data.get("status").asText()).isEqualTo(status);
    }

    /** 用户状态响应断言：data 恰为 uid/status 两键。 */
    private void assertUidStatus(MvcResult result, String uid, String status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        JsonNode data = json.get("data");
        assertKeys(data, "uid", "status");
        assertThat(data.get("uid").asText()).isEqualTo(uid);
        assertThat(data.get("status").asText()).isEqualTo(status);
    }

    /** 删除成功响应断言：code=ok 且 data=null。 */
    private void assertDeleted(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = parseBody(result);
        assertTopLevelKeys(json);
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).isNotEmpty();
        assertThat(json.get("data").isNull()).isTrue();
    }

    /** Work 十四键逐字段与库内夹具比对。 */
    private void assertWork(JsonNode item) {
        assertKeys(item, WORK_KEYS);
        assertWorkValues(item);
    }

    /** MineWork 十五键（W 加 reason），reason 键必须存在。 */
    private void assertMineWork(JsonNode item) {
        String[] keys = new String[WORK_KEYS.length + 1];
        System.arraycopy(WORK_KEYS, 0, keys, 0, WORK_KEYS.length);
        keys[WORK_KEYS.length] = "reason";
        assertKeys(item, keys);
        assertWorkValues(item);
    }

    private void assertWorkValues(JsonNode item) {
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

    /** AdminUser 八键逐字段与库内夹具比对：nickname=uname、createdAt 取 uct 的 +08:00 格式、pnum 管理员视角完整返回（脱敏只发生在旧接口非管理员查看）。 */
    private void assertAdminUser(JsonNode item, String uid) {
        assertKeys(item, ADMIN_USER_KEYS);
        assertThat(item.get("uid").asText()).isEqualTo(uid);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT uname, avatar, bio, role, status, pnum, DATE_FORMAT(uct,'%Y-%m-%dT%H:%i:%s+08:00') AS createdAt "
                        + "FROM user WHERE uid=?", uid);
        assertNullableText(item.get("nickname"), (String) row.get("uname"));
        assertNullableText(item.get("avatar"), (String) row.get("avatar"));
        assertNullableText(item.get("bio"), (String) row.get("bio"));
        assertThat(item.get("role").asText()).isEqualTo((String) row.get("role"));
        assertThat(item.get("status").asText()).isEqualTo((String) row.get("status"));
        // 管理列表仅管理员可见：pnum 一律完整返回、null 保持 null，不按行角色再脱敏。
        String pnum = (String) row.get("pnum");
        assertNullableText(item.get("pnum"), pnum);
        assertThat(item.get("createdAt").asText()).isEqualTo((String) row.get("createdAt"));
        assertThat(item.get("createdAt").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+08:00");
    }

    private void assertCount(JsonNode node, Number expected) {
        assertThat(node.isIntegralNumber()).isTrue();
        assertThat(node.asLong()).isEqualTo(expected.longValue());
    }

    private void assertCount(JsonNode node, long expected) {
        assertThat(node.isIntegralNumber()).isTrue();
        assertThat(node.asLong()).isEqualTo(expected);
    }

    private void assertNullableText(JsonNode node, String expected) {
        if (expected == null) {
            assertThat(node.isNull()).isTrue();
        } else {
            assertThat(node.asText()).isEqualTo(expected);
        }
    }

    private JsonNode findItem(JsonNode page, String pid) {
        for (JsonNode item : page.get("list")) {
            if (pid.equals(item.get("uid") != null ? item.get("uid").asText() : "")
                    || pid.equals(item.get("pid") != null ? item.get("pid").asText() : "")) {
                return item;
            }
        }
        throw new AssertionError("分页响应中找不到 " + pid);
    }

    private boolean containsPid(JsonNode page, String pid) {
        for (JsonNode item : page.get("list")) {
            if (pid.equals(item.get("pid").asText())) {
                return true;
            }
        }
        return false;
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
                "pwd", "password", "Bearer ", "Exception", "\tat ", "SQL");
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
    // 数据库观察辅助
    // ------------------------------------------------------------------

    private int auditRows() {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM waudit WHERE aid LIKE 'c04-%' OR pid LIKE 'c04-%' OR uadmin LIKE 'c04-%'",
                Integer.class);
        return rows == null ? 0 : rows;
    }

    private int auditRowsOf(String pid) {
        // 只按本类作品 pid 计数：实现生成的 aid 不保证 c04- 前缀，按 aid 过滤会漏算实现侧日志。
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM waudit WHERE pid=?", Integer.class, pid);
        return rows == null ? 0 : rows;
    }

    private String logReasonOf(String pid) {
        List<String> reasons = jdbc.queryForList(
                "SELECT reason FROM waudit WHERE pid=? ORDER BY ct DESC, aid DESC LIMIT 1", String.class, pid);
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    private int pendingCount() {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM pwork WHERE wstatus='待审核'", Integer.class);
        return rows == null ? 0 : rows;
    }

    private String statusOfWork(String pid) {
        return jdbc.queryForObject("SELECT wstatus FROM pwork WHERE pid=?", String.class, pid);
    }

    private int viewcountOfWork(String pid) {
        Integer viewcount = jdbc.queryForObject("SELECT viewcount FROM pwork WHERE pid=?", Integer.class, pid);
        return viewcount == null ? 0 : viewcount;
    }

    private String statusOf(String uid) {
        return jdbc.queryForObject("SELECT status FROM user WHERE uid=?", String.class, uid);
    }

    private String roleOf(String uid) {
        return jdbc.queryForObject("SELECT role FROM user WHERE uid=?", String.class, uid);
    }

    private String pnumOf(String uid) {
        return jdbc.queryForObject("SELECT pnum FROM user WHERE uid=?", String.class, uid);
    }

    private String mask11(String pnum) {
        return pnum.substring(0, 3) + "****" + pnum.substring(7);
    }

    private boolean userExists(String uid) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE uid=?", Integer.class, uid);
        return rows != null && rows > 0;
    }

    private int normalAdminCount() {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE role='管理员' AND status='正常'", Integer.class);
        return rows == null ? 0 : rows;
    }

    private boolean tableExists(String tableName) {
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
                Integer.class, tableName);
        return rows != null && rows > 0;
    }

    /**
     * JDBC 4.2 的 DATETIME 可能返回 LocalDateTime，也可能返回 Timestamp，统一取毫秒比较，不做强转。
     * @param rawCt 时间列原始值
     * @return 该时间的毫秒数
     */
    private long ctMillis(Object rawCt) {
        if (rawCt instanceof Timestamp) {
            return ((Timestamp) rawCt).getTime();
        }
        if (rawCt instanceof LocalDateTime) {
            return ((LocalDateTime) rawCt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        throw new AssertionError("时间列类型未预期: " + rawCt.getClass());
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

    /** 单行 pwork 快照（不含 wstatus）：用于断言审核只改状态列，其余字段逐一不变。 */
    private String pworkSnapshotWithoutStatus(String pid) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM pwork WHERE pid=?", pid);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (!"wstatus".equals(entry.getKey())) {
                parts.add(entry.getKey() + "=" + String.valueOf(normalizeDbValue(entry.getValue())));
            }
        }
        Collections.sort(parts);
        return String.join("|", parts);
    }

    /** 逐表读出全部行并规范化为可排序字符串行集合；时间列兼容 Timestamp/LocalDateTime 再比较，不做强转。 */
    private Map<String, List<String>> snapshotTables(String[] tables) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : tables) {
            snapshot.put(table, snapshotTable(table));
        }
        return snapshot;
    }

    private List<String> snapshotTable(String table) {
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
        return canonicalRows;
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
}
