import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router-dom'
import { useInvalidateMutation } from '@/features/ai/useInvalidateMutation'
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

export function useChatListController(agents: AgentDefinitionDTO[]) {
  const navigate = useNavigate()
  const [modalOpen, setModalOpen] = useState(false)
  const [selectedAgentId, setSelectedAgentId] = useState('')
  const [title, setTitle] = useState('')

  const chatsQuery = useQuery({
    queryKey: queryKeys.chats.list,
    queryFn: () => chatService.listChats(),
  })

  const createChatMutation = useInvalidateMutation({
    mutationFn: () =>
      chatService.createChat({
        title: title.trim() || undefined,
        defaultAgentId: selectedAgentId || undefined,
      }),
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async (chat: ChatDTO) => {
      setModalOpen(false)
      setTitle('')
      navigate(`/chats/${encodeURIComponent(chat.id)}`)
    },
  })

  function openCreateChat(agentId?: string) {
    setSelectedAgentId(resolveChatDefaultAgentId(agentId, selectedAgentId, agents))
    setTitle('')
    setModalOpen(true)
  }

  const submitCreateChat: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    createChatMutation.mutate(undefined)
  }

  return {
    chatsQuery,
    chats: chatsQuery.data ?? [],
    chatMutationError: createChatMutation.error,
    openCreateChat,
    createChatModal: {
      open: modalOpen,
      agents,
      selectedAgentId,
      title,
      pending: createChatMutation.isPending,
      onClose: () => setModalOpen(false),
      onSelectAgent: setSelectedAgentId,
      onTitleChange: setTitle,
      onSubmit: submitCreateChat,
    },
  }
}
