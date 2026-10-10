import type { LocaleCatalog } from '@/shared/i18n/types'

/**
 * SH1 终端面板与视口独立中英文文案。
 */
export const shellCatalog = {
  'shell.title': {
    'zh-CN': '终端',
    'en-US': 'Terminal',
  },
  'shell.empty.noEnvironments': {
    'zh-CN': '未配置环境',
    'en-US': 'No environments configured',
  },
  'shell.loading': {
    'zh-CN': '正在加载环境…',
    'en-US': 'Loading environments…',
  },
  'shell.loadFailed': {
    'zh-CN': '环境列表加载失败，请重试',
    'en-US': 'Failed to load environments. Please try again.',
  },
  'shell.empty.selectEnvironment': {
    'zh-CN': '请选择一个环境',
    'en-US': 'Select an environment',
  },
  'shell.empty.offline': {
    'zh-CN': '环境离线，仅就绪环境可启动新终端',
    'en-US': 'Environment offline. Only ready environments can start a new terminal',
  },
  'shell.status.running': {
    'zh-CN': '运行中',
    'en-US': 'Running',
  },
  'shell.status.exited': {
    'zh-CN': '已退出',
    'en-US': 'Exited',
  },
  'shell.status.failed': {
    'zh-CN': '已失败',
    'en-US': 'Failed',
  },
  'shell.control.active': {
    'zh-CN': '控制中',
    'en-US': 'In Control',
  },
  'shell.control.observing': {
    'zh-CN': '观察中',
    'en-US': 'Observing',
  },
  'shell.control.claim': {
    'zh-CN': '获取控制',
    'en-US': 'Claim Control',
  },
  'shell.control.takeover': {
    'zh-CN': '接管控制',
    'en-US': 'Takeover Control',
  },
  'shell.control.release': {
    'zh-CN': '释放控制',
    'en-US': 'Release Control',
  },
  'shell.action.restart': {
    'zh-CN': '重新启动',
    'en-US': 'Restart',
  },
  'shell.action.terminate': {
    'zh-CN': '终止终端',
    'en-US': 'Terminate',
  },
  'shell.action.terminateConfirm': {
    'zh-CN': '确认终止',
    'en-US': 'Confirm Terminate',
  },
  'shell.action.refresh': {
    'zh-CN': '刷新并重新同步',
    'en-US': 'Refresh and Resync',
  },
  'shell.action.hide': {
    'zh-CN': '收起终端',
    'en-US': 'Collapse Terminal',
  },
  'shell.dialog.terminateTitle': {
    'zh-CN': '终止当前终端？',
    'en-US': 'Terminate Current Terminal?',
  },
  'shell.dialog.terminateDescription': {
    'zh-CN': '终止后将停止该终端进程，未保存的终端状态将丢失。',
    'en-US': 'Terminating will stop the terminal process. Any unsaved terminal state will be lost.',
  },
  'shell.dialog.targetChanged': {
    'zh-CN': '终端目标已变化，已取消本次终止，请重新确认。',
    'en-US': 'The terminal target changed. This termination was cancelled; please confirm again.',
  },
  'shell.notice.unavailable': {
    'zh-CN': '终端暂不可用，请稍后重试',
    'en-US': 'Terminal is temporarily unavailable. Please try again.',
  },
  'shell.notice.backpressure': {
    'zh-CN': '输入未被终端接收，请稍后重试',
    'en-US': 'Input was not accepted by the terminal. Please try again.',
  },
  'shell.notice.not-written': {
    'zh-CN': '这次输入没有写入终端，请重新输入',
    'en-US': 'This input was not written to the terminal. Please retype it.',
  },
  'shell.notice.outcome-unknown': {
    'zh-CN': '操作结果暂不确定，请刷新确认',
    'en-US': 'The operation result is uncertain. Please refresh to confirm.',
  },
  'shell.notice.control-rejected': {
    'zh-CN': '暂时无法获得终端控制权',
    'en-US': 'Unable to acquire terminal control right now',
  },
  'shell.notice.stale-mode': {
    'zh-CN': '输入方式已更新，请重新输入',
    'en-US': 'Input mode changed. Please retype.',
  },
  'shell.notice.failed': {
    'zh-CN': '终端会话出错，请刷新或重启',
    'en-US': 'The terminal session failed. Please refresh or restart.',
  },
  'shell.notice.scope-limit': {
    'zh-CN': '已达终端数量上限，请先关闭其他终端',
    'en-US': 'Terminal limit reached. Please close another terminal first.',
  },
  'shell.input.ariaLabel': {
    'zh-CN': '终端输入',
    'en-US': 'Terminal input',
  },
  'shell.input.rejected': {
    'zh-CN': '本次输入未发送，请缩短内容或调整输入后重试',
    'en-US': 'This input was not sent. Please shorten or adjust it and try again.',
  },
  'shell.viewport.ariaLabel': {
    'zh-CN': '终端屏幕',
    'en-US': 'Terminal screen',
  },
  'shell.tabs.ariaLabel': {
    'zh-CN': '环境终端标签',
    'en-US': 'Environment terminal tabs',
  },
} as const satisfies LocaleCatalog
