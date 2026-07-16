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
    events: (sessionId: string) => ['sessions', 'detail', sessionId, 'events'] as const,
    runs: (sessionId: string) => ['sessions', 'detail', sessionId, 'runs'] as const,
  },
  usage: {
    all: ['usage'] as const,
    run: (runId: string) => ['usage', 'runs', runId] as const,
    session: (sessionId: string) => ['usage', 'sessions', sessionId] as const,
    model: (modelId: AgentResourceId) => ['usage', 'models', String(modelId)] as const,
  },
}
