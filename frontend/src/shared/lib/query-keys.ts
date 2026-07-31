import type { AgentResourceId } from '@/shared/api/contracts/base'

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
    realtimeStreamPolicy: ['harness', 'realtime-stream-policy'] as const,
  },
  chats: {
    all: ['chats'] as const,
    list: ['chats', 'list'] as const,
    detail: (chatId: string) => ['chats', 'detail', chatId] as const,
  },
  threads: {
    all: ['threads'] as const,
    list: ['threads', 'list'] as const,
    page: (scope: 'current' | 'global', sort: 'recent' | 'created', chatId?: string) =>
      ['threads', 'list', scope, sort, chatId ?? ''] as const,
    snapshot: (threadId: string) => ['threads', 'snapshot', threadId] as const,
  },
  sessions: {
    all: ['sessions'] as const,
    list: ['sessions', 'list'] as const,
    threadIndex: (sort: 'recent' | 'created') => ['sessions', 'thread-index', sort] as const,
    detail: (sessionId: string) => ['sessions', 'detail', sessionId] as const,
    entries: (sessionId: string) => ['sessions', 'detail', sessionId, 'entries'] as const,
  },
  usage: {
    all: ['usage'] as const,
    session: (sessionId: string) => ['usage', 'sessions', sessionId] as const,
    model: (modelId: AgentResourceId) => ['usage', 'models', String(modelId)] as const,
  },
  comfyui: {
    workflows: ['comfyui', 'workflows'] as const,
  },
  studio: {
    canvases: ['studio', 'canvases'] as const,
  },
}
