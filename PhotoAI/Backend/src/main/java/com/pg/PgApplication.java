package com.pg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PgApplication {
    private static final Logger log = LoggerFactory.getLogger(PgApplication.class);

    public static void main(String[] args) {
        log.info("启动成功");
        SpringApplication.run(PgApplication.class, args);
    }

}
