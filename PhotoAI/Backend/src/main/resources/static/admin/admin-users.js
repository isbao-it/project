/**
 * 用户管理页：分页 + 关键词 + 按 guards 结果决定「禁用/启用/删除」是否可点。
 * guards 只描述三项保护，不代表写权限；真正写入仍以服务端 403/409 为准。
 */
(function () {
    'use strict';

    var state = {
        keyword: '',
        page: 1,
        size: 20,
        total: 0,
        list: [],
        guards: {},
        guardsReady: false,
        guardsToken: 0,
        busy: false
    };

    var rows = document.getElementById('userRows');
    var REASON_TEXT = {
        self: '不能对自己执行此操作',
        last_admin: '最后一个正常管理员受保护',
        has_works: '名下仍有作品，需先处理作品'
    };

    function setBusy(value) {
        state.busy = value;
        document.querySelectorAll('#userRows button').forEach(function (button) {
            button.disabled = value || button.dataset.blocked === '1';
        });
        document.getElementById('searchBtn').disabled = value;
        document.getElementById('usersPrev').disabled = value || state.page <= 1;
        document.getElementById('usersNext').disabled = value || state.page * state.size >= state.total;
    }

    function formatTime(value) {
        if (!value) {
            return '—';
        }
        return String(value).replace('T', ' ').slice(0, 16);
    }

    /** 拉取本页按钮保护状态；旧响应回来时丢弃，避免覆盖新页。 */
    async function loadGuards() {
        var token = ++state.guardsToken;
        state.guardsReady = false;
        var uids = state.list.map(function (user) {
            return user.uid;
        });
        if (!uids.length) {
            state.guards = {};
            state.guardsReady = true;
            render();
            return;
        }
        try {
            var data = await AdminApi.request('/admin/user/guards?uids=' + encodeURIComponent(uids.join(',')));
            if (token !== state.guardsToken) {
                return;
            }
            var map = {};
            (data.list || []).forEach(function (item) {
                map[item.uid] = item;
            });
            state.guards = map;
            state.guardsReady = true;
            render();
        } catch (error) {
            if (token !== state.guardsToken) {
                return;
            }
            state.guards = {};
            state.guardsReady = false;
            render();
            if (error.status !== 401 && error.status !== 403) {
                AdminApi.feedback('按钮保护信息加载失败，写操作已暂时禁用：' + error.message, 'error');
            }
        }
    }

    function reasonHint(reason) {
        return REASON_TEXT[reason] || '';
    }

    function render() {
        rows.textContent = '';
        if (!state.list.length) {
            var emptyRow = document.createElement('tr');
            var emptyCell = document.createElement('td');
            emptyCell.colSpan = 7;
            emptyCell.className = 'empty';
            emptyCell.textContent = state.total ? '本页暂无用户' : '没有匹配的用户';
            emptyRow.appendChild(emptyCell);
            rows.appendChild(emptyRow);
            return;
        }
        var selfUid = AdminApi.currentUid();
        state.list.forEach(function (user) {
            var row = document.createElement('tr');

            var userCell = document.createElement('td');
            var cellBox = document.createElement('div');
            cellBox.className = 'user-cell';
            if (user.avatar) {
                var avatar = document.createElement('img');
                avatar.src = user.avatar;
                avatar.alt = '';
                cellBox.appendChild(avatar);
            }
            var info = document.createElement('div');
            var name = document.createElement('div');
            name.className = 'nm';
            name.textContent = user.nickname || user.uid || '未命名用户';
            info.appendChild(name);
            var sub = document.createElement('div');
            sub.className = 'id';
            sub.textContent = user.uid === selfUid ? '当前登录账号' : (user.bio || '');
            info.appendChild(sub);
            cellBox.appendChild(info);
            userCell.appendChild(cellBox);
            row.appendChild(userCell);

            var uidCell = document.createElement('td');
            uidCell.className = 'muted num';
            uidCell.textContent = user.uid || '';
            row.appendChild(uidCell);

            var roleCell = document.createElement('td');
            var roleTag = document.createElement('span');
            roleTag.className = 'tag ' + (user.role === '管理员' ? 'admin' : '');
            if (user.role !== '管理员') {
                roleTag.style.cssText = 'color:var(--ink2);background:#F5F1EE;';
            }
            roleTag.textContent = user.role || '';
            roleCell.appendChild(roleTag);
            row.appendChild(roleCell);

            var statusCell = document.createElement('td');
            var statusTag = document.createElement('span');
            statusTag.className = 'tag ' + (user.status === '正常' ? 'show' : 'off');
            statusTag.textContent = user.status || '';
            statusCell.appendChild(statusTag);
            row.appendChild(statusCell);

            var phoneCell = document.createElement('td');
            phoneCell.className = 'muted num';
            phoneCell.textContent = user.pnum || '—';
            row.appendChild(phoneCell);

            var timeCell = document.createElement('td');
            timeCell.className = 'muted num';
            timeCell.textContent = formatTime(user.createdAt);
            row.appendChild(timeCell);

            var actionCell = document.createElement('td');
            var guard = state.guards[user.uid];
            var loading = !state.guardsReady;
            var toggle = document.createElement('button');
            toggle.type = 'button';
            toggle.className = 'btn small ' + (user.status === '正常' ? 'danger' : 'primary');
            toggle.dataset.write = '1';
            toggle.textContent = user.status === '正常' ? '禁用' : '启用';
            // 启用不受「有作品」限制，但仍受自己/最后管理员限制。
            var disableBlocked = guard && !guard.canDisable;
            toggle.dataset.blocked = disableBlocked ? '1' : '0';
            toggle.disabled = state.busy || loading || !!disableBlocked;
            if (disableBlocked) {
                toggle.title = reasonHint(guard.disableReason);
            }
            if (loading) {
                toggle.title = '正在校验保护规则…';
            }
            toggle.addEventListener('click', function () {
                changeStatus(user);
            });
            actionCell.appendChild(toggle);

            var remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn small danger';
            remove.dataset.write = '1';
            remove.style.marginLeft = '6px';
            var workCount = guard ? (Number(guard.workCount) || 0) : 0;
            remove.textContent = workCount > 0 ? '删除（含 ' + workCount + ' 件）' : '删除';
            // 有作品不再禁用删除，而是要求二次确认后带 cascade=true；只有 self / last_admin 才真正禁用。
            var deleteBlocked = guard && !guard.canDelete && guard.deleteReason !== 'has_works';
            remove.dataset.blocked = deleteBlocked ? '1' : '0';
            remove.disabled = state.busy || loading || !!deleteBlocked;
            if (deleteBlocked) {
                remove.title = reasonHint(guard.deleteReason);
            }
            remove.dataset.workCount = String(workCount);
            remove.addEventListener('click', function () {
                removeUser(user, workCount);
            });
            actionCell.appendChild(remove);

            if (loading) {
                var hint = document.createElement('div');
                hint.className = 'guard-loading';
                hint.textContent = '校验保护规则…';
                actionCell.appendChild(hint);
            } else if (deleteBlocked || (guard && guard.canDisable === false)) {
                // 只有真正被禁用的按钮才解释原因；"有作品"已写在删除按钮上，不再重复提示行。
                var blockedHint = document.createElement('div');
                blockedHint.className = 'row-hint';
                blockedHint.textContent = reasonHint(
                    (deleteBlocked && guard.deleteReason) || (guard && guard.disableReason));
                actionCell.appendChild(blockedHint);
            }
            row.appendChild(actionCell);

            rows.appendChild(row);
        });
    }

    function renderPager() {
        var pages = Math.max(1, Math.ceil(state.total / state.size));
        document.getElementById('usersPageInfo').textContent =
            '共 ' + state.total + ' 位用户 · 每页 ' + state.size + ' 位';
        document.getElementById('usersPageNum').textContent = state.page + ' / ' + pages;
        document.getElementById('usersPrev').disabled = state.busy || state.page <= 1;
        document.getElementById('usersNext').disabled = state.busy || state.page >= pages;
        document.getElementById('userTotal').textContent = '共 ' + state.total + ' 位用户';
    }

    /** 加载列表；opts.force 用于写操作后的强制重读（否则会被外层 busy 守卫拦掉）。 */
    async function load(page, opts) {
        var force = !!(opts && opts.force);
        if (state.busy && !force) {
            return;
        }
        setBusy(true);
        state.page = page || 1;
        AdminApi.feedback('正在加载用户列表…');
        var query = ['page=' + state.page, 'size=' + state.size];
        if (state.keyword) {
            query.push('keyword=' + encodeURIComponent(state.keyword));
        }
        try {
            var data = await AdminApi.request('/admin/user/list?' + query.join('&'));
            // 末页数据被处理完后回退一页，避免停在空页。
            if (!data.list.length && state.page > 1 && data.total > 0) {
                state.page = Math.max(1, Math.ceil(data.total / state.size));
                data = await AdminApi.request('/admin/user/list?page=' + state.page + '&size=' + state.size
                    + (state.keyword ? '&keyword=' + encodeURIComponent(state.keyword) : ''));
            }
            state.list = data.list;
            state.total = data.total;
            render();
            renderPager();
            AdminApi.feedback('');
            await loadGuards();
        } catch (error) {
            if (error.status === 401 || error.status === 403) {
                return;
            }
            rows.textContent = '';
            var failedRow = document.createElement('tr');
            var failedCell = document.createElement('td');
            failedCell.colSpan = 7;
            failedCell.className = 'empty';
            failedCell.textContent = '加载失败：' + error.message;
            failedRow.appendChild(failedCell);
            rows.appendChild(failedRow);
            AdminApi.feedback('用户列表加载失败：' + error.message, 'error');
        } finally {
            setBusy(false);
        }
    }

    async function changeStatus(user) {
        if (state.busy) {
            return;
        }
        var target = user.status === '正常' ? '禁用' : '正常';
        if (target === '禁用' && !window.confirm('确定禁用「' + (user.nickname || user.uid) + '」吗？禁用后该账号将无法继续使用。')) {
            return;
        }
        setBusy(true);
        AdminApi.feedback('正在保存…');
        try {
            await AdminApi.request('/admin/user/' + encodeURIComponent(user.uid) + '/status', {
                method: 'PUT',
                body: {status: target}
            });
            await load(state.page, {force: true});
            AdminApi.feedback(target === '禁用'
                ? '已禁用该账号，其「展示」作品已全部隐藏（解禁不会自动恢复）。'
                : '已启用该账号。', 'success');
        } catch (error) {
            if (error.status === 403) {
                AdminApi.feedback('服务端拒绝：' + (error.payload && error.payload.msg || '不能操作该账号') + '，请刷新列表。', 'error');
            } else if (error.status === 409) {
                AdminApi.feedback('服务端拒绝：该账号受最后管理员保护，请刷新列表。', 'error');
            } else if (error.status !== 401) {
                AdminApi.feedback('操作失败：' + error.message, 'error');
            }
        } finally {
            setBusy(false);
        }
    }

    async function removeUser(user, workCount) {
        if (state.busy) {
            return;
        }
        var count = Number(workCount) || 0;
        var tip = count > 0
            ? '该用户名下有 ' + count + ' 件作品，将连同评论、点赞、收藏、关注等数据一并删除，且不可恢复。确定继续吗？'
            : '确定删除用户「' + (user.nickname || user.uid) + '」吗？删除后不可恢复。';
        if (!window.confirm(tip)) {
            return;
        }
        setBusy(true);
        AdminApi.feedback('正在删除…');
        try {
            // 只有确认了「波及 N 件作品」才带 cascade；无作品走普通删除，保持 v1 语义。
            var path = '/admin/user/' + encodeURIComponent(user.uid) + (count > 0 ? '?cascade=true' : '');
            var data = await AdminApi.request(path, {method: 'DELETE'});
            await load(state.page, {force: true});
            AdminApi.feedback('已删除该用户' + (data && data.deletedWorks ? '，连带删除 ' + data.deletedWorks + ' 件作品。' : '。'), 'success');
        } catch (error) {
            if (error.status === 409) {
                AdminApi.feedback('删除被拒绝：该用户仍有作品或受最后管理员保护，请先处理后再试。', 'error');
            } else if (error.status === 403) {
                AdminApi.feedback('删除被拒绝：不能删除自己的账号。', 'error');
            } else if (error.status !== 401) {
                AdminApi.feedback('删除失败：' + error.message, 'error');
            }
        } finally {
            setBusy(false);
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.getElementById('whoName').textContent = AdminApi.displayName();
        load(1);
        document.getElementById('searchBtn').addEventListener('click', function () {
            state.keyword = document.getElementById('keyword').value.trim();
            load(1);
        });
        document.getElementById('keyword').addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                document.getElementById('searchBtn').click();
            }
        });
        document.getElementById('resetBtn').addEventListener('click', function () {
            document.getElementById('keyword').value = '';
            state.keyword = '';
            load(1);
        });
        document.getElementById('usersPrev').addEventListener('click', function () {
            if (state.page > 1) {
                load(state.page - 1);
            }
        });
        document.getElementById('usersNext').addEventListener('click', function () {
            if (state.page * state.size < state.total) {
                load(state.page + 1);
            }
        });
    });
}());
