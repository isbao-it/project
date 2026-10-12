package com.pg.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import java.util.Date;
import lombok.Data;

/**
 * 用来存储系统的用户的表
 * @TableName user
 */
@TableName(value ="user")
@Data
public class User implements Serializable {
    /**
     * 用户表主键ID
     */
    @TableId(value = "uid")
    private String uid;

    /**
     * 用户名
     */
    @TableField(value = "uname")
    private String uname;

    /**
     * 手机号
     */
    @TableField(value = "pnum")
    private String pnum;

    /**
     * 选择权限0-管理员 1普通用户
     */
    @TableField(value = "role")
    private String role;

    /**
     * 账号密码
     */
    @TableField(value = "pwd")
    private String pwd;

    /**
     * 头像地址
     */
    @TableField(value = "avatar")
    private String avatar;

    /**
     * 用户取得积分
     */
    @TableField(value = "vote")
    private String vote;

    /**
     * 用户创建时间
     */
    @TableField(value = "uct")
    private Date uct;

    /**
     * 用户的状态
     */
    @TableField(value = "status")
    private String status;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

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