/**
 * 快捷键 catalog：分组只读事实源（纯展示数据，不做全局 dispatcher）。
 * 每个分组只声明当前实际支持的快捷键；descriptionKey 必须存在于 i18n
 * （测试会校验翻译可解析，缺失键渲染为 ⟦missing:key⟧ 会被拒绝）。
 */
export interface ShortcutEntry {
  keys: string
  descriptionKey: string
}

export interface ShortcutGroup {
  id: string
  titleKey: string
  entries: ShortcutEntry[]
}

export const SHORTCUT_CATALOG: ShortcutGroup[] = [
  {
    id: 'application',
    titleKey: 'ai.runtime.shortcuts.group.application',
    entries: [
      // ThreadComposer 全局 Escape：关闭当前面板/弹层并恢复 Composer 焦点。
      { keys: 'Esc', descriptionKey: 'ai.runtime.shortcuts.escape' },
    ],
  },
  {
    id: 'thread',
    titleKey: 'ai.runtime.shortcuts.group.thread',
    entries: [
      { keys: 'Enter', descriptionKey: 'ai.runtime.shortcuts.send' },
      { keys: 'Shift+Enter', descriptionKey: 'ai.runtime.shortcuts.newline' },
      { keys: '↑ / ↓', descriptionKey: 'ai.runtime.shortcuts.history' },
      { keys: '/', descriptionKey: 'ai.runtime.shortcuts.commands' },
      { keys: '↑↓ · Enter', descriptionKey: 'ai.runtime.shortcuts.commandNav' },
      { keys: 'Home / End', descriptionKey: 'ai.runtime.shortcuts.commandEdges' },
    ],
  },
  {
    id: 'events',
    titleKey: 'ai.runtime.shortcuts.group.events',
    entries: [
      { keys: '↑ / ↓', descriptionKey: 'ai.runtime.shortcuts.eventsNav' },
      { keys: 'PageUp / PageDown', descriptionKey: 'ai.runtime.shortcuts.eventsPage' },
      { keys: 'Home / End', descriptionKey: 'ai.runtime.shortcuts.eventsEdges' },
      { keys: 'Enter / Space', descriptionKey: 'ai.runtime.shortcuts.eventsOpen' },
      { keys: 'Esc', descriptionKey: 'ai.runtime.shortcuts.eventsEscape' },
    ],
  },
  {
    id: 'canvas',
    titleKey: 'ai.runtime.shortcuts.group.canvas',
    entries: [
      { keys: 'Esc', descriptionKey: 'ai.runtime.shortcuts.canvasEscape' },
      { keys: 'Ctrl/Cmd+K', descriptionKey: 'ai.runtime.shortcuts.canvasThread' },
      { keys: '0', descriptionKey: 'ai.runtime.shortcuts.canvasFit' },
      { keys: '1', descriptionKey: 'ai.runtime.shortcuts.canvasZoom' },
      { keys: 'F', descriptionKey: 'ai.runtime.shortcuts.canvasFocus' },
      { keys: 'T', descriptionKey: 'ai.runtime.shortcuts.canvasText' },
      { keys: 'Delete / Backspace', descriptionKey: 'ai.runtime.shortcuts.canvasDelete' },
    ],
  },
]
