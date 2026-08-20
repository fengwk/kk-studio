/**
 * 快捷键展示唯一事实源：扁平 {@link ShortcutDefinition} 列表。
 *
 * - 纯展示数据，不做全局 dispatcher；catalog 只声明当前实现实际支持的快捷键。
 * - 每条定义拥有稳定唯一 id（不随文案/键位展示变化），并按 scope 分组渲染。
 * - labelKey / descriptionKey 必须存在于 i18n（测试会校验翻译可解析，
 *   缺失键渲染为 ⟦missing:key⟧ 会被拒绝）。
 */
export type ShortcutScope = 'application' | 'thread' | 'debug' | 'canvas'

export interface ShortcutDefinition {
  /** 稳定唯一 id，如 `application.escape`。 */
  id: string
  scope: ShortcutScope
  /** 展示键序，如 `Esc`、`Ctrl/Cmd+K`。 */
  keys: string
  /** 动作短标签的 i18n key。 */
  labelKey: string
  /** 动作详细说明的 i18n key。 */
  descriptionKey: string
}

export const SHORTCUT_SCOPE_ORDER: readonly ShortcutScope[] = [
  'application',
  'thread',
  'debug',
  'canvas',
]

export const SHORTCUT_SCOPE_TITLE_KEYS: Record<ShortcutScope, string> = {
  application: 'ai.runtime.shortcuts.group.application',
  thread: 'ai.runtime.shortcuts.group.thread',
  debug: 'ai.runtime.shortcuts.group.debug',
  canvas: 'ai.runtime.shortcuts.group.canvas',
}

export const SHORTCUT_CATALOG: ShortcutDefinition[] = [
  {
    id: 'application.escape',
    scope: 'application',
    keys: 'Esc',
    labelKey: 'ai.runtime.shortcuts.label.escape',
    // ThreadComposer 全局 Escape：关闭当前面板/弹层并恢复 Composer 焦点。
    descriptionKey: 'ai.runtime.shortcuts.escape',
  },
  {
    id: 'thread.send',
    scope: 'thread',
    keys: 'Enter',
    labelKey: 'ai.runtime.shortcuts.label.send',
    descriptionKey: 'ai.runtime.shortcuts.send',
  },
  {
    id: 'thread.newline',
    scope: 'thread',
    keys: 'Shift+Enter',
    labelKey: 'ai.runtime.shortcuts.label.newline',
    descriptionKey: 'ai.runtime.shortcuts.newline',
  },
  {
    id: 'thread.history',
    scope: 'thread',
    keys: '↑ / ↓',
    labelKey: 'ai.runtime.shortcuts.label.history',
    descriptionKey: 'ai.runtime.shortcuts.history',
  },
  {
    id: 'thread.commands',
    scope: 'thread',
    keys: '/',
    labelKey: 'ai.runtime.shortcuts.label.commands',
    descriptionKey: 'ai.runtime.shortcuts.commands',
  },
  {
    id: 'thread.commandNav',
    scope: 'thread',
    keys: '↑↓ · Enter',
    labelKey: 'ai.runtime.shortcuts.label.commandNav',
    descriptionKey: 'ai.runtime.shortcuts.commandNav',
  },
  {
    id: 'thread.commandEdges',
    scope: 'thread',
    keys: 'Home / End',
    labelKey: 'ai.runtime.shortcuts.label.commandEdges',
    descriptionKey: 'ai.runtime.shortcuts.commandEdges',
  },
  {
    id: 'debug.nav',
    scope: 'debug',
    keys: '↑ / ↓',
    labelKey: 'ai.runtime.shortcuts.label.debugNav',
    descriptionKey: 'ai.runtime.shortcuts.debugNav',
  },
  {
    id: 'debug.escape',
    scope: 'debug',
    keys: 'Esc',
    labelKey: 'ai.runtime.shortcuts.label.debugEscape',
    descriptionKey: 'ai.runtime.shortcuts.debugEscape',
  },
  {
    id: 'canvas.escape',
    scope: 'canvas',
    keys: 'Esc',
    labelKey: 'ai.runtime.shortcuts.label.canvasEscape',
    descriptionKey: 'ai.runtime.shortcuts.canvasEscape',
  },
  {
    id: 'canvas.thread',
    scope: 'canvas',
    keys: 'Ctrl/Cmd+K',
    labelKey: 'ai.runtime.shortcuts.label.canvasThread',
    descriptionKey: 'ai.runtime.shortcuts.canvasThread',
  },
  {
    id: 'canvas.fit',
    scope: 'canvas',
    keys: '0',
    labelKey: 'ai.runtime.shortcuts.label.canvasFit',
    descriptionKey: 'ai.runtime.shortcuts.canvasFit',
  },
  {
    id: 'canvas.zoom',
    scope: 'canvas',
    keys: '1',
    labelKey: 'ai.runtime.shortcuts.label.canvasZoom',
    descriptionKey: 'ai.runtime.shortcuts.canvasZoom',
  },
  {
    id: 'canvas.focus',
    scope: 'canvas',
    keys: 'F',
    labelKey: 'ai.runtime.shortcuts.label.canvasFocus',
    descriptionKey: 'ai.runtime.shortcuts.canvasFocus',
  },
  {
    id: 'canvas.text',
    scope: 'canvas',
    keys: 'T',
    labelKey: 'ai.runtime.shortcuts.label.canvasText',
    descriptionKey: 'ai.runtime.shortcuts.canvasText',
  },
  {
    id: 'canvas.delete',
    scope: 'canvas',
    keys: 'Delete / Backspace',
    labelKey: 'ai.runtime.shortcuts.label.canvasDelete',
    descriptionKey: 'ai.runtime.shortcuts.canvasDelete',
  },
]
