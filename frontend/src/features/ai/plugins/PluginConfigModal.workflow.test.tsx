import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginConfigModal } from './PluginConfigModal'
import { pluginsService } from '@/shared/api/plugins-service'
import { MAX_PLUGIN_VALUES_UTF8_BYTES } from './plugin-config-json'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: { updateConfiguration: vi.fn() },
}))

/**
 * 通用受控 schema：只出现一次「受控 object 数组 + 内嵌 JSON 字符串 + 可选字段」结构，
 * 与任何具体插件无关，用来证明编辑器不做字段名或 pluginId 特判。
 */
const WORKFLOW_SCHEMA = JSON.stringify({
  type: 'object',
  description: 'generic workflow configuration',
  properties: {
    title: { type: 'string', description: 'optional label' },
    graphJson: { type: 'string', description: 'API workflow document' },
    entries: {
      type: 'array',
      description: 'frozen entries',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          note: { type: 'string' },
          slot: {
            type: 'object',
            properties: {
              index: { type: 'integer' },
              kind: { type: 'string', enum: ['ALPHA', 'BETA'] },
            },
            required: ['index', 'kind'],
            additionalProperties: false,
          },
          outputs: {
            type: 'array',
            items: {
              type: 'object',
              properties: { name: { type: 'string' }, index: { type: 'integer' } },
              required: ['index'],
              additionalProperties: false,
            },
          },
        },
        required: ['id', 'slot'],
        additionalProperties: false,
      },
    },
  },
  required: ['entries'],
  additionalProperties: false,
})

const WORKFLOW_VALUES =
  '{"title":"root","graphJson":"{\\"1\\":{\\"seed\\":9007199254740993123456789}}",' +
  '"entries":[{"id":"a","slot":{"index":0,"kind":"ALPHA"},"outputs":[{"index":0}]},' +
  '{"id":"b","slot":{"index":1,"kind":"BETA"},"outputs":[{"index":1}]}]}'

const workflowPlugin: PluginDTO = {
  pluginId: 'generic-workflow',
  name: 'Generic Workflow',
  version: '1.0.0',
  authKind: { type: 'STATIC_KEY', secretField: 'apiKey' },
  status: 'CONNECTED',
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '4',
    valuesJson: WORKFLOW_VALUES,
    valuesSchema: WORKFLOW_SCHEMA,
    defaultsJson: '{"entries":[]}',
    secrets: { apiKey: { description: 'workflow credential', configured: true } },
  },
  desiredEpoch: '7',
  appliedEpoch: '7',
  desiredRevision: '4',
  appliedRevision: '4',
  connectionCheckSupported: false,
  harnessToolNames: [],
  canvasFunctionNames: [],
  applicationError: null,
}

