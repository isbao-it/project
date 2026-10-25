package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
import com.pg.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 卡02｜微信登录与账号契约的冻结测试（T02-01～T02-50 中走真实 Controller/Service/Mapper 的部分）。
 *
 * 口径：数据库固定 db_pg_test_02（测试内 DynamicPropertySource 覆盖）；微信外部调用由测试专用
 * WeChatClient 桩替代（仅当实现卡交付 com.pg.service.WeChatClient 接口后按 @Primary 注册动态代理）；
 * mock 登录通道由测试内配置项 wx.mock-enabled 与 dev profile 控制。每个用例自建夹具并在结束后清理，
 * 不使用共享基座的事务回滚，以保证并发用例各请求走独立连接与事务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wx.mock-enabled=true",
        "wx.appid=card02-test-appid",
        "wx.secret=card02-test-secret"
})
@Import(WxLoginTest.WeChatStubConfiguration.class)
class WxLoginTest {

    /** 每次 JVM 运行内的唯一夹具标记，避免与历史残留行主键冲突。 */
    private static final String TAG = Long.toHexString(System.nanoTime());

    /** 管理员夹具的明文密码，登录用例按此值提交表单。 */
    private static final String ADMIN_PWD = "T02-Admin-Pwd-02";

    /** 普通用户夹具的明文密码。 */
    private static final String NORMAL_PWD = "T02-Normal-Pwd-02";

    /** 与 application-test.yml 相同的测试签名密钥，仅用于在测试内签发过期/特殊角色的 JWT。 */
    private static final String TEST_JWT_SECRET =
            "cGhvdG9haS1jYXJkMDEtdGVzdC1zZWNyZXQtb25seS0yMDI2MTAyMA==";

    /** 微信首登默认头像，冻结为复用既有静态图。 */
    private static final String DEFAULT_AVATAR = "/static/images/avatar/admin.jpg";

    /** 本卡固定测试库；与共享 application-test.yml 的 db_pg_test 区分，避免污染共享基座夹具。 */
    private static final String TEST_DATABASE = "db_pg_test_02";

    /** 夹具创建的 uid，@AfterEach 按主键清理。 */
    private static final Set<String> CREATED_UIDS = ConcurrentHashMap.newKeySet();

    /** 夹具或微信登录创建的 openid，@AfterEach 按唯一键清理。 */
    private static final Set<String> CREATED_OPENIDS = ConcurrentHashMap.newKeySet();

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper mapper;

    /**
     * 用测试内数据源覆盖共享配置，固定连接 db_pg_test_02（root/root 沿用 application-test.yml）。
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void card02TestDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:mysql://localhost:3306/" + TEST_DATABASE
                        + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
    }

    /**
     * 每个用例前复位微信桩与夹具记录；主类用例同时校验库名。
     * 嵌套类用例运行时外层实例未经 Spring 注入，此处对 jdbc 判空跳过，由嵌套类自行校验。
     */
    @BeforeEach
    void prepareCard02Round() {
        WeChatStub.reset();
        CREATED_UIDS.clear();
        CREATED_OPENIDS.clear();
        if (jdbc != null) {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(TEST_DATABASE);
        }
    }

    /**
     * 清理本用例创建的夹具行；嵌套类用例由嵌套类自身的清理方法负责。
     */
    @AfterEach
    void cleanupCard02Round() {
        if (jdbc == null) {
            return;
        }
        cleanupTracked(jdbc);
    }

    // ------------------------------------------------------------------
    // 微信登录：首登、复登、并发、拒绝与错误码（T02-01～T02-07）
    // ------------------------------------------------------------------

    /** T02-01：微信首次登录——新 openid 建号，返回 token/uid/isNew，库内默认资料、pnum/pwd 为 NULL。 */
    @Test
    void wx_01_firstLoginWithNewOpenidCreatesAccount() throws Exception {
        WeChatStub.success("wx-first", "openid-t02-01");
        CREATED_OPENIDS.add("openid-t02-01");
        long before = userCount();
        MvcResult result = mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-first\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ok"))
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(keysOf(json.get("data"))).containsExactlyInAnyOrder("token", "uid", "isNew");
        String rawToken = json.at("/data/token").asText();
        String uid = json.at("/data/uid").asText();
        assertThat(rawToken).isNotBlank();
        assertThat(uid).matches("[A-Za-z0-9_-]{1,64}");
        assertThat(json.at("/data/isNew").asBoolean()).isTrue();
        // JWT 可验签，subject 即返回 uid，角色为普通用户。
        Claims claims = jwtUtils.parseToken(rawToken);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(uid);
        assertThat(claims.get("role")).isEqualTo("普通用户");
        // 库内：默认头像、昵称“微信用户”+服务端序号、pnum/pwd 为 NULL、状态正常。
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT uname, avatar, pnum, pwd, status, role FROM user WHERE uid = ?", uid);
        assertThat((String) row.get("uname")).startsWith("微信用户");
        assertThat(row.get("avatar")).isEqualTo(DEFAULT_AVATAR);
        assertThat(row.get("pnum")).isNull();
        assertThat(row.get("pwd")).isNull();
        assertThat(row.get("status")).isEqualTo("正常");
        assertThat(row.get("role")).isEqualTo("普通用户");
        assertThat(userCount()).isEqualTo(before + 1);
        // 无 openid 出参，响应不泄露 openid/pwd 等敏感键。
        assertThat(result.getResponse().getContentAsString()).doesNotContain("openid-t02-01");
        assertNoSensitiveKeys(json);
        CREATED_UIDS.add(uid);
    }

