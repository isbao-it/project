package com.pg;

import com.pg.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 验证资料白名单的实际落库结果，防止仅返回成功但仍写入危险字段。 */
class ProfileWhitelistTest extends BaseIntegrationTest {
    @ParameterizedTest
    @CsvSource({"role,管理员,普通用户", "vote,9999,0", "status,禁用,正常", "pwd,newpass,unchanged-password"})
    void t21To24ProtectedFieldsAreSilentlyIgnored(String field, String value, String original) throws Exception {
        mvc.perform(put("/user/update").header("Authorization", token("U-normal", "普通用户"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"U-normal\",\"" + field + "\":\"" + value + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ok"));
        // 字段名仅来自上面的固定样本，查询独立于待测实体的内存值。
        assertThat(jdbc.queryForObject("SELECT " + field + " FROM user WHERE uid='U-normal'", String.class))
                .isEqualTo(original);
    }

    @Test
    void t25ChangingAnotherUserIsForbidden() throws Exception {
        mvc.perform(put("/user/update").header("Authorization", token("U-normal", "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"uid\":\"U-other\",\"uname\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT uname FROM user WHERE uid='U-other'", String.class)).isEqualTo("其他用户");
    }

    @Test
    void t26AllowedFieldsArePersisted() throws Exception {
        mvc.perform(put("/user/update").header("Authorization", token("U-normal", "普通用户"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"U-normal\",\"uname\":\"新名字\",\"avatar\":\"/a.png\",\"pnum\":\"19900000003\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT uname, avatar, pnum FROM user WHERE uid='U-normal'"))
                .containsEntry("uname", "新名字").containsEntry("avatar", "/a.png").containsEntry("pnum", "19900000003");
    }

    @Test
    void t27AdministratorCanChangeRoleStatusAndVote() throws Exception {
        mvc.perform(put("/user/update").header("Authorization", token("U-admin", "管理员"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"U-normal\",\"role\":\"管理员\",\"status\":\"禁用\",\"vote\":\"23\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("ok"));
        assertThat(jdbc.queryForMap("SELECT role, status, vote FROM user WHERE uid='U-normal'"))
                .containsEntry("role", "管理员").containsEntry("status", "禁用");
        assertThat(jdbc.queryForObject("SELECT vote FROM user WHERE uid='U-normal'", Integer.class)).isEqualTo(23);
    }

    @Test
    void duplicatePhoneDoesNotOverwriteEitherUser() throws Exception {
        mvc.perform(put("/user/update").header("Authorization", token("U-normal", "普通用户"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"uid\":\"U-normal\",\"pnum\":\"19900000002\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict"));
        assertThat(jdbc.queryForObject("SELECT pnum FROM user WHERE uid='U-normal'", String.class)).isEqualTo("19900000001");
    }
}
