import { describe, expect, test } from 'vitest'
import { isCompiledModulePath } from '../../vitest.config'

/**
 * 编译产物路径判定必须跨平台：Windows 上 `fileURLToPath` 产出 `\`，而 Vite 规范化 id 用 `/`，任一侧漏归一都会让
 * dist 内部 import 丢掉 `?vitest` 标记，V8 便会在 source map 之前把被转换的测试脚本和子进程的原始脚本合并。
 * 这里用两种分隔符分别驱动真实的判定逻辑，而不是只覆盖宿主单一分隔符。
 */
describe('compiled module path matching', () => {
  test('matches dist paths under a win32 prefix with either separator', () => {
    const dist = 'C:\\work\\daemon\\dist\\'
    expect(isCompiledModulePath('C:\\work\\daemon\\dist\\native\\index.js', dist)).toBe(true)
    expect(isCompiledModulePath('C:/work/daemon/dist/native/index.js', dist)).toBe(true)
  })

  test('matches dist paths under a posix prefix with either separator', () => {
    const dist = '/work/daemon/dist/'
    expect(isCompiledModulePath('/work/daemon/dist/native/index.js', dist)).toBe(true)
    expect(isCompiledModulePath('\\work\\daemon\\dist\\native\\index.js', dist)).toBe(true)
  })

  test('rejects sources outside dist, sibling prefixes and non-js files', () => {
    const dist = '/work/daemon/dist/'
    expect(isCompiledModulePath('/work/daemon/src/native/index.js', dist)).toBe(false)
    // 只按路径分量比较：`dist-extra` 不能被当成 dist 目录。
    expect(isCompiledModulePath('/work/daemon/dist-extra/index.js', dist)).toBe(false)
    expect(isCompiledModulePath('/work/daemon/dist/native/index.json', dist)).toBe(false)
    expect(isCompiledModulePath('/work/daemon/dist/native/index.js.map', dist)).toBe(false)
    expect(isCompiledModulePath('/work/daemon/dist/', dist)).toBe(false)
  })
})
