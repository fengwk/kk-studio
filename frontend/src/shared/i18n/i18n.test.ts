import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  getLocale,
  LOCALE_STORAGE_KEY,
  setLocale,
  translate,
  useI18n,
} from '@/shared/i18n'
import { messageCatalog } from '@/shared/i18n/catalogs'
import { chatCatalog } from '@/shared/i18n/catalogs/chat'
import { resourcesCatalog } from '@/shared/i18n/catalogs/resources'

describe('i18n runtime', () => {
  it('defaults to en-US when the stored preference is absent or invalid', () => {
    localStorage.removeItem(LOCALE_STORAGE_KEY)
    expect(getLocale()).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')

    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr-FR')
    expect(getLocale()).toBe('en-US')
  })

  it('persists locale changes and synchronizes document.lang', () => {
    setLocale('zh-CN')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')

    setLocale('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
  })

  it('rerenders hook consumers when the locale changes', () => {
    const { result } = renderHook(() => useI18n())

    expect(result.current.locale).toBe('zh-CN')
    expect(result.current.t('settings.sections.environments')).toBe('环境')

    act(() => {
      result.current.setLocale('en-US')
    })

    expect(result.current.locale).toBe('en-US')
    expect(result.current.t('settings.sections.environments')).toBe('Environments')
  })

  it('returns a visible deterministic marker for missing messages', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const missingKey: string = 'missing.example'
    try {
      // 测试意图：未知 key 在编译期被拒绝，同时运行时仍保留缺失文案标记。
      // @ts-expect-error string 类型的动态 key 不得传入 translate。
      expect(translate(missingKey)).toBe('⟦missing:missing.example⟧')
      expect(consoleError).toHaveBeenCalledWith('Missing i18n message: missing.example')
    } finally {
      consoleError.mockRestore()
    }
  })
})

/**
 * 双语字典是同一份产品文案的两个 locale：任何 key 的 en-US / zh-CN 都必须同时存在，
 * 且插值占位符名称集合完全一致（数量与名字），避免译文丢占位符导致运行时渲染出
 * ⟦missing:name⟧ 或吞掉变量。
 */
describe('i18n catalog locale alignment', () => {
  const catalog = messageCatalog as Record<string, Record<string, string>>
  const locales = ['en-US', 'zh-CN'] as const
  const placeholderPattern = /\{\{\s*([\w.-]+)\s*\}\}/gu

  function placeholderNames(text: string): string[] {
    return [...text.matchAll(placeholderPattern)].map((match) => match[1]!).sort()
  }

  it('defines a non-empty message for both locales under every key', () => {
    for (const [key, messages] of Object.entries(catalog)) {
      for (const locale of locales) {
        const message = messages[locale]
        expect(typeof message, `${key} ${locale}`).toBe('string')
        expect(message.trim(), `${key} ${locale}`).not.toBe('')
      }
    }
  })

  it('keeps the same placeholder names in both locales', () => {
    for (const [key, messages] of Object.entries(catalog)) {
      expect(placeholderNames(messages['zh-CN']), key).toEqual(placeholderNames(messages['en-US']))
    }
  })

  it('describes current runtime and update progress without task phase states', () => {
    // 运行状态只表达当前快照与下载进度：无历史任务阶段，也不把 100% 下载写成更新成功。
    expect(catalog['ai.environment.runtime.unavailable']?.['zh-CN']).toContain('不可用')
    expect(catalog['ai.environment.update.none']?.['zh-CN']).toBe('无进行中的更新')
    expect(catalog['ai.environment.update.progressUnknownTotal']?.['zh-CN']).toContain(
      '总大小未知',
    )
    expect(
      Object.keys(catalog).filter((key) => key.startsWith('ai.environment.update.phase.')),
    ).toEqual([])
  })
})

/**
 * 动态拼出的 key 无法被静态匹配，只允许下列显式前缀；每个前缀都对应唯一的构造点，
 * 新增动态 key 时必须同时在这里登记，否则守卫会把它们当成无引用文案。
 */
