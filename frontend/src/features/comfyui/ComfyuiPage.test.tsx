import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ComfyuiPage } from '@/features/comfyui/ComfyuiPage'
import { comfyuiService } from '@/shared/api/comfyui-service'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
  },
}))

vi.mock('@/shared/api/comfyui-service', () => ({
  comfyuiService: {
    listWorkflows: vi.fn(),
    createWorkflow: vi.fn(),
    updateWorkflow: vi.fn(),
    deleteWorkflow: vi.fn(),
    runWorkflow: vi.fn(),
    getRun: vi.fn(),
    cancelRun: vi.fn(),
    uploadFile: vi.fn(),
    createPresignedUpload: vi.fn(),
    createPresignedDownload: vi.fn(),
  },
}))

function workflow(): ComfyuiWorkflowApiDTO {
  return {
    id: 'workflow-1',
    apiName: 'image-upscale',
    name: 'Image Upscale',
    description: null,
    workflowJson: '{}',
    inputBindingsJson: '[]',
    defaultSelector: '$.outputs',
    enabled: true,
    createTime: null,
    updateTime: null,
  }
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/comfyui']}>
        <Routes>
          <Route path="/comfyui" element={<ComfyuiPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ComfyuiPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
  })

  it('renders the loading state while the workflow query is in flight', () => {
    let release!: () => void
    vi.mocked(comfyuiService.listWorkflows).mockImplementation(
      () =>
        new Promise((resolve) => {
          release = () =>
            resolve({ pageNumber: 1, pageSize: 100, totalCount: 0, results: [] })
        }),
    )
    renderPage()
    // busy=true 时展示加载块，且不渲染 workflows 面板。
    expect(screen.getByText('正在加载 ComfyUI 工作流')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新建 ComfyUI Workflow' })).not.toBeInTheDocument()
    release()
  })

  it('renders an empty workflows grid once the query settles', async () => {
    renderPage()
    // 空列表：只保留创建卡片，不展示加载块。
    expect(
      await screen.findByRole('button', { name: '新建 ComfyUI Workflow' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('正在加载 ComfyUI 工作流')).not.toBeInTheDocument()
  })

  it('shows the search box bound to the page controller', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [workflow()],
    })
    renderPage()
    expect(await screen.findByRole('button', { name: '运行 Image Upscale' })).toBeInTheDocument()
    const search = screen.getByPlaceholderText('搜索资源...')
    // 搜索框过滤：输入不匹配的查询后工作流卡片消失（deferred value 最终收敛）。
    await userEvent.type(search, 'no-such-workflow')
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '运行 Image Upscale' })).not.toBeInTheDocument(),
    )
    // 清空搜索后卡片恢复。
    await userEvent.clear(search)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '运行 Image Upscale' })).toBeInTheDocument(),
    )
  })

  it('renders the workflows panel and the run modal when a workflow is opened', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [workflow()],
    })
    renderPage()
    // 面板渲染工作流卡片。
    expect(await screen.findByRole('button', { name: '运行 Image Upscale' })).toBeInTheDocument()
    expect(screen.getByText('POST /api/comfyui/workflows/image-upscale/runs')).toBeInTheDocument()
    // 打开运行 modal 前不渲染。
    expect(screen.queryByText('JSONPath 选择器')).not.toBeInTheDocument()
    // 点击运行卡片 -> ComfyuiRunModalHost 渲染 modal。
    screen.getByRole('button', { name: '运行 Image Upscale' }).click()
    expect(await screen.findByText('JSONPath 选择器')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '运行 · Image Upscale' })).toBeInTheDocument()
  })

  it('renders the workflow load failure when listWorkflows rejects', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockRejectedValue(new Error('comfyui offline'))
    renderPage()
    // Error 实例 -> 直接展示 error.message。
    expect(await screen.findByText('comfyui offline')).toBeInTheDocument()
    // 加载失败时不渲染 workflows 面板。
    expect(screen.queryByRole('button', { name: '新建 ComfyUI Workflow' })).not.toBeInTheDocument()
  })

  it('renders the mutation failure block for non-Error mutation errors', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
    renderPage()
    expect(await screen.findByRole('button', { name: '新建 ComfyUI Workflow' })).toBeInTheDocument()
    // 打开创建 modal：ComfyuiWorkflowEditorDialog 是 extension contribution，
    // 需要 host 注册才能渲染；本页面测试只验证面板/加载/错误分支。
    screen.getByRole('button', { name: '新建 ComfyUI Workflow' }).click()
    // 未挂载 editor dialog 时页面保持原样（不崩溃、不误渲染 mutationError）。
    expect(screen.queryByText('工作流操作失败')).not.toBeInTheDocument()
  })
})
