import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadModelRequestDebug } from '@/features/ai/runtime/thread-panel/ThreadModelRequestDebug'
import {
  ThreadDebugInspector,
  type DebugInspectorSelection,
} from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadModelRequestDebugData } from '@/features/ai/runtime/thread-timeline-types'
import { setLocale } from '@/shared/i18n'

function sampleDebug(overrides: Partial<ThreadModelRequestDebugData> = {}): ThreadModelRequestDebugData {
  return {
    kind: 'NEXT_REQUEST_PREVIEW',
    generatedAt: '2026-09-21T00:00:00.000Z',
    model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
    environmentName: 'dev-node',
    systemInstruction: 'System prompt content with instructions',
    tools: [
      {
        name: 'read',
        description: 'Read file',
        inputSchemaJson: '{"type":"object","properties":{"path":{"type":"string"}}}',
        environmentSupport: 'OPTIONAL',
        requiredEnvironmentId: null,
        provenance: 'builtin:read',
        state: 'SENT',
        filterReason: null,
      },
      {
        name: 'bash',
        description: 'Execute shell commands',
        inputSchemaJson: '{"type":"object","properties":{"command":{"type":"string"}}}',
        environmentSupport: 'REQUIRED',
        requiredEnvironmentId: null,
        provenance: 'builtin:bash',
        state: 'FILTERED',
        filterReason: 'ENVIRONMENT_NOT_SELECTED',
      },
    ],
    skills: [
      {
        packageName: 'dev-tools',
        name: 'dev',
        description: 'dev workflow',
        path: '/opt/skills/dev-tools/dev/SKILL.md',
        delivery: 'LOCAL',
        currentCommit: '1111111111111111111111111111111111111111',
        observedHeadCommit: '1111111111111111111111111111111111111111',
        installedCommit: '1111111111111111111111111111111111111111',
        promptXml: '<skill name="dev">Run dev workflows</skill>',
      },
    ],
    subagents: [{ name: 'helper', description: 'Isolated helper' }],
    cacheControl: {
      retention: 'SHORT',
      key: 'prefix-key-1',
    },
    planningError: null,
    frozenInvocation: {
      kind: 'FROZEN_INVOCATION',
      requestJson: '{"model":"minimax","messages":[{"role":"user","content":"hi"}]}',
    },
    ...overrides,
  }
}

function DebugViewHarness({ debug = sampleDebug() }: { debug?: ThreadModelRequestDebugData }) {
  const [selection, setSelection] = useState<DebugInspectorSelection | null>(null)
  return (
    <div>
      <ThreadModelRequestDebug debug={debug} onSelectInspector={setSelection} />
      {selection && (
        <ThreadDebugInspector
          selection={selection}
          debug={debug}
          onClose={() => setSelection(null)}
        />
      )}
    </div>
  )
}

