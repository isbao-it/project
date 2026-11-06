/**
 * 作品管理页：任意状态分页查询 + 管理员可见性切换 + 详情弹层。
 * 状态写入只走 PUT /admin/work/{pid}/status；删除复用既有 DELETE /pwork/{pid}。
 */
(function () {
    'use strict';

    var state = {
        status: '',
        keyword: '',
        category: '',
        page: 1,
        size: 20,
        total: 0,
        busy: false
    };

    var rows = document.getElementById('workRows');

    function setBusy(value) {
        state.busy = value;
        document.querySelectorAll('#workRows button').forEach(function (button) {
            button.disabled = value;
        });
        document.getElementById('searchBtn').disabled = value;
        document.getElementById('worksPrev').disabled = value || state.page <= 1;
        document.getElementById('worksNext').disabled = value || state.page * state.size >= state.total;
    }

    function formatTime(value) {
        if (!value) {
            return '';
        }
        return String(value).replace('T', ' ').replace(/:(\d{2})(\+|$)/, '$2').slice(0, 16);
    }

    /** 分类下拉：一次拉字典，失败不阻塞列表（分类筛选退化为不可用）。 */
    async function loadCategories() {
        try {
            var data = await AdminApi.request('/category/list');
            var select = document.getElementById('category');
            var keep = select.value || state.category;
            // 只保留"全部分类"占位；否则每次新增分类后重拉，选项会翻倍。
            while (select.options.length > 1) {
                select.remove(1);
            }
            // 接口返回 {list:[...]}，不是裸数组；按 list 取，避免下拉永远为空。
            ((data && data.list) || []).forEach(function (item) {
                var option = document.createElement('option');
                option.value = item.pcid;
                option.textContent = item.pcname + (item.count === undefined ? '' : '（' + item.count + '）');
                select.appendChild(option);
            });
            select.value = keep || '';
        } catch (error) {
            // 分类字典是增强信息，失败时保留"全部分类"，不打断主流程。
        }
    }

    /** 顶部计数来自 /admin/stats，与概览页同源，避免两处口径不同。 */
    async function loadCounts() {
        try {
            var data = await AdminApi.request('/admin/stats');
            var counts = data.statusCounts || {};
            document.getElementById('cAll').textContent = data.workCount;
            document.getElementById('cVisible').textContent = counts.visible;
            document.getElementById('cPending').textContent = counts.pending;
            document.getElementById('cHidden').textContent = counts.hidden;
            document.getElementById('cRejected').textContent = counts.rejected;
            document.getElementById('worksTotal').textContent = '共 ' + data.workCount + ' 件作品';
            var badge = document.getElementById('navPendingCount');
            if (badge) {
                badge.textContent = counts.pending;
                badge.hidden = !counts.pending;
            }
        } catch (error) {
            // 计数失败不阻塞列表，列表自身会给出总数。
        }
    }

    function statusActions(work) {
        var actions = [];
        if (work.wstatus === '待审核') {
            actions.push({label: '去审核', cls: 'primary', handler: function () {
                location.href = '/page/admin/audit?pid=' + encodeURIComponent(work.pid);
            }});
        } else if (work.wstatus === '展示') {
            actions.push({label: '隐藏', cls: '', handler: function () { changeStatus(work.pid, '隐藏'); }});
        } else if (work.wstatus === '隐藏') {
            actions.push({label: '恢复展示', cls: 'primary', handler: function () { changeStatus(work.pid, '展示'); }});
        } else if (work.wstatus === '已驳回') {
            actions.push({label: '重新通过', cls: 'primary', handler: function () { changeStatus(work.pid, '展示'); }});
        }
        actions.push({label: '查看', cls: '', handler: function () { openDetail(work.pid); }});
        actions.push({label: '删除', cls: 'danger', handler: function () { removeWork(work); }});
        return actions;
    }

    function render() {
        rows.textContent = '';
        if (!state.list || !state.list.length) {
            var empty = document.createElement('tr');
            var cell = document.createElement('td');
            cell.colSpan = 7;
            cell.className = 'empty';
            cell.textContent = state.total ? '本页没有作品，请回到上一页' : '没有符合条件的作品';
            empty.appendChild(cell);
            rows.appendChild(empty);
            return;
        }
        state.list.forEach(function (work) {
            var row = document.createElement('tr');

            var imageCell = document.createElement('td');
            try {
                var url = new URL(work.vurl, location.origin);
                if (url.protocol === 'https:' || url.protocol === 'http:') {
                    var image = document.createElement('img');
                    image.className = 'thumb';
                    image.src = url.href;
                    image.alt = '';
                    image.loading = 'lazy';
                    imageCell.appendChild(image);
                } else {
                    imageCell.textContent = '—';
                }
            } catch (error) {
                imageCell.textContent = '—';
            }
            row.appendChild(imageCell);

            var workCell = document.createElement('td');
            var title = document.createElement('div');
            title.style.fontWeight = '600';
            title.textContent = work.wtitle || '未命名作品';
            workCell.appendChild(title);
            var pid = document.createElement('div');
            pid.className = 'row-hint';
            pid.textContent = work.pid;
            workCell.appendChild(pid);
            row.appendChild(workCell);

            var authorCell = document.createElement('td');
            authorCell.textContent = work.uname || work.uid || '未知';
            row.appendChild(authorCell);

            var categoryCell = document.createElement('td');
            categoryCell.textContent = work.pcname || '未分类';
            row.appendChild(categoryCell);

            var statusCell = document.createElement('td');
            var tag = document.createElement('span');
            tag.className = 'tag ' + AdminApi.statusClass(work.wstatus);
            tag.textContent = work.wstatus || '';
            statusCell.appendChild(tag);
            if (work.wstatus === '已驳回' && work.reason) {
                var reason = document.createElement('div');
                reason.className = 'row-hint';
                reason.textContent = '理由：' + work.reason;
                statusCell.appendChild(reason);
            }
            row.appendChild(statusCell);

            var timeCell = document.createElement('td');
            timeCell.className = 'muted num';
            timeCell.textContent = formatTime(work.cwtime);
            row.appendChild(timeCell);

            var actionCell = document.createElement('td');
            statusActions(work).forEach(function (action) {
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'btn small ' + action.cls;
                button.dataset.write = '1';
                button.textContent = action.label;
                button.style.marginRight = '4px';
                button.addEventListener('click', action.handler);
                actionCell.appendChild(button);
            });
            row.appendChild(actionCell);

            rows.appendChild(row);
        });
    }

    function renderPager() {
        var pages = Math.max(1, Math.ceil(state.total / state.size));
        document.getElementById('worksPageInfo').textContent =
            '共 ' + state.total + ' 件 · 每页 ' + state.size + ' 件';
        document.getElementById('worksPageNum').textContent = state.page + ' / ' + pages;
        document.getElementById('worksPrev').disabled = state.busy || state.page <= 1;
        document.getElementById('worksNext').disabled = state.busy || state.page >= pages;
    }

    /** 加载列表；opts.force 用于写操作后的强制重读（否则会被外层 busy 守卫拦掉）。 */
    async function load(page, opts) {
        var force = !!(opts && opts.force);
        if (state.busy && !force) {
            return;
        }
        setBusy(true);
        state.page = page || 1;
        AdminApi.feedback('正在加载作品列表…');
        var query = ['page=' + state.page, 'size=' + state.size];
        if (state.status) {
            query.push('status=' + encodeURIComponent(state.status));
        }
        if (state.keyword) {
            query.push('keyword=' + encodeURIComponent(state.keyword));
        }
        if (state.category) {
            query.push('category=' + encodeURIComponent(state.category));
        }
        try {
            var data = await AdminApi.request('/admin/work/list?' + query.join('&'));
            state.list = data.list;
            state.total = data.total;
            render();
            renderPager();
            AdminApi.feedback('');
        } catch (error) {
            if (error.status === 401 || error.status === 403) {
                return;
            }
            rows.textContent = '';
            var failed = document.createElement('tr');
            var cell = document.createElement('td');
            cell.colSpan = 7;
            cell.className = 'empty';
            cell.textContent = '加载失败：' + error.message;
            failed.appendChild(cell);
            rows.appendChild(failed);
            AdminApi.feedback('作品列表加载失败：' + error.message, 'error');
        } finally {
            setBusy(false);
        }
    }

    async function changeStatus(pid, status) {
        if (state.busy) {
            return;
        }
        setBusy(true);
        AdminApi.feedback('正在保存…');
        try {
            await AdminApi.request('/admin/work/' + encodeURIComponent(pid) + '/status', {
                method: 'PUT',
                body: {status: status}
            });
            AdminApi.feedback(status === '展示' ? '已恢复展示。' : '已隐藏。', 'success');
            await Promise.all([load(state.page, {force: true}), loadCounts()]);
        } catch (error) {
            if (error.status !== 401 && error.status !== 403) {
                AdminApi.feedback(error.status === 409
                    ? '该作品当前状态不允许此操作，请刷新后重试。'
                    : '操作失败：' + error.message, 'error');
            }
        } finally {
            setBusy(false);
        }
    }

    async function removeWork(work) {
        if (state.busy) {
            return;
        }
        if (!window.confirm('确定删除《' + (work.wtitle || work.pid) + '》吗？删除后不可恢复。')) {
            return;
        }
        setBusy(true);
        AdminApi.feedback('正在删除…');
        try {
            await AdminApi.request('/pwork/' + encodeURIComponent(work.pid), {method: 'DELETE'});
            AdminApi.feedback('已删除。', 'success');
            await Promise.all([load(state.page, {force: true}), loadCounts()]);
        } catch (error) {
            if (error.status !== 401 && error.status !== 403) {
                AdminApi.feedback('删除失败：' + error.message, 'error');
            }
        } finally {
            setBusy(false);
        }
    }

    /** 详情弹层：读只读管理详情，不触发浏览计数。 */
    async function openDetail(pid) {
        var mask = document.getElementById('detailMask');
        var hero = document.getElementById('modalHero');
        var body = document.getElementById('modalBody');
        document.getElementById('modalTitle').textContent = '作品详情';
        hero.textContent = '';
        body.textContent = '加载中…';
        mask.hidden = false;
        try {
            var packed = await AdminApi.request('/admin/work/' + encodeURIComponent(pid));
            var work = packed.work || {};
            var exif = packed.exif || {};
            document.getElementById('modalTitle').textContent = work.wtitle || '作品详情';
            hero.textContent = '';
            try {
                var url = new URL(work.vurl, location.origin);
                if (url.protocol === 'https:' || url.protocol === 'http:') {
                    var image = document.createElement('img');
                    image.src = url.href;
                    image.alt = work.wtitle || '作品大图';
                    hero.appendChild(image);
                }
            } catch (error) {
                var noImage = document.createElement('div');
                noImage.className = 'empty';
                noImage.textContent = '图片地址无效';
                hero.appendChild(noImage);
            }
            body.textContent = '';
            var meta = document.createElement('div');
            meta.style.cssText = 'display:flex;gap:10px;align-items:center;margin-bottom:8px;';
            var tag = document.createElement('span');
            tag.className = 'tag ' + AdminApi.statusClass(work.wstatus);
            tag.textContent = work.wstatus || '';
            meta.appendChild(tag);
            var authorText = document.createElement('span');
            authorText.className = 'muted';
            authorText.style.fontSize = '11.5px';
            authorText.textContent = '作者：' + ((packed.author && packed.author.nickname) || work.uname || work.uid || '未知');
            meta.appendChild(authorText);
            body.appendChild(meta);

            var desc = document.createElement('p');
            desc.className = 'desc';
            desc.textContent = work.wdesc || '（无描述）';
            body.appendChild(desc);

            var grid = document.createElement('div');
            grid.className = 'exif-grid';
            [
                ['相机', exif.camera],
                ['镜头', exif.lens],
                ['光圈', exif.aperture ? 'f/' + exif.aperture : ''],
                ['快门', exif.shutter],
                ['ISO', exif.iso],
                ['焦距', exif.focal ? exif.focal + 'mm' : '']
            ].forEach(function (pair) {
                var cell = document.createElement('div');
                var label = document.createElement('span');
                label.textContent = pair[0];
                cell.appendChild(label);
                var value = document.createElement('b');
                value.textContent = pair[1] || '—';
                cell.appendChild(value);
                grid.appendChild(cell);
            });
            body.appendChild(grid);
            if (work.reason) {
                var reason = document.createElement('p');
                reason.style.cssText = 'margin-top:12px;font-size:12px;color:var(--bad);';
                reason.textContent = '最近驳回理由：' + work.reason;
                body.appendChild(reason);
            }
        } catch (error) {
            body.textContent = '详情加载失败：' + error.message;
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.getElementById('whoName').textContent = AdminApi.displayName();
        loadCategories();
        loadCounts();
        load(1);

        document.querySelectorAll('#statusSeg button').forEach(function (button) {
            button.addEventListener('click', function () {
                if (state.busy) {
                    return;
                }
                document.querySelectorAll('#statusSeg button').forEach(function (other) {
                    other.classList.remove('active');
                });
                button.classList.add('active');
                state.status = button.dataset.status;
                load(1);
            });
        });
        document.getElementById('searchBtn').addEventListener('click', function () {
            state.keyword = document.getElementById('keyword').value.trim();
            state.category = document.getElementById('category').value;
            load(1);
        });
        document.getElementById('keyword').addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                document.getElementById('searchBtn').click();
            }
        });
        document.getElementById('resetBtn').addEventListener('click', function () {
            document.getElementById('keyword').value = '';
            document.getElementById('category').value = '';
            state.keyword = '';
            state.category = '';
            state.status = '';
            document.querySelectorAll('#statusSeg button').forEach(function (other) {
                other.classList.toggle('active', other.dataset.status === '');
            });
            load(1);
        });
        document.getElementById('worksPrev').addEventListener('click', function () {
            if (state.page > 1) {
                load(state.page - 1);
            }
        });
        document.getElementById('worksNext').addEventListener('click', function () {
            if (state.page * state.size < state.total) {
                load(state.page + 1);
            }
        });
        document.getElementById('modalClose').addEventListener('click', function () {
            document.getElementById('detailMask').hidden = true;
        });
        document.getElementById('detailMask').addEventListener('click', function (event) {
            if (event.target === event.currentTarget) {
                event.currentTarget.hidden = true;
            }
        });
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                document.getElementById('detailMask').hidden = true;
                document.getElementById('categoryMask').hidden = true;
            }
        });

        // 新增分类：成功后就地刷新下拉，不重载列表，避免打断当前筛选与翻页位置。
        var categoryMask = document.getElementById('categoryMask');
        var categoryName = document.getElementById('newCategoryName');
        var categoryTip = document.getElementById('categoryTip');
        var DEFAULT_TIP = categoryTip.textContent;

        function openCategoryModal() {
            categoryName.value = '';
            categoryTip.textContent = DEFAULT_TIP;
            categoryMask.hidden = false;
            categoryName.focus();
        }

        async function saveCategory() {
            var name = categoryName.value;
            if (!name || !name.trim() || name.length > 20) {
                categoryTip.textContent = '请输入 1-20 个字的分类名（不能全是空格）。';
                categoryName.focus();
                return;
            }
            var saveBtn = document.getElementById('categorySave');
            saveBtn.disabled = true;
            try {
                await AdminApi.request('/admin/category', {method: 'POST', body: {pcname: name}});
                categoryMask.hidden = true;
                AdminApi.feedback('分类「' + name + '」已创建', 'ok');
                await loadCategories();
            } catch (error) {
                // 重名等业务错误就地显示，不弹全局提示打断填写。
                categoryTip.textContent = error.message || '创建失败，请重试';
            } finally {
                saveBtn.disabled = false;
            }
        }

        document.getElementById('addCategoryBtn').addEventListener('click', openCategoryModal);
        document.getElementById('categoryClose').addEventListener('click', function () {
            categoryMask.hidden = true;
        });
        document.getElementById('categoryCancel').addEventListener('click', function () {
            categoryMask.hidden = true;
        });
        document.getElementById('categorySave').addEventListener('click', saveCategory);
        categoryName.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                saveCategory();
            }
        });
        categoryMask.addEventListener('click', function (event) {
            if (event.target === event.currentTarget) {
                categoryMask.hidden = true;
            }
        });
    });
}());
