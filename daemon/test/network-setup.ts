/**
 * 统一测试网络 fixture：以空环境安装生产 initializer，系统回退是显式直连。
 *
 * <p>生产代码依旧经 {@link getOutboundNetwork} 解析代理；需要验证代理路由的测试自行安装并恢复。
 */

import { installOutboundNetwork } from '../src/network.js'
import type { SystemProxyResolver } from '../src/contracts.js'

const testSystemDirect: SystemProxyResolver = {
  async resolve(): Promise<string | null> {
    return null
  },
  async close(): Promise<void> {},
}

await installOutboundNetwork({}, testSystemDirect)
