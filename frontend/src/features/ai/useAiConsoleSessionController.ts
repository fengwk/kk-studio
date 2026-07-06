import { useMemo, useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { resolveSessionAgentName } from '@/features/ai/ai-console-page-helpers'
import { toSessionTitleUpdate } from '@/features/ai/ai-console-utils'
import { agentService } from '@/shared/api/agent-service'
import type { AgentDefinitionDTO, AgentSessionDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'
import type { ConfirmModalState } from '@/features/ai/ai-console-types'

export function useAiConsoleSessionController(agents: AgentDefinitionDTO[]) {
  const navigate = useNavigate()
  const [chatModalOpen, setChatModalOpen] = useState(false)
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
  const [sessionEditId, setSessionEditId] = useState<string | null>(null)
  const [selectedAgentName, setSelectedAgentName] = useState('')
  const [sessionTitle, setSessionTitle] = useState('')
  const [sessionEditTitle, setSessionEditTitle] = useState('')

  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => agentService.listSessions(),
  })

  const sessions = useMemo(() => sessionsQuery.data?.results ?? [], [sessionsQuery.data?.results])

  const createSessionMutation = useInvalidateMutation({
    mutationFn: () => agentService.createSession({ agentName: selectedAgentName, title: sessionTitle.trim() || undefined }),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: async (session) => {
      setChatModalOpen(false)
      setSessionTitle('')
      navigate(`/agent/sessions/${session.sessionId}`)
    },
  })

  const updateSessionMutation = useInvalidateMutation({
    mutationFn: ({ sessionId, title }: { sessionId: string; title: string }) => agentService.updateSession(sessionId, toSessionTitleUpdate(title)),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: () => {
      setSessionEditId(null)
      setSessionEditTitle('')
    },
  })

  const deleteSessionMutation = useInvalidateMutation({
    mutationFn: (sessionId: string) => agentService.deleteSession(sessionId),
    invalidateQueryKeys: [queryKeys.sessions.list],
    onSuccess: () => setDeleteConfirm(null),
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
      onConfirm: () => deleteSessionMutation.mutate(session.sessionId),
    })
  }

  const submitCreateSession: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (selectedAgentName) {
      createSessionMutation.mutate(undefined)
    }
  }

  const submitEditSession: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (sessionEditId) {
      updateSessionMutation.mutate({ sessionId: sessionEditId, title: sessionEditTitle })
    }
  }

  return {
    sessionsQuery,
    sessions,
    sessionMutationError: createSessionMutation.error || updateSessionMutation.error || deleteSessionMutation.error,
    sessionDeletePending: deleteSessionMutation.isPending,
    openCreateSession,
    openEditSession,
    deleteSession,
    createSessionModal: {
      open: chatModalOpen,
      agents,
      selectedAgentName,
      sessionTitle,
      pending: createSessionMutation.isPending,
      onClose: () => setChatModalOpen(false),
      onSelectAgent: setSelectedAgentName,
      onSessionTitleChange: setSessionTitle,
      onSubmit: submitCreateSession,
    },
    editSessionModal: {
      open: Boolean(sessionEditId),
      title: sessionEditTitle,
      pending: updateSessionMutation.isPending,
      onClose: () => setSessionEditId(null),
      onTitleChange: setSessionEditTitle,
      onSubmit: submitEditSession,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: deleteSessionMutation.isPending,
      onClose: () => setDeleteConfirm(null),
    },
  }
}
