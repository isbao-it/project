(function (root) {
  'use strict';

  const MAX_BYTES = 10485760;
  const NAME_HEADERS = ['姓名', '学生姓名', '名字', 'name', 'student name', 'student_name'];
  const OTHER_HEADERS = ['学号', '序号', '编号', 'id', 'student id', 'student_id'];
  const FORMATS = ['xlsx', 'csv'];
  const ERRORS = ['E_FORMAT', 'E_CONTENT', 'E_TOO_LARGE', 'E_SIGNATURE', 'E_PARSE', 'E_TABLE', 'E_COLUMN', 'E_EMPTY'];
  const DECISIONS = ['USE_FIRST', 'SELECT_COLUMN'];
  const REASONS = ['EMPTY', 'HEADER_NOT_NAME', 'VALUE_NOT_NAME', 'NAME_LIKE'];
  const success = (data) => ({ ok: true, data });
  const failure = (code) => ({ ok: false, error: { code } });
  const hasOwn = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
  const normalizeHeader = (value) => value.trim().toLowerCase();
  const isAlias = (value, aliases) => aliases.includes(normalizeHeader(value));

  function calculateHeader(rows) {
    return Array.isArray(rows[0]) && rows[0].some((cell) =>
      typeof cell === 'string' && (isAlias(cell, NAME_HEADERS) || isAlias(cell, OTHER_HEADERS)));
  }

  function validateTable(table) {
    if (!table || typeof table !== 'object' || !Array.isArray(table.rows) || !FORMATS.includes(table.sourceFormat)) return false;
    if (!table.rows.every((row) => Array.isArray(row) && row.every((cell) => typeof cell === 'string'))) return false;
    const columns = table.rows.reduce((max, row) => Math.max(max, row.length), 0);
    return table.columnCount === columns && table.hasHeaderRow === calculateHeader(table.rows);
  }

  /**
   * 将用户提供的 xlsx 或 UTF-8 csv 内容转换成未清洗的二维字符串表。
   * @param {ArrayBuffer|string} content 文件字节或 CSV 文本。
   * @param {{format:string,xlsx:object}} options 文件格式及注入的 SheetJS 端口；本函数会调用该端口。
   * @returns {{ok:true,data:object}|{ok:false,error:{code:string}}} 返回保留行列形状的表或固定错误码。
   */
  function parseTable(content, options) {
    if (!options || typeof options !== 'object' || !FORMATS.includes(options.format) || !options.xlsx || typeof options.xlsx !== 'object') return failure('E_FORMAT');
    const format = options.format;
    let text;
    let bytes;
    if (format === 'xlsx') {
      if (!(content instanceof ArrayBuffer)) return failure('E_CONTENT');
      bytes = content.byteLength;
    } else {
      if (typeof content !== 'string') return failure('E_CONTENT');
      text = content;
      bytes = new TextEncoder().encode(text).byteLength;
    }
    if (bytes > MAX_BYTES) return failure('E_TOO_LARGE');
    if (format === 'xlsx') {
      if (bytes < 4) return failure('E_SIGNATURE');
      const view = new Uint8Array(content, 0, 4);
      if (view[0] !== 0x50 || view[1] !== 0x4b || view[2] !== 0x03 || view[3] !== 0x04) return failure('E_SIGNATURE');
    } else if (text.charCodeAt(0) === 0xfeff) {
      text = text.slice(1);
    }
    const xlsx = options.xlsx;
    if (typeof xlsx.read !== 'function' || !xlsx.utils || typeof xlsx.utils.sheet_to_json !== 'function') return failure('E_PARSE');
    try {
      const workbook = format === 'xlsx'
        ? xlsx.read(content, { type: 'array', cellDates: false })
        : xlsx.read(text, { type: 'string', raw: true });
      if (!workbook || !Array.isArray(workbook.SheetNames) || workbook.SheetNames.length === 0 ||
          !workbook.SheetNames.every((name) => typeof name === 'string') ||
          !workbook.Sheets || typeof workbook.Sheets !== 'object' ||
          !hasOwn(workbook.Sheets, workbook.SheetNames[0]) || !workbook.Sheets[workbook.SheetNames[0]] ||
          typeof workbook.Sheets[workbook.SheetNames[0]] !== 'object') return failure('E_PARSE');
      const rawRows = xlsx.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], { header: 1, raw: false, defval: '' });
      if (!Array.isArray(rawRows)) return failure('E_TABLE');
      const sourceRows = format === 'csv' && text === '' && rawRows.length === 1 && Array.isArray(rawRows[0]) && rawRows[0].length === 1 && rawRows[0][0] === '' ? [] : rawRows;
      const rows = [];
      for (const rawRow of sourceRows) {
        if (!Array.isArray(rawRow)) return failure('E_TABLE');
        const row = [];
        for (const value of rawRow) {
          if (value === null || value === undefined) row.push('');
          else if (typeof value === 'string') row.push(value);
          else if (typeof value === 'number' && Number.isFinite(value)) row.push(String(value));
          else if (typeof value === 'boolean') row.push(String(value));
          else return failure('E_TABLE');
        }
        rows.push(row);
      }
      return success({ rows, columnCount: rows.reduce((max, row) => Math.max(max, row.length), 0), hasHeaderRow: calculateHeader(rows), sourceFormat: format });
    } catch (_error) {
      return failure('E_PARSE');
    }
  }

  /**
   * 根据首列表头和最多五个非空值，为导入界面提供默认列建议。
   * @param {object} table parseTable 产生的表；只读取其中的行与表头标志。
   * @returns {{ok:true,data:{decision:string,reason:string,sample:string[]}}|{ok:false,error:{code:string}}} 返回建议和样本，或表格错误码。
   */
  function inspectFirstColumn(table) {
    if (!validateTable(table)) return failure('E_TABLE');
    const sample = [];
    if (table.columnCount > 0) {
      for (let index = table.hasHeaderRow ? 1 : 0; index < table.rows.length && sample.length < 5; index += 1) {
        const value = table.rows[index][0] ?? '';
        const trimmed = value.trim();
        if (trimmed) sample.push(trimmed);
      }
    }
    if (table.columnCount === 0 || sample.length === 0) return success({ decision: 'SELECT_COLUMN', reason: 'EMPTY', sample });
    const first = table.rows[0]?.[0] ?? '';
    if (table.hasHeaderRow && (!isAlias(first, NAME_HEADERS) || isAlias(first, OTHER_HEADERS))) return success({ decision: 'SELECT_COLUMN', reason: 'HEADER_NOT_NAME', sample });
    const chinese = /^[\u3400-\u4DBF\u4E00-\u9FFF]+(?:[·•][\u3400-\u4DBF\u4E00-\u9FFF]+)*$/;
    const latin = /^[A-Za-z]+(?:[ '-][A-Za-z]+)*$/;
    const nameLike = (value) => {
      const size = value.length;
      return !NAME_HEADERS.includes(normalizeHeader(value)) && !OTHER_HEADERS.includes(normalizeHeader(value)) &&
        ((size >= 2 && size <= 12 && chinese.test(value)) || (size >= 2 && size <= 60 && latin.test(value)));
    };
    return sample.every(nameLike)
      ? success({ decision: 'USE_FIRST', reason: 'NAME_LIKE', sample })
      : success({ decision: 'SELECT_COLUMN', reason: 'VALUE_NOT_NAME', sample });
  }

  /**
   * 从用户选择的列提取名单，仅清理首尾空白并跳过空值。
   * @param {object} table parseTable 产生的表；输入行不会被修改。
   * @param {{columnIndex:number}} options 用户选择的零起始列。
   * @returns {{ok:true,data:{names:string[],columnIndex:number,hasHeaderRow:boolean}}|{ok:false,error:{code:string}}} 返回新姓名数组或固定错误码。
   */
  function parseRoster(table, options) {
    if (!validateTable(table)) return failure('E_TABLE');
    if (table.columnCount === 0) return failure('E_EMPTY');
    if (!options || typeof options !== 'object' || !Number.isInteger(options.columnIndex) ||
        options.columnIndex < 0 || options.columnIndex >= table.columnCount) return failure('E_COLUMN');
    const names = [];
    for (let index = table.hasHeaderRow ? 1 : 0; index < table.rows.length; index += 1) {
      const value = table.rows[index][options.columnIndex] ?? '';
      const name = value.trim();
      if (name) names.push(name);
    }
    return names.length ? success({ names, columnIndex: options.columnIndex, hasHeaderRow: table.hasHeaderRow }) : failure('E_EMPTY');
  }

  const api = { parseTable, inspectFirstColumn, parseRoster };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ParseCore = api;
})(globalThis);


