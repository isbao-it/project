package com.pg.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String pwd;
    private String avatar;
    private String vote;
    @TableField(insertStrategy = FieldStrategy.NEVER)  // 这个要默认使用数据库插入时忽略改字段
    private Date uct;
    private String status;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String openid;
    private String bio;
    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
    //序列化支持分布式缓存当你的系统访问量变大时，
    // 经常会把数据库查出来的 User 对象存到 Redis 中缓存起来

    // getter 和 setter
    /** @return 仅供持久层使用的微信身份，不参与 JSON 输出。 */
    public String getOpenid() { return openid; }
    /** @param openid 微信验证后的身份，不接受资料补丁写入。 */
    public void setOpenid(String openid) { this.openid = openid; }
    /** @return 用户简介，可为 null。 */
    public String getBio() { return bio; }
    /** @param bio 已校验的用户简介。 */
    public void setBio(String bio) { this.bio = bio; }

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

    /**
     * 在响应副本上按角色遮盖手机号；调用者不得将此对象用于后续写库。
     * @param viewerRole 已验证的访问者角色；管理员可查看完整号码
     */
    public void maskPhoneFor(String viewerRole) {
        if (!"管理员".equals(viewerRole) && pnum != null) {
            pnum = pnum.matches("[0-9]{11}") ? pnum.substring(0, 3) + "****" + pnum.substring(7) : "****";
        }
    }
}
