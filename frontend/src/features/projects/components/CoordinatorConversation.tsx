import { useCallback, useEffect, useRef, useState } from 'react'
import { Bot, Send, Sparkles, User, AlertTriangle } from 'lucide-react'
import { harnessService } from '@/shared/api/harness-service'
import { createUuid } from '@/shared/lib/uuid'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type { ProjectSnapshotDTO } from '../types'

export interface CoordinatorConversationProps {
  projectId: string
  snapshot: ProjectSnapshotDTO
  onCommandAccepted: () => void
  api?: ProjectsApi
}

interface DisplayMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  text: string
  createdAt?: string
}

function parseEntryText(payloadJson: string): string {
  try {
    const parsed = JSON.parse(payloadJson) as {
      message?: {
        contents?: Array<{ type?: string; text?: string }>
        text?: string
      }
      contents?: Array<{ type?: string; text?: string }>
      text?: string
      summary?: string
    }
    if (parsed.message?.contents && Array.isArray(parsed.message.contents)) {
      return parsed.message.contents
        .map((c) => c.text ?? '')
        .filter(Boolean)
        .join('\n')
    }
    if (parsed.contents && Array.isArray(parsed.contents)) {
      return parsed.contents
        .map((c) => c.text ?? '')
        .filter(Boolean)
        .join('\n')
    }
    return parsed.message?.text || parsed.text || parsed.summary || payloadJson
  } catch {
    return payloadJson
  }
}

export function CoordinatorConversation({
  projectId,
  snapshot,
  onCommandAccepted,
  api = projectsApi,
}: CoordinatorConversationProps) {
  const { coordinatorSessionId, coordinatorThread, project } = snapshot
  const [messages, setMessages] = useState<DisplayMessage[]>([])
  const [inputText, setInputText] = useState('')
  const [isSending, setIsSending] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  const threadId = coordinatorThread?.threadId

  const loadThreadHistory = useCallback(async () => {
    if (!threadId) {
      if (coordinatorThread?.headMessagePreview) {
        setMessages([
          {
            id: 'head-preview',
            role: 'assistant',
            text: coordinatorThread.headMessagePreview,
          },
        ])
      } else {
        setMessages([])
      }
      return
    }

    try {
      const threadSnapshot = await harnessService.getThreadSnapshot(threadId)
      const list: DisplayMessage[] = []

      for (const entry of threadSnapshot.entries) {
        if (entry.entryType === 'MESSAGE' || entry.entryType === 'CUSTOM_MESSAGE') {
          let role: 'user' | 'assistant' | 'system' = 'assistant'
          try {
            const parsed = JSON.parse(entry.payloadJson) as {
              message?: { role?: string }
              role?: string
            }
            const r = (parsed.message?.role || parsed.role || '').toUpperCase()
            if (r === 'USER') {
              role = 'user'
            } else if (r === 'SYSTEM') {
              role = 'system'
            }
          } catch {
            // Keep default role
          }

          list.push({
            id: entry.entryId,
            role,
            text: parseEntryText(entry.payloadJson),
            createdAt: typeof entry.createTime === 'string' ? entry.createTime : undefined,
          })
        }
      }

      setMessages(list)
    } catch {
      // If thread fetch fails (e.g. mock test environment), fall back to preview if available
      if (coordinatorThread?.headMessagePreview) {
        setMessages([
          {
            id: 'head-preview',
            role: 'assistant',
            text: coordinatorThread.headMessagePreview,
          },
        ])
      }
    }
  }, [threadId, coordinatorThread])

  useEffect(() => {
    void loadThreadHistory()
  }, [loadThreadHistory])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSendMessage = async (e?: React.FormEvent) => {
    if (e) {
      e.preventDefault()
    }
    const trimmed = inputText.trim()
    if (!trimmed || isSending) {
      return
    }

    setIsSending(true)
    setErrorMessage(null)

    // 乐观添加本地显示
    const tempUserMessage: DisplayMessage = {
      id: createUuid(),
      role: 'user',
      text: trimmed,
      createdAt: new Date().toISOString(),
    }
    setMessages((prev) => [...prev, tempUserMessage])

    try {
      await api.sendProjectCommand(projectId, {
        idempotencyKey: createUuid(),
        message: trimmed,
        threadId: threadId || null,
      })
      setInputText('')
      onCommandAccepted()
      // 等待片刻后刷新会话历史
      setTimeout(() => {
        void loadThreadHistory()
      }, 500)
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : '发送指令失败')
    } finally {
      setIsSending(false)
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      void handleSendMessage()
    }
  }

  return (
    <aside className="coordinator-sidebar" aria-label="Coordinator 对话">
      <div className="coordinator-header">
        <div className="coordinator-header-title">
          <Bot size={18} color="var(--green-primary)" aria-hidden="true" />
          <span>Coordinator: {project.coordinatorAgentName || '未指定'}</span>
        </div>
        {coordinatorThread && (
          <span className="badge badge-status" style={{ fontSize: '0.75rem' }}>
            {coordinatorThread.status}
          </span>
        )}
      </div>

      <div className="coordinator-messages">
        {messages.length === 0 ? (
          <div className="coordinator-empty-hint">
            <Sparkles size={24} style={{ marginBottom: '8px', color: 'var(--green-primary)' }} aria-hidden="true" />
            <p style={{ margin: '0 0 6px 0', fontWeight: 500, color: 'var(--fg)' }}>
              {coordinatorSessionId ? '会话已建立' : '尚未创建 Coordinator 会话'}
            </p>
            <p style={{ margin: 0, fontSize: '0.8125rem' }}>
              向 Coordinator 发送指令以分解任务、派发 Issue 或查询进展。
            </p>
          </div>
        ) : (
          messages.map((msg) => (
            <div
              key={msg.id}
              className={`coordinator-message is-${msg.role}`}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '0.75rem', color: 'var(--fg-muted)' }}>
                {msg.role === 'user' ? (
                  <>
                    <span>人类</span>
                    <User size={12} aria-hidden="true" />
                  </>
                ) : (
                  <>
                    <Bot size={12} aria-hidden="true" />
                    <span>Coordinator</span>
                  </>
                )}
              </div>
              <div className="coordinator-bubble">{msg.text}</div>
            </div>
          ))
        )}
        <div ref={messagesEndRef} />
      </div>

      <div className="coordinator-composer">
        {errorMessage && (
          <div className="form-error-banner" role="alert" style={{ margin: 0 }}>
            <AlertTriangle size={14} aria-hidden="true" />
            <span>{errorMessage}</span>
          </div>
        )}

        <textarea
          className="coordinator-textarea"
          value={inputText}
          onChange={(e) => setInputText(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="向 Coordinator 发送指令... (Ctrl+Enter 发送)"
          rows={2}
          aria-label="Coordinator 指令输入"
          disabled={isSending}
        />

        <div className="coordinator-composer-footer">
          <small style={{ color: 'var(--fg-muted)', fontSize: '0.75rem' }}>
            按 Ctrl+Enter 发送
          </small>
          <button
            type="button"
            className="btn-primary"
            onClick={() => void handleSendMessage()}
            disabled={isSending || !inputText.trim()}
            style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
          >
            <Send size={14} aria-hidden="true" />
            <span>{isSending ? '发送中...' : '发送'}</span>
          </button>
        </div>
      </div>
    </aside>
  )
}
