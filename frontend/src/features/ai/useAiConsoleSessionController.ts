import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router-dom'
import { resolveSessionAgentName } from '@/features/ai/ai-console-page-helpers'
import type { AgentDefinitionDTO, AgentSessionDTO } from '@/shared/api/contracts'
import type { ConfirmModalState } from '@/features/ai/ai-console-types'
import { useAiConsoleSessionMutations } from '@/features/ai/useAiConsoleSessionMutations'
import { useAiConsoleSessionQueries } from '@/features/ai/useAiConsoleSessionQueries'

export function useAiConsoleSessionController(agents: AgentDefinitionDTO[]) {
  const navigate = useNavigate()
  const [chatModalOpen, setChatModalOpen] = useState(false)
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const [sessionEditId, setSessionEditId] = useState<string | null>(null)
  const [selectedAgentName, setSelectedAgentName] = useState('')
  const [sessionTitle, setSessionTitle] = useState('')
  const [sessionEditTitle, setSessionEditTitle] = useState('')

  const { sessionsQuery, sessions } = useAiConsoleSessionQueries()
  const sessionMutations = useAiConsoleSessionMutations({
    selectedAgentName,
    sessionTitle,
    onSessionCreated: async (session) => {
      setChatModalOpen(false)
      setSessionTitle('')
      navigate(`/sessions/${encodeURIComponent(session.sessionId)}`)
    },
    onSessionUpdated: () => {
      setSessionEditId(null)
      setSessionEditTitle('')
    },
    onSessionDeleted: () => setDeleteConfirm(null),
  })

  function openCreateSession(agentName?: string) {
    const resolvedAgentName = resolveSessionAgentName(agentName, selectedAgentName, agents)
    setSelectedAgentName(resolvedAgentName)
    setSessionTitle('')
    setChatModalOpen(true)
  }

  function openEditSession(session: AgentSessionDTO) {
    setSessionEditId(session.sessionId)
    setSessionEditTitle(session.title || '')
  }

  function deleteSession(session: AgentSessionDTO) {
    setDeleteConfirm({
      title: '删除 Chat',
      description: `将删除会话 ${session.title || session.sessionId}。`,
      confirmLabel: '确认删除',
      tone: 'danger',
      onConfirm: () => sessionMutations.deleteSession(session.sessionId),
    })
  }

  const submitCreateSession: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (selectedAgentName) {
      sessionMutations.createSession()
    }
  }

  const submitEditSession: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (sessionEditId) {
      sessionMutations.updateSession(sessionEditId, sessionEditTitle)
    }
  }

  return {
    sessionsQuery,
    sessions,
    sessionMutationError: sessionMutations.sessionMutationError,
    sessionDeletePending: sessionMutations.sessionDeletePending,
    openCreateSession,
    openEditSession,
    deleteSession,
    createSessionModal: {
      open: chatModalOpen,
      agents,
      selectedAgentName,
      sessionTitle,
      pending: sessionMutations.createSessionPending,
      onClose: () => setChatModalOpen(false),
      onSelectAgent: setSelectedAgentName,
      onSessionTitleChange: setSessionTitle,
      onSubmit: submitCreateSession,
    },
    editSessionModal: {
      open: Boolean(sessionEditId),
      title: sessionEditTitle,
      pending: sessionMutations.updateSessionPending,
      onClose: () => setSessionEditId(null),
      onTitleChange: setSessionEditTitle,
      onSubmit: submitEditSession,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: sessionMutations.sessionDeletePending,
      onClose: () => setDeleteConfirm(null),
    },
  }
}
