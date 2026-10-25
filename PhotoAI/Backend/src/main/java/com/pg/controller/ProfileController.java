package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.ProfileService;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 本人资料补丁入口，账号编号只从拦截器验证后的身份取得。 */
@RestController
public class ProfileController {
    private final ProfileService profileService;

    /** @param profileService 白名单资料服务。 */
    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 更新本人资料；未传字段保持原值，额外字段由服务忽略。
     * @param changes JSON 资料补丁
     * @param request 拦截器写入的当前身份
     * @return 更新后五字段资料信封
     */
    @PutMapping("/user/profile")
    public AjaxResult updateProfile(@RequestBody Map<String, Object> changes, HttpServletRequest request) {
        return AjaxResult.success(profileService.updateProfile((String) request.getAttribute("uid"), changes));
    }
}
