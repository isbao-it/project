/**
 * 管理端统一请求入口。
 * 页面只调用 AdminApi.request，不各自处理鉴权与信封，避免 401/403 在各页分叉。
 */
(function (global) {
    'use strict';

    var LOGIN_PAGE = '/login/page';

    /** 清除本地凭据：401 时只清一次，避免多个并发请求重复清理。 */
    function clearCredentials() {
        try {
            localStorage.removeItem('token');
            localStorage.removeItem('uid');
            localStorage.removeItem('role');
            localStorage.removeItem('uname');
        } catch (ignored) {
            // 隐私模式下 localStorage 可能不可写，忽略即可。
        }
        document.cookie = 'token=; path=/; max-age=0';
    }

    /** 读取当前管理员展示名。 */
    function displayName() {
        try {
            return localStorage.getItem('uname') || '管理员';
        } catch (ignored) {
            return '管理员';
        }
    }

    /** 当前登录管理员的 uid，用于按钮保护与「自己」判定。 */
    function currentUid() {
        try {
            return localStorage.getItem('uid') || '';
        } catch (ignored) {
            return '';
        }
    }

    /** 统一反馈：给 #admin-feedback 写一条状态；提示类数秒后自动消失，错误类留久一点。 */
    var feedbackTimer = null;

    function feedback(message, kind) {
        var box = document.getElementById('admin-feedback');
        if (!box) {
            return;
        }
        if (feedbackTimer) {
            clearTimeout(feedbackTimer);
            feedbackTimer = null;
        }
        box.textContent = message || '';
        box.className = 'admin-feedback' + (kind ? ' ' + kind : '');
        if (!message) {
            return;
        }
        if (kind === 'error' && /登录已失效/.test(message)) {
            var link = document.createElement('a');
            link.href = LOGIN_PAGE;
            link.textContent = ' 前往登录';
            box.appendChild(link);
            // 需要用户点击去登录，这类提示不自动消失。
            return;
        }
        var delay = kind === 'error' ? 6000 : 3000;
        feedbackTimer = setTimeout(function () {
            // 只清除仍是本次写入的内容，避免把后续提示误清。
            if (box.textContent === message) {
                box.textContent = '';
                box.className = 'admin-feedback';
            }
            feedbackTimer = null;
        }, delay);
    }

    /** 权限失败时锁定页面上的写按钮，防止用户继续点击制造 403 噪音。 */
    function lockWrites() {
        document.querySelectorAll('button[data-write]').forEach(function (button) {
            button.disabled = true;
            button.setAttribute('aria-disabled', 'true');
        });
    }

    /** 广播鉴权失败，页面据此停止后续队列（批量操作场景）。 */
    function broadcast(status, code) {
        if (typeof global.dispatchEvent !== 'function' || typeof global.CustomEvent !== 'function') {
            return;
        }
        global.dispatchEvent(new global.CustomEvent('admin:auth-error', {
            detail: {status: status, code: code}
        }));
    }

    /**
     * 发一次管理端请求。
     * @param {string} path 接口路径，必须以 /admin 开头
     * @param {Object} [options] {method, body}
     * @returns {Promise<Object>} 解包后的 data；失败抛出带 status/code 的错误
     */
    async function request(path, options) {
        var config = options || {};
        var method = config.method || 'GET';
        var headers = {};
        var token = null;
        try {
            token = localStorage.getItem('token');
        } catch (ignored) {
            token = null;
        }
        if (token) {
            headers.Authorization = 'Bearer ' + token;
        }
        if (config.body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        var response;
        try {
            response = await fetch(path, {
                method: method,
                headers: headers,
                credentials: 'same-origin',
                body: config.body === undefined ? undefined : JSON.stringify(config.body)
            });
        } catch (networkError) {
            var netError = new Error('网络异常，请检查连接后重试。');
            netError.status = 0;
            netError.code = null;
            throw netError;
        }

        // 网关或容器可能返回 HTML/空正文，解析失败仍保留 HTTP 状态，才能准确提示权限或冲突。
        var body = null;
        try {
            body = await response.json();
        } catch (parseError) {
            body = null;
        }
        if (!response.ok || !body || body.code !== 'ok') {
            var error = new Error((body && body.msg) || '请求失败，请稍后重试。');
            error.status = response.status;
            error.code = body && body.code;
            error.payload = body;
            if (response.status === 401) {
                clearCredentials();
                lockWrites();
                feedback('登录已失效，请重新登录后刷新。', 'error');
                broadcast(401, error.code);
            } else if (response.status === 403) {
                lockWrites();
                feedback('当前账号没有管理员权限，请使用管理员账号登录。', 'error');
                broadcast(403, error.code);
            }
            throw error;
        }
        return body.data;
    }

    /** 退出登录：清凭据后回登录页。 */
    function logout() {
        clearCredentials();
        global.location.href = LOGIN_PAGE;
    }

    /** 转义为可安全放进 innerHTML 的文本（页面统一用这段，不散写转义）。 */
    function escapeHtml(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /** 作品状态 → 标签样式类。 */
    function statusClass(status) {
        if (status === '展示') {
            return 'show';
        }
        if (status === '待审核') {
            return 'wait';
        }
        if (status === '已驳回') {
            return 'reject';
        }
        return 'hide';
    }

    global.AdminApi = {
        request: request,
        logout: logout,
        feedback: feedback,
        displayName: displayName,
        currentUid: currentUid,
        escapeHtml: escapeHtml,
        statusClass: statusClass,
        LOGIN_PAGE: LOGIN_PAGE
    };
}(window));
