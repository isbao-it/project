package com.pg.controller;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.entity.AjaxResult;
import com.pg.service.ExifStripper;
import com.pg.service.WorkStorage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 上传真实作品图，只在清洗及安全落盘成功后返回 URL。 */
@RestController
public class WorkUploadController {
    private static final long MAX_BYTES = 10L * 1024 * 1024; // 冻结§1.3，与 Spring 单文件 10MB 一致。
    private final ExifStripper exifStripper;
    private final WorkStorage workStorage;

    /**
     * 固定清洗与存储边界；
     * @param exifStripper 图片清洗器
     * @param workStorage 安全目录存储
     */
    public WorkUploadController(ExifStripper exifStripper, WorkStorage workStorage) {
        this.exifStripper = exifStripper;
        this.workStorage = workStorage;
    }

    /**
     * 上传并剥离元数据；
     * @param file 原始文件
     * @return url/exif/exifStripped
     * @throws IOException 读取或存储的未知 IO 故障，由全局出口返回 500
     */
    @PostMapping("/upload/work")
    public AjaxResult upload(@RequestParam(required = false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "请选择作品图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.PAYLOAD_TOO_LARGE, "作品图片超过大小限制");
        }
        byte[] source = file.getBytes();
        Map<String, Object> safe;
        try {
            safe = exifStripper.strip(source);
        } catch (RuntimeException exception) {
            throw new BizException(ErrorCode.INVALID_IMAGE, "图片解析或清洗失败");
        }
        // 入口为任意上传字节；出口只把清洗器产生的新字节交给存储，响应不含原始元数据。
        String url = workStorage.save((byte[]) safe.get("bytes"), (String) safe.get("format"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("exif", safe.get("exif"));
        result.put("exifStripped", true);
        return AjaxResult.success(result);
    }
}
