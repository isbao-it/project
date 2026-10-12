import { describe, it, expect, beforeAll, vi } from 'vitest';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const XLSX = require('../vendor/xlsx.full.min.js');
XLSX.set_fs(fs);
const fixtureDir = path.join(__dirname, 'fixtures', 'parse');
const loadCore = () => require('../parse.js');
const makePort = (convert = [['虚构甲']]) => ({
  read: vi.fn(() => ({ SheetNames: ['第一表'], Sheets: { 第一表: {} } })),
  utils: { sheet_to_json: vi.fn(() => convert) },
});
const raw = (rows, sourceFormat = 'csv', hasHeaderRow) => ({
  rows, columnCount: Math.max(0, ...rows.map((row) => row.length)), sourceFormat,
  hasHeaderRow: hasHeaderRow ?? rows[0]?.some((cell) => ['姓名','学生姓名','名字','name','student name','student_name','学号','序号','编号','id','student id','student_id'].includes(String(cell).trim().toLowerCase())) ?? false,
});
const failed = (result, code) => expect(result).toEqual({ ok: false, error: { code } });
let core;
beforeAll(() => {
  fs.mkdirSync(fixtureDir, { recursive: true });
  XLSX.writeFile({ SheetNames: ['名单'], Sheets: { 名单: XLSX.utils.aoa_to_sheet([['学号','姓名'],['900001','林星禾'],['900002','周云岚']]) } }, path.join(fixtureDir, 'two-column.xlsx'));
  fs.writeFileSync(path.join(fixtureDir, 'single-column.csv'), '林星禾\n周云岚\n', 'utf8');
  XLSX.writeFile({ SheetNames: ['空表'], Sheets: { 空表: XLSX.utils.aoa_to_sheet([]) } }, path.join(fixtureDir, 'empty.xlsx'));
  core = loadCore();
});

