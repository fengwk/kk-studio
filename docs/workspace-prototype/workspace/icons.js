const paths = {
  keyRound: '<path d="M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h4a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z"/><circle cx="16.5" cy="7.5" r=".5" fill="currentColor"/>',
  logOut: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4m7 14 5-5-5-5m5 5H9"/>',
  chev: '<path d="m9 6 6 6-6 6"/>',
  chevDown: '<path d="m6 9 6 6 6-6"/>',
  folder: '<path d="M3.5 7A1.5 1.5 0 0 1 5 5.5h4l2 2h8A1.5 1.5 0 0 1 20.5 9v8.5A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"/>',
  canvas: '<path d="M4 5h16v14H4z"/><path d="M4 9h16M9 9v10"/>',
  folderPlus: '<path d="M3.5 7A1.5 1.5 0 0 1 5 5.5h4l2 2h8A1.5 1.5 0 0 1 20.5 9v8.5A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"/><path d="M12 10.5v5M9.5 13h5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  waiting: '<path d="M7 4h10M7 20h10M8 4v3l4 5-4 5v3m8-16v3l-4 5 4 5v3"/>',
  more: '<path d="M5.5 12h.01M12 12h.01M18.5 12h.01" stroke-width="3"/>',
  x: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
  splitR: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M12 4.5v15"/>',
  splitD: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M3.5 12h17"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  sideL: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M9 4.5v15M15.5 10l-2 2 2 2"/>',
  sideR: '<rect x="3.5" y="4.5" width="17" height="15" rx="2"/><path d="M9 4.5v15M13.5 10l2 2-2 2"/>',
  clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l2.5 2"/>',
  branch: '<circle cx="7" cy="6" r="2"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="7" r="2"/><path d="M7 8v8M17 9c0 4.5-5 4-9 7.5"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/>',
  pencil: '<path d="M4.5 19.5h4l10-10a2.1 2.1 0 0 0-3-3l-10 10z"/><path d="m14 8 3 3"/>',
  send: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  stop: '<rect x="7" y="7" width="10" height="10" rx="1.5"/>',
  play: '<path d="M8 5.5v13l10.5-6.5z"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  trash: '<path d="M5 7h14M10 11v6M14 11v6M6.5 7l1 12h9l1-12M9.5 7V4.5h5V7"/>',
  needs: '<circle cx="12" cy="12" r="5" fill="currentColor" stroke="none"/>',
  running: '<circle cx="12" cy="12" r="7" stroke-opacity="0.35"/><path d="M12 5a7 7 0 0 1 7 7"/>',
  error: '<circle cx="12" cy="12" r="7.5"/><path d="m9.5 9.5 5 5M14.5 9.5l-5 5"/>'
};

export function svg(name, cls) {
  return '<svg class="x-ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + (paths[name] || '') + '</svg>';
}
