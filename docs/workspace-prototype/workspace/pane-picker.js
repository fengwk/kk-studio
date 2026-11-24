// The modal freezes the live views without moving them or changing the layout tree.
export function showPanePicker(stage, choices, currentId, source, onChoose) {
  const dialog = document.createElement('dialog');
  dialog.className = 'x-pane-picker';
  dialog.setAttribute('aria-label', '打开到窗格');
  const overview = choices.some(({ visible }) => !visible);
  dialog.dataset.overview = String(overview);
  const stageBox = stage.getBoundingClientRect();
  place(dialog, stageBox, 0, 0);

  function finish(id) {
    window.removeEventListener('resize', cancel);
    dialog.close();
    dialog.remove();
    const key = source.dataset.fkey;
    const restore = source.isConnected ? source
      : key && stage.parentElement.querySelector(`[data-fkey="${CSS.escape(key)}"]`);
    if (restore) restore.focus({ preventScroll: true });
    if (id) onChoose(id);
  }
  const cancel = () => finish();
  const buttons = choices.map(({ id, node, title, letter }) => {
    const tile = document.createElement('div');
    tile.className = 'x-pane-target';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'x-pane-target-key';
    button.dataset.paneLetter = letter;
    button.textContent = letter;
    button.setAttribute('aria-label', `${letter}：${title}`);
    button.title = `${title} · ${id === currentId ? `${letter} / Enter` : letter}`;
    button.setAttribute('aria-keyshortcuts', id === currentId ? `Enter ${letter}` : letter);
    if (id === currentId) button.setAttribute('aria-current', 'true');
    if (overview) {
      const name = document.createElement('span');
      name.className = 'x-pane-target-title';
      name.textContent = title;
      name.title = title;
      name.setAttribute('data-allow-truncate', '');
      tile.append(name);
    } else {
      const box = node.parentElement.getBoundingClientRect();
      place(tile, box, stageBox.x, stageBox.y);
    }
    button.addEventListener('click', () => finish(id));
    tile.append(button);
    dialog.append(tile);
    return button;
  });
  dialog.addEventListener('cancel', (event) => { event.preventDefault(); cancel(); });
  dialog.addEventListener('click', (event) => { if (event.target === dialog) cancel(); });
  dialog.addEventListener('keydown', (event) => {
    if (event.isComposing || event.keyCode === 229 || event.ctrlKey || event.altKey || event.metaKey) return;
    const choice = event.key === 'Enter' ? choices.find(({ id }) => id === currentId)
      : choices.find(({ letter }) => letter.toLowerCase() === event.key.toLowerCase());
    if (!choice) return;
    event.preventDefault();
    event.stopPropagation();
    if (!event.repeat) finish(choice.id);
  });
  stage.parentElement.append(dialog);
  dialog.showModal();
  buttons[choices.findIndex(({ id }) => id === currentId)].focus({ preventScroll: true });
  window.addEventListener('resize', cancel);
}

function place(element, box, originX, originY) {
  Object.assign(element.style, {
    left: `${box.x - originX}px`, top: `${box.y - originY}px`, width: `${box.width}px`, height: `${box.height}px`,
  });
}
