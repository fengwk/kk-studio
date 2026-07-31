import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useQuery } from '@tanstack/react-query'
import { useI18n } from '@/shared/i18n'

function resolveChatDefaultAgentId(
  agentId: string | undefined,
  selectedAgentId: string,
  agents: AgentDefinitionDTO[],
): string {
  if (agentId && agents.some((agent) => String(agent.id) === agentId)) {
    return agentId
  }
  if (selectedAgentId && agents.some((agent) => String(agent.id) === selectedAgentId)) {
    return selectedAgentId
  }
  return agents[0] ? String(agents[0].id) : ''
}

export function useChatListController(
  agents: AgentDefinitionDTO[],
  enabled: boolean,
) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const [modalOpen, setModalOpen] = useState(false)
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [title, setTitle] = useState('')
  const [formError, setFormError] = useState('')
  const [nameError, setNameError] = useState('')

  const chatsQuery = useQuery({
    queryKey: queryKeys.chats.list,
    queryFn: () => chatService.listChats(),
    enabled,
  })

  const createChatMutation = useInvalidateMutation({
    mutationFn: () =>
      chatService.createChat({
        title: title.trim(),
        defaultAgentId: selectedAgentId,
      }),
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async (chat: ChatDTO) => {
      setModalOpen(false)
      setTitle('')
      setFormError('')
      setNameError('')
      navigate(`/chats/${encodeURIComponent(chat.id)}`)
    },
  })

  function openCreateChat(agentId?: string) {
    setSelectedAgentId(resolveChatDefaultAgentId(agentId, selectedAgentId, agents))
    setTitle('')
    setFormError('')
    setNameError('')
    setModalOpen(true)
  }

  function handleTitleChange(next: string) {
    setTitle(next)
    if (nameError || formError) {
      setNameError('')
      setFormError('')
    }
  }

  function handleSelectAgent(agentId: string) {
    setSelectedAgentId(agentId)
  }

  const submitCreateChat: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!title.trim()) {
      setFormError(t('ai.chat.nameRequired'))
      setNameError(t('ai.chat.nameFieldRequired'))
      return
    }
    if (!selectedAgentId) {
      setFormError(t('ai.chat.agentRequired'))
      return
    }
    setFormError('')
    setNameError('')
    createChatMutation.mutate(undefined)
  }

  return {
    chatsQuery,
    chats: chatsQuery.data ?? [],
    // Keep API mutation errors out of page banner while modal is open; modal owns validation UX.
    chatMutationError: modalOpen ? null : createChatMutation.error,
    openCreateChat,
    createChatModal: {
      open: modalOpen,
      agents,
      selectedAgentId,
      title,
      pending: createChatMutation.isPending,
      formError: formError || (createChatMutation.error ? String(createChatMutation.error.message || createChatMutation.error) : ''),
      nameError,
      onClose: () => {
        setModalOpen(false)
        setFormError('')
        setNameError('')
      },
      onSelectAgent: handleSelectAgent,
      onTitleChange: handleTitleChange,
      onSubmit: submitCreateChat,
    },
  }
}
