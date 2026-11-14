import { useState, type FormEventHandler } from 'react'
import { useNavigate } from 'react-router'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { chatService } from '@/features/chat/chat-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { useInvalidateMutation } from '@/shared/lib/useInvalidateMutation'
import type { ConfirmModalState } from '@/shared/ui/overlays/confirm-modal'
import { useQueryClient } from '@tanstack/react-query'
import type { AgentDTO, ChatDTO } from '@/shared/api/generated/contracts'

function resolveChatAgentName(
  agentName: string | undefined,
  selectedAgentName: string,
  agents: AgentDTO[],
): string {
  if (agentName && agents.some((agent) => agent.name === agentName)) {
    return agentName
  }
  if (selectedAgentName && agents.some((agent) => agent.name === selectedAgentName)) {
    return selectedAgentName
  }
  return agents[0]?.name ?? ''
}

/**
 * Chat 创建/编辑/删除弹窗的状态、提交与导航。
 *
 * 表单字段是同一份草稿：创建、编辑共用，提交成功后回到默认值并跳转到 Chat 详情。
 */
export function useChatFormController(agents: AgentDTO[]) {
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
      clearFormFields()
      navigate(`/chats/${encodeURIComponent(chat.id)}`)
    },
  })

  const updateChatMutation = useInvalidateMutation({
    mutationFn: async () => {
      if (!editingChat) {
        throw new Error('No chat being edited')
      }
      const updated = await chatService.updateChat(editingChat.id, {
        title: title.trim(),
        agentName: selectedAgentName,
        environmentName: selectedEnvironmentName ?? null,
        expectedVersion: editingChat.version,
      })
      // 整体替换已提交：即使随后 YOLO 写入失败，弹窗也以新版本为基准，并让列表反映已保存的字段。
      setEditingChat(updated)
      await queryClient.invalidateQueries({ queryKey: queryKeys.chats.list })
      if (yoloEnabled === updated.yoloEnabled) {
        return updated
      }
      // YOLO 是独立子资源，跟随整体替换后的新版本单独提交。
      return chatService.updateYolo(updated.id, {
        yoloEnabled,
        expectedVersion: updated.version,
      })
    },
    invalidateQueryKeys: [queryKeys.chats.list],
    onSuccess: async () => {
      setModalOpen(false)
      setEditingChat(null)
      clearFormFields()
    },
  })

  /** 关闭弹窗、切换创建/编辑、提交成功后都把表单字段归零（顺序与各调用点一致）。 */
  function clearFormFields() {
    setTitle('')
    setYoloEnabled(false)
    setSelectedEnvironmentName(null)
    setFormError('')
    setNameError('')
  }

  /** 用户修改任一字段后，旧请求的错误不再对应当前输入。 */
  function clearMutationErrors() {
    if (createChatMutation.error) createChatMutation.reset()
    if (updateChatMutation.error) updateChatMutation.reset()
  }

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
    clearFormFields()
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
    clearMutationErrors()
  }

  function handleSelectAgent(agentName: string) {
    setSelectedAgentName(agentName)
    clearMutationErrors()
  }

  function handleYoloChange(next: boolean) {
    setYoloEnabled(next)
    if (formError) setFormError('')
    clearMutationErrors()
  }

  function handleSelectEnvironment(next: string | null) {
    setSelectedEnvironmentName(next)
    if (formError) setFormError('')
    clearMutationErrors()
  }

  function closeModal() {
    setModalOpen(false)
    setEditingChat(null)
    clearFormFields()
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

  return {
    dialogOpen: modalOpen || deleteConfirm != null,
    anyMutationError:
      createChatMutation.error || updateChatMutation.error || deleteChatMutation.error,
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
