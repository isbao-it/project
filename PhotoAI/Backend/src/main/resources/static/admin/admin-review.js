/**
 * 审核页：待审队列 + 详情 + 单件/批量决策。
 * 批量通过/驳回按「逐件调用已冻结的单件接口」实现，不依赖后端批量接口；
 * 每件的结果单独记录，部分失败不会把成功项回滚成"看似全部失败"。
 */
(function () {
    'use strict';

    var state = {
        page: 1,
        size: 10,
        total: 0,
        list: [],
        selected: {},
        activePid: null,
        detail: null,
        busy: false,
        focusPid: null
    };

    var queueBox = document.getElementById('queue');
    var heroBox = document.getElementById('detailHero');
    var bodyBox = document.getElementById('detailBody');
    var bulkBar = document.getElementById('bulkBar');
    var bulkCount = document.getElementById('bulkCount');
    var pageInfo = document.getElementById('pageInfo');
    var prevPage = document.getElementById('prevPage');
    var nextPage = document.getElementById('nextPage');

    function selectedPids() {
        return Object.keys(state.selected).filter(function (pid) {
            return state.selected[pid];
        });
    }

    function setBusy(value) {
        state.busy = value;
        document.querySelectorAll('#bulkApprove,#bulkReject,#bulkClear').forEach(function (button) {
            button.disabled = value;
        });
        document.querySelectorAll('#detailBody button[data-write], #detailBody textarea').forEach(function (control) {
            control.disabled = value;
        });
        document.querySelectorAll('#queue input[type=checkbox]').forEach(function (box) {
            box.disabled = value;
        });
        document.getElementById('pageSize').disabled = value;
        prevPage.disabled = value || state.page <= 1;
        nextPage.disabled = value || state.page * state.size >= state.total;
    }

    function renderBulkBar() {
        var pids = selectedPids();
        bulkCount.textContent = '已选 ' + pids.length + ' 件';
        bulkBar.hidden = pids.length === 0;
    }

    function formatTime(value) {
        if (!value) {
            return '';
        }
        return String(value).replace('T', ' ').replace(/:(\d{2})(\+|$)/, '$2').slice(0, 16);
    }

    /** 队列渲染：作品文本只走 textContent，图片地址只接受同源或 http(s)。 */
    function renderQueue() {
        queueBox.textContent = '';
        if (!state.list.length) {
            var empty = document.createElement('div');
            empty.className = 'empty';
            empty.textContent = state.total ? '本页暂无数据，请刷新' : '暂无待审核作品';
            queueBox.appendChild(empty);
            return;
        }
        state.list.forEach(function (work) {
            var item = document.createElement('div');
            item.className = 'queue-item' + (work.pid === state.activePid ? ' active' : '');
            item.dataset.pid = work.pid;

            var checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.checked = !!state.selected[work.pid];
            checkbox.setAttribute('aria-label', '选择 ' + (work.wtitle || work.pid));
            checkbox.addEventListener('click', function (event) {
                event.stopPropagation();
                state.selected[work.pid] = checkbox.checked;
                renderBulkBar();
            });
            item.appendChild(checkbox);

            var image = document.createElement('img');
            image.alt = '';
            image.loading = 'lazy';
            try {
                var url = new URL(work.vurl, location.origin);
                if (url.protocol === 'https:' || url.protocol === 'http:') {
                    image.src = url.href;
                }
            } catch (error) {
                image.alt = '无图';
            }
            item.appendChild(image);

            var info = document.createElement('div');
            info.style.minWidth = '0';
            var title = document.createElement('div');
            title.className = 't';
            title.textContent = work.wtitle || '未命名作品';
            info.appendChild(title);
            var author = document.createElement('div');
            author.className = 'm';
            var authorName = document.createElement('b');
            authorName.textContent = work.uname || work.uid || '未知';
            author.appendChild(authorName);
            author.appendChild(document.createTextNode(' · ' + (work.pcname || '未分类')));
            info.appendChild(author);
            var time = document.createElement('div');
            time.className = 'm';
            time.textContent = formatTime(work.cwtime);
            info.appendChild(time);
            item.appendChild(info);

            item.addEventListener('click', function () {
                select(work.pid);
            });
            queueBox.appendChild(item);
        });
    }

    function renderPager() {
        pageInfo.textContent = '第 ' + state.page + ' 页 · 共 ' + state.total + ' 件';
        prevPage.disabled = state.busy || state.page <= 1;
        nextPage.disabled = state.busy || state.page * state.size >= state.total;
    }

    /** 详情：接口返回 work/exif/author 三个键，前端不自行拼装作者资料。 */
    function renderDetail() {
        var packed = state.detail;
        if (!packed) {
            return;
        }
        var work = packed.work || {};
        var exif = packed.exif || {};
        var author = packed.author || {};

        heroBox.textContent = '';
        try {
            var url = new URL(work.vurl, location.origin);
            if (url.protocol === 'https:' || url.protocol === 'http:') {
                var image = document.createElement('img');
                image.src = url.href;
                image.alt = work.wtitle || '作品大图';
                heroBox.appendChild(image);
            } else {
                throw new Error('bad protocol');
            }
        } catch (error) {
            var emptyImage = document.createElement('div');
            emptyImage.className = 'hero-empty';
            emptyImage.textContent = '图片地址无效';
            heroBox.appendChild(emptyImage);
        }

        bodyBox.textContent = '';
        var meta = document.createElement('div');
        meta.className = 'meta-row';
        var title = document.createElement('h3');
        title.textContent = work.wtitle || '未命名作品';
        meta.appendChild(title);
        var tag = document.createElement('span');
        tag.className = 'tag ' + AdminApi.statusClass(work.wstatus);
        tag.textContent = work.wstatus || '';
        meta.appendChild(tag);
        var idText = document.createElement('span');
        idText.className = 'muted';
        idText.textContent = '作品 ID · ' + (work.pid || '');
        meta.appendChild(idText);
        bodyBox.appendChild(meta);

        var authorLine = document.createElement('div');
        authorLine.className = 'author-line';
        if (author.avatar) {
            var avatar = document.createElement('img');
            avatar.src = author.avatar;
            avatar.alt = '';
            authorLine.appendChild(avatar);
        }
        var authorInfo = document.createElement('div');
        var authorName = document.createElement('div');
        authorName.style.fontWeight = '600';
        authorName.textContent = author.nickname || work.uname || work.uid || '未知作者';
        authorInfo.appendChild(authorName);
        var authorId = document.createElement('div');
        authorId.style.cssText = 'font-size:10.5px;color:var(--ink3);';
        authorId.textContent = 'UID · ' + (author.uid || work.uid || '');
        authorInfo.appendChild(authorId);
        authorLine.appendChild(authorInfo);
        bodyBox.appendChild(authorLine);

        var desc = document.createElement('p');
        desc.className = 'desc';
        desc.textContent = work.wdesc || '（无描述）';
        bodyBox.appendChild(desc);

        var grid = document.createElement('div');
        grid.className = 'exif-grid';
        [
            ['相机', exif.camera],
            ['镜头', exif.lens],
            ['光圈', exif.aperture === null || exif.aperture === undefined ? '' : 'f/' + exif.aperture],
            ['快门', exif.shutter],
            ['ISO', exif.iso],
            ['焦距', exif.focal ? exif.focal + 'mm' : '']
        ].forEach(function (pair) {
            var cell = document.createElement('div');
            var label = document.createElement('span');
            label.textContent = pair[0];
            cell.appendChild(label);
            var value = document.createElement('b');
            value.textContent = pair[1] === null || pair[1] === undefined || pair[1] === '' ? '—' : String(pair[1]);
            cell.appendChild(value);
            grid.appendChild(cell);
        });
        bodyBox.appendChild(grid);

        var actions = document.createElement('div');
        actions.className = 'detail-actions';
        var approve = document.createElement('button');
        approve.type = 'button';
        approve.className = 'btn primary';
        approve.dataset.write = '1';
        approve.textContent = '✓ 通过并公开展示';
        approve.addEventListener('click', function () {
            decide(work.pid, 'approve');
        });
        actions.appendChild(approve);
        var reject = document.createElement('button');
        reject.type = 'button';
        reject.className = 'btn danger';
        reject.dataset.write = '1';
        reject.textContent = '驳回并填写理由';
        reject.addEventListener('click', function () {
            toggleRejectBox(work.pid);
        });
        actions.appendChild(reject);
        var hint = document.createElement('span');
        hint.className = 'hint';
        hint.textContent = '审核结果写入审核日志，作者可在「我的作品」看到驳回理由';
        actions.appendChild(hint);
        bodyBox.appendChild(actions);
    }

    /** 详情内的驳回输入区：理由 1–200 字符且不能全为空白，原文不裁剪。 */
    function toggleRejectBox(pid) {
        var existing = document.getElementById('rejectBox');
        if (existing) {
            existing.remove();
            return;
        }
        var box = document.createElement('div');
        box.className = 'reject-box';
        box.id = 'rejectBox';
        var label = document.createElement('label');
        label.textContent = '驳回理由（必填，1–200 字，不能全为空白）';
        label.htmlFor = 'rejectReason';
        box.appendChild(label);
        var textarea = document.createElement('textarea');
        textarea.id = 'rejectReason';
        textarea.rows = 4;
        textarea.style.marginTop = '8px';
        box.appendChild(textarea);
        var error = document.createElement('p');
        error.className = 'err';
        error.id = 'rejectReasonError';
        error.setAttribute('role', 'alert');
        box.appendChild(error);
        var confirm = document.createElement('button');
        confirm.type = 'button';
        confirm.className = 'btn danger small';
        confirm.dataset.write = '1';
        confirm.style.marginTop = '8px';
        confirm.textContent = '确认驳回';
        confirm.addEventListener('click', function () {
            if (!validReason(textarea.value)) {
                error.textContent = '驳回理由必须为 1–200 字，不能全为空白。';
                textarea.focus();
                return;
            }
            decide(pid, 'reject', textarea.value);
        });
        box.appendChild(confirm);
        bodyBox.appendChild(box);
        textarea.focus();
    }

    function validReason(value) {
        return !!value && value.trim().length > 0 && Array.from(value).length <= 200;
    }

    function select(pid) {
        state.activePid = pid;
        document.querySelectorAll('.queue-item').forEach(function (item) {
            item.classList.toggle('active', item.dataset.pid === pid);
        });
        loadDetail(pid);
    }

    async function loadDetail(pid) {
        AdminApi.feedback('正在加载作品详情…');
        try {
            var packed = await AdminApi.request('/admin/work/' + encodeURIComponent(pid));
            if (state.activePid !== pid) {
                return;
            }
            state.detail = packed;
            renderDetail();
            AdminApi.feedback('');
        } catch (error) {
            if (error.status === 401 || error.status === 403) {
                return;
            }
            AdminApi.feedback(error.status === 404 ? '作品已不存在，请刷新队列。' : '详情加载失败：' + error.message, 'error');
        }
    }

    /** 单件决策：成功后从队列移除并重读；失败按状态码给准确提示。 */
    async function decide(pid, action, reason) {
        if (state.busy) {
            return;
        }
        setBusy(true);
        AdminApi.feedback(action === 'approve' ? '正在提交通过…' : '正在提交驳回…');
        try {
            var body = action === 'reject' ? {reason: reason} : undefined;
            await AdminApi.request('/admin/work/' + encodeURIComponent(pid) + '/' + action, {
                method: 'PUT',
                body: body
            });
            delete state.selected[pid];
            renderBulkBar();
            if (state.activePid === pid) {
                state.activePid = null;
                state.detail = null;
                heroBox.textContent = '';
                var heroEmpty = document.createElement('div');
                heroEmpty.className = 'hero-empty';
                heroEmpty.textContent = '该作品已处理，选择下一件继续';
                heroBox.appendChild(heroEmpty);
                bodyBox.textContent = '';
                var done = document.createElement('p');
                done.className = 'empty';
                done.textContent = action === 'approve' ? '已通过，作品将公开展示。' : '已驳回，理由已记录。';
                bodyBox.appendChild(done);
            }
            state.total = Math.max(0, state.total - 1);
            AdminApi.feedback(action === 'approve' ? '审核已通过。' : '已驳回。', 'success');
            await load(state.page, {force: true});
        } catch (error) {
            if (error.status !== 401 && error.status !== 403) {
                AdminApi.feedback(error.status === 409
                    ? (error.message || '该作品已被处理，请刷新队列。')
                    : '提交失败：' + error.message, 'error');
            }
        } finally {
            setBusy(false);
        }
    }

    /** 批量：逐件串行提交，成功/失败分别计数，失败列举第一条原因。 */
    async function bulk(action) {
        var pids = selectedPids();
        if (!pids.length || state.busy) {
            return;
        }
        var reason;
        if (action === 'reject') {
            reason = window.prompt('批量驳回理由（1–200 字，应用到所选 ' + pids.length + ' 件）', '');
            if (reason === null) {
                return;
            }
            if (!validReason(reason)) {
                AdminApi.feedback('驳回理由必须为 1–200 字，不能全为空白。', 'error');
                return;
            }
        }
        setBusy(true);
        var ok = 0;
        var failed = [];
        for (var i = 0; i < pids.length; i++) {
            AdminApi.feedback('正在处理 ' + (i + 1) + ' / ' + pids.length + ' …');
            try {
                await AdminApi.request('/admin/work/' + encodeURIComponent(pids[i]) + '/' + action, {
                    method: 'PUT',
                    body: action === 'reject' ? {reason: reason} : undefined
                });
                ok++;
                delete state.selected[pids[i]];
            } catch (error) {
                failed.push(pids[i] + (error.status === 409 ? '（已被处理）' : '（' + (error.message || '失败') + '）'));
                if (error.status === 401 || error.status === 403) {
                    break;
                }
            }
        }
        renderBulkBar();
        setBusy(false);
        var message = '批量完成：成功 ' + ok + ' 件';
        if (failed.length) {
            message += '，失败 ' + failed.length + ' 件：' + failed.slice(0, 3).join('、');
        }
        AdminApi.feedback(message, failed.length ? 'error' : 'success');
        await load(state.page, {force: true});
    }

    /** 加载队列；opts.force 用于审完之后强制重读（否则会被外层 busy 守卫拦掉）。 */
    async function load(targetPage, opts) {
        var force = !!(opts && opts.force);
        var page = targetPage || 1;
        if (state.busy && !force) {
            return;
        }
        setBusy(true);
        AdminApi.feedback('正在加载待审核作品…');
        try {
            var data = await AdminApi.request('/admin/work/pending?page=' + page + '&size=' + state.size);
            if (!data.list.length && page > 1 && data.total > 0) {
                page = Math.max(1, Math.ceil(data.total / state.size));
                data = await AdminApi.request('/admin/work/pending?page=' + page + '&size=' + state.size);
            }
            state.page = page;
            state.total = data.total;
            state.list = data.list;
            renderQueue();
            renderPager();
            document.getElementById('queueTotal').textContent = data.total + ' 件待审核';
            AdminApi.feedback('');
            if (state.focusPid) {
                var pid = state.focusPid;
                state.focusPid = null;
                select(pid);
            } else if (state.activePid) {
                // 列表刷新后保持当前详情不丢失。
                select(state.activePid);
            }
        } catch (error) {
            if (error.status === 401 || error.status === 403) {
                return;
            }
            queueBox.textContent = '';
            var failed = document.createElement('div');
            failed.className = 'empty';
            failed.textContent = '队列加载失败：' + error.message;
            queueBox.appendChild(failed);
            AdminApi.feedback('队列加载失败：' + error.message, 'error');
        } finally {
            setBusy(false);
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.getElementById('whoName').textContent = AdminApi.displayName();
        var params = new URLSearchParams(location.search);
        state.focusPid = params.get('pid') || null;
        document.getElementById('pageSize').addEventListener('change', function (event) {
            state.size = Number(event.target.value) || 10;
            load(1);
        });
        prevPage.addEventListener('click', function () {
            if (state.page > 1) {
                load(state.page - 1);
            }
        });
        nextPage.addEventListener('click', function () {
            if (state.page * state.size < state.total) {
                load(state.page + 1);
            }
        });
        document.getElementById('bulkApprove').addEventListener('click', function () {
            bulk('approve');
        });
        document.getElementById('bulkReject').addEventListener('click', function () {
            bulk('reject');
        });
        document.getElementById('bulkClear').addEventListener('click', function () {
            state.selected = {};
            renderQueue();
            renderBulkBar();
        });
        document.addEventListener('admin:auth-error', function () {
            state.busy = true;
        });
        load(1);
    });
}());
