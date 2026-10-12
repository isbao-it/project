# 教室座位随机排座（zuoweipaixu）

老师上传学生名单（`.xlsx` / `.csv`）→ 输入教室行列 → 一键生成「**前排优先坐满 + 随机 + 可复现**」的座位表。讲台固定在座位表上方，结果可复制成 Markdown、可打印。

## 特点

- **离线单页工具**：双击 `index.html` 就能用 —— 无后端、无构建、无网络请求（SheetJS 已随仓库附带本地副本）
- **种子可复现**：相同种子 + 相同名单 + 相同行列 → 座位表**逐字一致**（种子驱动 RNG，全程不用 `Math.random()`）
- **前排优先**：从第 1 排第 1 列开始填，填满一排再填下一排；人少时空位留在最后几排；人多了提示「有 N 人未分配」
- **逻辑与界面分离**：`seat.js` / `parse.js` 是纯函数模块（不碰 DOM / 不做 IO / 不读时钟），可独立单测
- **安全渲染**：一律 `textContent` / `createElement`，无 `innerHTML`；错误提示不回显名单原文

## 快速开始

1. 克隆本仓库，进入 `zuoweipaixu/` 目录
2. **双击 `index.html`**（Chrome / Edge 均可）
3. 上传名单 —— `试用表格/` 里有 13 份现成样例（含 6×8 全满、前三排满、超员 2 人、空表、坏文件、特殊字符等场景）
4. 填「行数 / 列数」，可选填「随机种子」→ 生成座位表
5. 「复制 Markdown」贴进文档；「打印」只输出讲台与座位表（输入区自动隐藏）

## 目录结构

```
index.html                 页面装配与样式（含 @media print 规则）
app.js                     界面层：状态机、渲染、剪贴板、打印
seat.js                    纯逻辑：种子 RNG、前排优先填充、Markdown 生成
parse.js                   纯逻辑：二维表 → 姓名列判定 → 名单清洗
vendor/xlsx.full.min.js    SheetJS 0.20.3 本地副本（Apache-2.0，见 THIRD-PARTY-LICENSES/）
tests/                     vitest 单测（seat / parse）+ 真实 xlsx/csv 夹具
试用表格/                  13 份虚构姓名样例 + 使用说明
THIRD-PARTY-LICENSES/      第三方许可与声明
```

## 表格导入规则

- 支持 `.xlsx`（主路径）与 `.csv`，只读**第一个工作表**
- 默认取**第一列**；若第一列不像姓名（例如学号），会列出各列 + 前 5 行预览让你选
- 首行若是「姓名 / 学生姓名 / 名字」等表头，**自动跳过**；同时跳过空行、去掉每格首尾空格
- 重复姓名**原样保留**（不去重）
- 一份有效名单都读不到时，提示「未读取到学生名单」

## 开发与测试

```bash
npm install
npx --no-install vitest run tests/          # 23 个用例
npx --no-install vitest run --coverage      # 覆盖率（seat.js / parse.js 四项各 ≥90%）
```

约束：逻辑层**禁** `document` / `window` / `localStorage` / `Date.now()` / `Math.random()` / 网络 / 文件系统 —— RNG 与数据一律由参数注入，所以能脱离浏览器直接单测。

## 样例表格

见 [`试用表格/README.md`](试用表格/README.md)：每份文件对应一个验收场景，文件名里已写明预期结果。

## 许可

本项目随合集仓库 `isbao-it/project` 采用 **MIT** 许可（见仓库根 `LICENSE`）。随附的第三方组件许可见 [`THIRD-PARTY-LICENSES/`](THIRD-PARTY-LICENSES/NOTICE.md)。
