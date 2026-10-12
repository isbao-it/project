package com.pg.controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController//返回一个指定格式的数据
@RequestMapping("/syslog")
public class syslogController {
    @GetMapping("/list")
    public String voteList(){
        return ("syslog");
    }
}
