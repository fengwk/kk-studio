(() => {
  'use strict';

  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const worldSize = { width: 2600, height: 1600 };
  const stage = $('#canvasStage');
  const world = $('#canvasWorld');
  const nodesLayer = $('#nodesLayer');
  const relationsLayer = $('#relations');
  const selectionToolbar = $('#selectionToolbar');
  const selectionStatus = $('#selectionStatus');
  const saveState = $('#saveState');
  const editorLayout = $('#editorLayout');
  const contextCount = $('#contextCount');
  const contextDescription = $('#contextDescription');
  const toast = $('#toast');

  const initialNodes = [
    { id: 'frame', type: 'frame', x: 70, y: 110, width: 610, height: 430, title: '资料 Frame · 输入', subtitle: '3 个外部资料 + 研究笔记' },
    { id: 'web', type: 'web', x: 110, y: 175, width: 158, height: 162, title: 'NeoWOW 画布与工作流', copy: '一句目标、模板和作品闭环', meta: '网页 · 3 分钟前' },
    { id: 'image', type: 'image', x: 291, y: 175, width: 158, height: 162, title: '竞品编辑器截图', copy: '空间组织与低视觉重量', meta: '图片 · 2.4 MB' },
    { id: 'file', type: 'file', x: 472, y: 175, width: 158, height: 162, title: '竞品调研摘录', copy: 'Miro AI、Seko、tldraw Computer', meta: 'PDF · 12 页' },
    { id: 'note', type: 'text', x: 110, y: 365, width: 310, height: 128, title: '研究问题', copy: '如何让 Agent 在画布中理解上下文，并把可编辑结果稳定地放回来源附近？', meta: '文本 · 已同步' },
    { id: 'run', type: 'run', x: 760, y: 290, width: 275, height: 235, title: '竞品研究与归纳', status: 'succeeded', progress: 4, total: 4 },
    { id: 'matrix', type: 'matrix', x: 1145, y: 145, width: 286, height: 192, title: '竞品能力矩阵', copy: '定位、上下文与执行过程对比', meta: '结构化结果' },
    { id: 'result-a', type: 'result', x: 1145, y: 405, width: 178, height: 174, title: '方案 A · 研究画布', copy: '输入 → 运行 → 可编辑结论', variant: 'A' },
    { id: 'result-b', type: 'result', x: 1350, y: 405, width: 178, height: 174, title: '方案 B · 创作空间', copy: '对象驱动的连续生成', variant: 'B' },
    { id: 'direction', type: 'text', x: 1550, y: 200, width: 260, height: 145, title: '产品定位', copy: 'Agent 原生的多模态创作工作区。过程可见，结果可编辑、可追溯。', meta: '最终结论' }
  ];

  const initialRelations = [
    ['web', 'run'], ['image', 'run'], ['file', 'run'], ['note', 'run'], ['run', 'matrix'], ['run', 'result-a'], ['run', 'result-b'], ['matrix', 'direction']
  ];

  const state = {
    nodes: clone(initialNodes),
    relations: [...initialRelations],
    selected: new Set(),
    viewport: { x: 80, y: 20, scale: 0.6 },
    context: 'selection',
    tool: 'select',
    spaceDown: false,
    runTimer: null,
    activeView: 'library'
  };

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function escapeHTML(value) {
    return String(value).replace(/[&<>"]/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[character]));
  }

  function findNode(id) {
    return state.nodes.find((node) => node.id === id);
  }

  function nodeMarkup(node) {
    const label = (icon, text) => `<div class="node-label"><span class="type-icon">${icon}</span>${text}</div>`;
    const details = `<div class="node-title">${escapeHTML(node.title)}</div><div class="node-copy">${escapeHTML(node.copy || '')}</div>`;
    if (node.type === 'frame') {
      return `<div class="frame-title"><span></span>${escapeHTML(node.title)} <em>· ${escapeHTML(node.subtitle)}</em></div>`;
    }
    if (node.type === 'web') {
      return `<div class="web-strip"></div><div class="node-content">${label('⌁', 'Web reference')}${details}<div class="node-footer"><span>${escapeHTML(node.meta)}</span><span>↗</span></div></div>`;
    }
    if (node.type === 'image') {
      return `<div class="node-content"><div class="image-preview"></div>${label('◒', 'Image reference')}${details}<div class="node-footer"><span>${escapeHTML(node.meta)}</span><span>···</span></div></div>`;
    }
    if (node.type === 'file') {
      return `<div class="node-content"><span class="file-thumb">PDF</span>${label('▤', 'File')}${details}<div class="node-footer"><span>${escapeHTML(node.meta)}</span><span>↗</span></div></div>`;
    }
    if (node.type === 'text') {
      return `<div class="node-content">${label('T', node.meta || 'Text')}${details}</div>`;
    }
    if (node.type === 'run') {
      const progress = node.progress || 0;
      const isRunning = node.status === 'running';
      const statusText = isRunning ? `运行中 · ${progress}/${node.total}` : '已完成 · 4/4';
      const steps = ['提取定位与目标用户', '归纳交互与对象', '生成能力矩阵', '形成 MVP 页面方向'];
      return `<div class="node-content"><div class="run-header">${label('✦', 'Agent Run')}<span class="run-status">${statusText}</span></div><div class="node-title">${escapeHTML(node.title)}</div><div class="run-steps">${steps.map((step, index) => `<div class="run-step ${index < progress ? 'done' : ''} ${isRunning && index === progress ? 'current' : ''}"><b>${index < progress ? '✓' : index === progress && isRunning ? '●' : '○'}</b>${step}</div>`).join('')}</div><div class="run-progress"><i style="width:${(progress / node.total) * 100}%"></i></div></div>`;
    }
    if (node.type === 'matrix') {
      const cells = ['能力', 'Neo', 'Miro', '目标', '整图上下文', '—', '✓', '✓', '过程可见', '△', '△', '✓', '结果落位', '△', '✓', '✓'];
      return `<div class="node-content">${label('▦', 'Structured result')}${details}<div class="matrix-table">${cells.map((cell, index) => `<span class="${cell === '✓' ? 'yes' : ''}">${cell}</span>`).join('')}</div></div>`;
    }
    if (node.type === 'result') {
      return `<div class="node-content"><div class="variant-art ${node.variant === 'B' ? 'variant-b' : ''}"></div>${label('✦', 'Result variant')}${details}<span class="result-badge">变体 ${escapeHTML(node.variant || '新')}</span></div>`;
    }
    return '';
  }

  function renderNodes() {
    nodesLayer.innerHTML = state.nodes.map((node) => `<article class="canvas-node node-${node.type} ${state.selected.has(node.id) ? 'selected' : ''} ${node.status === 'running' ? 'running' : ''}" data-node-id="${node.id}" style="transform:translate(${node.x}px,${node.y}px);width:${node.width}px;height:${node.height}px">${nodeMarkup(node)}</article>`).join('');
    nodesLayer.querySelectorAll('.canvas-node').forEach((element) => element.addEventListener('pointerdown', onNodePointerDown));
  }

  function edgePath(source, target) {
    const fromX = source.x + source.width;
    const fromY = source.y + source.height / 2;
    const toX = target.x;
    const toY = target.y + target.height / 2;
    const distance = Math.max(70, (toX - fromX) * 0.5);
    return `M ${fromX} ${fromY} C ${fromX + distance} ${fromY}, ${toX - distance} ${toY}, ${toX} ${toY}`;
  }

  function renderRelations() {
    const active = state.selected;
    relationsLayer.innerHTML = state.relations.map(([sourceId, targetId]) => {
      const source = findNode(sourceId);
      const target = findNode(targetId);
      if (!source || !target) return '';
      const isActive = active.has(sourceId) || active.has(targetId);
      return `<path class="relation ${isActive ? 'active' : ''}" d="${edgePath(source, target)}"></path>`;
    }).join('');
  }

  function updateViewport() {
    const { x, y, scale } = state.viewport;
    world.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
    stage.style.backgroundSize = `${22 * scale}px ${22 * scale}px`;
    stage.style.backgroundPosition = `${x % (22 * scale)}px ${y % (22 * scale)}px`;
    $('#resetZoom').textContent = `${Math.round(scale * 100)}%`;
    const mapScale = 120 / worldSize.width;
    const viewportWidth = stage.clientWidth / scale;
    const viewportHeight = stage.clientHeight / scale;
    const mini = $('#miniViewport');
    mini.style.left = `${Math.max(0, -x / scale * mapScale)}px`;
    mini.style.top = `${Math.max(0, -y / scale * mapScale)}px`;
    mini.style.width = `${Math.min(116, viewportWidth * mapScale)}px`;
    mini.style.height = `${Math.min(74, viewportHeight * mapScale)}px`;
    updateSelectionToolbar();
  }

  function updateSelectionToolbar() {
    if (state.selected.size === 0) {
      selectionToolbar.classList.add('hidden');
      selectionStatus.textContent = '未选择对象';
      return;
    }
    const selectedNodes = state.nodes.filter((node) => state.selected.has(node.id));
    const first = selectedNodes[0];
    const bounds = selectionBounds(selectedNodes);
    const x = state.viewport.x + (bounds.x + bounds.width / 2) * state.viewport.scale;
    const y = state.viewport.y + bounds.y * state.viewport.scale - 38;
    selectionToolbar.style.left = `${Math.max(115, Math.min(stage.clientWidth - 145, x))}px`;
    selectionToolbar.style.top = `${Math.max(8, y)}px`;
    selectionToolbar.classList.remove('hidden');
    selectionStatus.textContent = state.selected.size === 1 ? `已选择：${first.title}` : `已选择 ${state.selected.size} 个对象`;
    contextCount.textContent = `${state.selected.size} 个对象`;
    if (state.context === 'selection') contextDescription.textContent = `${state.selected.size} 个选中对象将作为本次输入，并保留来源关系。`;
  }

  function render() {
    renderNodes();
    renderRelations();
    updateViewport();
    updateRunSummary();
  }

  function selectionBounds(nodes) {
    const left = Math.min(...nodes.map((node) => node.x));
    const top = Math.min(...nodes.map((node) => node.y));
    const right = Math.max(...nodes.map((node) => node.x + node.width));
    const bottom = Math.max(...nodes.map((node) => node.y + node.height));
    return { x: left, y: top, width: right - left, height: bottom - top };
  }

  function setSelection(ids, append = false) {
    if (!append) state.selected.clear();
    ids.forEach((id) => {
      if (append && state.selected.has(id)) state.selected.delete(id);
      else state.selected.add(id);
    });
    render();
  }

  function markSaved() {
    saveState.textContent = '保存中';
    saveState.style.color = 'var(--orange)';
    window.setTimeout(() => { saveState.textContent = '已保存'; saveState.style.color = ''; }, 420);
  }

  function stagePoint(event) {
    const rect = stage.getBoundingClientRect();
    return { x: (event.clientX - rect.left - state.viewport.x) / state.viewport.scale, y: (event.clientY - rect.top - state.viewport.y) / state.viewport.scale };
  }

  function onNodePointerDown(event) {
    if (event.button !== 0 || state.tool === 'hand' || state.spaceDown) return;
    event.preventDefault();
    event.stopPropagation();
    const id = event.currentTarget.dataset.nodeId;
    if (event.shiftKey) setSelection([id], true);
    else if (!state.selected.has(id)) setSelection([id]);
    const origin = stagePoint(event);
    const moving = state.nodes.filter((node) => state.selected.has(node.id));
    const startPositions = moving.map((node) => ({ id: node.id, x: node.x, y: node.y }));
    stage.classList.add('dragging-node');

    const move = (moveEvent) => {
      if (moveEvent.pointerId !== event.pointerId) return;
      const point = stagePoint(moveEvent);
      const dx = point.x - origin.x;
      const dy = point.y - origin.y;
      startPositions.forEach((position) => {
        const node = findNode(position.id);
        if (node) {
          node.x = Math.round(position.x + dx);
          node.y = Math.round(position.y + dy);
        }
      });
      render();
    };
    const end = (endEvent) => {
      if (endEvent.pointerId !== event.pointerId) return;
      stage.classList.remove('dragging-node');
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
      markSaved();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  }

  function zoomAt(clientX, clientY, nextScale) {
    const rect = stage.getBoundingClientRect();
    const oldScale = state.viewport.scale;
    const scale = Math.max(0.25, Math.min(1.45, nextScale));
    const cursorX = clientX - rect.left;
    const cursorY = clientY - rect.top;
    const worldX = (cursorX - state.viewport.x) / oldScale;
    const worldY = (cursorY - state.viewport.y) / oldScale;
    state.viewport.scale = scale;
    state.viewport.x = cursorX - worldX * scale;
    state.viewport.y = cursorY - worldY * scale;
    updateViewport();
  }

  function fitView() {
    const visible = state.nodes.filter((node) => node.type !== 'frame');
    const bounds = selectionBounds(visible);
    const padding = 105;
    const scale = Math.min(1, Math.max(0.28, Math.min((stage.clientWidth - padding) / bounds.width, (stage.clientHeight - padding) / bounds.height)));
    state.viewport.scale = scale;
    state.viewport.x = (stage.clientWidth - bounds.width * scale) / 2 - bounds.x * scale;
    state.viewport.y = (stage.clientHeight - bounds.height * scale) / 2 - bounds.y * scale;
    updateViewport();
    showToast('已适应全部内容');
  }

  function focusSelection() {
    const selected = state.nodes.filter((node) => state.selected.has(node.id));
    if (!selected.length) return fitView();
    const bounds = selectionBounds(selected);
    const scale = Math.min(1.15, Math.max(0.45, Math.min((stage.clientWidth - 150) / bounds.width, (stage.clientHeight - 150) / bounds.height)));
    state.viewport.scale = scale;
    state.viewport.x = stage.clientWidth / 2 - (bounds.x + bounds.width / 2) * scale;
    state.viewport.y = stage.clientHeight / 2 - (bounds.y + bounds.height / 2) * scale;
    updateViewport();
    showToast('已聚焦当前选区');
  }

  function deleteSelection() {
    if (!state.selected.size) return;
    const count = state.selected.size;
    state.nodes = state.nodes.filter((node) => !state.selected.has(node.id));
    state.relations = state.relations.filter(([source, target]) => !state.selected.has(source) && !state.selected.has(target));
    state.selected.clear();
    render();
    markSaved();
    showToast(`已删除 ${count} 个对象（可重置演示恢复）`);
  }

  function onStagePointerDown(event) {
    const clickedNode = event.target.closest('.canvas-node');
    const clickedOverlay = event.target.closest('.selection-toolbar, .canvas-controls, .copilot-peek');
    if (event.button !== 0 || clickedNode || clickedOverlay || state.spaceDown || state.tool !== 'select') return;
    if (event.shiftKey) return;
    state.selected.clear();
    render();
    startMarqueeSelection(event);
  }

  function startMarqueeSelection(event) {
    const marquee = $('#selectionMarquee');
    const start = stagePoint(event);
    marquee.classList.remove('hidden');
    const updateMarquee = (moveEvent) => {
      if (moveEvent.pointerId !== event.pointerId) return;
      const point = stagePoint(moveEvent);
      const left = Math.min(start.x, point.x);
      const top = Math.min(start.y, point.y);
      const right = Math.max(start.x, point.x);
      const bottom = Math.max(start.y, point.y);
      marquee.style.left = `${state.viewport.x + left * state.viewport.scale}px`;
      marquee.style.top = `${state.viewport.y + top * state.viewport.scale}px`;
      marquee.style.width = `${(right - left) * state.viewport.scale}px`;
      marquee.style.height = `${(bottom - top) * state.viewport.scale}px`;
      state.selected.clear();
      state.nodes.forEach((node) => {
        const intersects = node.x < right && node.x + node.width > left && node.y < bottom && node.y + node.height > top;
        if (node.type !== 'frame' && intersects) state.selected.add(node.id);
      });
      render();
    };
    const finishMarquee = (endEvent) => {
      if (endEvent.pointerId !== event.pointerId) return;
      marquee.classList.add('hidden');
      window.removeEventListener('pointermove', updateMarquee);
      window.removeEventListener('pointerup', finishMarquee);
      window.removeEventListener('pointercancel', finishMarquee);
    };
    window.addEventListener('pointermove', updateMarquee);
    window.addEventListener('pointerup', finishMarquee);
    window.addEventListener('pointercancel', finishMarquee);
  }

  function setTool(tool) {
    state.tool = tool;
    stage.classList.toggle('hand-tool', tool === 'hand');
    $$('[data-tool]').forEach((button) => button.classList.toggle('active', button.dataset.tool === tool));
    showToast(tool === 'hand' ? '手形工具：拖动空白区域平移画布' : '选择工具：拖动空白区域框选对象');
  }

  function installPanAndZoom() {
    let pan = null;
    window.addEventListener('keydown', (event) => { if (event.code === 'Space' && !isTyping(event.target)) state.spaceDown = true; });
    window.addEventListener('keyup', (event) => { if (event.code === 'Space') state.spaceDown = false; });
    stage.addEventListener('pointerdown', (event) => {
      const isInteractiveOverlay = event.target.closest('.selection-toolbar, .canvas-controls, .copilot-peek');
      const isBlankCanvas = !event.target.closest('.canvas-node') && !isInteractiveOverlay;
      const shouldPan = !isInteractiveOverlay && (event.button === 1 || (event.button === 0 && state.spaceDown) || (event.button === 0 && state.tool === 'hand' && isBlankCanvas));
      if (!shouldPan) return;
      event.preventDefault();
      pan = { x: event.clientX, y: event.clientY, viewportX: state.viewport.x, viewportY: state.viewport.y };
      stage.classList.add('panning');
      stage.setPointerCapture(event.pointerId);
    });
    stage.addEventListener('pointermove', (event) => {
      if (!pan) return;
      state.viewport.x = pan.viewportX + event.clientX - pan.x;
      state.viewport.y = pan.viewportY + event.clientY - pan.y;
      updateViewport();
    });
    const endPan = () => { pan = null; stage.classList.remove('panning'); };
    stage.addEventListener('pointerup', endPan);
    stage.addEventListener('pointercancel', endPan);
    stage.addEventListener('wheel', (event) => {
      event.preventDefault();
      if (event.ctrlKey || event.metaKey) zoomAt(event.clientX, event.clientY, state.viewport.scale * (event.deltaY > 0 ? 0.9 : 1.11));
      else { state.viewport.x -= event.deltaX; state.viewport.y -= event.deltaY; updateViewport(); }
    }, { passive: false });
  }

  function isTyping(target) {
    return ['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable;
  }

  function updateRunSummary() {
    const run = findNode('run');
    if (!run) return;
    const running = run.status === 'running';
    $('#runSummaryTitle').textContent = run.title;
    $('#runSummaryState').textContent = running ? `正在执行第 ${Math.min(run.progress + 1, run.total)} 步 · 来源可追溯` : `已完成 · ${state.nodes.filter((node) => node.type === 'result' || node.type === 'matrix').length} 个结果`;
    $('.pulse-dot').classList.toggle('running', running);
  }

  function startAgentRun() {
    const run = findNode('run');
    if (!run || state.runTimer) return;
    const selectedSources = state.nodes.filter((node) => state.selected.has(node.id) && node.id !== 'run');
    const source = selectedSources[0] || findNode('frame');
    run.status = 'running';
    run.progress = 0;
    run.total = 4;
    state.selected.clear();
    state.selected.add('run');
    const oldGenerated = state.nodes.filter((node) => node.generated).map((node) => node.id);
    state.nodes = state.nodes.filter((node) => !node.generated);
    state.relations = state.relations.filter(([from, to]) => !oldGenerated.includes(from) && !oldGenerated.includes(to));
    render();
    showToast(`Agent 已读取「${source.title}」，开始在画布中工作`);
    let step = 0;
    state.runTimer = window.setInterval(() => {
      step += 1;
      run.progress = step;
      render();
      if (step >= run.total) {
        window.clearInterval(state.runTimer);
        state.runTimer = null;
        run.status = 'succeeded';
        const result = {
          id: `generated-${Date.now()}`, type: 'result', x: run.x + run.width + 95, y: run.y + 250,
          width: 196, height: 178, title: 'MVP 页面方向 C', copy: 'Copilot 生成的新结果，固定落在来源右侧。', variant: '新', generated: true
        };
        state.nodes.push(result);
        state.relations.push([run.id, result.id]);
        state.selected.clear();
        state.selected.add(result.id);
        render();
        markSaved();
        showToast('任务完成：新结果已落在 Agent Run 右侧');
      }
    }, 620);
  }

  function resetDemo() {
    if (state.runTimer) window.clearInterval(state.runTimer);
    state.runTimer = null;
    state.nodes = clone(initialNodes);
    state.relations = [...initialRelations];
    state.selected.clear();
    state.viewport = { x: 80, y: 20, scale: 0.6 };
    render();
    fitView();
    showToast('演示已重置');
  }

  function setContext(mode) {
    state.context = mode;
    const selection = mode === 'selection';
    $('#selectionContext').classList.toggle('active', selection);
    $('#wholeContext').classList.toggle('active', !selection);
    $('#selectionContext').setAttribute('aria-pressed', selection);
    $('#wholeContext').setAttribute('aria-pressed', !selection);
    contextCount.textContent = selection ? `${state.selected.size || 3} 个对象` : `${state.nodes.length} 个对象`;
    contextDescription.textContent = selection ? (state.selected.size ? '选中对象将作为本次输入，并保留来源关系。' : '资料 Frame 中的 3 个对象将作为本次输入。') : 'Agent 将理解整张画布的对象、结构和已有结果。';
  }

  function showView(view) {
    state.activeView = view;
    $('#libraryView').classList.toggle('active', view === 'library');
    $('#editorView').classList.toggle('active', view === 'editor');
    if (view === 'editor') requestAnimationFrame(fitView);
  }

  function showToast(message) {
    toast.textContent = message;
    toast.classList.add('visible');
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.classList.remove('visible'), 2400);
  }

  const panelData = {
    research: `
      <p class="panel-section-title">关键竞品结论</p>
      <article class="insight-card"><h3>NeoWOW</h3><p>首页想法输入、模板分类、个人/协作画布与 Skill 闭环值得借鉴；产品底层仍须保持通用，而非绑定垂直领域。</p></article>
      <article class="insight-card"><h3>WorkRally · Seko</h3><p>WorkRally 证明 Agent/CLI 可操作带状态的画布对象；Seko 证明“灵感 → 自动策划”有效。<strong>一句话创建应先展示计划</strong>，而不是黑盒一键完成。</p></article>
      <article class="insight-card"><h3>Miro AI · tldraw Computer</h3><p>整张画布可以作为 Prompt，Agent 可以在空间中构建结构。执行过程应该成为可见对象，但普通用户不应手工搭建工作流。</p></article>
      <article class="insight-card"><h3>本地 infinite-canvas</h3><p>CSS 视口变换、节点/存储分层、导入导出是有效参考；避免反常框选、永久大 Dock、业务字段堆积和大页面耦合。</p></article>`,
    architecture: `
      <p class="panel-section-title">产品定位</p><article class="insight-card"><h3>Agent 原生的多模态创作工作区</h3><p>资料、想法和产物存在同一空间。<strong>输入 → Agent Run → 可编辑结果</strong>，过程可见、来源可追溯。</p></article>
      <p class="panel-section-title">对象模型</p><div class="model-flow">Workspace <span>→</span> Project <span>→</span> CanvasDocument <span>→</span> CanvasItem / Relation / AgentRun</div>
      <article class="insight-card"><h3>扩展协议</h3><ul class="protocol-list"><li>节点注册：schema、默认数据、渲染、检查器与迁移</li><li>动作注册：接受对象、输入输出、结果落位策略</li><li>Skill：能力、执行模式、成本和可见性</li><li>Importer / Exporter / Previewer / Indexer 独立扩展</li></ul></article>
      <article class="insight-card"><h3>React Flow 初步选型</h3><p>MIT 许可，适合富 DOM 节点和 Agent 状态；内置拖拽、视口、多选、MiniMap、Controls。它只承担交互渲染，领域模型保持独立。</p></article>
      <article class="insight-card"><h3>AGPL 风险</h3><p>本地 infinite-canvas 为 AGPL-3.0。网络提供服务可能触发对应源代码义务，<strong>仅作为行为和模块边界参考，不直接复制源码。</strong></p></article>`,
    roadmap: `
      <p class="panel-section-title">从验证到生态</p>
      <article class="insight-card phase"><span class="phase-index">A</span><div><h3>交互原型</h3><p>画布库、基础操作、Copilot、Agent Run 和结果落位，验证产品表达。</p></div></article>
      <article class="insight-card phase"><span class="phase-index">B</span><div><h3>画布 MVP</h3><p>持久化、统一命令历史、素材引用、自动保存和节点/动作注册表。</p></div></article>
      <article class="insight-card phase"><span class="phase-index">C</span><div><h3>Agent 原生能力</h3><p>选区/整图上下文、SSE 状态、画布命令、暂停重试与来源追踪。</p></div></article>
      <article class="insight-card phase"><span class="phase-index">D</span><div><h3>协作与生态</h3><p>实时协作、只读分享、创作回放、Playbook / Skill 市场与用量策略。</p></div></article>`
  };

  function openResearch(tab = 'research') {
    $('#researchPanel').classList.remove('hidden');
    setPanelTab(tab);
  }
  function setPanelTab(tab) {
    $$('.panel-tabs button').forEach((button) => button.classList.toggle('active', button.dataset.panelTab === tab));
    $('#panelContent').innerHTML = panelData[tab];
  }

  function filterLibrary(filter) {
    $$('[data-library-filter]').forEach((button) => button.classList.toggle('active', button.dataset.libraryFilter === filter));
    $$('[data-library-owner]').forEach((card) => { card.hidden = filter !== 'all' && card.dataset.libraryOwner !== filter; });
    showToast(filter === 'all' ? '正在展示全部画布' : filter === 'mine' ? '正在展示我的画布' : '正在展示协作画布');
  }

  function handleToolbarAction(action) {
    if (!state.selected.size) return;
    if (action === 'edit') {
      showToast('编辑模式已准备就绪（原型模拟）');
    } else if (action === 'ai') {
      editorLayout.classList.remove('copilot-collapsed');
      window.setTimeout(() => $('#copilotPrompt').focus(), 0);
      showToast('Copilot 已读取当前选区');
    } else if (action === 'context') {
      setContext('selection');
      showToast(`已将 ${state.selected.size} 个对象加入当前上下文`);
    } else if (action === 'more') {
      showToast('更多对象操作将在检查器中提供（原型模拟）');
    }
  }

  function bindControls() {
    $$('[data-view="library"]').forEach((button) => button.addEventListener('click', () => showView('library')));
    $$('[data-open-editor]').forEach((button) => button.addEventListener('click', () => showView('editor')));
    $('#createFromIdea').addEventListener('click', () => { showView('editor'); showToast('已根据目标创建「研究与归纳」画布'); });
    $('#ideaInput').addEventListener('keydown', (event) => { if (event.key === 'Enter') $('#createFromIdea').click(); });
    $$('.template-card').forEach((card) => card.addEventListener('click', () => { $$('.template-card').forEach((item) => item.classList.remove('selected')); card.classList.add('selected'); showToast(`已选择「${card.dataset.template}」模板`); }));
    $$('[data-library-filter]').forEach((button) => button.addEventListener('click', () => filterLibrary(button.dataset.libraryFilter)));
    $('#searchCanvases').addEventListener('click', () => showToast('画布搜索将在完整产品中打开命令搜索（原型模拟）'));
    $('#gridViewButton').addEventListener('click', () => showToast('当前使用网格视图（原型模拟）'));
    $('#researchButton').addEventListener('click', () => openResearch());
    $('#libraryResearchButton').addEventListener('click', () => openResearch());
    $('#closeResearch').addEventListener('click', () => $('#researchPanel').classList.add('hidden'));
    $$('.panel-tabs button').forEach((button) => button.addEventListener('click', () => setPanelTab(button.dataset.panelTab)));
    $('#collapseCopilot').addEventListener('click', () => editorLayout.classList.add('copilot-collapsed'));
    $('#expandCopilot').addEventListener('click', () => { editorLayout.classList.remove('copilot-collapsed'); requestAnimationFrame(fitView); });
    $('#openCopilotTool').addEventListener('click', () => { editorLayout.classList.remove('copilot-collapsed'); $('#copilotPrompt').focus(); });
    $('#selectionContext').addEventListener('click', () => setContext('selection'));
    $('#wholeContext').addEventListener('click', () => setContext('whole'));
    $('#runAgent').addEventListener('click', startAgentRun);
    $('#resetDemo').addEventListener('click', resetDemo);
    $('#zoomIn').addEventListener('click', () => zoomAt(stage.getBoundingClientRect().left + stage.clientWidth / 2, stage.getBoundingClientRect().top + stage.clientHeight / 2, state.viewport.scale * 1.15));
    $('#zoomOut').addEventListener('click', () => zoomAt(stage.getBoundingClientRect().left + stage.clientWidth / 2, stage.getBoundingClientRect().top + stage.clientHeight / 2, state.viewport.scale / 1.15));
    $('#fitView').addEventListener('click', fitView);
    $('#resetZoom').addEventListener('click', () => zoomAt(stage.getBoundingClientRect().left + stage.clientWidth / 2, stage.getBoundingClientRect().top + stage.clientHeight / 2, 1));
    $('#selectTool').addEventListener('click', () => setTool('select'));
    $('#handTool').addEventListener('click', () => setTool('hand'));
    $$('.selection-toolbar [data-action]').forEach((button) => button.addEventListener('click', () => handleToolbarAction(button.dataset.action)));
    $('#helpButton').addEventListener('click', () => $('#helpDialog').showModal());
    $('#closeHelp').addEventListener('click', () => $('#helpDialog').close());
    $('#shareButton').addEventListener('click', () => showToast('分享链接已复制（原型模拟）'));
    $('#exportButton').addEventListener('click', () => showToast('导出 PNG / PDF / JSON 将在完整产品中提供（原型模拟）'));
    stage.addEventListener('pointerdown', onStagePointerDown);
    window.addEventListener('resize', updateViewport);
    window.addEventListener('keydown', onKeyboardShortcut);
  }

  function onKeyboardShortcut(event) {
    const modifier = event.metaKey || event.ctrlKey;
    if (modifier && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      editorLayout.classList.remove('copilot-collapsed');
      if (state.activeView === 'editor') $('#copilotPrompt').focus();
      return;
    }
    if (modifier && event.key === 'Enter' && state.activeView === 'editor') { event.preventDefault(); startAgentRun(); return; }
    if (isTyping(event.target)) return;
    if (event.key === 'Escape') {
      if (!$('#researchPanel').classList.contains('hidden')) $('#researchPanel').classList.add('hidden');
      else if ($('#helpDialog').open) $('#helpDialog').close();
      else { state.selected.clear(); render(); }
    }
    if (state.activeView !== 'editor') return;
    if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); deleteSelection(); }
    if (event.key === '0') { event.preventDefault(); fitView(); }
    if (event.key === '1') { event.preventDefault(); zoomAt(stage.getBoundingClientRect().left + stage.clientWidth / 2, stage.getBoundingClientRect().top + stage.clientHeight / 2, 1); }
    if (event.key.toLowerCase() === 'f') { event.preventDefault(); focusSelection(); }
  }

  function init() {
    bindControls();
    installPanAndZoom();
    render();
    setContext('selection');
  }

  init();
})();
