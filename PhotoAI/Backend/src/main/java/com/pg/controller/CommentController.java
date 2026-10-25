package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.CommentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 评论入口；写入身份只消费拦截器验证后的属性。 */
@RestController
@RequestMapping("/comment")
public class CommentController {
    private final CommentService commentService;

    /** @param commentService 评论读写与权限服务。 */
    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 分页读取展示作品的评论。
     * @param pid 作品编号
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return 评论列表及总数信封
     */
    @GetMapping("/list")
    public AjaxResult list(@RequestParam String pid, @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(commentService.list(pid, page, size));
    }

    /**
     * 发布评论；客户端传入的身份字段不会进入持久层。
     * @param body 评论 JSON 对象
     * @param request 已验证身份
     * @return 新评论编号信封；安全检查失败不写库
     */
    @PostMapping
    public AjaxResult create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return AjaxResult.success(commentService.create((String) request.getAttribute("uid"), body));
    }

    /**
     * 删除本人评论，管理员也可删除他人评论。
     * @param cid 评论编号
     * @param request 已验证身份及角色
     * @return 空数据成功信封；成功会删除一条评论
     */
    @DeleteMapping("/{cid}")
    public AjaxResult delete(@PathVariable String cid, HttpServletRequest request) {
        commentService.delete(cid, (String) request.getAttribute("uid"), (String) request.getAttribute("role"));
        return AjaxResult.success();
    }
}
