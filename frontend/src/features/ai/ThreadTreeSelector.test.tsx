import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadTreeSelector } from '@/features/ai/ThreadTreeSelector'

describe('ThreadTreeSelector', () => {
  it('switches filters and invokes branching only for an enabled entry', async () => {
    const user = userEvent.setup()
    const onBranch = vi.fn()
    render(<ThreadTreeSelector entries={entries} onBranch={onBranch} pending={false} />)
    expect(screen.getByText('user prompt')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('会话树过滤'), 'assistant-only')
    expect(screen.queryByText('user prompt')).not.toBeInTheDocument()
    await user.click(screen.getByText('assistant reply'))
    expect(onBranch).toHaveBeenCalledWith(entries[2])
  })

  it('prevents branch clicks while a branch is being created', async () => {
    const user = userEvent.setup()
    const onBranch = vi.fn()
    render(<ThreadTreeSelector entries={entries} onBranch={onBranch} pending />)
    const button = screen.getByText('user prompt').closest('button')!
    expect(button).toBeDisabled()
    await user.click(button)
    expect(onBranch).not.toHaveBeenCalled()
  })
})

const entries = [
  { entryId: 'root', sessionId: 's', parentEntryId: null, entryType: 'agent_snapshot', payloadJson: '{}', createTime: null },
  { entryId: 'user', sessionId: 's', parentEntryId: 'root', entryType: 'message', payloadJson: '{"message":{"role":"USER","contents":[{"text":"user prompt"}]}}', createTime: null },
  { entryId: 'assistant', sessionId: 's', parentEntryId: 'user', entryType: 'message', payloadJson: '{"message":{"role":"ASSISTANT","contents":[{"text":"assistant reply"}]}}', createTime: null },
]
