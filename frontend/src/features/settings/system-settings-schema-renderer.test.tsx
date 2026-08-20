import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SystemSettingsSchemaRenderer,
  validateSystemSettingsSchema,
} from '@/features/settings/SystemSettingsSchemaRenderer'
import {
  makeSettingsDto,
  makeSettingsSchema,
} from '@/features/settings/settings-test-fixtures'
import { settingsSectionsToDraft } from '@/features/settings/system-settings-draft'
import type { SystemSettingsSchemaDTO } from '@/shared/api/contracts/system-settings'

/** 渲染单个 section 的 schema（tab 页一次只渲染当前 section）。 */
function renderSection(schema: SystemSettingsSchemaDTO, sectionKey: string) {
  const section = schema.sections.find((candidate) => candidate.key === sectionKey)!
  return section
}

/**
 * schema renderer 契约测试：
 * - 每个 schema path 恰好渲染一个控件（data-settings-field-path 计数唯一）；
 * - 未知 path / 重复 path / 未知 type 会 fail closed（validation 报错）；
 * - 新增 schema field 不需要修改 renderer switch（由 metadata 驱动）。
 */
describe('system settings schema renderer', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders every schema field exactly once for every section', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={schema} draft={draft} onChange={() => {}} />,
    )
    const paths = new Set<string>()
    for (const element of container.querySelectorAll('[data-settings-field-path]')) {
      const path = element.getAttribute('data-settings-field-path')!
      expect(paths.has(path)).toBe(false)
      paths.add(path)
    }
    const expectedPaths = new Set(
      schema.sections.flatMap((section) => section.groups.flatMap((group) => group.fields.map((field) => field.path))),
    )
    expect(paths).toEqual(expectedPaths)
    // 双 custom atomic leaf 也各渲染一次。
    expect(screen.getByText('权限规则')).toBeInTheDocument()
    expect(screen.getByText('压缩回退模型')).toBeInTheDocument()
  })

  it('renders a single section pane when given only that section', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const paths = [
      ...container.querySelectorAll('[data-settings-field-path]'),
    ].map((element) => element.getAttribute('data-settings-field-path'))
    expect(paths).toEqual([
      'aiRuntime.retryMaxRetries',
      'aiRuntime.retryBackoffStrategy',
      'aiRuntime.retryBaseDelayMillis',
      'aiRuntime.retryMaxDelayMillis',
      'aiRuntime.compactionKeepRecentTokens',
      'aiRuntime.compactionFallbackModel',
      'aiRuntime.subagentMaxDepth',
      'aiRuntime.subagentMaxConcurrency',
      'aiRuntime.subagentMaxTotalConcurrency',
      'aiRuntime.subagentIdleTimeoutMillis',
      'aiRuntime.subagentMaxTurns',
    ])
    // 顺序与 server schema 一致。
    expect(paths![0]).toBe('aiRuntime.retryMaxRetries')
  })

  it('fail-closes on a duplicate field path', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    schema.sections[0]!.groups[1]!.fields.push(schema.sections[0]!.groups[1]!.fields[0]!)
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/duplicate settings schema field/)
  })

  it('fail-closes on an unknown field path', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    schema.sections[1]!.groups[1]!.fields[1]!.path = 'aiRuntime.compactionFallbackModel.bogus'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/unknown system settings draft path/)
  })

  it('fail-closes on an unknown field type', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    ;(schema.sections[1]!.groups[1]!.fields[0] as { type: string }).type = 'SECRET'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema field/)
  })

  it('fail-closes when a schema path is missing from the draft', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    schema.sections[1]!.groups[1]!.fields.splice(1, 1)
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(
      /settings schema field paths do not match the editable draft/,
    )
  })

  it('renders metadata-driven min/max/options/nullable on primitives', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'integrations')
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const retry = container.querySelector('[data-settings-field-path="integrations.openCliHub.requestTimeoutMillis"]')!
    expect(retry.getAttribute('data-settings-min')).toBe('1000')
    expect(retry.getAttribute('data-settings-max')).toBe('1800000')
    const seedanceRetry = container.querySelector('[data-settings-field-path="integrations.seedance.retry"]')!
    expect(seedanceRetry.getAttribute('data-settings-min')).toBe('0')
    expect(seedanceRetry.getAttribute('data-settings-max')).toBe('5')
    const nullableBaseUrl = container.querySelector('[data-settings-field-path="integrations.comfyui.baseUrl"]')!
    expect(nullableBaseUrl.getAttribute('data-settings-nullable')).toBe('true')
    const enumField = container.querySelector('[data-settings-field-path="aiRuntime.retryBackoffStrategy"]')
    // 非当前 section 不渲染。
    expect(enumField).toBeNull()
  })

  it('writes through the single get/set-by-path point', async () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const savedDrafts: typeof draft[] = []
    function StatefulHarness() {
      const [current, setCurrent] = useState(draft)
      return (
        <SystemSettingsSchemaRenderer
          schema={{ sections: [section] }}
          draft={current}
          onChange={(next) => {
            savedDrafts.push(next)
            setCurrent(next)
          }}
        />
      )
    }
    render(<StatefulHarness />)
    const user = userEvent.setup()
    const field = screen.getByLabelText('基础延迟（毫秒）')
    await user.clear(field)
    await user.type(field, '3000')
    expect(field).toHaveValue(3000)
    const next = savedDrafts.at(-1)!
    expect(next.aiRuntime.retryBaseDelayMillis).toBe('3000')
    // 未触碰字段保持原值。
    expect(next.aiRuntime.retryMaxDelayMillis).toBe('60000')
  })
})
