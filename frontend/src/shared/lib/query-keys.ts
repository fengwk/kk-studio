export const queryKeys = {
  workspaces: {
    all: ['workspaces'] as const,
    list: ['workspaces', 'list'] as const,
    detail: (workspaceId: string) => ['workspaces', 'detail', workspaceId] as const,
  },
  providers: {
    list: (workspaceId: string) => ['workspaces', workspaceId, 'providers'] as const,
  },
  models: {
    list: (workspaceId: string) => ['workspaces', workspaceId, 'models'] as const,
  },
  agents: {
    list: (workspaceId: string) => ['workspaces', workspaceId, 'agents'] as const,
  },
  // Session data is currently loaded through the legacy Session API, but must
  // still be partitioned by the active workspace in React Query.
  sessions: {
    list: (workspaceId: string) => ['workspaces', workspaceId, 'sessions'] as const,
    detail: (workspaceId: string, sessionId: string) => ['workspaces', workspaceId, 'sessions', sessionId] as const,
    events: (workspaceId: string, sessionId: string) => ['workspaces', workspaceId, 'sessions', sessionId, 'events'] as const,
    runs: (workspaceId: string, sessionId: string) => ['workspaces', workspaceId, 'sessions', sessionId, 'runs'] as const,
  },
}
