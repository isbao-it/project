(() => {
  'use strict';

  const INITIALIZATION_ERROR = '页面初始化失败，请重新打开';
  const NON_GENERATED_HINT = '请先生成座位表，再复制或打印';
  const usedDocuments = new WeakSet();

  const ERROR_MESSAGES = {
    E_SEED: '请输入有效种子，长度为1至128字符',
    E_NAMES: '名单数据无效，请重新导入',
    E_DIMENSIONS: '行数和列数须为正整数且乘积安全',
    E_CAPACITY: '座位总数不能超过10000',
    E_RNG: '随机生成失败，请重试',
    E_MATRIX: '座位数据无效，无法复制',
    E_FORMAT: '仅支持 .xlsx 或 .csv',
    E_CONTENT: '文件内容类型不正确，请重新读取',
    E_TOO_LARGE: '文件超过 10 MiB 上限',
    E_SIGNATURE: '文件格式不正确或已损坏',
    E_PARSE: '文件格式不正确或已损坏',
    E_TABLE: '表格数据无效，请重新导入',
    E_COLUMN: '请选择有效的姓名列',
    E_EMPTY: '未读取到学生名单'
  };

  /**
   * 按冻结契约装配界面并连接显式注入的核心与浏览器端口。
   * @param {{document: Document, seat: object, parse: object, xlsx: object, ports: object}} options 已加载 body、核心对象和四个端口。
   * @returns {{ok: true, data: {readonly state: string}}|{ok: false, error: {message: string}}} 返回实时只读状态，或固定初始化错误。
   * @sideEffects 绑定当前文档事件；成功后只在内存保存文件、姓名与座位结果。
   */
  function mountApp(options) {
    const doc = options && options.document;
    const statusArea = doc && doc.getElementById('status-area');
    if (doc && doc.body && !statusArea) {
      const status = doc.createElement('section');
      status.id = 'status-area';
      status.className = 'no-print';
      status.setAttribute('aria-live', 'polite');
      const message = doc.createElement('p');
      message.dataset.role = 'status-message';
      message.textContent = INITIALIZATION_ERROR;
      status.appendChild(message);
      doc.body.appendChild(status);
    }
    const visibleStatus = doc && doc.getElementById('status-area');
    const fail = () => {
      if (visibleStatus) visibleStatus.textContent = INITIALIZATION_ERROR;
      return { ok: false, error: { message: INITIALIZATION_ERROR } };
    };

    if (!doc || !doc.body || !options || usedDocuments.has(doc)) return fail();
    const { seat, parse, xlsx, ports } = options;
    const control = doc.getElementById('control-area');
    const status = doc.getElementById('status-area');
    const printArea = doc.getElementById('print-area');
    if (!control || !status || !printArea ||
        !seat || typeof seat.createSeededRng !== 'function' || typeof seat.assignSeats !== 'function' || typeof seat.toMarkdown !== 'function' ||
        !parse || typeof parse.parseTable !== 'function' || typeof parse.inspectFirstColumn !== 'function' || typeof parse.parseRoster !== 'function' ||
        !ports || typeof ports.readFile !== 'function' || typeof ports.makeSeed !== 'function' ||
        typeof ports.copyText !== 'function' || typeof ports.printPage !== 'function') return fail();

    const element = (role) => control.querySelector(`[data-role="${role}"]`) || status.querySelector(`[data-role="${role}"]`) || printArea.querySelector(`[data-role="${role}"]`);
    const fileInput = element('file');
    const columnSection = element('column-section');
    const columnSelect = element('column');
    const columnMessage = element('column-message');
    const columnSuggestion = element('column-suggestion');
    const preview = element('preview');
    const confirmButton = element('confirm');
    const rowsInput = element('rows');
    const columnsInput = element('columns');
    const seedInput = element('seed');
    const dimensionMessage = element('dimension-message');
    const generateButton = element('generate');
    const copyButton = element('copy');
    const printButton = element('print');
    const statusMessage = element('status-message');
    const unassignedMessage = element('unassigned');
    const manualCopy = element('manual-copy');
    if ([fileInput, columnSection, columnSelect, columnMessage, columnSuggestion, preview, confirmButton,
      rowsInput, columnsInput, seedInput, dimensionMessage, generateButton, copyButton, printButton,
      statusMessage, unassignedMessage, manualCopy].some((node) => !node)) return fail();

    let state = 'EMPTY';
    let readSequence = 0;
    let rawTable = null;
    let columnAdvice = null;
    let names = null;
    let matrix = null;
    let assignedCount = 0;
    let unassignedCount = 0;
    let actualSeed = '';
    let configSnapshot = null;

    const result = { ok: true, data: {} };
    Object.defineProperty(result.data, 'state', { enumerable: true, get: () => state });

    function clearOutput() {
      matrix = null;
      assignedCount = 0;
      unassignedCount = 0;
      actualSeed = '';
      configSnapshot = null;
      printArea.replaceChildren();
      manualCopy.value = '';
      manualCopy.hidden = true;
      unassignedMessage.textContent = '';
    }

    function updateControls() {
      const generated = state === 'GENERATED';
      generateButton.disabled = state !== 'READY';
      copyButton.disabled = !generated;
      printButton.disabled = !generated;
      confirmButton.disabled = !rawTable || !(state === 'CHOOSING' || (state === 'ERROR' && rawTable));
      columnSection.hidden = !rawTable;
      if (!generated && !statusMessage.textContent) statusMessage.textContent = NON_GENERATED_HINT;
      if (generated && statusMessage.textContent === NON_GENERATED_HINT) statusMessage.textContent = '';
    }

    function setState(nextState) {
      state = nextState;
      updateControls();
    }

    function showStatus(message) {
      statusMessage.textContent = message;
    }

    function clearColumnFeedback() {
      columnMessage.textContent = '';
      columnSuggestion.textContent = '';
    }

    function clearGenerationFeedback() {
      dimensionMessage.textContent = '';
      if (statusMessage.textContent === ERROR_MESSAGES.E_SEED ||
          statusMessage.textContent === ERROR_MESSAGES.E_DIMENSIONS ||
          statusMessage.textContent === ERROR_MESSAGES.E_CAPACITY ||
          statusMessage.textContent === ERROR_MESSAGES.E_RNG ||
          statusMessage.textContent === '无法自动生成种子，请手动填写') showStatus('');
    }

    function showColumnError(message) {
      columnMessage.textContent = message;
      showStatus('');
    }

    function showCoreError(code) {
      const message = ERROR_MESSAGES[code] || ERROR_MESSAGES.E_PARSE;
      if (code === 'E_COLUMN') showColumnError(message);
      else if (code === 'E_DIMENSIONS' || code === 'E_CAPACITY') {
        dimensionMessage.textContent = message;
        showStatus('');
      } else showStatus(message);
    }

    function renderPreview(table) {
      preview.replaceChildren();
      const body = doc.createElement('tbody');
      // §11.3 将预览固定为前 5 行，保留原表头与空行，避免界面偷偷改写源数据。
      for (const row of table.rows.slice(0, 5)) {
        const rowElement = doc.createElement('tr');
        for (let column = 0; column < table.columnCount; column += 1) {
          const cell = doc.createElement('td');
          cell.textContent = row[column] === undefined ? '' : row[column];
          rowElement.appendChild(cell);
        }
        body.appendChild(rowElement);
      }
      preview.appendChild(body);
    }

    function renderSeats(seatMatrix) {
      printArea.replaceChildren();
      const podium = doc.createElement('p');
      podium.dataset.role = 'podium';
      podium.textContent = '讲台（座位表上方）';
      const table = doc.createElement('table');
      const body = doc.createElement('tbody');
      for (const row of seatMatrix) {
        const rowElement = doc.createElement('tr');
        for (const value of row) {
          const cell = doc.createElement('td');
          cell.textContent = value === null ? '空' : value;
          rowElement.appendChild(cell);
        }
        body.appendChild(rowElement);
      }
      table.appendChild(body);
      printArea.append(podium, table);
    }

    function clearImportView() {
      rawTable = null;
      columnAdvice = null;
      names = null;
      columnSelect.replaceChildren();
      clearColumnFeedback();
      preview.replaceChildren();
      clearOutput();
    }

    function fillColumns(table, advice) {
      columnSelect.replaceChildren();
      const placeholder = doc.createElement('option');
      placeholder.value = '';
      placeholder.textContent = '请选择姓名列';
      columnSelect.appendChild(placeholder);
      for (let index = 0; index < table.columnCount; index += 1) {
        const option = doc.createElement('option');
        option.value = String(index);
        option.textContent = `第 ${index + 1} 列`;
        columnSelect.appendChild(option);
      }
      if (advice.decision === 'USE_FIRST') columnSelect.value = '0';
      else {
        columnSelect.value = '';
        columnSuggestion.textContent = '第一列可能不是姓名，请选择姓名列';
      }
    }

    // 数据入口：浏览器 File 经 readFile 成为 ArrayBuffer 或 CSV 文本，再交给 ParseCore。
    async function readSelectedFile() {
      const sequence = ++readSequence;
      clearImportView();
      showStatus(NON_GENERATED_HINT);
      dimensionMessage.textContent = '';
      setState('READING');
      const file = fileInput.files && fileInput.files[0];
      if (!file) {
        setState('EMPTY');
        return;
      }
      const suffix = file.name.toLowerCase().split('.').pop();
      const format = suffix === 'xlsx' || suffix === 'csv' ? suffix : null;
      if (!format) {
        showStatus(ERROR_MESSAGES.E_FORMAT);
        setState('ERROR');
        return;
      }
      // §11.3 的 10 MiB 文件门槛在读取前执行；超限不触碰文件端口。
      if (file.size > 10485760) {
        showStatus(ERROR_MESSAGES.E_TOO_LARGE);
        setState('ERROR');
        return;
      }
      let content;
      try {
        content = await ports.readFile(file, format);
      } catch (_error) {
        if (sequence !== readSequence) return;
        showStatus('读取文件失败，请重新选择');
        setState('ERROR');
        return;
      }
      if (sequence !== readSequence) return;
      let parsed;
      try {
        parsed = parse.parseTable(content, { format, xlsx });
      } catch (_error) {
        showStatus(ERROR_MESSAGES.E_PARSE);
        setState('ERROR');
        return;
      }
      if (!parsed.ok) {
        showCoreError(parsed.error.code);
        setState('ERROR');
        return;
      }
      const table = parsed.data;
      if (!table.columnCount || !table.rows.length) {
        showStatus(ERROR_MESSAGES.E_EMPTY);
        setState('ERROR');
        return;
      }
      let advice;
      try {
        advice = parse.inspectFirstColumn(table);
      } catch (_error) {
        showStatus(ERROR_MESSAGES.E_TABLE);
        setState('ERROR');
        return;
      }
      if (!advice.ok) {
        showCoreError(advice.error.code);
        setState('ERROR');
        return;
      }
      rawTable = table;
      columnAdvice = advice.data;
      fillColumns(table, columnAdvice);
      renderPreview(table);
      clearColumnFeedback();
      if (columnAdvice.decision === 'SELECT_COLUMN') columnSuggestion.textContent = '第一列可能不是姓名，请选择姓名列';
      showStatus('');
      setState('CHOOSING');
    }

    function columnChanged() {
      if (!rawTable) return;
      columnSuggestion.textContent = '';
      columnMessage.textContent = '';
      if (state === 'READY' || state === 'GENERATED' || state === 'ERROR') {
        names = null;
        clearOutput();
        dimensionMessage.textContent = '';
        showStatus('');
        clearColumnFeedback();
        setState('CHOOSING');
      }
    }

    function confirmColumn() {
      if (!rawTable) return;
      const columnIndex = columnSelect.value === '' ? NaN : Number(columnSelect.value);
      const roster = parse.parseRoster(rawTable, { columnIndex });
      if (!roster.ok) {
        names = null;
        clearOutput();
        showCoreError(roster.error.code);
        setState('ERROR');
        return;
      }
      if (!roster.data.names.length) {
        names = null;
        clearOutput();
        showStatus(ERROR_MESSAGES.E_EMPTY);
        setState('ERROR');
        return;
      }
      names = roster.data.names;
      clearOutput();
      clearColumnFeedback();
      clearGenerationFeedback();
      showStatus('');
      setState('READY');
    }

    function parametersChanged() {
      if (state === 'GENERATED') {
        clearOutput();
        showStatus('');
        setState('READY');
      }
      clearGenerationFeedback();
    }

    function parseDimensions() {
      const rowText = rowsInput.value.trim();
      const columnText = columnsInput.value.trim();
      if (!/^[1-9][0-9]*$/.test(rowText) || !/^[1-9][0-9]*$/.test(columnText)) return null;
      const rows = Number(rowText);
      const columns = Number(columnText);
      if (!Number.isSafeInteger(rows) || !Number.isSafeInteger(columns) || !Number.isSafeInteger(rows * columns)) return null;
      // §11.3 容量上限为 10,000；超限在调用核心前给出固定提示。
      if (rows * columns > 10000) return { capacityError: true };
      return { rows, columns };
    }

    function generationFailure(code) {
      if (state === 'GENERATED') {
        clearOutput();
        setState('READY');
      }
      showCoreError(code);
    }

    function generate() {
      if (state !== 'READY' || !names) return;
      dimensionMessage.textContent = '';
      const dimensions = parseDimensions();
      if (!dimensions) {
        generationFailure('E_DIMENSIONS');
        return;
      }
      if (dimensions.capacityError) {
        generationFailure('E_CAPACITY');
        return;
      }
      let seed = seedInput.value.trim();
      // §11.3 种子最多 128 个 UTF-16 code unit；超过上限不得提交部分结果。
      if (seed.length > 128) {
        generationFailure('E_SEED');
        return;
      }
      if (!seed) {
        try {
          seed = ports.makeSeed();
        } catch (_error) {
          generationFailure('E_SEED');
          showStatus('无法自动生成种子，请手动填写');
          return;
        }
      }
      let rngResult;
      try {
        rngResult = seat.createSeededRng(seed);
      } catch (_error) {
        generationFailure('E_RNG');
        return;
      }
      if (!rngResult.ok) {
        generationFailure(rngResult.error.code);
        return;
      }
      // 数据出口：SeatCore 返回矩阵与统计，app 只保存并逐格渲染，不重新排座。
      const actual = rngResult.data.seed;
      let assignment;
      try {
        assignment = seat.assignSeats(names, dimensions.rows, dimensions.columns, rngResult.data.rng);
      } catch (_error) {
        generationFailure('E_RNG');
        return;
      }
      if (!assignment.ok) {
        if (assignment.error.code === 'E_NAMES') {
          names = null;
          clearOutput();
          showCoreError('E_NAMES');
          setState('ERROR');
        } else generationFailure(assignment.error.code);
        return;
      }
      matrix = assignment.data.matrix;
      assignedCount = assignment.data.assignedCount;
      unassignedCount = assignment.data.unassignedCount;
      actualSeed = actual;
      configSnapshot = { rows: dimensions.rows, columns: dimensions.columns, seed: actual };
      seedInput.value = actual;
      renderSeats(matrix);
      manualCopy.value = '';
      manualCopy.hidden = true;
      dimensionMessage.textContent = '';
      if (unassignedCount > 0) unassignedMessage.textContent = `有 ${unassignedCount} 人未分配`;
      else unassignedMessage.textContent = '';
      showStatus('');
      setState('GENERATED');
    }

    async function copySeats() {
      if (state !== 'GENERATED' || !matrix) return;
      manualCopy.value = '';
      manualCopy.hidden = true;
      let markdownResult;
      try {
        markdownResult = seat.toMarkdown(matrix);
      } catch (_error) {
        markdownResult = { ok: false, error: { code: 'E_MATRIX' } };
      }
      if (!markdownResult.ok) {
        showCoreError(markdownResult.error.code);
        return;
      }
      try {
        await ports.copyText(markdownResult.data.markdown);
        if (state === 'GENERATED') showStatus('复制成功');
      } catch (_error) {
        if (state !== 'GENERATED') return;
        manualCopy.value = markdownResult.data.markdown;
        manualCopy.hidden = false;
        showStatus('复制失败，请在文本框中手动复制');
      }
    }

    function printSeats() {
      if (state !== 'GENERATED') return;
      try {
        ports.printPage();
      } catch (_error) {
        showStatus('打印失败，请重试');
      }
    }

    fileInput.addEventListener('change', readSelectedFile);
    columnSelect.addEventListener('change', columnChanged);
    confirmButton.addEventListener('click', confirmColumn);
    rowsInput.addEventListener('input', parametersChanged);
    columnsInput.addEventListener('input', parametersChanged);
    seedInput.addEventListener('input', parametersChanged);
    generateButton.addEventListener('click', generate);
    copyButton.addEventListener('click', copySeats);
    printButton.addEventListener('click', printSeats);
    usedDocuments.add(doc);
    showStatus(NON_GENERATED_HINT);
    updateControls();

    return result;
  }

  globalThis.SeatApp = { mountApp };

  function createDefaultPorts() {
    return {
      readFile(file, format) {
        return format === 'xlsx' ? file.arrayBuffer() : file.text();
      },
      makeSeed() {
        if (!globalThis.crypto || typeof globalThis.crypto.getRandomValues !== 'function') throw new Error('entropy unavailable');
        // §11.10 用两个 Uint32 组成 16 位十六进制种子；熵源不可用时交给界面提示手填。
        const values = new Uint32Array(2);
        globalThis.crypto.getRandomValues(values);
        return Array.from(values, (value) => value.toString(16).padStart(8, '0')).join('');
      },
      async copyText(text) {
        if (!globalThis.navigator || !globalThis.navigator.clipboard || typeof globalThis.navigator.clipboard.writeText !== 'function') {
          throw new Error('clipboard unavailable');
        }
        await globalThis.navigator.clipboard.writeText(text);
      },
      printPage() {
        globalThis.window.print();
      }
    };
  }

  const defaultDocument = globalThis.document;
  if (defaultDocument && defaultDocument.body) {
    mountApp({
      document: defaultDocument,
      seat: globalThis.SeatCore,
      parse: globalThis.ParseCore,
      xlsx: globalThis.XLSX,
      ports: createDefaultPorts()
    });
  }
})();