describe('ParseCore', () => {
 it('BG-01/real_xlsx_two_columns', () => {
  const bytes = fs.readFileSync(path.join(fixtureDir,'two-column.xlsx'));
  const content = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const table = core.parseTable(content, { format:'xlsx', xlsx:XLSX });
  expect(table).toEqual({ok:true,data:{rows:[['学号','姓名'],['900001','林星禾'],['900002','周云岚']],columnCount:2,hasHeaderRow:true,sourceFormat:'xlsx'}});
  expect(core.inspectFirstColumn(table.data)).toEqual({ok:true,data:{decision:'SELECT_COLUMN',reason:'HEADER_NOT_NAME',sample:['900001','900002']}});
  expect(core.parseRoster(table.data,{columnIndex:1})).toEqual({ok:true,data:{names:['林星禾','周云岚'],columnIndex:1,hasHeaderRow:true}});
 });
 it('BG-02/real_csv_single', () => {
  const table=core.parseTable(fs.readFileSync(path.join(fixtureDir,'single-column.csv'),'utf8'),{format:'csv',xlsx:XLSX});
  expect(table.ok).toBe(true); expect(table.data.sourceFormat).toBe('csv'); expect(table.data.columnCount).toBe(1);
  expect(core.parseRoster(table.data,{columnIndex:0})).toEqual({ok:true,data:{names:['林星禾','周云岚'],columnIndex:0,hasHeaderRow:false}});
  const text='"林,星禾"\n"周""云岚"\n"沈\n竹安"\n';
  const quoted=core.parseTable(text,{format:'csv',xlsx:XLSX});
  expect(quoted.data.rows).toEqual([['林,星禾'],['周"云岚'],['沈\n竹安']]);
  expect(core.parseRoster(quoted.data,{columnIndex:0}).data.names).toEqual(['林,星禾','周"云岚','沈\n竹安']);
 });
 it('BG-03/all_aliases_and_internal_space', () => {
  const aliases=['姓名','学生姓名','名字','name','student name','student_name','学号','序号','编号','id','student id','student_id'];
  for(const alias of aliases){const t=raw([['备注',` ${alias.toUpperCase()} `],['林星禾','周云岚']]);expect(t.hasHeaderRow).toBe(true);expect(core.parseRoster(t,{columnIndex:1}).data.names).toEqual(['周云岚']);}
  for(const alias of ['student  name','student  id']) expect(raw([[alias],['林星禾']]).hasHeaderRow).toBe(false);
  expect(raw([['备注'],['姓名']]).hasHeaderRow).toBe(false);
 });
 it('BG-04/real_empty_xlsx_csv_and_blank_tables', () => {
  for(const [format,content] of [['csv',''],['xlsx',(()=>{const b=fs.readFileSync(path.join(fixtureDir,'empty.xlsx'));return b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength)})()]]){
   const t=core.parseTable(content,{format,xlsx:XLSX}); expect(t).toEqual({ok:true,data:{rows:[],columnCount:0,hasHeaderRow:false,sourceFormat:format}});
   expect(core.inspectFirstColumn(t.data)).toEqual({ok:true,data:{decision:'SELECT_COLUMN',reason:'EMPTY',sample:[]}}); failed(core.parseRoster(t.data,{columnIndex:0}),'E_EMPTY');
  }
  for(const rows of [[['姓名']],[[''],[' '],[]]]){const t=raw(rows);expect(core.inspectFirstColumn(t)).toEqual({ok:true,data:{decision:'SELECT_COLUMN',reason:'EMPTY',sample:[]}});failed(core.parseRoster(t,{columnIndex:0}),'E_EMPTY');}
  failed(core.parseTable(new ArrayBuffer(0),{format:'xlsx',xlsx:XLSX}),'E_SIGNATURE');
 });
 it('BG-05/name_positive_negative_sample_and_lengths', () => {
  for(const rows of [[['林星禾'],['周云岚']],[['姓名'],['沈竹安'],['许月舟']],[['Mira Vale'],['Orin Reed']]]) expect(core.inspectFirstColumn(raw(rows)).data).toMatchObject({decision:'USE_FIRST',reason:'NAME_LIKE'});
  for(const [rows,reason] of [[[['学号','姓名'],['900001','林星禾']],'HEADER_NOT_NAME'],[[['900001'],['900002']],'VALUE_NOT_NAME'],[[['A01'],['B02']],'VALUE_NOT_NAME']]) expect(core.inspectFirstColumn(raw(rows)).data.reason).toBe(reason);
  expect(core.inspectFirstColumn(raw([[],['林星禾'],['周云岚'],['沈竹安'],['许月舟'],['Mira Vale'],['A01']])).data).toEqual({decision:'USE_FIRST',reason:'NAME_LIKE',sample:['林星禾','周云岚','沈竹安','许月舟','Mira Vale']});
  for(const name of ['林'.repeat(2),'林'.repeat(12),'M'.repeat(2),'M'.repeat(60),'林·星禾','Mira Vale',"Orin-Reed","Mira'Vale"]) expect(core.inspectFirstColumn(raw([[name]])).data.reason).toBe('NAME_LIKE');
  for(const name of ['林','林'.repeat(13),'M','M'.repeat(61),'A01','900001']) expect(core.inspectFirstColumn(raw([[name]])).data.reason).toBe('VALUE_NOT_NAME');
  expect(core.inspectFirstColumn(raw([['备注'],['姓名']])).data.reason).toBe('VALUE_NOT_NAME');
  expect(core.parseRoster(raw([['A01']]),{columnIndex:0}).data.names).toEqual(['A01']);
 });
 it('BG-06/clean_ragged_duplicates_and_validation_priority', () => {
  const rows=[['学号','姓名'],['1',' 林星禾 '],[],['2',''],['3','Mira  Vale'],['4','林星禾'],['5']]; const snapshot=structuredClone(rows); const t=raw(rows,'csv',true);
  const got=core.parseRoster(t,{columnIndex:1});expect(got).toEqual({ok:true,data:{names:['林星禾','Mira  Vale','林星禾'],columnIndex:1,hasHeaderRow:true}});expect(got.data.names).not.toBe(rows);expect(rows).toEqual(snapshot);
  for(const bad of [null,{}, {rows:'x'}, {rows:[['x',4]]}, {...t,columnCount:3},{...t,sourceFormat:'xls'},{...t,hasHeaderRow:false}]){failed(core.inspectFirstColumn(bad),'E_TABLE');failed(core.parseRoster(bad,{columnIndex:999}),'E_TABLE');}
  for(const columnIndex of [undefined,-1,0.5,'1',NaN,2]) failed(core.parseRoster(t,{columnIndex}),'E_COLUMN');
  failed(core.parseRoster(raw([]),{columnIndex:-1}),'E_EMPTY');
 });
 it('BG-07/format_content_signature_size_and_bom', () => {
  const spy=makePort([]); failed(core.parseTable('x',{format:'xls',xlsx:spy}),'E_FORMAT');expect(spy.read).not.toHaveBeenCalled();
  for(const [content,options,code] of [[new ArrayBuffer(4),{format:'csv',xlsx:spy},'E_CONTENT'],['x',{format:'xlsx',xlsx:spy},'E_CONTENT'],['x',{},'E_FORMAT']])failed(core.parseTable(content,options),code);
  for(let n=0;n<4;n++)failed(core.parseTable(new Uint8Array(n).buffer,{format:'xlsx',xlsx:spy}),'E_SIGNATURE');
  failed(core.parseTable(new Uint8Array([80,75,3,5]).buffer,{format:'xlsx',xlsx:spy}),'E_SIGNATURE');
  const L=10485760; let result=core.parseTable('a'.repeat(L),{format:'csv',xlsx:spy});expect(result.ok).toBe(true);expect(spy.read).toHaveBeenCalledTimes(1);
  spy.read.mockClear();failed(core.parseTable('a'.repeat(L+1),{format:'csv',xlsx:spy}),'E_TOO_LARGE');expect(spy.read).not.toHaveBeenCalled();
  const exact='中'.repeat(3495253)+'a'; expect(Buffer.byteLength(exact,'utf8')).toBe(L); expect(core.parseTable(exact,{format:'csv',xlsx:spy}).ok).toBe(true);
  failed(core.parseTable(exact+'a',{format:'csv',xlsx:spy}),'E_TOO_LARGE');
  const bom='\uFEFF\uFEFF中\uFEFF'; spy.read.mockClear();core.parseTable(bom,{format:'csv',xlsx:spy});expect(spy.read.mock.calls.at(-1)[0]).toBe('\uFEFF中\uFEFF');
 });
 it('BG-08/port_faults_workbook_options_cells_and_dual_exports', () => {
  const good=()=>({SheetNames:['一','二'],Sheets:{一:{},二:{}}}); const ab=Uint8Array.from([80,75,3,4]).buffer;
  const errorPort={read(){throw Error('虚构敏感标记')},utils:{sheet_to_json(){}}}; expect(core.parseTable(ab,{format:'xlsx',xlsx:errorPort})).toEqual({ok:false,error:{code:'E_PARSE'}});
  for(const workbook of [{SheetNames:[],Sheets:{}},{SheetNames:['缺失','二'],Sheets:{二:{}}},{SheetNames:[3],Sheets:{}}]) expect(core.parseTable(ab,{format:'xlsx',xlsx:{read:()=>workbook,utils:{sheet_to_json:()=>[]}}})).toEqual({ok:false,error:{code:'E_PARSE'}});
  const spy={read:vi.fn((input,opts)=>{expect(opts).toEqual({type:'array',cellDates:false});return good()}),utils:{sheet_to_json:vi.fn((sheet,opts)=>{expect(sheet).toBe(good.Sheets?.一);expect(opts).toEqual({header:1,raw:false,defval:''});return [['  林星禾  ',12,true,null,undefined],[]]})}};
  spy.read.mockImplementation(()=>({SheetNames:['一','二'],Sheets:{一:{id:1},二:{id:2}}}));spy.utils.sheet_to_json.mockImplementation((sheet,opts)=>{expect(sheet).toEqual({id:1});expect(opts).toEqual({header:1,raw:false,defval:''});return [['  林星禾  ',12,true,null,undefined],[]]});
  expect(core.parseTable(ab,{format:'xlsx',xlsx:spy})).toEqual({ok:true,data:{rows:[['  林星禾  ','12','true','',''],[]],columnCount:5,hasHeaderRow:false,sourceFormat:'xlsx'}});
  for(const cells of [[['x'],{}],[['x',{}]],[['x',NaN]],[['x',Infinity]]])expect(core.parseTable(ab,{format:'xlsx',xlsx:{read:()=>({SheetNames:['一'],Sheets:{一:{}}}),utils:{sheet_to_json:()=>cells}}})).toEqual({ok:false,error:{code:'E_TABLE'}});
  expect(Object.keys(core).sort()).toEqual(['inspectFirstColumn','parseRoster','parseTable']);
  const sandbox={globalThis:{}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'..','parse.js'),'utf8'),sandbox);expect(Object.keys(sandbox.globalThis.ParseCore).sort()).toEqual(['inspectFirstColumn','parseRoster','parseTable']);
 });
});




