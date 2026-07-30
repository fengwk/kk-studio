import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import type { AgentDefinitionDTO, ChatDTO } from '@/shared/api/contracts'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useQuery } from '@tanstack/react-query'

export function resolveChatDefaultAgentId(
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
  return ''
}

export function useChatListController(
  agents: AgentDefinitionDTO[],
  enabled: boolean,
) {
  const navigate = useNavigate()
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
        defaultAgentId: selectedAgentId || undefined,
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
      setFormError('请填写 Chat 名称')
      setNameError('请填写名称')
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
