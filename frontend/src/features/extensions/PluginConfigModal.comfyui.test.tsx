import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginConfigModal } from './PluginConfigModal'
import { decodePluginList } from '@/shared/api/ai-plugin-codec'
import { pluginsService } from '@/shared/api/plugins-service'
import type {
  PluginConfigurationUpdateRequestDTO,
  PluginDTO,
} from '@/shared/api/contracts/ai-plugin'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: { updateConfiguration: vi.fn() },
}))

/**
 * 真实 ComfyUI 插件配置的 HTTP golden，由 Java 侧运行时测试用生产 mapper 产出并提交在同一 worktree。
 * 前端只读同一份资源，因此这里断言的 schema、默认 H3 预置工作流与输出选择器都不是手写镜像。
 */
function comfyuiHttpGolden(): PluginDTO {
  const source = readFileSync(
    resolve(
      dirname(fileURLToPath(import.meta.url)),
      '../../../../web/src/test/resources/fun/fengwk/kkstudio/web/plugin/comfyui-http.json',
    ),
    'utf8',
  )
  const envelope: unknown = JSON.parse(source)
  if (envelope === null || typeof envelope !== 'object' || !('data' in envelope)) {
    throw new Error('HTTP wire golden must contain a data envelope')
  }
  const decoded = decodePluginList([envelope.data])
  expect(decoded).toHaveLength(1)
  return decoded[0]
}

/** 与 Java 侧共用同一份真实 schema，用来推导可选/必需属性，避免测试里再手写一份结构。 */
function comfyuiSchema(): Record<string, unknown> {
  const parsed: unknown = JSON.parse(comfyuiHttpGolden().configuration.valuesSchema)
  return parsed as Record<string, unknown>
}

function workflowItemSchema(): Record<string, unknown> {
  const workflows = (comfyuiSchema().properties as Record<string, unknown>)
    .workflows as Record<string, unknown>
  return workflows.items as Record<string, unknown>
}

function requiredKeysOf(schemaNode: Record<string, unknown>): string[] {
  return [...((schemaNode.required as string[] | undefined) ?? [])].sort()
}

