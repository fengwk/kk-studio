/**
 * 快捷键 catalog：纯展示数据，不做全局 dispatcher。
 * Settings 等后续界面可复用同一 list 渲染。
 */
export interface ShortcutEntry {
  keys: string
  descriptionKey: string
}

export const SHORTCUT_CATALOG: ShortcutEntry[] = [
  { keys: 'Enter', descriptionKey: 'ai.runtime.shortcuts.send' },
  { keys: 'Shift+Enter', descriptionKey: 'ai.runtime.shortcuts.newline' },
  { keys: '↑ / ↓', descriptionKey: 'ai.runtime.shortcuts.history' },
  { keys: 'Esc', descriptionKey: 'ai.runtime.shortcuts.escape' },
  { keys: '/', descriptionKey: 'ai.runtime.shortcuts.commands' },
  { keys: '↑↓ · Enter', descriptionKey: 'ai.runtime.shortcuts.commandNav' },
  { keys: 'Home / End', descriptionKey: 'ai.runtime.shortcuts.commandEdges' },
]
