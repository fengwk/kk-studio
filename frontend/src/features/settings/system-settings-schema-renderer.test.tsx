import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import { validateSystemSettingsSchema } from '@/features/settings/system-settings-schema-validation'
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
 * - 未知 path / 重复 path / 未知 type / 非法 metadata 会 fail closed（validation 报错）；
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

  it('renders the active section description text', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const sectionElement = container.querySelector('[data-settings-section="aiRuntime"]')!
    expect(
      within(sectionElement).getByText('共享调用重试、压缩回退与子代理预算。'),
    ).toBeInTheDocument()
  })

  it('falls back to a restart badge when a group is restart-required without explicit applyTiming and the section is not wholly restart', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'tool')
    // 抹掉 gateway 的显式 timing 并标 restart，模拟「section 不整体重启但该 group 重启生效」的元数据。
    section.groups[2]!.applyTiming = null
    section.groups[2]!.restartRequired = true
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const cards = container.querySelectorAll('.settings-card')
    expect(cards).toHaveLength(3)
    // permission / yolo 不是 restart：无 restart 徽标；gateway 走 fallback 出现 restart 徽标。
    expect(within(cards[0] as HTMLElement).queryByText('重启后生效')).toBeNull()
    expect(within(cards[1] as HTMLElement).queryByText('重启后生效')).toBeNull()
    expect(within(cards[2] as HTMLElement).getByText('重启后生效')).toBeInTheDocument()
    // tool section 自身不重启，section 级 RestartNotice 不出现。
    expect(screen.queryByRole('note')).toBeNull()
  })

  it('lets an explicit group applyTiming win over restartRequired', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'tool')
    // permission 组虽然 restartRequired=true，但显式 NEXT_INVOCATION 必须优先于 restart 徽标。
    section.groups[0]!.restartRequired = true
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const cards = container.querySelectorAll('.settings-card')
    expect(within(cards[0] as HTMLElement).getByText('下次调用生效')).toBeInTheDocument()
    expect(within(cards[0] as HTMLElement).queryByText('重启后生效')).toBeNull()
  })

  it('does not repeat restart badges per card when the whole section is restart', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'integrations')
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    // integrations 整 section 仍是重启生效：section 级 RestartNotice 恰好一个；card 内不得再重复徽标。
    expect(screen.getByRole('note')).toBeInTheDocument()
    expect(screen.getAllByText('重启后生效')).toHaveLength(1)
    const cards = container.querySelectorAll('.settings-card')
    for (const card of cards) {
      expect(within(card as HTMLElement).queryByText('重启后生效')).toBeNull()
    }
  })

  it('renders hints as already-translated text without double translation', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const { container } = render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    // 曾经出现过 t(field.hintKey) 之后 primitives 又 t(hint) 的双重翻译，导致
    // ⟦missing:已翻译文本⟧；现在 hint 必须是渲染后的文本且无 missing 标记。
    expect(screen.getByText('留空表示不额外限制。')).toBeInTheDocument()
    expect(screen.getByText('0 表示关闭超时。')).toBeInTheDocument()
    expect(container.textContent).not.toContain('⟦missing:')
  })

  it('consumes BOOLEAN hints through the switch row description', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'integrations')
    // fixture 的 BOOLEAN 字段都没有 hintKey；补一个以覆盖 switch description 消费路径。
    section.groups[0]!.fields[0]!.hintKey = 'settings.section.integrations.comfyui.description'
    render(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const switchRow = screen
      .getByRole('switch', { name: '启用 ComfyUI' })
      .closest('.settings-row')!
    expect(switchRow).toHaveTextContent('ComfyUI 运行端点与输入预算。')
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
    schema.sections[0]!.groups[1]!.fields[1]!.path = 'aiRuntime.compactionFallbackModel.bogus'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/unknown system settings draft path/)
  })

  it('fail-closes on an unknown field type', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    ;(schema.sections[0]!.groups[1]!.fields[0] as { type: string }).type = 'SECRET'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema field/)
  })

  it('fail-closes when a null model selection is declared non-nullable', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    schema.sections[0]!.groups[1]!.fields[1]!.nullable = false
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(
      /settings schema type does not match draft/,
    )
  })

  it('fail-closes when a schema path is missing from the draft', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    schema.sections[0]!.groups[1]!.fields.splice(1, 1)
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(
      /settings schema field paths do not match the editable draft/,
    )
  })

  it('fail-closes on non-boolean restartRequired on section or group', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    ;(schema.sections[0] as { restartRequired: unknown }).restartRequired = 'yes'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema section/)
    ;(schema.sections[0] as { restartRequired: unknown }).restartRequired = true
    ;(schema.sections[0]!.groups[0] as { restartRequired: unknown }).restartRequired = 1
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema group/)
  })

  it('fail-closes on an unknown applyTiming value', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    ;(schema.sections[0]!.groups[0] as { applyTiming: unknown }).applyTiming = 'LATER'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema group/)
  })

  it('fail-closes on non-finite or inverted min/max bounds', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const field = schema.sections[0]!.groups[1]!.fields[0]!
    field.min = 1.5
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema bounds/)
    field.min = 10
    field.max = 5
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema bounds/)
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

  it('derives fallback model sub-field labels from labelKey and writes through', async () => {
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
    // 子字段标签来自 schema 的 labelKey 派生，而不是硬编码的 compaction 路径。
    const provider = screen.getByLabelText('Provider')
    await user.type(provider, 'minimax')
    const next = savedDrafts.at(-1)!
    expect(next.aiRuntime.compactionFallbackModel).toEqual({
      providerName: 'minimax',
      modelName: '',
      variant: '',
    })
    // 清空所有三个字段后回落到 null。
    await user.clear(provider)
    expect(savedDrafts.at(-1)!.aiRuntime.compactionFallbackModel).toBeNull()
  })
})
