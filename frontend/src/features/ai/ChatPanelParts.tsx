import { ArrowLeft, MessageSquare, Plus } from 'lucide-react'
import { Link } from 'react-router-dom'
import { ThreadTreeSelector } from '@/features/ai/ThreadTreeSelector'
import type { HarnessSessionEntryDTO, HarnessThreadDTO } from '@/shared/api/contracts'

export function ChatSidebar({
  threads,
  sessionEntries,
  activeThreadId,
  sessionId,
  mainThreadId,
  title,
  onBack,
  onBranch,
  branchPending,
}: {
  threads: HarnessThreadDTO[]
  sessionEntries: HarnessSessionEntryDTO[]
  activeThreadId: string
  sessionId: string
  mainThreadId?: string
  title: string
  onBack: () => void
  onBranch: (entry: HarnessSessionEntryDTO) => void
  branchPending: boolean
}) {
  return (
    <aside className="chat-sidebar">
      <div className="sidebar-header">
        <button className="sidebar-icon-btn" type="button" onClick={onBack} title="返回列表">
          <ArrowLeft aria-hidden="true" />
        </button>
        <h1>{title}</h1>
        <Link className="sidebar-icon-btn" to="/sessions" title="新建对话">
          <Plus aria-hidden="true" />
        </Link>
      </div>
      <div className="chat-list">
        {[...threads]
          .sort((left, right) => Number(right.threadId === mainThreadId) - Number(left.threadId === mainThreadId))
          .map((item) => {
          const label = item.threadId === activeThreadId ? title : item.threadId
          return (
            <Link
              key={item.threadId}
              className={`chat-item ${item.threadId === activeThreadId ? 'active' : ''}`}
              to={`/sessions/${encodeURIComponent(sessionId)}/threads/${encodeURIComponent(item.threadId)}`}
            >
              <MessageSquare aria-hidden="true" />
              <span>{label}</span>
            </Link>
          )
          })}
      </div>
      <ThreadTreeSelector entries={sessionEntries} onBranch={onBranch} pending={branchPending} />
    </aside>
  )
}
