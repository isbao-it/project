package com.pg;

import com.pg.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

/** 验证管理接口拒绝越权，同时保留本人的资料读取。 */
class AuthzTest extends BaseIntegrationTest {
    @Test
    void t11MissingTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/user/list")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized")).andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void t12MalformedTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/user/list").header("Authorization", "Bearer xxx.yyy.zzz"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void t13NormalUserIsForbidden() throws Exception {
        mvc.perform(get("/user/list").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void t14AdministratorCanListUsers() throws Exception {
        mvc.perform(get("/user/list").header("Authorization", token("U-admin", "管理员")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ok"));
    }

    @Test
    void t15EmptyRoleIsForbidden() throws Exception {
        mvc.perform(get("/user/list").header("Authorization", token("U-ghost", "")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void t16NormalUserCanReadOwnProfile() throws Exception {
        mvc.perform(get("/user/U-normal").header("Authorization", token("U-normal", "普通用户")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.uid").value("U-normal"));
    }
}
