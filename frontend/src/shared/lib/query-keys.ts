import type { AgentResourceId } from '@/shared/api/contracts'

export const queryKeys = {
  providers: {
    all: ['providers'] as const,
    list: ['providers', 'list'] as const,
  },
  models: {
    all: ['models'] as const,
    list: ['models', 'list'] as const,
  },
  agents: {
    all: ['agents'] as const,
    list: ['agents', 'list'] as const,
  },
  environments: {
    all: ['environments'] as const,
    list: ['environments', 'list'] as const,
  },
  harness: {
    retryPolicy: ['harness', 'retry-policy'] as const,
  },
  chats: {
    all: ['chats'] as const,
    list: ['chats', 'list'] as const,
    detail: (chatId: string) => ['chats', 'detail', chatId] as const,
    sessions: (chatId: string) => ['chats', 'detail', chatId, 'sessions'] as const,
  },
  threads: {
    all: ['threads'] as const,
    detail: (threadId: string) => ['threads', 'detail', threadId] as const,
    entries: (threadId: string) => ['threads', 'detail', threadId, 'entries'] as const,
    inputs: (threadId: string) => ['threads', 'detail', threadId, 'inputs'] as const,
    events: (threadId: string) => ['threads', 'detail', threadId, 'events'] as const,
    toolInvocations: (threadId: string) => ['threads', 'detail', threadId, 'tool-invocations'] as const,
  },
  sessions: {
    all: ['sessions'] as const,
    list: ['sessions', 'list'] as const,
    detail: (sessionId: string) => ['sessions', 'detail', sessionId] as const,
    threads: (sessionId: string) => ['sessions', 'detail', sessionId, 'threads'] as const,
    entries: (sessionId: string) => ['sessions', 'detail', sessionId, 'entries'] as const,
    activities: (sessionId: string) => ['sessions', 'detail', sessionId, 'activities'] as const,
    taskTree: (sessionId: string) => ['sessions', 'detail', sessionId, 'task-tree'] as const,
  },
  usage: {
    all: ['usage'] as const,
    thread: (threadId: string) => ['usage', 'threads', threadId] as const,
    session: (sessionId: string) => ['usage', 'sessions', sessionId] as const,
    model: (modelId: AgentResourceId) => ['usage', 'models', String(modelId)] as const,
  },
  comfyui: {
    workflows: ['comfyui', 'workflows'] as const,
  },
}
