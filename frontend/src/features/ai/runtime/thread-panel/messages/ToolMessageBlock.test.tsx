import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'
import { ResourceBlobUrlContext } from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'

function message(overrides: Partial<ToolDialogueMessage> = {}): ToolDialogueMessage {
  return {
    id: 'tool-1',
    role: 'tool',
    subjectEntryId: null,
    createdAt: null,
    status: 'done',
    phase: 'result',
    text: '',
    toolCallId: 'call-1',
    toolName: 'read',
    rendererKey: 'read',
    arguments: '',
    attachments: [],
    ...overrides,
  }
}

describe('ToolMessageBlock', () => {
  it('does not render a copy action in the full-width tool card', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          arguments: '{"path":"/app"}',
          status: 'error',
          text: 'Environment tool read has no environment binding.',
        })}
      />,
    )

    expect(screen.queryByRole('button', { name: '复制工具预览' })).not.toBeInTheDocument()
    expect(container.querySelector('.thread-turn-tool > .thread-block-tool')).toBeInTheDocument()
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-toggle')).not.toBeInTheDocument()
    expect(container.querySelector(
      '.thread-tool-surface > .thread-tool-result-body',
    )).toBeInTheDocument()
  })

  it('uses color state instead of WORKING/DONE/FAILED labels', () => {
    render(<ToolMessageBlock message={message({ phase: 'call', toolName: '', status: 'streaming' })} />)

    expect(screen.getByText(/Tool/)).toBeInTheDocument()
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')
    expect(document.querySelector('.thread-block-tool')).toHaveAttribute('aria-busy', 'true')
    expect(screen.queryByText(/WORKING|DONE|FAILED/)).not.toBeInTheDocument()
    expect(screen.queryByText('（无参数）')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()
    expect(screen.queryByText('等待工具结果…')).not.toBeInTheDocument()
  })

  it('renders a result and a distinct error message', () => {
    const { container, rerender } = render(
      <ToolMessageBlock
        message={message({
          status: 'error',
          arguments: '{"path":"missing"}',
          text: 'read failed',
          errorMessage: 'file not found',
        })}
      />,
    )

    expect(container.firstElementChild).toHaveClass('tool-state-error')
    expect(screen.queryByText(/WORKING|DONE|FAILED/)).not.toBeInTheDocument()
    expect(screen.queryByText('{"path":"missing"}')).not.toBeInTheDocument()
    expect(screen.getByText('read failed')).toBeInTheDocument()
    expect(screen.getByText('file not found')).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({ status: 'error', text: 'same error', errorMessage: 'same error' })}
      />,
    )
    expect(screen.getAllByText('same error')).toHaveLength(1)
  })

  it('does not offer expansion when an empty result has no hidden content', () => {
    const { rerender } = render(
      <ToolMessageBlock message={message({ phase: 'result', status: 'streaming' })} />,
    )

    expect(screen.queryByText('等待工具结果…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()

    rerender(<ToolMessageBlock message={message({ phase: 'result', status: 'done' })} />)
    expect(screen.queryByText('无文本输出')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()
  })

  it('defaults missing statuses to the success color state', () => {
    const { rerender } = render(
      <ToolMessageBlock
        message={message({ phase: 'call', status: undefined, partial: 'streaming text' })}
      />,
    )
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-success')
    expect(screen.queryByText(/WORKING|DONE|FAILED/)).not.toBeInTheDocument()
    expect(screen.queryByText('streaming text')).not.toBeInTheDocument()

    rerender(<ToolMessageBlock message={message({ phase: 'result', status: undefined })} />)
    expect(document.querySelector('.thread-turn-tool')).toHaveClass('tool-state-success')
    expect(screen.queryByText('无文本输出')).not.toBeInTheDocument()
  })

  it('shows the failed placeholder for an empty error result', () => {
    const { container } = render(<ToolMessageBlock message={message({ status: 'error' })} />)
    expect(screen.getByText('工具执行失败。')).toBeInTheDocument()
    expect(container.querySelector('.thread-tool-toggle')).not.toBeInTheDocument()
  })

  it('renders compact write/edit previews and keeps call/result in one tool block', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'write',
          rendererKey: 'write',
          arguments: JSON.stringify({
            path: 'App.java',
            content: 'one\ntwo\nthree\nfour\nfive\nsix\nseven\neight',
          }),
        })}
      />,
    )
    expect(screen.getByText('App.java')).toBeInTheDocument()
    expect(screen.getByText('one')).toBeInTheDocument()
    expect(screen.queryByText('eight')).not.toBeInTheDocument()
    expect(screen.getByText('... (1 more line, 8 total)')).toBeInTheDocument()
    expect(screen.queryByText(/"content"/)).not.toBeInTheDocument()
    expect(document.querySelector('.thread-tool-preview-body')).toHaveClass('is-write')
    expect(document.querySelector('.thread-tool-preview-body')).not.toHaveClass('is-expanded')

    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(screen.getByText('eight')).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            old_string: 'alpha\nbeta',
            new_string: 'alpha\nBETA',
          }),
        })}
        result={message({
          phase: 'result',
          toolName: 'edit',
          rendererKey: 'edit',
          text: 'Edited App.java successfully.',
        })}
      />,
    )
    expect(screen.getByText('-beta')).toBeInTheDocument()
    expect(screen.getByText('+BETA')).toBeInTheDocument()
    expect(screen.getByText('Edited App.java successfully.')).toBeInTheDocument()
    expect(document.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(document.querySelector(
      '.thread-tool-surface > .thread-tool-result-body',
    )).toBeInTheDocument()
  })

  it('renders model-streamed write/edit arguments in a five-line rolling tail', () => {
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: null,
          toolName: 'write',
          rendererKey: 'write',
          arguments: JSON.stringify({
            path: 'App.java',
            content: 'one\ntwo\nthree\nfour\nfive\nsix\nseven\neight',
          }),
        })}
      />,
    )

    // 八行参数压成“提示 + 最新四行”，总窗口稳定为五行并持续跟随尾部。
    expect(screen.getByText('... (4 earlier lines)')).toBeInTheDocument()
    expect(screen.queryByText('one')).not.toBeInTheDocument()
    expect(screen.queryByText('four')).not.toBeInTheDocument()
    expect(screen.getByText('five')).toBeInTheDocument()
    expect(screen.getByText('eight')).toBeInTheDocument()
    expect(document.querySelector('.thread-tool-preview-body')).toHaveClass('is-streaming')
    expect(document.querySelector('.thread-tool-preview-body')).not.toHaveClass('is-unbounded')
  })

  it('shows the complete edit diff once streamed arguments become durable', () => {
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          subjectEntryId: 'assistant-1',
          toolName: 'edit',
          rendererKey: 'edit',
          arguments: JSON.stringify({
            path: 'App.java',
            old_string: 'one\ntwo\nthree\nfour\nfive\nsix',
            new_string: 'ONE\nTWO\nTHREE\nFOUR\nFIVE\nSIX',
          }),
        })}
      />,
    )

    // durable call 已完成参数生成；即使工具仍在审批/执行，也不再折叠完整 diff。
    expect(screen.getByText('-one')).toBeInTheDocument()
    expect(screen.getByText('-six')).toBeInTheDocument()
    expect(screen.getByText('+ONE')).toBeInTheDocument()
    expect(screen.getByText('+SIX')).toBeInTheDocument()
    expect(screen.queryByText(/more lines/)).not.toBeInTheDocument()
    expect(document.querySelector('.thread-tool-preview-body')).toHaveClass('is-unbounded')
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()
  })

  it('keeps the original path case and expands the preview on toggle', async () => {
    const user = userEvent.setup()
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'write',
          rendererKey: 'write',
          arguments: JSON.stringify({
            path: 'src/SortingAlgorithms.java',
            content: 'one\ntwo\nthree\nfour\nfive\nsix\nseven\neight',
          }),
        })}
      />,
    )

    expect(screen.getByText('src/SortingAlgorithms.java')).toBeInTheDocument()
    expect(screen.queryByText('SRC/SORTINGALGORITHMS.JAVA')).not.toBeInTheDocument()
    expect(screen.queryByText(/WORKING|DONE|FAILED/)).not.toBeInTheDocument()
    expect(screen.queryByText('eight')).not.toBeInTheDocument()
    expect(document.querySelector('.thread-tool-preview-body')).not.toHaveClass('is-expanded')

    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(document.querySelector('.thread-tool-preview-body')).toHaveClass('is-expanded')
    expect(screen.getByText('eight')).toBeInTheDocument()
  })

  it('uses a pi-style summary and hides quiet successful results until expanded', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          arguments: JSON.stringify({
            path: '/app/quicksort.py',
            workdir: '/srv/project',
            offset: 2,
            limit: 20,
          }),
        })}
        result={message({
          phase: 'result',
          text: 'line one\nline two',
        })}
      />,
    )

    const header = container.querySelector('.thread-tool-header') as HTMLElement
    const toggle = screen.getByRole('button', { name: '展开工具预览' })
    expect(header).toHaveAttribute(
      'title',
      'read /app/quicksort.py in /srv/project [offset=2 limit=20]',
    )
    expect(header.tagName).toBe('DIV')
    expect(header).toHaveClass('has-toggle')
    expect(toggle).toHaveAttribute('title', '展开工具预览')
    expect(container.querySelector(
      '.thread-tool-header > .thread-tool-summary + .thread-tool-toggle',
    )).toBeInTheDocument()
    expect(screen.queryByText('line one')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-result-body')).not.toBeInTheDocument()

    await user.click(header)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('line one')).not.toBeInTheDocument()

    await user.click(toggle)
    expect(container.querySelector('.thread-tool-output')).toHaveTextContent('line one')
    expect(container.querySelector('.thread-tool-output')).toHaveTextContent('line two')
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(container.querySelector('.thread-tool-input')).not.toBeInTheDocument()
    expect(container.querySelector(
      '.thread-tool-surface > .thread-tool-result-body',
    )).toBeInTheDocument()
  })

  it('shows only the last ten bash result lines while collapsed', async () => {
    const user = userEvent.setup()
    const output = Array.from({ length: 12 }, (_, index) => `output-${index + 1}`).join('\n')
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          arguments: '{"command":"npm test"}',
        })}
        result={message({
          phase: 'result',
          toolName: 'bash',
          rendererKey: 'bash',
          text: output,
        })}
      />,
    )

    expect(screen.queryByText('output-1')).not.toBeInTheDocument()
    expect(screen.queryByText('output-2')).not.toBeInTheDocument()
    expect(screen.getByText(/^\.\.\. \(2 earlier lines\)/)).toBeInTheDocument()
    expect(screen.getByText(/output-3/)).toBeInTheDocument()
    expect(screen.getByText(/output-12/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(screen.getByText(/output-1/)).toBeInTheDocument()
    expect(screen.getByText(/output-12/)).toBeInTheDocument()
  })

  it('does not render a toggle when a short bash result is already fully visible', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          arguments: '{"command":"pwd"}',
        })}
        result={message({
          phase: 'result',
          toolName: 'bash',
          rendererKey: 'bash',
          text: '/app\n',
        })}
      />,
    )

    expect(screen.getByText('/app')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-toggle')).not.toBeInTheDocument()
  })

  it('renders image, markdown-style linked file, and attachment fallbacks', async () => {
    const user = userEvent.setup()
    render(
      <ToolMessageBlock
        message={message({
          attachments: [
            { type: 'image', name: 'preview.png', mime: 'image/png', data: 'data:image/png;base64,aGVsbG8=' },
            {
              type: 'file',
              name: 'result.json',
              mime: 'application/json',
              data: 'file:///tmp/result.json',
              size: 2,
              sha256: 'a'.repeat(64),
              downloadHref:
                `/api/harness/resources/${'a'.repeat(64)}`
                + '?mediaType=application%2Fjson&size=2&name=result.json',
            },
            { type: 'audio', name: '', mime: 'audio/mpeg', data: '' },
          ],
        })}
      />,
    )

    expect(screen.getByRole('img', { name: 'preview.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
    // file: URI 仅以文本形式展示（不作为内联媒体元素）
    expect(screen.getByText('file:///tmp/result.json')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '[result.json]' })).toHaveAttribute(
      'href',
      expect.stringContaining('/api/harness/resources/'),
    )
    expect(screen.getByText('[audio] audio/mpeg')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /\[audio/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '预览 preview.png' }))
    const dialog = screen.getByRole('dialog', { name: '预览 preview.png' })
    expect(within(dialog).getByRole('img', { name: 'preview.png' })).toHaveAttribute(
      'src',
      'data:image/png;base64,aGVsbG8=',
    )
  })

  it('renders previewable data audio and video attachments as media controls', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          attachments: [
            {
              type: 'audio',
              name: 'clip.mp3',
              mime: 'audio/mpeg',
              data: 'data:audio/mpeg;base64,QUFBQQ==',
            },
            {
              type: 'video',
              name: 'clip.mp4',
              mime: 'video/mp4',
              data: 'data:video/mp4;base64,QUFBQQ==',
            },
          ],
        })}
      />,
    )

    expect(container.querySelector('audio')).toHaveAttribute(
      'src',
      'data:audio/mpeg;base64,QUFBQQ==',
    )
    expect(container.querySelector('video')).toHaveAttribute(
      'src',
      'data:video/mp4;base64,QUFBQQ==',
    )
  })

  it('renders a non-previewable resource preview as TEXT (never as an img src)', () => {
    render(
      <ToolMessageBlock
        message={message({
          attachments: [
            {
              type: 'file',
              name: 'manifest.json',
              mime: 'application/json',
              data: 'file:///tmp/manifest.json',
              // ResourceMessageContent.preview 是文本摘录，而非 URL。
              preview: '{"version":1,"tools":["web-search"]}',
            },
          ],
        })}
      />,
    )

    expect(screen.getByText('file:///tmp/manifest.json')).toBeInTheDocument()
    // preview 必须以纯文本形式渲染，绝不能作为 src=<preview text> 的图片。
    expect(screen.getByText('{"version":1,"tools":["web-search"]}')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('resolves a durable blob tool attachment and keeps its preview as text', async () => {
    const resolveBlobUrls = vi.fn(async () => ({
      original: 'https://s3.test/result',
      preview: null,
      mediaType: 'text/plain',
      sizeBytes: 12,
    }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ToolMessageBlock
          message={message({
            attachments: [
              {
                type: 'file',
                name: 'result.txt',
                mime: '',
                data: '',
                blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
                preview: 'durable excerpt',
              },
            ],
          })}
        />
      </ResourceBlobUrlContext.Provider>,
    )

    expect(resolveBlobUrls).toHaveBeenCalledWith('0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01')
    expect(await screen.findByRole('link', { name: /下载 result\.txt/ })).toHaveAttribute(
      'href',
      'https://s3.test/result',
    )
    expect(screen.getByText('durable excerpt')).toBeInTheDocument()
  })

  it('renders a durable blob attachment without inventing preview text', async () => {
    const resolveBlobUrls = vi.fn(async () => ({
      original: 'https://s3.test/result',
      preview: null,
      mediaType: 'text/plain',
      sizeBytes: 12,
    }))
    render(
      <ResourceBlobUrlContext.Provider value={resolveBlobUrls}>
        <ToolMessageBlock
          message={message({
            attachments: [
              {
                type: 'file',
                name: 'result.txt',
                mime: '',
                data: '',
                blobId: '0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01',
              },
            ],
          })}
        />
      </ResourceBlobUrlContext.Provider>,
    )

    expect(await screen.findByRole('link', { name: /下载 result\.txt/ })).toBeInTheDocument()
    expect(screen.queryByText('durable excerpt')).not.toBeInTheDocument()
  })

  it('disables approval buttons while an approval request is in flight', () => {
    const onDecideApproval = vi.fn()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
        approvalPending
      />,
    )

    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled()

    // 请求结束后再次启用。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
        approvalPending={false}
      />,
    )
    expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
  })

  it('renders the approval bar only when required + undecided + onDecideApproval present', async () => {
    const onDecideApproval = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )

    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
    // danger class 直接命中全局红色边框契约，避免拒绝动作与普通次要按钮混淆。
    expect(screen.getByRole('button', { name: '拒绝' })).toHaveClass('ghost-btn', 'danger')

    await user.click(screen.getByRole('button', { name: '允许' }))
    expect(onDecideApproval).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'tool-1' }), 'ALLOW')

    await user.click(screen.getByRole('button', { name: '拒绝' }))
    expect(onDecideApproval).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'tool-1' }), 'DENY')

    // 审批已决定时隐藏。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: 'ALLOWED', decisionId: 'd-1', reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()

    // 无需审批时隐藏。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: false, decision: null, decisionId: null, reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()

    // 没有决策处理器时隐藏。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          approval: { required: true, decision: null, decisionId: null, reason: null },
        })}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
  })

  it('shows the persisted ALLOWED/DENIED decision with optional reason instead of buttons', () => {
    const onDecideApproval = vi.fn()
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'ALLOWED', decisionId: 'd-1', reason: 'looks safe' },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()
    expect(screen.getByText(/已允许/)).toBeInTheDocument()
    expect(screen.getByText(/looks safe/)).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          approval: { required: true, decision: 'DENIED', decisionId: 'd-2', reason: null },
        })}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.getByText(/已拒绝/)).toBeInTheDocument()
  })

  it('renders a transient result block under the active call for partial/terminal/resource/error', () => {
    const { rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'streaming',
          invocationId: 'inv-1',
          partial: 'streaming answer',
        })}
      />,
    )
    expect(screen.getByText('streaming answer')).toBeInTheDocument()

    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'error',
          invocationId: 'inv-1',
          partialErrorText: 'boom',
        })}
      />,
    )
    expect(screen.getByText('boom')).toBeInTheDocument()

    // 终止态数据：资源内容会渲染在调用下，直到持久的 result Entry 到达（自动预览）；
    // 远程 http(s) 资源则只渲染为稳定 URI + 链接。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          invocationId: 'inv-1',
          partialAttachments: [
            {
              type: 'image',
              name: 'a.png',
              mime: 'image/png',
              data: 'data:image/png;base64,aW1n',
            },
          ],
        })}
      />,
    )
    expect(screen.getByRole('img', { name: 'a.png' })).toBeInTheDocument()
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'done',
          invocationId: 'inv-1',
          partialAttachments: [
            {
              type: 'image',
              name: 'remote.png',
              mime: 'image/png',
              data: 'https://example.com/remote.png',
            },
          ],
        })}
      />,
    )
    // 远程资源绝不内联：只展示稳定 URI 文本 + 显式链接。
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('https://example.com/remote.png')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '[remote.png]' })).toHaveAttribute(
      'rel',
      'noopener noreferrer',
    )

    // 持久 result 阶段已接管时，不显示瞬态块。
    rerender(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          status: 'streaming',
          invocationId: 'inv-1',
        })}
      />,
    )
    expect(screen.queryByText('streaming answer')).not.toBeInTheDocument()
  })

  it('does not expose raw task.status heartbeats when the task renderer is unavailable', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'task',
          rendererKey: 'task',
          status: 'streaming',
          arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
          partial:
            '{"kind":"task.status","subagentType":"explorer","state":"running_model"}',
        })}
      />,
    )

    expect(container.querySelector('.thread-tool-header')).toHaveAttribute(
      'title',
      'task explorer',
    )
    expect(screen.queryByRole('button', { name: '展开工具预览' })).not.toBeInTheDocument()
    expect(screen.queryByText(/task\.status/)).not.toBeInTheDocument()
    expect(document.querySelector('.thread-tool-result-body')).not.toBeInTheDocument()
  })

  it('overlays partial text over durable result text', () => {
    const { container } = render(
      <ToolMessageBlock
        message={message({
          phase: 'result',
          toolName: 'bash',
          rendererKey: 'bash',
          status: 'streaming',
          text: 'stable text',
          partial: 'streaming partial',
        })}
      />,
    )
    expect(container.textContent).toContain('streaming partial')
    expect(container.textContent).not.toContain('stable text')
  })

  it('shows complete generic input after expansion without making output a nested scroll target', async () => {
    const user = userEvent.setup()
    const { container, rerender } = render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          toolName: 'custom',
          rendererKey: 'custom',
          arguments: '{"line1":1,\n"line2":2,\n"line3":3,\n"line4":4,\n"line5":5,\n"line6":6}',
        })}
      />,
    )
    expect(container.querySelector('.thread-tool-input')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    const input = container.querySelector('.thread-tool-input')
    expect(input).toHaveTextContent('"line1":1')
    expect(input).toHaveTextContent('"line6":6')
    expect(input).not.toHaveClass('thread-tool-output')

    rerender(
      <ToolMessageBlock
        message={message({ phase: 'result', text: 'one\ntwo\nthree\nfour\nfive\nsix' })}
      />,
    )
    const output = container.querySelector('.thread-tool-output')
    expect(output).toHaveTextContent('one')
    expect(output).toHaveTextContent('six')
    expect(output).not.toHaveAttribute('tabindex')
  })

  it('delegates the body to a compile-time renderer without duplicating the default output', async () => {
    const user = userEvent.setup()
    render(
      <ToolMessageBlock
        message={message({ text: 'default output' })}
        renderer={({ message: rendered }) => (
          <div>custom renderer: {rendered.text}</div>
        )}
      />,
    )
    expect(screen.queryByText('custom renderer: default output')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(screen.getByText('custom renderer: default output')).toBeInTheDocument()
    expect(screen.queryByText('default output')).not.toBeInTheDocument()
  })

  it('delegates a call body to a compile-time renderer and suppresses the default transient block', () => {
    render(
      <ToolMessageBlock
        message={message({
          phase: 'call',
          arguments: '{"path":"demo"}',
          partial: 'default partial',
        })}
        renderer={({ message: rendered }) => (
          <div>custom call: {rendered.toolName}</div>
        )}
      />,
    )
    expect(screen.getByText('custom call: read')).toBeInTheDocument()
    expect(screen.queryByText('{"path":"demo"}')).not.toBeInTheDocument()
    expect(screen.queryByText('default partial')).not.toBeInTheDocument()
  })
})
