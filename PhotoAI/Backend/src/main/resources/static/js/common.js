// 等待 jQuery 加载完成后注册全局 AJAX 处理器
(function initCommon() {
    if (typeof $ === 'undefined') {
        setTimeout(initCommon, 100);
        return;
    }

    // 1. 为所有 jQuery AJAX 请求自动添加 Authorization 头
    //    跳过公开接口（登录等），避免 token 无意义暴露
    $(document).ajaxSend(function(event, jqxhr, settings) {
        // 公开接口白名单 — 不需要携带 token
        if (settings.url && (
            settings.url.includes('/user/login') ||
            settings.url.includes('/static/')
        )) {
            return;
        }
        var token = localStorage.getItem('token');
        if (token) {
            jqxhr.setRequestHeader('Authorization', 'Bearer ' + token);
        }
    });

    // 2. 全局处理 401 → 跳转登录页
    $(document).ajaxError(function(event, jqXHR) {
        if (jqXHR.status === 401) {
            localStorage.clear();
            document.cookie = 'token=; path=/; max-age=0';
            location.href = '/login/page?msg=unauthorized';
        }
    });
})();
