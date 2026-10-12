# 第三方组件与许可

本目录收录本项目随附分发的第三方组件许可与声明。**若上游许可与本声明冲突，以上游为准。**

| 组件 | 版本 | 上游 | 许可 | 许可全文 |
| --- | :-: | --- | :-: | --- |
| SheetJS Community Edition（`xlsx`） | 0.20.3 | https://github.com/SheetJS/sheetjs | Apache-2.0 | [`LICENSE-SheetJS-Apache-2.0.txt`](LICENSE-SheetJS-Apache-2.0.txt) |

## SheetJS（`vendor/xlsx.full.min.js`）

- 本仓库 `vendor/xlsx.full.min.js` 是 **SheetJS 社区版 0.20.3** 的官方构建产物（`XLSX.version === "0.20.3"`），下载自
  `https://cdn.sheetjs.com/xlsx-0.20.3/package/dist/xlsx.full.min.js`；文件头保留其原始版权声明
  （`/*! xlsx.js (C) 2013-present SheetJS -- http://sheetjs.com */`）。
- 以 **Apache License 2.0** 分发，随附上游 LICENSE 全文（`LICENSE-SheetJS-Apache-2.0.txt`，逐字取自
  `https://raw.githubusercontent.com/SheetJS/sheetjs/master/LICENSE`，其末尾载有 `Copyright (C) 2012-present SheetJS LLC`）。
- 用途：浏览器侧解析 `.xlsx` / `.csv`，**仅作为本地副本**离线加载，不做任何修改（未改动、未压缩二次加工）。
- 版权归 SheetJS LLC 所有。
