// vitest 基线配置（派工方预置，两片共用；工人只跑定向用例，覆盖率由 Hermes 同快照复核）
// 为什么用 CJS：package.json 无 "type": "module"，浏览器侧走经典脚本 + 逻辑文件双导出（module.exports / globalThis）
module.exports = {
  test: {
    environment: 'node',
    include: ['tests/**/*.test.js'],
    coverage: {
      provider: 'v8',
      include: ['seat.js', 'parse.js'],
      reporter: ['text', 'json-summary'],
      // 覆盖率门槛由 Hermes 复核时判定（每条 ≥90%），不在此处 fail-fast，避免工人被门槛误导着改断言
      all: false,
    },
  },
}
