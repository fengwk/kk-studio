import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { setLocale } from '@/shared/i18n'
import { ApprovalCard } from './ApprovalCard'

afterEach(() => {
  vi.restoreAllMocks()
  setLocale('zh-CN')
})
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>
}
const args = '{"command":"pwd\\nls -la","workdir":"/srv/完整目录","timeout":3000}'
const props = { threadId: 'source-child', invocationId: 'original-invocation', toolName: 'bash',
  argumentsJson: args, approval: { required: true, reason: 'Permission rules require approval' } }

it('write 审批卡片显示真实覆盖 diff，且新文件允许 null SHA', () => {
  render(
    <ApprovalCard
      {...props}
      toolName="write"
      argumentsJson={'{"path":"/workspace/new.txt","content":"NEW CONTENT"}'}
      approval={{
        required: true,
        editReview: {
          path: '/canonical/new.txt',
          diff: '+1|NEW CONTENT',
          sha256: null,
        },
      }}
    />,
    { wrapper },
  )

  expect(screen.getByText('/canonical/new.txt')).toBeInTheDocument()
  expect(document.querySelector('.is-added')?.textContent).toBe('+1|NEW CONTENT')
  expect(document.querySelector('.thread-tool-preview-body')?.textContent).toBe('+1|NEW CONTENT')
})

it('write 覆盖审批卡片展示哈希保护的变更而非整文件输入', () => {
  render(
    <ApprovalCard
      {...props}
      toolName="write"
      argumentsJson={'{"path":"/workspace/a.txt","content":"whole replacement"}'}
      approval={{
        required: true,
        editReview: {
          path: '/canonical/a.txt',
          diff: '@@ -1 +1 @@\n-1|before\n+1|after',
          sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        },
      }}
    />,
    { wrapper },
  )

  expect(screen.getByText('/canonical/a.txt')).toBeInTheDocument()
  expect(document.querySelector('.is-removed')?.textContent).toBe('-1|before\n')
  expect(document.querySelector('.is-added')?.textContent).toBe('+1|after')
  expect(screen.queryByText('whole replacement')).not.toBeInTheDocument()
})

it('显示同文件后续审批数量并本地化单复数文案', () => {
  const { rerender } = render(<ApprovalCard {...props} queuedSameFileCount={2} />, { wrapper })
  expect(screen.getByText('处理后，此文件还有 2 个修改将依次请求审批')).toBeInTheDocument()
  setLocale('en-US')
  rerender(<ApprovalCard {...props} queuedSameFileCount={1} />)
  expect(screen.getByText('1 more change to this file will ask for approval after this one'))
    .toBeInTheDocument()
})

it('审批参数逐字段可读；command 保留换行且拒绝原因有双语标签', () => {
  const { rerender } = render(<ApprovalCard {...props} />, { wrapper })
  expect(screen.getByText('权限规则要求审批')).toBeInTheDocument()
  expect(screen.getByText('command')).toBeInTheDocument()
  expect(document.querySelector('pre.is-command')?.textContent).toBe('pwd\nls -la')
  expect(screen.getByText('/srv/完整目录')).toBeInTheDocument()
  expect(screen.getByText('timeout')).toBeInTheDocument()
  expect(screen.getByText('3000')).toBeInTheDocument()
  expect(screen.queryByText(args)).not.toBeInTheDocument()
  expect(screen.getByRole('textbox', { name: '拒绝原因（可选）' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '拒绝' })).toHaveClass('btn-primary', 'danger')
  expect(screen.getByRole('button', { name: '允许' })).toHaveClass('btn-primary')
  setLocale('en-US')
  rerender(<ApprovalCard {...props} />)
  expect(screen.getByRole('textbox', { name: 'Reason for denial (optional)' })).toBeInTheDocument()
})

it('拒绝在途禁用；失败精确复用 decisionId，写回原始来源身份', async () => {
  const decide = vi.spyOn(harnessService, 'decideApproval')
    .mockRejectedValueOnce(new Error('network unavailable')).mockResolvedValue({} as never)
  const done = vi.fn()
  render(<ApprovalCard {...props} onSuccess={done} />, { wrapper })
  fireEvent.click(screen.getByRole('button', { name: '拒绝' }))
  expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
  await screen.findByText('network unavailable')
  fireEvent.click(screen.getByRole('button', { name: '拒绝' }))
  await waitFor(() => expect(done).toHaveBeenCalledTimes(1))
  expect(decide.mock.calls[1]).toEqual(decide.mock.calls[0])
  expect(decide).toHaveBeenLastCalledWith('source-child', 'original-invocation', {
    decision: 'DENY', decisionId: expect.any(String), reason: null,
  })
})

it('输入拒绝原因后按 Enter 提交该原因；空白原因交由后端默认处理', async () => {
  const decide = vi.spyOn(harnessService, 'decideApproval').mockResolvedValue({} as never)
  const { rerender } = render(<ApprovalCard {...props} />, { wrapper })
  const input = screen.getByRole('textbox', { name: '拒绝原因（可选）' })
  fireEvent.change(input, { target: { value: '不应执行此命令' } })
  fireEvent.keyDown(input, { key: 'Enter' })
  await waitFor(() => expect(decide).toHaveBeenLastCalledWith('source-child', 'original-invocation', {
    decision: 'DENY', decisionId: expect.any(String), reason: '不应执行此命令',
  }))

  decide.mockClear()
  rerender(<ApprovalCard {...props} />)
  fireEvent.change(input, { target: { value: '   ' } })
  fireEvent.click(screen.getByRole('button', { name: '拒绝' }))
  await waitFor(() => expect(decide).toHaveBeenLastCalledWith('source-child', 'original-invocation', {
    decision: 'DENY', decisionId: expect.any(String), reason: null,
  }))
})

it('409 围栏不可重试且保留参数和拒绝按钮的 danger 语义', async () => {
  vi.spyOn(harnessService, 'decideApproval').mockRejectedValue(new ApiError('stale version', 409))
  render(<ApprovalCard {...props} />, { wrapper })
  fireEvent.click(screen.getByRole('button', { name: '允许' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled())
  await waitFor(() => expect(document.querySelector('.is-non-recoverable')).toBeInTheDocument())
  expect(document.querySelector('pre.is-command')?.textContent).toBe('pwd\nls -la')
})

it('普通 Error 文本中的 409 不构成结构化冲突', async () => {
  // 测试意图：只有 ApiError(status=409) 触发不可恢复围栏，响应文本不再参与判断。
  vi.spyOn(harnessService, 'decideApproval').mockRejectedValue(new Error('409 stale transport'))
  render(<ApprovalCard {...props} />, { wrapper })
  fireEvent.click(screen.getByRole('button', { name: '允许' }))
  await screen.findByText('409 stale transport')
  expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
  expect(document.querySelector('.is-non-recoverable')).not.toBeInTheDocument()
})

it.each(['ALLOWED', 'DENIED'])('已决 %s 没有写入口', (decision) => {
  render(<ApprovalCard {...props} approval={{ decision }} />, { wrapper })
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(document.querySelector('.is-completed')).toBeInTheDocument()
})

it('畸形审批 JSON 不隐藏完整调用参数，未知失败也能重试', async () => {
  vi.spyOn(harnessService, 'decideApproval').mockRejectedValue('transport error')
  render(<ApprovalCard {...props} approval="invalid" />, { wrapper })
  expect(document.querySelector('pre.is-command')?.textContent).toBe('pwd\nls -la')
  fireEvent.click(screen.getByRole('button', { name: '允许' }))
  await waitFor(() => expect(document.querySelector('.interaction-error-banner')).toBeInTheDocument())
  expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
})
