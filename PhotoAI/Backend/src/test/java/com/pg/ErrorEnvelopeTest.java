package com.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.service.PworkService;
import com.pg.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 检查真实 MVC 错误边界，响应只能携带安全的三个信封字段。 */
@Import(ErrorEnvelopeTest.FailureConfiguration.class)
class ErrorEnvelopeTest extends BaseIntegrationTest {
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private PworkService pworkService;

    @Test
    void t31MissingRouteIsNotFound() throws Exception {
        assertEnvelope(mvc.perform(get("/no/such/path")).andReturn(), 404, "not_found");
    }

    @Test
    void t32WrongMethodIs405InvalidRequest() throws Exception {
        assertEnvelope(mvc.perform(delete("/user/list").header("Authorization", token("U-admin", "管理员")))
                .andReturn(), 405, "invalid_request");
    }

    @Test
    void t33MissingLoginParametersAreBadRequest() throws Exception {
        assertEnvelope(mvc.perform(post("/user/login")).andReturn(), 400, "invalid_request");
    }

    @Test
    void t34InvalidUidIsBadRequest() throws Exception {
        assertEnvelope(mvc.perform(get("/user/illegal!uid").header("Authorization", token("U-admin", "管理员")))
                .andReturn(), 400, "invalid_request");
    }

    @Test
    void t34OverlongUidIsBadRequest() throws Exception {
        assertEnvelope(mvc.perform(get("/user/" + String.join("", Collections.nCopies(65, "a")))
                .header("Authorization", token("U-admin", "管理员"))).andReturn(), 400, "invalid_request");
    }

    @Test
    void t35InternalFailureHidesSensitiveDetails() throws Exception {
        when(pworkService.queryPwork(anyMap())).thenThrow(new RuntimeException(
                "java.lang.Exception SQL SELECT openid 13812345678 Bearer leaked-secret"));
        assertEnvelope(mvc.perform(get("/_test/failure").header("Authorization", token("U-admin", "管理员")))
                .andReturn(), 500, "internal_error");
    }

    @Test
    void t36AuthErrorsUseTheSameEnvelope() throws Exception {
        assertEnvelope(mvc.perform(get("/user/list")).andReturn(), 401, "unauthorized");
        assertEnvelope(mvc.perform(get("/user/list").header("Authorization", token("U-normal", "普通用户")))
                .andReturn(), 403, "forbidden");
    }

    private void assertEnvelope(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "java.", "SQL", "Bearer ", "openid", "\tat ");
        assertThat(body).doesNotContainPattern("138[0-9]{8}");
        JsonNode json = objectMapper.readTree(body);
        Set<String> keys = new HashSet<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("code", "msg", "data");
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("msg").asText()).isNotEmpty();
        assertThat(json.get("data").isNull()).isTrue();
    }

    /** 仅在测试上下文安装故障入口，不把测试开关带入生产代码。 */
    @TestConfiguration
    static class FailureConfiguration {
        /** 创建故障入口。
         * @param service 被替换的外部服务边界
         * @return 测试控制器
         */
        @Bean
        public FailureEndpoint failureEndpoint(PworkService service) {
            return new FailureEndpoint(service);
        }
    }

    /** 通过服务边界抛出故障，验证全局异常处理。 */
    @RestController
    static class FailureEndpoint {
        private final PworkService service;

        FailureEndpoint(PworkService service) {
            this.service = service;
        }

        /** 调用被注入故障的服务。
         * @return 服务响应；本用例中必然抛异常
         */
        @GetMapping("/_test/failure")
        public Object fail() {
            return service.queryPwork(Collections.emptyMap());
        }
    }
}
