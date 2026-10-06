import { describe, expect, it } from 'vitest'
import { isCanonicalThreadName, normalizeThreadName } from '@/features/ai/chat/thread-name'

describe('Thread name normalization', () => {
  it('trims outer whitespace and rejects blank input', () => {
    // 名称是创建 target 的必需事实：空白输入没有可持久化的规范形式。
    expect(normalizeThreadName('  branch-1  ')).toBe('branch-1')
    expect(normalizeThreadName('')).toBeNull()
    expect(normalizeThreadName('   ')).toBeNull()
    expect(normalizeThreadName('\n\t ')).toBeNull()
  })

  it('counts code points, not UTF-16 units, for the 256 limit', () => {
    // 后端按 code point 计数：emoji 等代理对不得被算成两个字符。
    const astral = '😀'.repeat(256)
    expect(normalizeThreadName(astral)).toBe(astral)
    expect(normalizeThreadName('😀'.repeat(257))).toBeNull()
    expect(normalizeThreadName(`${'a'.repeat(256)} `)).toBe('a'.repeat(256))
    expect(normalizeThreadName('a'.repeat(257))).toBeNull()
  })

  it('collapses whitespace runs to a single space and recognizes canonical values', () => {
    // 与后端 Names.normalize 一致：先把任意 Unicode 空白折叠为单空格，再去首尾。
    expect(normalizeThreadName('  new   branch  ')).toBe('new branch')
    expect(normalizeThreadName('a\t\nb\u00a0c')).toBe('a b c')
    expect(isCanonicalThreadName('new branch')).toBe(true)
    expect(isCanonicalThreadName(' new branch ')).toBe(false)
    expect(isCanonicalThreadName('new  branch')).toBe(false)
    expect(isCanonicalThreadName('')).toBe(false)
    expect(isCanonicalThreadName('a'.repeat(257))).toBe(false)
  })
})
