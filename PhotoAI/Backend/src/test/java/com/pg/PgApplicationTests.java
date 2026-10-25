package com.pg;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PgApplicationTests {

    @Test
    void contextLoads() {
        System.out.println("启动成功");
    }

}
