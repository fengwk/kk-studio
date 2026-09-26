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
      affinityKey: 'prefix-key-1',
      breakpoints: ['SYSTEM', 'TOOLS'],
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
  })

  it('renders preview rails without DEBUG prefix, localized labels and tool/skill chips (zh-CN)', () => {
    render(<DebugViewHarness />)

    // 标题去掉 DEBUG 前缀，本地化为“下一次请求预览”
    expect(screen.getByText('下一次请求预览')).toBeInTheDocument()
    expect(screen.queryByText(/DEBUG ·/)).not.toBeInTheDocument()
    expect(screen.getByText('System prompt content with instructions')).toBeInTheDocument()

    // 工具标题与徽章
    expect(screen.getByText(/工具 1 已发送 · 1 已过滤/)).toBeInTheDocument()
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

    // 英文下标题无 DEBUG 前缀
    expect(screen.getByText('Next Request Preview')).toBeInTheDocument()
    expect(screen.queryByText(/DEBUG ·/)).not.toBeInTheDocument()
    expect(screen.getByText(/TOOLS 1 sent · 1 filtered/)).toBeInTheDocument()
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
    expect(inspector).toHaveAttribute('aria-label', 'INSPECTOR: Tool · read')
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
    expect(inspector).toHaveAttribute('aria-label', '检查器: 工具 · read')
    expect(screen.getByText('名称')).toBeInTheDocument()
    expect(screen.getByText('已发送 (SENT)')).toBeInTheDocument()
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
    expect(inspector).toHaveAttribute('aria-label', '检查器: 技能 · dev')
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

      const snapshotBtn = screen.getByRole('button', { name: '查看当前调用规范化请求快照' })
      expect(snapshotBtn).toBeInTheDocument()
      expect(snapshotBtn).toHaveTextContent('请求快照')

      await user.click(snapshotBtn)

      const inspector = screen.getByTestId('thread-debug-inspector')
      expect(inspector).toHaveAttribute('aria-label', '检查器: 请求快照')

      const pre = screen.getByTestId('frozen-request-json')
      expect(pre).toHaveTextContent('"model": "minimax"')
      expect(pre).toHaveTextContent('"content": "hi"')
    })

    it('does NOT render Request Snapshot button when frozenInvocation is null', () => {
      render(<DebugViewHarness debug={sampleDebug({ frozenInvocation: null })} />)

      expect(screen.queryByRole('button', { name: /查看当前调用规范化请求快照/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /请求快照/ })).not.toBeInTheDocument()
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
        screen.getByText('当前无活动的冻结调用请求。仅在活动调用回合中显示规范化请求 JSON。'),
      ).toBeInTheDocument()
    })
  })

  it('maps cache retention NONE to localized no cache text and handles empty tools/skills', () => {
    render(
      <DebugViewHarness
        debug={sampleDebug({
          environmentName: null,
          tools: [],
          skills: [],
          subagents: [],
          cacheControl: {
            retention: 'NONE',
            affinityKey: null,
            breakpoints: [],
          },
        })}
      />,
    )
    expect(screen.getByText('未选择环境')).toBeInTheDocument()
    expect(screen.getByText('暂无技能')).toBeInTheDocument()
    expect(screen.getByText(/无缓存 \(NONE\)/)).toBeInTheDocument()
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
      expect(copyBtn).toHaveTextContent('复制提示词')

      fireEvent.click(copyBtn)
      // 等待 writeText microtask resolve
      await act(async () => {
        await Promise.resolve()
      })

      expect(writeTextMock).toHaveBeenCalledWith('System prompt content with instructions')
      expect(copyBtn).toHaveTextContent('已复制')

      // 2秒后恢复
      act(() => {
        vi.advanceTimersByTime(2000)
      })
      expect(copyBtn).toHaveTextContent('复制提示词')

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
      expect(copyBtn).not.toHaveTextContent('已复制')
      expect(copyBtn).toBeDisabled()

      // resolve promise
      await act(async () => {
        resolvePromise()
        await deferredPromise
      })

      // 成功 resolve 之后才显示“已复制”
      expect(copyBtn).toHaveTextContent('已复制')
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
      expect(copyBtn).not.toHaveTextContent('已复制')
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

      expect(copyBtn).not.toHaveTextContent('已复制')
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
      expect(copyBtn).toHaveTextContent('已复制')

      // 立即进行第二次尝试，第二次失败
      fireEvent.click(copyBtn)
      await act(async () => {
        await Promise.resolve()
      })

      // 绝不永久显示已复制，而是立即转为错误提示
      expect(copyBtn).not.toHaveTextContent('已复制')
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

    it('recovers from error state to idle after 3 seconds timeout', async () => {
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

      // 快进 3 秒
      act(() => {
        vi.advanceTimersByTime(3000)
      })
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
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
              affinityKey: null,
              breakpoints: [],
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
  })
})
