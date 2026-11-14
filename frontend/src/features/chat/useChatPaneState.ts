import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import {
  loadChatPaneState,
  saveChatPaneState,
  type ChatPaneState,
} from '@/features/chat/chat-pane-state'

export interface ChatPaneStateController {
  paneState: ChatPaneState
  /** 与 paneState 同步的镜像：异步流程读取“提交时最新值”，不订阅重渲染。 */
  paneStateRef: RefObject<ChatPaneState>
  /** tab 呈现状态的唯一提交入口：同步镜像内存状态，再由同一完整快照双写。 */
  commitPaneState: (update: (current: ChatPaneState) => ChatPaneState) => void
  /** 最近一次提交后 tab 快照未能保存到会话存储。 */
  storageUnavailable: boolean
}

/**
 * Chat 工作区的 tab 呈现状态：首次读取按 sessionStorage、最后状态、默认值依次恢复；
 * 之后的每次提交都双写完整快照，并以此报告存储是否可用。
 */
export function useChatPaneState(chatId: string): ChatPaneStateController {
  const [initialLoad] = useState(() => loadChatPaneState(chatId))
  const [paneState, setPaneState] = useState<ChatPaneState>(initialLoad.state)
  const [storageUnavailable, setStorageUnavailable] = useState(!initialLoad.saved)
  const paneStateRef = useRef(paneState)
  useEffect(() => {
    paneStateRef.current = paneState
  }, [paneState])

  const commitPaneState = useCallback((update: (current: ChatPaneState) => ChatPaneState) => {
    const current = paneStateRef.current
    const next = update(current)
    if (next === current) {
      return
    }
    paneStateRef.current = next
    setPaneState(next)
    setStorageUnavailable(!saveChatPaneState(chatId, next))
  }, [chatId])

  return { paneState, paneStateRef, commitPaneState, storageUnavailable }
}