const DYNAMIC_KEY_PREFIXES = [
  // CatalogEditorSection.tsx / AiConsoleResourceEditorModal.tsx: `ai.catalog.section.${name}`
  'ai.catalog.section.',
  // ModelPricingPlanEditor.tsx: `ai.catalog.form.weekday.${day}`（1..7）
  'ai.catalog.form.weekday.',
  // ModelPricingPlanEditor.tsx: `ai.catalog.form.${reason}`，reason 为
  // pricingOverlapTokens / pricingDuplicateTier / pricingOverlapTime
  'ai.catalog.form.pricing',
]

const CHAT_DYNAMIC_KEY_PREFIXES = [
  // ThreadTreeRows.tsx: `ai.runtime.agentTree.outcome.${outcome}`
  'ai.runtime.agentTree.outcome.',
  // ToolMessageBlock.tsx: `ai.runtime.tool.task.${taskReceipt.status}` / `ai.runtime.tool.${view.executionState}`
  'ai.runtime.tool.',
  // ToolCallPreviewSections.tsx: `ai.runtime.mutation.lossyDecode.${tool}.${plural}`
  'ai.runtime.mutation.lossyDecode.',
  // ResourceUri.tsx: `ai.resource.openInIde.${openError}`
  'ai.resource.openInIde.',
]

/** thread-commands.ts: `ai.runtime.command.${id}` / `ai.runtime.command.${id}Label` */
const ACTIVE_THREAD_COMMAND_IDS = ['models', 'stop', 'upload', 'debug'] as const

const THREAD_COMMAND_KEYS = new Set(
  ACTIVE_THREAD_COMMAND_IDS.flatMap((id) => [
    `ai.runtime.command.${id}`,
    `ai.runtime.command.${id}Label`,
  ]),
)

const sourceRoot = resolve(process.cwd(), 'src')
const catalogDefinitionRoot = resolve(sourceRoot, 'shared/i18n/catalogs')

/** 拼接源码文本：目录模块本身是 key 定义处，不能算引用方。 */
function collectSourceText(dir: string): string {
  const chunks: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (fullPath !== catalogDefinitionRoot) {
        chunks.push(collectSourceText(fullPath))
      }
    } else if (/\.(?:ts|tsx)$/u.test(entry.name) && !/\.(?:test|spec)\./u.test(entry.name)) {
      chunks.push(readFileSync(fullPath, 'utf8'))
    }
  }
  return chunks.join('\n')
}

/**
 * 文案只用来说明界面事实：资源目录里的每个 key 都必须还有源码引用方，
 * 否则它是旧界面留下的废弃文案，只能靠全仓 grep 才能发现。
 */
describe('i18n catalog key usage', () => {
  it('references every resources catalog key from source', () => {
    const source = collectSourceText(sourceRoot)
    const references = new Set(
      Array.from(source.matchAll(/(['"`])([\w.-]+)\1/gu), ([, , key]) => key),
    )
    const unreferenced = Object.keys(resourcesCatalog).filter(
      (key) =>
        !DYNAMIC_KEY_PREFIXES.some((prefix) => key.startsWith(prefix)) && !references.has(key),
    )

    expect(unreferenced, 'catalog keys without a source reference').toEqual([])
  })

  it('references every chat catalog key from source', () => {
    const source = collectSourceText(sourceRoot)
    const references = new Set(
      Array.from(source.matchAll(/(['"`])([\w.-]+)\1/gu), ([, , key]) => key),
    )
    const unreferenced = Object.keys(chatCatalog).filter(
      (key) =>
        !THREAD_COMMAND_KEYS.has(key) &&
        !CHAT_DYNAMIC_KEY_PREFIXES.some((prefix) => key.startsWith(prefix)) &&
        !references.has(key),
    )

    expect(unreferenced, 'chat catalog keys without a source reference').toEqual([])
  })
})
