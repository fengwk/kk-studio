/** React Query keys shared by the workspace shell and the pane renderers. */
export const workspaceKeys = {
  spaces: () => ['chat-spaces'] as const,
  tree: (spaceId: string) => ['chat-spaces', spaceId, 'tree'] as const,
  schedules: (spaceId: string) => ['chat-spaces', spaceId, 'schedules'] as const,
  scheduleRuns: (spaceId: string, scheduleId: string, before: string | null) =>
    ['chat-spaces', spaceId, 'schedules', scheduleId, 'runs', { before }] as const,
  agentSummaries: () => ['workspace-agent-summaries'] as const,
  threadEntries: (threadId: string) => ['workspace-thread-entries', threadId] as const,
}
