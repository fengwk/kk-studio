import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SkillEnvironmentHint } from '@/features/thread/runtime/SkillEnvironmentHint'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { agentService } from '@/shared/api/agent-service'
import { setLocale } from '@/shared/i18n'
import type {
  AgentDefinitionDTO,
  EnvironmentDTO,
  SkillEnvironmentSyncDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listSkillPackageEnvironments: vi.fn(),
  },
}))

const NAS_ID = 'e1000000-0000-4000-8000-0000000000e1'
const BUILDER_ID = 'e1000000-0000-4000-8000-0000000000e2'
const CURRENT_SHA = 'a'.repeat(64)
const PREVIOUS_SHA = 'b'.repeat(64)

const environments: EnvironmentDTO[] = [
  environment(NAS_ID, 'NAS'),
  environment(BUILDER_ID, 'builder'),
]

const agent: AgentDefinitionDTO = {
  name: 'assistant',
  description: null,
  systemPrompt: 'paired agent',
  type: 'BUILTIN',
  execution: null,
  config: {
    tools: [],
    skills: [
      { packageName: 'dev-tools', name: 'dev' },
      { packageName: 'docs-tools', name: 'docs' },
      { packageName: 'ops', name: 'deploy' },
      { packageName: 'release-tools', name: 'migrate' },
    ],
    subagents: [],
  },
  version: '1',
  createTime: 1790812800,
  updateTime: 1790812800,
}

function environment(id: string, name: string): EnvironmentDTO {
  return {
    id,
    name,
    status: 'READY',
    ready: true,
    statusExpiresAt: null,
    lastSeenAt: 1790812800000,
    capabilities: [],
    userName: null,
    homeDirectory: null,
    version: '1',
    createdAt: 1790812800000,
    updatedAt: 1790812800000,
  }
}

function sync(overrides: Partial<SkillEnvironmentSyncDTO> = {}): SkillEnvironmentSyncDTO {
  return {
    environmentId: NAS_ID,
    installedSha256: CURRENT_SHA,
    localPath: '/home/dev/.kkstudio/skills/dev-tools',
    error: null,
    upToDate: true,
    ...overrides,
  }
}

/** NAS 上已安装当前版本的 dev-tools、安装失败的 ops、版本不一致的 docs；release-tools 从未上报。 */
function nasSummaries(): Record<string, SkillEnvironmentSyncDTO[]> {
  return {
    'dev-tools': [sync()],
    'docs-tools': [sync({ installedSha256: PREVIOUS_SHA, upToDate: false })],
    ops: [sync({ error: 'git fetch failed' })],
    'release-tools': [sync({ environmentId: BUILDER_ID })],
  }
}

function installSummaries(summaries: Record<string, SkillEnvironmentSyncDTO[]>) {
  vi.mocked(agentService.listSkillPackageEnvironments).mockImplementation(
    async (packageName: string) => summaries[packageName] ?? [],
  )
}

function renderHint(environmentName: string | null) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  const sockets = new FakeWebSocketHarness()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/api/events" socketFactory={sockets.factory}>
        <SkillEnvironmentHint
          agentName={agent.name}
          environmentName={environmentName}
          agents={[agent]}
          environments={environments}
        />
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
  return { view, sockets }
}

beforeEach(() => {
  setLocale('zh-CN')
  vi.clearAllMocks()
})

describe('SkillEnvironmentHint', () => {
  // 提示必须列出该环境下真正拿不到当前版本的技能与原因，已安装当前版本的技能不出现。
  it('lists the skills the selected environment cannot run with their reasons', async () => {
    installSummaries(nasSummaries())
    renderHint('NAS')

    const hint = await screen.findByTestId('thread-skill-hint')
    expect(within(hint).getByText('docs-tools / docs')).toBeInTheDocument()
    expect(within(hint).getByText('版本不一致')).toBeInTheDocument()
    expect(within(hint).getByText('ops / deploy')).toBeInTheDocument()
    expect(within(hint).getByText('安装失败')).toBeInTheDocument()
    expect(within(hint).getByText('release-tools / migrate')).toBeInTheDocument()
    expect(within(hint).getByText('未安装')).toBeInTheDocument()
    // 已安装当前版本的技能不进入提示。
    expect(within(hint).queryByText('dev-tools / dev')).not.toBeInTheDocument()
    // 每个 Package 只按名字读取一次安装摘要。
    expect(agentService.listSkillPackageEnvironments).toHaveBeenCalledTimes(4)
  })

  // 未选择环境时技能走 vfs 路径，不需要提示，也不应发出任何安装摘要请求。
  it('stays silent without an environment and never reads install summaries', async () => {
    installSummaries(nasSummaries())
    renderHint(null)

    await act(async () => {
      await Promise.resolve()
    })
    expect(screen.queryByTestId('thread-skill-hint')).not.toBeInTheDocument()
    expect(agentService.listSkillPackageEnvironments).not.toHaveBeenCalled()
  })

  // 安装摘要读取失败时不出提示：宁可暂时沉默，也不把未知状态说成未安装。
  it('never fabricates a reason when a package summary cannot be read', async () => {
    vi.mocked(agentService.listSkillPackageEnvironments).mockRejectedValue(
      new Error('skill package not found'),
    )
    renderHint('NAS')

    await waitFor(() => {
      expect(agentService.listSkillPackageEnvironments).toHaveBeenCalled()
    })
    expect(screen.queryByTestId('thread-skill-hint')).not.toBeInTheDocument()
  })

  // 安装完成后环境推送 changed：只回读安装摘要，提示随当前版本的安装事实消失。
  it('clears the hint after the environment reports the current install', async () => {
    installSummaries(nasSummaries())
    const { sockets } = renderHint('NAS')
    expect(await screen.findByTestId('thread-skill-hint')).toBeInTheDocument()

    installSummaries({
      'dev-tools': [sync()],
      'docs-tools': [sync()],
      ops: [sync()],
      'release-tools': [sync()],
    })
    const socket = sockets.openLatest()
    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'environments' },
        name: 'changed',
        data: {},
      })
    })

    await waitFor(() => {
      expect(screen.queryByTestId('thread-skill-hint')).not.toBeInTheDocument()
    })
    expect(agentService.listSkillPackageEnvironments).toHaveBeenCalledTimes(8)
  })

  // 切换到已安装全部技能的另一个环境：同一份 Agent 技能不再产生提示。
  it('clears the hint when another environment has every skill at the current version', async () => {
    installSummaries({
      'dev-tools': [sync()],
      'docs-tools': [sync({ environmentId: BUILDER_ID })],
      ops: [sync({ environmentId: BUILDER_ID })],
      'release-tools': [sync({ environmentId: BUILDER_ID })],
    })
    renderHint('builder')

    await waitFor(() => {
      expect(agentService.listSkillPackageEnvironments).toHaveBeenCalledTimes(4)
    })
    expect(screen.queryByTestId('thread-skill-hint')).not.toBeInTheDocument()
  })
})
