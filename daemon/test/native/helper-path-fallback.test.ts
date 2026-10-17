import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { afterEach, describe, expect, test } from 'vitest'
import { resolveHelperPath } from '../../src/native/helper-path.js'

/**
 * helper 只运行编译产物。源码开发态下 `helper-path.js` 位于 `src/native/`，此时必须相对定位到
 * 仓库内的 `dist/native/helper.js`；两者都不存在则显式失败，绝不退回 `.ts` 或 transform loader。
 */
describe('native helper path resolution', () => {
  const roots: string[] = []

  afterEach(() => {
    for (const root of roots.splice(0)) {
      rmSync(root, { recursive: true, force: true })
    }
  })

  function makeRoot(): string {
    const root = mkdtempSync(join(tmpdir(), 'kk-studio-helper-path-'))
    roots.push(root)
    return root
  }

  test('source-mode resolution falls back to the repo dist helper build', () => {
    const root = makeRoot()
    mkdirSync(join(root, 'src', 'native'), { recursive: true })
    mkdirSync(join(root, 'dist', 'native'), { recursive: true })
    writeFileSync(join(root, 'dist', 'native', 'helper.js'), '// compiled helper')
    const sourceBase = pathToFileURL(`${join(root, 'src', 'native')}/`)
    expect(resolveHelperPath(sourceBase)).toBe(join(root, 'dist', 'native', 'helper.js'))
  })

  test('a missing build output fails closed instead of probing source', () => {
    const root = makeRoot()
    mkdirSync(join(root, 'src', 'native'), { recursive: true })
    const sourceBase = pathToFileURL(`${join(root, 'src', 'native')}/`)
    expect(() => resolveHelperPath(sourceBase)).toThrow(/build output is missing/)
  })
})
