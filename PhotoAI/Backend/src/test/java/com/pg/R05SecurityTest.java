package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.domain.User;
import com.pg.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** R05 的 19 条冻结场景；真实 MVC、JWT 与测试库共同验证安全边界。 */
class R05SecurityTest extends BaseIntegrationTest {
    @Autowired
    private ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"/user/%6cist", "/user/list;x=1", "/user//list", "/user/li%73t"})
    void t71To74EncodedListIsForbidden(String path) throws Exception {
        mvc.perform(get(URI.create(path)).header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void t75EncodedAddIsForbiddenBeforeValidation() throws Exception {
        mvc.perform(post(URI.create("/user/%61dd")).header("Authorization", token("U-normal", "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void t76EncodedDisableIsForbidden() throws Exception {
        mvc.perform(put(URI.create("/user/%64isable/x")).header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isForbidden());
    }

    @Test
    void t77AdministratorCanUseEncodedList() throws Exception {
        mvc.perform(get(URI.create("/user/%6cist")).header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ok"));
    }

    @Test
    void t81ListNeverSerializesPassword() throws Exception {
        String body = mvc.perform(get("/user/list").header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"pwd\"", "admin-password");
        // WRITE_ONLY 必须保留反序列化能力，不能用忽略字段造成以后无法收取密码。
        assertThat(mapper.readValue("{\"pwd\":\"incoming-password\"}", User.class).getPwd())
                .isEqualTo("incoming-password");
    }

    @Test
    void t82PhoneVisibilityFollowsRole() throws Exception {
        jdbc.update("UPDATE user SET pnum='13812340005' WHERE uid='U-normal'");
        mvc.perform(get("/user/U-normal").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pnum").value("138****0005"));
        mvc.perform(get("/user/list").param("pnum", "13812340005")
                .header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("13812340005")));
        assertThat(jdbc.queryForObject("SELECT pnum FROM user WHERE uid='U-normal'", String.class))
                .isEqualTo("13812340005");
    }

    @Test
    void t83DetailNeverSerializesPassword() throws Exception {
        String body = mvc.perform(get("/user/U-normal").header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"pwd\"");
    }

    @Test
    void t91EmptyRoleCannotReadOthers() throws Exception {
        mvc.perform(get("/user/U-other").header("Authorization", token("U-ghost", "")))
                .andExpect(status().isForbidden());
    }

    @Test
    void t92EmptyRoleCannotReadEvenSelf() throws Exception {
        mvc.perform(get("/user/U-ghost").header("Authorization", token("U-ghost", "")))
                .andExpect(status().isForbidden());
    }

    @Test
    void t101MissingSubjectIsUnauthorized() throws Exception {
        mvc.perform(get("/user/list").header("Authorization", token("U-missing-r05", "管理员")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void t102DisabledSubjectIsUnauthorized() throws Exception {
        jdbc.update("UPDATE user SET status='禁用' WHERE uid='U-normal'");
        mvc.perform(get("/user/U-normal").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void t111WrongPasswordIsHttp401() throws Exception {
        mvc.perform(post("/user/login").param("pnum", "19900000001").param("pwd", "wrong-password"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void t112SuccessEnvelopeNestsPagination() throws Exception {
        MvcResult result = mvc.perform(get("/user/list").header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = mapper.readTree(result.getResponse().getContentAsString());
        Set<String> keys = new HashSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.at("/data/list").isArray()).isTrue();
        assertThat(json.at("/data/total").asInt()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void t121InvalidEnumsNeverPersist() throws Exception {
        for (String field : new String[]{"\"role\":\"ADMIN\"", "\"status\":\"HACKED\"", "\"role\":\" 普通用户\""}) {
            mvc.perform(put("/user/update").header("Authorization", token("U-admin", "管理员"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"uid\":\"U-normal\"," + field + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
            assertThat(jdbc.queryForMap("SELECT role,status FROM user WHERE uid='U-normal'"))
                    .containsEntry("role", "普通用户").containsEntry("status", "正常");
        }
    }

    @Test
    void t131MissingWorkIsNotFound() throws Exception {
        mvc.perform(get("/pwork/P-missing-r05").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void t141NormalUserCannotLoadAdminPage() throws Exception {
        mvc.perform(get("/page/admin/index").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login/page"));
    }
}
