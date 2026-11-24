const HOLD_MS = 380;
const MOVE_TOLERANCE = 8;
const SCROLL_EDGE = 40;

export function bindTreeDrag(tree, { onStart, resolveDrop, onDrop }) {
  let source = null;
  let marker = null;
  let markedPosition = null;
  let touch = null;
  let timer = 0;
  let frame = 0;

  function mark(row, position) {
    if (row === marker && position === markedPosition) return;
    marker?.classList.remove(`x-drop-${markedPosition}`);
    marker = row;
    markedPosition = position;
    if (row) row.classList.add(`x-drop-${position}`);
  }

  function start(row) {
    source = row;
    row.classList.add('x-dragging');
    onStart();
  }

  function stop() {
    mark(null, null);
    source?.classList.remove('x-dragging');
    source = null;
  }

  function targetAt(element, y) {
    const row = element?.closest('.x-row');
    if (!row || !tree.contains(row)) { mark(null, null); return null; }
    const target = resolveDrop(source.dataset.key, row, y);
    mark(target ? row : null, target?.pos ?? null);
    return target;
  }

  function commit(target) {
    const key = source.dataset.key;
    stop();
    if (target) onDrop(key, target);
  }

  tree.addEventListener('dragstart', (event) => {
    const row = event.target.closest('.x-row[draggable="true"]');
    if (!row) return;
    if (touch || event.target.closest('[data-action], input')) { event.preventDefault(); return; }
    start(row);
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', row.dataset.key);
  });
  tree.addEventListener('dragover', (event) => {
    if (!source || touch || !targetAt(event.target, event.clientY)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  });
  tree.addEventListener('dragleave', (event) => {
    if (source && !touch && !tree.contains(event.relatedTarget)) mark(null, null);
  });
  tree.addEventListener('drop', (event) => {
    if (!source || touch) return;
    event.preventDefault();
    commit(targetAt(event.target, event.clientY));
  });
  tree.addEventListener('dragend', () => { if (!touch) stop(); });

  function cancelTouch() {
    clearTimeout(timer);
    cancelAnimationFrame(frame);
    frame = 0;
    if (touch?.active) stop();
    touch = null;
  }

  function touchTarget({ clientX, clientY }) {
    return targetAt(document.elementFromPoint(clientX, clientY), clientY);
  }

  function autoScroll() {
    frame = 0;
    if (!touch?.active) return;
    const { clientX, clientY } = touch.point;
    const { top, bottom, left, right, height } = tree.getBoundingClientRect();
    if (clientX < left || clientX > right || clientY < top || clientY > bottom) return;
    const edge = Math.min(SCROLL_EDGE, height / 3);
    const speed = clientY < top + edge ? -(top + edge - clientY) : Math.max(0, clientY - (bottom - edge));
    if (!speed) return;
    const before = tree.scrollTop;
    tree.scrollTop += speed / 3;
    if (tree.scrollTop === before) return;
    touchTarget(touch.point);
    frame = requestAnimationFrame(autoScroll);
  }

  tree.addEventListener('touchstart', (event) => {
    if (event.touches.length !== 1) { cancelTouch(); return; }
    const row = event.target.closest('.x-row[data-movable]');
    if (source || !row || event.target.closest('[data-action], input')) return;
    const point = event.touches[0];
    touch = { point, active: false };
    timer = setTimeout(() => {
      if (!row.isConnected || !row.hasAttribute('data-movable')) { cancelTouch(); return; }
      touch.active = true;
      start(row);
    }, HOLD_MS);
  }, { passive: true });

  // Keep the browser's native scroll until the hold wins. Only an active drag consumes motion.
  tree.addEventListener('touchmove', (event) => {
    if (!touch) return;
    if (event.touches.length !== 1 || !event.cancelable) { cancelTouch(); return; }
    const point = event.touches[0];
    if (!touch.active) {
      if (Math.hypot(point.clientX - touch.point.clientX, point.clientY - touch.point.clientY) > MOVE_TOLERANCE) cancelTouch();
      return;
    }
    event.preventDefault();
    touch.point = point;
    touchTarget(point);
    if (!frame) frame = requestAnimationFrame(autoScroll);
  }, { passive: false });
  tree.addEventListener('touchend', (event) => {
    if (!touch?.active) { cancelTouch(); return; }
    event.preventDefault();
    commit(touchTarget(event.changedTouches[0]));
    cancelTouch();
  }, { passive: false });
  tree.addEventListener('touchcancel', cancelTouch);
  tree.addEventListener('contextmenu', (event) => {
    if (!touch) return;
    event.preventDefault();
    event.stopImmediatePropagation();
  }, { capture: true });
}
