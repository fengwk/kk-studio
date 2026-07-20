import type { Plugin } from 'vite'

/**
 * 缓解 Vite dev 白屏：
 * - 关键壳/扩展注册相关文件变更时强制 full-reload（避免 Fast Refresh 半更新）
 * - 配合 vite.config 的 awaitWriteFinish，减少“写入中读到空文件”
 */
export function recoverEmptySrcModules(): Plugin {
  return {
    name: 'recover-empty-src-modules',
    handleHotUpdate(ctx) {
      const file = ctx.file.replace(/\\/g, '/')
      const critical =
        file.includes('/ai-extension') ||
        file.includes('/AppShell.tsx') ||
        file.includes('/AiResourceCardLayout.tsx') ||
        file.includes('/providers.tsx') ||
        file.includes('/extension-host.ts')
      if (critical) {
        ctx.server.ws.send({ type: 'full-reload', path: '*' })
        return []
      }
      return undefined
    },
  }
}
