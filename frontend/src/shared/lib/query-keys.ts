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
  threads: {
    all: ['threads'] as const,
    list: ['threads', 'list'] as const,
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