    /** T02-02：同一 openid 二次登录复用 uid，isNew=false，账号总数不变。 */
    @Test
    void wx_02_secondLoginWithSameOpenidReusesUid() throws Exception {
        WeChatStub.success("wx-second-a", "openid-t02-02");
        WeChatStub.success("wx-second-b", "openid-t02-02");
        CREATED_OPENIDS.add("openid-t02-02");
        MvcResult first = mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-second-a\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String firstUid = mapper.readTree(first.getResponse().getContentAsString()).at("/data/uid").asText();
        CREATED_UIDS.add(firstUid);
        long beforeSecond = userCount();
        MvcResult second = mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-second-b\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, second);
        JsonNode json = mapper.readTree(second.getResponse().getContentAsString());
        assertThat(keysOf(json.get("data"))).containsExactlyInAnyOrder("token", "uid", "isNew");
        assertThat(json.at("/data/uid").asText()).isEqualTo(firstUid);
        assertThat(json.at("/data/isNew").asBoolean()).isFalse();
        assertThat(json.at("/data/token").asText()).isNotBlank();
        assertThat(userCount()).isEqualTo(beforeSecond);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE openid = ?", Long.class, "openid-t02-02"))
                .isEqualTo(1L);
    }

    /** T02-03：同一新 openid 的两个并发首登各 200、uid 相同、只建一行、恰一个 isNew=true；不同 openid 首登序号不重复。 */
    @Test
    void wx_03_concurrentFirstLoginCreatesSingleAccount() throws Exception {
        WeChatStub.success("wx-c1", "openid-t02-03");
        WeChatStub.success("wx-c2", "openid-t02-03");
        CREATED_OPENIDS.add("openid-t02-03");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch fire = new CountDownLatch(1);
            Callable<MvcResult> first = () -> {
                ready.countDown();
                if (!fire.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发栅栏等待超时");
                }
                return mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-c1\"}")).andReturn();
            };
            Callable<MvcResult> second = () -> {
                ready.countDown();
                if (!fire.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发栅栏等待超时");
                }
                return mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-c2\"}")).andReturn();
            };
            Future<MvcResult> firstResult = pool.submit(first);
            Future<MvcResult> secondResult = pool.submit(second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            MvcResult r1 = firstResult.get(30, TimeUnit.SECONDS);
            MvcResult r2 = secondResult.get(30, TimeUnit.SECONDS);
            assertThat(r1.getResponse().getStatus()).isEqualTo(200);
            assertThat(r2.getResponse().getStatus()).isEqualTo(200);
            JsonNode j1 = mapper.readTree(r1.getResponse().getContentAsString());
            JsonNode j2 = mapper.readTree(r2.getResponse().getContentAsString());
            assertThat(j1.at("/code").asText()).isEqualTo("ok");
            assertThat(j2.at("/code").asText()).isEqualTo("ok");
            String uid1 = j1.at("/data/uid").asText();
            String uid2 = j2.at("/data/uid").asText();
            assertThat(uid1).matches("[A-Za-z0-9_-]{1,64}");
            assertThat(uid2).isEqualTo(uid1);
            CREATED_UIDS.add(uid1);
            assertThat(j1.at("/data/token").asText()).isNotBlank();
            assertThat(j2.at("/data/token").asText()).isNotBlank();
            boolean new1 = j1.at("/data/isNew").asBoolean();
            boolean new2 = j2.at("/data/isNew").asBoolean();
            assertThat(new1 != new2).as("两次并发登录恰一次 isNew=true").isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE openid = ?", Long.class, "openid-t02-03"))
                    .isEqualTo(1L);
            // 另一个 openid 的首登得到不同的“微信用户”序号。
            WeChatStub.success("wx-c3", "openid-t02-03b");
            CREATED_OPENIDS.add("openid-t02-03b");
            MvcResult third = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"wx-c3\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode j3 = mapper.readTree(third.getResponse().getContentAsString());
            String uid3 = j3.at("/data/uid").asText();
            CREATED_UIDS.add(uid3);
            String uname1 = jdbc.queryForObject("SELECT uname FROM user WHERE uid = ?", String.class, uid1);
            String uname3 = jdbc.queryForObject("SELECT uname FROM user WHERE uid = ?", String.class, uid3);
            assertThat(uname1).startsWith("微信用户");
            assertThat(uname3).startsWith("微信用户");
            assertThat(uname3).isNotEqualTo(uname1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** T02-04：微信码无效/已用（桩拒绝）——401 unauthorized，不建账号、不返回 token。 */
    @Test
    void wx_04_invalidOrUsedCodeIsUnauthorized() throws Exception {
        String[] codes = {"invalid", "used"};
        for (String code : codes) {
            WeChatStub.reject(code);
        }
        long before = userCount();
        for (String code : codes) {
            MvcResult result = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 401, "unauthorized");
        }
        assertThat(userCount()).isEqualTo(before);
    }

    /** T02-05：微信侧超时/不可用两例——503 service_unavailable，不建号、不降级 Mock。 */
    @Test
    void wx_05_timeoutOrUnavailableIsServiceUnavailable() throws Exception {
        WeChatStub.timeout("wx-timeout");
        WeChatStub.unavailable("wx-down");
        long before = userCount();
        for (String code : new String[]{"wx-timeout", "wx-down"}) {
            MvcResult result = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 503, "service_unavailable");
        }
        assertThat(userCount()).isEqualTo(before);
    }

    /** T02-06：body 缺 code、null、空串、纯空白、数值——逐例 400 invalid_request。 */
    @Test
    void wx_06_malformedBodyIsInvalidRequest() throws Exception {
        String[] bodies = {"{}", "{\"code\":null}", "{\"code\":\"\"}", "{\"code\":\"  \"}", "{\"code\":1}"};
        for (String body : bodies) {
            MvcResult result = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 400, "invalid_request");
        }
    }

    /** T02-07：openid 命中禁用用户——401 unauthorized，只返回信封三字段。 */
    @Test
    void wx_07_disabledAccountCodeIsUnauthorized() throws Exception {
        String uid = uidOf("DIS7");
        seedUser(jdbc, uid, "T02禁用七", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "禁用", "/a.png",
                "openid-t02-07", null);
        WeChatStub.success("wx-disabled", "openid-t02-07");
        long before = userCount();
        MvcResult result = mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"wx-disabled\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, result, 401, "unauthorized");
        assertThat(userCount()).isEqualTo(before);
    }

    /** T02-09(a)：非 dev profile（本主上下文）+ mock 开关开启——mock: 前缀不得生成 Mock 身份。 */
    @Test
    void wx_09a_mockPrefixOutsideDevProfileIsRejected() throws Exception {
        WeChatStub.reject("mock:x");
        long before = userCount();
        MvcResult result = mvc.perform(post("/auth/wxlogin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"mock:x\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, result, 401, "unauthorized");
        assertThat(userCount()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // 本人资料与资料更新（T02-10～T02-15、T02-49）
    // ------------------------------------------------------------------

    /** T02-10：/user/info 返回 SelfUserCompat 十字键；nickname=uname；普通 pnum 为 138****0005；非 11 位为 ****。 */
    @Test
    void wx_10_selfInfoReturnsTenCompatFields() throws Exception {
        String u = uidOf("U10");
        String u2 = uidOf("U2A");
        String uname = "T02十键昵称";
        seedUser(jdbc, u, uname, "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        seedUser(jdbc, u2, "T02短号码", "12345", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/b.png", null, null);
        MvcResult result = mvc.perform(get("/user/info").header("Authorization", tokenOf(jwtUtils, u, "普通用户")))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder(
                "uid", "nickname", "avatar", "bio", "role", "uname", "pnum", "vote", "uct", "status");
        assertThat(data.at("/uid").asText()).isEqualTo(u);
        assertThat(data.at("/nickname").asText()).isEqualTo(uname);
        assertThat(data.at("/uname").asText()).isEqualTo(uname);
        assertThat(data.at("/avatar").asText()).isEqualTo("/a.png");
        assertThat(data.at("/bio").isNull()).isTrue();
        assertThat(data.at("/role").asText()).isEqualTo("普通用户");
        assertThat(data.at("/pnum").asText()).isEqualTo("138****0005");
        assertThat(data.at("/vote").isTextual()).isTrue();
        assertThat(data.at("/uct").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(data.at("/status").asText()).isEqualTo("正常");
        // 另例：非 11 位手机号显示 ****。
        MvcResult shortResult = mvc.perform(get("/user/info").header("Authorization", tokenOf(jwtUtils, u2, "普通用户")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode shortData = mapper.readTree(shortResult.getResponse().getContentAsString()).get("data");
        assertThat(shortData.at("/pnum").asText()).isEqualTo("****");
    }

    /** T02-10 另例（依赖 V003）：pnum 为 NULL 时保持 NULL 且键仍输出。 */
    @Test
    void wx_10b_selfInfoKeepsNullPnumKey() throws Exception {
        String u = uidOf("U10B");
        seedUser(jdbc, u, "T02空号码", null, "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(get("/user/info").header("Authorization", tokenOf(jwtUtils, u, "普通用户")))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).contains("pnum");
        assertThat(data.at("/pnum").isNull()).isTrue();
    }

    /** T02-11：/user/profile 白名单只写 nickname/avatar/bio；保留首尾空格与空 bio；其余字段忽略且库值不变。 */
    @Test
    void wx_11_profileUpdateWhitelistIgnoresJunkFields() throws Exception {
        String u = uidOf("U11");
        String o = uidOf("O11");
        seedUser(jdbc, u, "旧昵称十一", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/old.png", null, "旧简介");
        seedUser(jdbc, o, "T02他人十一", "13812340006", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/o.png", null, null);
        String pwdBefore = jdbc.queryForObject("SELECT pwd FROM user WHERE uid = ?", String.class, u);
        String body = "{\"nickname\":\" 新昵称 \",\"avatar\":\"/a.png\",\"bio\":\"\",\"uid\":\"" + o
                + "\",\"role\":\"管理员\",\"vote\":9999,\"status\":\"禁用\",\"pwd\":\"x\",\"openid\":\"x\","
                + "\"pnum\":\"19900000000\",\"uname\":\"注入\"}";
        MvcResult result = mvc.perform(put("/user/profile")
                        .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder("uid", "nickname", "avatar", "bio", "role");
        assertThat(data.at("/uid").asText()).isEqualTo(u);
        assertThat(data.at("/nickname").asText()).isEqualTo(" 新昵称 ");
        assertThat(data.at("/avatar").asText()).isEqualTo("/a.png");
        assertThat(data.at("/bio").asText()).isEqualTo("");
        assertThat(data.at("/role").asText()).isEqualTo("普通用户");
        // 本人 uname 保留首尾空格、bio 空串落库；非白名单字段库值不变；O 完全不变。
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT uname, avatar, bio, pnum, role, vote, status, pwd, openid FROM user WHERE uid = ?", u);
        assertThat(row.get("uname")).isEqualTo(" 新昵称 ");
        assertThat(row.get("avatar")).isEqualTo("/a.png");
        assertThat(row.get("bio")).isEqualTo("");
        assertThat(row.get("pnum")).isEqualTo("13812340005");
        assertThat(row.get("role")).isEqualTo("普通用户");
        assertThat(((Number) row.get("vote")).longValue()).isZero();
        assertThat(row.get("status")).isEqualTo("正常");
        assertThat(row.get("pwd")).isEqualTo(pwdBefore);
        assertThat(row.get("openid")).isNull();
        Map<String, Object> other = jdbc.queryForMap(
                "SELECT uname, pnum, role, vote, status, avatar, pwd, openid, bio FROM user WHERE uid = ?", o);
        assertThat(other.get("uname")).isEqualTo("T02他人十一");
        assertThat(other.get("pnum")).isEqualTo("13812340006");
        assertThat(other.get("role")).isEqualTo("普通用户");
        assertThat(((Number) other.get("vote")).longValue()).isZero();
        assertThat(other.get("status")).isEqualTo("正常");
        assertThat(other.get("avatar")).isEqualTo("/o.png");
        assertThat(other.get("bio")).isNull();
    }

    /** T02-12：空 body 与仅 bio 两例——未传白名单字段原值不变，bio 允许空串与 NULL 并仍输出键。 */
    @Test
    void wx_12_profileEmptyBodyKeepsValues() throws Exception {
        String u = uidOf("U12");
        seedUser(jdbc, u, "旧昵称十二", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/old.png", null, null);
        MvcResult empty = mvc.perform(put("/user/profile")
                        .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, empty);
        JsonNode emptyData = mapper.readTree(empty.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(emptyData)).containsExactlyInAnyOrder("uid", "nickname", "avatar", "bio", "role");
        assertThat(emptyData.at("/nickname").asText()).isEqualTo("旧昵称十二");
        assertThat(emptyData.at("/avatar").asText()).isEqualTo("/old.png");
        assertThat(emptyData.at("/bio").isNull()).isTrue();
        Map<String, Object> untouched = jdbc.queryForMap("SELECT uname, avatar, bio FROM user WHERE uid = ?", u);
        assertThat(untouched.get("uname")).isEqualTo("旧昵称十二");
        assertThat(untouched.get("avatar")).isEqualTo("/old.png");
        assertThat(untouched.get("bio")).isNull();
        MvcResult bioOnly = mvc.perform(put("/user/profile")
                        .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"简介\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode bioData = mapper.readTree(bioOnly.getResponse().getContentAsString()).get("data");
        assertThat(bioData.at("/bio").asText()).isEqualTo("简介");
        assertThat(bioData.at("/nickname").asText()).isEqualTo("旧昵称十二");
        assertThat(bioData.at("/avatar").asText()).isEqualTo("/old.png");
        Map<String, Object> after = jdbc.queryForMap("SELECT uname, avatar, bio FROM user WHERE uid = ?", u);
        assertThat(after.get("bio")).isEqualTo("简介");
        assertThat(after.get("uname")).isEqualTo("旧昵称十二");
        assertThat(after.get("avatar")).isEqualTo("/old.png");
    }

    /** T02-12 另例（依赖 V003）：已有 avatar/bio 均为 NULL 时仍输出键。 */
    @Test
    void wx_12b_profileNullAvatarBioKeysStayPresent() throws Exception {
        String u = uidOf("U12B");
        seedUser(jdbc, u, "旧昵称十二乙", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", null, null, null);
        MvcResult result = mvc.perform(put("/user/profile")
                        .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder("uid", "nickname", "avatar", "bio", "role");
        assertThat(data.at("/avatar").isNull()).isTrue();
        assertThat(data.at("/bio").isNull()).isTrue();
    }

    /** T02-13：边界长度成功——nickname 50 字符（含补充平面）、avatar 255、bio 255。 */
    @Test
    void wx_13_profileBoundaryLengthsSucceed() throws Exception {
        String u = uidOf("U13");
        seedUser(jdbc, u, "旧昵称十三", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/old.png", null, null);
        String nickname = repeat("界", 49) + "\uD83D\uDE00";
        assertThat(nickname.codePointCount(0, nickname.length())).isEqualTo(50);
        String avatar = repeat("a", 255);
        String bio = repeat("b", 255);
        MvcResult result = mvc.perform(put("/user/profile")
                        .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"avatar\":\"" + avatar + "\",\"bio\":\""
                                + bio + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.at("/nickname").asText()).isEqualTo(nickname);
        assertThat(data.at("/avatar").asText()).isEqualTo(avatar);
        assertThat(data.at("/bio").asText()).isEqualTo(bio);
        assertThat(jdbc.queryForObject("SELECT uname FROM user WHERE uid = ?", String.class, u))
                .isEqualTo(nickname);
    }

    /** T02-14：nickname 51 字符——422 invalid_profile，只返回信封三字段。 */
    @Test
    void wx_14_profileNicknameOver50IsInvalidProfile() throws Exception {
        String u = uidOf("U14");
        seedUser(jdbc, u, "旧昵称十四", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/old.png", null, null);
        MvcResult result = mvc.perform(put("/user/profile")
                        .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + repeat("长", 51) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertErrorEnvelope(mapper, result, 422, "invalid_profile");
    }

    /** T02-15：类型/长度/空白十例——逐例 400 invalid_request 且无任何字段部分更新。 */
    @Test
    void wx_15_profileTypeAndLengthViolationsAreInvalidRequest() throws Exception {
        String u = uidOf("U15");
        seedUser(jdbc, u, "旧昵称十五", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/old.png", null, "旧简介十五");
        String[] bodies = {
                "{\"nickname\":null}",
                "{\"nickname\":\" \"}",
                "{\"nickname\":1}",
                "{\"avatar\":null}",
                "{\"avatar\":\" \"}",
                "{\"avatar\":1}",
                "{\"avatar\":\"" + repeat("a", 256) + "\"}",
                "{\"bio\":null}",
                "{\"bio\":1}",
                "{\"bio\":\"" + repeat("b", 256) + "\"}"
        };
        for (String body : bodies) {
            MvcResult result = mvc.perform(put("/user/profile")
                            .header("Authorization", tokenOf(jwtUtils, u, "普通用户"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 400, "invalid_request");
            Map<String, Object> row = jdbc.queryForMap("SELECT uname, avatar, bio FROM user WHERE uid = ?", u);
            assertThat(row.get("uname")).isEqualTo("旧昵称十五");
            assertThat(row.get("avatar")).isEqualTo("/old.png");
            assertThat(row.get("bio")).isEqualTo("旧简介十五");
        }
    }

    /** T02-49：未登录 PUT /user/profile——401 unauthorized，资料不更新。 */
    @Test
    void wx_49_anonymousProfileUpdateIsUnauthorized() throws Exception {
        String u = uidOf("U49");
        seedUser(jdbc, u, "旧昵称四九", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/old.png", null, null);
        MvcResult result = mvc.perform(put("/user/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\",\"avatar\":\"/a.png\",\"bio\":\"简介\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, result, 401, "unauthorized");
        Map<String, Object> row = jdbc.queryForMap("SELECT uname, avatar, bio FROM user WHERE uid = ?", u);
        assertThat(row.get("uname")).isEqualTo("旧昵称四九");
        assertThat(row.get("avatar")).isEqualTo("/old.png");
        assertThat(row.get("bio")).isNull();
    }

    // ------------------------------------------------------------------
    // 旧资料查询 /user/{uid}（T02-16～T02-20）
    // ------------------------------------------------------------------

    /** T02-16：本人 /user/U 返回 LegacyUser 八键，uid=U、pnum=138****0005、uct 旧格式。 */
    @Test
    void wx_16_selfLegacyProfileHasExactlyEightKeys() throws Exception {
        String u = uidOf("U16");
        seedUser(jdbc, u, "T02旧资料十六", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(get("/user/" + u).header("Authorization", tokenOf(jwtUtils, u, "普通用户")))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder("uid", "uname", "pnum", "role", "avatar", "vote", "uct", "status");
        assertThat(data.at("/uid").asText()).isEqualTo(u);
        assertThat(data.at("/uname").asText()).isEqualTo("T02旧资料十六");
        assertThat(data.at("/pnum").asText()).isEqualTo("138****0005");
        assertThat(data.at("/role").asText()).isEqualTo("普通用户");
        assertThat(data.at("/uct").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(data.at("/status").asText()).isEqualTo("正常");
    }

    /** T02-17：普通用户查他人 /user/O——403 forbidden。 */
    @Test
    void wx_17_normalUserCannotReadOtherProfile() throws Exception {
        String u = uidOf("U17");
        String o = uidOf("O17");
        seedUser(jdbc, u, "T02本人十七", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        seedUser(jdbc, o, "T02他人十七", "13812340006", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/b.png", null, null);
        MvcResult result = mvc.perform(get("/user/" + o).header("Authorization", tokenOf(jwtUtils, u, "普通用户")))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(mapper, result, 403, "forbidden");
    }

    /** T02-18：管理员查他人 /user/O——200，uid=O、pnum 为完整测试号码，无 nickname/bio/统计键。 */
    @Test
    void wx_18_adminReadsOtherProfileFullNumber() throws Exception {
        String a = uidOf("A18");
        String o = uidOf("O18");
        seedUser(jdbc, a, "T02管理员十八", "19900000003", "管理员", ENCODER.encode(ADMIN_PWD), "正常", "/a.png", null, null);
        seedUser(jdbc, o, "T02他人十八", "19900000002", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/b.png", null, null);
        MvcResult result = mvc.perform(get("/user/" + o).header("Authorization", tokenOf(jwtUtils, a, "管理员")))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder("uid", "uname", "pnum", "role", "avatar", "vote", "uct", "status");
        assertThat(data.at("/uid").asText()).isEqualTo(o);
        assertThat(data.at("/pnum").asText()).isEqualTo("19900000002");
    }

    /** T02-19：管理员查格式合法但不存在的 uid——404 not_found。 */
    @Test
    void wx_19_adminReadsMissingUidNotFound() throws Exception {
        String a = uidOf("A19");
        seedUser(jdbc, a, "T02管理员十九", "19900000003", "管理员", ENCODER.encode(ADMIN_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(get("/user/missing").header("Authorization", tokenOf(jwtUtils, a, "管理员")))
                .andExpect(status().isNotFound())
                .andReturn();
        assertErrorEnvelope(mapper, result, 404, "not_found");
    }

    /** T02-20：uid 格式非法（bad%21）——400 invalid_request。 */
    @Test
    void wx_20_badUidFormatIsInvalidRequest() throws Exception {
        String a = uidOf("A20");
        seedUser(jdbc, a, "T02管理员二十", "19900000003", "管理员", ENCODER.encode(ADMIN_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(get(java.net.URI.create("/user/bad%21"))
                        .header("Authorization", tokenOf(jwtUtils, a, "管理员")))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(mapper, result, 400, "invalid_request");
    }

    // ------------------------------------------------------------------
    // 凭证来源、主体状态与角色判定（T02-25～T02-30）
    // ------------------------------------------------------------------

    /** T02-25：坏 Authorization、过期 JWT、空值头三例——401，坏 Authorization 不能回退 Cookie。 */
    @Test
    void wx_25_badAuthorizationHeaderNeverFallsBackToCookie() throws Exception {
        String u = uidOf("U25");
        seedUser(jdbc, u, "T02回退二五", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        Cookie tokenCookie = new Cookie("token", jwtUtils.generateToken(u, "普通用户"));
        MvcResult bad = mvc.perform(get("/user/info").header("Authorization", "Bearer bad").cookie(tokenCookie))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, bad, 401, "unauthorized");
        MvcResult expired = mvc.perform(get("/user/info")
                        .header("Authorization", "Bearer " + expiredToken(u, "普通用户")).cookie(tokenCookie))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, expired, 401, "unauthorized");
        MvcResult empty = mvc.perform(get("/user/info").header("Authorization", "").cookie(tokenCookie))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, empty, 401, "unauthorized");
    }

    /** T02-26：仅无 Authorization 时回退 Cookie——有效 token Cookie 可取本人资料。 */
    @Test
    void wx_26_cookieTokenWorksWithoutAuthorization() throws Exception {
        String u = uidOf("U26");
        seedUser(jdbc, u, "T02饼干二六", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(get("/user/info").cookie(new Cookie("token", jwtUtils.generateToken(u, "普通用户"))))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder(
                "uid", "nickname", "avatar", "bio", "role", "uname", "pnum", "vote", "uct", "status");
        assertThat(data.at("/uid").asText()).isEqualTo(u);
        assertThat(data.at("/pnum").asText()).isEqualTo("138****0005");
        assertThat(data.at("/role").asText()).isEqualTo("普通用户");
        assertThat(data.at("/status").asText()).isEqualTo("正常");
    }

    /** T02-27：签发后删除主体/禁用主体两例——401，每次重查主体不缓存放过。 */
    @Test
    void wx_27_subjectRecheckedAfterDeleteOrDisable() throws Exception {
        String deleted = uidOf("U27A");
        seedUser(jdbc, deleted, "T02删除二七", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        String deletedToken = tokenOf(jwtUtils, deleted, "普通用户");
        jdbc.update("DELETE FROM user WHERE uid = ?", deleted);
        MvcResult deletedResult = mvc.perform(get("/user/info").header("Authorization", deletedToken))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, deletedResult, 401, "unauthorized");
        String disabled = uidOf("U27B");
        seedUser(jdbc, disabled, "T02禁用二七", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        String disabledToken = tokenOf(jwtUtils, disabled, "普通用户");
        jdbc.update("UPDATE user SET status = '禁用' WHERE uid = ?", disabled);
        MvcResult disabledResult = mvc.perform(get("/user/info").header("Authorization", disabledToken))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, disabledResult, 401, "unauthorized");
    }

    /** T02-28：已签名角色为 null/空串/未知/带空格四例——403 forbidden。 */
    @Test
    void wx_28_invalidSignedRolesAreForbidden() throws Exception {
        String u = uidOf("U28");
        seedUser(jdbc, u, "T02角色二八", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        String[] tokens = {
                customRoleToken(u, null),
                customRoleToken(u, ""),
                customRoleToken(u, "未知"),
                customRoleToken(u, " 管理员 ")
        };
        for (String raw : tokens) {
            MvcResult result = mvc.perform(get("/user/info").header("Authorization", "Bearer " + raw))
                    .andExpect(status().isForbidden())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 403, "forbidden");
        }
    }

    /** T02-29：普通用户 JWT 签发后数据库角色改管理员——仍以 JWT claim 授权，403。 */
    @Test
    void wx_29_roleChangeRequiresNewToken() throws Exception {
        String u = uidOf("U29");
        String o = uidOf("O29");
        seedUser(jdbc, u, "T02升职二九", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        seedUser(jdbc, o, "T02他人二九", "13812340006", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/b.png", null, null);
        String token = tokenOf(jwtUtils, u, "普通用户");
        jdbc.update("UPDATE user SET role = '管理员' WHERE uid = ?", u);
        MvcResult result = mvc.perform(get("/user/" + o).header("Authorization", token))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(mapper, result, 403, "forbidden");
    }

    /** T02-30：普通用户访问管理列表的四个路径变体——逐例 403，保留注解与路径规范化。 */
    @Test
    void wx_30_adminListPathsStayForbiddenForNormalUser() throws Exception {
        String u = uidOf("U30");
        seedUser(jdbc, u, "T02列表三十", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        String token = tokenOf(jwtUtils, u, "普通用户");
        String[] paths = {"/user/list", "/user/%6cist", "/user/list;x=1", "/user//list"};
        for (String path : paths) {
            MvcResult result = mvc.perform(get(java.net.URI.create(path)).header("Authorization", token))
                    .andExpect(status().isForbidden())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 403, "forbidden");
        }
    }

    // ------------------------------------------------------------------
    // 后台表单登录 /user/login（T02-31～T02-34）与错误方法（T02-35）
    // ------------------------------------------------------------------

    /** T02-31：管理员表单登录成功——200，data 恰为 token/role/uname/uid，role=管理员。 */
    @Test
    void wx_31_adminFormLoginSuccess() throws Exception {
        String a = uidOf("A31");
        String uname = "T02管理员三一";
        seedUser(jdbc, a, uname, "19900000003", "管理员", ENCODER.encode(ADMIN_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000003")
                        .param("pwd", ADMIN_PWD))
                .andExpect(status().isOk())
                .andReturn();
        assertOkEnvelope(mapper, result);
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(keysOf(json.get("data"))).containsExactlyInAnyOrder("token", "role", "uname", "uid");
        assertThat(json.at("/data/role").asText()).isEqualTo("管理员");
        assertThat(json.at("/data/uname").asText()).isEqualTo(uname);
        assertThat(json.at("/data/uid").asText()).isEqualTo(a);
        Claims claims = jwtUtils.parseToken(json.at("/data/token").asText());
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(a);
        assertThat(claims.get("role")).isEqualTo("管理员");
        assertNoSensitiveKeys(json);
    }

    /** T02-32：有效普通账号不可登录后台——403 forbidden。 */
    @Test
    void wx_32_normalAccountCannotLoginBackend() throws Exception {
        String u = uidOf("U32");
        seedUser(jdbc, u, "T02普通三二", "19900000001", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000001")
                        .param("pwd", NORMAL_PWD))
                .andExpect(status().isForbidden())
                .andReturn();
        assertErrorEnvelope(mapper, result, 403, "forbidden");
    }

    /** T02-33：号码不存在/禁用管理员/密码错误三例——401 unauthorized。 */
    @Test
    void wx_33_loginFailuresAreUnauthorized() throws Exception {
        String disabledAdmin = uidOf("DA33");
        seedUser(jdbc, disabledAdmin, "T02禁用管理三三", "19900000006", "管理员", ENCODER.encode(ADMIN_PWD), "禁用",
                "/a.png", null, null);
        String a = uidOf("A33");
        seedUser(jdbc, a, "T02管理员三三", "19900000003", "管理员", ENCODER.encode(ADMIN_PWD), "正常", "/a.png", null, null);
        MvcResult missing = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000009")
                        .param("pwd", NORMAL_PWD))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, missing, 401, "unauthorized");
        MvcResult disabled = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000006")
                        .param("pwd", ADMIN_PWD))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, disabled, 401, "unauthorized");
        MvcResult wrongPwd = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000003")
                        .param("pwd", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertErrorEnvelope(mapper, wrongPwd, 401, "unauthorized");
    }

    /** T02-34：缺 pnum/非法 pnum/缺 pwd/空 pwd 四例——400 invalid_request。 */
    @Test
    void wx_34_loginParameterViolationsAreInvalidRequest() throws Exception {
        MvcResult noPnum = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pwd", "x"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(mapper, noPnum, 400, "invalid_request");
        MvcResult shortPnum = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "123")
                        .param("pwd", "x"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(mapper, shortPnum, 400, "invalid_request");
        MvcResult noPwd = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000003"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(mapper, noPwd, 400, "invalid_request");
        MvcResult blankPwd = mvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pnum", "19900000003")
                        .param("pwd", ""))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertErrorEnvelope(mapper, blankPwd, 400, "invalid_request");
    }

    /** T02-35：GET /auth/wxlogin——405 invalid_request，错误方法不是 200 或数值 code。 */
    @Test
    void wx_35_wrongMethodOnWxloginIs405() throws Exception {
        String a = uidOf("A35");
        seedUser(jdbc, a, "T02管理员三五", "19900000003", "管理员", ENCODER.encode(ADMIN_PWD), "正常", "/a.png", null, null);
        MvcResult result = mvc.perform(get("/auth/wxlogin").header("Authorization", tokenOf(jwtUtils, a, "管理员")))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();
        assertErrorEnvelope(mapper, result, 405, "invalid_request");
    }

    // ------------------------------------------------------------------
    // dev profile 与 mock 通道（T02-08、T02-09(b)(c)）
    // ------------------------------------------------------------------

    /** dev profile + mock 开关开启：卡02 T02-08、T02-09(c)。 */
    @Nested
    @ActiveProfiles({"test", "dev"})
    class DevMockEnabledTests {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private JdbcTemplate jdbc;

        @Autowired
        private ObjectMapper mapper;

        /** 嵌套类用例自行校验库名；外层夹具状态复位已由外层 @BeforeEach 完成。 */
        @BeforeEach
        void assertCard02Database() {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(TEST_DATABASE);
        }

        /** 嵌套类用例清理本类创建的夹具行。 */
        @AfterEach
        void cleanupCard02Round() {
            cleanupTracked(jdbc);
        }

        /** T02-08：dev+开关+mock: 前缀——生成 Mock 身份 200，不调用真实微信客户端。 */
        @Test
        void wx_08_devMockChannelCreatesIdentityWithoutCallingRealClient() throws Exception {
            long before = userCount(jdbc);
            MvcResult result = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"mock:case-a\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertOkEnvelope(mapper, result);
            JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
            assertThat(keysOf(json.get("data"))).containsExactlyInAnyOrder("token", "uid", "isNew");
            assertThat(json.at("/data/isNew").asBoolean()).isTrue();
            assertThat(json.at("/data/token").asText()).isNotBlank();
            String uid = json.at("/data/uid").asText();
            assertThat(uid).matches("[A-Za-z0-9_-]{1,64}");
            assertThat(WeChatStub.calls()).isZero();
            CREATED_UIDS.add(uid);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE uid = ?", Long.class, uid)).isEqualTo(1L);
            assertThat(userCount(jdbc)).isEqualTo(before + 1);
        }

        /** T02-09(c)：dev+开关开启+非 mock: 前缀——走真实通道，桩拒绝，不生成 Mock 身份。 */
        @Test
        void wx_09c_realChannelFailureDoesNotCreateMockIdentity() throws Exception {
            WeChatStub.reject("wx-real-fail");
            long before = userCount(jdbc);
            MvcResult result = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"wx-real-fail\"}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 401, "unauthorized");
            assertThat(userCount(jdbc)).isEqualTo(before);
        }
    }

    /** dev profile 但 mock 开关关闭：卡02 T02-09(b)。 */
    @Nested
    @ActiveProfiles({"test", "dev"})
    @TestPropertySource(properties = "wx.mock-enabled=false")
    class DevMockDisabledTests {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private JdbcTemplate jdbc;

        @Autowired
        private ObjectMapper mapper;

        /** 嵌套类用例自行校验库名。 */
        @BeforeEach
        void assertCard02Database() {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(TEST_DATABASE);
        }

        /** 嵌套类用例清理本类创建的夹具行。 */
        @AfterEach
        void cleanupCard02Round() {
            cleanupTracked(jdbc);
        }

        /** T02-09(b)：dev profile 但 mock 开关关闭——mock: 前缀不得生成 Mock 身份。 */
        @Test
        void wx_09b_mockPrefixWithoutExplicitSwitchIsRejected() throws Exception {
            WeChatStub.reject("mock:x");
            long before = userCount(jdbc);
            MvcResult result = mvc.perform(post("/auth/wxlogin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"mock:x\"}"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            assertErrorEnvelope(mapper, result, 401, "unauthorized");
            assertThat(userCount(jdbc)).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------------
    // 主体校验仅一次（T02-50）
    // ------------------------------------------------------------------

    /** T02-50 专用上下文：@SpyBean 包装真实 UserService，统计主体校验的 getById 次数。 */
    @Nested
    class SubjectSingleValidationTests {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private JdbcTemplate jdbc;

        @Autowired
        private JwtUtils jwtUtils;

        @Autowired
        private ObjectMapper mapper;

        @SpyBean
        private UserService userService;

        /** 每个用例复位 spy 调用记录并校验库名。 */
        @BeforeEach
        void prepareCard02Round() {
            reset(userService);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(TEST_DATABASE);
        }

        /** 嵌套类用例清理本类创建的夹具行。 */
        @AfterEach
        void cleanupCard02Round() {
            cleanupTracked(jdbc);
        }

        /** T02-50：/user/info 成功返回十字键；身份主体校验仅查询一次，资料投影单独读取。 */
        @Test
        void wx_50_subjectValidatedOnceAndProfileReadSeparately() throws Exception {
            String u = uidOf("U50");
            seedUser(jdbc, u, "T02五十昵称", "13812340005", "普通用户", ENCODER.encode(NORMAL_PWD), "正常", "/a.png",
                    null, "五十简介");
            MvcResult result = mvc.perform(get("/user/info").header("Authorization", tokenOf(jwtUtils, u, "普通用户")))
                    .andExpect(status().isOk())
                    .andReturn();
            assertOkEnvelope(mapper, result);
            JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
            assertThat(keysOf(data)).containsExactlyInAnyOrder(
                    "uid", "nickname", "avatar", "bio", "role", "uname", "pnum", "vote", "uct", "status");
            assertThat(data.at("/uid").asText()).isEqualTo(u);
            assertThat(data.at("/bio").asText()).isEqualTo("五十简介");
            verify(userService, times(1)).getById(u);
        }
    }

    // ------------------------------------------------------------------
    // 微信桩：仅当 com.pg.service.WeChatClient 接口存在时注册 @Primary 动态代理。
    // 桩语义：返回 openid 即成功；抛 IllegalArgumentException 表示无效/已用 code（实现映射 401）；
    // 抛 SocketTimeoutException/IOException 表示超时/不可用（实现映射 503）。
    // ------------------------------------------------------------------

    /** 测试专用配置：反射注册 WeChatClient 桩，避免基线阶段引用尚未实现的接口类型造成编译红。 */
    @TestConfiguration
    public static class WeChatStubConfiguration {

        private static final Logger log = LoggerFactory.getLogger(WeChatStubConfiguration.class);

        /** 实现卡交付的微信客户端接口全限定名。 */
        private static final String CLIENT_TYPE = "com.pg.service.WeChatClient";

        /** 桩 Bean 名，便于调试定位。 */
        public static final String STUB_BEAN = "card02WeChatStub";

        /**
         * 在 Bean 实例化前注册微信桩；接口尚未交付时仅记录日志，不阻塞上下文启动。
         * @return Bean 工厂后处理器
         */
        @Bean
        public static BeanFactoryPostProcessor card02WeChatStubRegistrar() {
            return factory -> {
                DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) factory;
                try {
                    Class<?> clientType = Class.forName(CLIENT_TYPE);
                    if (!clientType.isInterface()) {
                        log.info("微信客户端类型不是接口，跳过测试桩注册");
                        return;
                    }
                    Object stub = Proxy.newProxyInstance(clientType.getClassLoader(),
                            new Class<?>[]{clientType}, new StubInvocationHandler());
                    // 用 raw BeanDefinition 直接设 BeanClass/InstanceSupplier：
                    // genericBeanDefinition(Class<?>, Supplier) 在 Spring 5.2 泛型推断下不接受 wildcard Class，
                    // 会报「无法将 genericBeanDefinition 应用到给定类型」，故绕开泛型。
                    // 注意：必须用 AbstractBeanDefinition 承接，BeanDefinition 接口上没有 setBeanClass/setInstanceSupplier
                    AbstractBeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition().getRawBeanDefinition();
                    definition.setBeanClass(clientType);
                    definition.setInstanceSupplier(() -> stub);
                    definition.setPrimary(true);
                    beanFactory.registerBeanDefinition(STUB_BEAN, definition);
                    log.info("已注册卡02微信测试桩 bean={}", STUB_BEAN);
                } catch (ClassNotFoundException e) {
                    log.info("微信客户端接口尚未实现，本卡测试桩暂不注册：{}", e.getMessage());
                }
            };
        }
    }

    /** 把桩代理的方法调用转发到静态行为表；code2Session 之外的方法给出安全的默认应答。 */
    static final class StubInvocationHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("code2Session".equals(method.getName())) {
                return WeChatStub.handle((String) args[0]);
            }
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) {
                    return "Card02WeChatStub";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
            }
            return null;
        }
    }

    /** 桩行为表：按 code 返回 openid 或抛出失败；并发用例可安全读写。 */
    static final class WeChatStub {

        private static final Map<String, Behavior> BEHAVIORS = new ConcurrentHashMap<>();

        private static final AtomicInteger CALLS = new AtomicInteger();

        private static final class Behavior {

            private final String openid;

            private final Exception failure;

            private Behavior(String openid, Exception failure) {
                this.openid = openid;
                this.failure = failure;
            }
        }

        private WeChatStub() {
        }

        /**
         * 该 code 换取 openid 成功。
         * @param code 微信登录码
         * @param openid 桩返回的 openid
         */
        static void success(String code, String openid) {
            BEHAVIORS.put(code, new Behavior(openid, null));
        }

        /**
         * 该 code 被微信拒绝（无效/已用）；实现须映射为 401 unauthorized。
         * @param code 微信登录码
         */
        static void reject(String code) {
            BEHAVIORS.put(code, new Behavior(null, new IllegalArgumentException("微信登录码无效")));
        }

        /**
         * 该 code 触发微信侧超时；实现须映射为 503 service_unavailable。
         * @param code 微信登录码
         */
        static void timeout(String code) {
            BEHAVIORS.put(code, new Behavior(null, new SocketTimeoutException("微信接口超时")));
        }

        /**
         * 该 code 触发微信侧不可用；实现须映射为 503 service_unavailable。
         * @param code 微信登录码
         */
        static void unavailable(String code) {
            BEHAVIORS.put(code, new Behavior(null, new IOException("微信接口不可用")));
        }

        /** @return 桩被调用的总次数，用于“不调用真实微信”断言。 */
        static int calls() {
            return CALLS.get();
        }

        /** 清空行为表并复位计数器，每个用例开始前调用。 */
        static void reset() {
            BEHAVIORS.clear();
            CALLS.set(0);
        }

        /**
         * 按 code 查行为并应答。
         * @param code 微信登录码
         * @return openid
         * @throws Exception 桩配置的失败信号
         */
        static Object handle(String code) throws Exception {
            CALLS.incrementAndGet();
            Behavior behavior = BEHAVIORS.get(code);
            if (behavior == null) {
                throw new IllegalArgumentException("桩未配置该code的行为");
            }
            if (behavior.failure != null) {
                throw behavior.failure;
            }
            return behavior.openid;
        }
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

    /** @return 本类唯一 uid。 */
    private static String uidOf(String kind) {
        return "T02" + kind + TAG;
    }

    /**
     * 插入一条用户夹具并登记清理。
     * @param jdbc 数据访问
     * @param uid 用户主键
     * @param uname 用户名
     * @param pnum 手机号，可为 null（依赖 V003 放宽）
     * @param role 角色
     * @param pwdHash 已加密密码
     * @param status 状态
     * @param avatar 头像，可为 null（依赖 V003 放宽）
     * @param openid 微信标识，可为 null
     * @param bio 简介，可为 null
     */
    private static void seedUser(JdbcTemplate jdbc, String uid, String uname, String pnum, String role,
                                 String pwdHash, String status, String avatar, String openid, String bio) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, avatar, vote, status, openid, bio) "
                        + "VALUES (?, ?, ?, ?, ?, ?, '0', ?, ?, ?)",
                uid, uname, pnum, role, pwdHash, avatar, status, openid, bio);
        CREATED_UIDS.add(uid);
        if (openid != null) {
            CREATED_OPENIDS.add(openid);
        }
    }

    /** 清理本类登记的用户行。 */
    private static void cleanupTracked(JdbcTemplate jdbc) {
        for (String openid : CREATED_OPENIDS) {
            jdbc.update("DELETE FROM user WHERE openid = ?", openid);
        }
        for (String uid : CREATED_UIDS) {
            jdbc.update("DELETE FROM user WHERE uid = ?", uid);
        }
    }

    /** @return user 表总行数。 */
    private static long userCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user", Long.class);
    }

    /** @return user 表总行数（主类用例）。 */
    private long userCount() {
        return userCount(jdbc);
    }

    /** @return 携带认证方案的请求头值。 */
    private static String tokenOf(JwtUtils jwtUtils, String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    /**
     * 用测试密钥签发过期 JWT。
     * @param uid 主体
     * @param role 角色
     * @return 已过期的紧凑令牌
     */
    private static String expiredToken(String uid, String role) {
        Key key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_JWT_SECRET));
        Date now = new Date();
        return Jwts.builder()
                .setSubject(uid)
                .claim("role", role)
                .setIssuedAt(new Date(now.getTime() - 7200_000L))
                .setExpiration(new Date(now.getTime() - 3600_000L))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 用测试密钥签发未过期 JWT；role 为 null 时省略 role claim。
     * @param uid 主体
     * @param role 角色，可为 null
     * @return 紧凑令牌
     */
    private static String customRoleToken(String uid, String role) {
        Key key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_JWT_SECRET));
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
                .setSubject(uid)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + 3600_000L));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    /** @return 重复拼接的字符串。 */
    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    /** @return JSON 对象的一级键集合。 */
    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** 断言成功信封：200、application/json、恰三键、code=ok、msg 为安全中文、data 非空。 */
    private static void assertOkEnvelope(ObjectMapper mapper, MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).contains("application/json");
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(keysOf(json)).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.get("code").asText()).isEqualTo("ok");
        assertThat(json.get("msg").asText()).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(json.get("data").isNull()).isFalse();
    }

    /** 断言错误信封：HTTP 状态、application/json、恰三键、code、安全中文 msg、data=null。 */
    private static void assertErrorEnvelope(ObjectMapper mapper, MvcResult result, int httpStatus, String code)
            throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(httpStatus);
        assertThat(result.getResponse().getContentType()).contains("application/json");
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(keysOf(json)).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("msg").asText()).matches(".*[\\u4e00-\\u9fff].*");
        assertThat(json.get("data").isNull()).isTrue();
    }

    /** 递归断言响应树不含敏感字段键。 */
    private static void assertNoSensitiveKeys(JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                assertThat(name).isNotIn("pwd", "openid", "session_key", "appsecret");
                assertNoSensitiveKeys(node.get(name));
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                assertNoSensitiveKeys(item);
            }
        }
    }
}
