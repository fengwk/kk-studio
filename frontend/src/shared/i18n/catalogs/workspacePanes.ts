import type { LocaleCatalog } from '../types'

export const workspacePanesCatalog = {
  'pane.splitRight': { 'en-US': 'Split right', 'zh-CN': '向右拆分' },
  'pane.splitDown': { 'en-US': 'Split down', 'zh-CN': '向下拆分' },
  'pane.split': { 'en-US': 'Split pane', 'zh-CN': '拆分窗格' },
  'pane.limit': { 'en-US': 'Up to {{count}} panes', 'zh-CN': '最多 {{count}} 个窗格' },
  'pane.noRoom': { 'en-US': 'Not enough space to split', 'zh-CN': '空间不足，无法拆分' },
  'pane.fullscreen': { 'en-US': 'Fullscreen', 'zh-CN': '全屏' },
  'pane.exitFullscreen': { 'en-US': 'Exit fullscreen', 'zh-CN': '退出全屏' },
  'pane.close': { 'en-US': 'Close pane', 'zh-CN': '关闭窗格' },
  'pane.switch': { 'en-US': 'Switch pane: {{letter}} {{title}}', 'zh-CN': '切换窗格：{{letter}} {{title}}' },
  'pane.choice': { 'en-US': '{{letter}}: {{title}}', 'zh-CN': '{{letter}}：{{title}}' },
  'pane.drag': { 'en-US': 'Drag pane {{letter}} to swap', 'zh-CN': '拖动窗格 {{letter}} 交换位置' },
  'pane.resize': { 'en-US': 'Resize panes (double-click to reset)', 'zh-CN': '调整窗格大小（双击重置）' },
  'pane.pick': { 'en-US': 'Open in pane', 'zh-CN': '打开到窗格' },
} satisfies LocaleCatalog
