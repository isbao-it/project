package com.pg.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pg.domain.WorkDetail;
import com.pg.entity.AjaxResult;
import com.pg.service.WorkDetailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/view/workdetail")
public class WorkDetailController {

    @Autowired
    private WorkDetailService workDetailService;

    /**
     * 分页条件查询作品详情视图（支持按标题、作者、状态筛选）
     */
    @GetMapping("/list")
    public AjaxResult list(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            WorkDetail workDetail) {

        PageHelper.startPage(pageNum, pageSize);

        Map<String, Object> param = new HashMap<>();
        param.put("wtitle", workDetail.getWtitle());
        param.put("uname", workDetail.getUname());
        param.put("wstatus", workDetail.getWstatus());

        List<WorkDetail> list = workDetailService.selectByCondition(param);
        PageInfo<WorkDetail> pageInfo = new PageInfo<>(list);

        return AjaxResult.success(pageInfo.getTotal(), pageInfo.getList());
    }

    /**
     * 根据作品ID查询详情
     */
    @GetMapping("/{pid}")
    public AjaxResult getByPid(@PathVariable String pid) {
        WorkDetail detail = workDetailService.getById(pid);
        if (detail == null) {
            return AjaxResult.error(404, "视图不存在");
        }
        return AjaxResult.success(detail); // 使用单个对象的success方法
    }
}