describe('PluginConfigModal workflow editing', () => {
  let queryClient: QueryClient
  const onClose = vi.fn()
  const onSuccess = vi.fn()

  beforeEach(() => {
    vi.resetAllMocks()
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  function renderModal(plugin: PluginDTO = workflowPlugin) {
    return render(
      <QueryClientProvider client={queryClient}>
        <PluginConfigModal plugin={plugin} onClose={onClose} onSuccess={onSuccess} />
      </QueryClientProvider>,
    )
  }

  async function save(): Promise<Record<string, unknown>> {
    await userEvent.setup().click(screen.getByRole('button', { name: '保存配置' }))
    await waitFor(() => {
      expect(pluginsService.updateConfiguration).toHaveBeenCalledTimes(1)
    })
    const payload = vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1]
    return JSON.parse(payload.valuesJson) as Record<string, unknown>
  }

  /** 可控的在途文件读取：arrayBuffer 由测试显式放行，用来构造"读取中"的真实时序。 */
  function deferredRead(text: string) {
    let release!: (bytes: ArrayBuffer) => void
    const pending = new Promise<ArrayBuffer>((resolve) => {
      release = resolve
    })
    const file = new File([''], 'deferred.json')
    Object.defineProperty(file, 'arrayBuffer', { value: () => pending })
    return { file, release: () => release(new TextEncoder().encode(text).buffer as ArrayBuffer) }
  }

  function fileInputOf(): HTMLInputElement {
    // 弹窗挂在 portal 上，因此从 document 查文件输入。
    const input = document.querySelector('input[type="file"]')
    if (!(input instanceof HTMLInputElement)) {
      throw new Error('file input must be rendered')
    }
    return input
  }

  it('expands controlled object arrays per schema with clear indices and nested fields', () => {
    renderModal()

    const first = screen.getByRole('group', { name: 'entries[0]' })
    const second = screen.getByRole('group', { name: 'entries[1]' })
    expect(within(first).getByRole('textbox', { name: 'id' })).toHaveValue('a')
    expect(within(second).getByRole('textbox', { name: 'id' })).toHaveValue('b')

    // 嵌套 object 与嵌套 object 数组都按 schema 继续展开。
    const slot = within(first).getByRole('group', { name: 'slot' })
    expect(within(slot).getByRole('textbox', { name: 'index' })).toHaveValue('0')
    expect(within(slot).getByRole('button', { name: 'kind' })).toHaveTextContent('ALPHA')

    const outputs = within(first).getByRole('group', { name: 'outputs' })
    expect(within(outputs).getByRole('group', { name: 'outputs[0]' })).toBeInTheDocument()
    expect(
      within(outputs).getByRole('textbox', { name: 'index' }),
    ).toHaveValue('0')
  })

  it('appends only schema placeholders, omits optional properties and preserves untouched bytes', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()

    await userEvent.setup().click(screen.getByRole('button', { name: '添加 entries 条目' }))
    const added = screen.getByRole('group', { name: 'entries[2]' })
    expect(within(added).getByRole('textbox', { name: 'id' })).toHaveValue('')
    expect(within(added).getByRole('textbox', { name: 'index' })).toHaveValue('0')
    // 可选 note 未被发明出来，也没有 null / 空串占位。
    // 可选 note 未被发明出来：保存后的条目里根本不出现该键。
    expect(within(added).getByRole('textbox', { name: 'note' })).toHaveValue('')

    const values = await save()
    const entries = values.entries as Record<string, unknown>[]
    expect(entries).toHaveLength(3)
    expect(entries[2]).toEqual({ id: '', slot: { index: 0, kind: '' } })
    expect(vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson).toContain(
      '9007199254740993123456789',
    )
  })

  it('removes an item only through its indexed control and keeps the rest byte-identical', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()

    await userEvent.setup().click(
      within(screen.getByRole('group', { name: 'entries[1]' })).getByRole('button', {
        name: '删除 entries[1]',
      }),
    )
    expect(screen.queryByRole('group', { name: 'entries[1]' })).not.toBeInTheDocument()

    const values = await save()
    expect(values.entries).toEqual([{ id: 'a', slot: { index: 0, kind: 'ALPHA' }, outputs: [{ index: 0 }] }])
    expect(vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson).toContain(
      '"graphJson":"{\\"1\\":{\\"seed\\":9007199254740993123456789}}"',
    )
  })

  it('keeps a single anonymous output unnamed until the user names it, and can unset it again', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()

    const outputs = within(screen.getByRole('group', { name: 'entries[0]' })).getByRole('group', {
      name: 'outputs',
    })
    const nameInput = within(outputs).getByRole('textbox', { name: 'name' })
    expect(nameInput).toHaveValue('')
    // 未设置状态下没有可省略的键，因此不提供显式省略入口。
    expect(within(outputs).queryByRole('button', { name: '未设置 name' })).not.toBeInTheDocument()

    await userEvent.setup().type(nameInput, 'poster')
    await userEvent.setup().click(within(outputs).getByRole('button', { name: '未设置 name' }))
    expect(nameInput).toHaveValue('')

    const values = await save()
    const firstEntry = (values.entries as Record<string, unknown>[])[0]
    expect((firstEntry.outputs as Record<string, unknown>[])[0]).toEqual({ index: 0 })
    expect(
      vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson,
    ).not.toContain('"name":null')
  })

  it('treats clearing an optional text field as an explicit empty string, never a silent delete', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()

    const user = userEvent.setup()
    const titleInput = screen.getByRole('textbox', { name: 'title' })
    await user.clear(titleInput)
    expect(screen.getByRole('button', { name: '未设置 title' })).toBeInTheDocument()

    const values = await save()
    expect(values).toHaveProperty('title', '')
  })

  it('edits an embedded JSON string through the multiline editor with strict JSON validation', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()

    const user = userEvent.setup()
    await user.click(
      screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }),
    )
    const editor = screen.getByRole('textbox', { name: 'graphJson' })
    // 打开后直接看到未转义的文档原文，不需要手工 JSON 转义。
    expect(editor).toHaveValue('{"1":{"seed":9007199254740993123456789}}')

    await user.click(screen.getByRole('checkbox', { name: '按 JSON 校验' }))
    fireEvent.change(editor, { target: { value: '{"1":' } })
    expect(screen.getByRole('alert')).toHaveTextContent('JSON 内容无效')
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()

    // 非法草稿可以显式丢弃，恢复为已提交内容且不再阻止保存。
    await user.click(screen.getByRole('button', { name: '丢弃 graphJson 的草稿' }))
    expect(editor).toHaveValue('{"1":{"seed":9007199254740993123456789}}')
    expect(screen.queryByText(/JSON 内容无效/)).not.toBeInTheDocument()

    fireEvent.change(editor, {
      target: { value: '{\n  "1": {"seed": 9007199254740993123456789},\n  "2": {"ratio": 1.230000}\n}' },
    })
    const values = await save()
    expect(values.graphJson).toBe(
      '{\n  "1": {"seed": 9007199254740993123456789},\n  "2": {"ratio": 1.230000}\n}',
    )
    // 换行以 \\n 转义进入 valuesJson，长整数与精确小数逐字符保留。
    expect(vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson).toContain(
      '9007199254740993123456789',
    )
    expect(vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1].valuesJson).toContain(
      '1.230000',
    )
  })

  it('imports a strict UTF-8 JSON file and rejects oversized, invalid-UTF-8 and invalid files', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }))
    const input = fileInputOf()
    const editor = screen.getByRole('textbox', { name: 'graphJson' })

    fireEvent.change(input, {
      target: {
        files: [new File(['{"3":{"seed":9007199254740993}}'], 'graph.json', { type: 'application/json' })],
      },
    })
    await waitFor(() => {
      expect(editor).toHaveValue('{"3":{"seed":9007199254740993}}')
    })

    // 非严格 UTF-8：拒绝且不覆盖旧值。
    fireEvent.change(input, {
      target: { files: [new File([new Uint8Array([0x7b, 0xff, 0x7d])], 'bad.json')] },
    })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('严格 UTF-8')
    })
    expect(editor).toHaveValue('{"3":{"seed":9007199254740993}}')

    // 语法非法：拒绝且不覆盖旧值。
    fireEvent.change(input, { target: { files: [new File(['{"a":1,}'], 'trailing.json')] } })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('JSON 文件语法无效')
    })
    expect(editor).toHaveValue('{"3":{"seed":9007199254740993}}')

    // 超过预算：在读取前拒绝。
    fireEvent.change(input, {
      target: {
        files: [
          new File([`{"a":"${'x'.repeat(MAX_PLUGIN_VALUES_UTF8_BYTES)}"}`], 'huge.json'),
        ],
      },
    })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        `JSON 文件不得超过 ${MAX_PLUGIN_VALUES_UTF8_BYTES} 字节`,
      )
    })
    expect(editor).toHaveValue('{"3":{"seed":9007199254740993}}')

    // 取消（没有选择文件）不写值。
    fireEvent.change(input, { target: { files: [] } })
    expect(editor).toHaveValue('{"3":{"seed":9007199254740993}}')

    const values = await save()
    expect(values.graphJson).toBe('{"3":{"seed":9007199254740993}}')
  })

  it('never lets a late file read overwrite newer manual edits', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }))
    const input = fileInputOf()
    const editor = screen.getByRole('textbox', { name: 'graphJson' })

    let releaseRead: (bytes: ArrayBuffer) => void = () => {
      throw new Error('read not pending')
    }
    const pendingRead = new Promise<ArrayBuffer>((resolve) => {
      releaseRead = resolve
    })
    const slowFile = new File(['{}'], 'slow.json')
    Object.defineProperty(slowFile, 'arrayBuffer', { value: () => pendingRead })

    fireEvent.change(input, { target: { files: [slowFile] } })
    fireEvent.change(editor, { target: { value: '{"manual":1}' } })
    releaseRead(new TextEncoder().encode('{"from-file":2}').buffer as ArrayBuffer)
    await Promise.resolve()

    expect(editor).toHaveValue('{"manual":1}')
    const values = await save()
    expect(values.graphJson).toBe('{"manual":1}')
  })

  it('drops only the drafts whose array item was removed or shifted', async () => {
    renderModal()
    const user = userEvent.setup()
    const slotIndex = (index: number) =>
      within(
        within(screen.getByRole('group', { name: `entries[${index}]` })).getByRole('group', {
          name: 'slot',
        }),
      ).getByRole('textbox', { name: 'index' })

    // 合法数字草稿不会阻止删除，因此这里的断言只反映草稿归属，而不是被错误状态屏蔽。
    fireEvent.change(slotIndex(0), { target: { value: '5' } })
    fireEvent.change(slotIndex(1), { target: { value: '7' } })

    // 删除 entries[0]：被删条目自身的草稿不能留给占据该索引的旧 entries[1]，
    // 否则被删条目的输入会静默套到还活着的条目上。
    await user.click(
      within(screen.getByRole('group', { name: 'entries[0]' })).getByRole('button', {
        name: '删除 entries[0]',
      }),
    )
    expect(slotIndex(0)).toHaveValue('7')

    // 反向：删除 entries[1] 时，索引更小的 entries[0] 草稿保持原位。
    await user.click(screen.getByRole('button', { name: '添加 entries 条目' }))
    fireEvent.change(slotIndex(0), { target: { value: '5' } })
    await user.click(
      within(screen.getByRole('group', { name: 'entries[1]' })).getByRole('button', {
        name: '删除 entries[1]',
      }),
    )
    expect(slotIndex(0)).toHaveValue('5')
  })

  it('imports through the visible button, validates the imported graph and reports an unreadable file', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }))
    const input = fileInputOf()

    // 可见按钮必须真的触发隐藏文件输入，而不是只在浏览器里靠 setInputFiles 绕过。
    const openPicker = vi.spyOn(input, 'click')
    await user.click(screen.getByRole('button', { name: '从文件导入 JSON' }))
    expect(openPicker).toHaveBeenCalledTimes(1)

    // 校验模式下导入：文件内容既落进本地草稿，也按严格 JSON 写入提交值。
    await user.click(screen.getByRole('checkbox', { name: '按 JSON 校验' }))
    const graphArea = screen.getByRole('textbox', { name: 'graphJson' })
    fireEvent.change(input, {
      target: { files: [new File(['{"9":{"seed":9007199254740993}}'], 'graph.json')] },
    })
    await waitFor(() => {
      expect(graphArea).toHaveValue('{"9":{"seed":9007199254740993}}')
    })
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    // 读取失败的边界：给出明确错误、不覆盖当前值，也不能把保存永久锁死。
    const broken = new File(['{}'], 'broken.json')
    Object.defineProperty(broken, 'arrayBuffer', { value: () => Promise.reject(new Error('io')) })
    fireEvent.change(input, { target: { files: [broken] } })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('读取 JSON 文件失败')
    })
    expect(graphArea).toHaveValue('{"9":{"seed":9007199254740993}}')
    expect(screen.getByRole('button', { name: '保存配置' })).toBeEnabled()

    // 关闭校验：草稿文本按普通字符串落值并清掉不再成立的校验错误。
    await user.click(screen.getByRole('checkbox', { name: '按 JSON 校验' }))
    const values = await save()
    expect(values.graphJson).toBe('{"9":{"seed":9007199254740993}}')
  })

  it('gates saving on in-flight file reads and keeps late results out of newer state', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }))
    const input = fileInputOf()
    const editor = screen.getByRole('textbox', { name: 'graphJson' })
    const saveButton = screen.getByRole('button', { name: '保存配置' })

    // 读取中：保存被阻止，且明确提示原因；此时文本仍可继续编辑。
    const first = deferredRead('{"from-file":1}')
    fireEvent.change(input, { target: { files: [first.file] } })
    expect(screen.getByRole('status')).toHaveTextContent('正在读取导入的文件')
    expect(saveButton).toBeDisabled()
    await user.click(saveButton)
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()

    fireEvent.change(editor, { target: { value: '{"manual":1}' } })
    first.release()
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    expect(editor).toHaveValue('{"manual":1}')
    expect(saveButton).toBeEnabled()

    // 切换 JSON 校验模式让在途读取失效：晚到结果不能把刚切走的模式内容改回来。
    const second = deferredRead('{"from-file":2}')
    fireEvent.change(input, { target: { files: [second.file] } })
    await user.click(screen.getByRole('checkbox', { name: '按 JSON 校验' }))
    second.release()
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    expect(screen.getByRole('textbox', { name: 'graphJson' })).toHaveValue('{"manual":1}')

    // 折叠编辑器（卸载视图）后晚到结果同样不能让它复活。
    const third = deferredRead('{"from-file":3}')
    fireEvent.change(input, { target: { files: [third.file] } })
    await user.click(screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }))
    third.release()
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    expect(screen.getByRole('textbox', { name: 'graphJson' })).toHaveValue('{"manual":1}')

    const values = await save()
    expect(values.graphJson).toBe('{"manual":1}')
  })

  it('blocks switching editors while the form still holds unresolved draft errors', async () => {
    renderModal()
    const user = userEvent.setup()

    // 内嵌 JSON 错误草稿：既不能在全局 JSON 编辑器里被悄悄换成上次的合法值，也不能提交旧值。
    await user.click(screen.getByRole('button', { name: 'graphJson 的多行/JSON 文本编辑器' }))
    await user.click(screen.getByRole('checkbox', { name: '按 JSON 校验' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'graphJson' }), {
      target: { value: '{"1":' },
    })
    const jsonMode = screen.getByRole('button', { name: 'JSON 编辑' })
    expect(jsonMode).toBeDisabled()
    await user.click(jsonMode)
    expect(screen.queryByRole('textbox', { name: 'JSON 编辑' })).not.toBeInTheDocument()
    expect(
      screen.getByText('请先修复或显式丢弃表单中的草稿错误，再切换编辑器'),
    ).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('JSON 内容无效')
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()

    // 显式丢弃后错误消失，切换与保存同时恢复。
    await user.click(screen.getByRole('button', { name: '丢弃 graphJson 的草稿' }))
    expect(screen.queryByText(/JSON 内容无效/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'JSON 编辑' }))
    expect(screen.getByRole('textbox', { name: 'JSON 编辑' })).toBeInTheDocument()
  })

  it('never auto-discards an invalid raw array snippet to switch back to structured editing', async () => {
    renderModal()
    const user = userEvent.setup()
    const toggleName = '切换 entries 的结构化/Raw JSON 编辑'

    // 结构化 -> Raw：整个数组变成一段 raw 片段。
    await user.click(screen.getByRole('button', { name: toggleName }))
    const rawArea = screen.getByRole('textbox', { name: 'entries' })
    fireEvent.change(rawArea, { target: { value: '[] ,"injected":1' } })
    expect(screen.getByRole('alert')).toHaveTextContent('无效的 JSON 容器格式')

    // 无效片段存在时不允许切回结构化：切换会卸载这段输入，自动丢弃等同于静默吞掉用户输入。
    const toggle = screen.getByRole('button', { name: toggleName })
    expect(toggle).toBeDisabled()
    await user.click(toggle)
    expect(screen.getByRole('textbox', { name: 'entries' })).toHaveValue('[] ,"injected":1')
    expect(screen.getByRole('alert')).toHaveTextContent('无效的 JSON 容器格式')
    expect(screen.getByText('请先修复或显式丢弃 entries 下的草稿错误，再切换编辑器')).toBeInTheDocument()
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()

    // 显式修复成合法片段后，切换与保存都恢复。
    fireEvent.change(rawArea, { target: { value: '[{"id":"c","slot":{"index":2,"kind":"BETA"}}]' } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: toggleName })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: toggleName }))
    expect(screen.getByRole('group', { name: 'entries[0]' })).toBeInTheDocument()

    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    const values = await save()
    expect((values.entries as Record<string, unknown>[])[0]).toMatchObject({ id: 'c' })
  })

  it('blocks removal while a draft error is unsaved and keeps the draft visible', async () => {
    renderModal()
    fireEvent.change(screen.getByRole('textbox', { name: 'graphJson' }), {
      target: { value: '{"1":' },
    })
    const removeButton = within(screen.getByRole('group', { name: 'entries[0]' })).getByRole(
      'button',
      { name: '删除 entries[0]' },
    )
    // 普通文本编辑不校验 JSON，因此这里改用嵌套数字草稿制造错误。
    expect(removeButton).toBeEnabled()

    fireEvent.change(
      within(within(screen.getByRole('group', { name: 'entries[0]' })).getByRole('group', {
        name: 'slot',
      })).getByRole('textbox', { name: 'index' }),
      { target: { value: '-' } },
    )
    expect(removeButton).toBeDisabled()
    expect(screen.getByText('请先解决草稿错误，再删除 entries 条目')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()
  })

  it('blocks POST when the valuesJson byte budget is exceeded, including escape overhead', async () => {
    renderModal()
    const editor = screen.getByRole('textbox', { name: 'graphJson' })
    // 逻辑字符数远小于上限，但每个引号都要写成 \"，转义开销让最终 valuesJson 超过 64KiB。
    fireEvent.change(editor, { target: { value: '"'.repeat(40000) } })

    expect(screen.getByRole('alert')).toHaveTextContent(
      `普通配置不得超过 ${MAX_PLUGIN_VALUES_UTF8_BYTES} 字节`,
    )
    expect(screen.getByRole('button', { name: '保存配置' })).toBeDisabled()
    await userEvent.setup().click(screen.getByRole('button', { name: '保存配置' }))
    expect(pluginsService.updateConfiguration).not.toHaveBeenCalled()
  })

  it('keeps the secret patch and ordinary values separate while editing workflows', async () => {
    vi.mocked(pluginsService.updateConfiguration).mockResolvedValue(workflowPlugin)
    renderModal()

    await userEvent.setup().click(
      within(screen.getByRole('group', { name: 'entries[0]' })).getByRole('button', {
        name: '删除 entries[0]',
      }),
    )
    await save()

    const payload = vi.mocked(pluginsService.updateConfiguration).mock.calls[0][1]
    expect(payload.secretPatch).toEqual({ apiKey: { operation: 'KEEP' } })
    expect(payload.valuesJson).not.toContain('apiKey')
    expect(onSuccess).toHaveBeenCalledTimes(1)
  })
})
