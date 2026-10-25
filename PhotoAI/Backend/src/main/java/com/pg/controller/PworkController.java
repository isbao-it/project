package com.pg.controller;

import com.pg.service.PworkService;
import com.pg.service.WorkQueryService;
import com.pg.myutils.JwtUtils;
import com.pg.entity.AjaxResult;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 作品公开读取及本人写入入口，所有输出均采用冻结投影。 */
@RestController
@RequestMapping("/pwork")
public class PworkController {

    private final PworkService pworkService;
    private final WorkQueryService workQueryService;
    private final JwtUtils jwtUtils;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param pworkService 摄影作品服务
     * @param workQueryService 冻结作品读取服务
     * @param jwtUtils 可选身份的令牌校验组件
     */
    public PworkController(PworkService pworkService, WorkQueryService workQueryService, JwtUtils jwtUtils) {
        this.pworkService = pworkService;
        this.workQueryService = workQueryService;
        this.jwtUtils = jwtUtils;
    }

    /**
     * 查询公开作品；
     * @param tab 页签
     * @param category 分类
     * @param page 页码
     * @param size 页长
     * @param request 原始查询，用于拒绝旧分页别名并读取可选身份
     * @return list/total 成功信封
     */
    @GetMapping("/list")
    public AjaxResult list(
            @RequestParam(defaultValue = "recommend") String tab,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        rejectAliases(request);
        return AjaxResult.success(workQueryService.list(tab, category, page, size, optionalUid(request)));
    }

    /**
     * 查询展示作品或作者本人的作品，并增加浏览；
     * @param pid 作品编号
     * @param request 原始请求，用于读取可选身份
     * @return work/exif/author 信封
     */
    @GetMapping("/{pid}")
    public AjaxResult getById(@PathVariable String pid, HttpServletRequest request) {
        return AjaxResult.success(workQueryService.detail(pid, optionalUid(request)));
    }

    /**
     * 查询本人四状态作品；
     * @param uid 已验证身份
     * @param status 状态
     * @param page 页码
     * @param size 页长
     * @param request 原始查询
     * @return 带 reason 的分页信封
     */
    @GetMapping("/mine")
    public AjaxResult mine(@RequestAttribute String uid, @RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        rejectAliases(request);
        return AjaxResult.success(workQueryService.mine(uid, status, page, size));
    }

    /**
     * 发布待审作品；
     * @param uid 已验证作者
     * @param body 冻结请求对象
     * @return 新 pid 与待审核状态
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AjaxResult publish(@RequestAttribute String uid, @RequestBody Map<String, Object> body) {
        return AjaxResult.success(pworkService.publish(uid, body));
    }

    /**
     * 全量编辑作品并送审；
     * @param uid 已验证作者
     * @param pid 作品编号
     * @param body 四项内容
     * @return pid/status
     */
    @PutMapping("/{pid}")
    public AjaxResult update(@RequestAttribute String uid, @PathVariable String pid,
                             @RequestBody Map<String, Object> body) {
        return AjaxResult.success(pworkService.update(uid, pid, body));
    }

    /**
     * 作者切换作品展示或隐藏状态；
     * @param uid 已验证作者
     * @param pid 作品编号
     * @param body 目标 status
     * @return pid/status 成功信封
     */
    @PutMapping("/{pid}/status")
    public AjaxResult updateStatus(@RequestAttribute String uid, @PathVariable String pid,
                                   @RequestBody Map<String, Object> body) {
        return AjaxResult.success(pworkService.updateVisibility(uid, pid, body));
    }

    /**
     * 删除作品与从属行；
     * @param uid 已验证用户
     * @param role 令牌角色
     * @param pid 作品编号
     * @return data=null
     */
    @DeleteMapping("/{pid}")
    public AjaxResult delete(@RequestAttribute String uid, @RequestAttribute String role, @PathVariable String pid) {
        pworkService.delete(uid, role, pid);
        return AjaxResult.success();
    }

    /**
     * 读取可选身份：无令牌、令牌无效或过期一律返回 null，不影响匿名访问。
     * @param request 原始请求
     * @return 已验证的 uid，匿名时为 null
     */
    private String optionalUid(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            return jwtUtils.getUidFromToken(authHeader.substring(7));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void rejectAliases(HttpServletRequest request) {
        if (request.getParameterMap().containsKey("pageNum") || request.getParameterMap().containsKey("pageSize")) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "请使用新的分页参数");
        }
    }
}
