import type { SystemProxyResolver } from '../contracts.js'
import { DarwinSystemProxyResolver, loadDarwinProxyBindings } from './system-proxy-darwin.js'
import { UnsupportedPlatformError } from './process-supervisor.js'
import { LinuxSystemProxyResolver, loadLinuxProxyBindings } from './system-proxy-linux.js'
import { WindowsSystemProxyResolver, loadWindowsProxyBindings } from './system-proxy-windows.js'

/**
 * 按平台创建操作系统代理解析器。
 *
 * <p>出站策略把等价的 HTTP/HTTPS URL 交给 {@link SystemProxyResolver.resolve}：`null` 只表示操作系统明确选择了直连。
 * 委托只读取操作系统配置，不修改它，也不回显任何 URI、环境变量、请求头或原生异常原文。
 *
 * <p>工厂本身不查询、不加载原生代理库。Windows / Linux / macOS 的真实绑定都要到第一次 {@code resolve} 才初始化，因此
 * 显式环境代理、空值直连和 {@code no_proxy} 不会被系统代理库阻断。加载或查询失败必须抛出，绝不默默改写成直连。
 */
export function createSystemProxyResolver(): SystemProxyResolver {
  if (process.platform === 'win32') {
    return new WindowsSystemProxyResolver(() => loadWindowsProxyBindings())
  }
  if (process.platform === 'linux') {
    return new LinuxSystemProxyResolver(() => loadLinuxProxyBindings())
  }
  if (process.platform === 'darwin') {
    return new DarwinSystemProxyResolver(() => loadDarwinProxyBindings())
  }
  throw new UnsupportedPlatformError(process.platform)
}
