package com.pg;

import com.pg.myutils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用独立测试库服务器和原始 curl 路径，避免 MockMvc 的路径处理掩盖绕过。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class R05HttpTest {
    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtUtils jwt;

    @Test
    void rawCurlPathVariantsAreForbidden() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test");
        Path details = Paths.get("../_Outputs/待审核/01/R05/details");
        Files.createDirectories(details);
        String phase = "red".equals(System.getenv("R05_PHASE")) ? "red" : "green";
        List<String> evidence = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        int beforeCount = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user WHERE uid IN ('U-r05-http','U-r05-http-admin')", Integer.class))
                .isZero();
        try {
            jdbc.update("INSERT INTO user (uid,uname,pnum,role,pwd,vote,status,avatar) VALUES (?,?,?,?,?,?,?,?)",
                    "U-r05-http", "R05 HTTP夹具", "19900000091", "普通用户", "test-password", "0", "正常", "/test.png");
            int index = 0;
            for (String path : Arrays.asList("/user/%6cist", "/user/list;x=1", "/user//list", "/user/li%73t")) {
                Path body = details.resolve("curl_" + phase + "_" + (++index) + ".json");
                Process process = new ProcessBuilder("curl.exe", "--noproxy", "*", "--path-as-is", "--silent", "--show-error",
                        "--max-time", "15", "-H", "Authorization: Bearer " + jwt.generateToken("U-r05-http", "普通用户"),
                        "-o", body.toString(), "-w", "%{http_code}", "http://127.0.0.1:" + port + path).start();
                assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
                String status = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
                statuses.add(status);
                evidence.add("curl --path-as-is -H 'Authorization: Bearer <test-token>' " + path + " -> HTTP " + status);
            }
            jdbc.update("INSERT INTO user (uid,uname,pnum,role,pwd,vote,status,avatar) VALUES (?,?,?,?,?,?,?,?)",
                    "U-r05-http-admin", "R05 HTTP管理员", "19900000092", "管理员", "test-admin-password", "0", "正常", "/test.png");
            Path adminBody = details.resolve("curl_" + phase + "_admin.json");
            Process admin = new ProcessBuilder("curl.exe", "--noproxy", "*", "--path-as-is", "--silent", "--show-error",
                    "--max-time", "15", "-H", "Authorization: Bearer " + jwt.generateToken("U-r05-http-admin", "管理员"),
                    "-o", adminBody.toString(), "-w", "%{http_code}", "http://127.0.0.1:" + port + "/user/%6cist").start();
            assertThat(admin.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(admin.exitValue()).isZero();
            String adminStatus = new String(readAll(admin.getInputStream()), StandardCharsets.UTF_8).trim();
            evidence.add("curl --path-as-is -H 'Authorization: Bearer <admin-test-token>' /user/%6cist -> HTTP " + adminStatus);
            Files.write(details.resolve("curl_" + phase + ".log"), evidence, StandardCharsets.UTF_8);
            assertThat(statuses).containsExactly("403", "403", "403", "403");
            assertThat(adminStatus).isEqualTo("200");
            assertThat(new String(Files.readAllBytes(adminBody), StandardCharsets.UTF_8)).doesNotContain("\"pwd\"");
        } finally {
            jdbc.update("DELETE FROM user WHERE uid IN ('U-r05-http','U-r05-http-admin')");
            int afterCount = jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class);
            Files.write(details.resolve("http_fixture_" + phase + ".log"),
                    Arrays.asList("database=db_pg_test", "users_before=" + beforeCount, "users_after=" + afterCount),
                    StandardCharsets.UTF_8);
            assertThat(afterCount).isEqualTo(beforeCount);
        }
    }

    private byte[] readAll(java.io.InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
