import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import { validateSystemSettingsSchema } from '@/features/settings/system-settings-schema-validation'
import {
  makeSettingsDto,
  makeSettingsSchema,
} from '@/test-support/settings-test-fixtures'
import {
  assembleSettingsUpdate,
  settingsSectionsToDraft,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'
import { agentService } from '@/shared/api/agent-service'
import type { ModelDTO, SystemSettingsSchemaDTO } from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listTools: vi.fn(),
    listModels: vi.fn(),
  },
}))

/** 渲染单个 section 的 schema（tab 页一次只渲染当前 section）。 */
function renderSection(schema: SystemSettingsSchemaDTO, sectionKey: string) {
  const section = schema.sections.find((candidate) => candidate.key === sectionKey)!
  return section
}

function renderRenderer(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

function catalogModel(): ModelDTO {
  return {
    providerName: 'minimax',
    type: 'MODEL',
    name: 'MiniMax',
    modelId: 'minimax-model-id',
    description: null,
    config: {
      limit: { context: 128000, output: 8192 },
      abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
      pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
      defaultVariant: 'default',
      variants: [{ id: 'default', promptCacheRetention: 'NONE' }, { id: 'fast', promptCacheRetention: 'NONE' }],
    },
    version: '1',
    createdAt: 1790503200,
    updatedAt: 1790503200,
  }
}

/**
 * schema renderer 契约测试：
 * - 每个 schema path 恰好渲染一个控件（data-settings-field-path 计数唯一）；
 * - 未知 path / 重复 path / 未知 type / 非法 metadata 会 fail closed（validation 报错）；
 * - 新增 schema field 不需要修改 renderer switch（由 metadata 驱动）。
 */
describe('system settings schema renderer', () => {
  beforeEach(() => {
    vi.mocked(agentService.listTools).mockResolvedValue([])
    vi.mocked(agentService.listModels).mockResolvedValue([catalogModel()])
  })

  it('renders every schema field exactly once for every section', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const { container } = renderRenderer(
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
    // custom atomic leaf（PERMISSION）渲染一次。
    expect(screen.getByText('权限规则')).toBeInTheDocument()
  })

  it('shows a server field validation error on the matching field', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const { container } = renderRenderer(
      <SystemSettingsSchemaRenderer
        schema={{ sections: [renderSection(schema, 'environment')] }}
        draft={draft}
        fieldErrors={{ 'environment.heartbeatTimeoutMillis': 'minimum is 30000 ms' }}
        onChange={() => {}}
      />,
    )
    const field = container.querySelector<HTMLInputElement>(
      '[data-settings-field-path="environment.heartbeatTimeoutMillis"] input',
    )!
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('minimum is 30000 ms')
  })

  it('renders a single section pane when given only that section', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const { container } = renderRenderer(
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
      'aiRuntime.modelHttpRetryStatusCodes',
      'aiRuntime.compactionModel',
      'aiRuntime.compactionKeepRecentTokens',
      'aiRuntime.subagentMaxDepth',
      'aiRuntime.subagentMaxConcurrency',
      'aiRuntime.subagentMaxTotalConcurrency',
      'aiRuntime.subagentMaxTurns',
    ])
    // 顺序与 server schema 一致。
    expect(paths![0]).toBe('aiRuntime.retryMaxRetries')
  })

  it('does not render the section description on the pane', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const { container } = renderRenderer(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const sectionElement = container.querySelector<HTMLElement>('[data-settings-section="aiRuntime"]')!
    expect(within(sectionElement).queryByText('共享调用重试、压缩回退与子代理预算。')).toBeNull()
  })

  it('renders hints as already-translated text without double translation', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const { container } = renderRenderer(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    // hint 为渲染后的本地化文本，不得出现双重翻译导致的 ⟦missing: 标记。
    expect(screen.getByText('全系统所有会话中同时运行的子代理任务总数上限。设为 0 表示不设全局上限。')).toBeInTheDocument()
    expect(screen.getByText('单个子代理任务的交互轮数软预算。达到上限后提醒子代理结束当前阶段并汇报。')).toBeInTheDocument()
    expect(container.textContent).not.toContain('⟦missing:')
  })

  it('consumes BOOLEAN hints through the switch row description', () => {
    // 六节 fixture 已不含 BOOLEAN 字段：用合成 BOOLEAN leaf 覆盖 renderer 的 switch row hint→description 消费路径。
    const baseDraft = settingsSectionsToDraft(makeSettingsDto())
    const draft = {
      ...baseDraft,
      advanced: { ...baseDraft.advanced, syntheticFlag: false },
    } as SystemSettingsSectionsDraft
    const section: SystemSettingsSchemaDTO['sections'][number] = {
      key: 'advanced',
      labelKey: 'settings.tabs.advanced',
      descriptionKey: 'settings.section.advanced.description',
      groups: [
        {
          key: 'advanced.synthetic',
          labelKey: 'settings.section.advanced.resource.title',
          descriptionKey: 'settings.section.advanced.resource.description',
          fields: [
            {
              path: 'advanced.syntheticFlag',
              labelKey: 'settings.field.backendNetwork.proxyUrl',
              hintKey: 'settings.field.backendNetwork.proxyUrl.hint',
              type: 'BOOLEAN',
              nullable: false,
              min: null,
              max: null,
              options: null,
            },
          ],
        },
      ],
    }
    renderRenderer(
      <SystemSettingsSchemaRenderer schema={{ sections: [section] }} draft={draft} onChange={() => {}} />,
    )
    const switchRow = screen
      .getByRole('switch', { name: '代理地址' })
      .closest('.settings-row')!
    expect(switchRow).toHaveTextContent('Backend 发起外部请求时使用的 HTTP 代理')
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
    schema.sections[0]!.groups[1]!.fields[0]!.path = 'aiRuntime.compactionKeepRecentTokens.bogus'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/unknown system settings draft path/)
  })

  it('fail-closes on an unknown field type', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    ;(schema.sections[0]!.groups[1]!.fields[0] as { type: string }).type = 'SECRET'
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema field/)
  })

  it('fail-closes when a schema path is missing from the draft', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    schema.sections[0]!.groups[0]!.fields.splice(1, 1)
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(
      /settings schema field paths do not match the editable draft/,
    )
  })

  it('fail-closes on non-finite or inverted min/max bounds', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const field = schema.sections[0]!.groups[1]!.fields[0]!
    field.min = 1.5
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema bounds/)
    field.min = null
    field.max = 2.5
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema bounds/)
    field.min = 10
    field.max = 5
    expect(validateSystemSettingsSchema(schema, draft)).toMatch(/invalid settings schema bounds/)
  })

  it('renders metadata-driven min/max/options/nullable on primitives', () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const storageMedia = renderSection(schema, 'storageMedia')
    const backendNetwork = renderSection(schema, 'backendNetwork')
    const { container } = renderRenderer(
      <SystemSettingsSchemaRenderer
        schema={{ sections: [storageMedia, backendNetwork] }}
        draft={draft}
        onChange={() => {}}
      />,
    )
    const quality = container.querySelector('[data-settings-field-path="storageMedia.thumbnailQuality"]')!
    expect(quality.getAttribute('data-settings-min')).toBe('1')
    expect(quality.getAttribute('data-settings-max')).toBe('100')
    const uploadExpires = container.querySelector('[data-settings-field-path="storageMedia.uploadExpiresSeconds"]')!
    expect(uploadExpires.getAttribute('data-settings-min')).toBe('1')
    expect(uploadExpires.getAttribute('data-settings-max')).toBeNull()
    const nullableProxy = container.querySelector('[data-settings-field-path="backendNetwork.proxyUrl"]')!
    expect(nullableProxy.getAttribute('data-settings-nullable')).toBe('true')
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
    renderRenderer(<StatefulHarness />)
    const user = userEvent.setup()
    const field = screen.getByLabelText('基础延迟（毫秒）')
    await user.clear(field)
    await user.type(field, '3000')
    expect(field).toHaveValue('3000')
    const next = savedDrafts.at(-1)!
    expect(next.aiRuntime.retryBaseDelayMillis).toBe('3000')
    // 未触碰字段保持原值。
    expect(next.aiRuntime.retryMaxDelayMillis).toBe('60000')
  })

  it('supports roundtrip editing of INTEGER_LIST field via TagInput and serializes into settings update', async () => {
    const schema = makeSettingsSchema()
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const section = renderSection(schema, 'aiRuntime')
    const savedDrafts: SystemSettingsSectionsDraft[] = []

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

    renderRenderer(<StatefulHarness />)
    const user = userEvent.setup()

    // Status code chips are initially rendered
    expect(screen.getByText('408')).toBeInTheDocument()
    expect(screen.getByText('429')).toBeInTheDocument()
    expect(screen.getByText('500')).toBeInTheDocument()

    // Remove 502
    const remove502 = screen.getByLabelText('移除 502')
    await user.click(remove502)

    const updatedDraft1 = savedDrafts.at(-1)!
    expect(updatedDraft1.aiRuntime.modelHttpRetryStatusCodes).toEqual([408, 429, 500, 503, 504])

    // Add 520 via keyboard input
    const tagInputField = screen.getByLabelText('重试 HTTP 状态码')
    await user.type(tagInputField, '520{enter}')

    const updatedDraft2 = savedDrafts.at(-1)!
    expect(updatedDraft2.aiRuntime.modelHttpRetryStatusCodes).toEqual([408, 429, 500, 503, 504, 520])

    // Assemble settings update roundtrip
    const update = assembleSettingsUpdate(updatedDraft2, '0')
    expect(update.aiRuntime.modelHttpRetryStatusCodes).toEqual([408, 429, 500, 503, 504, 520])
  })

  /** 渲染 aiRuntime 分区的有状态 harness，返回每次变更后的草稿。 */
  function renderAiRuntime(draft: SystemSettingsSectionsDraft) {
    const section = renderSection(makeSettingsSchema(), 'aiRuntime')
    const savedDrafts: SystemSettingsSectionsDraft[] = []
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
    renderRenderer(<StatefulHarness />)
    return savedDrafts
  }

  it('selects a compaction model and variant, then clears it back to null', async () => {
    // 意图：MODEL_SELECTION 的选项来自模型目录；改选模型重置变体；选“未设置”后提交为 null。
    const savedDrafts = renderAiRuntime(settingsSectionsToDraft(makeSettingsDto()))
    const user = userEvent.setup()
    const modelSelect = screen.getByLabelText('压缩模型')
    expect(modelSelect).toHaveAttribute('data-value', '')
    expect(screen.queryByLabelText('变体')).toBeNull()

    await user.click(modelSelect)
    await user.click(await screen.findByRole('option', { name: 'MiniMax' }))
    await user.click(screen.getByLabelText('变体'))
    await user.click(screen.getByRole('option', { name: 'fast' }))
    expect(savedDrafts.at(-1)!.aiRuntime.compactionModel).toEqual({ modelName: 'MiniMax', variant: 'fast' })
    expect(assembleSettingsUpdate(savedDrafts.at(-1)!, '0').aiRuntime.compactionModel).toEqual({
      modelName: 'MiniMax',
      variant: 'fast',
    })

    await user.click(screen.getByLabelText('压缩模型'))
    await user.click(screen.getByRole('option', { name: '未设置' }))
    expect(savedDrafts.at(-1)!.aiRuntime.compactionModel).toEqual({ modelName: '', variant: '' })
    expect(assembleSettingsUpdate(savedDrafts.at(-1)!, '0').aiRuntime.compactionModel).toBeNull()
  })

  it('shows a compaction model missing from the catalog as unavailable and lets it be cleared', async () => {
    // 意图：引用的模型已删除时保留原值并标为不可用，用户仍可改回“未设置”。
    const dto = makeSettingsDto()
    const savedDrafts = renderAiRuntime(
      settingsSectionsToDraft({
        ...dto,
        aiRuntime: { ...dto.aiRuntime, compactionModel: { modelName: 'Removed', variant: 'v1' } },
      }),
    )
    const user = userEvent.setup()
    const modelSelect = screen.getByLabelText('压缩模型')
    expect(modelSelect).toHaveAttribute('data-value', 'Removed')
    await waitFor(() => expect(agentService.listModels).toHaveBeenCalled())
    await user.click(modelSelect)
    expect(await screen.findByRole('option', { name: 'Removed · 不可用' })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: '未设置' }))
    expect(savedDrafts.at(-1)!.aiRuntime.compactionModel).toEqual({ modelName: '', variant: '' })
  })
})
