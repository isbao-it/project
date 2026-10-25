package com.pg;

import com.pg.domain.Pwork;
import com.pg.service.PworkService;
import com.pg.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证整型计数与数据库默认值经真实服务映射后的结果。 */
class PworkTypeTest extends BaseIntegrationTest {
    @Autowired
    private PworkService pworkService;

    @ParameterizedTest
    @ValueSource(strings = {"viewcount", "votecount", "likep"})
    void t51CountersAreIntegers(String field) throws Exception {
        assertThat(Pwork.class.getDeclaredField(field).getType()).isEqualTo(Integer.class);
    }

    @Test
    void t52ServiceReadsDatabaseDefaultCounters() {
        jdbc.update("INSERT INTO pwork (pid, uid, wtitle, wdesc, vurl, wstatus) VALUES (?, ?, ?, ?, ?, ?)",
                "P-card01", "U-normal", "测试作品", "回滚夹具", "/test.png", "展示");
        Pwork work = pworkService.getById("P-card01");
        assertThat(work).isNotNull();
        // 与 Integer 字面值比较，也会捕获仍由 String 承接整型列的映射回归。
        assertThat((Object) work.getViewcount()).isEqualTo(0);
        assertThat((Object) work.getVotecount()).isEqualTo(0);
        assertThat((Object) work.getLikep()).isEqualTo(0);
    }
}
