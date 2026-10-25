package com.pg.support;

import com.pg.myutils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** 卡 01 集成基座：使用真实测试库，每个用例结束后回滚写入。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class BaseIntegrationTest {
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JwtUtils jwtUtils;

    /** 校验库名并写入测试身份，拒绝把夹具写进业务库。 */
    @BeforeEach
    public void prepareUsers() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("db_pg_test");
        // R05：鉴权现在核对真实账号；补齐旧断言使用的身份，事务结束时一起回滚。
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, vote, status, avatar) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "U-admin", "测试管理员", "19900000004", "管理员", "admin-password", "0", "正常", "/test.png");
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, vote, status, avatar) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "U-ghost", "空角色令牌主体", "19900000005", "普通用户", "ghost-password", "0", "正常", "/test.png");
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, vote, status, avatar) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "U-normal", "测试用户", "19900000001", "普通用户", "unchanged-password", "0", "正常", "/test.png");
        jdbc.update("INSERT INTO user (uid, uname, pnum, role, pwd, vote, status, avatar) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "U-other", "其他用户", "19900000002", "普通用户", "other-password", "0", "正常", "/test.png");
    }

    /** 签发真实测试令牌。
     * @param uid 测试用户标识
     * @param role 测试角色
     * @return 携带认证方案的请求头值
     */
    protected String token(String uid, String role) {
        return "Bearer " + jwtUtils.generateToken(uid, role);
    }
}
