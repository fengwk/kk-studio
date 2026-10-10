import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * helper 入口的定位：编译产物是同目录 `helper.js`，源码开发态是同目录 `helper.ts`（Node 类型剥离可直接执行）。
 *
 * <p>两者只取存在者；都不存在时显式失败，绝不退回到「没有范围」的直接执行。
 */
export function resolveHelperPath(): string {
  const compiled = fileURLToPath(new URL('./helper.js', import.meta.url))
  if (existsSync(compiled)) {
    return compiled
  }
  const source = fileURLToPath(new URL('./helper.ts', import.meta.url))
  if (existsSync(source)) {
    return source
  }
  throw new Error('the native process scope helper module is missing')
}
