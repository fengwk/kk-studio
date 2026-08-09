import { useCallback, useEffect, useMemo, useRef } from 'react'
import { createTaskLevelStateMap } from '@/features/ai/runtime/task-status'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

const COMPLETION_AFTER_REJECTION_SUPPRESSION_MS = 5_000

export type BrowserNotificationPermission = NotificationPermission | 'unsupported'

export interface PendingPermission {
  key: string
  toolName: string
  reason: string | null
}

export function browserNotificationPermission(): BrowserNotificationPermission {
  return typeof window !== 'undefined' && 'Notification' in window
    ? window.Notification.permission
    : 'unsupported'
}

export async function requestBrowserNotificationPermission(): Promise<BrowserNotificationPermission> {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return 'unsupported'
  }
  if (window.Notification.permission !== 'default') {
    return window.Notification.permission
  }
  try {
    return await window.Notification.requestPermission()
  } catch {
    return window.Notification.permission
  }
}

/** 汇总父 Thread 与 task.status relay 的所有当前待决审批，身份包含目标 Thread。 */
export function collectPendingPermissions(
  parentThreadId: string,
  messages: readonly DialogueMessage[],
): PendingPermission[] {
  const permissions = new Map<string, PendingPermission>()
  for (const message of messages) {
    if (message.role !== 'tool') {
      continue
    }
    collectParentPermission(permissions, parentThreadId, message)
  }
  const levels = createTaskLevelStateMap(messages)
  for (const statuses of levels.values()) {
    for (const status of statuses) {
      for (const approval of status.approvals) {
        const key = `${status.threadId}:${approval.invocationId}`
        permissions.set(key, {
          key,
          toolName: approval.toolName,
          reason: approval.reason,
        })
      }
    }
  }
  return [...permissions.values()]
}

/**
 * 浏览器通知只观察现有 timeline/working 投影，不建立第二套 Session 状态。
 * permission requested 与 agent end 都是 best-effort UI 副作用。
 */
export function useThreadNotifications({
  threadId,
  title,
  messages,
  working,
  enabled,
}: {
  threadId: string
  title: string
  messages: readonly DialogueMessage[]
  working: boolean
  enabled: boolean
}) {
  const pendingPermissions = useMemo(
    () => collectPendingPermissions(threadId, messages),
    [messages, threadId],
  )
  const previousPermissionKeysRef = useRef(new Set<string>())
  const previousWorkingRef = useRef(working)
  const rejectedAtRef = useRef<number | null>(null)
  const observedThreadIdRef = useRef(threadId)

  useEffect(() => {
    if (observedThreadIdRef.current === threadId) {
      return
    }
    observedThreadIdRef.current = threadId
    previousPermissionKeysRef.current.clear()
    previousWorkingRef.current = working
    rejectedAtRef.current = null
  }, [threadId, working])

  useEffect(() => {
    const currentKeys = new Set(pendingPermissions.map((permission) => permission.key))
    if (enabled) {
      const requested = pendingPermissions.filter(
        (permission) => !previousPermissionKeysRef.current.has(permission.key),
      )
      for (const permission of requested) {
        showBrowserNotification(
          translate('ai.runtime.notification.permissionTitle'),
          permission.reason
            ? translate('ai.runtime.notification.permissionBodyWithReason', {
                title,
                toolName: permission.toolName,
                reason: permission.reason,
              })
            : translate('ai.runtime.notification.permissionBody', {
                title,
                toolName: permission.toolName,
              }),
        )
      }
    }
    previousPermissionKeysRef.current = enabled ? currentKeys : new Set()
  }, [enabled, pendingPermissions, title])

  useEffect(() => {
    const wasWorking = previousWorkingRef.current
    previousWorkingRef.current = working
    if (!enabled || !wasWorking || working) {
      return
    }
    const finality = latestAssistantFinality(messages)
    if (finality === 'aborted') {
      return
    }
    if (finality === 'error') {
      showBrowserNotification(
        translate('ai.runtime.notification.errorTitle'),
        translate('ai.runtime.notification.agentBody', { title }),
      )
      return
    }
    const rejectedAt = rejectedAtRef.current
    if (
      rejectedAt != null
      && Date.now() - rejectedAt < COMPLETION_AFTER_REJECTION_SUPPRESSION_MS
    ) {
      return
    }
    showBrowserNotification(
      translate('ai.runtime.notification.completedTitle'),
      translate('ai.runtime.notification.agentBody', { title }),
    )
  }, [enabled, messages, title, working])

  return {
    markPermissionRejected: useCallback(() => {
      rejectedAtRef.current = Date.now()
    }, []),
  }
}

function collectParentPermission(
  permissions: Map<string, PendingPermission>,
  threadId: string,
  message: ToolDialogueMessage,
): void {
  if (
    message.phase !== 'call'
    || !message.invocationId
    || message.approval?.required !== true
    || message.approval.decision != null
  ) {
    return
  }
  const key = `${threadId}:${message.invocationId}`
  permissions.set(key, {
    key,
    toolName: message.toolName,
    reason: message.approval.reason,
  })
}

function latestAssistantFinality(
  messages: readonly DialogueMessage[],
): 'completed' | 'error' | 'aborted' {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (message?.role !== 'assistant') {
      continue
    }
    if (message.aborted) {
      return 'aborted'
    }
    return message.status === 'error' ? 'error' : 'completed'
  }
  return 'completed'
}

function showBrowserNotification(title: string, body: string): void {
  if (browserNotificationPermission() !== 'granted') {
    return
  }
  try {
    new window.Notification(title, { body, tag: `kkstudio:${title}:${body}` })
  } catch {
    // 通知是辅助能力，绝不能中断审批或 Thread 生命周期。
  }
}
