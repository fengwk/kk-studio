import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentManagementModal } from '@/features/ai/environment/EnvironmentManagementModal'
import { environmentService, DEFAULT_OPERATION_LIMIT } from '@/shared/api/environment-service'
import { ApiError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  EnvironmentCardDTO,
  EnvironmentInventoryDTO,
  EnvironmentOperationDTO,
  EnvironmentSkillDTO,
  EnvironmentSkillSourceDTO,
} from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  DEFAULT_OPERATION_LIMIT: 50,
  environmentService: {
    listSkillSources: vi.fn(),
    createSkillSource: vi.fn(),
    updateSkillSource: vi.fn(),
    deleteSkillSource: vi.fn(),
    getInventory: vi.fn(),
    listInventorySkills: vi.fn(),
    requestSkillSourceRefresh: vi.fn(),
    requestSkillSourceInstall: vi.fn(),
    requestSkillSourceUpdate: vi.fn(),
    listOperations: vi.fn(),
    cancelOperation: vi.fn(),
    listEnvironments: vi.fn(),
  },
}))

function testEnvironment(overrides: Partial<EnvironmentCardDTO> = {}): EnvironmentCardDTO {
  return {
    id: 'env-test-1',
    name: 'test-environment',
    rootPath: '/root/env',
    status: 'READY',
    ready: true,
    lastSeen: '2026-07-20T00:00:00.000Z',
    capabilities: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
}

function testSource(overrides: Partial<EnvironmentSkillSourceDTO> = {}): EnvironmentSkillSourceDTO {
  return {
    sourceId: 'src-1',
    environmentId: 'env-test-1',
    type: 'path',
    path: '/custom/skills',
    gitUrl: null,
    gitRef: null,
    scanPath: null,
    defaultSource: false,
    version: '1',
    status: 'READY',
    appliedVersion: '1',
    appliedRevision: 'rev-abcdef1234567890',
    diagnostics: [],
    lastErrorCode: null,
    lastErrorMessage: null,
    lastAppliedAt: '2026-07-20T01:00:00.000Z',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T01:00:00.000Z',
    ...overrides,
  }
}

function testInventory(overrides: Partial<EnvironmentInventoryDTO> = {}): EnvironmentInventoryDTO {
  return {
    environmentId: 'env-test-1',
    sourceSetVersion: '3',
    appliedSourceSetVersion: '3',
    capabilitiesVersion: 1,
    operatingSystem: 'linux',
    timeZone: 'Asia/Shanghai',
    note: 'Production box',
    rootPath: '/opt/studio',
    reportedAt: '2026-07-20T02:00:00.000Z',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T02:00:00.000Z',
    ...overrides,
  }
}

function testSkill(overrides: Partial<EnvironmentSkillDTO> = {}): EnvironmentSkillDTO {
  return {
    sourceId: 'src-1',
    name: 'dev-tools',
    sourceVersion: '1',
    description: 'Developer toolkit',
    baseDirectory: '/custom/skills/dev-tools',
    contentRevision: 'a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0',
    discoveredAt: '2026-07-20T01:30:00.000Z',
    ...overrides,
  }
}

function testOperation(overrides: Partial<EnvironmentOperationDTO> = {}): EnvironmentOperationDTO {
  return {
    id: 'op-1',
    environmentId: 'env-test-1',
    resourceType: 'SKILL_SOURCE',
    resourceId: 'src-1',
    operationType: 'SKILL_REFRESH',
    status: 'SUCCEEDED',
    resourceVersion: '1',
    parameterSummary: { timeoutMillis: 60000 },
    deadlineAt: '2026-07-20T01:01:00.000Z',
    startedAt: '2026-07-20T01:00:01.000Z',
    finishedAt: '2026-07-20T01:00:05.000Z',
    resultSummary: { discoveredSkills: 1 },
    failureCode: null,
    failureMessage: null,
    createdAt: '2026-07-20T01:00:00.000Z',
    updatedAt: '2026-07-20T01:00:05.000Z',
    ...overrides,
  }
}

function renderModal(
  env = testEnvironment(),
  onClose = vi.fn(),
  customQueryClient?: QueryClient,
) {
  const queryClient =
    customQueryClient ??
    new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentManagementModal environment={env} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { queryClient, view, onClose }
}

describe('EnvironmentManagementModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(environmentService.listSkillSources).mockResolvedValue([])
    vi.mocked(environmentService.getInventory).mockResolvedValue(testInventory())
    vi.mocked(environmentService.listInventorySkills).mockResolvedValue([])
    vi.mocked(environmentService.listOperations).mockResolvedValue([])
  })

  // 验证模态框渲染：标题、三大独立分区（Skill 来源、持久化 Inventory、操作记录）
  it('renders modal with three main sections and header', async () => {
    renderModal()
    expect(await screen.findByRole('dialog', { name: /管理环境 - test-environment/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Skill 来源' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: '持久化 Inventory' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: '异步操作记录' })).toBeInTheDocument()
  })

  // Sources Section: 列表与事实展示、诊断展示
  describe('Sources Section', () => {
    it('renders sources with facts, status, and diagnostics', async () => {
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({
          sourceId: 'src-path-1',
          type: 'path',
          path: '/host/path/skills',
          status: 'READY',
          version: '2',
          appliedVersion: '2',
          appliedRevision: 'rev-123456789012',
          defaultSource: true,
        }),
        testSource({
          sourceId: 'src-git-fail',
          type: 'git',
          gitUrl: 'https://github.com/example/skills.git',
          gitRef: 'main',
          scanPath: 'sub-skills',
          status: 'FAILED',
          lastErrorCode: 'CLONE_TIMEOUT',
          lastErrorMessage: 'git clone timed out after 300000ms',
          diagnostics: [{ location: 'skills/bad.json', message: 'Syntax error in skill manifest' }],
          defaultSource: false,
        }),
      ])
      renderModal()

      expect(await screen.findByText('/host/path/skills')).toBeInTheDocument()
      expect(screen.getByText('PATH')).toBeInTheDocument()
      expect(screen.getByText('缺省')).toBeInTheDocument()
      expect(screen.getByText('READY')).toBeInTheDocument()
      expect(screen.getByText('rev-12345678')).toBeInTheDocument()

      expect(screen.getByText('https://github.com/example/skills.git')).toBeInTheDocument()
      expect(screen.getByText('main')).toBeInTheDocument()
      expect(screen.getByText('sub-skills')).toBeInTheDocument()
      expect(screen.getByText('FAILED')).toBeInTheDocument()
      expect(screen.getByText('Syntax error in skill manifest')).toBeInTheDocument()
    })

    // 创建 PATH 来源：严格只传 path 字段，校验必填，并在成功后失效 sources + inventory + both skills + list
    it('creates a PATH source with strict fields and invalidates expected query caches', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.createSkillSource).mockResolvedValue(
        testSource({ sourceId: 'src-new', type: 'path', path: '~/my-skills' }),
      )
      const { queryClient } = renderModal()
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const addBtn = await screen.findByRole('button', { name: '添加来源' })
      await user.click(addBtn)

      const dialog = await screen.findByRole('dialog', { name: '添加来源' })
      const pathInput = within(dialog).getByRole('textbox', { name: /宿主目录路径/ })

      // 空路径提交报错
      await user.click(within(dialog).getByRole('button', { name: '确认' }))
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('必须填写宿主目录路径')
      expect(environmentService.createSkillSource).not.toHaveBeenCalled()

      // 输入有效路径并提交
      await user.type(pathInput, '  ~/my-skills  ')
      await user.click(within(dialog).getByRole('button', { name: '确认' }))

      expect(environmentService.createSkillSource).toHaveBeenCalledWith('env-test-1', {
        type: 'path',
        path: '~/my-skills',
      })

      // 验证 create 成功后失效 sources + inventory header + both inventory skill keys (true/false)
      await waitFor(() => {
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.skillSources('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventory('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', true),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', false),
          exact: true,
        })
      })
      expect(invalidateSpy).not.toHaveBeenCalledWith({
        queryKey: queryKeys.environments.list,
        exact: true,
      })
    })

    // 创建 GIT 来源：严格只传 gitUrl, gitRef, scanPath 字段
    it('creates a GIT source with strict fields and validation', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.createSkillSource).mockResolvedValue(
        testSource({ sourceId: 'src-git', type: 'git', gitUrl: 'https://github.com/user/repo.git' }),
      )
      renderModal()

      const addBtn = await screen.findByRole('button', { name: '添加来源' })
      await user.click(addBtn)

      const dialog = await screen.findByRole('dialog', { name: '添加来源' })

      // 切换为 GIT 类型
      const gitRadio = within(dialog).getByRole('radio', { name: /GIT 仓库/ })
      await user.click(gitRadio)

      const gitUrlInput = within(dialog).getByRole('textbox', { name: /Git 仓库 URL/ })
      const gitRefInput = within(dialog).getByRole('textbox', { name: /Git Ref/ })
      const scanPathInput = within(dialog).getByRole('textbox', { name: /相对扫描路径/ })

      // 空 URL 提交报错
      await user.click(within(dialog).getByRole('button', { name: '确认' }))
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('必须填写 Git 仓库 URL')
      expect(environmentService.createSkillSource).not.toHaveBeenCalled()

      await user.type(gitUrlInput, 'https://github.com/user/repo.git')
      await user.type(gitRefInput, 'v1.0.0')
      await user.type(scanPathInput, 'packages/skills')
      await user.click(within(dialog).getByRole('button', { name: '确认' }))

      expect(environmentService.createSkillSource).toHaveBeenCalledWith('env-test-1', {
        type: 'git',
        gitUrl: 'https://github.com/user/repo.git',
        gitRef: 'v1.0.0',
        scanPath: 'packages/skills',
      })
    })

    // 编辑来源：携带 expectedVersion CAS，defaultSource 禁止切换为 GIT，并在成功后失效 sources + both inventory skill keys
    it('edits a source with expectedVersion CAS and locks defaultSource type to PATH', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({
          sourceId: 'src-default',
          type: 'path',
          path: '/default/skills',
          defaultSource: true,
          version: '4',
        }),
      ])
      vi.mocked(environmentService.updateSkillSource).mockResolvedValue(
        testSource({ sourceId: 'src-default', path: '/new/path', version: '5' }),
      )
      const { queryClient } = renderModal()
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const editBtn = await screen.findByRole('button', { name: '编辑来源 src-default' })
      await user.click(editBtn)

      const dialog = await screen.findByRole('dialog', { name: '编辑来源' })
      // defaultSource 类型单选框为禁用状态，且说明缺省来源不可变更为 GIT
      const gitRadio = within(dialog).getByRole('radio', { name: /GIT 仓库/ })
      expect(gitRadio).toBeDisabled()
      expect(within(dialog).getByText('缺省来源类型不可变更为 GIT')).toBeInTheDocument()

      const pathInput = within(dialog).getByRole('textbox', { name: /宿主目录路径/ })
      await user.clear(pathInput)
      await user.type(pathInput, '/new/path')
      await user.click(within(dialog).getByRole('button', { name: '确认' }))

      expect(environmentService.updateSkillSource).toHaveBeenCalledWith('env-test-1', 'src-default', {
        type: 'path',
        path: '/new/path',
        gitUrl: null,
        gitRef: null,
        scanPath: null,
        expectedVersion: '4',
      })

      // 验证 update 成功后失效 sources + both inventory skill keys
      await waitFor(() => {
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.skillSources('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', true),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', false),
          exact: true,
        })
      })
      expect(invalidateSpy).not.toHaveBeenCalledWith({
        queryKey: queryKeys.environments.list,
        exact: true,
      })
    })

    // 删除来源：二次确认，携带 expectedVersion，失效 sources + inventory header + both skills
    it('deletes a source with confirmation and invalidates expected caches', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({ sourceId: 'src-del-1', version: '2' }),
      ])
      vi.mocked(environmentService.deleteSkillSource).mockResolvedValue()
      const { queryClient } = renderModal()
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const deleteBtn = await screen.findByRole('button', { name: '删除来源 src-del-1' })
      await user.click(deleteBtn)

      const confirmModal = await screen.findByRole('alertdialog', { name: '删除来源' })
      const confirmBtn = within(confirmModal).getByRole('button', { name: '删除来源' })
      await user.click(confirmBtn)

      expect(environmentService.deleteSkillSource).toHaveBeenCalledWith('env-test-1', 'src-del-1', '2')

      // 验证 delete 成功后失效 sources + inventory header + both inventory skill keys
      await waitFor(() => {
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.skillSources('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventory('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', true),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', false),
          exact: true,
        })
      })
      expect(invalidateSpy).not.toHaveBeenCalledWith({
        queryKey: queryKeys.environments.list,
        exact: true,
      })
    })

    // 非 409 变更错误保留用户编辑草稿与输入值
    it('preserves user draft inputs in edit modal on non-409 mutation error', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({ sourceId: 'src-draft-test', type: 'path', path: '/initial/path', version: '1' }),
      ])
      vi.mocked(environmentService.updateSkillSource).mockRejectedValue(
        new Error('Directory does not exist on target daemon'),
      )
      renderModal()

      const editBtn = await screen.findByRole('button', { name: '编辑来源 src-draft-test' })
      await user.click(editBtn)

      const dialog = await screen.findByRole('dialog', { name: '编辑来源' })
      const pathInput = within(dialog).getByRole('textbox', { name: /宿主目录路径/ })
      await user.clear(pathInput)
      await user.type(pathInput, '/modified/uncommitted/path')
      await user.click(within(dialog).getByRole('button', { name: '确认' }))

      // 弹窗依然保留开启状态，用户修改的草稿内容没有被抹除
      expect(screen.getByRole('dialog', { name: '编辑来源' })).toBeInTheDocument()
      expect(pathInput).toHaveValue('/modified/uncommitted/path')
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(
        'Directory does not exist on target daemon',
      )
    })

    // ApiError 409 冲突呈现 ConflictPresenter 并支持刷新重置
    it('handles actual ApiError 409 conflict via ConflictPresenter and refreshes all caches on user refresh', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({ sourceId: 'src-conflict', version: '1' }),
      ])
      const conflictError = new ApiError(
        '版本冲突',
        409,
        'CONFLICT',
        { reason: 'version_conflict', detail: 'Source was modified concurrently by another user' },
      )
      vi.mocked(environmentService.updateSkillSource).mockRejectedValue(conflictError)
      const { queryClient } = renderModal()
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const editBtn = await screen.findByRole('button', { name: '编辑来源 src-conflict' })
      await user.click(editBtn)

      const dialog = await screen.findByRole('dialog', { name: '编辑来源' })
      const pathInput = within(dialog).getByRole('textbox', { name: /宿主目录路径/ })
      await user.type(pathInput, '-mod')
      await user.click(within(dialog).getByRole('button', { name: '确认' }))

      // 409 发生后，编辑弹窗关闭，呈现全局 ConflictPresenter alertdialog
      expect(screen.queryByRole('dialog', { name: '编辑来源' })).toBeNull()
      const conflictDialog = await screen.findByRole('alertdialog', { name: '持久状态已变化' })
      expect(within(conflictDialog).getByText(/version_conflict/)).toBeInTheDocument()
      expect(within(conflictDialog).getByText(/Source was modified concurrently/)).toBeInTheDocument()

      // 点击刷新触发全量失效
      const refreshBtn = within(conflictDialog).getByRole('button', { name: '刷新' })
      await user.click(refreshBtn)

      await waitFor(() => {
        expect(screen.queryByRole('alertdialog', { name: '持久状态已变化' })).toBeNull()
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.skillSources('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventory('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', true),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', false),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.operations('env-test-1', DEFAULT_OPERATION_LIMIT),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.list,
          exact: true,
        })
      })
    })
  })

  // Operation actions per source & Cache seeding & Invalidation
  describe('Operation Actions per Source', () => {
    // sourceAction onSuccess 将返回的 PENDING 操作预置到 query cache，保证即时 active
    it('seeds returned PENDING operation into query cache on action success', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({ sourceId: 'src-p1', type: 'path', path: '/opt/skills' }),
      ])
      const pendingOp = testOperation({
        id: 'op-seed-pending',
        status: 'PENDING',
        operationType: 'SKILL_REFRESH',
      })
      vi.mocked(environmentService.requestSkillSourceRefresh).mockResolvedValue(pendingOp)
      vi.mocked(environmentService.listOperations).mockResolvedValue([pendingOp])

      const { queryClient } = renderModal()
      const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

      const refreshBtn = await screen.findByRole('button', { name: 'Refresh src-p1' })
      await user.click(refreshBtn)

      const actionDialog = await screen.findByRole('dialog', { name: /Refresh Skill 来源/ })
      await user.click(within(actionDialog).getByRole('button', { name: '确认' }))

      // 验证通过 setQueryData 将返回的 PENDING 操作写入缓存
      await waitFor(() => {
        expect(setQueryDataSpy).toHaveBeenCalledWith(
          queryKeys.environments.operations('env-test-1', DEFAULT_OPERATION_LIMIT),
          expect.any(Function),
        )
      })

      // 验证操作列表中立即呈现 op-seed-pending
      expect(await screen.findByText('op-seed-pending')).toBeInTheDocument()
    })

    // 快速完成（fast-completion）测试：action 返回 PENDING，随后的 invalidation fetch 立即返回 SUCCEEDED
    it('handles fast-completion scenario where action seeds PENDING and immediate fetch returns terminal', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({ sourceId: 'src-fast-p1', type: 'path', path: '/opt/skills' }),
      ])
      const pendingOp = testOperation({
        id: 'op-fast-1',
        status: 'PENDING',
        operationType: 'SKILL_REFRESH',
      })
      const completedOp = testOperation({
        id: 'op-fast-1',
        status: 'SUCCEEDED',
        operationType: 'SKILL_REFRESH',
      })

      // Action 请求返回 PENDING
      vi.mocked(environmentService.requestSkillSourceRefresh).mockResolvedValue(pendingOp)
      // 但随后由 invalidation 触发的操作拉取已快速变为终态 SUCCEEDED
      vi.mocked(environmentService.listOperations).mockResolvedValue([completedOp])

      const { queryClient } = renderModal()
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      const refreshBtn = await screen.findByRole('button', { name: 'Refresh src-fast-p1' })
      await user.click(refreshBtn)

      const actionDialog = await screen.findByRole('dialog', { name: /Refresh Skill 来源/ })
      await user.click(within(actionDialog).getByRole('button', { name: '确认' }))

      // 验证在极速完成时依然检测到终态转换，触发全部关联 artifacts 失效与刷新
      await waitFor(() => {
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.skillSources('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventory('env-test-1'),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', true),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.inventorySkills('env-test-1', false),
          exact: true,
        })
        expect(invalidateSpy).toHaveBeenCalledWith({
          queryKey: queryKeys.environments.list,
          exact: true,
        })
      })

      // 验证操作记录展示该终态记录
      expect(await screen.findByText('op-fast-1')).toBeInTheDocument()
      expect(screen.getByText('SUCCEEDED')).toBeInTheDocument()
    })

    it('renders Install for GIT source without applied revision and defaults to 300000ms', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({
          sourceId: 'src-git-unapplied',
          type: 'git',
          gitUrl: 'https://github.com/test/repo.git',
          appliedVersion: null,
          appliedRevision: null,
        }),
      ])
      vi.mocked(environmentService.requestSkillSourceInstall).mockResolvedValue(testOperation())
      renderModal()

      expect(screen.queryByRole('button', { name: 'Update src-git-unapplied' })).toBeNull()
      const installBtn = await screen.findByRole('button', { name: 'Install src-git-unapplied' })
      await user.click(installBtn)

      const actionDialog = await screen.findByRole('dialog', { name: /Install Skill 来源/ })
      const timeoutInput = within(actionDialog).getByRole('textbox', { name: /超时时间/ })
      expect(timeoutInput).toHaveValue('300000')

      await user.click(within(actionDialog).getByRole('button', { name: '确认' }))
      expect(environmentService.requestSkillSourceInstall).toHaveBeenCalledWith('env-test-1', 'src-git-unapplied', {
        timeoutMillis: 300000,
      })
    })

    it('renders both Refresh and Update for GIT source with current applied revision', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({
          sourceId: 'src-git-applied',
          type: 'git',
          gitUrl: 'https://github.com/test/repo.git',
          version: '3',
          appliedVersion: '3',
          appliedRevision: 'commit-sha-applied',
        }),
      ])
      vi.mocked(environmentService.requestSkillSourceUpdate).mockResolvedValue(testOperation())
      renderModal()

      expect(await screen.findByRole('button', { name: 'Refresh src-git-applied' })).toBeInTheDocument()
      const updateBtn = screen.getByRole('button', { name: 'Update src-git-applied' })
      await user.click(updateBtn)

      const actionDialog = await screen.findByRole('dialog', { name: /Update Skill 来源/ })
      const timeoutInput = within(actionDialog).getByRole('textbox', { name: /超时时间/ })
      expect(timeoutInput).toHaveValue('300000')

      await user.click(within(actionDialog).getByRole('button', { name: '确认' }))
      expect(environmentService.requestSkillSourceUpdate).toHaveBeenCalledWith('env-test-1', 'src-git-applied', {
        timeoutMillis: 300000,
      })
    })

    it('renders Update but NOT Refresh for stale configured GIT source (appliedVersion !== version)', async () => {
      vi.mocked(environmentService.listSkillSources).mockResolvedValue([
        testSource({
          sourceId: 'src-git-stale',
          type: 'git',
          gitUrl: 'https://github.com/test/repo.git',
          version: '4',
          appliedVersion: '3',
          appliedRevision: 'commit-sha-old',
        }),
      ])
      renderModal()

      expect(await screen.findByRole('button', { name: 'Update src-git-stale' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Refresh src-git-stale' })).toBeNull()
      expect(screen.queryByRole('button', { name: 'Install src-git-stale' })).toBeNull()
    })
  })

  // Active-only polling 与终态转换刷新测试（Fake Timers）
  describe('Active-only Polling and Terminal State Invalidation', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('polls listOperations every 2000ms while operation is active, and invalidates artifacts when transitioned to terminal', async () => {
      // 初始：返回一个正在 RUNNING 的操作
      const runningOp = testOperation({
        id: 'op-poll-1',
        status: 'RUNNING',
      })
      vi.mocked(environmentService.listOperations).mockResolvedValueOnce([runningOp])

      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
      })
      const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

      renderModal(testEnvironment(), vi.fn(), queryClient)

      // 等待初次渲染与查询触发
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(environmentService.listOperations).toHaveBeenCalledTimes(1)

      // 第二次拉取（2s 后轮询）：状态仍然是 RUNNING
      vi.mocked(environmentService.listOperations).mockResolvedValueOnce([runningOp])
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      expect(environmentService.listOperations).toHaveBeenCalledTimes(2)
      // 仍然处于活跃态，未终结，因此不触发终态 invalidation
      expect(invalidateSpy).not.toHaveBeenCalledWith({
        queryKey: queryKeys.environments.skillSources('env-test-1'),
        exact: true,
      })

      // 第三次拉取（再过 2s 轮询）：操作转为终态 SUCCEEDED
      const succeededOp = testOperation({
        id: 'op-poll-1',
        status: 'SUCCEEDED',
      })
      vi.mocked(environmentService.listOperations).mockResolvedValueOnce([succeededOp])
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2000)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(100)
      })
      expect(environmentService.listOperations).toHaveBeenCalledTimes(3)

      // 终态转换：立即失效 sources + inventory header + both inventory skills + list
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.environments.skillSources('env-test-1'),
        exact: true,
      })
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.environments.inventory('env-test-1'),
        exact: true,
      })
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.environments.inventorySkills('env-test-1', true),
        exact: true,
      })
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.environments.inventorySkills('env-test-1', false),
        exact: true,
      })
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.environments.list,
        exact: true,
      })

      // 第四次：此时已全为终态（没有 active operation），轮询停止（refetchInterval 返回 false）
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5000)
      })
      // 次数不再增加
      expect(environmentService.listOperations).toHaveBeenCalledTimes(3)
    })
  })

  // Durable Inventory Section: 离线可用性与数据展示
  describe('Durable Inventory Section', () => {
    it('renders inventory metadata and persisted skills list while environment is offline', async () => {
      const offlineEnv = testEnvironment({
        ready: false,
        status: 'OFFLINE',
      })
      vi.mocked(environmentService.getInventory).mockResolvedValue(
        testInventory({
          sourceSetVersion: '12',
          appliedSourceSetVersion: '10',
          operatingSystem: 'linux',
          timeZone: 'UTC',
          note: 'Offline edge device',
          rootPath: '/opt/offline-daemon',
        }),
      )
      vi.mocked(environmentService.listInventorySkills).mockResolvedValue([
        testSkill({
          name: 'skill-alpha',
          sourceId: 'src-12345678',
          description: 'Alpha capability',
          baseDirectory: '/opt/offline-daemon/alpha',
          contentRevision: 'fedcba9876543210abcdef0123456789abcdef0123456789abcdef0123456789',
        }),
      ])
      renderModal(offlineEnv)

      // 验证在离线状态下仍然能展示持久化的元数据
      expect(await screen.findByText('12')).toBeInTheDocument()
      expect(screen.getByText('10')).toBeInTheDocument()
      expect(screen.getByText('linux')).toBeInTheDocument()
      expect(screen.getByText('UTC')).toBeInTheDocument()
      expect(screen.getByText('Offline edge device')).toBeInTheDocument()
      expect(screen.getByText('/opt/offline-daemon')).toBeInTheDocument()

      // 验证持久化 skills 列表行详情
      expect(screen.getByText('skill-alpha')).toBeInTheDocument()
      expect(screen.getByText('Alpha capability')).toBeInTheDocument()
      expect(screen.getByText('/opt/offline-daemon/alpha')).toBeInTheDocument()
    })
  })

  // Operations Section: 列表展示、参数/结果摘要展示、PENDING 取消
  describe('Operations Section', () => {
    it('renders operations with parameterSummary, resultSummary, and allows cancelling only PENDING operations', async () => {
      const user = userEvent.setup()
      vi.mocked(environmentService.listOperations).mockResolvedValue([
        testOperation({
          id: 'op-pending-1',
          status: 'PENDING',
          operationType: 'SKILL_INSTALL',
          parameterSummary: { timeoutMillis: 300000, type: 'git', defaultSource: false },
          resultSummary: null,
        }),
        testOperation({
          id: 'op-running-2',
          status: 'RUNNING',
          operationType: 'SKILL_UPDATE',
          parameterSummary: { timeoutMillis: 60000 },
          resultSummary: null,
        }),
        testOperation({
          id: 'op-succeeded-3',
          status: 'SUCCEEDED',
          operationType: 'SKILL_REFRESH',
          parameterSummary: { timeoutMillis: 60000 },
          resultSummary: { discoveredSkills: 3, durationMs: 1420 },
        }),
        testOperation({
          id: 'op-failed-4',
          status: 'FAILED',
          failureCode: 'DAEMON_DISCONNECTED',
          failureMessage: 'Connection dropped during sync',
        }),
        testOperation({
          id: 'op-mcp-5',
          resourceType: 'MCP_SERVER',
          resourceId: 'mcp-srv-1',
          operationType: 'MCP_SERVER_DISCOVER',
          status: 'SUCCEEDED',
          resourceVersion: '2',
          parameterSummary: { timeoutMillis: 60000 },
          resultSummary: { toolCount: 5 },
        }),
      ])
      vi.mocked(environmentService.cancelOperation).mockResolvedValue(
        testOperation({ id: 'op-pending-1', status: 'CANCELLED' }),
      )
      renderModal()

      expect(await screen.findByText('op-pending-1')).toBeInTheDocument()
      expect(screen.getByText('op-running-2')).toBeInTheDocument()
      expect(screen.getByText('op-succeeded-3')).toBeInTheDocument()
      expect(screen.getByText('op-failed-4')).toBeInTheDocument()
      expect(screen.getByText('op-mcp-5')).toBeInTheDocument()

      // 验证呈现服务端安全参数摘要 (parameterSummary) 与结果摘要 (resultSummary)，不含原始 arguments
      expect(screen.getByText(/"type": "git"/)).toBeInTheDocument()
      expect(screen.getByText(/"defaultSource": false/)).toBeInTheDocument()
      expect(screen.queryByText(/arguments/i)).toBeNull()
      expect(screen.getByText(/"discoveredSkills": 3/)).toBeInTheDocument()
      expect(screen.getByText(/"toolCount": 5/)).toBeInTheDocument()
      expect(screen.getByText(/Connection dropped during sync/)).toBeInTheDocument()

      // 验证泛化资源类型与版本标签
      expect(screen.getAllByText('SKILL_SOURCE').length).toBeGreaterThan(0)
      expect(screen.getByText('MCP_SERVER')).toBeInTheDocument()
      expect(screen.getByText('MCP_SERVER_DISCOVER')).toBeInTheDocument()
      expect(screen.getAllByText(/版本 v1/).length).toBeGreaterThan(0)
      expect(screen.getAllByText(/创建:/).length).toBeGreaterThan(0)

      // 仅 PENDING 状态允许取消
      const cancelBtn = screen.getByRole('button', { name: '取消操作 op-pending-1' })
      expect(cancelBtn).toBeInTheDocument()

      // RUNNING 与 SUCCEEDED 绝不呈现取消按钮
      expect(screen.queryByRole('button', { name: /op-running-2/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /op-succeeded-3/ })).toBeNull()
      expect(screen.queryByRole('button', { name: /op-mcp-5/ })).toBeNull()

      // 点击取消 PENDING 操作
      await user.click(cancelBtn)
      const confirmModal = await screen.findByRole('alertdialog', { name: '取消操作' })
      const confirmBtn = within(confirmModal).getByRole('button', { name: '取消操作' })
      await user.click(confirmBtn)

      expect(environmentService.cancelOperation).toHaveBeenCalledWith('env-test-1', 'op-pending-1')
    })
  })
})
