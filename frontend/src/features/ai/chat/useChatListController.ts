import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { mergeChatList } from '@/features/ai/chat/chat-utils'
import { useI18n } from '@/shared/i18n'

function resolveChatAgentName(
  agentName: string | undefined,
  selectedAgentName: string,
  agents: AgentDefinitionDTO[],
): string {
  if (agentName && agents.some((agent) => agent.name === agentName)) {
    return agentName
  }
  if (selectedAgentName && agents.some((agent) => agent.name === selectedAgentName)) {
    return selectedAgentName
  }
  return agents[0]?.name ?? ''
}

export function useChatListController(
  agents: AgentDefinitionDTO[],
  enabled: boolean,
) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [modalOpen, setModalOpen] = useState(false)
  const [selectedAgentName, setSelectedAgentName] = useState('')
  const [title, setTitle] = useState('')
  const [formError, setFormError] = useState('')
  const [nameError, setNameError] = useState('')

  const chatsQuery = useQuery({
    queryKey: queryKeys.chats.list,
    queryFn: async () => {
      const incoming = await chatService.listChats()
      const current = queryClient.getQueryData<ChatDTO[]>(queryKeys.chats.list)
      return mergeChatList(current, incoming)
    },
    enabled,
  })

  const createChatMutation = useInvalidateMutation({
    mutationFn: () =>
      chatService.createChat({
        title: title.trim(),
        agentName: selectedAgentName,
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

  function openCreateChat(agentName?: string) {
    setSelectedAgentName(resolveChatAgentName(agentName, selectedAgentName, agents))
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

  function handleSelectAgent(agentName: string) {
    setSelectedAgentName(agentName)
  }

  const submitCreateChat: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!title.trim()) {
      setFormError(t('ai.chat.nameRequired'))
      setNameError(t('ai.chat.nameFieldRequired'))
      return
    }
    if (!selectedAgentName) {
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
      selectedAgentName,
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
