import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * helper 入口的定位。
 *
 * <p>helper **只运行编译产物** `helper.js`：Node 的类型剥离不支持 parameter property 等语法，源码态直接执行并不可靠，因此这里
 * 绝不退回 `.ts`，也不做任何 transform-types/loader hook 兼容。
 *
 * <p>生产取同目录 `helper.js`；Vitest 源码开发态（本文件仍在 `src/` 下）取本仓库 `daemon/dist/native/helper.js`。两者都不存在
 * 时显式失败并提示先构建，绝不退回到「没有范围」的直接执行。
 *
 * <p>`base` 仅为测试提供（验证「没有构建产物时失败关闭」），生产调用一律使用默认值。
 */
export function resolveHelperPath(base: URL = new URL('./', import.meta.url)): string {
  const compiled = fileURLToPath(new URL('helper.js', base))
  if (existsSync(compiled)) {
    return compiled
  }
  const built = fileURLToPath(new URL('../../dist/native/helper.js', base))
  if (existsSync(built)) {
    return built
  }
  throw new Error('the native process scope helper build output is missing; build the daemon (tsc -p tsconfig.json) before starting a process scope')
}
