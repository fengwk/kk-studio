import type { LocaleCatalog } from '@/shared/i18n/types'

export const settingsCatalog = {
  'settings.loading': {
    'en-US': 'Loading settings…',
    'zh-CN': '正在加载设置…',
  },
  'settings.title': {
    'en-US': 'Settings',
    'zh-CN': '设置',
  },
  'settings.notifications.title': {
    'en-US': 'Notifications',
    'zh-CN': '通知',
  },
  'settings.notifications.description': {
    'en-US': 'Receive browser notifications when a task completes or needs your approval.',
    'zh-CN': '任务完成或需要你审批时接收浏览器通知。',
  },
  'settings.notifications.enabled': {
    'en-US': 'Browser notifications',
    'zh-CN': '浏览器通知',
  },
  'settings.notifications.permissionLabel': {
    'en-US': 'Permission',
    'zh-CN': '权限',
  },
  'settings.notifications.permission.default': {
    'en-US': 'Not decided',
    'zh-CN': '未决定',
  },
  'settings.notifications.permission.granted': {
    'en-US': 'Granted',
    'zh-CN': '已允许',
  },
  'settings.notifications.permission.denied': {
    'en-US': 'Denied',
    'zh-CN': '已拒绝',
  },
  'settings.notifications.permission.unsupported': {
    'en-US': 'Unsupported',
    'zh-CN': '不支持',
  },
  'settings.notifications.defaultHint': {
    'en-US': 'Turning this on asks the browser for notification permission.',
    'zh-CN': '开启时会向浏览器申请通知权限。',
  },
  'settings.notifications.deniedHint': {
    'en-US':
      'Notifications are blocked. Allow this site to send notifications in your browser settings, then try again.',
    'zh-CN': '通知已被浏览器阻止。请在浏览器的站点设置中允许本站发送通知后重试。',
  },
  'settings.notifications.unsupportedHint': {
    'en-US': 'This browser does not support notifications, so this option cannot be enabled.',
    'zh-CN': '当前浏览器不支持通知，无法启用该选项。',
  },
  'settings.shortcuts.title': {
    'en-US': 'Keyboard shortcuts',
    'zh-CN': '键盘快捷键',
  },
  'settings.shortcuts.description': {
    'en-US': 'Read-only catalog of the keyboard shortcuts supported by this version.',
    'zh-CN': '本版本支持的键盘快捷键只读目录。',
  },
} satisfies LocaleCatalog
