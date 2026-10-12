package com.pg.domain;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import java.util.Date;
import lombok.Data;

/**
 * 用来存储系统的用户的表
 * @TableName user
 */
@TableName(value ="user")
public class User implements Serializable {
    /**
     * 用户表主键ID
     */
    @TableId(value = "uid")//标记该属性对应数据库表的主键字段 uid。MyBatis-Plus在执行插入或按ID查询/更新/删除时，会识别此字段。
    private String uid;
    private String uname;
    private String pnum;
    private String role;
    private String pwd;
    private String avatar;
    private String vote;
    @TableField(insertStrategy = FieldStrategy.NEVER)  // 这个要默认使用数据库插入时忽略改字段
    private Date uct;
    private String status;
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
    //序列化支持分布式缓存当你的系统访问量变大时，
    // 经常会把数据库查出来的 User 对象存到 Redis 中缓存起来

    // getter 和 setter
    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getUname() { return uname; }
    public void setUname(String uname) { this.uname = uname; }

    public String getPnum() { return pnum; }
    public void setPnum(String pnum) { this.pnum = pnum; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getPwd() { return pwd; }
    public void setPwd(String pwd) { this.pwd = pwd; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getVote() { return vote; }
    public void setVote(String vote) { this.vote = vote; }

    public Date getUct() { return uct; }
    public void setUct(Date uct) { this.uct = uct; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}