package com.pg.service;

import java.util.Map;

/** 图片清洗边界；只向存储交付重新编码的安全字节。 */
public interface ExifStripper {
    /**
     * 解码并剥离元数据；
     * @param source 原始图片字节
     * @return bytes/format/exif，仅供内部使用
     */
    Map<String, Object> strip(byte[] source);
}
