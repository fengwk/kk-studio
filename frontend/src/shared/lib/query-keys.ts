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
  sessions: {
    all: ['sessions'] as const,
    list: ['sessions', 'list'] as const,
    detail: (sessionId: string) => ['sessions', 'detail', sessionId] as const,
    entries: (sessionId: string) => ['sessions', 'detail', sessionId, 'entries'] as const,
    runs: (sessionId: string) => ['sessions', 'detail', sessionId, 'runs'] as const,
    activities: (sessionId: string) => ['sessions', 'detail', sessionId, 'activities'] as const,
    taskTree: (sessionId: string) => ['sessions', 'detail', sessionId, 'task-tree'] as const,
    yolo: (sessionId: string) => ['sessions', 'detail', sessionId, 'yolo'] as const,
  },
  runs: {
    events: (runId: string) => ['runs', 'detail', runId, 'events'] as const,
    toolInvocations: (runId: string) => ['runs', 'detail', runId, 'tool-invocations'] as const,
  },
  usage: {
    all: ['usage'] as const,
    run: (runId: string) => ['usage', 'runs', runId] as const,
    session: (sessionId: string) => ['usage', 'sessions', sessionId] as const,
    model: (modelId: AgentResourceId) => ['usage', 'models', String(modelId)] as const,
  },
  comfyui: {
    workflows: ['comfyui', 'workflows'] as const,
  },
}
