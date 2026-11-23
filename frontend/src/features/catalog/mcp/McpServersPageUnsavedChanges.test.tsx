import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { McpServersPage } from '@/features/catalog/mcp/McpServersPage'
import { mcpServerService } from '@/features/catalog/mcp-server-service'
import { ApiError } from '@/shared/api/client'
import type { McpServerDTO, McpServerSummaryDTO } from '@/shared/api/generated/contracts'

vi.mock('@/features/catalog/mcp-server-service', () => ({
  headersUpdate: vi.fn((headers: Record<string, string> | null, clear: boolean) => {
    if (headers && Object.keys(headers).length > 0) {
      return { operation: 'SET', value: JSON.stringify(headers) }
    }
    return { operation: clear ? 'CLEAR' : 'KEEP' }
  }),
  mcpServerService: {
    listServers: vi.fn(),
    getServer: vi.fn(),
    createServer: vi.fn(),
    updateServer: vi.fn(),
    discoverServer: vi.fn(),
    deleteServer: vi.fn(),
  },
}))

vi.mock('@/app/ai-console/AiNavigation', () => ({
  AiNavigation: () => null,
}))

const SEEDED_AT = 1784505600000
const EDIT_NAME = 'srv_edit'
const EDIT_TITLE = `编辑 MCP 服务：${EDIT_NAME}`
const SAVED_URL = 'https://example.com/mcp'

type Kind = 'create' | 'edit'
const KINDS: Kind[] = ['create', 'edit']
type ClosePath = 'escape' | 'close-button' | 'footer-cancel' | 'backdrop'
const CLOSE_PATHS: ClosePath[] = ['escape', 'close-button', 'footer-cancel', 'backdrop']

function summary(overrides: Partial<McpServerSummaryDTO> = {}): McpServerSummaryDTO {
  return {
    name: 'filesystem',
    enabled: true,
    timeoutMillis: '30000',
    discoveryStatus: 'AVAILABLE',
    toolCount: 3,
    version: '1',
    createdAt: SEEDED_AT,
    updatedAt: SEEDED_AT,
    ...overrides,
  }
}

function detail(overrides: Partial<McpServerDTO> = {}): McpServerDTO {
  return {
    name: 'filesystem',
    url: SAVED_URL,
    enabled: true,
    timeoutMillis: '30000',
    discoveryStatus: 'AVAILABLE',
    headers: { configured: false },
    toolCount: 0,
    version: '1',
    createdAt: SEEDED_AT,
    updatedAt: SEEDED_AT,
    ...overrides,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <McpServersPage />
    </QueryClientProvider>,
  )
}

async function openDialog(
  user: ReturnType<typeof userEvent.setup>,
  kind: Kind,
): Promise<HTMLElement> {
  if (kind === 'create') {
    await user.click(await screen.findByRole('button', { name: '新建 MCP 服务' }))
    return screen.getByRole('dialog', { name: '新建 MCP 服务' })
  }
  await user.click(await screen.findByLabelText(`编辑 MCP 服务 ${EDIT_NAME}`))
  const dialog = await screen.findByRole('dialog', { name: EDIT_TITLE })
  // 详情到达前只呈现 loading，此时没有可比较的基线。
  await within(dialog).findByDisplayValue(SAVED_URL)
  return dialog
}

function titleOf(kind: Kind): string {
  return kind === 'create' ? '新建 MCP 服务' : EDIT_TITLE
}

/** 让草稿偏离打开时的基线：新建改名称，编辑改 URL。 */
async function editDraft(
  user: ReturnType<typeof userEvent.setup>,
  kind: Kind,
  dialog: HTMLElement,
): Promise<void> {
  if (kind === 'create') {
    await user.type(within(dialog).getByPlaceholderText('例如 filesystem'), 'draft_srv')
    return
  }
  await user.clear(within(dialog).getByDisplayValue(SAVED_URL))
  await user.type(
    within(dialog).getByPlaceholderText('https://example.com/mcp'),
    `${SAVED_URL}-draft`,
  )
}

