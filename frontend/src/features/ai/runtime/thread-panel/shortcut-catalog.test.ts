import { describe, expect, it } from 'vitest'
import {
  SHORTCUT_CATALOG,
} from '@/features/ai/runtime/thread-panel/shortcut-catalog'
import { setLocale, translate } from '@/shared/i18n'

/**
 * 快捷键 catalog 是只读事实源：分组完整、键唯一，且每个 descriptionKey / titleKey
 * 在两个 locale 都必须可解析（缺失键会渲染为 ⟦missing:key⟧）。
 */
describe('SHORTCUT_CATALOG', () => {
  it('declares the four fact groups in order', () => {
    expect(SHORTCUT_CATALOG.map((group) => group.id)).toEqual([
      'application',
      'thread',
      'events',
      'canvas',
    ])
    for (const group of SHORTCUT_CATALOG) {
      expect(group.titleKey).toMatch(/^ai\.runtime\.shortcuts\.group\./)
      expect(group.entries.length).toBeGreaterThan(0)
    }
  })

  it('keeps keys unique within each group', () => {
    for (const group of SHORTCUT_CATALOG) {
      const keys = group.entries.map((entry) => entry.keys)
      expect(new Set(keys).size).toBe(keys.length)
    }
  })

  it('resolves every title and description in zh-CN and en-US (no missing keys)', () => {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      setLocale(locale)
      for (const group of SHORTCUT_CATALOG) {
        expect(translate(group.titleKey), `${locale} ${group.titleKey}`).not.toMatch(/⟦missing/)
        for (const entry of group.entries) {
          const text = translate(entry.descriptionKey)
          expect(text, `${locale} ${entry.descriptionKey}`).not.toMatch(/⟦missing/)
          expect(text.trim(), `${locale} ${entry.descriptionKey}`).not.toBe('')
        }
      }
    }
  })

  it('only declares shortcuts that exist in the current implementation', () => {
    // Application：ThreadComposer 全局 Escape。
    const application = SHORTCUT_CATALOG.find((group) => group.id === 'application')!
    expect(application.entries.map((entry) => entry.keys)).toEqual(['Esc'])
    // Events：ThreadEventView 键盘语义。
    const events = SHORTCUT_CATALOG.find((group) => group.id === 'events')!
    expect(events.entries.map((entry) => entry.keys)).toEqual([
      '↑ / ↓',
      'PageUp / PageDown',
      'Home / End',
      'Enter / Space',
      'Esc',
    ])
    // Canvas：useCanvasKeyboard 实际绑定的快捷键。
    const canvas = SHORTCUT_CATALOG.find((group) => group.id === 'canvas')!
    expect(canvas.entries.map((entry) => entry.keys)).toEqual([
      'Esc',
      'Ctrl/Cmd+K',
      '0',
      '1',
      'F',
      'T',
      'Delete / Backspace',
    ])
  })
})
