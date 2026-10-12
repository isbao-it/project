package com.pg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PgApplication {

    public static void main(String[] args) {
        System.out.println("启动成功");
        SpringApplication.run(PgApplication.class, args);
    }

}
