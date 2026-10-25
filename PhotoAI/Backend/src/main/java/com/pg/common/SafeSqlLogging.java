package com.pg.common;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 账号引入微信身份后禁止 SQL 参数/结果打印，避免现有 StdOutImpl 泄露密钥和身份。 */
@Configuration
public class SafeSqlLogging {
    /**
     * 关闭 MyBatis 的 SQL 参数与结果日志；保留业务 SLF4J 和全局异常出口。
     * @return 只调整日志实现的配置器，不改变连接、SQL、映射或事务
     */
    @Bean
    public ConfigurationCustomizer suppressSensitiveSqlLogging() {
        return configuration -> configuration.setLogImpl(NoLoggingImpl.class);
    }
}
