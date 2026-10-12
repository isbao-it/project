package com.pg.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.util.Date;

/**
 * 对应视图 view_work_detail
 */
@TableName("view_work_detail")   // ← 指定视图名
public class WorkDetail implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId//主键字段标识
    private String pid;
    private String uid;
    private String wtitle;
    private String wdesc;
    private String vurl;
    private String viewcount;
    private String votecount;
    private String wstatus;
    private Date cwtime;
    private String likep;
    private String uname;
    private String avatar;
    private String pcname;
    @TableField("real_like_count")   // 映射视图中的 real_like_count 列
    private String realLikeCount;  // 实际点赞数（从视图计算得来）


    public String getPid() {
        return pid;
    }

    public void setPid(String pid) {
        this.pid = pid;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getWtitle() {
        return wtitle;
    }

    public void setWtitle(String wtitle) {
        this.wtitle = wtitle;
    }

    public String getWdesc() {
        return wdesc;
    }

    public void setWdesc(String wdesc) {
        this.wdesc = wdesc;
    }

    public String getVurl() {
        return vurl;
    }

    public void setVurl(String vurl) {
        this.vurl = vurl;
    }

    public String getViewcount() {
        return viewcount;
    }

    public void setViewcount(String viewcount) {
        this.viewcount = viewcount;
    }

    public String getVotecount() {
        return votecount;
    }

    public void setVotecount(String votecount) {
        this.votecount = votecount;
    }

    public String getWstatus() {
        return wstatus;
    }

    public void setWstatus(String wstatus) {
        this.wstatus = wstatus;
    }

    public Date getCwtime() {
        return cwtime;
    }

    public void setCwtime(Date cwtime) {
        this.cwtime = cwtime;
    }

    public String getLikep() {
        return likep;
    }

    public void setLikep(String likep) {
        this.likep = likep;
    }

    public String getUname() {
        return uname;
    }

    public void setUname(String uname) {
        this.uname = uname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getPcname() {
        return pcname;
    }

    public void setPcname(String pcname) {
        this.pcname = pcname;
    }

    public String getRealLikeCount() {
        return realLikeCount;
    }

    public void setRealLikeCount(String realLikeCount) {
        this.realLikeCount = realLikeCount;
    }

    @Override
    public String toString() {
        return "WorkDetail{" +
                "pid='" + pid + '\'' +
                ", uid='" + uid + '\'' +
                ", wtitle='" + wtitle + '\'' +
                ", wdesc='" + wdesc + '\'' +
                ", vurl='" + vurl + '\'' +
                ", viewcount='" + viewcount + '\'' +
                ", votecount='" + votecount + '\'' +
                ", wstatus='" + wstatus + '\'' +
                ", cwtime=" + cwtime +
                ", likep='" + likep + '\'' +
                ", uname='" + uname + '\'' +
                ", avatar='" + avatar + '\'' +
                ", pcname='" + pcname + '\'' +
                ", realLikeCount='" + realLikeCount + '\'' +
                '}';
    }
}