import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useQuery } from '@tanstack/react-query'
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
  environments: LiveEnvironmentDTO[],
  enabled: boolean,
) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const [modalOpen, setModalOpen] = useState(false)
  const [selectedAgentName, setSelectedAgentName] = useState('')
  const [selectedEnvironmentName, setSelectedEnvironmentName] = useState('')
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
        agentName: selectedAgentName,
        ...(selectedEnvironmentName ? { environmentName: selectedEnvironmentName } : {}),
      }),
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async (chat: ChatDTO) => {
      setModalOpen(false)
      setTitle('')
      setSelectedEnvironmentName('')
      setFormError('')
      setNameError('')
      navigate(`/chats/${encodeURIComponent(chat.id)}`)
    },
  })

  function openCreateChat(agentName?: string) {
    setSelectedAgentName(resolveChatAgentName(agentName, selectedAgentName, agents))
    setTitle('')
    setSelectedEnvironmentName('')
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

  function handleSelectEnvironment(environmentName: string) {
    setSelectedEnvironmentName(environmentName)
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
      environments,
      selectedAgentName,
      selectedEnvironmentName,
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
      onSelectEnvironment: handleSelectEnvironment,
      onTitleChange: handleTitleChange,
      onSubmit: submitCreateChat,
    },
  }
}
