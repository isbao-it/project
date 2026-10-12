import { describe, expect, it } from 'vitest';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const require = createRequire(import.meta.url);

function loadSeat() {
  return require('../seat.js');
}

function seats(names, rows, columns, rng = () => 0) {
  return loadSeat().assignSeats(names, rows, columns, rng);
}

describe('SeatCore', () => {
  it('BG-01/rng_vector_zero', () => {
    const { createSeededRng } = loadSeat();
    const result = createSeededRng(' 0 ');
    expect(result).toEqual({ ok: true, data: { seed: '0', rng: expect.any(Function) } });
    expect(Array.from({ length: 3 }, () => result.data.rng())).toEqual(
      [3828474694, 835006876, 4044406313].map((n) => n / 4294967296),
    );
  });

  it('BG-01/rng_utf16_independent', () => {
    const { createSeededRng } = loadSeat();
    const a = createSeededRng('林🌟').data.rng;
    const b = createSeededRng('林🌟').data.rng;
    const aValues = [a(), a(), a()];
    const bValues = [b(), b(), b()];
    expect(aValues).toEqual([3061555433, 4025560124, 3261750186].map((n) => n / 4294967296));
    expect(bValues).toEqual(aValues);
  });

  it('BG-01/seed_domain', () => {
    const { createSeededRng } = loadSeat();
    for (const seed of [undefined, null, 0, '', '  ', 'x'.repeat(129)]) {
      expect(createSeededRng(seed)).toEqual({ ok: false, error: { code: 'E_SEED' } });
    }
    expect(createSeededRng('x'.repeat(128)).ok).toBe(true);
    expect(createSeededRng('  0  ').data.seed).toBe('0');
  });

  it('BG-01/same_seed_matrix', () => {
    const { createSeededRng } = loadSeat();
    const names = ['林星禾', '周云岚', '沈竹安', '许月舟', '顾松宁', '叶晴川', '江墨雨', '苏若溪'];
    const before = [...names];
    const a = seats(names, 4, 2, createSeededRng('0').data.rng);
    const b = seats(names, 4, 2, createSeededRng('0').data.rng);
    expect(a).toEqual(b);
    expect(a.data).toMatchObject({ assignedCount: 8, unassignedCount: 0, unassignedNames: [] });
    expect(names).toEqual(before);
  });

  it('BG-02/seed_sample_32', () => {
    const { createSeededRng } = loadSeat();
    const names = ['林星禾', '周云岚', '沈竹安', '许月舟', '顾松宁', '叶晴川', '江墨雨', '苏若溪'];
    const matrices = new Set();
    for (let i = 0; i < 32; i += 1) {
      matrices.add(JSON.stringify(seats(names, 4, 2, createSeededRng(`sample-${String(i).padStart(2, '0')}`).data.rng).data.matrix));
    }
    expect(matrices.size).toBeGreaterThanOrEqual(24);
  });

  it('BG-03/capacity_48_24_50', () => {
    const rng = () => 0.999999;
    const makeNames = (n) => Array.from({ length: n }, (_, i) => `同学${String(i + 1).padStart(2, '0')}`);
    const full = seats(makeNames(48), 6, 8, rng).data;
    expect(full.matrix.flat().every(Boolean)).toBe(true);
    expect(full.unassignedCount).toBe(0);
    const half = seats(makeNames(24), 6, 8, rng).data;
    expect(half.matrix.slice(0, 3).flat().every(Boolean)).toBe(true);
    expect(half.matrix.slice(3).flat()).toEqual(Array(24).fill(null));
    const excess = seats(makeNames(50), 6, 8, rng).data;
    expect(excess).toMatchObject({ assignedCount: 48, unassignedCount: 2, unassignedNames: ['同学49', '同学50'] });
  });

  it('BG-04/shuffle_injected_front', () => {
    const names = ['林星禾', '周云岚', '沈竹安'];
    const before = [...names];
    let calls = 0;
    const result = seats(names, 2, 2, () => { calls += 1; return 0; });
    expect(result.data).toMatchObject({ matrix: [['周云岚', '沈竹安'], ['林星禾', null]], assignedCount: 3, unassignedCount: 0, unassignedNames: [] });
    expect(calls).toBe(2);
    expect(names).toEqual(before);
  });

  it('BG-04/duplicate_single_overflow', () => {
    const duplicated = seats(['林星禾', '林星禾', '周云岚'], 1, 3, () => 0.999999).data;
    expect(duplicated.matrix.flat().filter((n) => n === '林星禾')).toHaveLength(2);
    let calls = 0;
    expect(seats(['林星禾'], 1, 1, () => { calls += 1; return 0; }).data.matrix).toEqual([['林星禾']]);
    expect(calls).toBe(0);
    calls = 0;
    const overflow = seats(['林星禾', '周云岚', '沈竹安'], 1, 1, () => { calls += 1; return 0; }).data;
    expect(overflow).toMatchObject({ matrix: [['周云岚']], unassignedNames: ['沈竹安', '林星禾'] });
    expect(calls).toBe(2);
  });

  it('BG-05/names_bad_priority', () => {
    for (const names of [null, [], [1], [''], [' '], [' 林星禾']]) {
      let calls = 0;
      expect(seats(names, 0, 1, () => { calls += 1; return 1; })).toEqual({ ok: false, error: { code: 'E_NAMES' } });
      expect(calls).toBe(0);
    }
  });

  it('BG-05/dimensions_capacity', () => {
    for (const [rows, columns] of [[0, 1], [-1, 1], [1.5, 1], [NaN, 1], [Infinity, 1], ['1', 1], [Number.MAX_SAFE_INTEGER, 2], [Number.MAX_SAFE_INTEGER * 2, 1]]) {
      let calls = 0;
      expect(seats(['林星禾'], rows, columns, () => { calls += 1; return 0; })).toEqual({ ok: false, error: { code: 'E_DIMENSIONS' } });
      expect(calls).toBe(0);
    }
    expect(seats(['林星禾'], 1, 10000).ok).toBe(true);
    expect(seats(['林星禾'], 1, 10001)).toEqual({ ok: false, error: { code: 'E_CAPACITY' } });
  });

  it('BG-06/rng_faults', () => {
    const faults = [null, () => -0.1, () => 1, () => NaN, () => Infinity, () => '0', () => undefined, () => { throw new Error('虚构敏感标记'); }];
    for (const rng of faults) {
      const names = ['林星禾', '周云岚'];
      const before = [...names];
      const result = seats(names, 1, 2, rng);
      expect(result).toEqual({ ok: false, error: { code: 'E_RNG' } });
      expect(JSON.stringify(result)).not.toContain('虚构敏感标记');
      expect(names).toEqual(before);
    }
    expect(seats(['林星禾', '周云岚'], 1, 2, () => 0).ok).toBe(true);
    expect(seats(['林星禾', '周云岚'], 1, 2, () => 0.999999).ok).toBe(true);
  });

  it('BG-07/markdown_exact', () => {
    const { toMarkdown } = loadSeat();
    expect(toMarkdown([['林星禾', '周云岚', null], ['沈竹安', null, '空']])).toEqual({
      ok: true,
      data: { markdown: '讲台（座位表上方）\n\n| 排/列 | 第1列 | 第2列 | 第3列 |\n| --- | --- | --- | --- |\n| 第1排 | 林星禾 | 周云岚 | 空 |\n| 第2排 | 沈竹安 | 空 | 空 |\n' },
    });
  });

  it('BG-07/markdown_escape', () => {
    const { toMarkdown } = loadSeat();
    const matrix = [['林&<>\\星|禾\r\n月\r舟\n晴\t川', null, '空']];
    const before = structuredClone(matrix);
    expect(toMarkdown(matrix)).toEqual({
      ok: true,
      data: { markdown: '讲台（座位表上方）\n\n| 排/列 | 第1列 | 第2列 | 第3列 |\n| --- | --- | --- | --- |\n| 第1排 | 林&amp;&lt;&gt;\\\\星\\|禾 月 舟 晴 川 | 空 | 空 |\n' },
    });
    expect(matrix).toEqual(before);
  });

  it('BG-08/matrix_bad', () => {
    const { toMarkdown } = loadSeat();
    for (const matrix of [[], [[]], [['林星禾'], [null, null]], [null], [['']], [[undefined]], [[1]], [[' 林星禾']]]) {
      expect(toMarkdown(matrix)).toEqual({ ok: false, error: { code: 'E_MATRIX' } });
    }
    expect(toMarkdown([Array(10001).fill(null)])).toEqual({ ok: false, error: { code: 'E_MATRIX' } });
    expect(toMarkdown([Array(10000).fill(null)]).ok).toBe(true);
  });

  it('BG-08/dual_exports', () => {
    const api = loadSeat();
    expect(Object.keys(api).sort()).toEqual(['assignSeats', 'createSeededRng', 'toMarkdown']);
    const context = { globalThis: {} };
    vm.runInNewContext(readFileSync(require.resolve('../seat.js'), 'utf8'), context);
    expect(Object.keys(context.globalThis.SeatCore).sort()).toEqual(['assignSeats', 'createSeededRng', 'toMarkdown']);
  });
});
