package com.pg.independent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.myutils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * T 岗独立复核 R05 的 19 条冻结断言（T7.1～T14.1）。
 * 自带数据库夹具与断言，不继承、不调用任何既有 R05 测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class IndependentR05Test {

    private static final String RUN_TAG = Long.toHexString(System.nanoTime());
    private static final String ADMIN_UID = "T05ADM" + RUN_TAG;
    private static final String USER_UID = "T05USR" + RUN_TAG;
    private static final String OTHER_UID = "T05OTH" + RUN_TAG;
    private static final String GHOST_UID = "T05GST" + RUN_TAG;
    private static final String DISABLED_UID = "T05DIS" + RUN_TAG;
    private static final String MISSING_UID = "T05MIS" + RUN_TAG;
    private static final int PHONE_TAIL = Math.floorMod(RUN_TAG.hashCode(), 10000);
    private static final String USER_PNUM = String.format("138%04d0005", PHONE_TAIL);
    private static final String MASKED_USER_PNUM = "138****0005";
    private static final String OTHER_PNUM = String.format("139%04d0002", PHONE_TAIL);
    private static final String PASSWORD = "t05-correct-password";
    private static final String WRONG_PASSWORD = "t05-wrong-password";
    private static final String USER_PWD_HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    public void insertOwnFixtures() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .as("独立测试只允许连接 db_pg_test")
                .isEqualTo("db_pg_test");
        insertUser(ADMIN_UID, "T05管理员", phone("137"), "管理员", "T05-PWD-ADMIN", "正常");
        insertUser(USER_UID, "T05用户", USER_PNUM, "普通用户", USER_PWD_HASH, "正常");
        insertUser(OTHER_UID, "T05他人", OTHER_PNUM, "普通用户", "T05-PWD-OTHER", "正常");
        insertUser(GHOST_UID, "T05空角色主体", phone("135"), "普通用户", "T05-PWD-GHOST", "正常");
        insertUser(DISABLED_UID, "T05禁用主体", phone("136"), "普通用户", "T05-PWD-DISABLED", "禁用");
    }

    // ---------- T7：路径变体 ----------

    @Test
    void t71NormalUserPercentEncodedListIsForbidden() throws Exception {
        assertErrorStatus(perform(get(URI.create("/user/%6cist")).header("Authorization", bearer(USER_UID, "普通用户"))),
                403, "forbidden");
    }

    @Test
    void t72NormalUserMatrixParameterListIsForbidden() throws Exception {
        assertErrorStatus(perform(get(URI.create("/user/list;x=1")).header("Authorization", bearer(USER_UID, "普通用户"))),
                403, "forbidden");
    }

    @Test
    void t73NormalUserDoubleSlashListIsForbidden() throws Exception {
        assertErrorStatus(perform(get(URI.create("/user//list")).header("Authorization", bearer(USER_UID, "普通用户"))),
                403, "forbidden");
    }

    @Test
    void t74NormalUserEncodedLetterListIsForbidden() throws Exception {
        assertErrorStatus(perform(get(URI.create("/user/li%73t")).header("Authorization", bearer(USER_UID, "普通用户"))),
                403, "forbidden");
    }

    @Test
    void t75NormalUserEncodedAddIsForbiddenBeforeBodyValidation() throws Exception {
        assertErrorStatus(perform(post(URI.create("/user/%61dd"))
                        .header("Authorization", bearer(USER_UID, "普通用户"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                403, "forbidden");
    }

    @Test
    void t76NormalUserEncodedDisableIsForbidden() throws Exception {
        assertErrorStatus(perform(put(URI.create("/user/%64isable/x"))
                        .header("Authorization", bearer(USER_UID, "普通用户"))),
                403, "forbidden");
    }

    @Test
    void t77AdministratorEncodedListStillSucceeds() throws Exception {
        MvcResult result = perform(get(URI.create("/user/%6cist"))
                .header("Authorization", bearer(ADMIN_UID, "管理员")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = responseJson(result);
        assertThat(json.path("code").asText()).isEqualTo("ok");
        assertThat(json.at("/data/list").isArray()).isTrue();
        assertThat(json.at("/data/total").asInt()).isGreaterThanOrEqualTo(5);
    }

    // ---------- T8：密码与手机号 ----------

    @Test
    void t81UserListBodyHasNoPasswordFieldOrHash() throws Exception {
        MvcResult result = perform(get("/user/list").param("pageSize", "1000")
                .header("Authorization", bearer(ADMIN_UID, "管理员")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = responseBody(result);
        assertThat(USER_PWD_HASH).startsWith("$2a$");
        assertThat(body).doesNotContain("\"pwd\"");
        assertThat(body).doesNotContain(USER_PWD_HASH);
        assertThat(body).doesNotContain("T05-PWD-");
    }

    @Test
    void t82PhoneIsFullForAdminAndMaskedForNormalUser() throws Exception {
        MvcResult listResult = perform(get("/user/list").param("pageSize", "1000")
                .header("Authorization", bearer(ADMIN_UID, "管理员")));
        assertThat(listResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode list = responseJson(listResult).at("/data/list");
        String adminView = null;
        for (JsonNode item : list) {
            if (USER_UID.equals(item.path("uid").asText())) {
                adminView = item.path("pnum").asText();
            }
        }
        assertThat(adminView).as("管理员在列表中应看到完整手机号").isEqualTo(USER_PNUM);

        MvcResult selfResult = perform(get("/user/" + USER_UID)
                .header("Authorization", bearer(USER_UID, "普通用户")));
        assertThat(selfResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseJson(selfResult).at("/data/pnum").asText())
                .as("非管理员查看手机号必须脱敏")
                .isEqualTo(MASKED_USER_PNUM);
    }

    @Test
    void t83UserDetailHasNoPasswordFieldOrHash() throws Exception {
        MvcResult result = perform(get("/user/" + USER_UID)
                .header("Authorization", bearer(ADMIN_UID, "管理员")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = responseJson(result);
        assertThat(json.at("/data/uid").asText()).isEqualTo(USER_UID);
        String body = responseBody(result);
        assertThat(body).doesNotContain("\"pwd\"");
        assertThat(body).doesNotContain(USER_PWD_HASH);
    }

    // ---------- T9：空角色 ----------

    @Test
    void t91EmptyRoleTokenCannotReadAnotherUser() throws Exception {
        MvcResult result = perform(get("/user/" + OTHER_UID)
                .header("Authorization", bearer(GHOST_UID, "")));
        assertErrorStatus(result, 403, "forbidden");
        assertThat(responseBody(result)).doesNotContain(OTHER_PNUM);
    }

    @Test
    void t92EmptyRoleTokenNeverReceivesAnotherUsersData() throws Exception {
        MvcResult result = perform(get("/user/" + GHOST_UID)
                .header("Authorization", bearer(GHOST_UID, "")));
        int status = result.getResponse().getStatus();
        assertThat(status).as("空角色读自己：200 或 403 均可").isIn(200, 403);
        String body = responseBody(result);
        assertThat(body).doesNotContain(OTHER_PNUM);
        assertThat(body).doesNotContain(OTHER_UID);
        if (status == 200) {
            assertThat(responseJson(result).at("/data/uid").asText()).isEqualTo(GHOST_UID);
        } else {
            assertThat(responseJson(result).path("code").asText()).isEqualTo("forbidden");
        }
    }

    // ---------- T10：令牌主体状态 ----------

    @Test
    void t101TokenSubjectMissingFromDatabaseIsUnauthorized() throws Exception {
        assertErrorStatus(perform(get("/user/list")
                        .header("Authorization", bearer(MISSING_UID, "管理员"))),
                401, "unauthorized");
    }

    @Test
    void t102TokenSubjectWithNonNormalStatusIsUnauthorized() throws Exception {
        assertErrorStatus(perform(get("/user/list")
                        .header("Authorization", bearer(DISABLED_UID, "管理员"))),
                401, "unauthorized");
    }

    // ---------- T11：HTTP 语义与信封 ----------

    @Test
    void t111WrongPasswordReturnsHttp401AndUnauthorizedCode() throws Exception {
        MvcResult result = perform(post("/user/login")
                .param("pnum", USER_PNUM)
                .param("pwd", WRONG_PASSWORD));
        assertErrorStatus(result, 401, "unauthorized");
        assertThat(jdbc.queryForObject("SELECT role FROM user WHERE uid = ?", String.class, USER_UID))
                .isEqualTo("普通用户");
    }

    @Test
    void t112SuccessEnvelopeHasOnlyCodeMsgDataAndNestedPagination() throws Exception {
        MvcResult result = perform(get("/user/list").param("pageSize", "1000")
                .header("Authorization", bearer(ADMIN_UID, "管理员")));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = responseJson(result);
        Set<String> topLevelKeys = new LinkedHashSet<>();
        json.fieldNames().forEachRemaining(topLevelKeys::add);
        assertThat(topLevelKeys).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.has("total")).as("成功响应顶层不得出现 total").isFalse();
        assertThat(json.at("/data/list").isArray()).isTrue();
        assertThat(json.at("/data/total").isNumber()).isTrue();
        assertThat(json.at("/data/total").asInt()).isGreaterThanOrEqualTo(5);
    }

    // ---------- T12：非法枚举 ----------

    @Test
    void t121IllegalRoleOrStatusReturns400AndPersistsNothing() throws Exception {
        int usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
        String[] invalidFields = {"\"role\":\"ADMIN\"", "\"status\":\"HACKED\"", "\"role\":\" 普通用户\""};
        for (String invalidField : invalidFields) {
            MvcResult result = perform(put("/user/update")
                    .header("Authorization", bearer(ADMIN_UID, "管理员"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"uid\":\"" + USER_UID + "\"," + invalidField + "}"));
            assertErrorStatus(result, 400, "invalid_request");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT role FROM user WHERE uid = ?", String.class, USER_UID))
                .isEqualTo("普通用户");
        assertThat(jdbc.queryForObject("SELECT status FROM user WHERE uid = ?", String.class, USER_UID))
                .isEqualTo("正常");
        assertThat(jdbc.queryForObject("SELECT uname FROM user WHERE uid = ?", String.class, USER_UID))
                .isEqualTo("T05用户");
    }

    // ---------- T13：不存在的作品 ----------

    @Test
    void t131MissingWorkReturns404NotFound() throws Exception {
        assertErrorStatus(perform(get("/pwork/T05-NO-SUCH-" + RUN_TAG)
                        .header("Authorization", bearer(USER_UID, "普通用户"))),
                404, "not_found");
    }

    // ---------- T14：管理页面重定向 ----------

    @Test
    void t141NormalUserAdminPageRedirectsToLogin() throws Exception {
        MvcResult result = perform(get("/page/admin/index")
                .header("Authorization", bearer(USER_UID, "普通用户")));
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/login/page");
    }

    // ---------- 独立辅助方法 ----------

    private void insertUser(String uid, String uname, String pnum, String role, String pwd, String status) {
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, vote, status, avatar) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                uid, uname, pnum, role, pwd, "0", status, "/t05-independent.png");
    }

    private static String phone(String prefix) {
        return prefix + String.format("%08d", Math.floorMod(RUN_TAG.hashCode(), 100000000));
    }

    private String bearer(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String responseBody(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void assertErrorStatus(MvcResult result, int httpStatus, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(httpStatus);
        JsonNode json = responseJson(result);
        assertThat(json.path("code").asText()).isEqualTo(code);
        assertThat(json.path("data").isNull()).isTrue();
    }
}