/** 遮罩是弹窗卡片的父节点；在遮罩自身按下鼠标才会关闭。 */
function backdropOf(dialog: HTMLElement): HTMLElement {
  const backdrop = dialog.parentElement
  if (!backdrop) {
    throw new Error('dialog is not portaled under a backdrop')
  }
  return backdrop
}

async function closeDialog(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  path: ClosePath,
): Promise<void> {
  if (path === 'escape') {
    await user.keyboard('{Escape}')
    return
  }
  if (path === 'backdrop') {
    fireEvent.mouseDown(backdropOf(dialog))
    return
  }
  const label = path === 'close-button' ? '关闭' : '取消'
  await user.click(within(dialog).getByRole('button', { name: label }))
}

/** 确认弹窗是嵌套的 alertdialog：编辑器仍是 dialog，未保存确认只有这一层。 */
function confirmation(): HTMLElement {
  return screen.getByRole('alertdialog')
}

function keepEditing(): HTMLElement {
  return within(confirmation()).getByRole('button', { name: '继续编辑' })
}

function discardChanges(): HTMLElement {
  return within(confirmation()).getByRole('button', { name: '放弃修改' })
}

async function expectDialogClosed(): Promise<void> {
  await waitFor(() => {
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
  expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
}

describe('McpServersPage unsaved changes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(mcpServerService.listServers).mockResolvedValue([
      summary({ name: EDIT_NAME, version: '1' }),
    ])
    vi.mocked(mcpServerService.getServer).mockResolvedValue(detail({ name: EDIT_NAME }))
    vi.mocked(mcpServerService.createServer).mockResolvedValue(detail({ name: 'created_srv' }))
    vi.mocked(mcpServerService.updateServer).mockResolvedValue(detail({ name: EDIT_NAME, version: '2' }))
    vi.mocked(mcpServerService.discoverServer).mockResolvedValue(
      detail({ name: EDIT_NAME, toolCount: 4 }),
    )
  })

  // 无修改时四条关闭入口都直接关闭，新建与编辑一致。
  it('closes pristine create and edit dialogs straight from every close path', async () => {
    const user = userEvent.setup()
    renderPage()

    for (const kind of KINDS) {
      for (const path of CLOSE_PATHS) {
        const dialog = await openDialog(user, kind)
        await closeDialog(user, dialog, path)
        await expectDialogClosed()
      }
    }
  })

  // 详情还在加载时没有可比较的基线，关闭不应被当成有未保存修改。
  it('closes the edit dialog straight away while the detail is still loading', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.getServer).mockReturnValue(new Promise<McpServerDTO>(() => {}))
    renderPage()

    await user.click(await screen.findByLabelText(`编辑 MCP 服务 ${EDIT_NAME}`))
    await screen.findByRole('dialog', { name: EDIT_TITLE })
    await user.keyboard('{Escape}')

    await expectDialogClosed()
  })

  // 四条关闭入口在有修改时都必须先确认；选择放弃才关闭、不提交，也不留下草稿。
  it('asks before closing a dirty draft from every close path and discards on confirm', async () => {
    const user = userEvent.setup()
    renderPage()

    for (const kind of KINDS) {
      for (const path of CLOSE_PATHS) {
        const dialog = await openDialog(user, kind)
        await editDraft(user, kind, dialog)
        await closeDialog(user, dialog, path)

        await screen.findByRole('alertdialog')
        expect(screen.getByRole('dialog', { name: titleOf(kind) })).toBeInTheDocument()
        await user.click(discardChanges())
        await expectDialogClosed()
      }
    }

    expect(mcpServerService.createServer).not.toHaveBeenCalled()
    expect(mcpServerService.updateServer).not.toHaveBeenCalled()

    const reopenedCreate = await openDialog(user, 'create')
    expect(within(reopenedCreate).getByPlaceholderText('例如 filesystem')).toHaveValue('')
    await closeDialog(user, reopenedCreate, 'escape')
    await expectDialogClosed()
    expect(within(await openDialog(user, 'edit')).getByDisplayValue(SAVED_URL)).toBeInTheDocument()
  })

  // 继续编辑回到原弹窗：草稿与焦点都在编辑器内；确认弹窗上的 Escape 只关确认，再次关闭放弃才关闭。
  it('keeps the draft when continuing and only closes the confirmation on Escape', async () => {
    const user = userEvent.setup()
    renderPage()
    const dialog = await openDialog(user, 'create')
    await user.type(within(dialog).getByPlaceholderText('例如 filesystem'), 'draft_srv')

    await user.keyboard('{Escape}')
    await screen.findByRole('alertdialog')
    await user.click(keepEditing())

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    const resumed = screen.getByRole('dialog', { name: titleOf('create') })
    expect(within(resumed).getByPlaceholderText('例如 filesystem')).toHaveValue('draft_srv')
    expect(resumed).toContainElement(document.activeElement as HTMLElement)

    await user.keyboard('{Escape}')
    await screen.findByRole('alertdialog')
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
    expect(
      within(screen.getByRole('dialog', { name: titleOf('create') })).getByPlaceholderText(
        '例如 filesystem',
      ),
    ).toHaveValue('draft_srv')

    await user.keyboard('{Escape}')
    await screen.findByRole('alertdialog')
    await user.click(discardChanges())

    await expectDialogClosed()
    expect(mcpServerService.createServer).not.toHaveBeenCalled()
  })

  // 关闭比较的是草稿，不是提交后会归一化的配置。
  it('asks before discarding a cleared timeout even when it would save as the default', async () => {
    const user = userEvent.setup()
    vi.mocked(mcpServerService.getServer).mockResolvedValue(detail({ name: EDIT_NAME, timeoutMillis: '60000' }))
    renderPage()
    const edit = await openDialog(user, 'edit')
    await user.clear(within(edit).getByLabelText('超时（毫秒）'))
    await user.keyboard('{Escape}')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  // 把值改回打开时的基线即视为无修改；新建逐字段比较原值，清空超时仍算修改。
  it('treats drafts restored to the opening baseline as clean', async () => {
    const user = userEvent.setup()
    renderPage()

    const create = await openDialog(user, 'create')
    const nameInput = within(create).getByPlaceholderText('例如 filesystem')
    await user.type(nameInput, 'temp_srv')
    await user.clear(nameInput)
    const enabled = within(create).getByLabelText('已启用')
    await user.click(enabled)
    await user.click(enabled)
    fireEvent.change(within(create).getByLabelText(/JSON 请求头/), {
      target: { value: '{"X-Temp": "1"}' },
    })
    fireEvent.change(within(create).getByLabelText(/JSON 请求头/), { target: { value: '' } })
    const timeout = within(create).getByLabelText('超时（毫秒）')
    await user.clear(timeout)
    await user.type(timeout, '45000')
    await user.clear(timeout)
    await user.type(timeout, '60000')
    await closeDialog(user, create, 'footer-cancel')
    await expectDialogClosed()

    // 清空超时不是提交时的默认值，原值比较下仍是草稿修改。
    const cleared = await openDialog(user, 'create')
    await user.clear(within(cleared).getByLabelText('超时（毫秒）'))
    await closeDialog(user, cleared, 'footer-cancel')
    await screen.findByRole('alertdialog')
    await user.click(keepEditing())
    await user.type(within(cleared).getByLabelText('超时（毫秒）'), '60000')
    await closeDialog(user, cleared, 'escape')
    await expectDialogClosed()

    const edit = await openDialog(user, 'edit')
    const urlInput = within(edit).getByDisplayValue(SAVED_URL)
    await user.clear(urlInput)
    await user.type(
      within(edit).getByPlaceholderText('https://example.com/mcp'),
      `${SAVED_URL}-draft`,
    )
    await user.clear(within(edit).getByPlaceholderText('https://example.com/mcp'))
    await user.type(within(edit).getByPlaceholderText('https://example.com/mcp'), SAVED_URL)
    await closeDialog(user, edit, 'escape')
    await expectDialogClosed()
  })

  // 保存成功由系统关闭弹窗，不应再问是否放弃修改。
  it('closes without asking after a successful create or update', async () => {
    const user = userEvent.setup()
    renderPage()

    const create = await openDialog(user, 'create')
    await user.type(within(create).getByPlaceholderText('例如 filesystem'), 'created_srv')
    await user.type(within(create).getByPlaceholderText('https://example.com/mcp'), SAVED_URL)
    await user.click(within(create).getByRole('button', { name: '创建' }))
    await expectDialogClosed()
    expect(mcpServerService.createServer).toHaveBeenCalledTimes(1)

    const edit = await openDialog(user, 'edit')
    const urlInput = within(edit).getByDisplayValue(SAVED_URL)
    await user.clear(urlInput)
    await user.type(
      within(edit).getByPlaceholderText('https://example.com/mcp'),
      `${SAVED_URL}-saved`,
    )
    await user.click(within(edit).getByRole('button', { name: '保存修改' }))
    await expectDialogClosed()
    expect(mcpServerService.updateServer).toHaveBeenCalledTimes(1)
  })

  // “发现工具”成功同样由系统关闭；冲突关闭与冲突后刷新也不得触发未保存确认。
  it('closes without asking on discover success and on conflict handling', async () => {
    const user = userEvent.setup()
    renderPage()

    const edit = await openDialog(user, 'edit')
    await user.click(within(edit).getByRole('button', { name: '发现工具' }))
    await expectDialogClosed()
    expect(mcpServerService.discoverServer).toHaveBeenCalledWith(EDIT_NAME, '1')

    vi.mocked(mcpServerService.updateServer).mockRejectedValueOnce(
      new ApiError('Conflict', 409, 'version_conflict', { currentVersion: '2' }),
    )
    const conflicted = await openDialog(user, 'edit')
    const urlInput = within(conflicted).getByDisplayValue(SAVED_URL)
    await user.clear(urlInput)
    await user.type(
      within(conflicted).getByPlaceholderText('https://example.com/mcp'),
      `${SAVED_URL}-conflict`,
    )
    await user.click(within(conflicted).getByRole('button', { name: '保存修改' }))

    const conflictDialog = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(screen.queryByRole('dialog', { name: EDIT_TITLE })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '放弃修改' })).not.toBeInTheDocument()
    expect(within(conflictDialog).getByRole('button', { name: '刷新' })).toBeInTheDocument()

    await user.click(within(conflictDialog).getByRole('button', { name: '刷新' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
  })

  // pending 期间 Dialog 的关闭门禁不变：Escape 与遮罩都不关闭，也不弹确认。
  it('keeps the pending close gate without asking for confirmation', async () => {
    const user = userEvent.setup()
    let resolveCreate: (value: McpServerDTO) => void = () => {}
    vi.mocked(mcpServerService.createServer).mockReturnValue(
      new Promise<McpServerDTO>((resolve) => {
        resolveCreate = resolve
      }),
    )
    renderPage()

    const create = await openDialog(user, 'create')
    await user.type(within(create).getByPlaceholderText('例如 filesystem'), 'pending_srv')
    await user.type(within(create).getByPlaceholderText('https://example.com/mcp'), SAVED_URL)
    await user.click(within(create).getByRole('button', { name: '创建' }))

    await user.keyboard('{Escape}')
    fireEvent.mouseDown(backdropOf(create))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '新建 MCP 服务' })).toBeInTheDocument()

    await act(async () => {
      resolveCreate(detail({ name: 'pending_srv' }))
    })
    await expectDialogClosed()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })
})
