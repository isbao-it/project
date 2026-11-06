/**
 * 概览页：只请求一个 /admin/stats，不拉分页接口自行聚合。
 * 所有文本经 AdminApi.escapeHtml 后写入，作品标题/作者名不当作 HTML。
 */
(function () {
    'use strict';

    var STATUS_LABELS = [
        {key: 'pending', name: '待审核', cls: ''},
        {key: 'visible', name: '展示', cls: 'show'},
        {key: 'hidden', name: '隐藏', cls: 'hide'},
        {key: 'rejected', name: '已驳回', cls: 'reject'}
    ];

    /** 填充一个 KPI 数字，末尾单位单独用小字。 */
    function setKpi(id, value, unit) {
        var box = document.getElementById(id);
        if (!box) {
            return;
        }
        box.textContent = fmt(value);
        var small = document.createElement('small');
        small.textContent = unit;
        box.appendChild(small);
    }

    /** 千分位格式化，避免 1204 这种数字难以速读。 */
    function fmt(value) {
        var number = Number(value) || 0;
        return number.toLocaleString('zh-CN');
    }

    /** 百分比：分母为 0 时显示 0%，不做 0/0 或 NaN 输出。 */
    function percent(count, total) {
        if (!total) {
            return '0%';
        }
        return Math.round(count * 100 / total) + '%';
    }

    /** 侧栏待审角标：与概览数字同源，避免两处口径不一致。 */
    function renderNavCount(pending) {
        var badge = document.getElementById('navPendingCount');
        if (!badge) {
            return;
        }
        badge.textContent = pending;
        badge.hidden = !pending;
    }

    function renderStatus(data) {
        var box = document.getElementById('statusBars');
        var counts = data.statusCounts || {};
        var total = Number(data.workCount) || 0;
        box.textContent = '';
        STATUS_LABELS.forEach(function (item) {
            var count = Number(counts[item.key]) || 0;
            var row = document.createElement('div');
            row.className = 'bar-row';
            row.innerHTML = '<div class="nm">' + item.name + '</div>'
                + '<div class="track"><div class="fill ' + item.cls + '" style="width:'
                + (total ? Math.max(2, Math.round(count * 100 / total)) : 0) + '%"></div></div>'
                + '<div class="num">' + fmt(count) + '</div>';
            box.appendChild(row);
        });
        document.getElementById('statusTotal').textContent = '共 ' + fmt(total) + ' 件';
    }

    function renderCategories(data) {
        var box = document.getElementById('categoryBars');
        var list = data.categoryCounts || [];
        var total = Number(data.workCount) || 0;
        box.textContent = '';
        if (!list.length) {
            var empty = document.createElement('div');
            empty.className = 'empty';
            empty.textContent = '暂无分类数据';
            box.appendChild(empty);
            return;
        }
        list.forEach(function (item) {
            var count = Number(item.count) || 0;
            var row = document.createElement('div');
            row.className = 'row';
            row.innerHTML = '<div class="nm" title="' + AdminApi.escapeHtml(item.pcname) + '">'
                + AdminApi.escapeHtml(item.pcname) + '</div>'
                + '<div class="track"><div class="fill" style="width:'
                + (total ? Math.max(2, Math.round(count * 100 / total)) : 0) + '%"></div></div>'
                + '<div class="pc">' + percent(count, total) + '</div>';
            box.appendChild(row);
        });
        document.getElementById('categoryTotal').textContent = '共 ' + fmt(total) + ' 件';
    }

    function renderRecent(data) {
        var body = document.getElementById('recentRows');
        var list = data.recentPending || [];
        body.textContent = '';
        if (!list.length) {
            var emptyRow = document.createElement('tr');
            var emptyCell = document.createElement('td');
            emptyCell.colSpan = 5;
            emptyCell.className = 'empty';
            emptyCell.textContent = '当前没有待审核作品';
            emptyRow.appendChild(emptyCell);
            body.appendChild(emptyRow);
            return;
        }
        list.forEach(function (work) {
            var row = document.createElement('tr');
            var imageCell = document.createElement('td');
            // 图片地址只接受同源或 http(s)，不把作品数据当 HTML 注入。
            try {
                var url = new URL(work.vurl, location.origin);
                if (url.protocol === 'https:' || url.protocol === 'http:') {
                    var image = document.createElement('img');
                    image.className = 'thumb';
                    image.src = url.href;
                    image.alt = work.wtitle || '待审核作品';
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
            var link = document.createElement('a');
            link.href = '/page/admin/audit?pid=' + encodeURIComponent(work.pid);
            link.textContent = '去审核';
            link.style.cssText = 'font-size:11px;color:var(--brand);margin-top:3px;display:inline-block;';
            workCell.appendChild(link);
            row.appendChild(workCell);

            var authorCell = document.createElement('td');
            authorCell.textContent = work.uname || work.uid || '未知';
            row.appendChild(authorCell);

            var categoryCell = document.createElement('td');
            categoryCell.textContent = work.pcname || '未分类';
            row.appendChild(categoryCell);

            var timeCell = document.createElement('td');
            timeCell.className = 'muted num';
            timeCell.textContent = formatTime(work.cwtime);
            row.appendChild(timeCell);
            body.appendChild(row);
        });
    }

    /** 后端已按 +08:00 输出，这里只做「去掉秒」的展示裁剪，不改时区。 */
    function formatTime(value) {
        if (!value) {
            return '';
        }
        return String(value).replace('T', ' ').replace(/:(\d{2})(\+|$)/, '$2').slice(0, 16);
    }

    function render(data) {
        setKpi('kpiPending', data.pendingCount, '件');
        setKpi('kpiToday', data.todayApprovedCount, '件');
        setKpi('kpiUsers', data.userCount, '人');
        setKpi('kpiWorks', data.workCount, '件');
        var counts = data.statusCounts || {};
        document.getElementById('kpiBreakdown').textContent =
            '展示 ' + fmt(counts.visible) + ' · 隐藏 ' + fmt(counts.hidden)
            + ' · 驳回 ' + fmt(counts.rejected);
        renderNavCount(data.pendingCount);
        renderStatus(data);
        renderCategories(data);
        renderRecent(data);
    }

    async function load() {
        AdminApi.feedback('正在加载概览数据…');
        try {
            var data = await AdminApi.request('/admin/stats');
            render(data);
            AdminApi.feedback('');
        } catch (error) {
            if (error.status === 401 || error.status === 403) {
                return;
            }
            AdminApi.feedback('概览加载失败：' + (error.message || '请稍后刷新重试。'), 'error');
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.getElementById('whoName').textContent = AdminApi.displayName();
        load();
    });
}());
