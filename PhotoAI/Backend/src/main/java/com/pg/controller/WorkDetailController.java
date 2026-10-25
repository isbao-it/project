package com.pg.controller;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pg.domain.WorkDetail;
import com.pg.entity.AjaxResult;
import com.pg.service.WorkDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/view/workdetail")
public class WorkDetailController {

    private final WorkDetailService workDetailService;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param workDetailService 作品详情服务
     */
    public WorkDetailController(WorkDetailService workDetailService) {
        this.workDetailService = workDetailService;
    }

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
            throw new BizException(ErrorCode.NOT_FOUND, "视图不存在");
        }
        return AjaxResult.success(detail); // 使用单个对象的success方法
    }
}