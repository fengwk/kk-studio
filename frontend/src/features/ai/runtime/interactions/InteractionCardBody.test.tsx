import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { InteractionCardBody } from './InteractionCardBody'
import type { EnvironmentWaitInteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { interactionService } from '@/shared/api/interaction-service'
import { harnessService } from '@/shared/api/harness-service'

it('renders environment waits read-only without approval or questionnaire actions', () => {
  const submit = vi.spyOn(interactionService, 'submitToolInput')
  const approve = vi.spyOn(harnessService, 'decideApproval')
  const item: EnvironmentWaitInteractionDTO = {
    type: 'ENVIRONMENT_WAIT', rootThreadId: 'root', environmentId: 'env',
    environmentName: 'archlinux', waitingCount: 2, createTime: 1780000000,
    owner: { type: 'CHAT', chatId: 'chat', chatTitle: 'Chat', issueId: null,
      issueTitle: null, agentName: null, rootThreadName: 'Root' },
    interactionId: null, status: null, threadId: null, sessionId: null,
    toolCallId: null, toolName: null, argumentsJson: null, approvalJson: null,
  }
  render(<InteractionCardBody item={item} />)
  // 环境等待只有聚合身份：展示等待的环境与调用数，不出现任何可操作入口。
  expect(screen.getByRole('status')).toHaveTextContent('2 个工具调用等待 archlinux 上线')
  expect(screen.queryAllByRole('button')).toHaveLength(0)
  expect(submit).not.toHaveBeenCalled()
  expect(approve).not.toHaveBeenCalled()
  vi.restoreAllMocks()
})
