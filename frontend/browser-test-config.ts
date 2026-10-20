/**
 * 统一浏览器测试服务端口：默认 5174。
 * 通过环境变量 KK_BROWSER_PORT 支持并行 worktree 隔离。
 * 变量缺失时取默认值；显式传入的非法值直接报错，不静默回退。
 */
export function resolveBrowserPort(): number {
  const raw = process.env.KK_BROWSER_PORT
  if (raw === undefined) {
    return 5174
  }
  if (!/^\d+$/.test(raw)) {
    throw new Error(`KK_BROWSER_PORT 必须是十进制端口号，当前为 "${raw}"`)
  }
  const port = Number(raw)
  if (port < 1 || port > 65535) {
    throw new Error(`KK_BROWSER_PORT 必须在 1-65535 之间，当前为 "${raw}"`)
  }
  return port
}
