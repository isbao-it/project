package com.pg.service.impl;

import com.pg.service.WorkStorage;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 仅写入 uploads/work/，临时文件完整写完后才原子发布。 */
@Service
public class WorkStorageImpl implements WorkStorage {
    /** {@inheritDoc} */
    @Override
    public String save(byte[] bytes, String format) throws IOException {
        if (!"jpg".equals(format) && !"png".equals(format)) {
            throw new IOException("安全图片格式不合法");
        }
        Path directory = Paths.get(System.getProperty("user.dir"), "uploads", "work");
        Files.createDirectories(directory);
        String name = UUID.randomUUID().toString() + "." + format;
        Path temp = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return "/uploads/work/" + name;
    }
}
