import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router-dom'
import { resolveSessionAgentId } from '@/features/ai/ai-console-page-helpers'
import type { AgentDefinitionDTO } from '@/shared/api/contracts'
import { useAiConsoleSessionMutations } from '@/features/ai/useAiConsoleSessionMutations'
import { useAiConsoleSessionQueries } from '@/features/ai/useAiConsoleSessionQueries'

export function useAiConsoleSessionController(agents: AgentDefinitionDTO[]) {
  const navigate = useNavigate()
  const [chatModalOpen, setChatModalOpen] = useState(false)
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [sessionTitle, setSessionTitle] = useState('')

  const { sessionsQuery, sessions } = useAiConsoleSessionQueries()
  const sessionMutations = useAiConsoleSessionMutations({
    selectedAgentId,
    sessionTitle,
    onSessionCreated: async (session) => {
      setChatModalOpen(false)
      setSessionTitle('')
      navigate(`/sessions/${encodeURIComponent(session.sessionId)}`)
    },
  })

  function openCreateSession(agentId?: string) {
    const resolvedAgentId = resolveSessionAgentId(agentId, selectedAgentId, agents)
    setSelectedAgentId(resolvedAgentId)
    setSessionTitle('')
    setChatModalOpen(true)
  }

  const submitCreateSession: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (selectedAgentId) {
      sessionMutations.createSession()
    }
  }

  return {
    sessionsQuery,
    sessions,
    sessionMutationError: sessionMutations.sessionMutationError,
    openCreateSession,
    createSessionModal: {
      open: chatModalOpen,
      agents,
      selectedAgentId,
      sessionTitle,
      pending: sessionMutations.createSessionPending,
      onClose: () => setChatModalOpen(false),
      onSelectAgent: setSelectedAgentId,
      onSessionTitleChange: setSessionTitle,
      onSubmit: submitCreateSession,
    },
  }
}
