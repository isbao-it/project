/**
 * 申诉处理页：按状态分页查看封禁申诉，接受即解禁，驳回必须写理由。
 * 接口：GET /admin/appeal/list、PUT /admin/appeal/{aid}/handle（docs/接口增补_ADMIN.md §7.2/§7.3）。
 */
(function () {
    'use strict';

    var state = {
        status: '',
        page: 1,
        size: 20,
        total: 0,
        busy: false,
        pendingAid: null
    };

    var rows = document.getElementById('appealRows');

    /** 忙态统一禁用会写库的按钮，避免重复提交产生并发处理。 */
    function setBusy(value) {
        state.busy = value;
        document.querySelectorAll('#appealRows button').forEach(function (button) {
            button.disabled = value;
        });
        document.getElementById('appealPrev').disabled = value || state.page <= 1;
        document.getElementById('appealNext').disabled = value || state.page * state.size >= state.total;
    }

    function formatTime(value) {
        if (!value) {
            return '—';
        }
        return String(value).replace('T', ' ').replace(/:(\d{2})(\+|$)/, '$2').slice(0, 16);
    }

    /** 状态色沿用 admin-shell.css 的 .tag 系列（wait/show/reject），不另造一套。 */
    function statusClass(status) {
        if (status === '待处理') {
            return 'wait';
        }
        return status === '已受理' ? 'show' : 'reject';
    }

    /** 申诉人单元格：头像 + 昵称 + uid，用户已删时昵称为空也要能看出是谁。 */
    function whoCell(item) {
        var cell = document.createElement('td');
        var wrap = document.createElement('div');
        wrap.className = 'who';
        var img = document.createElement('img');
        img.src = item.avatar || '/static/images/avatar/admin.jpg';
        img.alt = '';
        var box = document.createElement('div');
        var name = document.createElement('b');
        name.textContent = item.nickname || '（用户已删除）';
        var uid = document.createElement('span');
        uid.textContent = item.uid;
        box.appendChild(name);
        box.appendChild(uid);
        wrap.appendChild(img);
        wrap.appendChild(box);
        cell.appendChild(wrap);
        return cell;
    }

    function rowOf(item) {
        var tr = document.createElement('tr');

        tr.appendChild(whoCell(item));

        // 理由用 textContent：申诉内容来自用户输入，禁止拼进 innerHTML。
        var reasonCell = document.createElement('td');
        var reason = document.createElement('div');
        reason.className = 'reason';
        reason.textContent = item.content || '';
        reasonCell.appendChild(reason);
        tr.appendChild(reasonCell);

        var timeCell = document.createElement('td');
        timeCell.textContent = formatTime(item.ct);
        tr.appendChild(timeCell);

        var statusCell = document.createElement('td');
        var badge = document.createElement('span');
        badge.className = 'tag ' + statusClass(item.status);
        badge.textContent = item.status || '';
        statusCell.appendChild(badge);
        tr.appendChild(statusCell);

        var handlerCell = document.createElement('td');
        handlerCell.textContent = item.handler || '—';
        if (item.reason) {
            handlerCell.title = '驳回理由：' + item.reason;
        }
        tr.appendChild(handlerCell);

        var actionCell = document.createElement('td');
        var actions = document.createElement('div');
        actions.className = 'actions';
        if (item.status === '待处理') {
            var accept = document.createElement('button');
            accept.type = 'button';
            accept.className = 'btn small primary';
            accept.textContent = '接受并解禁';
            accept.addEventListener('click', function () {
                decide(item, '接受');
            });
            var reject = document.createElement('button');
            reject.type = 'button';
            reject.className = 'btn small danger';
            reject.textContent = '驳回';
            reject.addEventListener('click', function () {
                openReject(item);
            });
            actions.appendChild(accept);
            actions.appendChild(reject);
        } else {
            var done = document.createElement('span');
            done.className = 'sub';
            done.textContent = formatTime(item.hct) === '—' ? '已处理' : '处理于 ' + formatTime(item.hct);
            actions.appendChild(done);
        }
        actionCell.appendChild(actions);
        tr.appendChild(actionCell);
        return tr;
    }

    function render(data) {
        var list = (data && data.list) || [];
        state.total = (data && data.total) || 0;
        rows.innerHTML = '';
        if (!list.length) {
            var empty = document.createElement('tr');
            var cell = document.createElement('td');
            cell.colSpan = 6;
            cell.className = 'empty';
            cell.textContent = '没有符合条件的申诉';
            empty.appendChild(cell);
            rows.appendChild(empty);
        } else {
            list.forEach(function (item) {
                rows.appendChild(rowOf(item));
            });
        }
        var pages = Math.max(1, Math.ceil(state.total / state.size));
        document.getElementById('appealPageInfo').textContent = state.page + ' / ' + pages;
        document.getElementById('appealTotal').textContent = '共 ' + state.total + ' 条';
        setBusy(false);
    }

    async function load(opts) {
        var force = opts && opts.force;
        if (state.busy && !force) {
            return;
        }
        setBusy(true);
        try {
            var query = ['page=' + state.page, 'size=' + state.size];
            if (state.status) {
                query.push('status=' + encodeURIComponent(state.status));
            }
            var data = await AdminApi.request('/admin/appeal/list?' + query.join('&'));
            render(data);
        } catch (error) {
            rows.innerHTML = '';
            var tr = document.createElement('tr');
            var td = document.createElement('td');
            td.colSpan = 6;
            td.className = 'empty';
            td.textContent = '加载失败：' + (error.message || '未知错误');
            tr.appendChild(td);
            rows.appendChild(tr);
            setBusy(false);
        }
    }

    /** 四个状态各取一次 total 拼顶部计数；成本是 4 次轻查询，换来一眼看清积压。 */
    async function loadCounts() {
        var targets = [
            {id: 'cAll', status: ''},
            {id: 'cPending', status: '待处理'},
            {id: 'cAccepted', status: '已受理'},
            {id: 'cRejected', status: '已驳回'}
        ];
        for (var i = 0; i < targets.length; i++) {
            try {
                var query = 'page=1&size=1';
                if (targets[i].status) {
                    query += '&status=' + encodeURIComponent(targets[i].status);
                }
                var data = await AdminApi.request('/admin/appeal/list?' + query);
                document.getElementById(targets[i].id).textContent = (data && data.total) || 0;
            } catch (error) {
                document.getElementById(targets[i].id).textContent = '–';
            }
        }
        var badge = document.getElementById('navAppealCount');
        var pending = document.getElementById('cPending').textContent;
        if (badge && pending && pending !== '0' && pending !== '–') {
            badge.textContent = pending;
            badge.hidden = false;
        }
    }

    async function decide(item, result, reason) {
        if (result === '接受' && !window.confirm('接受后会立即解除该账号的封禁（作品不会自动恢复展示）。确认？')) {
            return;
        }
        setBusy(true);
        try {
            var body = {result: result};
            if (reason) {
                body.reason = reason;
            }
            await AdminApi.request('/admin/appeal/' + encodeURIComponent(item.aid) + '/handle', {
                method: 'PUT',
                body: body
            });
            AdminApi.feedback(result === '接受' ? '已解禁该账号' : '已驳回申诉', 'ok');
            await load({force: true});
            await loadCounts();
        } catch (error) {
            AdminApi.feedback('处理失败：' + (error.message || '未知错误'), 'error');
            setBusy(false);
        }
    }

    var rejectMask = document.getElementById('rejectMask');
    var rejectReason = document.getElementById('rejectReason');
    var rejectTip = document.getElementById('rejectTip');

    function openReject(item) {
        state.pendingAid = item.aid;
        rejectReason.value = '';
        rejectTip.textContent = '账号会保持封禁状态；理由必填。';
        rejectMask.hidden = false;
        rejectReason.focus();
    }

    function closeReject() {
        rejectMask.hidden = true;
        state.pendingAid = null;
    }

    async function submitReject() {
        var reason = rejectReason.value;
        if (!reason || !reason.trim() || reason.length > 200) {
            rejectTip.textContent = '请填写 1-200 字的驳回理由。';
            rejectReason.focus();
            return;
        }
        var aid = state.pendingAid;
        closeReject();
        await decide({aid: aid}, '驳回', reason);
    }

    document.querySelectorAll('#statusSeg button').forEach(function (button) {
        button.addEventListener('click', function () {
            document.querySelectorAll('#statusSeg button').forEach(function (other) {
                other.classList.toggle('active', other === button);
            });
            state.status = button.dataset.status || '';
            state.page = 1;
            load();
        });
    });

    document.getElementById('appealPrev').addEventListener('click', function () {
        if (state.page > 1) {
            state.page -= 1;
            load();
        }
    });

    document.getElementById('appealNext').addEventListener('click', function () {
        if (state.page * state.size < state.total) {
            state.page += 1;
            load();
        }
    });

    document.getElementById('rejectClose').addEventListener('click', closeReject);
    document.getElementById('rejectCancel').addEventListener('click', closeReject);
    document.getElementById('rejectSave').addEventListener('click', submitReject);
    rejectMask.addEventListener('click', function (event) {
        if (event.target === event.currentTarget) {
            closeReject();
        }
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeReject();
        }
    });

    load();
    loadCounts();
}());
