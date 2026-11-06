package com.pg.controller;

import com.pg.common.BizException;
import com.pg.common.RequiresRole;
import com.pg.entity.AjaxResult;
import com.pg.service.AppealService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 匿名自证身份提交申诉；查询与处理逐方法限定管理员角色。 */
@RestController
public class AdminAppealController {
    private final AppealService appealService;

    /** @param appealService 申诉身份验证与事务服务 */
    public AdminAppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    /** @param body 微信临时码和申诉原文 @return 仅含申诉编号及状态的信封 */
    @PostMapping("/appeal")
    public AjaxResult submit(@RequestBody Map<String, Object> body) {
        return AjaxResult.success(appealService.submit(body));
    }

    /** @param parameters 原始状态及分页字符串 @return 申诉列表与总数信封 */
    @GetMapping("/admin/appeal/list")
    @RequiresRole("管理员")
    public AjaxResult list(@RequestParam Map<String, String> parameters) {
        return AjaxResult.success(appealService.list(parameters));
    }

    /** @param aid 申诉编号 @param body 处理结果及理由 @param request 拦截器验证的身份 @return 新状态信封 */
    @PutMapping("/admin/appeal/{aid}/handle")
    @RequiresRole("管理员")
    public AjaxResult handle(@PathVariable String aid, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        return AjaxResult.success(appealService.handle(aid, body, (String) request.getAttribute("uid")));
    }

    /** @param exception 本功能的固定业务说明 @return 不泄露账号是否存在的业务失败信封 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<AjaxResult> rejected(BizException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(AjaxResult.error(exception.getCode()).put("msg", exception.getMessage()));
    }
}
