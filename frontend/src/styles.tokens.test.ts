import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = path.resolve(process.cwd(), 'src')

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      return sourceFiles(full)
    }
    return /\.(css|tsx?)$/.test(entry.name) && !entry.name.includes('.test.') ? [full] : []
  })
}

const files = sourceFiles(SRC).map((file) => ({
  file: path.relative(SRC, file),
  text: readFileSync(file, 'utf8'),
}))

function definedTokens(): Set<string> {
  const defined = new Set<string>()
  for (const { text } of files) {
    // CSS 声明 `--x:` 与 TSX 内联样式 `'--x':` 都算定义。
    for (const match of text.matchAll(/(--[a-z0-9-]+)['"]?\s*:/g)) {
      defined.add(match[1])
    }
  }
  return defined
}

describe('design tokens', () => {
  // 测试意图：组件只能消费已定义的 token，且不能用 fallback 掩盖未定义或漂移的 token。
  it('references only defined custom properties', () => {
    const defined = definedTokens()
    const undefinedRefs = files.flatMap(({ file, text }) =>
      [...text.matchAll(/var\((--[a-z0-9-]+)/g)]
        .map((match) => match[1])
        .filter((token) => !defined.has(token))
        .map((token) => `${file}: ${token}`),
    )
    expect(undefinedRefs).toEqual([])
  })

  it('does not hide root tokens behind fallbacks', () => {
    const runtimeTokens = new Set(['--range-progress', '--ui-select-available-height'])
    const fallbacks = files.flatMap(({ file, text }) =>
      [...text.matchAll(/var\((--[a-z0-9-]+),/g)]
        .map((match) => match[1])
        .filter((token) => !runtimeTokens.has(token))
        .map((token) => `${file}: ${token}`),
    )
    expect(fallbacks).toEqual([])
  })
})
