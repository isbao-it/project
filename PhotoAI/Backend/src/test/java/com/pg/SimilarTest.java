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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.Key;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 卡06 同款接口冻结测试（同款分支）：T06-31..T06-48、T06-50、T06-51 同款分支、T06-52 同款分支。
 * 分类分支由 CategoryTest 承接，收藏分支由 FavoriteTest 承接。
 * 只连 db_pg_test_06，夹具全部在本类内自建自清（uid/pid/pcid 统一 c06- 前缀），不继承共享基座、
 * 不改 application-test.yml。卡面同款标准夹具的 pid 示例 s06-k1/s06-k2/s06-k3 按自建唯一 ID 口径
 * 映射为本类 K1=c06-sim-k1、K2=c06-sim-k2、K3=c06-sim-k3，EXIF/likep/返回次序与冻结语义不变。
 * 匹配全部走真实 pclist/pwork/work_exif/liketable 数据断言，双阈值按冻结公式推导期望：
 * 光圈 ABS(LOG2(a1)-LOG2(a2))&lt;=1、焦距 ABS(f2-f1)/f1&lt;=0.20（f1 为源），排序 likep 降序、
 * 同 likep 按 pid 升序，limit 默认 6、上限 20；不使用 Mock 替身代替主业务检查。本分支无故障注入
 * 用例，不引入任何测试替身，也不对 final 构造器注入字段做反射打桩。所有断言在实现缺失时因路由
 * 或目标行为缺失而红，不使用 @Disabled、不放松期望。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SimilarTest {

    /** 测试库固定指向 db_pg_test_06，不改共享 application-test.yml。 */
    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:mysql://localhost:3306/db_pg_test_06?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
    }

    /** 与 application-test.yml 相同的测试签名密钥，仅用于测试内签发过期 JWT。 */
    private static final String TEST_JWT_SECRET =
            "cGhvdG9haS1jYXJkMDEtdGVzdC1zZWNyZXQtb25seS0yMDI2MTAyMA==";

    private static final Logger log = LoggerFactory.getLogger(SimilarTest.class);

    /** 测试身份：U 正常普通用户（源作品作者），A 正常管理员。 */
    private static final String U = "c06-sim-u";
    private static final String A = "c06-sim-a";
    /** 标准分类夹具 C1/C2：pcid 依次 c06-c1/c06-c2，pcname 人像/风光。 */
    private static final String C1 = "c06-c1";
    private static final String C2 = "c06-c2";
    /** 同款标准夹具：源 S 与正向集合 K1/K2/K3。 */
    private static final String S = "c06-sim-s";
    private static final String K1 = "c06-sim-k1";
    private static final String K2 = "c06-sim-k2";
    private static final String K3 = "c06-sim-k3";
    /** 非展示源作品 H1..H3：待审核/隐藏/已驳回；M 为格式合法但不存在的作品。 */
    private static final String H1 = "c06-sim-h1";
    private static final String H2 = "c06-sim-h2";
    private static final String H3 = "c06-sim-h3";
    private static final String M = "c06-sim-m";
    /** 逐样本候选作品 pid 前缀，便于样本间精确清理。 */
    private static final String CAND = "c06-sim-cand-";
    /** 路径 pid 的冻结长度上限，超过即 400。 */
    private static final int PID_MAX_LENGTH = 36;
    /** 同款 limit 冻结范围：默认 6、上限 20。 */
    private static final int DEFAULT_LIMIT = 6;
    private static final int LIMIT_MAX = 20;
    /** 源作品冻结 EXIF：focal=50、aperture=4.0。 */
    private static final int SOURCE_FOCAL = 50;
    private static final String SOURCE_APERTURE = "4.0";
    /** INT UNSIGNED 计数样本：超出 Java Integer 正数范围，服务端必须以 Long 输出原值。 */
    private static final long INT_UNSIGNED_SAMPLE = 2147483648L;
    /** 溢出 Integer 上限的 limit 样本，必须 400 而不是夹到 20。 */
    private static final String LIMIT_OVERFLOW = "2147483648";
    /** T06-50 冻结的固定作品时间及响应格式。 */
    private static final String FIXED_CWTIME = "2026-10-23 10:20:30";
    private static final String FIXED_CWTIME_ISO = "2026-10-23T10:20:30+08:00";
    /** T06-51 读前后逐行一致的快照表集合。 */
    private static final String[] SNAPSHOT_TABLES = {"pwork", "favorite", "pclist", "work_exif"};

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
    SimilarTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    /**
     * 每个用例前校验库名并清空本类夹具，再写入两个测试身份。
     */
    @BeforeEach
    void prepare() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test_06");
        cleanupFixtures();
        seedUser(U, "c06同款用户U", "普通用户", "19900000201");
        seedUser(A, "c06同款管理员A", "管理员", "19900000202");
        log.info("SimilarTest 夹具准备完成 uid={}/{}", U, A);
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'c06-%' OR uid LIKE 'c06-%' OR targetid LIKE 'c06-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'c06-%' OR uid LIKE 'c06-%' OR pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pcatogory WHERE pcid LIKE 'c06-%' OR pid LIKE 'c06-%' OR uid LIKE 'c06-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'c06-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'c06-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（标准集合、双阈值与候选过滤）
    // ------------------------------------------------------------------

    /** T06-31 标准 K 集合：likep 降序、相同 likep 按 pid 升序，精确 K1/K2/K3 三份十四键对象；data 只有 list 无 total，不能用 realLikeCount 排序。 */
    @Test
    void sim_31_standardCandidatesExactOrderAndKeys() throws Exception {
        seedStandardSimilarFixture();
        assertSimilarList(similarGet(S, String.valueOf(LIMIT_MAX)), K1, K2, K3);
    }

    /** T06-32 两个阈值均含边界：候选 (40,2)(60,8)(50,2)(50,8)(40,4)(60,4) 逐例都应参与匹配并返回。 */
    @Test
    void sim_32_inclusiveThresholdBoundariesMatch() throws Exception {
        seedSource();
        Object[][] samples = {
                {40, "2.0"}, {60, "8.0"}, {50, "2.0"}, {50, "8.0"}, {40, "4.0"}, {60, "4.0"}
        };
        for (int i = 0; i < samples.length; i++) {
            clearCandidates();
            String pid = CAND + String.format("%02d", i + 1);
            seedCandidate(pid, (Integer) samples[i][0], (String) samples[i][1]);
            log.info("sim_32 含边界样本 focal={} aperture={} pid={}", samples[i][0], samples[i][1], pid);
            assertSimilarList(similarGet(S, null), pid);
        }
    }

    /** T06-33 任一条件失败即排除：候选 (39,4)(61,4)(50,1.9)(50,8.1)(39,8.1) 逐例返回空 list。 */
    @Test
    void sim_33_outOfRangeThresholdsExcluded() throws Exception {
        seedSource();
        Object[][] samples = {
                {39, "4.0"}, {61, "4.0"}, {50, "1.9"}, {50, "8.1"}, {39, "8.1"}
        };
        for (int i = 0; i < samples.length; i++) {
            clearCandidates();
            String pid = CAND + String.format("%02d", i + 1);
            seedCandidate(pid, (Integer) samples[i][0], (String) samples[i][1]);
            log.info("sim_33 越界样本 focal={} aperture={} pid={}", samples[i][0], samples[i][1], pid);
            assertSimilarList(similarGet(S, null));
        }
    }

    /** T06-34 焦距相对差以源焦距作分母：41 例 9/50=18% 接受，61 例 11/50=22% 拒绝。 */
    @Test
    void sim_34_focalRelativeDiffUsesSourceDenominator() throws Exception {
        seedSource();
        clearCandidates();
        String accept = CAND + "34a";
        seedCandidate(accept, 41, "4.0");
        log.info("sim_34 41mm 接受样本 pid={}", accept);
        assertSimilarList(similarGet(S, null), accept);
        clearCandidates();
        String reject = CAND + "34b";
        seedCandidate(reject, 61, "4.0");
        log.info("sim_34 61mm 拒绝样本 pid={}", reject);
        assertSimilarList(similarGet(S, null));
    }

    /** T06-35 排除源图、异分类与所有非展示候选：本人 S、C2 展示、C1 待审核/隐藏/已驳回 逐例为空，不按作者或管理员放行。 */
    @Test
    void sim_35_excludesSourceOtherCategoryAndNonDisplayed() throws Exception {
        seedSource();
        clearCandidates();
        log.info("sim_35 候选为源图本身样本 pid={}", S);
        assertSimilarList(similarGet(S, null));
        clearCandidates();
        String otherCategory = CAND + "35c2";
        seedCandidate(otherCategory, 50, "4.0");
        jdbc.update("UPDATE pwork SET pcid=? WHERE pid=?", C2, otherCategory);
        log.info("sim_35 异分类展示候选样本 pid={}", otherCategory);
        assertSimilarList(similarGet(S, null));
        String[] statuses = {"待审核", "隐藏", "已驳回"};
        for (int i = 0; i < statuses.length; i++) {
            clearCandidates();
            String pid = CAND + String.format("35s%d", i + 1);
            seedWork(pid, U, statuses[i], C1);
            seedExif(pid, 50, "4.0");
            log.info("sim_35 非展示候选样本 pid={} status={}", pid, statuses[i]);
            assertSimilarList(similarGet(S, null));
        }
    }

    /** T06-36 分类为空不臆测：源与候选均 NULL、源 C1 候选 NULL 两例均为空，旧 pcatogory 相同关联不回退，两个 NULL 不视为同分类。 */
    @Test
    void sim_36_nullCategoryNeverInferred() throws Exception {
        seedSource();
        clearCandidates();
        jdbc.update("UPDATE pwork SET pcid=NULL WHERE pid=?", S);
        String bothNull = CAND + "36a";
        seedCandidate(bothNull, 50, "4.0");
        jdbc.update("UPDATE pwork SET pcid=NULL WHERE pid=?", bothNull);
        seedLegacyCategory("c06-sim-legacy", S, U, "旧同款分类", 9);
        seedLegacyCategory("c06-sim-legacy", bothNull, U, "旧同款分类", 9);
        log.info("sim_36 源 NULL 候选 NULL 样本 pid={}", bothNull);
        assertSimilarList(similarGet(S, null));
        clearCandidates();
        jdbc.update("UPDATE pwork SET pcid=? WHERE pid=?", C1, S);
        String candidateNull = CAND + "36b";
        seedCandidate(candidateNull, 50, "4.0");
        jdbc.update("UPDATE pwork SET pcid=NULL WHERE pid=?", candidateNull);
        seedLegacyCategory("c06-sim-legacy", candidateNull, U, "旧同款分类", 9);
        log.info("sim_36 源 C1 候选 NULL 样本 pid={}", candidateNull);
        assertSimilarList(similarGet(S, null));
    }

    /** T06-37 源缺 EXIF 行或缺任一必要参数返回空 list，即使有完全合格热门候选也不补位，不 404。 */
    @Test
    void sim_37_sourceMissingExifIsEmptyList() throws Exception {
        seedSource();
        clearCandidates();
        seedCandidate(CAND + "37hot", 50, "4.0");
        jdbc.update("UPDATE pwork SET likep=999 WHERE pid=?", CAND + "37hot");
        jdbc.update("DELETE FROM work_exif WHERE pid=?", S);
        log.info("sim_37 源无 work_exif 行样本");
        assertSimilarList(similarGet(S, null));
        jdbc.update("DELETE FROM work_exif WHERE pid=?", S);
        seedExif(S, null, SOURCE_APERTURE);
        log.info("sim_37 源 focal=NULL 样本");
        assertSimilarList(similarGet(S, null));
        jdbc.update("DELETE FROM work_exif WHERE pid=?", S);
        seedExif(S, SOURCE_FOCAL, null);
        log.info("sim_37 源 aperture=NULL 样本");
        assertSimilarList(similarGet(S, null));
        jdbc.update("DELETE FROM work_exif WHERE pid=?", S);
        seedExif(S, null, null);
        log.info("sim_37 源两参数均 NULL 样本");
        assertSimilarList(similarGet(S, null));
    }

    /** T06-38 源参数非正数返回空 list：focal=0/-1、aperture=0/-1 逐例，不除零、不对非正数取 LOG、不 500。 */
    @Test
    void sim_38_sourceNonPositiveParamsIsEmptyList() throws Exception {
        seedSource();
        clearCandidates();
        seedCandidate(CAND + "38hot", 50, "4.0");
        jdbc.update("UPDATE pwork SET likep=999 WHERE pid=?", CAND + "38hot");
        Object[][] samples = {
                {0, SOURCE_APERTURE}, {-1, SOURCE_APERTURE}, {SOURCE_FOCAL, "0.0"}, {SOURCE_FOCAL, "-1.0"}
        };
        for (Object[] sample : samples) {
            jdbc.update("DELETE FROM work_exif WHERE pid=?", S);
            seedExif(S, (Integer) sample[0], (String) sample[1]);
            log.info("sim_38 源非正数样本 focal={} aperture={}", sample[0], sample[1]);
            assertSimilarList(similarGet(S, null));
        }
    }

    /** T06-39 候选缺 EXIF 行或参数为 NULL/非正数不参与匹配：逐例返回空，不以默认焦距/光圈补齐。 */
    @Test
    void sim_39_invalidCandidateExifExcluded() throws Exception {
        seedSource();
        clearCandidates();
        String noExif = CAND + "39a";
        seedWork(noExif, U, "展示", C1);
        log.info("sim_39 候选无 EXIF 行样本 pid={}", noExif);
        assertSimilarList(similarGet(S, null));
        seedInvalidCandidate("39b", null, "4.0");
        seedInvalidCandidate("39c", 50, null);
        seedInvalidCandidate("39d", null, null);
        seedInvalidCandidate("39e", 0, "4.0");
        seedInvalidCandidate("39f", -1, "4.0");
        seedInvalidCandidate("39g", 50, "0.0");
        seedInvalidCandidate("39h", 50, "-1.0");
    }

    /** T06-40 必要参数只有 focal/aperture：候选 shutter/iso/lens/camera 逐例 NULL 及四者全 NULL 仍匹配。 */
    @Test
    void sim_40_otherExifNullsStillMatch() throws Exception {
        seedSource();
        Object[][] samples = {
                {null, 100, "50mm", "Canon"},
                {"1/250", null, "50mm", "Canon"},
                {"1/250", 100, null, "Canon"},
                {"1/250", 100, "50mm", null},
                {null, null, null, null}
        };
        for (int i = 0; i < samples.length; i++) {
            clearCandidates();
            String pid = CAND + String.format("40%02d", i + 1);
            seedWork(pid, U, "展示", C1);
            seedExifFull(pid, 50, "4.0", (String) samples[i][0], (Integer) samples[i][1],
                    (String) samples[i][2], (String) samples[i][3]);
            log.info("sim_40 其它 EXIF 缺失样本 pid={}", pid);
            assertSimilarList(similarGet(S, null), pid);
        }
    }

    /** T06-41 无合格候选时返回空：高 likep 异分类与超阈值候选不热门补位、不向量兜底。 */
    @Test
    void sim_41_noHotFillOrVectorFallback() throws Exception {
        seedSource();
        clearCandidates();
        String hotOther = CAND + "41a";
        seedCandidate(hotOther, 50, "4.0");
        jdbc.update("UPDATE pwork SET pcid=?, likep=999 WHERE pid=?", C2, hotOther);
        String overThreshold = CAND + "41b";
        seedCandidate(overThreshold, 100, "4.0");
        jdbc.update("UPDATE pwork SET likep=998 WHERE pid=?", overThreshold);
        log.info("sim_41 高 likep 异分类与超阈值候选样本 pid={}/{}", hotOther, overThreshold);
        assertSimilarList(similarGet(S, null));
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（源可见性与路径/参数校验）
    // ------------------------------------------------------------------

    /** T06-42 源非展示逐状态 404：匿名/作者/管理员 × 待审核/隐藏/已驳回 × 有/无 EXIF，源可见性先于缺 EXIF 成功空列表。 */
    @Test
    void sim_42_hiddenSourceIs404AllIdentities() throws Exception {
        seedCategory(C1, "人像", 1);
        String[] pids = {H1, H2, H3};
        String[] statuses = {"待审核", "隐藏", "已驳回"};
        for (int i = 0; i < pids.length; i++) {
            seedWork(pids[i], U, statuses[i], C1);
            seedExif(pids[i], 50, "4.0");
        }
        for (int i = 0; i < pids.length; i++) {
            assertHiddenSource404(pids[i], statuses[i], true);
            jdbc.update("DELETE FROM work_exif WHERE pid=?", pids[i]);
            assertHiddenSource404(pids[i], statuses[i], false);
        }
    }

    /** T06-43 格式合法但不存在的源 404 not_found，不能返回成功空列表。 */
    @Test
    void sim_43_missingSourceIs404NotEmptyList() throws Exception {
        assertError(mvc.perform(get("/pwork/similar/" + M)).andReturn(), 404, "not_found");
    }

    /** T06-44 非法 pid 逐例 400 invalid_request：URL 编码的 bad! 与 37 位，不截断 ID、不按数字解析。 */
    @Test
    void sim_44_badPidFormatsAre400() throws Exception {
        assertError(mvc.perform(get("/pwork/similar/bad%21")).andReturn(), 400, "invalid_request");
        assertError(mvc.perform(get("/pwork/similar/" + repeat("a", PID_MAX_LENGTH + 1)))
                .andReturn(), 400, "invalid_request");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（limit 边界与截取次序）
    // ------------------------------------------------------------------

    /** T06-45 默认 limit=6：21 个合格候选只取排序后前 6 份，截断边界同 likep 按 pid 升序，无 total。 */
    @Test
    void sim_45_defaultLimitSixExactOrder() throws Exception {
        seedSource();
        seed21Candidates();
        String[] firstSix = new String[DEFAULT_LIMIT];
        for (int i = 0; i < DEFAULT_LIMIT; i++) {
            firstSix[i] = candPid(i + 1);
        }
        assertSimilarList(similarGet(S, null), firstSix);
    }

    /** T06-46 limit=1 只返回排名第一的候选：先排序/过滤再截取，不能先 LIMIT 再排序。 */
    @Test
    void sim_46_limitOneReturnsTopOnly() throws Exception {
        seedSource();
        seed21Candidates();
        assertSimilarList(similarGet(S, "1"), candPid(1));
    }

    /** T06-47 limit=20 上限：21 个合格候选加高 likep 无效 EXIF/非展示/异分类候选，精确返回合格前 20 份；先过滤再截取，无效候选不挤占名额。 */
    @Test
    void sim_47_limitTwentyFiltersBeforeTruncate() throws Exception {
        seedSource();
        seed21Candidates();
        String badExif = CAND + "90";
        seedWork(badExif, U, "展示", C1);
        jdbc.update("UPDATE pwork SET likep=9999 WHERE pid=?", badExif);
        String hidden = CAND + "91";
        seedWork(hidden, U, "待审核", C1);
        seedExif(hidden, 50, "4.0");
        jdbc.update("UPDATE pwork SET likep=9998 WHERE pid=?", hidden);
        String otherCategory = CAND + "92";
        seedCandidate(otherCategory, 50, "4.0");
        jdbc.update("UPDATE pwork SET pcid=?, likep=9997 WHERE pid=?", C2, otherCategory);
        String[] firstTwenty = new String[LIMIT_MAX];
        for (int i = 0; i < LIMIT_MAX; i++) {
            firstTwenty[i] = candPid(i + 1);
        }
        assertSimilarList(similarGet(S, String.valueOf(LIMIT_MAX)), firstTwenty);
    }

    /** T06-48 limit 非整数/溢出/越界逐例 400 invalid_request：0/-1/21/1.5/x/2147483648，不静默取默认或夹到 20。 */
    @Test
    void sim_48_invalidLimitsAre400() throws Exception {
        seedStandardSimilarFixture();
        String[] badLimits = {"0", "-1", String.valueOf(LIMIT_MAX + 1), "1.5", "x", LIMIT_OVERFLOW};
        for (String limit : badLimits) {
            log.info("sim_48 非法 limit 样本 limit={}", limit);
            assertError(mvc.perform(get("/pwork/similar/" + S).param("limit", limit))
                    .andReturn(), 400, "invalid_request");
        }
    }

    // ------------------------------------------------------------------
    // 冻结断言用例（计数边界、只读与凭证）
    // ------------------------------------------------------------------

    /** T06-50 唯一合格候选三计数各 2147483648、uid 与用户字段 NULL、pcname=人像、真实帖子赞 2、cwtime 精确 +08:00；无 total/exif/stripped/reason/score，NULL 键保留。 */
    @Test
    void sim_50_longCountersNullKeysAndFixedTime() throws Exception {
        seedSource();
        clearCandidates();
        String pid = CAND + "50";
        seedWork(pid, null, "c06夹具标题", "c06夹具描述", "/static/images/pwork/" + pid + ".jpg",
                INT_UNSIGNED_SAMPLE, INT_UNSIGNED_SAMPLE, "展示", FIXED_CWTIME, INT_UNSIGNED_SAMPLE, C1);
        seedExif(pid, 50, "4.0");
        seedLike("c06-sim-l50a", U, pid, "帖子");
        seedLike("c06-sim-l50b", A, pid, "帖子");
        seedLike("c06-sim-l50c", U, pid, "评论");
        JsonNode data = assertOk(mvc.perform(get("/pwork/similar/" + S).param("limit", "1")).andReturn());
        assertKeys(data, "list");
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        JsonNode item = list.get(0);
        assertKeys(item, "pid", "uid", "wtitle", "wdesc", "vurl", "viewcount", "votecount",
                "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
        assertThat(item.get("pid").asText()).isEqualTo(pid);
        assertThat(item.get("uid").isNull()).isTrue();
        assertThat(item.get("wtitle").asText()).isEqualTo("c06夹具标题");
        assertThat(item.get("wdesc").asText()).isEqualTo("c06夹具描述");
        assertThat(item.get("vurl").asText()).isEqualTo("/static/images/pwork/" + pid + ".jpg");
        assertCount(item.get("viewcount"), INT_UNSIGNED_SAMPLE);
        assertCount(item.get("votecount"), INT_UNSIGNED_SAMPLE);
        assertCount(item.get("likep"), INT_UNSIGNED_SAMPLE);
        assertThat(item.get("wstatus").asText()).isEqualTo("展示");
        assertThat(item.get("cwtime").asText()).isEqualTo(FIXED_CWTIME_ISO);
        assertThat(item.get("uname").isNull()).isTrue();
        assertThat(item.get("avatar").isNull()).isTrue();
        assertThat(item.get("pcname").asText()).isEqualTo("人像");
        assertCount(item.get("realLikeCount"), 2L);
    }

    /** T06-51 同款分支：标准夹具下读同款前后 pwork/favorite/pclist/work_exif 快照逐行相同，不触发浏览+1，标准 K 集合精确返回。 */
    @Test
    void sim_51_readOnlySnapshot() throws Exception {
        seedStandardSimilarFixture();
        Map<String, List<String>> before = snapshotTables();
        MvcResult result = mvc.perform(get("/pwork/similar/" + S).param("limit", String.valueOf(LIMIT_MAX)))
                .andReturn();
        assertSimilarList(result, K1, K2, K3);
        Map<String, List<String>> after = snapshotTables();
        for (String table : SNAPSHOT_TABLES) {
            assertThat(after.get(table)).as("表 %s 读前后逐行一致", table).isEqualTo(before.get(table));
        }
    }

    /** T06-52 同款分支：匿名可读不能忽略主动携带的坏凭证，坏签名与过期 JWT 逐例 401 unauthorized。 */
    @Test
    void sim_52_badCredentialsOnPublicReadIs401() throws Exception {
        seedStandardSimilarFixture();
        String badSignature = "Bearer " + corrupt(jwtUtils.generateToken(U, "普通用户"));
        String expired = "Bearer " + expiredToken(U, "普通用户");
        for (String authorization : new String[]{badSignature, expired}) {
            log.info("sim_52 坏凭证样本 authorization 前缀={}", authorization.substring(0, 12));
            assertError(mvc.perform(get("/pwork/similar/" + S)
                    .header("Authorization", authorization)).andReturn(), 401, "unauthorized");
        }
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    private void seedUser(String uid, String nickname, String role, String pnum) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status) VALUES (?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "c06-test-password", "/avatar/" + uid + ".png", "0", "正常");
    }

    private void seedCategory(String pcid, String pcname, int sort) {
        jdbc.update("INSERT INTO pclist (pcid, pcname, cds, sort) VALUES (?,?,?,?)",
                pcid, pcname, pcname + "测试分类说明", sort);
    }

    private void seedWork(String pid, String authorUid, String status, String pcid) {
        seedWork(pid, authorUid, "c06标题" + pid, "c06描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                1, 1, status, "2026-10-23 09:00:00", 1, pcid);
    }

    private void seedWork(String pid, String authorUid, String title, String desc, String vurl,
                          long viewcount, long votecount, String status, String cwtime, long likep, String pcid) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, cwtime, likep, pcid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                pid, authorUid, title, desc, vurl, viewcount, votecount, status, cwtime, likep, pcid);
    }

    private void seedExif(String pid, Integer focal, String aperture) {
        jdbc.update("INSERT INTO work_exif (pid, focal, aperture, stripped) VALUES (?,?,?,0)",
                pid, focal, aperture);
    }

    private void seedExifFull(String pid, Integer focal, String aperture, String shutter,
                              Integer iso, String lens, String camera) {
        jdbc.update("INSERT INTO work_exif (pid, focal, aperture, shutter, iso, lens, camera, stripped) "
                        + "VALUES (?,?,?,?,?,?,?,0)",
                pid, focal, aperture, shutter, iso, lens, camera);
    }

    private void seedLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT IGNORE INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void seedFavorite(String fid, String uid, String pid) {
        jdbc.update("INSERT IGNORE INTO favorite (fid, uid, pid) VALUES (?,?,?)", fid, uid, pid);
    }

    private void seedLegacyCategory(String pcid, String pid, String uid, String pcname, int count) {
        jdbc.update("INSERT INTO pcatogory (pcid, pid, uid, pcname, cdesc, sort, pc_count) VALUES (?,?,?,?,?,?,?)",
                pcid, pid, uid, pcname, "旧关系描述", "9", count);
    }

    /** 源作品 S：展示、C1、focal=50/aperture=4.0、likep=100 高于全部候选，验证靠 pid 排除源图而不是 likep。 */
    private void seedSource() {
        seedCategory(C1, "人像", 1);
        seedWork(S, U, "c06标题" + S, "c06描述" + S, "/static/images/pwork/" + S + ".jpg",
                30, 10, "展示", "2026-10-23 09:00:00", 100, C1);
        seedExif(S, SOURCE_FOCAL, SOURCE_APERTURE);
    }

    /** 同款标准夹具：源 S 与正向集合 K1(focal=40,aperture=2,likep=9)、K2(60,8,8)、K3(50,4,8)；realLikeCount 顺序与 likep 故意不同。 */
    private void seedStandardSimilarFixture() {
        seedSource();
        seedCategory(C2, "风光", 1);
        seedWork(K1, U, "c06标题" + K1, "c06描述" + K1, "/static/images/pwork/" + K1 + ".jpg",
                1, 1, "展示", "2026-10-23 09:00:01", 9, C1);
        seedExif(K1, 40, "2.0");
        seedWork(K2, U, "c06标题" + K2, "c06描述" + K2, "/static/images/pwork/" + K2 + ".jpg",
                2, 2, "展示", "2026-10-23 09:00:02", 8, C1);
        seedExif(K2, 60, "8.0");
        seedWork(K3, U, "c06标题" + K3, "c06描述" + K3, "/static/images/pwork/" + K3 + ".jpg",
                3, 3, "展示", "2026-10-23 09:00:03", 8, C1);
        seedExif(K3, 50, "4.0");
        // realLikeCount 依次 2/1/5：按 realLikeCount 排序会得到 K3,K1,K2，冻结口径是 likep 降序。
        seedLike("c06-sim-lk1a", U, K1, "帖子");
        seedLike("c06-sim-lk1b", A, K1, "帖子");
        seedLike("c06-sim-lk2a", U, K2, "帖子");
        seedLike("c06-sim-lk2b", A, K2, "评论");
        seedLike("c06-sim-lk3a", U, K3, "帖子");
        seedLike("c06-sim-lk3b", A, K3, "帖子");
        seedLike("c06-sim-lk3c", U, K3, "帖子");
        seedLike("c06-sim-lk3d", A, K3, "帖子");
        seedLike("c06-sim-lk3e", U, K3, "帖子");
        seedFavorite("c06-sim-f1", U, S);
        seedFavorite("c06-sim-f2", A, K1);
    }

    /** 单个合格候选：展示、C1、likep=1，EXIF 按参数写入。 */
    private void seedCandidate(String pid, Integer focal, String aperture) {
        seedWork(pid, U, "c06标题" + pid, "c06描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                1, 1, "展示", "2026-10-23 09:00:00", 1, C1);
        seedExif(pid, focal, aperture);
    }

    /** 逐样本写入一个 EXIF 可能带 NULL/非正数的候选并断言被排除。 */
    private void seedInvalidCandidate(String suffix, Integer focal, String aperture) throws Exception {
        clearCandidates();
        String pid = CAND + suffix;
        seedWork(pid, U, "展示", C1);
        seedExif(pid, focal, aperture);
        log.info("sim_39 无效候选样本 pid={} focal={} aperture={}", pid, focal, aperture);
        assertSimilarList(similarGet(S, null));
    }

    /** 第 n 个批量候选的 pid（两位零填充保证字符串升序与编号一致）。 */
    private String candPid(int n) {
        return CAND + String.format("%02d", n);
    }

    /** 21 个合格候选：likep 按 9,9,8,8,7,7,7,7,6,6,5,5,4,4,3,3,2,2,2,1,1 分布，截断边界与榜首均有同 likep，检验 pid 升序决胜。 */
    private void seed21Candidates() {
        int[] likeps = {9, 9, 8, 8, 7, 7, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 2, 1, 1};
        for (int i = 0; i < likeps.length; i++) {
            String pid = candPid(i + 1);
            seedWork(pid, U, "c06标题" + pid, "c06描述" + pid, "/static/images/pwork/" + pid + ".jpg",
                    1, 1, "展示", "2026-10-23 09:00:00", likeps[i], C1);
            seedExif(pid, SOURCE_FOCAL, SOURCE_APERTURE);
        }
    }

    /** 逐样本清理候选作品及其从属行（liketable 无 pid 外键，需显式删除）。 */
    private void clearCandidates() {
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'c06-sim-cand-%' OR targetid LIKE 'c06-sim-cand-%'");
        jdbc.update("DELETE FROM favorite WHERE pid LIKE 'c06-sim-cand-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'c06-sim-cand-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c06-sim-cand-%'");
    }

    /** 逐身份断言非展示源 404 not_found 信封。 */
    private void assertHiddenSource404(String pid, String status, boolean withExif) throws Exception {
        log.info("sim_42 非展示源样本 pid={} status={} exif={}", pid, status, withExif);
        assertError(mvc.perform(get("/pwork/similar/" + pid)).andReturn(), 404, "not_found");
        assertError(mvc.perform(get("/pwork/similar/" + pid)
                .header("Authorization", token(U, "普通用户"))).andReturn(), 404, "not_found");
        assertError(mvc.perform(get("/pwork/similar/" + pid)
                .header("Authorization", token(A, "管理员"))).andReturn(), 404, "not_found");
    }

    /** 构造同款请求；limit 为 null 时省略 query，验证默认 limit。 */
    private MvcResult similarGet(String pid, String limit) throws Exception {
        MockHttpServletRequestBuilder builder = get("/pwork/similar/" + pid);
        if (limit != null) {
            builder.param("limit", limit);
        }
        return mvc.perform(builder).andReturn();
    }

    /** 断言同款成功信封与精确 list：data 只有 list 键、长度与 pid 次序冻结一致，逐项十四键与库内夹具比对。 */
    private void assertSimilarList(MvcResult result, String... expectedPids) throws Exception {
        JsonNode data = assertOk(result);
        assertKeys(data, "list");
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(expectedPids.length);
        for (int i = 0; i < expectedPids.length; i++) {
            JsonNode item = list.get(i);
            assertThat(item.get("pid").asText()).isEqualTo(expectedPids[i]);
            assertWork(item);
        }
    }

    /** 逐项十四键 W 投影断言：值与库内 pwork/user/pclist/liketable 夹具逐字段比对，NULL 键保留。 */
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

    /** 逐表读出全部行并规范化为可排序字符串行集合；时间列兼容 Timestamp/LocalDateTime 再比较，不做强转。 */
    private Map<String, List<String>> snapshotTables() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
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
            snapshot.put(table, canonicalRows);
        }
        return snapshot;
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
