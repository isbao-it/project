package com.pg.service;

import java.io.IOException;

/** 交换微信临时登录码；外部身份仅供持久层定位账号，不进入响应。 */
public interface WeChatClient {
    /**
     * 调用微信换取身份。
     * @param code 微信临时登录码
     * @return 微信身份标识；无效码抛出 IllegalArgumentException
     * @throws IOException 微信超时或服务不可用
     */
    String code2Session(String code) throws IOException;
}
