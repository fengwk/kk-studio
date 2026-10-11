import type { SystemProxyResolver } from '../contracts.js'
import { UnsupportedPlatformError } from './process-supervisor.js'
import { LinuxSystemProxyResolver, loadLinuxProxyBindings } from './system-proxy-linux.js'
import { WindowsSystemProxyResolver, loadWindowsProxyBindings } from './system-proxy-windows.js'

/**
 * 按平台创建操作系统代理解析器。
 *
 * <p>出站策略把等价的 HTTP/HTTPS URL 交给 {@link SystemProxyResolver.resolve}：`null` 严格表示操作系统
 * 选择了直连，绝不把任意连接失败兜底成直连。委托只读取操作系统配置，不修改它，也不回显任何 URI/环境变量/请求头/原生异常
 * 原文。
 *
 * <p>Windows 走 WinHTTP 的当前用户 IE 配置与异步代理解析，Linux 走 GLib/GIO 的真实 `GProxyResolver`。macOS 与未知平台
 * 一律失败关闭：与进程收敛一样复用 {@link UnsupportedPlatformError}，不提供任何降级实现。
 */
export function createSystemProxyResolver(): SystemProxyResolver {
  if (process.platform === 'win32') {
    return new WindowsSystemProxyResolver(loadWindowsProxyBindings())
  }
  if (process.platform === 'linux') {
    return new LinuxSystemProxyResolver(loadLinuxProxyBindings())
  }
  throw new UnsupportedPlatformError(process.platform)
}