describe('PluginConfigModal real ComfyUI workflow', () => {
  let queryClient: QueryClient
  const onClose = vi.fn()
  const onSuccess = vi.fn()

  beforeEach(() => {
    vi.resetAllMocks()
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  function renderModal(plugin: PluginDTO) {
    return render(
      <QueryClientProvider client={queryClient}>
        <PluginConfigModal plugin={plugin} onClose={onClose} onSuccess={onSuccess} />
      </QueryClientProvider>,
    )
  }

  /** 保存一次并返回这一次提交的请求体：断言直接落在真实提交内容上。 */
  async function save(): Promise<PluginConfigurationUpdateRequestDTO> {
    const updateConfiguration = vi.mocked(pluginsService.updateConfiguration)
    const calls = updateConfiguration.mock.calls.length
    await userEvent.setup().click(screen.getByRole('button', { name: '保存配置' }))
    await waitFor(() => {
      expect(updateConfiguration.mock.calls.length).toBe(calls + 1)
    })
    return updateConfiguration.mock.calls[calls][1]
  }

  it('renders the real H3 preset workflow straight from the plugin schema', () => {
    const plugin = comfyuiHttpGolden()
    renderModal(plugin)

    const workflow = screen.getByRole('group', { name: 'workflows[0]' })
    expect(within(workflow).getByRole('textbox', { name: 'id' })).toHaveValue('minimax-h3-ref2va')
    expect(within(workflow).getByRole('textbox', { name: 'description' })).toHaveValue(
      'MiniMax-H3 Ref2VA',
    )
    expect(within(workflow).getByRole('button', { name: 'strategy' })).toHaveTextContent('H3')

    // 三个内在参数绑定按 schema 结构化展开，仍然是真实预置值。
    const bindings = within(workflow).getByRole('group', { name: 'parameterBindings' })
    expect(within(bindings).getAllByRole('textbox', { name: 'argument' })).toHaveLength(3)
    expect(within(bindings).getAllByRole('textbox', { name: 'argument' })[0]).toHaveValue('prompt')
    expect(within(bindings).getAllByRole('textbox', { name: 'nodeId' })[0]).toHaveValue('136')
    expect(within(bindings).getAllByRole('textbox', { name: 'input' })[0]).toHaveValue('prompt')

    // 单一匿名输出：schema 声明了可选 name，但 golden 里没有该键，因此只是空输入且没有可省略的键。
    const outputs = within(workflow).getByRole('group', { name: 'outputs' })
    expect(within(outputs).getAllByRole('group')).toHaveLength(1)
    expect(within(outputs).getByRole('textbox', { name: 'name' })).toHaveValue('')
    expect(within(outputs).queryByRole('button', { name: '未设置 name' })).not.toBeInTheDocument()
    expect(within(outputs).getByRole('button', { name: 'field' })).toHaveTextContent('videos')
    expect(within(outputs).getByRole('textbox', { name: 'index' })).toHaveValue('0')

    // 未使用的 mediaBindings 是空数组，只暴露新增入口而不伪造条目。
    const media = within(workflow).getByRole('group', { name: 'mediaBindings' })
    expect(within(media).queryAllByRole('group')).toHaveLength(0)
    expect(within(media).getByRole('button', { name: '添加 mediaBindings 条目' })).toBeEnabled()
  })

  it('shows the real ComfyUI API graph unescaped in the multiline editor', async () => {
    const plugin = comfyuiHttpGolden()
    const values = JSON.parse(plugin.configuration.valuesJson) as {
      workflows: { workflowJson: string; argsSchemaJson: string }[]
    }
    renderModal(plugin)

    const workflow = screen.getByRole('group', { name: 'workflows[0]' })
    await userEvent.setup().click(
      within(workflow).getByRole('button', { name: 'workflowJson 的多行/JSON 文本编辑器' }),
    )
    const editor = within(workflow).getByRole('textbox', { name: 'workflowJson' })
    expect(editor).toHaveValue(values.workflows[0].workflowJson)
    expect(values.workflows[0].workflowJson.length).toBeGreaterThan(5000)
    // 展开后就是可直接编辑与粘贴的真实图，不再是转义后的单行字符串。
    expect((editor as HTMLTextAreaElement).value).toContain('\n')
  })

  it('saves the untouched real configuration byte-identically', async () => {
    const plugin = comfyuiHttpGolden()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(plugin)
    renderModal(plugin)

    const payload = await save()
    expect(payload.valuesJson).toBe(plugin.configuration.valuesJson)
  })

  it('keeps the preset graph byte-identical while editing sibling fields', async () => {
    const plugin = comfyuiHttpGolden()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(plugin)
    const original = JSON.parse(plugin.configuration.valuesJson) as {
      workflows: { id: string; workflowJson: string; parameterBindings: unknown[] }[]
    }
    renderModal(plugin)

    const workflow = screen.getByRole('group', { name: 'workflows[0]' })
    fireEvent.change(within(workflow).getByRole('textbox', { name: 'id' }), {
      target: { value: 'h3-edited' },
    })
    const payload = await save()

    const edited = JSON.parse(payload.valuesJson) as typeof original
    expect(edited.workflows[0].id).toBe('h3-edited')
    expect(payload.valuesJson).toContain(JSON.stringify(original.workflows[0].workflowJson))
    expect(edited.workflows[0].parameterBindings).toEqual(original.workflows[0].parameterBindings)
  })

  it('appends and removes a workflow from the real schema using only required placeholders', async () => {
    const plugin = comfyuiHttpGolden()
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(plugin)
    renderModal(plugin)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '添加 workflows 条目' }))
    const added = screen.getByRole('group', { name: 'workflows[1]' })
    expect(within(added).getByRole('textbox', { name: 'id' })).toHaveValue('')

    let payload = await save()
    let workflows = (JSON.parse(payload.valuesJson) as { workflows: Record<string, unknown>[] })
      .workflows
    expect(workflows).toHaveLength(2)
    // 占位只包含 schema 声明的必需属性，绝不为可选属性发明值。
    expect(Object.keys(workflows[1]).sort()).toEqual(requiredKeysOf(workflowItemSchema()))
    expect(workflows[1].outputs).toEqual([])
    expect(workflows[1].mediaBindings).toEqual([])
    // 真实预置工作流逐字节保留，没有被重新格式化。
    expect(payload.valuesJson).toContain(JSON.stringify(
      (JSON.parse(plugin.configuration.valuesJson) as { workflows: { workflowJson: string }[] })
        .workflows[0].workflowJson,
    ))

    await user.click(
      within(screen.getByRole('group', { name: 'workflows[1]' })).getByRole('button', {
        name: '删除 workflows[1]',
      }),
    )
    payload = await save()
    workflows = (JSON.parse(payload.valuesJson) as { workflows: Record<string, unknown>[] }).workflows
    expect(workflows).toHaveLength(1)
    expect(payload.valuesJson).toBe(plugin.configuration.valuesJson)
  })
})
