package com.pg.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Map;

/** 仅持久化六项安全 EXIF；发布时与作品共用事务。 */
@Mapper
public interface WorkExifMapper {
    /**
     * 保存六项；
     * @param pid 新作品编号
     * @param exif 已校验白名单
     * @return 新增行数
     */
    @Insert("INSERT INTO work_exif(pid,focal,aperture,shutter,iso,lens,camera) "
            + "VALUES(#{pid},#{exif.focal},#{exif.aperture},#{exif.shutter},#{exif.iso},#{exif.lens},#{exif.camera})")
    int insert(@Param("pid") String pid, @Param("exif") Map<String, Object> exif);

    /**
     * 读取六项；
     * @param pid 作品编号
     * @return 六项或 null，无需返回 stripped 标记
     */
    // pid 仅作行存在标记，避免 MyBatis 把六项全 NULL 的已有行折叠成 null。
    @Select("SELECT pid,focal,aperture,shutter,iso,lens,camera FROM work_exif WHERE pid=#{pid}")
    Map<String, Object> find(@Param("pid") String pid);
}
