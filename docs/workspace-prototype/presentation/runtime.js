// Review toolbar, screen switching, annotations, and shared dialog hosting.
const PRESENTATION_CONFIG = {"icons":{"chevron-left":"<path d=\"M15 6l-6 6l6 6\" />","chevron-right":"<path d=\"M9 6l6 6l-6 6\" />"},"t":{"screens":"屏幕","state":"状态","width":"宽度","scheme":"配色","light":"浅色","dark":"深色","notes":"标注","mobile":"手机","tablet":"平板","desktop":"桌面","wireframe":"线框","mockup":"界面","toolbar":"界面预览","toastRegion":"提示","toastClose":"关闭提示","captionSep":" · ","defaultState":"默认"}};

export function startPresentation() {
  const CONFIG = PRESENTATION_CONFIG;
  var ICONS = CONFIG.icons || {};
  var T = CONFIG.t || {};
  var html = document.documentElement;
  var frame = document.getElementById('ui-frame');
  var bar = document.getElementById('ui-bar');
  var notesEl = document.getElementById('ui-notes');
  if (!frame) return;

  function t(key) {
    return T[key] == null ? key : T[key];
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (name) {
        if (attrs[name] == null || attrs[name] === false) return;
        node.setAttribute(name, attrs[name] === true ? '' : String(attrs[name]));
      });
    }
    if (children) {
      (Array.isArray(children) ? children : [children]).forEach(function (child) {
        if (child == null || child === false) return;
        node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
      });
    }
    return node;
  }

  function setHidden(node, hidden) {
    if (!node) return;
    if (hidden) node.setAttribute('hidden', '');
    else node.removeAttribute('hidden');
  }

  function splitList(value) {
    return String(value || '')
      .split(',')
      .map(function (item) { return item.trim(); })
      .filter(Boolean);
  }

  var uid = 0;
  var state = {
    screenId: null,
    stateName: null,
    width: 'desktop',
    notesHidden: true,
    exporting: false,
    printing: false,
    snapshot: null,
    dialogs: [],
    toastTimers: new Set(),
    nextTimers: new Set(),
  };

  /* ---------- 图标 ---------- */

  function iconSvg(name) {
    var inner = ICONS[name];
    if (!inner) return '';
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + inner + '</svg>';
  }

  function renderIcons(root) {
    var scope = root || document;
    scope.querySelectorAll('i.icon[data-icon]').forEach(function (node) {
      if (node.querySelector('svg')) return;
      node.setAttribute('aria-hidden', 'true');
      node.innerHTML = iconSvg(node.getAttribute('data-icon') || '');
    });
  }

  /* ---------- 屏幕与状态 ---------- */

  function allScreens() {
    return Array.prototype.filter.call(frame.children, function (node) {
      return node.classList && node.classList.contains('screen');
    });
  }

  function findScreen(id) {
    return allScreens().find(function (screen) { return screen.id === id; }) || null;
  }

  function currentScreen() {
    return findScreen(state.screenId);
  }

  function statesOf(screen) {
    return screen ? splitList(screen.getAttribute('data-states')) : [];
  }

  function applyWhen(screen, stateName) {
    screen.querySelectorAll('[data-when]').forEach(function (node) {
      var list = splitList(node.getAttribute('data-when'));
      setHidden(node, list.indexOf(stateName) < 0);
    });
  }

  // data-next 是状态切换而不是动画；prefers-reduced-motion 下也保留同样的延迟。
  var NEXT_DELAY = 1200;

  function cancelNextTimers() {
    state.nextTimers.forEach(function (timer) { clearTimeout(timer); });
    state.nextTimers.clear();
  }

  function scheduleNext(screen) {
    cancelNextTimers();
    if (!screen || state.exporting || state.printing) return;
    if (screen !== currentScreen()) return;
    var stateName = state.stateName;
    screen.querySelectorAll('[data-when][data-next]').forEach(function (node) {
      var next = (node.getAttribute('data-next') || '').trim();
      if (!next || next === stateName) return;
      if (statesOf(screen).indexOf(next) < 0) return;
      if (!isVisible(node)) return;
      var timer = setTimeout(function () {
        state.nextTimers.delete(timer);
        if (state.exporting || state.printing) return;
        if (currentScreen() !== screen || state.stateName !== stateName) return;
        if (!isVisible(node)) return;
        setState(next);
      }, NEXT_DELAY);
      state.nextTimers.add(timer);
    });
  }

  // 同一组导航里只有一项是当前页：单屏演示稿常把未实现的入口也指向当前屏，
  // 这时保留作者标的 aria-current，没有就取第一个指向当前屏的项。
  function syncNav(root, id) {
    var groups = new Map();
    root.querySelectorAll('.side-nav-item, .tab-bar-item').forEach(function (link) {
      var nav = link.closest('.side-nav, .tab-bar') || root;
      if (!groups.has(nav)) groups.set(nav, []);
      groups.get(nav).push(link);
    });
    groups.forEach(function (links) {
      var matches = links.filter(function (link) {
        var target = link.getAttribute('data-go') || (link.getAttribute('href') || '').replace(/^#/, '');
        return target === id;
      });
      var current = matches.find(function (link) { return link.getAttribute('aria-current') === 'page'; }) || matches[0];
      links.forEach(function (link) {
        if (link === current) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
      });
    });
  }

  function syncScreenNavs() {
    allScreens().forEach(function (screen) { syncNav(screen, screen.id); });
  }

  function writeHash(id) {
    if (!id) return;
    try {
      history.replaceState(null, '', '#' + id);
    } catch (err) {
      /* file:// 或沙箱里可能不允许；屏幕切换本身不受影响 */
    }
  }

  function current() {
    return { screen: state.screenId, state: state.stateName };
  }

  function notifyChange() {
    document.dispatchEvent(new CustomEvent('htmlview:change', { detail: current() }));
  }

  function showScreen(id, stateName, options) {
    var screens = allScreens();
    if (!screens.length) return;
    if (state.dialogs.length) closeAllDialogs();
    var target = id ? findScreen(id) : screens[0];
    if (!target) return;
    var previous = current();
    screens.forEach(function (screen) { setHidden(screen, screen !== target); });
    state.screenId = target.id;
    var names = statesOf(target);
    state.stateName = stateName && names.indexOf(stateName) >= 0 ? stateName : (names[0] || '');
    applyWhen(target, state.stateName);
    relayoutViz(target);
    scheduleNext(target);
    closeMenus();
    if (!(options && options.noHash)) writeHash(target.id);
    syncScreenNavs();
    scheduleFloating();
    syncToolbar();
    renderNotes();
    if (!(options && options.silent)) {
      try { target.focus({ preventScroll: true }); } catch (err) { target.focus(); }
    }
    if (!(options && options.initial) && (previous.screen !== state.screenId || previous.state !== state.stateName)) notifyChange();
  }

  function setState(name) {
    var screen = currentScreen();
    if (!screen) return;
    var names = statesOf(screen);
    if (names.indexOf(name) < 0) return;
    if (state.dialogs.length) closeAllDialogs();
    closeMenus();
    var changed = state.stateName !== name;
    state.stateName = name;
    applyWhen(screen, name);
    relayoutViz(screen);
    scheduleNext(screen);
    syncToolbar();
    renderNotes();
    var active = document.activeElement;
    if (active && active !== document.body && !isVisible(active)) screen.focus();
    scheduleFloating();
    if (changed) notifyChange();
  }

  function syncToggleTriggers(id, expanded) {
    document.querySelectorAll('[data-toggle]').forEach(function (trigger) {
      if (trigger.getAttribute('data-toggle') === id) trigger.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    });
  }

  // 菜单是浮层式操作列表，切屏、切状态、点外部、Esc、打开弹窗都要收起；
  // 其他 data-toggle 目标是展开块，只由自己的触发按钮切换，切屏后保持展开。
  function isMenuTarget(target) {
    return !!(target && target.classList && target.classList.contains('menu'));
  }

  function closeMenus() {
    document.querySelectorAll('[data-toggle]').forEach(function (trigger) {
      var target = document.getElementById(trigger.getAttribute('data-toggle'));
      if (isMenuTarget(target)) {
        setHidden(target, true);
        trigger.setAttribute('aria-expanded', 'false');
      }
    });
  }

  function closeMenusOutside(node) {
    document.querySelectorAll('[data-toggle]').forEach(function (trigger) {
      var target = document.getElementById(trigger.getAttribute('data-toggle'));
      if (!isMenuTarget(target) || target.hidden) return;
      if (target.contains(node) || trigger.contains(node)) return;
      setHidden(target, true);
      trigger.setAttribute('aria-expanded', 'false');
    });
  }

  /* ---------- 标注 ---------- */

  function isVisible(node) {
    if (!node || node.closest('[hidden]')) return false;
    if (node.closest('dialog:not([open])')) return false;
    var style = getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden') return false;
    return !!(node.offsetWidth || node.offsetHeight || node.getClientRects().length);
  }

  function visibleNotes(screen) {
    if (!screen) return [];
    return Array.prototype.filter.call(screen.querySelectorAll('[data-note]'), isVisible);
  }

  function updateNotesButton(count) {
    if (!ui.notesBtn) return;
    var show = count > 0;
    ui.notesBtn.hidden = !show;
    ui.notesCount.textContent = String(count);
    var pressed = show && !state.notesHidden;
    ui.notesBtn.setAttribute('aria-pressed', pressed ? 'true' : 'false');
    ui.notesBtn.setAttribute('aria-label', t('notes') + ' (' + count + ')');
  }

  // KChart 自行监听尺寸；屏幕、状态和主题变更后显式请求重新排版。
  function relayoutViz() {
    var api = window.KChart;
    if (!api || typeof api.relayout !== 'function') return;
    try { api.relayout(); } catch (err) { /* KChart renders diagnostics in place. */ }
  }

  function renderNotes() {
    var screen = currentScreen();
    if (!screen || !notesEl) return;
    screen.querySelectorAll('[data-note]').forEach(function (node) {
      node.removeAttribute('data-ui-note');
    });
    var items = visibleNotes(screen);
    items.forEach(function (node, index) {
      node.setAttribute('data-ui-note', String(index + 1));
    });
    updateNotesButton(items.length);
    notesEl.textContent = '';
    if (!items.length || state.notesHidden || state.exporting) {
      notesEl.hidden = true;
      syncNoteBadges();
      return;
    }
    notesEl.hidden = false;
    var title = el('h2', { class: 'ui-notes-title' }, t('notes'));
    var list = el('ol', { class: 'ui-notes-list' });
    items.forEach(function (node, index) {
      var text = node.getAttribute('data-note') || '';
      list.appendChild(el('li', { class: 'ui-notes-item' }, [
        el('span', { class: 'ui-notes-num' }, String(index + 1)),
        el('span', { class: 'ui-notes-text' }, text),
      ]));
    });
    notesEl.appendChild(title);
    notesEl.appendChild(list);
    syncNoteBadges();
  }

  /* ---------- 标注角标浮层 ---------- */

  var noteLayer = null;

  function ensureNoteLayer() {
    if (noteLayer) return noteLayer;
    noteLayer = el('div', { class: 'ui-note-layer', 'aria-hidden': 'true', hidden: true });
    frame.appendChild(noteLayer);
    return noteLayer;
  }

  /** 角标属于哪个屏幕/导出图；用它作为裁切边界，保证不越出屏幕可见区域。 */
  function noteRoot(node) {
    return node.closest('.screen') || node.closest('.ui-shot') || frame;
  }

  function syncNoteBadges() {
    var layer = ensureNoteLayer();
    var frameRect = frame.getBoundingClientRect();
    var originX = frameRect.left + frame.clientLeft;
    var originY = frameRect.top + frame.clientTop;
    var nodes = Array.prototype.filter.call(
      frame.querySelectorAll('[data-ui-note]'),
      function (node) {
        if (!isVisible(node)) return false;
        var screen = node.closest('.ui-shot') || node.closest('.screen');
        return !!screen;
      }
    );

    layer.textContent = '';
    layer.hidden = nodes.length === 0;
    if (!nodes.length) return;

    nodes.forEach(function (node) {
      var badge = el('span', { class: 'ui-note-badge' }, node.getAttribute('data-ui-note') || '');
      layer.appendChild(badge);
      // 以被标注元素的右上角为中心；靠近屏幕边缘时整体收回屏幕内，避免被裁切。
      var root = noteRoot(node);
      var rootRect = root.getBoundingClientRect();
      var rect = node.getBoundingClientRect();
      var w = badge.offsetWidth;
      var h = badge.offsetHeight;
      var left = rect.right - originX - w / 2;
      var top = rect.top - originY - h / 2;
      var minLeft = rootRect.left - originX + 1;
      var minTop = rootRect.top - originY + 1;
      var maxLeft = rootRect.right - originX - w - 1;
      var maxTop = rootRect.bottom - originY - h - 1;
      if (maxLeft < minLeft) maxLeft = minLeft;
      if (maxTop < minTop) maxTop = minTop;
      badge.style.left = Math.round(Math.max(minLeft, Math.min(maxLeft, left))) + 'px';
      badge.style.top = Math.round(Math.max(minTop, Math.min(maxTop, top))) + 'px';
    });
  }

  /* ---------- 工具栏 ---------- */

  var ui = {};

  function deviceLabel(device) {
    if (device === 'desktop') return t('desktop');
    if (device === 'tablet') return t('tablet');
    return t('mobile');
  }

  function fidelityLabel() {
    return html.getAttribute('data-fidelity') === 'wireframe' ? t('wireframe') : t('mockup');
  }

  function buildSelectTool(id, label) {
    var wrap = el('div', { class: 'ui-tool' });
    var caption = el('label', { class: 'ui-tool-label', for: id }, label);
    var box = el('span', { class: 'ui-select-wrap' });
    var select = el('select', { id: id, class: 'ui-preview-select' });
    box.appendChild(select);
    wrap.appendChild(caption);
    wrap.appendChild(box);
    return { wrap: wrap, select: select };
  }

  function buildSegTool(label, entries, onPick) {
    var wrap = el('div', { class: 'ui-tool' });
    var caption = el('span', { class: 'ui-tool-label' }, label);
    var seg = el('div', { class: 'ui-seg', role: 'group', 'aria-label': label });
    entries.forEach(function (entry) {
      var button = el('button', { type: 'button', class: 'ui-seg-btn', 'aria-pressed': 'false' }, entry.label);
      button.addEventListener('click', function () { onPick(entry.value); });
      seg.appendChild(button);
    });
    wrap.appendChild(caption);
    wrap.appendChild(seg);
    return { wrap: wrap, seg: seg };
  }

  function setSegValue(seg, value, labels) {
    Array.prototype.forEach.call(seg.querySelectorAll('button'), function (button, index) {
      button.setAttribute('aria-pressed', labels[index] === value ? 'true' : 'false');
    });
  }

  function buildToolbar() {
    if (!bar) return;
    bar.textContent = '';
    bar.setAttribute('aria-label', t('toolbar'));
    var title = el('span', { class: 'ui-bar-title' });
    var meta = el('span', { class: 'ui-bar-meta' });
    var idBox = el('div', { class: 'ui-bar-id' }, [title, meta]);
    var tools = el('div', { class: 'ui-bar-tools' });

    var screenTool = buildSelectTool('ui-screen-select', t('screens'));
    var stateTool = buildSelectTool('ui-state-select', t('state'));
    var widthTool = buildSegTool(t('width'), [
      { value: 'mobile', label: t('mobile') },
      { value: 'tablet', label: t('tablet') },
      { value: 'desktop', label: t('desktop') },
    ], function (value) { setWidth(value); });
    var schemeTool = buildSegTool(t('scheme'), [
      { value: 'light', label: t('light') },
      { value: 'dark', label: t('dark') },
    ], function (value) { setScheme(value); });
    var notesBtn = el('button', {
      type: 'button',
      class: 'ui-tool ui-notes-toggle',
      'aria-controls': 'ui-notes',
      'aria-pressed': 'true',
    }, [el('span', {}, t('notes')), el('span', { class: 'ui-count' }, '0')]);
    var notesCount = notesBtn.querySelector('.ui-count');

    tools.appendChild(screenTool.wrap);
    tools.appendChild(stateTool.wrap);
    tools.appendChild(widthTool.wrap);
    tools.appendChild(schemeTool.wrap);
    tools.appendChild(notesBtn);
    bar.appendChild(idBox);
    bar.appendChild(tools);

    ui = {
      title: title,
      meta: meta,
      screenSelect: screenTool.select,
      screenWrap: screenTool.wrap,
      stateSelect: stateTool.select,
      stateWrap: stateTool.wrap,
      widthSeg: widthTool.seg,
      widthWrap: widthTool.wrap,
      schemeSeg: schemeTool.seg,
      schemeWrap: schemeTool.wrap,
      notesBtn: notesBtn,
      notesCount: notesCount,
      stateKey: null,
    };

    var screens = allScreens();
    ui.screenSelect.textContent = '';
    screens.forEach(function (screen) {
      var option = el('option', { value: screen.id }, screen.getAttribute('data-title') || screen.id);
      ui.screenSelect.appendChild(option);
    });
    if (!screens.length) ui.screenWrap.hidden = true;
    ui.screenSelect.addEventListener('change', function () {
      showScreen(ui.screenSelect.value, null);
    });
    ui.stateSelect.addEventListener('change', function () {
      setState(ui.stateSelect.value);
    });
    ui.notesBtn.addEventListener('click', function () {
      state.notesHidden = !state.notesHidden;
      renderNotes();
      syncToolbar();
    });
  }

  function syncToolbar() {
    if (!ui.title) return;
    ui.title.textContent = document.title || t('toolbar');
    var device = html.getAttribute('data-device') || 'mobile';
    var meta = [fidelityLabel()];
    meta.push(device === 'responsive' ? deviceLabel(state.width) : deviceLabel(device));
    ui.meta.textContent = meta.join(t('captionSep'));

    var screen = currentScreen();
    if (screen && ui.screenSelect.value !== screen.id) ui.screenSelect.value = screen.id;
    ui.screenSelect.disabled = allScreens().length < 2;

    var names = screen ? statesOf(screen) : [];
    var stateKey = screen ? screen.id + '::' + names.join(',') : '';
    if (ui.stateKey !== stateKey) {
      ui.stateKey = stateKey;
      ui.stateSelect.textContent = '';
      names.forEach(function (name) {
        ui.stateSelect.appendChild(el('option', { value: name }, name));
      });
    }
    ui.stateWrap.hidden = names.length < 2;
    if (names.length) ui.stateSelect.value = state.stateName;

    ui.widthWrap.hidden = device !== 'responsive';
    setSegValue(ui.widthSeg, state.width, ['mobile', 'tablet', 'desktop']);

    var scheme = html.getAttribute('data-scheme') === 'dark' ? 'dark' : 'light';
    ui.schemeWrap.hidden = true; // The product preview uses the current KK Studio dark palette.
    setSegValue(ui.schemeSeg, scheme, ['light', 'dark']);

    updateNotesButton(visibleNotes(screen).length);
    syncCombo(ui.screenSelect);
    syncCombo(ui.stateSelect);
  }

  function setWidth(value) {
    if ((html.getAttribute('data-device') || 'mobile') !== 'responsive') return;
    if (['mobile', 'tablet', 'desktop'].indexOf(value) < 0) return;
    state.width = value;
    document.querySelector('.ui-shell').setAttribute('data-width', value);
    relayoutViz(document);
    syncToolbar();
    scheduleFloating();
  }

  function setScheme(value) {
    if (html.getAttribute('data-fidelity') === 'wireframe') return;
    if (value !== 'light' && value !== 'dark') return;
    html.setAttribute('data-scheme', value);
    relayoutViz(document);
    syncToolbar();
  }

  // 默认贴合实际窗口；手机、平板按钮可在大屏上模拟固定宽度。
  function initialResponsiveWidth() { return 'desktop'; }

  function syncWidthFromDevice() {
    var device = html.getAttribute('data-device') || 'mobile';
    if (device === 'responsive') state.width = initialResponsiveWidth();
    else state.width = device === 'desktop' ? 'desktop' : 'mobile';
    var shell = document.querySelector('.ui-shell');
    if (shell) shell.setAttribute('data-width', state.width);
    relayoutViz(document);
  }

  /* ---------- 弹窗 ---------- */

  var scrim = null;

  function ensureScrim() {
    if (scrim) return scrim;
    scrim = el('div', { class: 'ui-scrim', 'aria-hidden': 'true', hidden: true });
    scrim.addEventListener('click', function () {
      var top = state.dialogs[state.dialogs.length - 1];
      if (top) closeDialog(top);
    });
    frame.appendChild(scrim);
    return scrim;
  }

  function focusable(root) {
    return Array.prototype.filter.call(
      root.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'),
      function (node) { return isVisible(node); }
    );
  }

  function openDialog(dialog, trigger) {
    if (!dialog || dialog.tagName !== 'DIALOG' || dialog.open) return;
    closeMenus();
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    if (!dialog.hasAttribute('aria-label') && !dialog.hasAttribute('aria-labelledby')) {
      var heading = dialog.querySelector('h1, h2, h3, h4');
      if (heading) {
        if (!heading.id) heading.id = 'ui-dlg-title-' + (++uid);
        dialog.setAttribute('aria-labelledby', heading.id);
      }
    }
    dialog.__uiTrigger = trigger || document.activeElement;
    dialog.show();
    relayoutViz(dialog);
    state.dialogs.push(dialog);
    ensureScrim().hidden = false;
    var list = focusable(dialog);
    if (list.length) list[0].focus();
    else {
      if (!dialog.hasAttribute('tabindex')) dialog.setAttribute('tabindex', '-1');
      dialog.focus();
    }
    updateFloating();
    renderNotes();
  }

  function closeDialog(dialog, options) {
    if (!dialog || !dialog.open) return;
    dialog.close();
    state.dialogs = state.dialogs.filter(function (item) { return item !== dialog; });
    if (scrim && !state.dialogs.length) scrim.hidden = true;
    updateFloating();
    var trigger = dialog.__uiTrigger;
    delete dialog.__uiTrigger;
    if (!(options && options.silent) && trigger && trigger.isConnected && isVisible(trigger)) {
      try { trigger.focus({ preventScroll: true }); } catch (err) { trigger.focus(); }
    }
    renderNotes();
  }

  function closeAllDialogs() {
    state.dialogs.slice().reverse().forEach(function (dialog) {
      closeDialog(dialog, { silent: true });
    });
  }

  /* ---------- 提示 ---------- */

  var toastHost = null;

  function toastHostNode() {
    if (toastHost) return toastHost;
    toastHost = el('div', {
      class: 'ui-toast-host',
      'aria-live': 'polite',
      'aria-label': t('toastRegion'),
    });
    frame.appendChild(toastHost);
    return toastHost;
  }

  function dismissToast(node) {
    if (!node || !node.parentNode) return;
    if (node.__uiTimer) clearTimeout(node.__uiTimer);
    state.toastTimers.delete(node.__uiTimer);
    node.classList.add('ui-toast-out');
    setTimeout(function () {
      if (node.parentNode) node.parentNode.removeChild(node);
    }, 170);
  }

  function showToast(message) {
    var host = toastHostNode();
    var visible = Array.from(host.children).filter(function (node) { return !node.classList.contains('ui-toast-out'); });
    while (visible.length >= 3) dismissToast(visible.shift());
    var close = el('button', { type: 'button', class: 'ui-toast-close', 'aria-label': t('toastClose') }, el('i', { class: 'icon', 'data-icon': 'close' }));
    var node = el('div', { class: 'ui-toast' }, [
      el('span', { class: 'ui-toast-text' }, message),
      close,
    ]);
    close.addEventListener('click', function () { dismissToast(node); });
    node.__uiTimer = setTimeout(function () { dismissToast(node); }, 3000);
    state.toastTimers.add(node.__uiTimer);
    host.appendChild(node);
    renderIcons(node);
    updateFloating();
  }

  function clearToasts() {
    if (toastHost) toastHost.textContent = '';
    state.toastTimers.forEach(function (timer) { clearTimeout(timer); });
    state.toastTimers.clear();
  }

  /* ---------- 浮层只出现在设备框当前可见的部分 ---------- */

  var floatTick = 0;

  function updateFloating() {
    floatTick = 0;
    var fr = frame.getBoundingClientRect();
    var padTop = fr.top + frame.clientTop;
    var padBottom = padTop + frame.clientHeight;
    var barBottom = bar ? bar.getBoundingClientRect().bottom : 0;
    var visTop = Math.max(padTop, barBottom, 0);
    var visBottom = Math.min(padBottom, window.innerHeight);
    var visibleHeight = Math.max(0, visBottom - visTop);

    state.dialogs.forEach(function (dialog) {
      dialog.style.top = (visTop + visibleHeight / 2 - padTop) + 'px';
      dialog.style.maxHeight = Math.max(160, visibleHeight - 40) + 'px';
      if (dialog.classList.contains('sheet')) {
        dialog.style.top = 'auto';
        dialog.style.bottom = Math.max(0, padBottom - visBottom) + 'px';
        dialog.style.maxHeight = Math.max(160, visibleHeight - 24) + 'px';
      }
      if (dialog.classList.contains('drawer')) {
        // 停靠在设备框可见区域的右侧，占满可见高度；宽度由 CSS 按设备框宽度决定。
        dialog.style.top = Math.max(0, visTop - padTop) + 'px';
        dialog.style.bottom = Math.max(0, padBottom - visBottom) + 'px';
        dialog.style.height = Math.max(160, visibleHeight) + 'px';
        dialog.style.maxHeight = Math.max(160, visibleHeight) + 'px';
      }
    });

    if (toastHost) {
      var bottomY = visBottom - 16;
      var screen = currentScreen();
      if (screen) {
        screen.querySelectorAll('*').forEach(function (node) {
          if (node === toastHost || node.closest('.ui-toast-host')) return;
          var position = window.getComputedStyle(node).position;
          if (position !== 'sticky' && position !== 'fixed') return;
          var rect = node.getBoundingClientRect();
          var bottomGap = visBottom - rect.bottom;
          if (rect.height <= 0 || rect.right <= 0 || rect.left >= window.innerWidth
            || rect.top >= visBottom || rect.bottom <= visTop || bottomGap < -2 || bottomGap > 120) return;
          bottomY = Math.min(bottomY, rect.top - 12);
        });
      }
      toastHost.style.top = Math.max(0, bottomY - padTop) + 'px';
      toastHost.style.bottom = 'auto';
      toastHost.style.transform = 'translate(-50%, -100%)';
    }

    syncNoteBadges();
  }

  function scheduleFloating() {
    if (floatTick) return;
    floatTick = requestAnimationFrame(updateFloating);
  }

  /* ---------- 下拉选择：原生 select 换成自绘列表框 ----------
   * 原生 select 留在原处（视觉隐藏），继续承担表单取值、required 校验和 change 事件；
   * 读者看到和操作的是按钮 + 浮层列表，展开的选项不再是操作系统的原生弹层。
   * 列表挂在 body 上、按按钮位置 fixed 定位，不会被工具栏或屏幕的滚动容器裁掉。 */

  var combo = { open: null, seq: 0 };

  function comboItems(select) {
    return Array.prototype.filter.call(select.options, function (option) { return !option.hidden; });
  }

  function syncCombo(select) {
    var box = select && select.__uiCombo;
    if (!box) return;
    var option = select.options[select.selectedIndex];
    box.value.textContent = option ? option.textContent : '';
    box.button.classList.toggle('ui-is-placeholder', !option || option.value === '');
    box.button.disabled = !!select.disabled;
  }

  function closeCombo(focusButton) {
    var box = combo.open;
    if (!box) return;
    combo.open = null;
    box.list.hidden = true;
    box.list.textContent = '';
    box.button.setAttribute('aria-expanded', 'false');
    box.button.removeAttribute('aria-activedescendant');
    if (focusButton) box.button.focus();
  }

  function setActiveOption(box, index) {
    var nodes = box.list.querySelectorAll('[role="option"]');
    if (!nodes.length) return;
    box.active = Math.max(0, Math.min(nodes.length - 1, index));
    Array.prototype.forEach.call(nodes, function (node, i) { node.classList.toggle('ui-is-active', i === box.active); });
    var node = nodes[box.active];
    box.button.setAttribute('aria-activedescendant', node.id);
    var top = node.offsetTop;
    var bottom = top + node.offsetHeight;
    if (top < box.list.scrollTop) box.list.scrollTop = top - 4;
    else if (bottom > box.list.scrollTop + box.list.clientHeight) box.list.scrollTop = bottom - box.list.clientHeight + 4;
  }

  function chooseOption(box, index) {
    var option = box.items[index];
    if (!option || option.disabled) return;
    var changed = box.select.value !== option.value || box.select.selectedIndex !== option.index;
    box.select.selectedIndex = option.index;
    syncCombo(box.select);
    closeCombo(true);
    if (changed) {
      box.select.dispatchEvent(new Event('input', { bubbles: true }));
      box.select.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  // 列表贴着按钮展开；下方放不下且上方更宽裕时向上弹出，高度不超过可见空间。
  function placeCombo(box) {
    var rect = box.button.getBoundingClientRect();
    var list = box.list;
    list.style.minWidth = rect.width + 'px';
    list.style.left = Math.max(8, Math.min(rect.left, window.innerWidth - Math.max(rect.width, list.offsetWidth) - 8)) + 'px';
    var below = window.innerHeight - rect.bottom - 12;
    var above = rect.top - 12;
    var cap = box.cap || 296;
    var natural = Math.min(list.scrollHeight, cap);
    var up = below < natural && above > below;
    var room = Math.max(120, Math.min(cap, up ? above : below));
    list.style.maxHeight = room + 'px';
    list.classList.toggle('ui-is-up', up);
    if (up) {
      list.style.top = 'auto';
      list.style.bottom = (window.innerHeight - rect.top + 6) + 'px';
    } else {
      list.style.bottom = 'auto';
      list.style.top = (rect.bottom + 6) + 'px';
    }
  }

  function openCombo(box) {
    if (combo.open === box || box.select.disabled) return;
    closeCombo(false);
    closeMenus();
    box.items = comboItems(box.select);
    box.list.textContent = '';
    var current = 0;
    box.items.forEach(function (option, index) {
      var item = el('div', {
        id: box.id + '-o' + index,
        class: 'ui-combo-option',
        role: 'option',
        'aria-selected': option.index === box.select.selectedIndex ? 'true' : 'false',
      }, option.textContent);
      if (option.disabled) item.setAttribute('aria-disabled', 'true');
      if (option.index === box.select.selectedIndex) current = index;
      item.addEventListener('mousedown', function (event) { event.preventDefault(); });
      item.addEventListener('click', function () { chooseOption(box, index); });
      item.addEventListener('mousemove', function () { if (box.active !== index) setActiveOption(box, index); });
      box.list.appendChild(item);
    });
    box.list.hidden = false;
    box.button.setAttribute('aria-expanded', 'true');
    combo.open = box;
    placeCombo(box);
    setActiveOption(box, current);
  }

  function comboKey(box, event) {
    var key = event.key;
    var opened = combo.open === box;
    if (!opened) {
      if (key === 'ArrowDown' || key === 'ArrowUp' || key === 'Enter' || key === ' ') {
        event.preventDefault();
        openCombo(box);
      }
      return;
    }
    var count = box.items.length;
    var step = { ArrowDown: 1, ArrowUp: -1, PageDown: 5, PageUp: -5 }[key];
    if (step) {
      event.preventDefault();
      setActiveOption(box, box.active + step);
    } else if (key === 'Home' || key === 'End') {
      event.preventDefault();
      setActiveOption(box, key === 'Home' ? 0 : count - 1);
    } else if (key === 'Enter' || key === ' ') {
      event.preventDefault();
      chooseOption(box, box.active);
    } else if (key === 'Escape') {
      // 只收起列表，不让外层弹窗跟着关闭。
      event.preventDefault();
      event.stopPropagation();
      closeCombo(true);
    } else if (key === 'Tab') {
      closeCombo(false);
    } else if (key.length === 1 && /\S/.test(key)) {
      // 键入首字跳到下一个以它开头的选项。
      var lower = key.toLowerCase();
      for (var offset = 1; offset <= count; offset += 1) {
        var index = (box.active + offset) % count;
        if (box.items[index].textContent.trim().toLowerCase().indexOf(lower) === 0) {
          setActiveOption(box, index);
          break;
        }
      }
    }
  }

  function bindPickerTrigger(control, box, open, onKey) {
    var button = box.button;
    var labels = Array.from(control.labels);
    if (labels.length) {
      labels.forEach(function (label, index) {
        if (!label.id) label.id = box.id + '-label' + index;
        // Let the browser activate the visible button, never the hidden backing field.
        label.htmlFor = button.id;
      });
      button.setAttribute('aria-labelledby', labels.map(function (label) { return label.id; }).join(' ') + ' ' + box.value.id);
    } else if (control.hasAttribute('aria-label')) {
      button.setAttribute('aria-label', control.getAttribute('aria-label'));
    }
    if (control.required) button.setAttribute('aria-required', 'true');
    // Validation can still focus the backing field. Move focus without opening its picker.
    control.addEventListener('focus', function () { button.focus(); });
    button.addEventListener('click', function () {
      button.focus();
      if (combo.open === box) closeCombo(false);
      else open(box);
    });
    button.addEventListener('keydown', function (event) { onKey(box, event); });
  }

  function enhanceSelect(select) {
    if (select.__uiCombo || select.multiple || select.size > 1) return;
    var id = 'ui-combo-' + (++combo.seq);
    var host = el('span', { class: 'ui-combo-host' + (select.classList.contains('ui-preview-select') ? ' ui-combo-host-tool' : '') });
    select.parentNode.insertBefore(host, select);
    host.appendChild(select);
    select.classList.add('ui-native-select');
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');
    var value = el('span', { class: 'ui-combo-value', id: id + '-value', 'data-allow-truncate': '' });
    var button = el('button', {
      id: id + '-trigger',
      type: 'button',
      class: 'ui-combo' + (select.classList.contains('ui-preview-select') ? ' ui-combo-tool' : ''),
      'aria-haspopup': 'listbox',
      'aria-expanded': 'false',
      'aria-controls': id + '-list',
    }, [value, el('span', { class: 'ui-combo-chevron', 'aria-hidden': 'true' })]);
    host.appendChild(button);
    var list = el('div', { class: 'ui-combo-list', id: id + '-list', role: 'listbox', hidden: '' });
    document.body.appendChild(list);
    var box = { id: id, select: select, button: button, value: value, list: list, items: [], active: 0 };
    select.__uiCombo = box;
    bindPickerTrigger(select, box, openCombo, comboKey);
    select.addEventListener('change', function () { syncCombo(select); });
    if (select.form) select.form.addEventListener('reset', function () { setTimeout(function () { syncCombo(select); }, 0); });
    syncCombo(select);
  }

  function enhanceSelects(root) {
    Array.prototype.forEach.call((root || document).querySelectorAll('select'), enhanceSelect);
  }

  document.addEventListener('mousedown', function (event) {
    var box = combo.open;
    if (!box || !(event.target instanceof Node)) return;
    if (box.list.contains(event.target) || box.button.contains(event.target)) return;
    var label = event.target instanceof Element ? event.target.closest('label') : null;
    if (label?.control === box.button) return;
    closeCombo(false);
  }, true);
  // 页面或容器滚动、窗口缩放时列表跟着按钮重新定位；按钮滚出可见区域才收起。列表自身滚动不算。
  function followCombo(event) {
    var box = combo.open;
    if (!box || (event && event.target === box.list)) return;
    var rect = box.button.getBoundingClientRect();
    if (rect.bottom < 0 || rect.top > window.innerHeight) closeCombo(false);
    else placeCombo(box);
  }
  document.addEventListener('scroll', followCombo, true);
  window.addEventListener('resize', function () { followCombo(null); });

  /* ---------- 声明式交互 ---------- */

  function activateTab(button, focus) {
    if (!button) return;
    var group = button.closest('.tabs');
    if (!group) return;
    var buttons = Array.prototype.slice.call(group.querySelectorAll('[data-tab]'));
    buttons.forEach(function (item) {
      var panel = document.getElementById(item.getAttribute('data-tab'));
      var selected = item === button;
      item.setAttribute('aria-selected', selected ? 'true' : 'false');
      item.setAttribute('tabindex', selected ? '0' : '-1');
      if (panel) setHidden(panel, !selected);
    });
    relayoutViz(group.closest('.screen') || document);
    if (focus) button.focus();
  }

  function collectActions(node) {
    if (!node) return null;
    return {
      go: node.getAttribute('data-go'),
      goState: node.getAttribute('data-go-state'),
      stateTo: node.getAttribute('data-state-to'),
      open: node.getAttribute('data-open'),
      close: node.hasAttribute('data-close'),
      toggle: node.getAttribute('data-toggle'),
      tab: node.getAttribute('data-tab'),
      toast: node.getAttribute('data-toast'),
      origin: node,
    };
  }

  function hasActions(actions) {
    if (!actions) return false;
    return actions.close || ['go', 'goState', 'stateTo', 'open', 'toggle', 'tab', 'toast']
      .some(function (key) { return !!actions[key]; });
  }

  function perform(actions) {
    if (!actions) return;
    if (actions.close) {
      var dialog = actions.origin.closest('dialog');
      if (dialog) closeDialog(dialog);
    }
    if (actions.toggle) {
      var target = document.getElementById(actions.toggle);
      if (target) {
        var hide = !target.hasAttribute('hidden');
        setHidden(target, hide);
        syncToggleTriggers(actions.toggle, !hide);
        relayoutViz(target);
      }
    }
    if (actions.tab) {
      activateTab(actions.origin, false);
    }
    if (actions.go) showScreen(actions.go, actions.goState || null);
    if (actions.stateTo) setState(actions.stateTo);
    if (actions.toast) showToast(actions.toast);
    if (actions.open) openDialog(document.getElementById(actions.open), actions.origin);
    if (actions.tab || actions.toggle) {
      renderNotes();
      scheduleFloating();
    }
  }

  function mergeActions(primary, secondary) {
    var merged = collectActions(primary);
    if (!merged) return null;
    var extra = collectActions(secondary);
    if (extra) {
      ['go', 'goState', 'stateTo', 'open', 'toggle', 'tab', 'toast'].forEach(function (key) {
        if (extra[key]) merged[key] = extra[key];
      });
      if (extra.close) merged.close = true;
    }
    merged.origin = secondary || primary;
    return merged;
  }

  /* ---------- 初始化：屏幕、标签页、开关目标 ---------- */

  function initTabs() {
    document.querySelectorAll('.tabs').forEach(function (tabs) {
      if (!tabs.hasAttribute('role')) tabs.setAttribute('role', 'tablist');
      var buttons = Array.prototype.slice.call(tabs.querySelectorAll('[data-tab]'));
      buttons.forEach(function (button) {
        var id = button.getAttribute('data-tab');
        var panel = document.getElementById(id);
        if (!button.hasAttribute('type')) button.setAttribute('type', 'button');
        button.setAttribute('role', 'tab');
        if (!button.id) button.id = 'ui-tab-' + (++uid);
        button.setAttribute('aria-controls', id);
        if (panel) {
          panel.setAttribute('role', 'tabpanel');
          if (!panel.hasAttribute('aria-labelledby')) panel.setAttribute('aria-labelledby', button.id);
        }
      });
      var selected = buttons.find(function (button) { return button.getAttribute('aria-selected') === 'true'; }) || buttons[0];
      if (selected) activateTab(selected, false);
      tabs.addEventListener('keydown', function (event) {
        var key = event.key;
        if (['ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowUp', 'Home', 'End'].indexOf(key) < 0) return;
        var active = event.target.closest('[data-tab]');
        if (!active) return;
        event.preventDefault();
        var index = buttons.indexOf(active);
        if (index < 0) return;
        if (key === 'Home') index = 0;
        else if (key === 'End') index = buttons.length - 1;
        else if (key === 'ArrowRight' || key === 'ArrowDown') index = (index + 1) % buttons.length;
        else index = (index - 1 + buttons.length) % buttons.length;
        activateTab(buttons[index], true);
        renderNotes();
      });
    });
  }

  function initToggles() {
    document.querySelectorAll('[data-toggle]').forEach(function (trigger) {
      var target = document.getElementById(trigger.getAttribute('data-toggle'));
      if (!target) return;
      trigger.setAttribute('aria-controls', trigger.getAttribute('data-toggle'));
      trigger.setAttribute('aria-expanded', target.hasAttribute('hidden') ? 'false' : 'true');
      if (target.classList.contains('menu') && !trigger.hasAttribute('aria-haspopup')) {
        trigger.setAttribute('aria-haspopup', 'true');
      }
    });
  }

  function initScreens() {
    var screens = allScreens();
    screens.forEach(function (screen) {
      if (!screen.hasAttribute('tabindex')) screen.setAttribute('tabindex', '-1');
    });
    var fromHash = (location.hash || '').replace(/^#/, '');
    var linked = findScreen(fromHash);
    var target = linked || screens[0];
    showScreen(target ? target.id : null, null, { silent: true, noHash: true, initial: true });
    // 打开 #scr-… 链接时，浏览器会把该屏滚到视口顶端，藏到吸顶工具栏下面；原型从页首完整显示。
    if (linked) {
      var toTop = function () { if (window.scrollY) window.scrollTo(0, 0); };
      toTop();
      if (document.readyState === 'complete') requestAnimationFrame(toTop);
      else window.addEventListener('load', function () { requestAnimationFrame(toTop); }, { once: true });
    }
  }

  /* ---------- 导出与打印 ---------- */

  var pageStyle = null;

  function stateNames(screen) {
    var names = statesOf(screen);
    return names.length ? names : [''];
  }

  function exportWidths() {
    var device = html.getAttribute('data-device') || 'mobile';
    if (device === 'responsive') {
      return [{ key: 'mobile', width: 390 }, { key: 'desktop', width: 1280 }];
    }
    if (device === 'desktop') return [{ key: 'desktop', width: 1280 }];
    return [{ key: 'mobile', width: 390 }];
  }

  function collectCloneNotes(clone) {
    return Array.prototype.filter.call(clone.querySelectorAll('[data-note]'), isVisible);
  }

  function textOf(node) {
    return (node && node.textContent ? node.textContent : '').replace(/\s+/g, ' ').trim();
  }

  function screenNodeById(screen, id) {
    return Array.prototype.find.call(screen.querySelectorAll('[id]'), function (node) {
      return node.id === id;
    }) || null;
  }

  function dialogLabel(screen, dialog) {
    var label = (dialog.getAttribute('aria-label') || '').trim();
    if (label) return label;
    var labelledBy = (dialog.getAttribute('aria-labelledby') || '').trim();
    if (labelledBy) {
      var named = labelledBy.split(/\s+/).map(function (id) {
        return textOf(screenNodeById(screen, id));
      }).filter(Boolean).join(' ');
      if (named) return named;
    }
    var heading = dialog.querySelector('h1, h2, h3, h4, h5, h6');
    if (textOf(heading)) return textOf(heading);
    return dialog.id || '';
  }

  // 触发按钮在该状态下是否可见；只读 data-when / hidden，不改动真实屏幕。
  function visibleInState(node, screen, stateName) {
    for (var current = node; current && current !== screen; current = current.parentElement) {
      if (current.hasAttribute('hidden')) return false;
      if (current.tagName === 'DIALOG') return false;
      if (stateName && current.hasAttribute('data-when')
        && splitList(current.getAttribute('data-when')).indexOf(stateName) < 0) return false;
    }
    return true;
  }

  function dialogTrigger(screen, dialog) {
    if (!dialog.id) return null;
    return Array.prototype.find.call(screen.querySelectorAll('[data-open]'), function (node) {
      return node.getAttribute('data-open') === dialog.id;
    }) || null;
  }

  // 触发按钮所在的祖先 dialog 链，外层在前；目标自身不算祖先。
  function dialogAncestors(trigger, screen, dialog) {
    var chain = [];
    for (var node = trigger ? trigger.parentElement : null; node && node !== screen; node = node.parentElement) {
      if (node.tagName === 'DIALOG' && node !== dialog) chain.unshift(node);
    }
    return chain;
  }

  // 打开目标前需要先打开的弹窗：触发按钮的祖先，以及这些祖先自己的触发链。
  function dialogChain(screen, dialog) {
    var chain = [];
    var seen = [];
    var current = dialog;
    for (var guard = 0; current && guard < 20; guard += 1) {
      var trigger = dialogTrigger(screen, current);
      if (!trigger) break;
      var ancestors = dialogAncestors(trigger, screen, current).filter(function (node) {
        return chain.indexOf(node) < 0 && seen.indexOf(node) < 0;
      });
      if (!ancestors.length) break;
      chain = ancestors.concat(chain);
      var outer = ancestors[0];
      seen.push(outer);
      current = outer;
    }
    return chain;
  }

  function dialogState(screen, dialog) {
    var names = stateNames(screen);
    var chain = dialogChain(screen, dialog);
    var trigger = chain.length ? dialogTrigger(screen, chain[0]) : dialogTrigger(screen, dialog);
    if (!trigger) return names[0];
    for (var index = 0; index < names.length; index += 1) {
      if (visibleInState(trigger, screen, names[index])) return names[index];
    }
    return names[0];
  }

  function cloneScreen(screen, stateName) {
    var clone = screen.cloneNode(true);
    clone.removeAttribute('id');
    clone.removeAttribute('hidden');
    clone.removeAttribute('data-states');
    Array.prototype.forEach.call(clone.querySelectorAll('[id]'), function (node) {
      node.removeAttribute('id');
    });
    Array.prototype.forEach.call(clone.querySelectorAll('dialog'), function (dialog) {
      dialog.removeAttribute('open');
    });
    Array.prototype.forEach.call(clone.querySelectorAll('script'), function (script) {
      script.remove();
    });
    clone.querySelectorAll('[data-ui-note]').forEach(function (node) { node.removeAttribute('data-ui-note'); });
    syncNav(clone, screen.id);
    clone.querySelectorAll('[data-when]').forEach(function (node) {
      setHidden(node, splitList(node.getAttribute('data-when')).indexOf(stateName) < 0);
    });
    if (!stateName) {
      clone.querySelectorAll('[data-when]').forEach(function (node) { node.removeAttribute('hidden'); });
    }
    return clone;
  }

  function figureCaption(title, stateName, suffix, width) {
    var caption = title + t('captionSep') + (stateName || t('defaultState'));
    if (suffix) caption += t('captionSep') + suffix;
    if ((html.getAttribute('data-device') || '') === 'responsive') {
      caption += t('captionSep') + deviceLabel(width.key) + ' ' + width.width;
    }
    return caption;
  }

  function buildFigure(screen, stateName, width) {
    var title = screen.getAttribute('data-title') || screen.id;
    var clone = cloneScreen(screen, stateName);
    var figure = el('figure', { class: 'ui-shot', 'data-w': width.key, 'data-state': stateName || '' });
    figure.appendChild(el('figcaption', { class: 'ui-shot-caption' }, figureCaption(title, stateName, '', width)));
    figure.appendChild(clone);
    return figure;
  }

  function buildDialogFigure(screen, stateName, width, index) {
    var dialogs = screen.querySelectorAll('dialog');
    var dialog = dialogs[index];
    if (!dialog) return null;
    var title = screen.getAttribute('data-title') || screen.id;
    var chain = dialogChain(screen, dialog);
    var clone = cloneScreen(screen, stateName);
    var cloneDialogs = clone.querySelectorAll('dialog');
    var target = cloneDialogs[index];
    if (!target) return null;
    chain.forEach(function (ancestor, depth) {
      var node = cloneDialogs[Array.prototype.indexOf.call(dialogs, ancestor)];
      if (!node || node === target) return;
      node.setAttribute('open', '');
      node.style.zIndex = String(41 + depth);
    });
    target.setAttribute('open', '');
    target.style.zIndex = String(41 + chain.length);
    clone.appendChild(el('div', { class: 'ui-shot-scrim', 'aria-hidden': 'true' }));
    var figure = el('figure', {
      class: 'ui-shot ui-shot-dialog',
      'data-w': width.key,
      'data-state': stateName || '',
    });
    figure.appendChild(el('figcaption', {
      class: 'ui-shot-caption',
    }, figureCaption(title, stateName, dialogLabel(screen, dialog), width)));
    figure.appendChild(clone);
    return { figure: figure, target: target };
  }

  function appendFigureNotes(figure, notes) {
    if (!notes.length) return;
    var list = el('ol', { class: 'ui-shot-notes', 'aria-label': t('notes') });
    notes.forEach(function (node, index) {
      node.setAttribute('data-ui-note', String(index + 1));
      list.appendChild(el('li', { class: 'ui-shot-note' }, [
        el('span', { class: 'ui-shot-note-num' }, String(index + 1)),
        el('span', {}, node.getAttribute('data-note') || ''),
      ]));
    });
    figure.appendChild(list);
  }

  function pageHeight() {
    var height = 0;
    frame.querySelectorAll('.ui-shot').forEach(function (figure) {
      height = Math.max(height, Math.ceil(figure.getBoundingClientRect().height));
    });
    return Math.max(900, height + 48);
  }

  function injectPageStyle() {
    if (!pageStyle) {
      pageStyle = document.createElement('style');
      pageStyle.setAttribute('data-ui', 'page');
      document.head.appendChild(pageStyle);
    }
    var device = html.getAttribute('data-device') || 'mobile';
    var width = device === 'mobile' ? 390 : 1280;
    var height = pageHeight();
    pageStyle.textContent = '@page{size:' + (width + 48) + 'px ' + (height + 48) + 'px;margin:24px}';
  }

  function enterExport() {
    if (state.exporting) return;
    var screens = allScreens();
    if (!screens.length) return;
    cancelNextTimers();
    closeAllDialogs();
    clearToasts();
    var last = screens[screens.length - 1];
    var ref = last.nextSibling;
    state.snapshot = {
      screens: screens,
      ref: ref,
      screenId: state.screenId,
      stateName: state.stateName,
      notesHidden: state.notesHidden,
      chartDisposers: [],
    };
    screens.forEach(function (screen) { screen.remove(); });
    state.exporting = true;
    frame.setAttribute('data-export', '');
    function placeFigure(figure) {
      if (!figure) return;
      if (ref && ref.parentNode === frame) frame.insertBefore(figure, ref);
      else frame.appendChild(figure);
      var disposeCharts = window.KChart?.mountClones?.(figure, document);
      if (disposeCharts) state.snapshot.chartDisposers.push(disposeCharts);
    }
    screens.forEach(function (screen) {
      var widths = exportWidths();
      stateNames(screen).forEach(function (name) {
        widths.forEach(function (width) {
          var figure = buildFigure(screen, name, width);
          placeFigure(figure);
          appendFigureNotes(figure, collectCloneNotes(figure.querySelector('.screen')));
        });
      });
      Array.prototype.forEach.call(screen.querySelectorAll('dialog'), function (dialog, index) {
        var name = dialogState(screen, dialog);
        widths.forEach(function (width) {
          var result = buildDialogFigure(screen, name, width, index);
          if (!result) return;
          placeFigure(result.figure);
          appendFigureNotes(result.figure, collectCloneNotes(result.target));
        });
      });
    });
    renderNotes();
    injectPageStyle();
    // 图从声明按各自宽度重绘后，高度可能改变。用最终布局计算原型 PDF 的页高。
    var snapshot = state.snapshot;
    if (snapshot.chartDisposers.length) {
      window.KChart.ready().then(function () {
        if (state.snapshot === snapshot) injectPageStyle();
      });
    }
    syncNoteBadges();
  }

  function exitExport() {
    if (!state.exporting) return;
    var snapshot = state.snapshot;
    snapshot?.chartDisposers.forEach(function (dispose) { dispose(); });
    Array.prototype.forEach.call(frame.querySelectorAll(':scope > .ui-shot'), function (figure) {
      figure.remove();
    });
    state.exporting = false;
    frame.removeAttribute('data-export');
    if (pageStyle) {
      pageStyle.remove();
      pageStyle = null;
    }
    if (snapshot) {
      var ref = snapshot.ref && snapshot.ref.parentNode === frame ? snapshot.ref : null;
      snapshot.screens.forEach(function (screen) {
        if (ref) frame.insertBefore(screen, ref);
        else frame.appendChild(screen);
      });
      state.notesHidden = snapshot.notesHidden;
      showScreen(snapshot.screenId, snapshot.stateName, { silent: true });
    }
    state.snapshot = null;
    relayoutViz(document);
  }

  function syncMode() {
    if (html.getAttribute('data-mode') === 'export' || state.printing) enterExport();
    else exitExport();
  }

  /* ---------- 事件 ---------- */

  document.addEventListener('click', function (event) {
    if (state.exporting) return;
    closeMenusOutside(event.target);
    var target = event.target instanceof Element ? event.target.closest('button, a') : null;
    if (!target) return;
    if (target.tagName === 'BUTTON') {
      var type = target.getAttribute('type') || 'submit';
      if (type === 'submit' && target.form) return;
    }
    var actions = collectActions(target);
    if (!hasActions(actions)) return;
    if (target.tagName === 'A') event.preventDefault();
    perform(actions);
  });

  document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || form.tagName !== 'FORM') return;
    if (state.exporting) {
      event.preventDefault();
      return;
    }
    var submitter = event.submitter || null;
    var merged = mergeActions(form, submitter);
    if (!hasActions(merged)) return;
    event.preventDefault();
    if (typeof form.checkValidity === 'function' && !form.checkValidity()) {
      form.reportValidity();
      return;
    }
    perform(merged);
  });

  document.addEventListener('invalid', function (event) {
    var field = event.target.closest ? event.target.closest('.field') : null;
    if (field) {
      field.setAttribute('data-invalid', '');
      event.target.setAttribute('aria-invalid', 'true');
    }
  }, true);

  function clearInvalid(event) {
    var field = event.target.closest ? event.target.closest('.field') : null;
    if (!field || !event.target.validity?.valid) return;
    field.removeAttribute('data-invalid');
    event.target.removeAttribute('aria-invalid');
  }
  document.addEventListener('input', clearInvalid);
  document.addEventListener('change', clearInvalid);

  document.addEventListener('keydown', function (event) {
    if (!state.dialogs.length) {
      if (event.key === 'Escape') closeMenus();
      return;
    }
    var top = state.dialogs[state.dialogs.length - 1];
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDialog(top);
      return;
    }
    if (event.key !== 'Tab') return;
    var list = focusable(top);
    if (!list.length) {
      event.preventDefault();
      top.focus();
      return;
    }
    var first = list[0];
    var last = list[list.length - 1];
    var active = document.activeElement;
    if (!top.contains(active)) {
      event.preventDefault();
      first.focus();
    } else if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  });

  window.addEventListener('scroll', scheduleFloating, { passive: true });
  window.addEventListener('resize', scheduleFloating);

  window.addEventListener('hashchange', function () {
    if (state.exporting) return;
    var id = (location.hash || '').replace(/^#/, '');
    if (id && findScreen(id)) showScreen(id, null);
  });

  window.addEventListener('beforeprint', function () {
    state.printing = true;
    enterExport();
    injectPageStyle();
  });
  window.addEventListener('afterprint', function () {
    state.printing = false;
    if (html.getAttribute('data-mode') !== 'export') exitExport();
  });

  // 某些浏览器/工具只切换 print 媒体而不触发 beforeprint；媒体查询变化兜底。
  var printQuery = window.matchMedia ? window.matchMedia('print') : null;
  if (printQuery) {
    if (printQuery.matches) state.printing = true;
    var onPrintMedia = function (event) {
      if (event.matches) {
        state.printing = true;
        enterExport();
        injectPageStyle();
      } else {
        state.printing = false;
        if (html.getAttribute('data-mode') !== 'export') exitExport();
      }
    };
    if (printQuery.addEventListener) printQuery.addEventListener('change', onPrintMedia);
    else if (printQuery.addListener) printQuery.addListener(onPrintMedia);
  }

  new MutationObserver(function () {
    if (html.getAttribute('data-device') !== ui.device) {
      ui.device = html.getAttribute('data-device') || 'mobile';
      syncWidthFromDevice();
    }
    syncToolbar();
    syncMode();
    relayoutViz(document);
  }).observe(html, {
    attributes: true,
    attributeFilter: ['data-mode', 'data-scheme', 'data-fidelity', 'data-device'],
  });

  /* ---------- 启动 ---------- */

  renderIcons(document);
  if (notesEl) notesEl.setAttribute('aria-label', t('notes'));
  syncWidthFromDevice();
  buildToolbar();
  ui.device = html.getAttribute('data-device') || 'mobile';
  initTabs();
  initToggles();
  initScreens();
  enhanceSelects(document);
  relayoutViz(document);
  syncToolbar();
  renderNotes();
  ensureScrim();
  toastHostNode();
  syncMode();

  window.htmlviewUI = Object.freeze({
    go: function (screenId, name) { if (screenId) showScreen(screenId, name); },
    state: function (name) {
      var names = statesOf(currentScreen());
      if (names.indexOf(name) < 0) {
        throw new TypeError('未知状态「' + String(name) + '」，可用状态：' + (names.join('、') || '无'));
      }
      setState(name);
    },
    open: function (id) { openDialog(document.getElementById(id)); },
    close: function () { closeDialog(state.dialogs[state.dialogs.length - 1]); },
    toast: function (text) { if (text) showToast(String(text)); },
    current: current,
  });
  if (currentScreen()) notifyChange();
}
