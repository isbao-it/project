/* 排座纯逻辑：不读取宿主状态，随机来源只由调用方注入。 */
(function () {
  'use strict';

  const MAX_CAPACITY = 10000;
  const MAX_SEED_LENGTH = 128;

  function failure(code) {
    return { ok: false, error: { code } };
  }

  function validNameList(names) {
    return Array.isArray(names)
      && names.length > 0
      && names.every((name) => typeof name === 'string' && name.length > 0 && name.trim() === name);
  }

  /**
   * 根据文本种子创建可复现的随机数生成器。
   * @param {string} seed 原始种子文本；函数会去掉首尾空白。
   * @returns {{ok: true, data: {seed: string, rng: () => number}} | {ok: false, error: {code: string}}} 清理后的种子与独立 RNG；每个 RNG 调用都会推进自己的状态。
   */
  function createSeededRng(seed) {
    if (typeof seed !== 'string') return failure('E_SEED');
    const normalizedSeed = seed.trim();
    if (normalizedSeed.length < 1 || normalizedSeed.length > MAX_SEED_LENGTH) return failure('E_SEED');

    // 对 UTF-16 code unit 显式拆字节，确保中文与代理对在不同宿主有相同种子映射。
    let hash = 2166136261;
    for (let i = 0; i < normalizedSeed.length; i += 1) {
      const unit = normalizedSeed.charCodeAt(i);
      hash = Math.imul(hash ^ (unit & 255), 16777619) >>> 0;
      hash = Math.imul(hash ^ (unit >>> 8), 16777619) >>> 0;
    }

    let state = hash;
    const rng = function () {
      state = (state + 0x6D2B79F5) >>> 0;
      let value = state;
      value = Math.imul(value ^ (value >>> 15), value | 1);
      value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
      return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
    };
    return { ok: true, data: { seed: normalizedSeed, rng } };
  }

  /**
   * 将整份名单打乱后按行优先填入座位矩阵。
   * @param {string[]} names 去掉首尾空白后的非空姓名列表，重复姓名会保留。
   * @param {number} rows 正安全整数排数。
   * @param {number} columns 正安全整数列数。
   * @param {() => number} rng 注入的随机函数；每次洗牌调用会推进其状态。
   * @returns {{ok: true, data: {matrix: Array<Array<string|null>>, assignedCount: number, unassignedCount: number, unassignedNames: string[]}} | {ok: false, error: {code: string}}} 新矩阵、计数与未入座尾段。
   */
  function assignSeats(names, rows, columns, rng) {
    if (!validNameList(names)) return failure('E_NAMES');
    if (!Number.isSafeInteger(rows) || rows <= 0 || !Number.isSafeInteger(columns) || columns <= 0) {
      return failure('E_DIMENSIONS');
    }
    const capacity = rows * columns;
    if (!Number.isSafeInteger(capacity)) return failure('E_DIMENSIONS');
    if (capacity > MAX_CAPACITY) return failure('E_CAPACITY');
    if (typeof rng !== 'function') return failure('E_RNG');

    const shuffled = names.slice();
    for (let i = shuffled.length - 1; i > 0; i -= 1) {
      let value;
      try {
        value = rng();
      } catch (_error) {
        return failure('E_RNG');
      }
      if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value >= 1) {
        return failure('E_RNG');
      }
      const j = Math.floor(value * (i + 1));
      [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
    }

    // 先建立完整空矩阵，再按行优先写入，保证未入座时只会留在后排。
    const matrix = Array.from({ length: rows }, () => Array(columns).fill(null));
    const assignedCount = Math.min(shuffled.length, capacity);
    for (let k = 0; k < assignedCount; k += 1) {
      matrix[Math.floor(k / columns)][k % columns] = shuffled[k];
    }
    const unassignedNames = shuffled.slice(assignedCount);
    return {
      ok: true,
      data: { matrix, assignedCount, unassignedCount: unassignedNames.length, unassignedNames },
    };
  }

  /**
   * 将合法矩阵转换为包含讲台方位的 Markdown 表格。
   * @param {Array<Array<string|null>>} matrix 正矩形座位矩阵，空位用 null 表示。
   * @returns {{ok: true, data: {markdown: string}} | {ok: false, error: {code: string}}} 含末尾 LF 的 Markdown 文本。
   */
  function toMarkdown(matrix) {
    if (!Array.isArray(matrix) || matrix.length === 0 || matrix.length > MAX_CAPACITY) return failure('E_MATRIX');
    if (!Array.isArray(matrix[0]) || matrix[0].length === 0) return failure('E_MATRIX');
    const columns = matrix[0].length;
    if (columns > MAX_CAPACITY || matrix.length * columns > MAX_CAPACITY) return failure('E_MATRIX');
    for (const row of matrix) {
      if (!Array.isArray(row) || row.length !== columns) return failure('E_MATRIX');
      for (const cell of row) {
        if (cell !== null && (typeof cell !== 'string' || cell.length === 0 || cell.trim() !== cell)) {
          return failure('E_MATRIX');
        }
      }
    }

    const headers = Array.from({ length: columns }, (_, index) => `第${index + 1}列`);
    const lines = [
      '讲台（座位表上方）',
      '',
      `| 排/列 | ${headers.join(' | ')} |`,
      `| ${Array(columns + 1).fill('---').join(' | ')} |`,
    ];
    for (let r = 0; r < matrix.length; r += 1) {
      const cells = matrix[r].map((cell) => {
        if (cell === null) return '空';
        return cell
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/\\/g, '\\\\')
          .replace(/\|/g, '\\|')
          .replace(/\r\n|\r|\n|\t/g, ' ');
      });
      lines.push(`| 第${r + 1}排 | ${cells.join(' | ')} |`);
    }
    return { ok: true, data: { markdown: `${lines.join('\n')}\n` } };
  }

  const api = { createSeededRng, assignSeats, toMarkdown };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else globalThis.SeatCore = api;
})();
