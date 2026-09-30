/**
 * UI 矩阵（L5）case 的能力门禁。
 *
 * case 只声明 `requires*`，旗标 → 能力的映射集中在 runner 的 parseArgs；这里保持纯函数，
 * 让「缺能力时必须跳过、被 --only 显式选中时必须报错」的契约可被 node:test 直接锁定。
 */
export const UI_CAPABILITY_FLAGS = {
  requiresTools: '--with-tools',
  requiresCanvasFunction: '--with-canvas-function',
}

/** 返回当前旗标组合下缺失的能力旗标；空数组表示 case 可以执行。 */
export function missingUiCapabilities(requires = {}, flags = {}) {
  const missing = []
  if (requires.requiresTools && !flags.withTools) {
    missing.push(UI_CAPABILITY_FLAGS.requiresTools)
  }
  if (requires.requiresCanvasFunction && !flags.withCanvasFunction) {
    missing.push(UI_CAPABILITY_FLAGS.requiresCanvasFunction)
  }
  return missing
}
