package com.pg.controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController//返回一个指定格式的数据
@RequestMapping("/pwork")
public class pworkController {
    @GetMapping("/list")
    public String voteList(){
        return ("pwork");
    }
}
