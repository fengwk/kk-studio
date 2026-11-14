import { useChatFormController } from '@/features/chat/useChatFormController'
import { useChatListPages } from '@/features/chat/useChatListPages'
import type { AgentDTO } from '@/shared/api/generated/contracts'

/**
 * Chat 列表页的控制器：组合列表分页与创建/编辑/删除表单，只负责两者的输出拼装。
 *
 * 列表在弹窗打开期间不暴露请求错误，避免后台刷新失败打断用户输入；弹窗关闭后再显示。
 */
export function useChatListController(
  agents: AgentDTO[],
  enabled: boolean,
) {
  const listPages = useChatListPages(enabled)
  const form = useChatFormController(agents)

  return {
    ...listPages,
    chatMutationError: form.dialogOpen ? null : form.anyMutationError,
    openCreateChat: form.openCreateChat,
    openEditChat: form.openEditChat,
    openDeleteChat: form.openDeleteChat,
    createChatModal: form.createChatModal,
    deleteConfirmModal: form.deleteConfirmModal,
  }
}
