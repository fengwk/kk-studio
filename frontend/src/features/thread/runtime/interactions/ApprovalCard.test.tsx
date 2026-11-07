import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { ApprovalCard } from './ApprovalCard'

afterEach(() => vi.restoreAllMocks())
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>
}
const args = '{"command":"pwd\\nls -la","workdir":"/srv/完整目录","extra":{"flag":false}}'
const props = { threadId: 'source-child', invocationId: 'original-invocation', toolName: 'bash',
  argumentsJson: args, approval: { required: true, reason: 'Permission rules require approval' } }

it('默认权限原因本地化；完整 arguments/workdir 一次，额外真实原因完整保留', () => {
  const { container, rerender } = render(<ApprovalCard {...props} />, { wrapper })
  expect(screen.getByText('权限规则要求审批')).toBeInTheDocument()
  expect(container.querySelectorAll('pre')).toHaveLength(1)
  expect(container.querySelector('pre')).toHaveClass('ui-code', 'interaction-raw-pre')
  expect(container.querySelector('pre')?.textContent).toBe(args)
  expect(container.textContent?.split('/srv/完整目录')).toHaveLength(2)
  expect(screen.getByRole('button', { name: '拒绝' })).toHaveClass('btn-primary', 'danger')
  expect(screen.getByRole('button', { name: '允许' })).toHaveClass('btn-primary')
  const reason = '此目录含部署凭据，需人工核对后才可执行。'.repeat(30)
  rerender(<ApprovalCard {...props} approval={{ required: true, reason }} />)
  expect(screen.getByText(reason)).toBeInTheDocument()
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

it('409 围栏不可重试且保留参数和拒绝按钮的 danger 语义', async () => {
  vi.spyOn(harnessService, 'decideApproval').mockRejectedValue(new ApiError('stale version', 409))
  render(<ApprovalCard {...props} />, { wrapper })
  fireEvent.click(screen.getByRole('button', { name: '允许' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled())
  await waitFor(() => expect(document.querySelector('.is-non-recoverable')).toBeInTheDocument())
  expect(document.querySelector('pre')?.textContent).toBe(args)
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
  expect(document.querySelector('pre')?.textContent).toBe(args)
  fireEvent.click(screen.getByRole('button', { name: '允许' }))
  await waitFor(() => expect(document.querySelector('.interaction-error-banner')).toBeInTheDocument())
  expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
})
