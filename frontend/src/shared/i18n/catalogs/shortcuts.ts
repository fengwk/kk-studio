import type { LocaleCatalog } from '@/shared/i18n/types'

/**
 * 快捷键展示唯一事实源（src/shared/shortcuts/shortcut-catalog.ts）的翻译：
 * scope 分组标题、动作标签与详细说明。catalog 与翻译同属共享展示层。
 */
export const shortcutsCatalog = {
  'ai.runtime.shortcuts.escape': {
    'en-US': 'Close the current panel or return focus to the Composer',
    'zh-CN': '关闭当前面板或把焦点还给 Composer',
  },
  'ai.runtime.shortcuts.send': {
    'en-US': 'Send the message',
    'zh-CN': '发送消息',
  },
  'ai.runtime.shortcuts.newline': {
    'en-US': 'Insert a new line',
    'zh-CN': '插入换行',
  },
  'ai.runtime.shortcuts.history': {
    'en-US': 'Browse message history at the line boundary',
    'zh-CN': '在行首/行尾边界浏览消息历史',
  },
  'ai.runtime.shortcuts.commands': {
    'en-US': 'Open the command palette',
    'zh-CN': '打开命令表',
  },
  'ai.runtime.shortcuts.commandNav': {
    'en-US': 'Move the active command and confirm',
    'zh-CN': '移动选中命令并确认',
  },
  'ai.runtime.shortcuts.commandEdges': {
    'en-US': 'Jump to the first / last command',
    'zh-CN': '跳到第一个/最后一个命令',
  },
  'ai.runtime.shortcuts.group.application': {
    'en-US': 'Application',
    'zh-CN': '应用',
  },
  'ai.runtime.shortcuts.group.thread': {
    'en-US': 'Thread',
    'zh-CN': '对话',
  },
  'ai.runtime.shortcuts.group.events': {
    'en-US': 'Events',
    'zh-CN': '事件',
  },
  'ai.runtime.shortcuts.group.canvas': {
    'en-US': 'Canvas',
    'zh-CN': '画布',
  },
  'ai.runtime.shortcuts.eventsNav': {
    'en-US': 'Move the active event',
    'zh-CN': '移动选中事件',
  },
  'ai.runtime.shortcuts.eventsPage': {
    'en-US': 'Page through the event list',
    'zh-CN': '事件列表翻页',
  },
  'ai.runtime.shortcuts.eventsEdges': {
    'en-US': 'Jump to the first / last event',
    'zh-CN': '跳到第一个/最后一个事件',
  },
  'ai.runtime.shortcuts.eventsOpen': {
    'en-US': 'Open the event detail',
    'zh-CN': '打开事件详情',
  },
  'ai.runtime.shortcuts.eventsEscape': {
    'en-US': 'Close the event detail',
    'zh-CN': '关闭事件详情',
  },
  'ai.runtime.shortcuts.canvasEscape': {
    'en-US': 'Clear the selection and focus the stage',
    'zh-CN': '清空选择并聚焦画布',
  },
  'ai.runtime.shortcuts.canvasThread': {
    'en-US': 'Focus the bound conversation',
    'zh-CN': '聚焦绑定对话',
  },
  'ai.runtime.shortcuts.canvasFit': {
    'en-US': 'Fit the canvas view',
    'zh-CN': '缩放适应画布',
  },
  'ai.runtime.shortcuts.canvasZoom': {
    'en-US': 'Reset the zoom to 100%',
    'zh-CN': '缩放重置为 100%',
  },
  'ai.runtime.shortcuts.canvasFocus': {
    'en-US': 'Focus the selected node',
    'zh-CN': '聚焦选中节点',
  },
  'ai.runtime.shortcuts.canvasText': {
    'en-US': 'Create a text node',
    'zh-CN': '新建文本节点',
  },
  'ai.runtime.shortcuts.canvasDelete': {
    'en-US': 'Delete the selected link',
    'zh-CN': '删除选中的连线',
  },
  'ai.runtime.shortcuts.label.escape': {
    'en-US': 'Close / refocus',
    'zh-CN': '关闭/聚焦',
  },
  'ai.runtime.shortcuts.label.send': {
    'en-US': 'Send',
    'zh-CN': '发送',
  },
  'ai.runtime.shortcuts.label.newline': {
    'en-US': 'New line',
    'zh-CN': '换行',
  },
  'ai.runtime.shortcuts.label.history': {
    'en-US': 'Message history',
    'zh-CN': '消息历史',
  },
  'ai.runtime.shortcuts.label.commands': {
    'en-US': 'Commands',
    'zh-CN': '命令',
  },
  'ai.runtime.shortcuts.label.commandNav': {
    'en-US': 'Command navigation',
    'zh-CN': '命令导航',
  },
  'ai.runtime.shortcuts.label.commandEdges': {
    'en-US': 'Command edges',
    'zh-CN': '命令边界',
  },
  'ai.runtime.shortcuts.label.eventsNav': {
    'en-US': 'Event navigation',
    'zh-CN': '事件导航',
  },
  'ai.runtime.shortcuts.label.eventsPage': {
    'en-US': 'Page events',
    'zh-CN': '事件翻页',
  },
  'ai.runtime.shortcuts.label.eventsEdges': {
    'en-US': 'Event edges',
    'zh-CN': '事件边界',
  },
  'ai.runtime.shortcuts.label.eventsOpen': {
    'en-US': 'Open event',
    'zh-CN': '打开事件',
  },
  'ai.runtime.shortcuts.label.eventsEscape': {
    'en-US': 'Close detail',
    'zh-CN': '关闭详情',
  },
  'ai.runtime.shortcuts.label.canvasEscape': {
    'en-US': 'Clear & focus',
    'zh-CN': '清空并聚焦',
  },
  'ai.runtime.shortcuts.label.canvasThread': {
    'en-US': 'Focus thread',
    'zh-CN': '聚焦对话',
  },
  'ai.runtime.shortcuts.label.canvasFit': {
    'en-US': 'Fit view',
    'zh-CN': '适应视图',
  },
  'ai.runtime.shortcuts.label.canvasZoom': {
    'en-US': 'Reset zoom',
    'zh-CN': '重置缩放',
  },
  'ai.runtime.shortcuts.label.canvasFocus': {
    'en-US': 'Focus node',
    'zh-CN': '聚焦节点',
  },
  'ai.runtime.shortcuts.label.canvasText': {
    'en-US': 'Text node',
    'zh-CN': '文本节点',
  },
  'ai.runtime.shortcuts.label.canvasDelete': {
    'en-US': 'Delete',
    'zh-CN': '删除',
  },
} satisfies LocaleCatalog
