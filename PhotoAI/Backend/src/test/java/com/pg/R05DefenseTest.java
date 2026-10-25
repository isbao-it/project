package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.controller.UserController;
import com.pg.interceptor.AuthInterceptor;
import com.pg.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 补查双保险独立性及旧手工错误出口，防止只修冻结样例。 */
class R05DefenseTest extends BaseIntegrationTest {
    @Autowired
    private AuthInterceptor interceptor;
    @Autowired
    private UserController controller;
    @Autowired
    private ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"userList", "addUser", "disableUser", "enableUser", "batchDeleteUser"})
    void actualAdminMethodRejectsAliasOutsidePathRules(String name) throws Exception {
        for (Method method : UserController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/_test/r05/alias");
                request.addHeader("Authorization", token("U-normal", "普通用户"));
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(interceptor.preHandle(request, response, new HandlerMethod(controller, method))).isFalse();
                assertThat(response.getStatus()).isEqualTo(403);
                return;
            }
        }
        throw new AssertionError("找不到待检查的管理方法: " + name);
    }

    @Test
    void unknownRoleIsForbidden() throws Exception {
        mvc.perform(get("/user/U-normal").header("Authorization", token("U-normal", "ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"role\":\"ADMIN\"", "\"status\":\"HACKED\"", "\"role\":\" 普通用户\""})
    void invalidEnumsAreRejectedAlsoForNormalUsers(String field) throws Exception {
        mvc.perform(put("/user/update").header("Authorization", token("U-normal", "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"uid\":\"U-normal\"," + field + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(jdbc.queryForMap("SELECT role,status FROM user WHERE uid='U-normal'"))
                .containsEntry("role", "普通用户").containsEntry("status", "正常");
    }

    @Test
    void currentProfileUsesSafeEnvelopeAndPhone() throws Exception {
        mvc.perform(get("/user/info").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pnum").value("199****0001"))
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"pwd\""))));
    }

    @Test
    void missingViewUsesHttp404() throws Exception {
        mvc.perform(get("/view/workdetail/P-missing-r05").header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void manualAddValidationUsesHttp400() throws Exception {
        mvc.perform(post("/user/add").header("Authorization", token("U-admin", "管理员"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.total").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/disable/NO-r05", "/user/enable/NO-r05"})
    void missingManagedUserUsesHttp404(String path) throws Exception {
        mvc.perform(put(path).header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void validAddStillAcceptsWriteOnlyPassword() throws Exception {
        String body = mvc.perform(post("/user/add").header("Authorization", token("U-admin", "管理员"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"U-r05-added\",\"uname\":\"R05新增\",\"pnum\":\"19900000088\",\"pwd\":\"new-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        Set<String> keys = new HashSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("code", "msg", "data");
        // 主键由数据库触发器生成，按本用例唯一手机号核对真实写入的密码。
        assertThat(jdbc.queryForObject("SELECT pwd FROM user WHERE pnum='19900000088'", String.class))
                .startsWith("$2a$").isNotEqualTo("new-password");
        assertThat(body).doesNotContain("\"pwd\"", "new-password");
    }
}
