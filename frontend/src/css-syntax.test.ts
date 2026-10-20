import { readFileSync, readdirSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import postcss from 'postcss'
import { describe, expect, it } from 'vitest'

const sourceRoot = resolve(process.cwd(), 'src')

function collectStylesheets(dir: string): string[] {
  const files: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...collectStylesheets(full))
    } else if (entry.name.endsWith('.css')) {
      files.push(full)
    }
  }
  return files
}

describe('stylesheet syntax', () => {
  it('parses every source stylesheet with PostCSS', () => {
    // 意图：使用构建链同款 PostCSS parser 校验全部 CSS 的真实语法，而非针对个别孤立括号做断言。
    const stylesheets = collectStylesheets(sourceRoot).sort()
    expect(stylesheets.length).toBeGreaterThan(0)

    const failures: string[] = []
    for (const file of stylesheets) {
      try {
        postcss.parse(readFileSync(file, 'utf8'), { from: file })
      } catch (error) {
        failures.push(`${relative(process.cwd(), file)}: ${(error as Error).message}`)
      }
    }

    expect(failures).toEqual([])
  })
})
