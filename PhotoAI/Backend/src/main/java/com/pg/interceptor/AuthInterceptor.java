package com.pg.interceptor;

import com.pg.entity.AjaxResult;
import com.pg.common.ErrorCode;
import com.pg.common.RequiresRole;
import com.pg.domain.User;
import com.pg.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import com.pg.myutils.JwtUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 集中执行登录校验和管理路径规则，向下游传递已验证的身份。 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
            "/pwork/list", "/comment/list", "/category/list", "/search", "/health",
            "/login/page", "/favicon.ico"));
    private static final Set<String> ADMIN_PATH_PREFIXES = new HashSet<>(Arrays.asList(
            "/admin", "/user/list", "/user/add", "/user/disable", "/user/enable", "/user/batch"));

    private final JwtUtils jwtUtils;

    private final ObjectMapper objectMapper;

    private final UserService userService;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param jwtUtils 令牌签发与验签组件
     * @param objectMapper 错误信封序列化组件
     * @param userService 用户数据服务
     */
    public AuthInterceptor(JwtUtils jwtUtils, ObjectMapper objectMapper, UserService userService) {
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
        this.userService = userService;
    }

    private final UrlPathHelper pathHelper = new UrlPathHelper();

    /**
     * 校验令牌并按路径表拒绝越权，失败时直接写入 JSON 响应。
     * @param request 传入的 HTTP 请求
     * @param response 传出的 HTTP 响应
     * @param handler MVC 已匹配的处理器
     * @return 是否允许进入控制器
     * @throws Exception 响应写入失败
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 使用和 MVC 相同的解码、矩阵参数清理规则；合并重复斜杠避免规则与路由看到不同路径。
        String uri = pathHelper.getPathWithinApplication(request).replaceAll("/{2,}", "/");

        // 输入是请求头或 Cookie 的令牌；只将验签后的身份交给控制器。
        String token = getTokenFromRequest(request);
        boolean hasCredential = request.getHeader("Authorization") != null || token != null;
        if (!hasCredential && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("token".equals(cookie.getName())) {
                    hasCredential = true;
                    break;
                }
            }
        }
        boolean anonymous = "/error".equals(uri)
                || ("POST".equals(request.getMethod())
                    && ("/auth/wxlogin".equals(uri) || "/user/login".equals(uri) || "/appeal".equals(uri)));
        if ("GET".equals(request.getMethod())) {
            anonymous = anonymous || PUBLIC_PATHS.contains(uri) || matchesPrefix(uri, "/static")
                    || matchesPrefix(uri, "/uploads");
            if (handler instanceof HandlerMethod) {
                // 使用 MVC 匹配出的模板识别动态作品路由；固定 mine/following/管理路由永不冒充作品编号。
                Object pattern = request.getAttribute(
                        org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                boolean workDetail = "/pwork/{pid}".equals(pattern)
                        && !Arrays.asList("mine", "following", "add", "update", "batch", "list")
                            .contains(uri.substring(uri.lastIndexOf('/') + 1));
                anonymous = anonymous || workDetail || "/pwork/similar/{pid}".equals(pattern);
            }
        }
        if (anonymous && handler instanceof HandlerMethod) {
            HandlerMethod method = (HandlerMethod) handler;
            RequiresRole required = method.getMethodAnnotation(RequiresRole.class);
            if (required == null) {
                required = AnnotationUtils.findAnnotation(method.getBeanType(), RequiresRole.class);
            }
            // 游客规则不能盖过已匹配处理器的管理注解；带凭据的请求仍走后续完整授权。
            anonymous = required == null;
        }
        if (anonymous && !hasCredential) {
            return true;
        }
        Claims claims = token == null ? null : jwtUtils.parseToken(token);
        if (claims == null || claims.getSubject() == null || claims.getSubject().trim().isEmpty()) {
            if (matchesPrefix(uri, "/page/admin")) {
                response.sendRedirect(request.getContextPath() + "/login/page");
                return false;
            }
            sendJsonError(response, ErrorCode.UNAUTHORIZED);
            return false;
        }

        String role = claims.get("role") instanceof String ? (String) claims.get("role") : null;
        if (matchesPrefix(uri, "/page/admin") && !"管理员".equals(role)) {
            response.sendRedirect(request.getContextPath() + "/login/page");
            return false;
        }

        // 仅受保护 API 查一次账号状态，静态资源、登录页和其他页面不触发数据库查询。
        if (isProtectedApi(uri)) {
            User subject = userService.getById(claims.getSubject());
            if (subject == null || !"正常".equals(subject.getStatus())) {
                sendJsonError(response, ErrorCode.UNAUTHORIZED);
                return false;
            }
        }
        if (!"管理员".equals(role) && !"普通用户".equals(role)) {
            sendJsonError(response, ErrorCode.FORBIDDEN);
            return false;
        }
        request.setAttribute("uid", claims.getSubject());
        request.setAttribute("role", role);

        // 注解按已匹配的方法鉴权，即使将来出现新的 URL 别名，也不能绕过管理权限。
        if (handler instanceof HandlerMethod) {
            HandlerMethod method = (HandlerMethod) handler;
            RequiresRole required = method.getMethodAnnotation(RequiresRole.class);
            if (required == null) {
                required = AnnotationUtils.findAnnotation(method.getBeanType(), RequiresRole.class);
            }
            if (required != null && !required.value().equals(role)
                    && !(required.allowSelfUpdate() && "普通用户".equals(role))) {
                sendJsonError(response, ErrorCode.FORBIDDEN);
                return false;
            }
        }

        // 按路径段匹配，避免 /x/user/list 误命中和 /user/listSomething 被当作管理路径。
        for (String prefix : ADMIN_PATH_PREFIXES) {
            if (matchesPrefix(uri, prefix) && !"管理员".equals(role)) {
                sendJsonError(response, ErrorCode.FORBIDDEN);
                return false;
            }
        }
        return true;
    }

    private boolean isProtectedApi(String uri) {
        for (String prefix : Arrays.asList("/user", "/pwork", "/view", "/upload", "/admin",
                "/auth", "/comment", "/like", "/favorite", "/follow", "/search", "/category", "/health")) {
            if (matchesPrefix(uri, prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPrefix(String uri, String prefix) {
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }

    /**
     * 从请求中获取 token：优先 Authorization 头，其次 Cookie
     */
    private String getTokenFromRequest(HttpServletRequest request) {
        // 1. 先从 Authorization 头获取（API 请求）
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (authHeader != null) {
            return null;
        }
        // 2. 再从 Cookie 获取（页面请求）
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("token".equals(cookie.getName())) {
                    String token = cookie.getValue();
                    if (token != null && !token.isEmpty()) {
                        return token;
                    }
                }
            }
        }
        return null;
    }

    private void sendJsonError(HttpServletResponse response, ErrorCode code) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code.getStatus());
        objectMapper.writeValue(response.getWriter(), AjaxResult.error(code));
    }
}
