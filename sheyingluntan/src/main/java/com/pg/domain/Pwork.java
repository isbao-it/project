package com.pg.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.util.Date;

@TableName("pwork")
public class Pwork implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId
    private String pid;
    private String uid;
    private String wtitle;
    private String wdesc;
    private String vurl;
    private String viewcount;
    private String votecount;
    private String wstatus;
    @TableField(insertStrategy = FieldStrategy.NEVER)
    private Date cwtime;
    private String likep;

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

    @Override
    public String toString() {
        return "Pwork{" +
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
                '}';
    }
}