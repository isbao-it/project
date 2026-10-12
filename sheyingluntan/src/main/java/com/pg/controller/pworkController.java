package com.pg.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pg.domain.Pwork;
import com.pg.service.PworkService;
import com.pg.entity.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pwork")
public class pworkController {

    @Autowired
    private PworkService pworkService;

    /**
     * 分页查询摄影作品（支持按标题模糊查询、按状态筛选）
     */
    @GetMapping("/list")
    public AjaxResult list(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            Pwork pwork) {   // wdesc 是 Pwork 的属性，前端直接传 wdesc 即可
        PageHelper.startPage(pageNum, pageSize);
        Map<String, Object> param = new HashMap<>();
        param.put("wtitle", pwork.getWtitle());
        param.put("wstatus", pwork.getWstatus());
        param.put("uid", pwork.getUid());
        param.put("wdesc", pwork.getWdesc());   // 从 pwork 对象中获取
        List<Pwork> list = pworkService.queryPwork(param);
        PageInfo<Pwork> pageInfo = new PageInfo<>(list);
        return AjaxResult.success(pageInfo.getTotal(), pageInfo.getList());
    }

    /**
     * 根据主键 pid 查询单个作品
     */
    @GetMapping("/{pid}")
    public AjaxResult getById(@PathVariable String pid) {
        Pwork pwork = pworkService.getById(pid);
        return AjaxResult.success(pwork);
    }

}