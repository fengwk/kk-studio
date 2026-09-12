import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import { mergeChatList } from '@/features/ai/chat/chat-utils'
import { chatService } from '@/shared/api/chat-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'
import { useQuery, useQueryClient } from '@tanstack/react-query'

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
  const [mode, setMode] = useState<'create' | 'edit'>('create')
  const [editingChat, setEditingChat] = useState<ChatDTO | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState<ConfirmModalState | null>(null)
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

  const updateChatMutation = useInvalidateMutation({
    mutationFn: () => {
      if (!editingChat) {
        throw new Error('No chat being edited')
      }
      return chatService.updateChat(editingChat.id, {
        title: title.trim(),
        agentName: selectedAgentName,
        expectedVersion: editingChat.version,
      })
    },
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async () => {
      setModalOpen(false)
      setEditingChat(null)
      setTitle('')
      setFormError('')
      setNameError('')
    },
  })

  const deleteChatMutation = useInvalidateMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion: string }) =>
      chatService.deleteChat(id, expectedVersion),
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async () => {
      setDeleteConfirm(null)
    },
  })

  function openCreateChat(agentName?: string) {
    createChatMutation.reset()
    updateChatMutation.reset()
    setMode('create')
    setEditingChat(null)
    const nextAgentName = resolveChatAgentName(agentName, selectedAgentName, agents)
    setSelectedAgentName(nextAgentName)
    setTitle('')
    setFormError('')
    setNameError('')
    setModalOpen(true)
  }

  function openEditChat(chat: ChatDTO) {
    createChatMutation.reset()
    updateChatMutation.reset()
    setMode('edit')
    setEditingChat(chat)
    setSelectedAgentName(chat.agentName || resolveChatAgentName(undefined, '', agents))
    setTitle(chat.title ?? '')
    setFormError('')
    setNameError('')
    setModalOpen(true)
  }

  function openDeleteChat(chat: ChatDTO) {
    deleteChatMutation.reset()
    setDeleteConfirm({
      title: t('ai.chat.deleteTitle'),
      description: t('ai.chat.deleteDescription', { title: chat.title || chat.id }),
      confirmLabel: t('ai.catalog.action.confirmDelete'),
      tone: 'danger',
      onConfirm: () =>
        deleteChatMutation.mutate({
          id: chat.id,
          expectedVersion: chat.version,
        }),
    })
  }

  function handleTitleChange(next: string) {
    setTitle(next)
    if (nameError || formError) {
      setNameError('')
      setFormError('')
    }
    if (createChatMutation.error) createChatMutation.reset()
    if (updateChatMutation.error) updateChatMutation.reset()
  }

  function handleSelectAgent(agentName: string) {
    setSelectedAgentName(agentName)
    if (createChatMutation.error) createChatMutation.reset()
    if (updateChatMutation.error) updateChatMutation.reset()
  }

  function closeModal() {
    setModalOpen(false)
    setEditingChat(null)
    setTitle('')
    setFormError('')
    setNameError('')
    createChatMutation.reset()
    updateChatMutation.reset()
  }

  const submitChat: FormEventHandler<HTMLFormElement> = (event) => {
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
    if (mode === 'edit') {
      updateChatMutation.mutate(undefined)
    } else {
      createChatMutation.mutate(undefined)
    }
  }

  const activeMutation = mode === 'edit' ? updateChatMutation : createChatMutation
  const activeMutationError = activeMutation.error
    ? toUserFacingErrorMessage(activeMutation.error)
    : ''
  const effectiveFormError = formError || activeMutationError
  const anyMutationError =
    createChatMutation.error || updateChatMutation.error || deleteChatMutation.error

  return {
    chatsQuery,
    chats: chatsQuery.data ?? [],
    chatMutationError: modalOpen || deleteConfirm ? null : anyMutationError,
    openCreateChat,
    openEditChat,
    openDeleteChat,
    createChatModal: {
      open: modalOpen,
      mode,
      agents,
      selectedAgentName,
      title,
      pending: activeMutation.isPending,
      formError: effectiveFormError,
      nameError,
      onClose: closeModal,
      onSelectAgent: handleSelectAgent,
      onTitleChange: handleTitleChange,
      onSubmit: submitChat,
    },
    deleteConfirmModal: {
      modal: deleteConfirm,
      pending: deleteChatMutation.isPending,
      error: deleteChatMutation.error ? toUserFacingErrorMessage(deleteChatMutation.error) : null,
      onClose: () => {
        setDeleteConfirm(null)
        deleteChatMutation.reset()
      },
    },
  }
}
