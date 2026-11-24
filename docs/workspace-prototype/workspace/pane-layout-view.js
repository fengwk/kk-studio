import { createElement } from 'react';
import { mountDomView } from './dom-view';
import { PaneHeader } from './PaneHeader';
import {
  leaves, findSplit, minWidth, minHeight, paneLetter, treeKey, MIN_W, MIN_H, DIV, MAX_PANES,
} from './layout.js';

// Layout owns the chrome and stable content hosts. Callers supply titles, actions, and content.
export function createPaneLayoutView({ panes, storage, mobileHeader, onAction, onFocus, onSwap }) {
  const nodes = new Map();
  const paneDragType = 'application/x-kk-preview-pane';
  let layout, narrow, single, visibleLayout, dragId, dropPane;

  function render(nextLayout, options) {
    layout = nextLayout;
    narrow = options.narrow;
    single = options.single;
    const order = leaves(layout.root);
    const ids = new Set(order.map(({ id }) => id));
    for (const [id, pane] of nodes) {
      if (ids.has(id)) continue;
      mountDomView(pane.firstElementChild, null);
      pane.remove();
      nodes.delete(id);
    }
    const multiple = order.length > 1;
    for (const leaf of order) {
      const presentation = options.present(leaf.id);
      const letter = multiple ? paneLetter(order, leaf) : undefined;
      const title = presentation.titleParts.join(' › ');
      const pane = paneElement(leaf.id);
      pane.setAttribute('aria-label', multiple ? `窗格 ${letter}：${title}` : title);
      const props = {
        ...presentation, id: leaf.id, letter, canDrag: multiple && !single,
        canSwitch: single && !options.fullscreen, canClose: multiple && !single,
        fullscreen: options.fullscreen,
        onAction: (action, anchor, dir) => onAction(leaf.id, action, anchor, dir),
      };
      mountDomView(pane.firstElementChild, narrow ? null : createElement(PaneHeader, props));
      if (narrow && leaf.id === layout.focus) {
        mountDomView(mobileHeader, createElement(PaneHeader, { ...props, mobile: true }));
      }
    }
    if (!narrow) mountDomView(mobileHeader, null);

    const key = single ? `one:${layout.focus}` : treeKey(layout.root);
    if (key !== visibleLayout) {
      const active = document.activeElement;
      if (single || !multiple) {
        const pane = nodes.get(layout.focus);
        pane.style.cssText = '';
        if (panes.firstElementChild !== pane || panes.childElementCount !== 1) panes.replaceChildren(pane);
        for (const leaf of order) {
          if (leaf.id !== layout.focus) storage.appendChild(nodes.get(leaf.id));
        }
      } else {
        panes.replaceChildren(nodeElement(layout.root));
      }
      if (active !== document.activeElement && nodes.get(layout.focus).contains(active)) {
        active.focus({ preventScroll: true });
      }
      visibleLayout = key;
    }
    applyFocus();
    updateSplitButtons();
  }

  function paneElement(id) {
    let pane = nodes.get(id);
    if (!pane) {
      pane = document.createElement('section');
      pane.className = 'x-pane';
      pane.dataset.leaf = id;
      pane.innerHTML = '<header class="x-pane-head" tabindex="-1"></header><div class="x-pane-body"></div>';
      nodes.set(id, pane);
    }
    return pane;
  }

  function nodeElement(node) {
    if (node.t === 'leaf') return nodes.get(node.id);
    const box = document.createElement('div');
    box.className = `x-split x-split-${node.dir}`;
    const first = nodeElement(node.a);
    const second = nodeElement(node.b);
    for (const [element, child] of [[first, node.a], [second, node.b]]) {
      element.style.minWidth = `${minWidth(child)}px`;
      element.style.minHeight = `${minHeight(child)}px`;
    }
    const divider = document.createElement('div');
    divider.className = 'x-divider';
    divider.dataset.split = node.id;
    divider.dataset.fkey = `div:${node.id}`;
    divider.tabIndex = 0;
    divider.setAttribute('role', 'separator');
    divider.setAttribute('aria-orientation', node.dir === 'row' ? 'vertical' : 'horizontal');
    divider.setAttribute('aria-label', '调整窗格大小，双击恢复一半');
    divider.setAttribute('aria-valuemin', '0');
    divider.setAttribute('aria-valuemax', '100');
    box.append(first, divider, second);
    applyRatio(box, node);
    return box;
  }

  function applyRatio(box, node) {
    box.children[0].style.flex = `${node.ratio} 1 0px`;
    box.children[2].style.flex = `${1 - node.ratio} 1 0px`;
    box.children[1].setAttribute('aria-valuenow', String(Math.round(node.ratio * 100)));
    if (box.isConnected) updateSplitButtons();
  }

  function applyFocus() {
    for (const [id, pane] of nodes) pane.classList.toggle('x-focus', id === layout.focus);
  }

  function focusedHeader() {
    return narrow ? mobileHeader : nodes.get(layout.focus).firstElementChild;
  }

  function focusHeader() {
    const header = focusedHeader();
    const control = header.querySelector('[data-action="view-select"]') || header;
    control.focus({ preventScroll: true });
  }

  function focusFullscreen() {
    focusedHeader().querySelector('[data-action="pane-fullscreen"]').focus({ preventScroll: true });
  }

  function flash(id) {
    const pane = nodes.get(id);
    if (!pane || pane.parentElement === storage) return;
    pane.classList.remove('x-flash');
    void pane.offsetWidth;
    pane.classList.add('x-flash');
  }

  function mounts() {
    return leaves(layout.root).map(({ id }) => {
      const pane = nodes.get(id);
      return { id, node: pane.lastElementChild, visible: pane.parentElement !== storage };
    });
  }

  function canSplit(id, dir) {
    if (nodes.size >= MAX_PANES) return `最多 ${MAX_PANES} 个窗格`;
    if (narrow || single) return '';
    const { width, height } = nodes.get(id).getBoundingClientRect();
    const short = dir === 'row' ? width < 2 * MIN_W + DIV : height < 2 * MIN_H + DIV;
    return short ? `空间不够：每个窗格至少 ${MIN_W}×${MIN_H}` : '';
  }

  function updateSplitButtons() {
    for (const host of [panes, mobileHeader]) {
      for (const button of host.querySelectorAll('.x-pane-split')) {
        const id = button.closest('.x-pane')?.dataset.leaf ?? layout.focus;
        const why = canSplit(id, button.dataset.dir);
        const label = button.getAttribute('aria-label');
        button.title = why ? `${label}：${why}` : label;
        if (why) button.setAttribute('aria-disabled', 'true');
        else button.removeAttribute('aria-disabled');
      }
    }
  }

  function ratioLimits(divider, node) {
    const rect = divider.parentElement.getBoundingClientRect();
    const row = node.dir === 'row';
    const size = (row ? rect.width : rect.height) - DIV;
    const min = row ? minWidth : minHeight;
    return { rect, row, size, lo: min(node.a) / size, hi: 1 - min(node.b) / size };
  }

  function setRatio(divider, node, ratio, { lo, hi }) {
    node.ratio = lo > hi ? 0.5 : Math.min(hi, Math.max(lo, ratio));
    applyRatio(divider.parentElement, node);
  }

  function startDividerDrag(event, divider) {
    event.preventDefault();
    const node = findSplit(layout.root, divider.dataset.split);
    const limits = ratioLimits(divider, node);
    divider.setPointerCapture(event.pointerId);
    divider.classList.add('x-drag');
    function move(ev) {
      const { rect, row, size } = limits;
      const pos = (row ? ev.clientX - rect.left : ev.clientY - rect.top) - DIV / 2;
      setRatio(divider, node, pos / size, limits);
    }
    function end() {
      divider.classList.remove('x-drag');
      divider.removeEventListener('pointermove', move);
      divider.removeEventListener('pointerup', end);
      divider.removeEventListener('pointercancel', end);
    }
    divider.addEventListener('pointermove', move);
    divider.addEventListener('pointerup', end);
    divider.addEventListener('pointercancel', end);
  }

  function keyDivider(event, divider) {
    const node = findSplit(layout.root, divider.dataset.split);
    const dec = node.dir === 'row' ? 'ArrowLeft' : 'ArrowUp';
    const inc = node.dir === 'row' ? 'ArrowRight' : 'ArrowDown';
    const key = event.key;
    if (![dec, inc, 'Home', 'End'].includes(key)) return;
    event.preventDefault();
    const limits = ratioLimits(divider, node);
    const ratio = key === dec ? node.ratio - 0.05 : key === inc ? node.ratio + 0.05 : key === 'Home' ? limits.lo : limits.hi;
    setRatio(divider, node, ratio, limits);
  }

  function paneDropTarget(event) {
    if (!dragId || single || !event.dataTransfer.types.includes(paneDragType)) return null;
    const pane = event.target.closest('.x-pane-head')?.closest('.x-pane');
    return pane && pane.dataset.leaf !== dragId ? pane : null;
  }

  function showPaneDrop(pane) {
    if (dropPane === pane) return;
    dropPane?.classList.remove('x-pane-drop');
    pane?.classList.add('x-pane-drop');
    dropPane = pane;
  }

  panes.addEventListener('dragstart', (event) => {
    const grip = event.target.closest('[data-pane-grip]');
    if (!grip) return;
    dragId = grip.closest('.x-pane').dataset.leaf;
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData(paneDragType, dragId);
  });
  panes.addEventListener('dragover', (event) => {
    const pane = paneDropTarget(event);
    showPaneDrop(pane);
    if (!pane) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  });
  panes.addEventListener('dragleave', (event) => {
    if (!(event.relatedTarget instanceof Element) || !event.relatedTarget.closest('.x-pane-head')) showPaneDrop(null);
  });
  panes.addEventListener('drop', (event) => {
    const pane = paneDropTarget(event);
    showPaneDrop(null);
    if (!pane) return;
    event.preventDefault();
    const from = event.dataTransfer.getData(paneDragType);
    if (from === dragId) onSwap(from, pane.dataset.leaf);
    dragId = null;
  });
  panes.addEventListener('dragend', () => { showPaneDrop(null); dragId = null; });
  panes.addEventListener('pointerdown', (event) => {
    const divider = event.target.closest('.x-divider');
    if (divider) startDividerDrag(event, divider);
    else focusPaneAt(event.target);
  });
  panes.addEventListener('focusin', (event) => focusPaneAt(event.target));
  panes.addEventListener('dblclick', (event) => {
    const divider = event.target.closest('.x-divider');
    if (!divider) return;
    const node = findSplit(layout.root, divider.dataset.split);
    setRatio(divider, node, 0.5, ratioLimits(divider, node));
  });

  function focusPaneAt(target) {
    const pane = target.closest('.x-pane');
    if (pane) onFocus(pane.dataset.leaf);
  }

  function keyHeader(event, header) {
    if (event.defaultPrevented || event.isComposing || event.keyCode === 229 || event.repeat ||
      event.ctrlKey || event.metaKey || event.altKey || event.shiftKey ||
      (event.target !== header && !event.target.matches('button:not([role="combobox"])'))) return;
    const dir = event.key === 'v' ? 'row' : event.key === 's' ? 'col' : null;
    if (!dir) return;
    const anchor = header.querySelector(`.x-pane-split[data-dir="${narrow ? '' : dir}"]`);
    if (!anchor) return;
    event.preventDefault();
    onAction(header.closest('.x-pane')?.dataset.leaf ?? layout.focus, 'pane-split', anchor, dir);
  }

  panes.addEventListener('keydown', (event) => {
    const target = event.target;
    if (target.matches('.x-divider')) keyDivider(event, target);
    const header = target.closest('.x-pane-head');
    if (header) keyHeader(event, header);
  });
  mobileHeader.addEventListener('keydown', (event) => keyHeader(event, mobileHeader));

  return { render, mounts, applyFocus, focusHeader, focusFullscreen, flash, canSplit, updateSplitButtons };
}
