// 直接执行小程序请求/上传回调和守卫，防止字符串成功码再次失配。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../../..');
const mini = path.join(root, 'WeChat Mini Program/pg');
const guard = path.join(root, 'Backend/scripts/assert-frontend-code.sh');

for (const [statusCode, code, succeeds] of [
  [200, 'ok', true], [200, 200, false],
  [200, 'invalid_request', false], [500, 'ok', false]
]) {
  test(`请求入口：HTTP ${statusCode}，业务码 ${JSON.stringify(code)}`, async () => {
    const body = { code, msg: '测试响应', data: { uid: 'test' } };
    const sandbox = {
      module: { exports: {} },
      getApp: () => ({ globalData: { baseUrl: 'https://example.test' }, getAuthHeader: () => ({}) }),
      wx: {
        showLoading() {}, hideLoading() {}, showToast() {},
        request(options) { options.success({ statusCode, data: body }); }
      }
    };
    vm.runInNewContext(fs.readFileSync(path.join(mini, 'utils/request.js'), 'utf8'), sandbox);
    const result = sandbox.module.exports.request('/user/test');
    if (succeeds) assert.equal(await result, body);
    else await assert.rejects(result, error => error === body);
  });
}

for (const [code, succeeds] of [['ok', true], [200, false], ['invalid_request', false]]) {
  test(`头像上传：业务码 ${JSON.stringify(code)}`, () => {
    const updates = [];
    const toasts = [];
    let page;
    const sandbox = {
      require: () => ({ request() {} }),
      getApp: () => ({ globalData: { baseUrl: 'https://example.test' } }),
      Page(value) { page = value; },
      wx: {
        showLoading() {}, hideLoading() {}, showToast(value) { toasts.push(value); },
        uploadFile(options) {
          options.success({ data: JSON.stringify({ code, data: '/avatar.png', msg: '失败' }) });
        }
      }
    };
    vm.runInNewContext(fs.readFileSync(path.join(mini, 'pages/userEdit/userEdit.js'), 'utf8'), sandbox);
    page.setData = value => updates.push(value);
    page.uploadAvatar('/local.png');
    assert.equal(updates.length, succeeds ? 1 : 0);
    assert.equal(toasts[0].icon, succeeds ? 'success' : 'none');
    if (succeeds) assert.equal(updates[0]['form.avatar'], '/avatar.png');
  });
}

for (const [label, source, exitCode] of [
  ['字符串码与 HTTP 状态', "res.data.code === 'ok'; res.statusCode === 200;", 0],
  ['严格数字比较', 'res.data.' + 'code' + '===200;', 1],
  ['宽松数字比较', 'res.data.' + 'code' + ' == 200;', 1]
]) {
  test(`守卫：${label}`, () => {
    assert.ok(fs.existsSync(guard), '缺少前端成功码守卫');
    const fixture = fs.mkdtempSync(path.join(root, '_Outputs/待审核/01/R04/details/guard-fixture-'));
    const script = path.join(fixture, 'Backend/scripts/assert-frontend-code.sh');
    fs.mkdirSync(path.dirname(script), { recursive: true });
    fs.copyFileSync(guard, script);
    fs.writeFileSync(path.join(fixture, 'client.js'), source);
    const result = spawnSync('bash', [script.replaceAll('\\', '/')], { cwd: root, encoding: 'utf8' });
    assert.ifError(result.error);
    assert.equal(result.status, exitCode, result.stdout + result.stderr);
    assert.match(result.stdout, exitCode === 0 ? /PASS/ : /client\.js:1:.*\nFAIL/);
  });
}
