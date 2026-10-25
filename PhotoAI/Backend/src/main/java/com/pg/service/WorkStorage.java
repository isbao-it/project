package com.pg.service;

import java.io.IOException;

/** 安全作品图存储，禁止将原始上传直接写入公开目录。 */
public interface WorkStorage {
    /**
     * 原子写入安全图片；
     * @param bytes 重新编码的字节
     * @param format jpg 或 png
     * @return 相对安全 URL
     * @throws IOException 写入失败，不伪装成图片校验失败
     */
    String save(byte[] bytes, String format) throws IOException;
}
