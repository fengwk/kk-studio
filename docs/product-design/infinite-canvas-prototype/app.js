(() => {
  'use strict';

  const $ = (selector) => document.querySelector(selector);
  const $$ = (selector) => [...document.querySelectorAll(selector)];
  const worldSize = { width: 2600, height: 1600 };
  const canvasGridSize = 18;
  let researchOpener = null;

  const stage = $('#canvasStage');
  const world = $('#canvasWorld');
  const nodesLayer = $('#nodesLayer');
  const relationsLayer = $('#relations');
  const selectionToolbar = $('#selectionToolbar');
  const saveState = $('#saveState');
  const toast = $('#toast');
  const thread = $('#agentThread');
  const threadMessages = $('#threadMessages');
  const agentPrompt = $('#agentPrompt');
  const generationPanel = $('#generationPanel');
  const generationPrompt = $('#generationPrompt');
  const addMenu = $('#addMenu');

  const initialNodes = [
    {
      id: 'frame',
      type: 'frame',
      x: 70,
      y: 110,
      width: 610,
      height: 430,
      title: '资料 Frame · 输入',
      subtitle: '3 个外部资料 + 研究笔记'
    },
    {
      id: 'web',
      type: 'web',
      x: 110,
      y: 175,
      width: 158,
      height: 162,
      title: 'NeoWOW 画布与工作流',
      copy: '一句目标、模板和作品闭环',
      meta: '网页 · 3 分钟前'
    },
    {
      id: 'image',
      type: 'image',
      x: 291,
      y: 175,
      width: 158,
      height: 162,
      title: '竞品编辑器截图',
      copy: '空间组织与低视觉重量',
      meta: '图片 · 2.4 MB'
    },
    {
      id: 'file',
      type: 'file',
      x: 472,
      y: 175,
      width: 158,
      height: 162,
      title: 'Miro AI / Seko / tldraw 摘录',
      copy: '整图上下文、Agent 执行与画布结果',
      meta: 'PDF · 12 页'
    },
    {
      id: 'note',
      type: 'text',
      x: 110,
      y: 365,
      width: 310,
      height: 128,
      title: '研究问题',
      copy: '如何让 Agent 理解画布上下文，并把可编辑结果稳定地放回来源附近？',
      meta: '文本 · 已同步'
    },
    {
      id: 'run',
      type: 'run',
      x: 760,
      y: 290,
      width: 275,
      height: 235,
      title: '竞品研究与归纳',
      status: 'succeeded',
      progress: 4,
      total: 4
    },
    {
      id: 'matrix',
      type: 'matrix',
      x: 1145,
      y: 145,
      width: 286,
      height: 192,
      title: '竞品能力矩阵',
      copy: '定位、上下文与执行过程对比',
      meta: '结构化结果'
    },
    {
      id: 'result-a',
      type: 'result',
      x: 1145,
      y: 405,
      width: 178,
      height: 174,
      title: '方案 A · 研究画布',
      copy: '输入 → 运行 → 可编辑结论',
      variant: 'A'
    },
    {
      id: 'result-b',
      type: 'result',
      x: 1350,
      y: 405,
      width: 178,
      height: 174,
      title: '方案 B · 创作空间',
      copy: '对象驱动的连续生成',
      variant: 'B'
    },
    {
      id: 'direction',
      type: 'text',
      x: 1550,
      y: 200,
      width: 260,
      height: 145,
      title: '产品定位',
      copy: 'Agent 原生的多模态创作工作区。过程可见，结果可编辑、可追溯。',
      meta: '最终结论'
    }
  ];

  const initialRelations = [
    ['web', 'run'],
    ['image', 'run'],
    ['file', 'run'],
    ['note', 'run'],
    ['run', 'matrix'],
    ['run', 'result-a'],
    ['run', 'result-b'],
    ['matrix', 'direction']
  ];

  const defaultContextIds = ['web', 'image', 'file'];

  const generationProfiles = {
    image: {
      prompt: '低饱和黑白产品视觉，留出清晰的编辑空间',
      cost: '≈ 8 积分',
      groups: [
        { key: '模型', values: ['Flux · Pro', 'SDXL · Turbo', 'Ideogram · V3'] },
        { key: '比例', values: ['1:1', '4:3', '16:9'] },
        { key: '数量', values: ['4 张', '2 张', '1 张'] },
        { key: '风格', values: ['电影感', '编辑感', '极简'] }
      ]
    },
    video: {
      prompt: '黑灰创作工作台缓慢推镜，抽象素材在画布中展开，克制的镜头运动',
      cost: '≈ 28 积分',
      groups: [
        { key: '模型', values: ['Kling · 1.6', 'Runway · Gen-3', 'Luma · Ray 2'] },
        { key: '规格', values: ['16:9 · 1080p', '9:16 · 1080p', '1:1 · 720p'] },
        { key: '时长', values: ['6 秒', '4 秒', '10 秒'] },
        { key: '镜头', values: ['缓慢推镜', '固定镜头', '横向移动'] }
      ]
    }
  };

  const state = {
    nodes: clone(initialNodes),
    relations: [...initialRelations],
    selected: new Set(),
    viewport: { x: 80, y: 20, scale: 0.6 },
    context: 'selection',
    tool: 'select',
    spaceDown: false,
    runTimer: null,
    activeView: 'library',
    messages: [],
    generationMode: 'image',
    generationPrompts: {
      image: generationProfiles.image.prompt,
      video: generationProfiles.video.prompt
    },
    generationParameters: {
      image: [0, 0, 0, 0],
      video: [0, 0, 0, 0]
    },
    references: [true, true, false],
    sequence: 0,
    forceThreadScroll: false
  };

  function clone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function escapeHTML(value) {
    return String(value || '').replace(/[&<>"]/g, (character) => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;'
    }[character]));
  }

  function findNode(id) {
    return state.nodes.find((node) => node.id === id);
  }

  function nextId(prefix) {
    state.sequence += 1;
    return `${prefix}-${Date.now()}-${state.sequence}`;
  }

  function nodeMarkup(node) {
    const label = (icon, text) => `
      <div class="node-label"><span class="type-icon">${icon}</span>${text}</div>
    `;
    const details = `
      <div class="node-title">${escapeHTML(node.title)}</div>
      <div class="node-copy">${escapeHTML(node.copy)}</div>
    `;

    if (node.type === 'frame') {
      return `
        <div class="frame-title">
          <span></span>${escapeHTML(node.title)} <em>· ${escapeHTML(node.subtitle)}</em>
        </div>
      `;
    }

    if (node.type === 'web') {
      return `
        <div class="web-strip"></div>
        <div class="node-content">
          ${label('⌁', 'Web reference')}
          ${details}
          <div class="node-footer"><span>${escapeHTML(node.meta)}</span><span>↗</span></div>
        </div>
      `;
    }

    if (node.type === 'image') {
      return `
        <div class="node-content">
          <div class="image-preview"></div>
          ${label('◒', 'Image reference')}
          ${details}
          <div class="node-footer"><span>${escapeHTML(node.meta)}</span><span>···</span></div>
        </div>
      `;
    }

    if (node.type === 'file') {
      return `
        <div class="node-content">
          <span class="file-thumb">PDF</span>
          ${label('▤', 'File')}
          ${details}
          <div class="node-footer"><span>${escapeHTML(node.meta)}</span><span>↗</span></div>
        </div>
      `;
    }

    if (node.type === 'text') {
      return `<div class="node-content">${label('T', node.meta || 'Text')}${details}</div>`;
    }

    if (node.type === 'run') {
      const running = node.status === 'running';
      const paused = node.status === 'paused';
      const statusText = running
        ? `运行中 · ${node.progress}/${node.total}`
        : paused
          ? `已暂停 · ${node.progress}/${node.total}`
          : `已完成 · ${node.progress}/${node.total}`;
      const steps = ['提取定位与目标用户', '归纳交互与对象', '生成能力矩阵', '形成 MVP 页面方向'];
      const control = running ? '暂停' : paused ? '继续' : '重试';
      const action = running ? 'pause' : paused ? 'resume' : 'retry';
      const stepMarkup = steps.map((step, index) => {
        const done = index < node.progress;
        const current = running && index === node.progress;
        const marker = done ? '✓' : current ? '●' : '○';
        return `<div class="run-step ${done ? 'done' : ''} ${current ? 'current' : ''}"><b>${marker}</b>${step}</div>`;
      }).join('');

      return `
        <div class="node-content">
          <div class="run-header">
            ${label('✦', 'Agent Run')}
            <span class="run-status">${statusText}</span>
          </div>
          <div class="node-title">${escapeHTML(node.title)}</div>
          <div class="run-steps">${stepMarkup}</div>
          <div
            class="run-progress"
            role="progressbar"
            aria-label="Agent 运行进度"
            aria-valuemin="0"
            aria-valuemax="${node.total}"
            aria-valuenow="${node.progress}"
          ><i style="width:${(node.progress / node.total) * 100}%"></i></div>
          <div class="run-node-controls">
            <button class="run-node-control" data-run-action="${action}" type="button">${control}</button>
          </div>
        </div>
      `;
    }

    if (node.type === 'matrix') {
      const cells = ['能力', '参考', '目标', '整图上下文', '—', '✓', '过程可见', '△', '✓', '结果落位', '△', '✓'];
      return `
        <div class="node-content">
          ${label('▦', 'Structured result')}
          ${details}
          <div class="matrix-table">
            ${cells.map((cell) => `<span class="${cell === '✓' ? 'yes' : ''}">${cell}</span>`).join('')}
          </div>
        </div>
      `;
    }

    if (node.type === 'result') {
      return `
        <div class="node-content">
          <div class="variant-art ${node.variant === 'B' ? 'variant-b' : ''}"></div>
          ${label('✦', 'Result variant')}
          ${details}
          <span class="result-badge">变体 ${escapeHTML(node.variant || '新')}</span>
        </div>
      `;
    }

    if (node.type === 'media') {
      const mediaLabel = node.mediaType === 'video' ? 'Generated video' : 'Generated image';
      const mediaIcon = node.mediaType === 'video' ? '▻' : '◒';
      const mediaMeta = node.meta || (node.mediaType === 'video' ? '16:9 · 6 秒' : '1:1 · 4 个变体');
      return `
        <div class="node-content">
          <div class="media-preview"></div>
          ${label(mediaIcon, mediaLabel)}
          ${details}
          <div class="node-footer"><span>${mediaMeta}</span><span>✦</span></div>
        </div>
      `;
    }

    return '';
  }

  function renderNodes() {
    nodesLayer.innerHTML = state.nodes.map((node) => `
      <article
        class="canvas-node node-${node.type} ${node.mediaType || ''} ${state.selected.has(node.id) ? 'selected' : ''}"
        data-node-id="${node.id}"
        style="transform:translate(${node.x}px,${node.y}px);width:${node.width}px;height:${node.height}px"
      >${nodeMarkup(node)}</article>
    `).join('');

    nodesLayer.querySelectorAll('.canvas-node').forEach((element) => {
      element.addEventListener('pointerdown', onNodePointerDown);
    });

    nodesLayer.querySelectorAll('[data-run-action]').forEach((button) => {
      button.addEventListener('pointerdown', (event) => event.stopPropagation());
      button.addEventListener('click', (event) => {
        event.stopPropagation();
        handleRunAction(button.dataset.runAction);
      });
    });
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
    relationsLayer.innerHTML = state.relations.map(([sourceId, targetId]) => {
      const source = findNode(sourceId);
      const target = findNode(targetId);
      if (!source || !target) {
        return '';
      }
      const active = state.selected.has(sourceId) || state.selected.has(targetId);
      return `<path class="relation ${active ? 'active' : ''}" d="${edgePath(source, target)}"></path>`;
    }).join('');
  }

  function getContextInfo() {
    if (state.context === 'whole') {
      return {
        count: state.nodes.length,
        description: '整张画布的对象、结构和已有结果将作为本次输入。',
        source: '整张画布'
      };
    }

    const selected = state.nodes.filter((node) => state.selected.has(node.id));
    if (selected.length) {
      return {
        count: selected.length,
        description: `${selected.length} 个选中对象将作为本次输入，并保留来源关系。`,
        source: `当前选区（${selected.length} 个对象）`
      };
    }

    const defaults = state.nodes.filter((node) => defaultContextIds.includes(node.id));
    return {
      count: defaults.length,
      description: defaults.length ? '网页、截图和研究资料将作为本次输入。' : '暂无默认资料对象；请选中对象或使用整张画布。',
      source: `默认资料（${defaults.length} 个对象）`
    };
  }

  function updateContextUI() {
    const info = getContextInfo();
    const selection = state.context === 'selection';
    $('#contextCount').textContent = info.count;
    $('#contextDescription').textContent = info.description;
    $('#selectionContext').classList.toggle('active', selection);
    $('#wholeContext').classList.toggle('active', !selection);
    $('#selectionContext').setAttribute('aria-pressed', String(selection));
    $('#wholeContext').setAttribute('aria-pressed', String(!selection));
    return info;
  }

  function updateViewport() {
    const scale = Number.isFinite(state.viewport.scale) ? state.viewport.scale : 0.6;
    const x = Number.isFinite(state.viewport.x) ? state.viewport.x : 0;
    const y = Number.isFinite(state.viewport.y) ? state.viewport.y : 0;
    state.viewport = { x, y, scale };

    world.style.transform = `translate(${x}px, ${y}px) scale(${scale})`;
    const scaledGridSize = canvasGridSize * scale;
    stage.style.backgroundSize = `${scaledGridSize}px ${scaledGridSize}px`;
    stage.style.backgroundPosition = `${x % scaledGridSize}px ${y % scaledGridSize}px`;
    $('#resetZoom').textContent = `${Math.round(scale * 100)}%`;

    const mapScale = 120 / worldSize.width;
    const mini = $('#miniViewport');
    mini.style.left = `${Math.max(0, -x / scale * mapScale)}px`;
    mini.style.top = `${Math.max(0, -y / scale * mapScale)}px`;
    mini.style.width = `${Math.min(116, stage.clientWidth / scale * mapScale)}px`;
    mini.style.height = `${Math.min(74, stage.clientHeight / scale * mapScale)}px`;
    updateSelectionToolbar();
  }

  function selectionBounds(nodes) {
    if (!nodes.length) {
      return null;
    }

    const left = Math.min(...nodes.map((node) => node.x));
    const top = Math.min(...nodes.map((node) => node.y));
    const right = Math.max(...nodes.map((node) => node.x + node.width));
    const bottom = Math.max(...nodes.map((node) => node.y + node.height));
    return {
      x: left,
      y: top,
      width: Math.max(1, right - left),
      height: Math.max(1, bottom - top)
    };
  }

  function updateSelectionToolbar() {
    const selected = state.nodes.filter((node) => state.selected.has(node.id));
    if (!selected.length) {
      selectionToolbar.classList.add('hidden');
      updateContextUI();
      return;
    }

    const bounds = selectionBounds(selected);
    const x = state.viewport.x + (bounds.x + bounds.width / 2) * state.viewport.scale;
    const y = state.viewport.y + bounds.y * state.viewport.scale - 38;
    selectionToolbar.classList.remove('hidden');
    const stageWidth = Math.max(0, stage.clientWidth || 0);
    const toolbarWidth = selectionToolbar.offsetWidth || 240;
    const toolbarHalfWidth = toolbarWidth / 2;
    const horizontalGap = 8;
    const minimumLeft = toolbarHalfWidth + horizontalGap;
    const maximumLeft = stageWidth - toolbarHalfWidth - horizontalGap;
    const toolbarLeft = minimumLeft > maximumLeft
      ? stageWidth / 2
      : Math.max(minimumLeft, Math.min(maximumLeft, x));
    selectionToolbar.style.left = `${toolbarLeft}px`;
    selectionToolbar.style.top = `${Math.max(8, y)}px`;
    updateContextUI();
  }

  function updateLibraryCardState() {
    const status = $('[data-project-run-state]');
    const objectCount = $('[data-project-object-count]');
    const run = findNode('run');

    if (status) {
      const runState = run ? run.status : 'removed';
      const presentation = {
        running: ['running', '● 1 个任务进行中'],
        paused: ['paused', 'Ⅱ 1 个任务已暂停'],
        succeeded: ['completed', '✓ 最近任务已完成'],
        removed: ['removed', 'Agent Run 已移除']
      }[runState];
      status.className = `project-state ${presentation[0]}`;
      status.textContent = presentation[1];
    }

    if (objectCount) {
      objectCount.textContent = `${state.nodes.length + state.relations.length} 个对象`;
    }
  }

  function render() {
    renderNodes();
    renderRelations();
    updateViewport();
    renderThread();
    updateLibraryCardState();
  }

  function setSelection(ids, append = false) {
    if (!append) {
      state.selected.clear();
    }

    ids.forEach((id) => {
      if (append && state.selected.has(id)) {
        state.selected.delete(id);
      } else {
        state.selected.add(id);
      }
    });
    render();
  }

  function markSaved() {
    saveState.textContent = '保存中';
    saveState.style.color = '#c7c7c7';
    window.setTimeout(() => {
      saveState.textContent = '已保存';
      saveState.style.color = '';
    }, 420);
  }

  function stagePoint(event) {
    const rect = stage.getBoundingClientRect();
    return {
      x: (event.clientX - rect.left - state.viewport.x) / state.viewport.scale,
      y: (event.clientY - rect.top - state.viewport.y) / state.viewport.scale
    };
  }

  function onNodePointerDown(event) {
    if (event.button !== 0 || state.tool === 'hand' || state.spaceDown) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    stage.focus({ preventScroll: true });
    const id = event.currentTarget.dataset.nodeId;

    if (event.shiftKey) {
      const wasSelected = state.selected.has(id);
      setSelection([id], true);
      if (wasSelected) {
        return;
      }
    } else if (!state.selected.has(id)) {
      setSelection([id]);
    }

    const origin = stagePoint(event);
    const moving = state.nodes.filter((node) => state.selected.has(node.id));
    const starts = moving.map((node) => ({ id: node.id, x: node.x, y: node.y }));
    stage.classList.add('dragging-node');

    const move = (moveEvent) => {
      if (moveEvent.pointerId !== event.pointerId) {
        return;
      }

      const point = stagePoint(moveEvent);
      starts.forEach((start) => {
        const node = findNode(start.id);
        if (node) {
          node.x = Math.round(start.x + point.x - origin.x);
          node.y = Math.round(start.y + point.y - origin.y);
        }
      });
      render();
    };

    const end = (endEvent) => {
      if (endEvent.pointerId !== event.pointerId) {
        return;
      }

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
    state.viewport = {
      scale,
      x: cursorX - worldX * scale,
      y: cursorY - worldY * scale
    };
    updateViewport();
  }

  function fitView(options = {}) {
    const notify = options.notify !== false;
    const content = state.nodes.filter((node) => node.type !== 'frame');
    const bounds = selectionBounds(content.length ? content : state.nodes);
    if (!bounds) {
      state.viewport = {
        x: stage.clientWidth / 2 - worldSize.width * 0.3,
        y: stage.clientHeight / 2 - worldSize.height * 0.3,
        scale: 0.6
      };
      updateViewport();
      if (notify) {
        showToast('画布暂无内容，已保持稳定视图');
      }
      return;
    }

    const padding = 105;
    const scale = Math.min(
      1,
      Math.max(0.28, Math.min((stage.clientWidth - padding) / bounds.width, (stage.clientHeight - padding) / bounds.height))
    );
    state.viewport = {
      scale,
      x: (stage.clientWidth - bounds.width * scale) / 2 - bounds.x * scale,
      y: (stage.clientHeight - bounds.height * scale) / 2 - bounds.y * scale
    };
    updateViewport();
    if (notify) {
      showToast('已适应全部内容');
    }
  }

  function focusSelection() {
    const selected = state.nodes.filter((node) => state.selected.has(node.id));
    if (!selected.length) {
      fitView();
      return;
    }

    const bounds = selectionBounds(selected);
    const scale = Math.min(
      1.15,
      Math.max(0.45, Math.min((stage.clientWidth - 150) / bounds.width, (stage.clientHeight - 150) / bounds.height))
    );
    state.viewport = {
      scale,
      x: stage.clientWidth / 2 - (bounds.x + bounds.width / 2) * scale,
      y: stage.clientHeight / 2 - (bounds.y + bounds.height / 2) * scale
    };
    updateViewport();
    showToast('已聚焦当前选区');
  }

  function deleteSelection() {
    if (!state.selected.size) {
      return;
    }

    const ids = [...state.selected];
    if (ids.includes('run')) {
      stopRunTimer();
    }
    state.nodes = state.nodes.filter((node) => !state.selected.has(node.id));
    state.relations = state.relations.filter(([from, to]) => !state.selected.has(from) && !state.selected.has(to));
    state.selected.clear();
    render();
    markSaved();
    showToast(`已删除 ${ids.length} 个对象（可重置演示恢复）`);
  }

  function onStagePointerDown(event) {
    const overlay = event.target.closest('.selection-toolbar, .canvas-controls, .agent-dock-wrap');
    if (event.button !== 0 || event.target.closest('.canvas-node') || overlay || state.spaceDown || state.tool !== 'select' || event.shiftKey) {
      return;
    }

    state.selected.clear();
    render();
    startMarqueeSelection(event);
  }

  function startMarqueeSelection(event) {
    const marquee = $('#selectionMarquee');
    const start = stagePoint(event);
    marquee.classList.remove('hidden');

    const move = (moveEvent) => {
      if (moveEvent.pointerId !== event.pointerId) {
        return;
      }

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
        const intersects = node.type !== 'frame'
          && node.x < right
          && node.x + node.width > left
          && node.y < bottom
          && node.y + node.height > top;
        if (intersects) {
          state.selected.add(node.id);
        }
      });
      render();
    };

    const end = (endEvent) => {
      if (endEvent.pointerId !== event.pointerId) {
        return;
      }

      marquee.classList.add('hidden');
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  }

  function setTool(tool) {
    state.tool = tool;
    stage.classList.toggle('hand-tool', tool === 'hand');
    showToast(tool === 'hand' ? '手形工具：拖动空白区域平移画布' : '选择工具：拖动空白区域框选对象');
  }

  function installPanAndZoom() {
    let pan = null;

    window.addEventListener('keydown', (event) => {
      if (event.code !== 'Space' || state.activeView !== 'editor' || isInteractiveControl(event.target)) {
        return;
      }
      event.preventDefault();
      state.spaceDown = true;
    });

    window.addEventListener('keyup', (event) => {
      if (event.code === 'Space') {
        state.spaceDown = false;
      }
    });

    stage.addEventListener('pointerdown', (event) => {
      const overlay = event.target.closest('.selection-toolbar, .canvas-controls, .agent-dock-wrap');
      if (!overlay) {
        stage.focus({ preventScroll: true });
      }
      const blank = !event.target.closest('.canvas-node') && !overlay;
      const shouldPan = !overlay && (
        event.button === 1
        || (event.button === 0 && state.spaceDown)
        || (event.button === 0 && state.tool === 'hand' && blank)
      );
      if (!shouldPan) {
        return;
      }

      event.preventDefault();
      pan = {
        x: event.clientX,
        y: event.clientY,
        viewportX: state.viewport.x,
        viewportY: state.viewport.y
      };
      stage.classList.add('panning');
      if (stage.setPointerCapture) {
        stage.setPointerCapture(event.pointerId);
      }
    });

    stage.addEventListener('pointermove', (event) => {
      if (!pan) {
        return;
      }
      state.viewport.x = pan.viewportX + event.clientX - pan.x;
      state.viewport.y = pan.viewportY + event.clientY - pan.y;
      updateViewport();
    });

    const endPan = () => {
      pan = null;
      stage.classList.remove('panning');
    };
    stage.addEventListener('pointerup', endPan);
    stage.addEventListener('pointercancel', endPan);
    window.addEventListener('blur', () => {
      state.spaceDown = false;
      endPan();
    });

    stage.addEventListener('wheel', (event) => {
      event.preventDefault();
      if (event.ctrlKey || event.metaKey) {
        const factor = event.deltaY > 0 ? 0.9 : 1.11;
        zoomAt(event.clientX, event.clientY, state.viewport.scale * factor);
      } else {
        state.viewport.x -= event.deltaX;
        state.viewport.y -= event.deltaY;
        updateViewport();
      }
    }, { passive: false });
  }

  function isTyping(target) {
    return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target && target.tagName) || Boolean(target && target.isContentEditable);
  }

  function isInteractiveControl(target) {
    return isTyping(target) || Boolean(target && target.closest && target.closest('button'));
  }

  function stopRunTimer() {
    if (state.runTimer) {
      window.clearInterval(state.runTimer);
    }
    state.runTimer = null;
  }

  function removeGeneratedResults() {
    const ids = state.nodes.filter((node) => node.generated && node.type === 'result').map((node) => node.id);
    state.nodes = state.nodes.filter((node) => !ids.includes(node.id));
    state.relations = state.relations.filter(([from, to]) => !ids.includes(from) && !ids.includes(to));
  }

  function ensureRunMessage() {
    state.messages = state.messages.filter((message) => message.kind !== 'run');
    state.messages.push({ kind: 'run' });
  }

  function requestThreadScroll() {
    state.forceThreadScroll = true;
  }

  function finishAgentRun(run) {
    stopRunTimer();
    if (!findNode(run.id)) {
      return;
    }

    run.status = 'succeeded';
    const width = 196;
    const height = 178;
    const position = findOpenCanvasPosition(run, width, height);
    const result = {
      id: nextId('generated'),
      type: 'result',
      x: position.x,
      y: position.y,
      width,
      height,
      title: 'MVP 页面方向 C',
      copy: 'Agent 生成的新结果，避让已有内容并保留来源关系。',
      variant: '新',
      generated: true
    };
    state.nodes.push(result);
    state.relations.push([run.id, result.id]);
    state.selected.clear();
    state.selected.add(result.id);
    state.messages.push({ kind: 'agent', text: '任务完成：已生成可编辑的 MVP 页面方向，并保留来源关系。' });
    requestThreadScroll();
    render();
    revealNodeAboveDock(result);
    markSaved();
    showToast('任务完成：新结果已避让已有内容并保留来源关系');
  }

  function scheduleRun(run) {
    stopRunTimer();
    state.runTimer = window.setInterval(() => {
      if (findNode(run.id) !== run || run.status !== 'running') {
        stopRunTimer();
        return;
      }

      run.progress += 1;
      if (run.progress >= run.total) {
        finishAgentRun(run);
      } else {
        render();
      }
    }, 620);
  }

  function startAgentRun({ retry = false, task } = {}) {
    const run = findNode('run');
    if (!run) {
      showToast('Agent Run 已被删除；请重置演示后再运行');
      return;
    }

    if (run.status === 'running') {
      return;
    }

    if (run.status === 'paused' && !retry) {
      run.status = 'running';
      scheduleRun(run);
      render();
      showToast(`Agent 从第 ${run.progress + 1} 步继续执行`);
      return;
    }

    stopRunTimer();
    const context = retry && run.inputSource ? { source: run.inputSource } : getContextInfo();
    removeGeneratedResults();
    run.status = 'running';
    run.progress = 0;
    run.total = 4;
    run.inputSource = context.source;
    if (task) {
      run.title = task.length > 30 ? `${task.slice(0, 30)}…` : task;
    }

    state.selected.clear();
    state.selected.add('run');
    ensureRunMessage();
    requestThreadScroll();
    render();
    scheduleRun(run);
    showToast(`Agent 已读取${context.source}，开始在画布中工作`);
  }

  function pauseAgentRun() {
    const run = findNode('run');
    if (!run || run.status !== 'running') {
      return;
    }

    stopRunTimer();
    run.status = 'paused';
    render();
    showToast(`Agent 已暂停在第 ${run.progress}/${run.total} 步`);
  }

  function handleRunAction(action) {
    setThreadOpen(true);
    if (action === 'pause') {
      pauseAgentRun();
    } else if (action === 'resume') {
      startAgentRun();
    } else if (action === 'retry') {
      startAgentRun({ retry: true });
    }
  }

  function isThreadNearBottom() {
    return threadMessages.scrollTop + threadMessages.clientHeight >= threadMessages.scrollHeight - 24;
  }

  function runControlsMarkup(run) {
    if (run.status === 'running') {
      return '<button data-thread-run-action="pause">暂停</button>';
    }
    if (run.status === 'paused') {
      return '<button data-thread-run-action="resume">继续</button><button data-thread-run-action="retry">重试</button>';
    }
    return '<button data-thread-run-action="retry">重试</button>';
  }

  function runMessageMarkup() {
    const run = findNode('run');
    if (!run) {
      return '<div class="thread-message run"><strong>Agent Run 已删除</strong>重置演示可恢复运行对象。</div>';
    }

    const status = run.status === 'running'
      ? `正在执行第 ${Math.min(run.progress + 1, run.total)} / ${run.total} 步`
      : run.status === 'paused'
        ? `已暂停于第 ${run.progress} / ${run.total} 步`
        : `已完成 ${run.total} 步，结果已落到画布`;
    return `
      <div class="thread-message run">
        <strong>✦ ${escapeHTML(run.title)}</strong>
        ${status}
        <small>上下文：${escapeHTML(run.inputSource || getContextInfo().source)}</small>
        <div class="run-controls">${runControlsMarkup(run)}</div>
      </div>
    `;
  }

  function generationMessageMarkup(message) {
    const title = message.mode === 'video' ? '▻ 视频生成任务' : '◒ 图片生成任务';
    return `
      <div class="thread-message run">
        <strong>${title}</strong>
        ${escapeHTML(message.text)}
        <small>${escapeHTML(message.parameters)} · 已完成 · 结果已放入画布</small>
      </div>
    `;
  }

  function renderThread() {
    const previousScrollTop = threadMessages.scrollTop;
    const shouldScroll = state.forceThreadScroll || isThreadNearBottom();
    updateContextUI();
    threadMessages.innerHTML = state.messages.map((message) => {
      if (message.kind === 'user') {
        return `<div class="thread-message user">${escapeHTML(message.text)}</div>`;
      }
      if (message.kind === 'run') {
        return runMessageMarkup();
      }
      if (message.kind === 'generation') {
        return generationMessageMarkup(message);
      }
      return `<div class="thread-message"><strong>Agent</strong>${escapeHTML(message.text)}</div>`;
    }).join('');

    threadMessages.querySelectorAll('[data-thread-run-action]').forEach((button) => {
      button.addEventListener('click', () => handleRunAction(button.dataset.threadRunAction));
    });

    if (shouldScroll) {
      threadMessages.scrollTop = threadMessages.scrollHeight;
    } else {
      threadMessages.scrollTop = previousScrollTop;
    }
    state.forceThreadScroll = false;
  }

  function setVisible(element, visible) {
    element.hidden = !visible;
    element.inert = !visible;
    element.setAttribute('aria-hidden', String(!visible));
    if (visible) {
      element.removeAttribute('inert');
    } else {
      element.setAttribute('inert', '');
    }
  }

  function setThreadOpen(open) {
    if (open) {
      setVisible(addMenu, false);
      setVisible(generationPanel, false);
      $('#dockAdd').setAttribute('aria-expanded', 'false');
    }
    setVisible(thread, open);
  }

  function setAddMenuOpen(open) {
    if (open) {
      setVisible(thread, false);
      setVisible(generationPanel, false);
    }
    setVisible(addMenu, open);
    $('#dockAdd').setAttribute('aria-expanded', String(open));
    if (open) {
      addMenu.querySelector('button').focus();
    }
  }

  function setGenerationOpen(open) {
    if (open) {
      setVisible(thread, false);
      setVisible(addMenu, false);
      $('#dockAdd').setAttribute('aria-expanded', 'false');
    }
    setVisible(generationPanel, open);
  }

  function collapseThread() {
    setThreadOpen(false);
    $('#dockAdd').focus();
  }

  function closeAddMenu() {
    setAddMenuOpen(false);
    $('#dockAdd').focus();
  }

  function closeGenerationPanel() {
    setGenerationOpen(false);
    $('#dockAdd').focus();
  }

  function hasBlockingOverlay() {
    return $('#helpDialog').open
      || !$('#researchPanel').classList.contains('hidden')
      || !generationPanel.hidden
      || !addMenu.hidden;
  }

  function getParameterSummary(mode = state.generationMode) {
    const profile = generationProfiles[mode];
    return profile.groups.map((group, index) => {
      return group.values[state.generationParameters[mode][index]];
    }).join(' · ');
  }

  function renderGenerationControls() {
    const mode = state.generationMode;
    const profile = generationProfiles[mode];
    $('#parameterChips').innerHTML = profile.groups.map((group, index) => {
      const value = group.values[state.generationParameters[mode][index]];
      return `
        <button
          class="parameter-chip"
          type="button"
          data-parameter-index="${index}"
          aria-label="${group.key}：${value}，点击切换"
        >${group.key} · ${value}</button>
      `;
    }).join('');

    $('#parameterChips').querySelectorAll('[data-parameter-index]').forEach((button) => {
      button.addEventListener('click', () => cycleParameter(Number(button.dataset.parameterIndex)));
    });
  }

  function renderReferenceSelection() {
    $$('.reference-thumb').forEach((button, index) => {
      const selected = state.references[index];
      button.classList.toggle('selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
  }

  function setGenerationMode(mode, { persistCurrent = true } = {}) {
    if (persistCurrent) {
      state.generationPrompts[state.generationMode] = generationPrompt.value;
    }
    state.generationMode = mode;
    $$('.generation-tabs [data-generation-mode]').forEach((button) => {
      const selected = button.dataset.generationMode === mode;
      button.setAttribute('aria-pressed', String(selected));
    });
    generationPrompt.value = state.generationPrompts[mode];
    $('#generationCost').textContent = generationProfiles[mode].cost;
    $('#submitGeneration').setAttribute('aria-label', `提交${mode === 'video' ? '视频' : '图片'}生成`);
    renderGenerationControls();
    renderReferenceSelection();
  }

  function cycleParameter(index) {
    const mode = state.generationMode;
    const values = generationProfiles[mode].groups[index].values;
    state.generationParameters[mode][index] = (state.generationParameters[mode][index] + 1) % values.length;
    renderGenerationControls();
  }

  function toggleReference(index) {
    state.references[index] = !state.references[index];
    renderReferenceSelection();
  }

  function findOpenCanvasPosition(anchor, width, height) {
    const gap = 28;
    const startX = anchor ? anchor.x + anchor.width + 100 : 1060;
    const startY = anchor ? anchor.y + anchor.height + 100 : 380;
    let x = startX;
    let y = startY;

    for (let attempt = 0; attempt < 60; attempt += 1) {
      const blocked = state.nodes.some((node) => {
        if (node.type === 'frame') {
          return false;
        }
        return x < node.x + node.width + gap
          && x + width + gap > node.x
          && y < node.y + node.height + gap
          && y + height + gap > node.y;
      });
      if (!blocked) {
        return { x, y };
      }

      y += height + gap;
      if (y + height > worldSize.height - 70) {
        y = 110;
        x += width + 70;
      }
    }

    return {
      x: Math.max(40, Math.min(worldSize.width - width - 40, x)),
      y: Math.max(40, Math.min(worldSize.height - height - 40, y))
    };
  }

  function revealNodeAboveDock(node) {
    const scale = Number.isFinite(state.viewport.scale) && state.viewport.scale > 0
      ? state.viewport.scale
      : 0.6;
    const stageRect = stage.getBoundingClientRect();
    const dockRect = $('#agentDockWrap').getBoundingClientRect();
    const stageWidth = stageRect.width || stage.clientWidth || 960;
    const stageHeight = stageRect.height || stage.clientHeight || 640;
    const dockTop = dockRect.height > 0 && dockRect.top > stageRect.top
      ? dockRect.top - stageRect.top
      : stageHeight - 96;
    const margin = 18;
    const safeLeft = margin;
    const safeTop = margin;
    const safeRight = Math.max(safeLeft + 1, stageWidth - margin);
    const safeBottom = Math.max(safeTop + 1, Math.min(stageHeight - margin, dockTop - 12));
    const nodeLeft = state.viewport.x + node.x * scale;
    const nodeTop = state.viewport.y + node.y * scale;
    const nodeRight = nodeLeft + node.width * scale;
    const nodeBottom = nodeTop + node.height * scale;
    let shiftX = 0;
    let shiftY = 0;

    if (nodeRight > safeRight) {
      shiftX = safeRight - nodeRight;
    }
    if (nodeLeft + shiftX < safeLeft) {
      shiftX += safeLeft - (nodeLeft + shiftX);
    }
    if (nodeBottom > safeBottom) {
      shiftY = safeBottom - nodeBottom;
    }
    if (nodeTop + shiftY < safeTop) {
      shiftY += safeTop - (nodeTop + shiftY);
    }

    const nextX = state.viewport.x + shiftX;
    const nextY = state.viewport.y + shiftY;
    if (Number.isFinite(nextX) && Number.isFinite(nextY)) {
      state.viewport.x = nextX;
      state.viewport.y = nextY;
      updateViewport();
    }
  }

  function submitGeneration() {
    const prompt = generationPrompt.value.trim();
    if (!prompt) {
      showToast('请先描述要生成的内容');
      generationPrompt.focus();
      return;
    }

    const run = findNode('run');
    const mode = state.generationMode;
    const parameters = getParameterSummary(mode);
    const width = mode === 'video' ? 254 : 220;
    const height = 205;
    const position = findOpenCanvasPosition(run, width, height);
    const node = {
      id: nextId(mode),
      type: 'media',
      mediaType: mode,
      x: position.x,
      y: position.y,
      width,
      height,
      title: mode === 'video' ? '概念镜头 · 生成结果' : '视觉方向 · 生成结果',
      copy: prompt,
      meta: parameters,
      generated: true
    };

    state.nodes.push(node);
    if (run) {
      state.relations.push([run.id, node.id]);
    }
    state.selected.clear();
    state.selected.add(node.id);
    state.messages.push({
      kind: 'generation',
      mode,
      parameters,
      text: `已提交${mode === 'video' ? '视频' : '图片'}生成任务。`
    });
    state.messages.push({
      kind: 'agent',
      text: run
        ? `${mode === 'video' ? '视频' : '图片'}生成完成，结果已放入画布并关联 Agent Run。`
        : `${mode === 'video' ? '视频' : '图片'}生成完成，结果已放入当前画布。`
    });
    requestThreadScroll();
    setGenerationOpen(false);
    setThreadOpen(true);
    render();
    revealNodeAboveDock(node);
    agentPrompt.focus();
    markSaved();
    showToast(`${mode === 'video' ? '视频' : '图片'}生成完成，结果已放入画布`);
  }

  function focusAgentDock() {
    setAddMenuOpen(false);
    setGenerationOpen(false);
    if (state.messages.length) {
      setThreadOpen(true);
    }
    agentPrompt.focus();
  }

  function sendAgentMessage() {
    const text = agentPrompt.value.trim();
    if (!text) {
      showToast('请输入要交给 Agent 的任务');
      agentPrompt.focus();
      return;
    }

    setAddMenuOpen(false);
    setGenerationOpen(false);
    setThreadOpen(true);

    const run = findNode('run');
    if (!run) {
      showToast('Agent Run 已被删除；请重置演示后再发起任务');
      return;
    }
    if (run.status === 'running' || run.status === 'paused') {
      const status = run.status === 'running' ? '正在运行' : '已暂停';
      showToast(`当前任务${status}，请完成、继续或重试后再发起新任务`);
      return;
    }

    state.messages.push({ kind: 'user', text });
    state.messages.push({
      kind: 'agent',
      text: '我会先梳理上下文，再把执行过程和可编辑结果放回画布。'
    });
    requestThreadScroll();
    agentPrompt.value = '';
    resizeAgentInput();
    startAgentRun({ task: text });
  }

  function resetDemo() {
    stopRunTimer();
    state.nodes = clone(initialNodes);
    state.relations = [...initialRelations];
    state.selected.clear();
    state.viewport = { x: 80, y: 20, scale: 0.6 };
    state.context = 'selection';
    state.tool = 'select';
    state.spaceDown = false;
    state.messages = [];
    state.generationMode = 'image';
    state.generationPrompts = {
      image: generationProfiles.image.prompt,
      video: generationProfiles.video.prompt
    };
    state.generationParameters = {
      image: [0, 0, 0, 0],
      video: [0, 0, 0, 0]
    };
    state.references = [true, true, false];
    state.forceThreadScroll = false;
    agentPrompt.value = '';
    resizeAgentInput();
    stage.classList.remove('hand-tool', 'panning', 'dragging-node');
    setThreadOpen(false);
    setAddMenuOpen(false);
    setGenerationOpen(false);
    setGenerationMode('image', { persistCurrent: false });
    render();
    fitView({ notify: false });
    agentPrompt.focus();
    showToast('演示和 Agent 消息已重置');
  }

  function setContext(mode) {
    state.context = mode;
    updateContextUI();
  }

  function showView(view) {
    const editorActive = view === 'editor';
    const libraryView = $('#libraryView');
    const editorView = $('#editorView');
    state.activeView = view;
    document.body.classList.toggle('editor-active', editorActive);
    libraryView.classList.toggle('active', !editorActive);
    editorView.classList.toggle('active', editorActive);
    if (!editorActive) {
      setThreadOpen(false);
      setAddMenuOpen(false);
      setGenerationOpen(false);
    }
    (editorActive ? editorView : libraryView).focus({ preventScroll: true });
    if (editorActive) {
      (window.requestAnimationFrame || ((callback) => callback()))(() => fitView({ notify: false }));
    }
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
      <article class="insight-card"><h3>本地 infinite-canvas</h3><p>CSS 视口变换、节点/存储分层、导入导出是有效参考；避免反常框选、永久大 Dock、业务字段堆积和大页面耦合。</p></article>
    `,
    architecture: `
      <p class="panel-section-title">产品定位</p>
      <article class="insight-card"><h3>Agent 原生的多模态创作工作区</h3><p>资料、想法和产物存在同一空间。<strong>输入 → Agent Run → 可编辑结果</strong>，过程可见、来源可追溯。</p></article>
      <p class="panel-section-title">对象模型</p>
      <div class="model-flow">Workspace <span>→</span> Project <span>→</span> CanvasDocument <span>→</span> CanvasItem / Relation / AgentRun</div>
      <article class="insight-card">
        <h3>扩展协议</h3>
        <ul class="protocol-list">
          <li>节点注册：schema、默认数据、渲染、检查器与迁移</li>
          <li>动作注册：接受对象、输入输出、结果落位策略</li>
          <li>Skill：能力、执行模式、成本和可见性</li>
          <li>Importer / Exporter / Previewer / Indexer 独立扩展</li>
        </ul>
      </article>
      <article class="insight-card"><h3>React Flow 初步选型</h3><p>MIT 许可，适合富 DOM 节点和 Agent 状态；内置拖拽、视口、多选、MiniMap、Controls。它只承担交互渲染，领域模型保持独立。</p></article>
      <article class="insight-card"><h3>AGPL 风险</h3><p>本地 infinite-canvas 为 AGPL-3.0。若修改版支持远程网络交互，§13 要求向相关用户提供获取对应源码的机会；具体边界需法务评估。<strong>仅作为行为和模块边界参考，不直接复制源码。</strong></p></article>
    `,
    roadmap: `
      <p class="panel-section-title">从验证到生态</p>
      <article class="insight-card phase"><span class="phase-index">A</span><div><h3>交互原型</h3><p>中性暗色 Stage、底部 Agent Dock、媒体生成和结果落位，验证产品表达。</p></div></article>
      <article class="insight-card phase"><span class="phase-index">B</span><div><h3>画布 MVP</h3><p>持久化、统一命令历史、素材引用、自动保存和节点/动作注册表。</p></div></article>
      <article class="insight-card phase"><span class="phase-index">C</span><div><h3>Agent 原生能力</h3><p>选区/整图上下文、SSE 状态、画布命令、暂停重试与来源追踪。</p></div></article>
      <article class="insight-card phase"><span class="phase-index">D</span><div><h3>协作与生态</h3><p>实时协作、只读分享、创作回放、Playbook / Skill 市场与用量策略。</p></div></article>
    `
  };

  function isElementInActiveView(element) {
    if (!element || !element.isConnected) {
      return false;
    }
    const view = element.closest('.view');
    return !view || view.classList.contains('active');
  }

  function setResearchOpen(open, { opener = null, restoreFocus = true } = {}) {
    const panel = $('#researchPanel');
    const wasOpen = !panel.classList.contains('hidden');
    if (open) {
      researchOpener = opener && opener.isConnected ? opener : document.activeElement;
    }
    panel.classList.toggle('hidden', !open);
    panel.setAttribute('aria-hidden', String(!open));
    panel.inert = !open;
    if (open) {
      panel.removeAttribute('inert');
      $('#closeResearch').focus();
    } else {
      panel.setAttribute('inert', '');
      if (restoreFocus && wasOpen) {
        const focusTarget = isElementInActiveView(researchOpener)
          ? researchOpener
          : $('#researchButton');
        if (isElementInActiveView(focusTarget)) {
          focusTarget.focus();
        }
      }
      researchOpener = null;
    }
  }

  function setPanelTab(tab) {
    $$('.panel-tabs button').forEach((button) => {
      const selected = button.dataset.panelTab === tab;
      button.classList.toggle('active', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    $('#panelContent').innerHTML = panelData[tab];
  }

  function openResearch(tab = 'research', opener = document.activeElement) {
    setResearchOpen(true, { opener });
    setPanelTab(tab);
  }

  function filterLibrary(filter) {
    $$('[data-library-filter]').forEach((button) => {
      const selected = button.dataset.libraryFilter === filter;
      button.classList.toggle('active', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    $$('[data-library-owner]').forEach((card) => {
      card.hidden = filter !== 'all' && card.dataset.libraryOwner !== filter;
    });
    showToast(filter === 'all' ? '正在展示全部画布' : '画布筛选已更新');
  }

  function handleToolbarAction(action) {
    if (!state.selected.size) {
      return;
    }

    if (action === 'edit') {
      showToast('编辑模式已准备就绪（原型模拟）');
    } else if (action === 'ai') {
      setThreadOpen(true);
      agentPrompt.focus();
      showToast('Agent 已读取当前选区');
    } else if (action === 'context') {
      setContext('selection');
      showToast(`已将 ${state.selected.size} 个对象加入当前上下文`);
    } else {
      showToast('更多对象操作将在检查器中提供（原型模拟）');
    }
  }

  function createTextNode() {
    const scale = state.viewport.scale;
    const node = {
      id: nextId('text'),
      type: 'text',
      x: Math.round((stage.clientWidth / 2 - state.viewport.x) / scale - 120),
      y: Math.round((stage.clientHeight / 2 - state.viewport.y) / scale - 52),
      width: 240,
      height: 104,
      title: '新建文本',
      copy: '在完整产品中可直接编辑此文本。',
      meta: '文本 · 新建'
    };
    state.nodes.push(node);
    setSelection([node.id]);
    markSaved();
    showToast('已在当前视口中心创建文本对象');
  }

  function resizeAgentInput() {
    const minimumHeight = 37;
    const maximumHeight = 104;
    agentPrompt.style.height = 'auto';
    const measuredHeight = Number(agentPrompt.scrollHeight) || minimumHeight;
    const nextHeight = Math.max(minimumHeight, Math.min(measuredHeight, maximumHeight));
    agentPrompt.style.height = `${nextHeight}px`;
    agentPrompt.style.overflowY = measuredHeight > maximumHeight ? 'auto' : 'hidden';
  }

  function handleAddAction(action) {
    if (action === 'text') {
      setAddMenuOpen(false);
      createTextNode();
      return;
    }

    if (action === 'image' || action === 'video') {
      setGenerationMode(action);
      setGenerationOpen(true);
      generationPrompt.focus();
      return;
    }

    setAddMenuOpen(false);
    showToast(action === 'file'
      ? '文件导入将在完整产品中打开（原型模拟）'
      : 'Frame 创建将在完整产品中提供（原型模拟）');
  }

  function bindControls() {
    $$('[data-view="library"]').forEach((button) => {
      button.addEventListener('click', () => showView('library'));
    });
    $$('[data-open-editor]').forEach((button) => {
      button.addEventListener('click', () => showView('editor'));
    });

    $('#createFromIdea').addEventListener('click', () => {
      const idea = $('#ideaInput').value.trim();
      if (!idea) {
        showToast('请先描述想完成的工作');
        $('#ideaInput').focus();
        return;
      }
      showView('editor');
      showToast('已根据目标创建「研究与归纳」画布');
    });
    $('#ideaInput').addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.isComposing) {
        $('#createFromIdea').click();
      }
    });
    $$('.template-card').forEach((card) => {
      card.addEventListener('click', () => {
        $$('.template-card').forEach((item) => {
          item.classList.remove('selected');
          item.setAttribute('aria-pressed', 'false');
        });
        card.classList.add('selected');
        card.setAttribute('aria-pressed', 'true');
        showToast(`已选择「${card.dataset.template}」模板`);
      });
    });
    $$('[data-library-filter]').forEach((button) => {
      button.addEventListener('click', () => filterLibrary(button.dataset.libraryFilter));
    });
    $('#searchCanvases').addEventListener('click', () => showToast('画布搜索将在完整产品中打开（原型模拟）'));
    $('#gridViewButton').addEventListener('click', () => showToast('当前使用网格视图（原型模拟）'));
    $('#allTemplatesButton').addEventListener('click', () => showToast('全部模板库将在完整产品中打开（原型模拟）'));
    $('#aiNavButton').addEventListener('click', () => showToast('AI 控制台入口将在完整产品中打开（原型模拟）'));
    $('#assetsNavButton').addEventListener('click', () => showToast('资产库入口将在完整产品中打开（原型模拟）'));
    $('#workspaceAvatar').addEventListener('click', () => showToast('工作区菜单将在完整产品中打开（原型模拟）'));

    $('#researchButton').addEventListener('click', (event) => openResearch('research', event.currentTarget));
    $('#libraryResearchButton').addEventListener('click', (event) => openResearch('research', event.currentTarget));
    $('#closeResearch').addEventListener('click', () => setResearchOpen(false));
    $$('.panel-tabs button').forEach((button) => {
      button.addEventListener('click', () => setPanelTab(button.dataset.panelTab));
    });

    $('#selectionContext').addEventListener('click', () => setContext('selection'));
    $('#wholeContext').addEventListener('click', () => setContext('whole'));
    $('#resetDemo').addEventListener('click', resetDemo);
    $('#collapseThread').addEventListener('click', collapseThread);

    $('#dockAdd').addEventListener('click', () => setAddMenuOpen(addMenu.hidden));
    $$('.add-menu [data-add-action]').forEach((button) => {
      button.addEventListener('click', () => handleAddAction(button.dataset.addAction));
    });
    $('#closeGenerationPanel').addEventListener('click', closeGenerationPanel);
    $$('.generation-tabs [data-generation-mode]').forEach((button) => {
      button.addEventListener('click', () => setGenerationMode(button.dataset.generationMode));
    });
    $$('.reference-thumb').forEach((button, index) => {
      button.addEventListener('click', () => toggleReference(index));
    });
    $('#addReference').addEventListener('click', () => showToast('参考素材选择器将在完整产品中打开（原型模拟）'));
    $('#submitGeneration').addEventListener('click', submitGeneration);
    generationPrompt.addEventListener('input', () => {
      state.generationPrompts[state.generationMode] = generationPrompt.value;
    });

    $('#sendAgent').addEventListener('click', sendAgentMessage);
    agentPrompt.addEventListener('focus', focusAgentDock);
    agentPrompt.addEventListener('click', focusAgentDock);
    agentPrompt.addEventListener('input', resizeAgentInput);
    agentPrompt.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        sendAgentMessage();
      }
    });

    $('#zoomIn').addEventListener('click', () => {
      const rect = stage.getBoundingClientRect();
      zoomAt(rect.left + stage.clientWidth / 2, rect.top + stage.clientHeight / 2, state.viewport.scale * 1.15);
    });
    $('#zoomOut').addEventListener('click', () => {
      const rect = stage.getBoundingClientRect();
      zoomAt(rect.left + stage.clientWidth / 2, rect.top + stage.clientHeight / 2, state.viewport.scale / 1.15);
    });
    $('#fitView').addEventListener('click', fitView);
    $('#resetZoom').addEventListener('click', () => {
      const rect = stage.getBoundingClientRect();
      zoomAt(rect.left + stage.clientWidth / 2, rect.top + stage.clientHeight / 2, 1);
    });

    $$('.selection-toolbar [data-action]').forEach((button) => {
      button.addEventListener('click', () => handleToolbarAction(button.dataset.action));
    });
    $('#helpButton').addEventListener('click', () => $('#helpDialog').showModal());
    $('#closeHelp').addEventListener('click', () => {
      $('#helpDialog').close();
      $('#helpButton').focus();
    });
    $('#shareButton').addEventListener('click', () => showToast('分享链接已复制（原型模拟）'));
    $('#exportButton').addEventListener('click', () => showToast('导出 PNG / PDF / JSON 将在完整产品中提供（原型模拟）'));
    $('#editorMoreButton').addEventListener('click', () => showToast('更多编辑器操作将在完整产品中提供（原型模拟）'));

    stage.addEventListener('pointerdown', onStagePointerDown);
    window.addEventListener('resize', updateViewport);
    window.addEventListener('keydown', onKeyboardShortcut);
  }

  function onKeyboardShortcut(event) {
    const modifier = event.metaKey || event.ctrlKey;
    if (modifier && event.key.toLowerCase() === 'k') {
      if (state.activeView !== 'editor') {
        return;
      }
      event.preventDefault();
      if ($('#helpDialog').open) {
        $('#helpDialog').close();
      }
      if (!$('#researchPanel').classList.contains('hidden')) {
        setResearchOpen(false, { restoreFocus: false });
      }
      setThreadOpen(true);
      agentPrompt.focus();
      return;
    }

    if (event.key === 'Escape') {
      if ($('#helpDialog').open) {
        $('#helpDialog').close();
        $('#helpButton').focus();
      } else if (!$('#researchPanel').classList.contains('hidden')) {
        setResearchOpen(false);
      } else if (!generationPanel.hidden) {
        closeGenerationPanel();
      } else if (!addMenu.hidden) {
        closeAddMenu();
      } else if (!thread.hidden) {
        collapseThread();
      } else if (state.activeView === 'editor') {
        state.selected.clear();
        render();
      }
      return;
    }

    if (state.activeView !== 'editor'
      || isInteractiveControl(event.target)
      || hasBlockingOverlay()) {
      return;
    }

    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault();
      deleteSelection();
    } else if (event.key === '0') {
      event.preventDefault();
      fitView();
    } else if (event.key === '1') {
      event.preventDefault();
      const rect = stage.getBoundingClientRect();
      zoomAt(rect.left + stage.clientWidth / 2, rect.top + stage.clientHeight / 2, 1);
    } else if (event.key.toLowerCase() === 'f') {
      event.preventDefault();
      focusSelection();
    } else if (event.key.toLowerCase() === 'v') {
      event.preventDefault();
      setTool('select');
    } else if (event.key.toLowerCase() === 'h') {
      event.preventDefault();
      setTool('hand');
    } else if (event.key.toLowerCase() === 't') {
      event.preventDefault();
      createTextNode();
    }
  }

  function init() {
    bindControls();
    installPanAndZoom();
    setResearchOpen(false);
    setThreadOpen(false);
    setAddMenuOpen(false);
    setGenerationOpen(false);
    setGenerationMode('image');
    renderReferenceSelection();
    render();
    resizeAgentInput();
  }

  init();
})();
