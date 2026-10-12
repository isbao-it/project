package com.pg.controller;

import com.pg.entity.AjaxResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * 文件上传控制器
 * 处理用户头像上传相关的请求
 */
@RestController
@RequestMapping("/upload")
public class FileUploadController {

    /**
     * 从配置文件中读取的头像上传路径
     * 通过@Value注解自动注入
     */
    @Value("${file.upload.avatar}")
    private String avatarUploadPath;

    /**
     * 处理头像上传请求
     * @param file 用户上传的头像文件
     * @return AjaxResult 包含上传结果和文件访问URL
     */
    @PostMapping("/avatar")
    public AjaxResult uploadAvatar(MultipartFile file) {
        // 检查文件是否为空
        if (file == null || file.isEmpty()) {
            return AjaxResult.error(400, "请选择要上传的文件");
        }
        try {
            // 获取项目根目录
            String projectRoot = System.getProperty("user.dir");
            // 创建目标文件目录
            File destDir = new File(projectRoot, avatarUploadPath);
            if (!destDir.exists()) destDir.mkdirs();

            // 获取原始文件名
            String originalFilename = file.getOriginalFilename();
            // 提取文件扩展名
            String ext = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            // 生成新的文件名（UUID + 时间戳 + 扩展名）
            String newFileName = UUID.randomUUID().toString().replace("-", "") + "_" + System.currentTimeMillis() + ext;
            // 创建目标文件
            File destFile = new File(destDir, newFileName);
            // 执行文件上传
            file.transferTo(destFile);

            // 返回的 URL 格式：/static/images/avatar/xxx.jpg前端进行回调
            String fileUrl = "/static/images/avatar/" + newFileName;
            return AjaxResult.success(1,fileUrl);
        } catch (IOException e) {
            e.printStackTrace();
            return AjaxResult.error(500, "上传失败：" + e.getMessage());
        }
    }
}