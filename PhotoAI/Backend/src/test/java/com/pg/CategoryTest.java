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
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Proxy;
import java.security.Key;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 卡06 分类接口冻结测试（T06-01、T06-02、T06-03、T06-52 分类分支、T06-54）。
 * 只连 db_pg_test_06，夹具全部在本类内自建自清（uid/pid/pcid 统一 c06- 前缀），
 * 不继承共享基座；分类排序/计数全部走真实 pclist/pwork 数据断言，不用 Mock 替身
 * 代替主业务检查。T06-54 的未知 SQL 故障按卡片约定用「运行时类型查找 + 测试替身」
 * 注入：CategoryMapper 属卡06 新类型，基线不能静态引用，故由类内 @TestConfiguration
 * 的 BeanPostProcessor 在启动时按接口名定位分类查询 Mapper 并包一层受控替身，
 * 不对 final 构造器注入字段做反射打桩；实现未合入时没有可包装的 Bean，由缺路由
 * 404 与冻结 500 断言形成可解释行为红。所有断言在实现缺失时失败，不使用 @Disabled。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 构造器注入 MockMvc/JdbcTemplate 需要显式开启 JUnit5 构造器自动装配，否则报 ParameterResolution
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CategoryTest {

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

    private static final Logger log = LoggerFactory.getLogger(CategoryTest.class);

    private static final String U = "c06-u";
    private static final String O = "c06-o";

    /** 分类标准夹具 C1/C2/C3：pcid 依次 c06-c1/c06-c2/c06-c3，sort 依次 1/1/2。 */
    private static final String C1 = "c06-c1";
    private static final String C2 = "c06-c2";
    private static final String C3 = "c06-c3";

    /** C1 两条展示作品。 */
    private static final String W1 = "c06-w1";
    private static final String W2 = "c06-w2";
    /** C1 三种非展示作品各一条。 */
    private static final String PEND = "c06-pend";
    private static final String HIDE = "c06-hide";
    private static final String REJ = "c06-rej";
    /** C2 唯一展示作品。 */
    private static final String W3 = "c06-w3";
    /** pcid=NULL 的历史作品，旧 pcatogory 关联与新分类故意不一致。 */
    private static final String H1 = "c06-h1";

    /** T06-03 读前后逐行一致的快照表集合。 */
    private static final String[] SNAPSHOT_TABLES = {"pclist", "pwork", "pcatogory", "favorite", "work_exif"};

    /** T06-54 故障开关；开启时分类查询 Mapper 替身抛出未知 SQL 故障。 */
    static final AtomicBoolean CATEGORY_QUERY_FAULT = new AtomicBoolean(false);
    /** T06-54 注入故障的独有标记，响应中出现即视为泄露底层异常。 */
    private static final String FAULT_MARKER = "card06-injected-unknown-sql-fault";

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
    CategoryTest(MockMvc mvc, JdbcTemplate jdbc, JwtUtils jwtUtils, ObjectMapper objectMapper) {
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
        seedUser(U, "c06用户U", "普通用户", "19900000101");
        seedUser(O, "c06用户O", "普通用户", "19900000102");
    }

    /**
     * 每个用例结束后按外键顺序清理本类夹具，失败中断也不把脏数据留给后续用例。
     */
    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM liketable WHERE lid LIKE 'c06-%' OR targetid LIKE 'c06-%' OR uid LIKE 'c06-%'");
        jdbc.update("DELETE FROM favorite WHERE fid LIKE 'c06-%' OR pid LIKE 'c06-%' OR uid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pcatogory WHERE pcid LIKE 'c06-%' OR pid LIKE 'c06-%' OR uid LIKE 'c06-%'");
        jdbc.update("DELETE FROM work_exif WHERE pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pwork WHERE pid LIKE 'c06-%'");
        jdbc.update("DELETE FROM pclist WHERE pcid LIKE 'c06-%'");
        jdbc.update("DELETE FROM user WHERE uid LIKE 'c06-%'");
    }

    // ------------------------------------------------------------------
    // 冻结断言用例
    // ------------------------------------------------------------------

    /** T06-01 匿名读分类列表：sort 升序、同 sort 按 pcid 升序；count 只算展示作品；条目键集合精确，无 total/cds/sort/ct。 */
    @Test
    void cat_01_listStandardOrderAndExactCounts() throws Exception {
        seedStandardFixture();
        JsonNode data = assertCategoryListOk();
        assertStandardCategoryList(data);
    }

    /** T06-02 隔离夹具中 pclist 为空、旧 pcatogory 有记录时返回 list:[]，不从旧关系表补造分类。 */
    @Test
    void cat_02_emptyDictionaryWithLegacyRowsIsEmptyList() throws Exception {
        seedWork(H1, U, "展示", null);
        seedLegacyCategory("c06-old-1", H1, U, "人像", 5);
        JsonNode data = assertCategoryListOk();
        assertKeys(data, "list");
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(0);
    }

    /** T06-03 同一展示作品多条收藏与真实帖子赞不扇出分类计数；读前后 pclist/pwork/pcatogory/favorite/work_exif 逐行相同。 */
    @Test
    void cat_03_joinFanoutAndReadOnlySnapshot() throws Exception {
        seedStandardFixture();
        seedFavorite("c06-f1", U, W1);
        seedFavorite("c06-f2", O, W1);
        seedFavorite("c06-f3", U, W2);
        seedPostLike("c06-l1", U, W1, "帖子");
        seedPostLike("c06-l2", O, W1, "帖子");
        seedPostLike("c06-l3", U, "c06-cmt1", "评论");
        seedPostLike("c06-l4", O, W2, "帖子");
        seedExif(W1, 50, "4.0");
        Map<String, List<String>> before = snapshotTables();
        JsonNode data = assertCategoryListOk();
        assertStandardCategoryList(data);
        Map<String, List<String>> after = snapshotTables();
        for (String table : SNAPSHOT_TABLES) {
            assertThat(after.get(table)).as("表 %s 读前后逐行一致", table).isEqualTo(before.get(table));
        }
    }

    /** T06-52 分类分支：匿名可读不能忽略主动携带的坏凭证，坏签名与过期 JWT 逐例 401 unauthorized。 */
    @Test
    void cat_52_badCredentialsOnPublicReadIs401() throws Exception {
        seedStandardFixture();
        String badSignature = "Bearer " + corrupt(jwtUtils.generateToken(U, "普通用户"));
        String expired = "Bearer " + expiredToken(U, "普通用户");
        for (String authorization : new String[]{badSignature, expired}) {
            assertError(mvc.perform(get("/category/list")
                    .header("Authorization", authorization)).andReturn(), 401, "unauthorized");
        }
    }

    /** T06-54 对分类查询注入未知 SQL 故障返回 500 internal_error 安全信封，不吐 SQL/底层异常、不 200 吞错、不 409。 */
    @Test
    void cat_54_unknownSqlFaultIsInternalErrorEnvelope() throws Exception {
        seedStandardFixture();
        CATEGORY_QUERY_FAULT.set(true);
        MvcResult result;
        try {
            result = mvc.perform(get("/category/list")).andReturn();
        } finally {
            CATEGORY_QUERY_FAULT.set(false);
        }
        assertError(result, 500, "internal_error");
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(FAULT_MARKER, "SQL", "sql", "Exception", "\tat ");
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助（全部在本类内部）
    // ------------------------------------------------------------------

    /** 标准分类夹具：同 sort 的 C2 先落库，冻结口径是同 sort 按 pcid 升序，不能依赖插入顺序。 */
    private void seedStandardFixture() {
        seedCategory(C2, "风光", 1);
        seedCategory(C1, "人像", 1);
        seedCategory(C3, "静物", 2);
        seedWork(W1, U, "展示", C1);
        seedWork(W2, U, "展示", C1);
        seedWork(PEND, U, "待审核", C1);
        seedWork(HIDE, U, "隐藏", C1);
        seedWork(REJ, U, "已驳回", C1);
        seedWork(W3, U, "展示", C2);
        seedWork(H1, U, "展示", null);
        // 旧 pcatogory 关系与新分类故意不一致：同一作品旧名不同、NULL 分类作品挂旧类，均不得参与新字典计数
        seedLegacyCategory("c06-old-1", W1, U, "旧人像", 7);
        seedLegacyCategory("c06-old-2", H1, U, "风光", 3);
    }

    private void seedUser(String uid, String nickname, String role, String pnum) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status) VALUES (?,?,?,?,?,?,?,?)",
                uid, nickname, pnum, role, "c06-test-password", "/avatar/" + uid + ".png", "0", "正常");
    }

    private void seedCategory(String pcid, String pcname, int sort) {
        jdbc.update("INSERT INTO pclist (pcid, pcname, cds, sort) VALUES (?,?,?,?)",
                pcid, pcname, pcname + "测试分类说明", sort);
    }

    private void seedWork(String pid, String authorUid, String status, String pcid) {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, viewcount, votecount, wstatus, likep, pcid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                pid, authorUid, "测试作品" + pid, "测试作品描述", "/static/images/pwork/c06.jpg", 0, 0, status, 0, pcid);
    }

    private void seedLegacyCategory(String pcid, String pid, String uid, String pcname, int count) {
        jdbc.update("INSERT INTO pcatogory (pcid, pid, uid, pcname, cdesc, sort, pc_count) VALUES (?,?,?,?,?,?,?)",
                pcid, pid, uid, pcname, "旧关系描述", "9", count);
    }

    private void seedFavorite(String fid, String uid, String pid) {
        jdbc.update("INSERT IGNORE INTO favorite (fid, uid, pid) VALUES (?,?,?)", fid, uid, pid);
    }

    private void seedPostLike(String lid, String uid, String targetid, String targettype) {
        jdbc.update("INSERT IGNORE INTO liketable (lid, uid, targetid, targettype) VALUES (?,?,?,?)",
                lid, uid, targetid, targettype);
    }

    private void seedExif(String pid, int focal, String aperture) {
        jdbc.update("INSERT INTO work_exif (pid, focal, aperture, stripped) VALUES (?,?,?,?)",
                pid, focal, aperture, 0);
    }

    private JsonNode assertCategoryListOk() throws Exception {
        return assertOk(mvc.perform(get("/category/list")).andReturn());
    }

    private void assertStandardCategoryList(JsonNode data) {
        assertKeys(data, "list");
        JsonNode list = data.get("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(3);
        String[][] expected = {
                {C1, "人像", "2"},
                {C2, "风光", "1"},
                {C3, "静物", "0"}
        };
        for (int i = 0; i < expected.length; i++) {
            JsonNode item = list.get(i);
            assertKeys(item, "pcid", "pcname", "count");
            assertThat(item.get("pcid").asText()).isEqualTo(expected[i][0]);
            assertThat(item.get("pcname").asText()).isEqualTo(expected[i][1]);
            assertThat(item.get("count").isIntegralNumber()).isTrue();
            assertThat(item.get("count").asLong()).isEqualTo(Long.parseLong(expected[i][2]));
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

    /**
     * T06-54 的故障替身配置：运行时按接口名查找分类查询 Mapper 并包一层受控替身。
     * CategoryMapper 属卡06 新类型，基线不能静态引用；实现未合入时没有可包装的 Bean，
     * 由缺路由 404 与冻结 500 断言形成可解释行为红。
     */
    @TestConfiguration
    static class CategoryFaultConfiguration {

        @Bean
        BeanPostProcessor categoryMapperFaultInjector() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    List<Class<?>> mapperInterfaces = new ArrayList<>();
                    for (Class<?> type : bean.getClass().getInterfaces()) {
                        if (isCategoryMapperInterface(type)) {
                            mapperInterfaces.add(type);
                        }
                    }
                    if (mapperInterfaces.isEmpty()) {
                        return bean;
                    }
                    Object target = bean;
                    Class<?>[] interfaces = mapperInterfaces.toArray(new Class<?>[mapperInterfaces.size()]);
                    log.info("已为分类查询 Mapper 安装故障替身 bean={} interfaces={}", beanName, interfaces);
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces,
                            (proxy, method, args) -> {
                                if (CATEGORY_QUERY_FAULT.get()) {
                                    throw new RuntimeException(FAULT_MARKER, new SQLException(FAULT_MARKER));
                                }
                                return method.invoke(target, args);
                            });
                }
            };
        }

        private boolean isCategoryMapperInterface(Class<?> type) {
            String simpleName = type.getSimpleName();
            return simpleName.contains("Mapper") && simpleName.toLowerCase(Locale.ROOT).contains("category");
        }
    }
}
