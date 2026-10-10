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
import type { ConfirmModalState } from '@/shared/ui/overlays/confirm-modal'
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
  const [yoloEnabled, setYoloEnabled] = useState(false)
  const [selectedEnvironmentName, setSelectedEnvironmentName] = useState<string | null>(null)
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
        yoloEnabled,
        environmentName: selectedEnvironmentName || null,
      }),
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async (chat: ChatDTO) => {
      setModalOpen(false)
      setTitle('')
      setYoloEnabled(false)
      setSelectedEnvironmentName(null)
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
        yoloEnabled,
        environmentName: selectedEnvironmentName ?? null,
        expectedVersion: editingChat.version,
      })
    },
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async () => {
      setModalOpen(false)
      setEditingChat(null)
      setTitle('')
      setYoloEnabled(false)
      setSelectedEnvironmentName(null)
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
    setYoloEnabled(false)
    setSelectedEnvironmentName(null)
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
    setYoloEnabled(chat.yoloEnabled)
    setSelectedEnvironmentName(chat.environmentName ?? null)
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

  function handleYoloChange(next: boolean) {
    setYoloEnabled(next)
    if (formError) setFormError('')
    if (createChatMutation.error) createChatMutation.reset()
    if (updateChatMutation.error) updateChatMutation.reset()
  }

  function handleSelectEnvironment(next: string | null) {
    setSelectedEnvironmentName(next)
    if (formError) setFormError('')
    if (createChatMutation.error) createChatMutation.reset()
    if (updateChatMutation.error) updateChatMutation.reset()
  }

  function closeModal() {
    setModalOpen(false)
    setEditingChat(null)
    setTitle('')
    setYoloEnabled(false)
    setSelectedEnvironmentName(null)
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
      yoloEnabled,
      selectedEnvironmentName,
      pending: activeMutation.isPending,
      formError: effectiveFormError,
      nameError,
      onClose: closeModal,
      onSelectAgent: handleSelectAgent,
      onTitleChange: handleTitleChange,
      onYoloChange: handleYoloChange,
      onSelectEnvironment: handleSelectEnvironment,
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
