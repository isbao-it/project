package com.pg;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查卡 01 的配置边界，防止真实口令或可用默认密钥重新进入主配置。
 */
class ConfigurationTest {

    @Test
    void t41To43SecretsMustComeFromEnvironmentWithoutDefaults() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        boolean passwordFound = false;
        boolean secretFound = false;
        for (String line : lines) {
            String value = line.split("#", 2)[0].trim();
            if (value.startsWith("password:")) {
                passwordFound = true;
                assertThat(value).isEqualTo("password: ${DB_PASSWORD}");
            }
            if (value.startsWith("secret:")) {
                secretFound = true;
                assertThat(value).isEqualTo("secret: ${JWT_SECRET}");
            }
        }
        assertThat(passwordFound).isTrue();
        assertThat(secretFound).isTrue();
    }

    @Test
    void t44ExampleContainsOnlyPlaceholdersAndEnvIsIgnored() throws Exception {
        Path example = Paths.get(".env.example");
        assertThat(example).exists();
        String content = new String(Files.readAllBytes(example), StandardCharsets.UTF_8);
        assertThat(content).contains("DB_PASSWORD=", "JWT_SECRET=");
        for (String line : content.split("\\R")) {
            if (!line.trim().isEmpty() && !line.trim().startsWith("#")) {
                assertThat(line).matches("[A-Z_]+=<[^>]+>");
            }
        }
        assertThat(Files.readAllLines(Paths.get("../.gitignore"), StandardCharsets.UTF_8)).contains(".env");
    }
}
