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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 管理端一揽子后端冻结测试：契约见 docs/接口增补_ADMIN.md，卡面见
 * docs/cards/AD_管理端后端_执行卡.md（G1–G8）。
 *
 * 设计约束（与卡 04 的冻结测试同口径）：
 * 1. 只通过 HTTP 打接口，不 import 任何尚未实现的类，因此实现缺失时是"路由 404"的行为红，
 *    而不是编译失败——编译失败不算行为红。
 * 2. 只连 db_pg_test_04，夹具统一 ad- 前缀并在本类内自建自清；不清空、不 TRUNCATE 共享表。
 * 3. 统计类断言一律取"插入前后差值"，避免与库里既有种子数据（10 用户 / 9 作品）耦合；
 *    自洽关系（pending==statusCounts.pending、四项和==workCount、分类总和==workCount）
 *    是全局不变式，直接断言。
 * 4. 最后正常管理员保护需要"全库只有 1 个正常管理员"，用 withOnlyAdmin 临时降级其它管理员，
 *    finally 必定恢复原 role/status。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdminApiTest {

    /** 测试库固定 db_pg_test_04，与卡 04 共用；加锁等待上限避免死锁挂死。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_04?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&sessionVariables=lock_wait_timeout=2");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    private static final Logger log = LoggerFactory.getLogger(AdminApiTest.class);

    /** 身份样本：ADM/ADM2 正常管理员，U/O 普通用户，DIS 禁用用户，GONE 仅用于格式合法的不存在目标。 */
    private static final String ADM = "ad-adm";
    private static final String ADM2 = "ad-adm2";
    private static final String U = "ad-u";
    private static final String O = "ad-o";
    private static final String DIS = "ad-dis";
    private static final String GONE = "ad-gone";

    /** 作品样本：四状态各一件，另加 keyword / 分页 / 分类样本。 */
    private static final String W_PENDING = "ad-w-pending";
    private static final String W_SHOW = "ad-w-show";
    private static final String W_HIDE = "ad-w-hide";
    private static final String W_REJ = "ad-w-rej";
    private static final String W_KEY = "ad-w-keyword-样本";
    private static final String CAT1 = "ad-cat1";
    private static final String CAT2 = "ad-cat2";

    /** 契约冻结的键集合。 */
    private static final Set<String> STATS_KEYS = new HashSet<>(Arrays.asList(
            "pendingCount", "todayApprovedCount", "userCount", "workCount",
            "statusCounts", "categoryCounts", "recentPending"));
    private static final Set<String> STATUS_KEYS = new HashSet<>(Arrays.asList(
            "pending", "visible", "hidden", "rejected"));
    private static final List<String> WORK_KEYS = Arrays.asList(
            "pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount", "wstatus", "cwtime",
            "likep", "uname", "avatar", "pcid", "pcname", "realLikeCount", "favoriteCount", "reason");
    private static final Set<String> GUARD_KEYS = new HashSet<>(Arrays.asList(
            "uid", "canDisable", "canDelete", "disableReason", "deleteReason", "workCount"));
    private static final Set<String> DETAIL_KEYS = new HashSet<>(Arrays.asList("work", "exif", "author"));

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
    AdminApiTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /** 每例先校验库名、清理本类残留，再写入四状态标准夹具。 */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_04");
        cleanup();
        seedUser(ADM, "ad管理员", "管理员", "正常");
        seedUser(ADM2, "ad管理员2", "管理员", "正常");
        seedUser(U, "ad作者", "普通用户", "正常");
        seedUser(O, "ad用户O", "普通用户", "正常");
        seedUser(DIS, "ad禁用", "普通用户", "禁用");
        seedCategory(CAT1, "ad分类一", 10);
        seedCategory(CAT2, "ad分类二", 20);
        seedWork(W_PENDING, U, "待审核", "2099-01-01 00:00:01", CAT1);
        seedWork(W_SHOW, U, "展示", "2099-01-01 00:00:02", CAT2);
        seedWork(W_HIDE, U, "隐藏", "2099-01-01 00:00:03", null);
        seedWork(W_REJ, O, "已驳回", "2099-01-01 00:00:04", CAT1);
        seedExif(W_SHOW, 35, "2.8", "1/250", 400, "FE 35mm", "Sony A7M4");
        seedAudit("ad-a1", W_REJ, "reject", "ad旧驳回理由", ADM);
    }

    /**
     * 按外键依赖顺序清理本类夹具。
     * 测试库对 user 有 RESTRICT 外键（comment/favorite/follow/liketable），必须先把子表清空再删用户；
     * 业务库没有这些外键，所以实现端也必须显式按顺序删，不能依赖 ON DELETE CASCADE。
     */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM waudit WHERE aid LIKE 'ad-%' OR pid LIKE 'ad-%' OR uadmin LIKE 'ad-%'");
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'ad-%' OR uid LIKE 'ad-%' OR targetid LIKE 'ad-%'");
        jdbc.update("DELETE FROM follow WHERE fid LIKE 'ad-%' OR uid LIKE 'ad-%' OR tuid LIKE 'ad-%'");
        jdbc.update("DELETE FROM comment WHERE cid LIKE 'ad-%' OR uid LIKE 'ad-%' OR pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'ad-%' OR uid LIKE 'ad-%' OR pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM vote WHERE vid LIKE 'ad-%' OR uid LIKE 'ad-%' OR pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM pcatogory WHERE pcid LIKE 'ad-%' OR uid LIKE 'ad-%' OR pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM work_embedding WHERE pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM orderinfo WHERE orid LIKE 'ad-%' OR uid LIKE 'ad-%' OR gid LIKE 'ad-%'");
        jdbc.update("DELETE FROM gift WHERE gid LIKE 'ad-%' OR uid LIKE 'ad-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'ad-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'ad-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'ad-%'");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void seedUser(String uid, String uname, String role, String status) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, bio) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                uid, uname, null, role, "ad-test-password", "/avatar/" + uid + ".png", "0", status, "ad-bio-" + uid);
    }

    private void seedCategory(String pcid, String pcname, int sort) {
        jdbc.update("INSERT INTO pclist (pcid, pcname, cds, sort) VALUES (?,?,?,?)",
                pcid, pcname, pcname + "说明", sort);
    }

    private void seedWork(String pid, String uid, String status, String cwtime, String pcid) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, cwtime, likep, pcid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                pid, uid, "ad标题" + pid, "ad描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                7, 3, status, cwtime, 2, pcid);
    }

    private void seedExif(String pid, int focal, String aperture, String shutter, int iso, String lens, String camera) {
        jdbc.update("INSERT INTO work_exif (pid, focal, aperture, shutter, iso, lens, camera, stripped) "
                        + "VALUES (?,?,?,?,?,?,?,1)",
                pid, focal, Double.valueOf(aperture), shutter, iso, lens, camera);
    }

    private void seedAudit(String aid, String pid, String act, String reason, String uadmin) {
        seedAuditAt(aid, pid, act, reason, uadmin, Timestamp.valueOf(LocalDateTime.now().minusHours(3)));
    }

    private void seedAuditAt(String aid, String pid, String act, String reason, String uadmin, Timestamp ct) {
        jdbc.update("INSERT INTO waudit (aid, pid, act, reason, uadmin, ct) VALUES (?,?,?,?,?,?)",
                aid, pid, act, reason, uadmin, ct);
    }

    private String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    // ------------------------------------------------------------------
    // G1 聚合自洽
    // ------------------------------------------------------------------

    /** G1 七键齐全、类型正确，且三条自洽不变式成立。 */
    @Test
    void ad_01_statsShapeAndSelfConsistency() throws Exception {
        JsonNode data = assertOk(perform(get("/admin/stats").header("Authorization", token(ADM, "管理员"))));
        assertThat(keysOf(data)).isEqualTo(STATS_KEYS);
        for (String key : new String[]{"pendingCount", "todayApprovedCount", "userCount", "workCount"}) {
            assertThat(data.get(key).isNumber()).as("%s 必须是数字", key).isTrue();
            assertThat(data.get(key).asLong()).as("%s 必须非负", key).isGreaterThanOrEqualTo(0L);
        }
        JsonNode statusCounts = data.get("statusCounts");
        assertThat(keysOf(statusCounts)).isEqualTo(STATUS_KEYS);
        long sum = 0;
        for (String key : STATUS_KEYS) {
            assertThat(statusCounts.get(key).isNumber()).isTrue();
            sum += statusCounts.get(key).asLong();
        }
        assertThat(statusCounts.get("pending").asLong())
                .as("pending 必须等于 pendingCount").isEqualTo(data.get("pendingCount").asLong());
        assertThat(sum).as("四状态和必须等于 workCount").isEqualTo(data.get("workCount").asLong());

        JsonNode categories = data.get("categoryCounts");
        assertThat(categories.isArray()).isTrue();
        long categorySum = 0;
        for (JsonNode item : categories) {
            assertThat(item.has("pcname") && item.has("count")).isTrue();
            assertThat(item.get("count").asLong()).isGreaterThanOrEqualTo(0L);
            categorySum += item.get("count").asLong();
        }
        assertThat(categorySum).as("分类总和必须等于 workCount").isEqualTo(data.get("workCount").asLong());

        JsonNode recent = data.get("recentPending");
        assertThat(recent.isArray()).isTrue();
        assertThat(recent.size()).isLessThanOrEqualTo(3);
        for (JsonNode item : recent) {
            assertThat(item.get("wstatus").asText()).isEqualTo("待审核");
            assertThat(item.has("pid") && item.has("vurl") && item.has("cwtime")).isTrue();
        }
    }

    /** G1 增量口径：新增四状态作品与零件分类后，pending/workCount/分类分布同步变化。 */
    @Test
    void ad_02_statsCountsFollowNewRows() throws Exception {
        long pendingBefore = statLong("pendingCount");
        long workBefore = statLong("workCount");
        String extraPending = "ad-w-extra-pending";
        seedWork(extraPending, U, "待审核", "2099-01-02 00:00:00", CAT2);
        JsonNode data = assertOk(perform(get("/admin/stats").header("Authorization", token(ADM, "管理员"))));
        assertThat(data.get("pendingCount").asLong()).isEqualTo(pendingBefore + 1);
        assertThat(data.get("workCount").asLong()).isEqualTo(workBefore + 1);
        assertThat(data.get("statusCounts").get("pending").asLong()).isEqualTo(pendingBefore + 1);

        JsonNode cat2 = findCategoryByPcid(data.get("categoryCounts"), CAT2);
        assertThat(cat2).as("新增作品必须落在 ad 分类二").isNotNull();
        assertThat(cat2.get("count").asLong()).isGreaterThanOrEqualTo(1L);
        // 未分类桶：W_HIDE 的 pcid 为空，必须被计入末尾固定追加的未分类项。
        JsonNode uncategorized = findCategory(data.get("categoryCounts"), "未分类");
        assertThat(uncategorized).as("必须固定追加未分类桶").isNotNull();
        assertThat(uncategorized.get("pcid").isNull()).isTrue();
        assertThat(uncategorized.get("count").asLong()).isGreaterThanOrEqualTo(1L);
    }

    /** G1 最近待审：最多 3 件，按 cwtime 倒序，2399 夹具必须排在真实种子之前。 */
    @Test
    void ad_03_recentPendingOrderAndLimit() throws Exception {
        for (int i = 1; i <= 4; i++) {
            seedWork("ad-w-recent" + i, U, "待审核", String.format("2099-02-0%d 00:00:00", i), null);
        }
        JsonNode recent = assertOk(perform(get("/admin/stats").header("Authorization", token(ADM, "管理员"))))
                .get("recentPending");
        assertThat(recent.size()).isEqualTo(3);
        List<String> pids = new ArrayList<>();
        for (JsonNode item : recent) {
            pids.add(item.get("pid").asText());
        }
        assertThat(pids).containsExactly("ad-w-recent4", "ad-w-recent3", "ad-w-recent2");
    }
    // ------------------------------------------------------------------
    // G2 今日通过：闭开区间 + 按 pid 去重
    // ------------------------------------------------------------------

    /** G2 只统计 [今日00:00, 明日00:00) 内的 pass，并按 pid 去重；reject 与区间外一律不计。 */
    @Test
    void ad_04_todayApprovedBoundaryAndDedup() throws Exception {
        LocalDate today = LocalDate.now();
        String ws = "ad-w-today";
        for (String suffix : new String[]{"start", "end", "yesterday", "tomorrow", "dup", "rej"}) {
            seedWork(ws + "-" + suffix, U, "展示", "2099-03-01 00:00:00", null);
        }
        Timestamp startOfToday = Timestamp.valueOf(today.atStartOfDay());
        Timestamp endOfToday = Timestamp.valueOf(today.atTime(LocalTime.of(23, 59, 59)));
        Timestamp endOfYesterday = Timestamp.valueOf(today.minusDays(1).atTime(LocalTime.of(23, 59, 59)));
        Timestamp startOfTomorrow = Timestamp.valueOf(today.plusDays(1).atStartOfDay());

        long before = statLong("todayApprovedCount");
        seedAuditAt("ad-t-start", ws + "-start", "pass", null, ADM, startOfToday);
        seedAuditAt("ad-t-end", ws + "-end", "pass", null, ADM, endOfToday);
        seedAuditAt("ad-t-yesterday", ws + "-yesterday", "pass", null, ADM, endOfYesterday);
        seedAuditAt("ad-t-tomorrow", ws + "-tomorrow", "pass", null, ADM, startOfTomorrow);
        // 同一 pid 今日再通过一次：去重后仍只算一件。
        seedAuditAt("ad-t-dup", ws + "-start", "pass", null, ADM2, startOfToday);
        seedAuditAt("ad-t-rej", ws + "-rej", "reject", "ad理由", ADM, startOfToday);

        long after = statLong("todayApprovedCount");
        assertThat(after - before).as("仅今日区间内 pass 且按 pid 去重").isEqualTo(2L);
    }

    /** G2 通过与 reject 同 pid 交错：只有 pass 计入，且状态后续变为隐藏不影响计数。 */
    @Test
    void ad_05_todayApprovedIgnoresLaterStatusChange() throws Exception {
        long before = statLong("todayApprovedCount");
        String pid = "ad-w-pass-then-hide";
        seedWork(pid, U, "展示", "2099-03-02 00:00:00", null);
        seedAuditAt("ad-t2-pass", pid, "pass", null, ADM, Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)));
        jdbc.update("UPDATE pwork SET wstatus = '隐藏' WHERE pid = ?", pid);
        assertThat(statLong("todayApprovedCount") - before).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // G3 列表与详情
    // ------------------------------------------------------------------

    /** G3 列表四状态过滤与非法枚举：非法 status 必须 400，不得降级为全部。 */
    @Test
    void ad_06_listStatusFilterStrict() throws Exception {
        for (String status : new String[]{"待审核", "展示", "隐藏", "已驳回"}) {
            JsonNode data = assertOk(perform(get("/admin/work/list")
                    .header("Authorization", token(ADM, "管理员")).param("status", status)
                    .param("size", "50")));
            assertThat(data.has("list") && data.has("total")).isTrue();
            for (JsonNode item : data.get("list")) {
                assertThat(item.get("wstatus").asText()).isEqualTo(status);
            }
        }
        for (String bad : new String[]{"", " ", "展示 ", "全部", "pending"}) {
            assertError(perform(get("/admin/work/list").header("Authorization", token(ADM, "管理员"))
                    .param("status", bad)), 400, "invalid_request");
        }
    }

    /** G3 关键词与分类过滤：标题子串、作者子串、pid 精确；分类过滤只出该分类。 */
    @Test
    void ad_07_listKeywordAndCategory() throws Exception {
        seedWork(W_KEY, O, "展示", "2099-04-01 00:00:00", CAT2);
        JsonNode byTitle = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("keyword", "keyword-样本").param("size", "50")));
        assertThat(pidsOf(byTitle)).contains(W_KEY);
        JsonNode byAuthor = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("keyword", "ad用户O").param("size", "50")));
        assertThat(pidsOf(byAuthor)).contains(W_REJ, W_KEY);
        JsonNode byPid = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("keyword", W_SHOW)));
        assertThat(pidsOf(byPid)).containsExactly(W_SHOW);

        JsonNode byCategory = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("category", CAT2).param("size", "50")));
        assertThat(pidsOf(byCategory)).containsExactlyInAnyOrder(W_SHOW, W_KEY);
        // 格式合法但不存在 → 200 空页，不是 404。
        JsonNode missing = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("category", "ad-cat-not-exist")));
        assertThat(missing.get("list").size()).isZero();
        assertThat(missing.get("total").asLong()).isZero();
        // 非法分类格式 → 400。
        assertError(perform(get("/admin/work/list").header("Authorization", token(ADM, "管理员"))
                .param("category", "ad cat")), 400, "invalid_request");
    }

    /** G3 分页与别名：默认 10、超页空列表真实 total、旧别名 pageNum/pageSize 必须 400。 */
    @Test
    void ad_08_listPagingAndAliases() throws Exception {
        for (int i = 1; i <= 11; i++) {
            seedWork("ad-w-page" + i, U, "展示", String.format("2099-05-01 00:00:%02d", i), null);
        }
        JsonNode first = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("status", "展示")));
        assertThat(first.get("list").size()).isEqualTo(10);
        assertThat(first.get("total").asLong()).isGreaterThanOrEqualTo(11L);
        JsonNode second = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("status", "展示").param("page", "2")));
        assertThat(second.get("list").size()).isGreaterThanOrEqualTo(1);
        JsonNode overflow = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("page", "999").param("size", "50")));
        assertThat(overflow.get("list").size()).isZero();
        assertThat(overflow.get("total").asLong()).isGreaterThan(0L);
        for (String[] pair : new String[][]{{"pageNum", "2"}, {"pageSize", "5"}}) {
            assertError(perform(get("/admin/work/list").header("Authorization", token(ADM, "管理员"))
                    .param(pair[0], pair[1])), 400, "invalid_request");
        }
    }

    /** G3 列表 17 键与排序：key 集合固定、reason 取最近一条、按 cwtime DESC 稳定排序。 */
    @Test
    void ad_09_listShapeAndReason() throws Exception {
        seedAuditAt("ad-a2", W_REJ, "pass", null, ADM, Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
        seedAuditAt("ad-a3", W_REJ, "reject", "ad最新驳回理由", ADM, Timestamp.valueOf(LocalDateTime.now()));
        JsonNode data = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("keyword", W_REJ)));
        JsonNode item = data.get("list").get(0);
        assertThat(keysOf(item)).as("AdminWork 必须恰 17 键").isEqualTo(new HashSet<>(WORK_KEYS));
        assertThat(item.get("reason").asText()).isEqualTo("ad最新驳回理由");
        assertThat(item.has("favorited")).isFalse();

        JsonNode all = assertOk(perform(get("/admin/work/list")
                .header("Authorization", token(ADM, "管理员")).param("size", "50")));
        String previous = null;
        for (JsonNode row : all.get("list")) {
            String cwtime = row.get("cwtime").asText();
            if (previous != null) {
                assertThat(cwtime.compareTo(previous)).as("必须按 cwtime 倒序").isLessThanOrEqualTo(0);
            }
            previous = cwtime;
        }
        List<String> order = pidsOf(all);
        assertThat(order.get(0)).as("四件夹具中已驳回的 cwtime 最晚，必须排首位").isEqualTo(W_REJ);
        assertThat(order).as("按 cwtime 倒序：已驳回(04) → 隐藏(03) → 展示(02) → 待审核(01)")
                .containsSubsequence(W_REJ, W_HIDE, W_SHOW, W_PENDING);
    }

    /** G3 详情三键、EXIF 六项、作者不含敏感字段，且不增加浏览量。 */
    @Test
    void ad_10_detailShapeAndNoViewIncrement() throws Exception {
        long before = jdbc.queryForObject("SELECT viewcount FROM pwork WHERE pid = ?", Long.class, W_SHOW);
        JsonNode data = assertOk(perform(get("/admin/work/" + W_SHOW).header("Authorization", token(ADM, "管理员"))));
        assertThat(keysOf(data)).isEqualTo(DETAIL_KEYS);
        JsonNode work = data.get("work");
        assertThat(keysOf(work)).isEqualTo(new HashSet<>(WORK_KEYS));
        JsonNode exif = data.get("exif");
        assertThat(exif.has("focal") && exif.has("aperture") && exif.has("shutter")
                && exif.has("iso") && exif.has("lens") && exif.has("camera")).isTrue();
        assertThat(exif.get("camera").asText()).isEqualTo("Sony A7M4");
        JsonNode author = data.get("author");
        assertThat(author.has("pnum")).as("作者不得返回手机号").isFalse();
        assertThat(author.has("pwd") || author.has("openid")).isFalse();
        long after = jdbc.queryForObject("SELECT viewcount FROM pwork WHERE pid = ?", Long.class, W_SHOW);
        assertThat(after).as("管理详情不得自增浏览").isEqualTo(before);
    }

    /** G3 详情可查待审/隐藏/已驳回；非法 pid 格式 400，合法不存在 404。 */
    @Test
    void ad_11_detailAllStatesAndErrors() throws Exception {
        for (String pid : new String[]{W_PENDING, W_HIDE, W_REJ}) {
            JsonNode data = assertOk(perform(get("/admin/work/" + pid).header("Authorization", token(ADM, "管理员"))));
            assertThat(data.get("work").get("pid").asText()).isEqualTo(pid);
        }
        assertError(perform(get("/admin/work/ad bad").header("Authorization", token(ADM, "管理员"))),
                400, "invalid_request");
        assertError(perform(get("/admin/work/" + GONE).header("Authorization", token(ADM, "管理员"))),
                404, "not_found");
    }

    /** G3 路由优先级：静态 /pending 与 /list 不得被 /{pid} 吞掉。 */
    @Test
    void ad_12_staticRoutesNotSwallowed() throws Exception {
        JsonNode pending = assertOk(perform(get("/admin/work/pending").header("Authorization", token(ADM, "管理员"))));
        assertThat(pending.has("list")).as("/admin/work/pending 必须命中待审分页").isTrue();
        JsonNode list = assertOk(perform(get("/admin/work/list").header("Authorization", token(ADM, "管理员"))));
        assertThat(list.has("list")).as("/admin/work/list 必须命中列表").isTrue();
    }
    // ------------------------------------------------------------------
    // G4 状态矩阵
    // ------------------------------------------------------------------

    /** G4 展示↔隐藏往返：200 且不追加审核日志。 */
    @Test
    void ad_13_statusVisibilityRoundTripWritesNoLog() throws Exception {
        int before = auditCount(W_SHOW);
        assertPidStatus(perform(put("/admin/work/" + W_SHOW + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"隐藏\"}")), W_SHOW, "隐藏");
        assertThat(statusOf(W_SHOW)).isEqualTo("隐藏");
        assertThat(auditCount(W_SHOW)).isEqualTo(before);
        assertPidStatus(perform(put("/admin/work/" + W_SHOW + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"展示\"}")), W_SHOW, "展示");
        assertThat(statusOf(W_SHOW)).isEqualTo("展示");
        assertThat(auditCount(W_SHOW)).isEqualTo(before);
    }

    /** G4 同状态幂等：200 且零写入。 */
    @Test
    void ad_14_statusIdempotent() throws Exception {
        int before = auditCount(W_SHOW);
        assertPidStatus(perform(put("/admin/work/" + W_SHOW + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"展示\"}")), W_SHOW, "展示");
        assertThat(auditCount(W_SHOW)).isEqualTo(before);
        assertThat(statusOf(W_SHOW)).isEqualTo("展示");
    }

    /** G4 已驳回→展示：200，追加唯一 pass 日志，mine 的最近理由归零。 */
    @Test
    void ad_15_rejectedToVisibleAppendsPass() throws Exception {
        int before = auditCount(W_REJ);
        assertPidStatus(perform(put("/admin/work/" + W_REJ + "/status")
                .header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"展示\"}")), W_REJ, "展示");
        assertThat(statusOf(W_REJ)).isEqualTo("展示");
        assertThat(auditCount(W_REJ)).isEqualTo(before + 1);
        Map<String, Object> latest = jdbc.queryForMap(
                "SELECT act, reason, uadmin FROM waudit WHERE pid = ? ORDER BY ct DESC, aid DESC LIMIT 1", W_REJ);
        assertThat(latest.get("act")).isEqualTo("pass");
        assertThat(latest.get("reason")).isNull();
        assertThat(latest.get("uadmin")).isEqualTo(ADM);
        JsonNode list = assertOk(perform(get("/admin/work/list").header("Authorization", token(ADM, "管理员"))
                .param("keyword", W_REJ)));
        assertThat(list.get("list").get(0).get("reason").isNull()).isTrue();
    }

    /** G4 非法迁移：已驳回→隐藏、待审核→展示/隐藏必须 409，不隐式改状态、不写日志。 */
    @Test
    void ad_16_illegalTransitionsConflict() throws Exception {
        int rejectedLogs = auditCount(W_REJ);
        int pendingLogs = auditCount(W_PENDING);
        assertError(perform(put("/admin/work/" + W_REJ + "/status").header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"隐藏\"}")), 409, "conflict");
        assertError(perform(put("/admin/work/" + W_PENDING + "/status").header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"展示\"}")), 409, "conflict");
        assertError(perform(put("/admin/work/" + W_PENDING + "/status").header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"隐藏\"}")), 409, "conflict");
        assertThat(statusOf(W_REJ)).isEqualTo("已驳回");
        assertThat(statusOf(W_PENDING)).isEqualTo("待审核");
        assertThat(auditCount(W_REJ)).isEqualTo(rejectedLogs);
        assertThat(auditCount(W_PENDING)).isEqualTo(pendingLogs);
    }

    /** G4 入参严格：缺 body / 缺 status / null / 非法枚举 / 类型错误 全部 400；合法不存在 404。 */
    @Test
    void ad_17_statusBodyStrict() throws Exception {
        for (String body : new String[]{"{}", "{\"status\":null}", "{\"status\":\"待审核\"}",
                "{\"status\":\"已驳回\"}", "{\"status\":\"展示 \"}", "{\"status\":1}", "{\"status\":\"全部\"}"}) {
            assertError(perform(put("/admin/work/" + W_SHOW + "/status")
                    .header("Authorization", token(ADM, "管理员"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "invalid_request");
        }
        assertThat(statusOf(W_SHOW)).isEqualTo("展示");
        assertError(perform(put("/admin/work/" + GONE + "/status").header("Authorization", token(ADM, "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"展示\"}")), 404, "not_found");
    }

    // ------------------------------------------------------------------
    // G5 用户按钮保护 guards
    // ------------------------------------------------------------------

    /** G5 四项保护：自己 / 最后管理员 / 有作品 / 正常可删，且顺序与请求一致。 */
    @Test
    void ad_18_guardsProtectionMatrix() throws Exception {
        // 有作品（W_PENDING/W_SHOW/W_HIDE 都属 U）→ 可禁用、不可删
        JsonNode data = assertOk(perform(get("/admin/user/guards")
                .header("Authorization", token(ADM, "管理员")).param("uids", U + "," + ADM)));
        JsonNode forU = data.get("list").get(0);
        assertThat(keysOf(forU)).isEqualTo(GUARD_KEYS);
        assertThat(forU.get("uid").asText()).isEqualTo(U);
        assertThat(forU.get("canDisable").asBoolean()).isTrue();
        assertThat(forU.get("canDelete").asBoolean()).isFalse();
        assertThat(forU.get("deleteReason").asText()).isEqualTo("has_works");
        assertThat(forU.get("workCount").asLong()).as("U 名下三件作品").isEqualTo(3L);
        assertThat(forU.get("disableReason").isNull()).isTrue();
        // 自己 → 两项都不可，原因 self
        JsonNode self = data.get("list").get(1);
        assertThat(self.get("uid").asText()).isEqualTo(ADM);
        assertThat(self.get("canDisable").asBoolean()).isFalse();
        assertThat(self.get("canDelete").asBoolean()).isFalse();
        assertThat(self.get("disableReason").asText()).isEqualTo("self");
        assertThat(self.get("deleteReason").asText()).isEqualTo("self");
        // 可删普通用户（O 有作品 W_REJ，所以先造一个无作品的）
        String clean = "ad-clean";
        seedUser(clean, "ad干净用户", "普通用户", "正常");
        JsonNode cleanNode = assertOk(perform(get("/admin/user/guards")
                .header("Authorization", token(ADM, "管理员")).param("uids", clean))).get("list").get(0);
        assertThat(cleanNode.get("canDisable").asBoolean()).isTrue();
        assertThat(cleanNode.get("canDelete").asBoolean()).isTrue();
        assertThat(cleanNode.get("deleteReason").isNull()).isTrue();
    }

    /** G5 最后正常管理员：由另一管理员身份查询，避免 self 优先级覆盖。 */
    @Test
    void ad_19_guardsLastAdmin() throws Exception {
        // ADM 是库中唯一正常管理员，但请求者用 ADM2 的身份（其 claim 仍为管理员），
        // 因此 guards 看到的目标不是"自己"，才会进入 last_admin 判定分支。
        withOnlyAdmin(ADM, () -> {
            JsonNode node = assertOk(perform(get("/admin/user/guards")
                    .header("Authorization", token(ADM2, "管理员")).param("uids", ADM))).get("list").get(0);
            assertThat(node.get("uid").asText()).isEqualTo(ADM);
            assertThat(node.get("canDisable").asBoolean()).isFalse();
            assertThat(node.get("canDelete").asBoolean()).isFalse();
            assertThat(node.get("disableReason").asText()).isEqualTo("last_admin");
            assertThat(node.get("deleteReason").asText()).isEqualTo("last_admin");
        });
    }

    /** G5 入参严格：缺失 / 空 / 重复 / 超 50 / 非法格式 400；合法不存在 404 且整次失败。 */
    @Test
    void ad_20_guardsInputStrict() throws Exception {
        assertError(perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))),
                400, "invalid_request");
        assertError(perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))
                .param("uids", "")), 400, "invalid_request");
        assertError(perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))
                .param("uids", U + "," + U)), 400, "invalid_request");
        assertError(perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))
                .param("uids", "ad bad")), 400, "invalid_request");
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            tooMany.append("ad-x").append(i).append(',');
        }
        assertError(perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))
                .param("uids", tooMany.substring(0, tooMany.length() - 1))), 400, "invalid_request");
        MvcResult missing = perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))
                .param("uids", U + "," + GONE));
        assertError(missing, 404, "not_found");
        JsonNode body = parseBody(missing);
        assertThat(body.get("data").isNull()).as("整次失败时 data 必须为 null").isTrue();
    }

    /** G5 用户列表仍严格八键，不允许加 workCount。 */
    @Test
    void ad_21_userListStaysEightKeys() throws Exception {
        JsonNode data = assertOk(perform(get("/admin/user/list").header("Authorization", token(ADM, "管理员"))
                .param("keyword", "ad作者")));
        assertThat(data.has("list") && data.has("total")).isTrue();
        assertThat(data.get("list").size()).isGreaterThanOrEqualTo(1);
        JsonNode item = data.get("list").get(0);
        assertThat(keysOf(item)).isEqualTo(new HashSet<>(Arrays.asList(
                "uid", "nickname", "avatar", "bio", "role", "status", "pnum", "createdAt")));
    }
    // ------------------------------------------------------------------
    // G5.1 级联删除（cascade=true）
    // ------------------------------------------------------------------

    /** 级联删除：用户与其作品及全部从属数据一并清除，返回实际删除的作品数。 */
    @Test
    void ad_26_cascadeDeleteRemovesWorksAndRelations() throws Exception {
        String target = "ad-del-user";
        seedUser(target, "ad待删用户", "普通用户", "正常");
        String w1 = "ad-del-w1";
        String w2 = "ad-del-w2";
        String w3 = "ad-del-w3";
        seedWork(w1, target, "展示", "2099-06-01 00:00:01", CAT1);
        seedWork(w2, target, "隐藏", "2099-06-01 00:00:02", null);
        seedWork(w3, target, "已驳回", "2099-06-01 00:00:03", CAT1);
        seedExif(w1, 50, "1.8", "1/500", 100, "FE 50mm", "Sony A7C");
        seedAudit("ad-del-a1", w1, "pass", null, ADM);
        jdbc.update("INSERT INTO comment (cid, uid, pid, comcontent, likecom, comct) "
                + "VALUES ('ad-del-c1', ?, ?, 'ad评论', 0, NOW())", target, w1);
        jdbc.update("INSERT INTO favorite (fid, uid, pid, ct) VALUES ('ad-del-f1', ?, ?, NOW())", target, w1);
        jdbc.update("INSERT INTO liketable (lid, uid, likect, targetid, targettype) "
                + "VALUES ('ad-del-l1', ?, NOW(), ?, '帖子')", target, w1);
        jdbc.update("INSERT INTO vote (vid, uid, pid, votect) VALUES ('ad-del-v1', ?, ?, NOW())", target, w2);
        jdbc.update("INSERT INTO pcatogory (pcid, pid, uid, pcname, cdesc, sort, pcct, pc_count) "
                + "VALUES ('ad-del-p1', ?, ?, 'ad分类', 'ad', 0, NOW(), 0)", w2, target);
        jdbc.update("INSERT INTO work_embedding (pid, model, dim, vec, status, err, uct) "
                + "VALUES (?, 'ad-model', 2, '[0,0]', 'ready', NULL, NOW())", w3);
        jdbc.update("INSERT INTO follow (fid, uid, tuid, ct) VALUES ('ad-del-fo1', ?, ?, NOW())", target, O);
        // 历史死业务：订单与礼物功能已决定不做，删用户时它们的用户行一并清理。
        jdbc.update("INSERT INTO gift (gid, uid, gname, gurl, gneed, stocknum) "
                + "VALUES ('ad-del-g1', ?, 'ad礼物', '', 0, 1)", target);
        jdbc.update("INSERT INTO orderinfo (orid, uid, gid, needor, address, remark) "
                + "VALUES ('ad-del-o1', ?, 'ad-del-g1', '0', 'ad地址', 'ad备注')", target);

        JsonNode data = assertOk(perform(delete("/admin/user/" + target).param("cascade", "true")
                .header("Authorization", token(ADM, "管理员"))));
        assertThat(data != null && !data.isNull()).as("删除必须返回 {uid, deletedWorks} 而不是 null").isTrue();
        assertThat(keysOf(data)).isEqualTo(new HashSet<>(Arrays.asList("uid", "deletedWorks")));
        assertThat(data.get("uid").asText()).isEqualTo(target);
        assertThat(data.get("deletedWorks").asLong()).isEqualTo(3L);

        assertThat(countWhere("user", "uid", target)).isZero();
        assertThat(countWhere("pwork", "uid", target)).isZero();
        assertThat(countWhere("work_exif", "pid", w1)).isZero();
        assertThat(countWhere("waudit", "pid", w1)).isZero();
        assertThat(countWhere("comment", "uid", target)).isZero();
        assertThat(countWhere("favorite", "uid", target)).isZero();
        assertThat(countWhere("liketable", "uid", target)).isZero();
        assertThat(countWhere("vote", "uid", target)).isZero();
        assertThat(countWhere("pcatogory", "uid", target)).isZero();
        assertThat(countWhere("work_embedding", "pid", w3)).isZero();
        assertThat(countWhere("gift", "uid", target)).isZero();
        assertThat(countWhere("orderinfo", "uid", target)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM follow WHERE uid = ? OR tuid = ?", Integer.class, target, target)).isZero();
    }

    /** 不带 cascade 时保持 v1：有作品一律 409，且数据一行不少。 */
    @Test
    void ad_27_deleteWithoutCascadeStillConflicts() throws Exception {
        String target = "ad-del-user2";
        seedUser(target, "ad待删用户2", "普通用户", "正常");
        seedWork("ad-del-x1", target, "展示", "2099-06-02 00:00:01", null);
        assertError(perform(delete("/admin/user/" + target).header("Authorization", token(ADM, "管理员"))),
                409, "conflict");
        // cascade=false / 其它值同样按 v1 处理，不做类型转换。
        assertError(perform(delete("/admin/user/" + target).param("cascade", "false")
                .header("Authorization", token(ADM, "管理员"))), 409, "conflict");
        assertError(perform(delete("/admin/user/" + target).param("cascade", "1")
                .header("Authorization", token(ADM, "管理员"))), 409, "conflict");
        assertThat(countWhere("user", "uid", target)).isEqualTo(1);
        assertThat(countWhere("pwork", "uid", target)).isEqualTo(1);
    }

    /** 无作品的普通用户：不带 cascade 也能删，保持 v1 的 data=null（既有 AuditApiTest 依赖）。 */
    @Test
    void ad_28_deleteWithoutWorksNeedsNoCascade() throws Exception {
        String target = "ad-del-empty";
        seedUser(target, "ad空用户", "普通用户", "正常");
        JsonNode data = assertOk(perform(delete("/admin/user/" + target)
                .header("Authorization", token(ADM, "管理员"))));
        assertThat(data == null || data.isNull()).as("v1 兼容：普通删除不返回 data").isTrue();
        assertThat(countWhere("user", "uid", target)).isZero();
    }

    /**
     * 无作品但有互动数据的用户：普通删除必须先清 comment/like/favorite/follow，
     * 否则 user 的 RESTRICT 外键会挡住删除并返回 500（真实数据踩到的 bug）。
     */
    @Test
    void ad_30_deleteWithoutWorksStillClearsRelations() throws Exception {
        String target = "ad-del-rel";
        seedUser(target, "ad只互动", "普通用户", "正常");
        jdbc.update("INSERT INTO comment (cid, uid, pid, comcontent, likecom, comct) "
                + "VALUES ('ad-r-c1', ?, ?, 'ad评论', 0, NOW())", target, W_SHOW);
        jdbc.update("INSERT INTO favorite (fid, uid, pid, ct) VALUES ('ad-r-f1', ?, ?, NOW())", target, W_SHOW);
        jdbc.update("INSERT INTO liketable (lid, uid, likect, targetid, targettype) "
                + "VALUES ('ad-r-l1', ?, NOW(), ?, '帖子')", target, W_SHOW);
        jdbc.update("INSERT INTO follow (fid, uid, tuid, ct) VALUES ('ad-r-fo1', ?, ?, NOW())", target, O);

        JsonNode data = assertOk(perform(delete("/admin/user/" + target)
                .header("Authorization", token(ADM, "管理员"))));
        assertThat(data == null || data.isNull()).as("v1 兼容：普通删除不返回 data").isTrue();
        assertThat(countWhere("user", "uid", target)).isZero();
        assertThat(countWhere("comment", "uid", target)).isZero();
        assertThat(countWhere("favorite", "uid", target)).isZero();
        assertThat(countWhere("liketable", "uid", target)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow WHERE uid = ? OR tuid = ?",
                Integer.class, target, target)).isZero();
        // 别人的作品与其它用户的互动不能被误删。
        assertThat(countWhere("pwork", "pid", W_SHOW)).isEqualTo(1);
    }

    /** 级联不越过保护：自己 403，最后一个正常管理员 409。 */
    @Test
    void ad_29_cascadeStillRespectsProtection() throws Exception {
        assertError(perform(delete("/admin/user/" + ADM).param("cascade", "true")
                .header("Authorization", token(ADM, "管理员"))), 403, "forbidden");
        withOnlyAdmin(ADM, () ->
                assertError(perform(delete("/admin/user/" + ADM).param("cascade", "true")
                        .header("Authorization", token(ADM2, "管理员"))), 409, "conflict"));
        assertThat(countWhere("user", "uid", ADM)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // G6 权限与信封
    // ------------------------------------------------------------------

    /** G6 权限矩阵：匿名 401、普通用户 403、禁用主体 401、坏头+好 Cookie 401，全部 JSON 信封不重定向。 */
    @Test
    void ad_22_permissionMatrix() throws Exception {
        List<java.util.function.Supplier<MockHttpServletRequestBuilder>> apis = adminApis();
        for (java.util.function.Supplier<MockHttpServletRequestBuilder> api : apis) {
            assertError(perform(api.get()), 401, "unauthorized");
            assertError(perform(api.get().header("Authorization", token(U, "普通用户"))), 403, "forbidden");
            assertError(perform(api.get().header("Authorization", token(DIS, "普通用户"))), 401, "unauthorized");
            // 坏头 + 好 Cookie：头存在但非 Bearer，不得回退到 Cookie。
            assertError(perform(api.get().header("Authorization", "Basic abc")
                    .cookie(new javax.servlet.http.Cookie("token", token(ADM, "管理员").substring(7)))),
                    401, "unauthorized");
        }
        // 管理员凭 Cookie 可用（页面侧同一身份）。
        assertOk(mvc.perform(get("/admin/stats")
                .cookie(new javax.servlet.http.Cookie("token", token(ADM, "管理员").substring(7)))).andReturn());
    }

    /** G6 方法不支持返回 405，且不返回 HTML。 */
    @Test
    void ad_23_methodNotAllowed() throws Exception {
        MvcResult result = mvc.perform(post("/admin/stats").header("Authorization", token(ADM, "管理员"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat(result.getResponse().getContentType()).contains("application/json");
    }

    /** G6 管理页面未授权仍 302 到登录页，不返回数据。 */
    @Test
    void ad_24_adminPagesRedirectAnonymous() throws Exception {
        for (String page : new String[]{"/page/admin/index", "/page/admin/audit",
                "/page/admin/works", "/page/admin/users"}) {
            MvcResult result = mvc.perform(get(page)).andReturn();
            assertThat(result.getResponse().getStatus()).as("%s 应重定向", page).isEqualTo(302);
            assertThat(result.getResponse().getRedirectedUrl()).contains("/login/page");
        }
    }

    // ------------------------------------------------------------------
    // G7 只读性
    // ------------------------------------------------------------------

    /** G7 四类读接口连续调用零写入：行数与浏览计数全部不变。 */
    @Test
    void ad_25_readOnlyNoSideEffects() throws Exception {
        String snap = snapshot();
        long view = jdbc.queryForObject("SELECT viewcount FROM pwork WHERE pid = ?", Long.class, W_SHOW);
        for (int i = 0; i < 3; i++) {
            assertOk(perform(get("/admin/stats").header("Authorization", token(ADM, "管理员"))));
            assertOk(perform(get("/admin/work/list").header("Authorization", token(ADM, "管理员"))));
            assertOk(perform(get("/admin/work/" + W_SHOW).header("Authorization", token(ADM, "管理员"))));
            assertOk(perform(get("/admin/user/guards").header("Authorization", token(ADM, "管理员"))
                    .param("uids", U + "," + ADM)));
        }
        assertThat(snapshot()).as("读接口不得写库").isEqualTo(snap);
        assertThat(jdbc.queryForObject("SELECT viewcount FROM pwork WHERE pid = ?", Long.class, W_SHOW))
                .isEqualTo(view);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private List<java.util.function.Supplier<MockHttpServletRequestBuilder>> adminApis() {
        List<java.util.function.Supplier<MockHttpServletRequestBuilder>> apis = new ArrayList<>();
        apis.add(() -> get("/admin/stats"));
        apis.add(() -> get("/admin/work/list"));
        apis.add(() -> get("/admin/work/" + W_SHOW));
        apis.add(() -> put("/admin/work/" + W_SHOW + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"隐藏\"}"));
        apis.add(() -> get("/admin/user/guards").param("uids", U));
        return apis;
    }

    private String snapshot() {
        return jdbc.queryForObject("SELECT CONCAT("
                + "(SELECT COUNT(*) FROM user), '-', "
                + "(SELECT COUNT(*) FROM pwork), '-', "
                + "(SELECT COUNT(*) FROM waudit), '-', "
                + "(SELECT IFNULL(SUM(viewcount),0) FROM pwork), '-', "
                + "(SELECT IFNULL(SUM(likep),0) FROM pwork))", String.class);
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

    private long statLong(String key) throws Exception {
        return assertOk(perform(get("/admin/stats").header("Authorization", token(ADM, "管理员"))))
                .get(key).asLong();
    }

    private List<String> pidsOf(JsonNode page) {
        List<String> pids = new ArrayList<>();
        page.get("list").forEach(item -> pids.add(item.get("pid").asText()));
        return pids;
    }

    private JsonNode findCategory(JsonNode categories, String pcname) {
        for (JsonNode item : categories) {
            if (pcname.equals(item.path("pcname").asText())) {
                return item;
            }
        }
        return null;
    }

    /** 按分类 ID 定位分布项，避免用分类名匹配时的同名歧义。 */
    private JsonNode findCategoryByPcid(JsonNode categories, String pcid) {
        for (JsonNode item : categories) {
            if (pcid.equals(item.path("pcid").asText())) {
                return item;
            }
        }
        return null;
    }

    private String statusOf(String pid) {
        return jdbc.queryForObject("SELECT wstatus FROM pwork WHERE pid = ?", String.class, pid);
    }

    private int auditCount(String pid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM waudit WHERE pid = ?", Integer.class, pid);
    }

    /** 统计某表某列等于给定值的行数；表名/列名只用测试内常量，值走参数绑定。 */
    private int countWhere(String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    private void assertPidStatus(MvcResult result, String pid, String status) throws Exception {
        JsonNode data = assertOk(result);
        assertThat(keysOf(data)).isEqualTo(new HashSet<>(Arrays.asList("pid", "status")));
        assertThat(data.get("pid").asText()).isEqualTo(pid);
        assertThat(data.get("status").asText()).isEqualTo(status);
    }

    /** 临时把除目标外的正常管理员降为普通用户，结束后按原 role/status 恢复。 */
    private void withOnlyAdmin(String uid, ThrowingRunnable action) throws Exception {
        List<Map<String, Object>> others = jdbc.queryForList(
                "SELECT uid, role, status FROM user WHERE role = '管理员' AND uid <> ?", uid);
        try {
            jdbc.update("UPDATE user SET role = '普通用户' WHERE role = '管理员' AND uid <> ?", uid);
            action.run();
        } finally {
            for (Map<String, Object> row : others) {
                jdbc.update("UPDATE user SET role = ?, status = ? WHERE uid = ?",
                        row.get("role"), row.get("status"), row.get("uid"));
            }
        }
    }

    /** 允许测试体内抛受检异常的 lambda。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        /** 执行断言体。 @throws Exception 断言或请求失败 */
        void run() throws Exception;
    }
}