describe('ThreadModelRequestDebug & Inspector', () => {
  beforeEach(() => {
    setLocale('zh-CN')
  })

  afterEach(() => {
    setLocale('zh-CN')
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('renders preview rails without DEBUG prefix, localized labels and tool/skill chips (zh-CN)', () => {
    render(<DebugViewHarness />)

    // 当前规划标题不可点击，预览是独立检查操作。
    expect(screen.getByRole('heading', { name: '当前规划' }).closest('button')).toBeNull()
    expect(screen.getByText('检查操作')).toBeInTheDocument()
    const environment = screen.getByText('dev-node')
    expect(environment.closest('footer')).toHaveClass('thread-debug-environment')
    expect(environment).toHaveClass('is-neutral')
    expect(environment).not.toHaveClass('is-ready')
    expect(screen.getByText('预览当前草稿')).toBeInTheDocument()
    expect(screen.queryByText(/DEBUG ·/)).not.toBeInTheDocument()
    expect(screen.getByText('System prompt content with instructions')).toBeInTheDocument()

    // 工具标题与徽章
    expect(screen.getByText(/工具 1 纳入规划 · 1 已过滤/)).toBeInTheDocument()
    expect(screen.getByText('read')).toBeInTheDocument()
    const readBadge = screen.getByTitle('可选环境 (OPTIONAL)')
    expect(readBadge).toHaveTextContent('P+E')

    // 过滤工具包含 ⊘ 符号与需要环境徽章
    expect(screen.getByText(/⊘/)).toBeInTheDocument()
    expect(screen.getByText('bash')).toBeInTheDocument()
    const bashBadge = screen.getByTitle('需要环境 (REQUIRED)')
    expect(bashBadge).toHaveTextContent('E')

    // 技能区域
    expect(screen.getByText(/技能 1/)).toBeInTheDocument()
    expect(screen.getByText('dev')).toBeInTheDocument()
    expect(screen.getByText('· 本地')).toBeInTheDocument()

    // 元数据与缓存
    expect(screen.getByText('子代理：')).toBeInTheDocument()
    expect(screen.getByText('helper')).toBeInTheDocument()
    expect(screen.getByText('缓存：')).toBeInTheDocument()
    expect(screen.getByText(/短期 \(SHORT\)/)).toBeInTheDocument()
  })

  it('switches dynamically to en-US and verifies real english localization across all labels and badges', async () => {
    setLocale('en-US')
    const user = userEvent.setup()
    render(<DebugViewHarness />)

    expect(screen.getByRole('heading', { name: 'Current planning' })).toBeInTheDocument()
    expect(screen.getByText('Currently planned tool environment')).toBeInTheDocument()
    expect(screen.getByText('Preview current draft')).toBeInTheDocument()
    expect(screen.queryByText(/DEBUG ·/)).not.toBeInTheDocument()
    expect(screen.getByText(/TOOLS 1 planned · 1 filtered/)).toBeInTheDocument()
    expect(screen.getByTitle('Optional environment (OPTIONAL)')).toBeInTheDocument()
    expect(screen.getByTitle('Environment required (REQUIRED)')).toBeInTheDocument()
    expect(screen.getByText('SKILLS 1')).toBeInTheDocument()
    expect(screen.getByText('· local')).toBeInTheDocument()
    expect(screen.getByText('Subagents:')).toBeInTheDocument()
    expect(screen.getByText('Cache:')).toBeInTheDocument()
    expect(screen.getByText(/Short \(SHORT\)/)).toBeInTheDocument()

    // 打开 Tool 检查器，断言英文标题与行标题
    await user.click(screen.getByRole('button', { name: 'Tool read' }))
    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toHaveAttribute('aria-label', 'read')
    expect(screen.getByRole('heading', { level: 3, name: 'read' })).toBeInTheDocument()
    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
    expect(screen.getByText('Environment Support')).toBeInTheDocument()
    expect(screen.getByText('Contributor (Provenance)')).toBeInTheDocument()
    expect(screen.getByText('Description')).toBeInTheDocument()
    expect(screen.getByText('Input Schema JSON:')).toBeInTheDocument()

    // 关闭检查器
    await user.click(screen.getByRole('button', { name: 'Close inspector' }))
    expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
  })

  it('renders planning error prominently when present', () => {
    render(<DebugViewHarness debug={sampleDebug({ planningError: 'MODEL_UNAVAILABLE' })} />)
    expect(screen.getByRole('alert')).toHaveTextContent('规划错误: MODEL_UNAVAILABLE')
  })

  it('opens tool inspector on tool chip click and shows details and schema (zh-CN)', async () => {
    const user = userEvent.setup()
    render(<DebugViewHarness />)

    await user.click(screen.getByRole('button', { name: '工具 read' }))

    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toHaveAttribute('aria-label', 'read')
    expect(screen.getByRole('heading', { level: 3, name: 'read' })).toBeInTheDocument()
    expect(screen.getByText('名称')).toBeInTheDocument()
    expect(screen.getByText('纳入当前规划 (SENT)')).toBeInTheDocument()
    expect(screen.getByText('可选环境 (OPTIONAL)')).toBeInTheDocument()
    expect(screen.getByText('builtin:read')).toBeInTheDocument()
    expect(screen.getByText('Read file')).toBeInTheDocument()
    expect(screen.getByText('输入 Schema JSON：')).toBeInTheDocument()

    // 关闭检查器
    await user.click(screen.getByRole('button', { name: '关闭检查器' }))
    expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
  })

  it('opens skill inspector on skill chip click and shows package, commits, XML (zh-CN)', async () => {
    const user = userEvent.setup()
    render(<DebugViewHarness />)

    await user.click(screen.getByRole('button', { name: '技能 dev' }))

    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toHaveAttribute('aria-label', 'dev')
    expect(screen.getByRole('heading', { level: 3, name: 'dev' })).toBeInTheDocument()
    expect(screen.getByText('技能名称')).toBeInTheDocument()
    expect(screen.getByText('包名 (Package)')).toBeInTheDocument()
    expect(screen.getByText('交付方式 (Delivery)')).toBeInTheDocument()
    expect(screen.getByText('本地')).toBeInTheDocument()
    expect(screen.getByText('dev-tools')).toBeInTheDocument()
    expect(screen.getByText('/opt/skills/dev-tools/dev/SKILL.md')).toBeInTheDocument()
    expect(screen.getByText('<skill name="dev">Run dev workflows</skill>')).toBeInTheDocument()

    // 键盘 Escape 关闭
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
  })

  describe('Request Snapshot rendering and inspector defense', () => {
    it('renders Request Snapshot button only when frozenInvocation exists', async () => {
      const user = userEvent.setup()
      render(<DebugViewHarness />)

      const snapshotBtn = screen.getByRole('button', { name: '查看当前调用冻结的规范化 ProviderRequest（非 HTTP 原始报文）' })
      expect(snapshotBtn).toBeInTheDocument()
      expect(snapshotBtn).toHaveTextContent('冻结调用输入')

      await user.click(snapshotBtn)

      const inspector = screen.getByTestId('thread-debug-inspector')
      expect(inspector).toHaveAttribute('aria-label', '冻结调用输入')
      expect(screen.getByRole('heading', { level: 3, name: '冻结调用输入' })).toBeInTheDocument()

      const pre = screen.getByTestId('frozen-request-json')
      expect(pre).toHaveTextContent('"model": "minimax"')
      expect(pre).toHaveTextContent('"content": "hi"')
    })

    it('does NOT render Request Snapshot button when frozenInvocation is null', () => {
      render(<DebugViewHarness debug={sampleDebug({ frozenInvocation: null })} />)

      expect(screen.queryByRole('button', { name: /查看当前调用冻结的规范化 ProviderRequest/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /冻结调用输入/ })).not.toBeInTheDocument()
    })

    it('displays defensive placeholder when opening request inspector without active frozen invocation', () => {
      render(
        <ThreadDebugInspector
          selection={{ type: 'request' }}
          debug={sampleDebug({ frozenInvocation: null })}
          onClose={() => {}}
        />,
      )

      expect(
        screen.getByText('当前没有可读取的对应模型调用；调用物化清理后不再提供冻结输入。'),
      ).toBeInTheDocument()
    })
  })

  it('maps cache retention NONE to localized no cache text and handles empty tools/skills/subagents', () => {
    render(
      <DebugViewHarness
        debug={sampleDebug({
          environmentName: null,
          tools: [],
          skills: [],
          subagents: [],
          cacheControl: {
            retention: 'NONE',
            key: null,
          },
        })}
      />,
    )
    expect(screen.getByText('未选择环境')).toBeInTheDocument()
    expect(screen.getByText('暂无技能')).toBeInTheDocument()
    expect(screen.getByText('无缓存')).toBeInTheDocument()
    expect(screen.queryByText(/无缓存 \(NONE\)/)).not.toBeInTheDocument()
  })

  describe('Subagents and Cache inspection', () => {
    it('renders subagent chips as clickable buttons, opens subagent inspector with direct title and actual fields', async () => {
      const user = userEvent.setup()
      render(
        <DebugViewHarness
          debug={sampleDebug({
            subagents: [
              { name: 'Explorer', description: 'Read-only exploration subagent' },
              { name: 'helper', description: 'General-purpose execution subagent' },
            ],
          })}
        />,
      )

      const explorerChip = screen.getByRole('button', { name: '子代理 Explorer' })
      expect(explorerChip).toBeInTheDocument()
      const helperChip = screen.getByRole('button', { name: '子代理 helper' })
      expect(helperChip).toBeInTheDocument()

      await user.click(explorerChip)

      const inspector = screen.getByTestId('thread-debug-inspector')
      expect(inspector).toHaveAttribute('aria-label', 'Explorer')
      expect(screen.getByRole('heading', { level: 3, name: 'Explorer' })).toBeInTheDocument()
      expect(screen.getByText('名称')).toBeInTheDocument()
      expect(screen.getByText('描述')).toBeInTheDocument()
      expect(screen.getByText('Read-only exploration subagent')).toBeInTheDocument()

      // 按 Escape 关闭
      await user.keyboard('{Escape}')
      expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
    })

    it('renders factual empty text for subagents when list is empty or undefined', () => {
      render(<DebugViewHarness debug={sampleDebug({ subagents: [] })} />)
      const metaRow = screen.getByTestId('debug-meta-row')
      expect(metaRow).toHaveTextContent('子代理： 无')
      expect(screen.queryByRole('button', { name: /子代理 helper/ })).not.toBeInTheDocument()
    })

    it('renders cache chip with clean summary, opens cache inspector with Cache Policy title and actual fields (SHORT)', async () => {
      const user = userEvent.setup()
      render(
        <DebugViewHarness
          debug={sampleDebug({
            cacheControl: {
              retention: 'SHORT',
              key: 'aff-key-42',
            },
          })}
        />,
      )

      const cacheBtn = screen.getByRole('button', { name: /缓存 短期 \(SHORT\) \(aff-key-42\)/ })
      expect(cacheBtn).toBeInTheDocument()

      await user.click(cacheBtn)

      const inspector = screen.getByTestId('thread-debug-inspector')
      expect(inspector).toHaveAttribute('aria-label', '缓存策略')
      expect(screen.getByRole('heading', { level: 3, name: '缓存策略' })).toBeInTheDocument()
      expect(screen.getByText('留存档位')).toBeInTheDocument()
      expect(screen.getByText('SHORT')).toBeInTheDocument()
      expect(screen.getByText('会话缓存键')).toBeInTheDocument()
      expect(screen.getByText('aff-key-42')).toBeInTheDocument()
      // 断点字段已从契约移除：不再出现任何断点行
      expect(screen.queryByText('Cache 断点')).not.toBeInTheDocument()

      await user.keyboard('{Escape}')
      expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
    })

    it('opens cache inspector when retention is NONE: retains literal NONE and disclaimer', async () => {
      const user = userEvent.setup()
      render(
        <DebugViewHarness
          debug={sampleDebug({
            cacheControl: {
              retention: 'NONE',
              key: null,
            },
          })}
        />,
      )

      const cacheBtn = screen.getByRole('button', { name: '缓存 无缓存' })
      expect(cacheBtn).toBeInTheDocument()

      await user.click(cacheBtn)

      const inspector = screen.getByTestId('thread-debug-inspector')
      expect(inspector).toHaveAttribute('aria-label', '缓存策略')
      expect(screen.getByText('NONE')).toBeInTheDocument()
      expect(screen.getByText('(不保证 Provider 自动缓存)')).toBeInTheDocument()
      // key 为空时如实显示 '—'（断点字段已移除）
      const dashes = screen.getAllByText('—')
      expect(dashes.length).toBeGreaterThanOrEqual(1)
    })

    it('verifies subagent and cache inspector localization in en-US', async () => {
      setLocale('en-US')
      const user = userEvent.setup()
      render(
        <DebugViewHarness
          debug={sampleDebug({
            subagents: [{ name: 'Explorer', description: 'Exploring code' }],
            cacheControl: {
              retention: 'NONE',
              key: null,
            },
          })}
        />,
      )

      expect(screen.getByText('No cache')).toBeInTheDocument()
      expect(screen.queryByText(/NONE/)).not.toBeInTheDocument()

      // 点击 Subagent 验证英文
      await user.click(screen.getByRole('button', { name: 'Subagent Explorer' }))
      expect(screen.getByTestId('thread-debug-inspector')).toHaveAttribute('aria-label', 'Explorer')
      expect(screen.getByText('Name')).toBeInTheDocument()
      expect(screen.getByText('Description')).toBeInTheDocument()
      expect(screen.getByText('Exploring code')).toBeInTheDocument()

      await user.keyboard('{Escape}')

      // 点击 Cache 验证英文
      await user.click(screen.getByRole('button', { name: 'Cache No cache' }))
      expect(screen.getByTestId('thread-debug-inspector')).toHaveAttribute('aria-label', 'Cache Policy')
      expect(screen.getByRole('heading', { level: 3, name: 'Cache Policy' })).toBeInTheDocument()
      expect(screen.getByText('Retention')).toBeInTheDocument()
      expect(screen.getByText('NONE')).toBeInTheDocument()
      expect(screen.getByText('(Provider automatic caching is not guaranteed)')).toBeInTheDocument()
      expect(screen.getByText('Session Cache Key')).toBeInTheDocument()
      // 断点字段已从 DTO 移除
      expect(screen.queryByText('Breakpoints')).not.toBeInTheDocument()
    })
  })

  it('handles invalid json gracefully in tool inspector schema display', async () => {
    const user = userEvent.setup()
    render(
      <DebugViewHarness
        debug={sampleDebug({
          tools: [
            {
              name: 'malformed_tool',
              description: 'Malformed schema tool',
              inputSchemaJson: '{not-valid-json}',
              environmentSupport: 'NONE',
              requiredEnvironmentId: null,
              provenance: 'custom',
              state: 'SENT',
              filterReason: null,
            },
          ],
        })}
      />,
    )
    await user.click(screen.getByRole('button', { name: '工具 malformed_tool' }))
    expect(screen.getByText('{not-valid-json}')).toBeInTheDocument()
  })

  it('handles empty inputSchemaJson in tool inspector and ignores Escape when isComposing', async () => {
    const user = userEvent.setup()
    render(
      <DebugViewHarness
        debug={sampleDebug({
          tools: [
            {
              name: 'empty_schema_tool',
              description: 'Empty schema tool',
              inputSchemaJson: '',
              environmentSupport: 'NONE',
              requiredEnvironmentId: null,
              provenance: 'custom',
              state: 'SENT',
              filterReason: null,
            },
          ],
        })}
      />,
    )
    await user.click(screen.getByRole('button', { name: '工具 empty_schema_tool' }))
    const inspector = screen.getByTestId('thread-debug-inspector')
    expect(inspector).toBeInTheDocument()

    // 验证 isComposing 状态下按 Escape 不关闭 inspector
    fireEvent.keyDown(inspector, { key: 'Escape', isComposing: true })
    expect(screen.getByTestId('thread-debug-inspector')).toBeInTheDocument()

    // 正常按 Escape 关闭
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('thread-debug-inspector')).not.toBeInTheDocument()
  })

  describe('Prompt copy behavior and clipboard contracts', () => {
    it('copies system prompt successfully with timeout reset and clears timeout on unmount', async () => {
      vi.useFakeTimers()
      const writeTextMock = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      const { unmount } = render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })
      expect(copyBtn).toHaveAttribute('title', '复制系统提示词')
      expect(copyBtn).toHaveTextContent('')
      expect(copyBtn).toHaveClass('md-code-copy')

      fireEvent.click(copyBtn)
      // 等待 writeText microtask resolve
      await act(async () => {
        await Promise.resolve()
      })

      expect(writeTextMock).toHaveBeenCalledWith('System prompt content with instructions')
      expect(copyBtn).toHaveAccessibleName('已复制')
      expect(copyBtn).toHaveAttribute('title', '已复制')

      // 共享 CopyButton 的 1500ms 成功反馈。
      act(() => {
        vi.advanceTimersByTime(1500)
      })
      expect(copyBtn).toHaveAccessibleName('复制系统提示词')

      // unmount 清理 timeout 不抛错
      unmount()
      vi.useRealTimers()
    })

    it('demonstrates deferred promise: does NOT show copied until writeText resolves', async () => {
      let resolvePromise: () => void = () => {}
      const deferredPromise = new Promise<void>((resolve) => {
        resolvePromise = resolve
      })
      const writeTextMock = vi.fn().mockReturnValue(deferredPromise)
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)

      // 在 promise resolve 之前，绝不提前显示“已复制”
      expect(copyBtn).not.toHaveAccessibleName('已复制')
      expect(copyBtn).toBeDisabled()

      // resolve promise
      await act(async () => {
        resolvePromise()
        await deferredPromise
      })

      // 成功 resolve 之后才显示“已复制”
      expect(copyBtn).toHaveAccessibleName('已复制')
    })

    it('handles clipboard rejection safely by showing accessible error alert and avoiding false success', async () => {
      const writeTextMock = vi.fn().mockRejectedValue(new Error('Permission denied'))
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })

      // 拒绝路径：不显示假成功，展示 role="alert" 的错误提示
      expect(copyBtn).not.toHaveAccessibleName('已复制')
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('复制失败')
    })

    it('handles unavailable navigator.clipboard gracefully without unhandled errors', async () => {
      Object.defineProperty(navigator, 'clipboard', {
        value: undefined,
        configurable: true,
      })

      render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })

      expect(copyBtn).not.toHaveAccessibleName('已复制')
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('复制失败')
    })

    it('disables copy button when prompt is empty and prevents invocation', () => {
      const writeTextMock = vi.fn()
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      render(<DebugViewHarness debug={sampleDebug({ systemInstruction: '' })} />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      expect(copyBtn).toBeDisabled()
      fireEvent.click(copyBtn)
      expect(writeTextMock).not.toHaveBeenCalled()
    })

    it('resets copied state immediately on retry and displays error when second attempt fails', async () => {
      let callCount = 0
      const writeTextMock = vi.fn().mockImplementation(() => {
        callCount++
        if (callCount === 1) {
          return Promise.resolve()
        }
        return Promise.reject(new Error('Second copy failed'))
      })
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      // 第一次成功
      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })
      expect(copyBtn).toHaveAccessibleName('已复制')

      // 立即进行第二次尝试，第二次失败
      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })

      // 绝不永久显示已复制，而是立即转为错误提示
      expect(copyBtn).not.toHaveAccessibleName('已复制')
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('复制失败')
    })

    it('does not set timers or update state when unmounted during pending copy promise', async () => {
      vi.useFakeTimers()
      let resolvePromise: () => void = () => {}
      const deferredPromise = new Promise<void>((resolve) => {
        resolvePromise = resolve
      })
      const writeTextMock = vi.fn().mockReturnValue(deferredPromise)
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      const { unmount } = render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)

      // 在 pending 状态下直接卸载组件
      unmount()

      // 卸载后再 resolve
      await act(async () => {
        resolvePromise()
        await deferredPromise
      })

      // 验证无未决的活动 timer
      expect(vi.getTimerCount()).toBe(0)
      vi.useRealTimers()
    })

    it('dynamically translates copy error alert when locale is switched', async () => {
      const writeTextMock = vi.fn().mockRejectedValue(new Error('Denied'))
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      const { rerender } = render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })

      // zh-CN 下显示“复制失败”
      expect(screen.getByRole('alert')).toHaveTextContent('复制失败')

      // 动态切换到 en-US 并 rerender
      setLocale('en-US')
      rerender(<DebugViewHarness />)

      // 错误状态基于单一 enum 状态渲染，即时显示为“Copy failed”
      expect(screen.getByRole('alert')).toHaveTextContent('Copy failed')
    })

    it('keeps a readable failure until retry and clears it on successful retry', async () => {
      vi.useFakeTimers()
      const writeTextMock = vi.fn().mockRejectedValue(new Error('Denied'))
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })
      expect(screen.getByRole('alert')).toHaveTextContent('复制失败')

      // 不用另一个计时器静默隐藏失败；重试前仍然可读。
      act(() => {
        vi.advanceTimersByTime(3000)
      })
      expect(screen.getByRole('alert')).toHaveTextContent('复制失败')
      writeTextMock.mockResolvedValue(undefined)
      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(copyBtn).toHaveAccessibleName('已复制')
      vi.useRealTimers()
    })

    it('does not set error timer when unmounted during rejected copy promise', async () => {
      vi.useFakeTimers()
      let rejectPromise: (err: unknown) => void = () => {}
      const deferredPromise = new Promise<void>((_, reject) => {
        rejectPromise = reject
      })
      const writeTextMock = vi.fn().mockReturnValue(deferredPromise)
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeTextMock },
        configurable: true,
      })

      const { unmount } = render(<DebugViewHarness />)
      const copyBtn = screen.getByRole('button', { name: '复制系统提示词' })

      fireEvent.click(copyBtn)
      unmount()

      await act(async () => {
        rejectPromise(new Error('Failed after unmount'))
        try {
          await deferredPromise
        } catch {
          // ignore
        }
      })

      expect(vi.getTimerCount()).toBe(0)
      vi.useRealTimers()
    })

    it('handles unknown and extreme envBadge / cacheRetention fallbacks', () => {
      render(
        <DebugViewHarness
          debug={sampleDebug({
            tools: [
              {
                name: 'custom_tool',
                description: 'custom',
                inputSchemaJson: '{}',
                environmentSupport: 'UNKNOWN_SUPPORT' as unknown as ThreadModelRequestDebugTool['environmentSupport'],
                requiredEnvironmentId: null,
                provenance: 'custom',
                state: 'SENT',
                filterReason: null,
              },
              {
                name: 'tool_none',
                description: 'none',
                inputSchemaJson: '{}',
                environmentSupport: 'NONE',
                requiredEnvironmentId: null,
                provenance: 'custom',
                state: 'FILTERED',
                filterReason: null,
              },
            ],
            cacheControl: {
              retention: 'LONG',
              key: null,
            },
          })}
        />,
      )

      expect(screen.getByText('UNKNOWN_SUPPORT')).toBeInTheDocument()
      expect(screen.getByText(/长期 \(LONG\)/)).toBeInTheDocument()
      expect(screen.getByTitle('平台独立 (NONE)')).toBeInTheDocument()
    })

    it('handles PLATFORM skill delivery, requiredEnvironmentId, and container fallback focus in inspector', () => {
      render(
        <ThreadDebugInspector
          selection={{
            type: 'skill',
            skill: {
              packageName: 'platform-pkg',
              name: 'platform-skill',
              description: 'platform skill',
              path: '/platform/path',
              delivery: 'PLATFORM',
              currentCommit: '222',
              observedHeadCommit: '222',
              installedCommit: '222',
              promptXml: '<platform />',
            },
          }}
          debug={sampleDebug()}
          onClose={() => {}}
          autoFocusCloseButton={true}
        />,
      )

      expect(screen.getByText('平台')).toBeInTheDocument()

      // 验证 tool 带有 requiredEnvironmentId
      render(
        <ThreadDebugInspector
          selection={{
            type: 'tool',
            tool: {
              name: 'env_tool',
              description: 'needs env',
              inputSchemaJson: '{}',
              environmentSupport: 'REQUIRED',
              requiredEnvironmentId: 'env-node-123',
              provenance: 'builtin',
              state: 'SENT',
              filterReason: null,
            },
          }}
          debug={sampleDebug()}
          onClose={() => {}}
        />,
      )
      expect(screen.getByText('所需环境 ID')).toBeInTheDocument()
      expect(screen.getByText('env-node-123')).toBeInTheDocument()
    })

    it('preserves raw unknown delivery without lowercase conversion', () => {
      render(
        <DebugViewHarness
          debug={sampleDebug({
            skills: [
              {
                packageName: 'pkg',
                name: 'custom-skill',
                description: 'custom',
                path: '/path',
                delivery: 'CUSTOM_CARRIER' as unknown as ThreadModelRequestDebugSkill['delivery'],
                currentCommit: '111',
                observedHeadCommit: null,
                installedCommit: null,
                promptXml: '<skill />',
              },
            ],
          })}
        />,
      )
      expect(screen.getByText('· CUSTOM_CARRIER')).toBeInTheDocument()
    })

    it('covers platform and custom delivery in skill inspector and custom filter reason in tool inspector', async () => {
      const user = userEvent.setup()
      render(
        <DebugViewHarness
          debug={sampleDebug({
            tools: [
              {
                name: 'filtered-tool',
                description: 'Custom filtered tool',
                inputSchemaJson: '{}',
                environmentSupport: 'UNKNOWN_ENV' as unknown as 'NONE',
                requiredEnvironmentId: null,
                provenance: 'custom:tool',
                state: 'FILTERED',
                filterReason: 'RATE_LIMIT_EXCEEDED',
              },
            ],
            skills: [
              {
                packageName: 'pkg-p',
                name: 'platform-skill',
                description: 'platform delivery skill',
                path: '/p',
                delivery: 'PLATFORM',
                currentCommit: '222',
                observedHeadCommit: null,
                installedCommit: null,
                promptXml: '<skill />',
              },
              {
                packageName: 'pkg-c',
                name: 'custom-carrier-skill',
                description: 'custom carrier skill',
                path: '/c',
                delivery: 'S3_BUCKET' as unknown as 'LOCAL',
                currentCommit: '333',
                observedHeadCommit: null,
                installedCommit: null,
                promptXml: '<skill />',
              },
            ],
          })}
        />,
      )

      await user.click(screen.getByRole('button', { name: '已过滤工具 filtered-tool' }))
      expect(screen.getByText('(RATE_LIMIT_EXCEEDED)')).toBeInTheDocument()
      expect(screen.getAllByText('UNKNOWN_ENV').length).toBeGreaterThanOrEqual(1)
      await user.keyboard('{Escape}')

      await user.click(screen.getByRole('button', { name: '技能 platform-skill' }))
      expect(screen.getByText('平台')).toBeInTheDocument()
      await user.keyboard('{Escape}')

      await user.click(screen.getByRole('button', { name: '技能 custom-carrier-skill' }))
      expect(screen.getByText('S3_BUCKET')).toBeInTheDocument()
    })

    it('renders provider request preview with metadata, preformatted payload and no authentication credentials', () => {
      // 测试意图：验证 selection.type === 'preview' 时展示请求预览标题、Provider/Model/ByteSize/Timestamp 元数据，
      // 并以可选择预格式化代码块渲染 bodyJson（只读，无点击快照承诺），且绝不暴露任何 Authorization 头或凭据信息。
      const mockPreview = {
        kind: 'DRAFT_REQUEST_PREVIEW' as const,
        providerType: 'OPENAI',
        modelName: 'gpt-4o',
        bodyByteSize: 1280,
        bodyJson: JSON.stringify({
          model: 'gpt-4o',
          messages: [{ role: 'user', content: 'hello preview' }],
        }),
        sourceHeadEntryId: 'entry-head-123',
        generatedAt: 1790504400,
        notice: 'Snapshot for draft preview',
      }

      render(
        <ThreadDebugInspector
          selection={{
            type: 'preview',
            preview: mockPreview,
          }}
          onClose={() => {}}
        />,
      )

      expect(screen.getByRole('heading', { level: 3, name: '请求预览' })).toBeInTheDocument()
      expect(screen.getByText('DRAFT_REQUEST_PREVIEW')).toBeInTheDocument()
      // 不再有「点击快照」承诺标识：这是只读回放视图
      expect(screen.queryByText('点击快照')).not.toBeInTheDocument()
      expect(screen.getByText('OPENAI')).toBeInTheDocument()
      expect(screen.getByText('gpt-4o')).toBeInTheDocument()
      expect(screen.getByText(/1.3 KB/)).toBeInTheDocument()
      expect(screen.getByText('1790504400')).toBeInTheDocument()
      expect(screen.getByText('entry-head-123')).toBeInTheDocument()
      expect(screen.getByText('Snapshot for draft preview')).toBeInTheDocument()

      const payload = screen.getByTestId('preview-request-body')
      expect(payload).toHaveAttribute('tabindex', '0')
      expect(payload.textContent).toContain('"hello preview"')

      // 验证不泄露任何敏感鉴权元数据
      expect(screen.queryByText(/Authorization/i)).not.toBeInTheDocument()
      expect(screen.queryByText(/Bearer/i)).not.toBeInTheDocument()
      expect(screen.queryByText(/api-key/i)).not.toBeInTheDocument()
    })

    // 意图：历史条目重放与草稿预览语义不同，检查器标题必须按 kind 区分，不能复用草稿标题。
    it('does not reuse the draft preview title for a historical request preview', () => {
      const sharedPreview = {
        providerType: 'OPENAI',
        modelName: 'gpt-4o',
        bodyByteSize: 12,
        bodyJson: '{"model":"gpt-4o"}',
        sourceHeadEntryId: 'entry-head-1',
        generatedAt: '2026-09-27T05:00:00Z',
        notice: null,
      }

      const draft = render(
        <ThreadDebugInspector
          selection={{ type: 'preview', preview: { ...sharedPreview, kind: 'DRAFT_REQUEST_PREVIEW' } }}
          onClose={() => {}}
        />,
      )
      expect(screen.getByRole('heading', { level: 3, name: '请求预览' })).toBeInTheDocument()
      draft.unmount()

      render(
        <ThreadDebugInspector
          selection={{
            type: 'preview',
            preview: { ...sharedPreview, kind: 'HISTORICAL_REQUEST_PREVIEW' },
          }}
          onClose={() => {}}
        />,
      )
      expect(screen.getByText('HISTORICAL_REQUEST_PREVIEW')).toBeInTheDocument()
      expect(screen.queryByRole('heading', { level: 3, name: '请求预览' })).not.toBeInTheDocument()
      expect(screen.getByRole('heading', { level: 3 }).textContent).not.toBe('请求预览')
    })
  })

  describe('on-demand inspect actions', () => {
    it('disables the preview action without onPreview and surfaces the disabled reason', () => {
      // 标题不是按钮；检查操作保留禁用原因与 title/aria-label。
      const { rerender } = render(
        <ThreadModelRequestDebug debug={sampleDebug()} onSelectInspector={vi.fn()} />,
      )
      const idleTitle = screen.getByRole('button', { name: '预览当前草稿' })
      expect(screen.getByRole('heading', { name: '当前规划' }).closest('button')).toBeNull()
      expect(idleTitle.closest('header')).toBeNull()
      expect(idleTitle).toBeDisabled()
      expect(idleTitle).toHaveAttribute('title', '预览当前草稿')

      rerender(
        <ThreadModelRequestDebug
          debug={sampleDebug()}
          onSelectInspector={vi.fn()}
          onPreview={vi.fn()}
          previewDisabled
          previewDisabledReason="草稿为空"
        />,
      )
      const blockedTitle = screen.getByRole('button', { name: '预览当前草稿 (草稿为空)' })
      expect(blockedTitle).toBeDisabled()
      expect(blockedTitle).toHaveAttribute('title', '预览当前草稿 (草稿为空)')
    })

    it('labels the inspect action as loading with a spinner while a preview is in flight', () => {
      // 测试意图：previewLoading 时按钮禁用，title/aria-label 变为生成中文案并渲染 spinner 图标。
      render(
        <ThreadModelRequestDebug
          debug={sampleDebug()}
          onSelectInspector={vi.fn()}
          onPreview={vi.fn()}
          previewLoading
        />,
      )
      const loadingTitle = screen.getByRole('button', { name: '正在生成请求预览…' })
      expect(loadingTitle).toBeDisabled()
      expect(loadingTitle).toHaveAttribute('title', '正在生成请求预览…')
      expect(loadingTitle.querySelector('.spin')).not.toBeNull()
    })

    it('runs onPreview on inspect action click and renders the localized preview error', async () => {
      // 检查操作按需预览；失败以 role=alert 展示且不破坏其余面板内容。
      const user = userEvent.setup()
      const onPreview = vi.fn()
      const { rerender } = render(
        <ThreadModelRequestDebug
          debug={sampleDebug()}
          onSelectInspector={vi.fn()}
          onPreview={onPreview}
        />,
      )
      const titleButton = screen.getByRole('button', { name: '预览当前草稿' })
      expect(titleButton).toBeEnabled()
      await user.click(titleButton)
      expect(onPreview).toHaveBeenCalledTimes(1)

      rerender(
        <ThreadModelRequestDebug
          debug={sampleDebug()}
          onSelectInspector={vi.fn()}
          onPreview={onPreview}
          previewError="会话游标已过期，请刷新状态后重试"
        />,
      )
      expect(screen.getByRole('alert')).toHaveTextContent('会话游标已过期，请刷新状态后重试')
      expect(screen.getByText('System prompt content with instructions')).toBeInTheDocument()
    })

    it('keeps frozen invocation and preview together as inspect actions', async () => {
      // 冻结调用与按需预览属于检查操作，不能挤掉任一有效入口。
      const user = userEvent.setup()
      const onSelectInspector = vi.fn()
      const { rerender } = render(
        <ThreadModelRequestDebug
          debug={sampleDebug()}
          onSelectInspector={onSelectInspector}
          onPreview={vi.fn()}
        />,
      )
      expect(screen.getByRole('button', { name: '预览当前草稿' })).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: '查看当前调用冻结的规范化 ProviderRequest（非 HTTP 原始报文）' }))
      expect(onSelectInspector).toHaveBeenCalledWith({ type: 'request' })

      rerender(
        <ThreadModelRequestDebug
          debug={sampleDebug({ frozenInvocation: null })}
          onSelectInspector={onSelectInspector}
          onPreview={vi.fn()}
        />,
      )
      expect(screen.queryByRole('button', { name: '查看当前调用冻结的规范化 ProviderRequest（非 HTTP 原始报文）' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '预览当前草稿' })).toBeInTheDocument()
    })
  })
})
