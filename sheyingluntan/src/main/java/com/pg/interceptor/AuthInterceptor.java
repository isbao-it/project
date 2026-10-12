package com.pg.interceptor;

import com.pg.entity.AjaxResult;
import com.pg.myutils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 1. 放行登录页面和静态资源
        if (uri.equals("/login/page")
                || uri.contains("/user/login")
                || uri.contains("/static/")
                || uri.contains("/error")
                || uri.contains("/favicon.ico")) {
            return true;
        }

        // 2. 判断是否为页面请求（需要重定向到登录页，而非返回 JSON）
        boolean isPageRequest = uri.endsWith("/page") || uri.contains("/page/");

        // 3. 判断是否为数据接口（返回 JSON 错误，非页面）
        boolean isApiRequest = !isPageRequest && (
                   uri.startsWith("/user/")
                || uri.startsWith("/pwork/")
                || uri.startsWith("/view/")
                || uri.startsWith("/upload/")
                || uri.startsWith("/work/")
        );

        // 4. 获取 token：优先从 Authorization 头，页面请求从 Cookie
        String token = getTokenFromRequest(request);

        if (token == null) {
            if (isApiRequest) {
                sendJsonError(response, 401, "未登录，请先登录");
            } else {
                response.sendRedirect("/login/page");
            }
            return false;
        }

        // 5. 验证 token
        if (!jwtUtils.validateToken(token)) {
            if (isApiRequest) {
                sendJsonError(response, 401, "登录已过期，请重新登录");
            } else {
                response.sendRedirect("/login/page");
            }
            return false;
        }

        // 6. 从 token 中获取用户信息
        String uid = jwtUtils.getUidFromToken(token);
        String role = jwtUtils.getRoleFromToken(token);
        request.setAttribute("uid", uid);
        request.setAttribute("role", role);

        // 7. 权限检查
        if ("管理员".equals(role)) {
            return true;
        }

        if ("普通用户".equals(role)) {
            // 普通用户不能访问管理接口
            if (uri.contains("/user/list") || uri.contains("/user/disable") || uri.contains("/user/enable")
                    || uri.contains("/user/batch")) {
                sendJsonError(response, 403, "无权限访问该资源");
                return false;
            }
            // pwork/list 允许普通用户访问（前端自动按 uid 过滤，只显示自己的作品）
            return true;
        }

        sendJsonError(response, 403, "权限不足");
        return false;
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

    private void sendJsonError(HttpServletResponse response, int code, String msg) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(code);
        PrintWriter writer = response.getWriter();
        writer.write(AjaxResult.error(code, msg).toString());
        writer.flush();
    }
}