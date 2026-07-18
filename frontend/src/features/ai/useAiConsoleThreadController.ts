import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router-dom'
import { resolveThreadAgentId } from '@/features/ai/ai-console-page-helpers'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'
import { useAiConsoleThreadMutations } from '@/features/ai/useAiConsoleThreadMutations'
import { useAiConsoleThreadQueries } from '@/features/ai/useAiConsoleThreadQueries'

export function useAiConsoleThreadController(agents: AgentDefinitionDTO[]) {
  const navigate = useNavigate()
  const [chatModalOpen, setChatModalOpen] = useState(false)
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [title, setTitle] = useState('')

  const { threadsQuery, threads } = useAiConsoleThreadQueries()
  const threadMutations = useAiConsoleThreadMutations({
    selectedAgentId,
    title,
    onThreadCreated: async (thread) => {
      setChatModalOpen(false)
      setTitle('')
      navigate(`/threads/${encodeURIComponent(thread.threadId)}`)
    },
  })

  function openCreateThread(agentId?: string) {
    const resolvedAgentId = resolveThreadAgentId(agentId, selectedAgentId, agents)
    setSelectedAgentId(resolvedAgentId)
    setTitle('')
    setChatModalOpen(true)
  }

  const submitCreateThread: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (selectedAgentId) {
      threadMutations.createThread()
    }
  }

  return {
    threadsQuery,
    threads,
    threadMutationError: threadMutations.threadMutationError,
    openCreateThread,
    createThreadModal: {
      open: chatModalOpen,
      agents,
      selectedAgentId,
      title,
      pending: threadMutations.createThreadPending,
      onClose: () => setChatModalOpen(false),
      onSelectAgent: setSelectedAgentId,
      onTitleChange: setTitle,
      onSubmit: submitCreateThread,
    },
  }
}
