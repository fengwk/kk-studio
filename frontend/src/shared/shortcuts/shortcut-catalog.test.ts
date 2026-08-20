import { describe, expect, it } from 'vitest'
import {
  SHORTCUT_CATALOG,
  SHORTCUT_SCOPE_ORDER,
  SHORTCUT_SCOPE_TITLE_KEYS,
  type ShortcutDefinition,
  type ShortcutScope,
} from '@/shared/shortcuts/shortcut-catalog'
import { setLocale, translate } from '@/shared/i18n'

/**
 * 快捷键 catalog 是只读事实源：scope 顺序稳定、每条定义 id 稳定唯一，
 * 且每个 labelKey / descriptionKey / 分组标题在两个 locale 都必须可解析
 * （缺失键会渲染为 ⟦missing:key⟧）。
 */
describe('SHORTCUT_CATALOG', () => {
  it('declares the four fact scopes in order and a title key per scope', () => {
    expect(SHORTCUT_SCOPE_ORDER).toEqual(['application', 'thread', 'debug', 'canvas'])
    for (const scope of SHORTCUT_SCOPE_ORDER) {
      expect(SHORTCUT_SCOPE_TITLE_KEYS[scope]).toMatch(/^ai\.runtime\.shortcuts\.group\./)
    }
    const scopes = new Set<ShortcutScope>(SHORTCUT_CATALOG.map((definition) => definition.scope))
    expect([...scopes]).toEqual([...SHORTCUT_SCOPE_ORDER])
    for (const scope of SHORTCUT_SCOPE_ORDER) {
      expect(
        SHORTCUT_CATALOG.filter((definition) => definition.scope === scope).length,
      ).toBeGreaterThan(0)
    }
  })

  it('keeps a stable unique id for every definition', () => {
    const ids = SHORTCUT_CATALOG.map((definition) => definition.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const definition of SHORTCUT_CATALOG) {
      expect(definition.id).toMatch(new RegExp(`^${definition.scope}\\.`))
    }
  })

  it('keeps keys unique within each scope', () => {
    for (const scope of SHORTCUT_SCOPE_ORDER) {
      const keys = SHORTCUT_CATALOG.filter((definition) => definition.scope === scope)
        .map((definition) => definition.keys)
      expect(new Set(keys).size).toBe(keys.length)
    }
  })

  it('resolves every title, label and description in zh-CN and en-US (no missing keys)', () => {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      setLocale(locale)
      for (const scope of SHORTCUT_SCOPE_ORDER) {
        expect(translate(SHORTCUT_SCOPE_TITLE_KEYS[scope]), `${locale} ${SHORTCUT_SCOPE_TITLE_KEYS[scope]}`).not.toMatch(/⟦missing/)
        for (const definition of SHORTCUT_CATALOG.filter((item) => item.scope === scope)) {
          const label = translate(definition.labelKey)
          const description = translate(definition.descriptionKey)
          expect(label, `${locale} ${definition.labelKey}`).not.toMatch(/⟦missing/)
          expect(description, `${locale} ${definition.descriptionKey}`).not.toMatch(/⟦missing/)
          expect(label.trim(), `${locale} ${definition.labelKey}`).not.toBe('')
          expect(description.trim(), `${locale} ${definition.descriptionKey}`).not.toBe('')
          // 标签与说明是两条独立文案，避免同一动作重复展示。
          expect(label).not.toBe(description)
        }
      }
    }
  })

  it('only declares shortcuts that exist in the current implementation', () => {
    const keysByScope = (scope: ShortcutScope) =>
      SHORTCUT_CATALOG.filter((definition) => definition.scope === scope)
        .map((definition) => definition.keys)
    // Application：ThreadComposer 全局 Escape。
    expect(keysByScope('application')).toEqual(['Esc'])
    // Debug：ThreadEventView 键盘语义。
    expect(keysByScope('debug')).toEqual([
      '↑ / ↓',
      'Esc',
    ])
    // Canvas：useCanvasKeyboard 实际绑定的快捷键。
    expect(keysByScope('canvas')).toEqual([
      'Esc',
      'Ctrl/Cmd+K',
      '0',
      '1',
      'F',
      'T',
      'Delete / Backspace',
    ])
  })

  it('exposes every definition with the frozen ShortcutDefinition shape', () => {
    const definition: ShortcutDefinition = SHORTCUT_CATALOG[0]!
    expect(Object.keys(definition).sort()).toEqual([
      'descriptionKey',
      'id',
      'keys',
      'labelKey',
      'scope',
    ])
  })
})
