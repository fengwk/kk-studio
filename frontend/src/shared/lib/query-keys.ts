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
  tools: {
    all: ['tools'] as const,
    list: ['tools', 'list'] as const,
  },
  environments: {
    all: ['environments'] as const,
    list: ['environments', 'list'] as const,
    detail: (id: string) => ['environments', 'detail', id] as const,
    skillSources: (environmentId: string) =>
      ['environments', 'detail', environmentId, 'skill-sources'] as const,
    skillSource: (environmentId: string, sourceId: string) =>
      ['environments', 'detail', environmentId, 'skill-sources', sourceId] as const,
    inventory: (environmentId: string) =>
      ['environments', 'detail', environmentId, 'inventory'] as const,
    inventorySkills: (environmentId: string, usableOnly: boolean) =>
      ['environments', 'detail', environmentId, 'inventory', 'skills', usableOnly] as const,
    operations: (environmentId: string, limit: number) =>
      ['environments', 'detail', environmentId, 'operations', limit] as const,
    operation: (environmentId: string, operationId: string) =>
      ['environments', 'detail', environmentId, 'operations', operationId] as const,
  },
  mcpServers: {
    all: ['mcp-servers'] as const,
    list: (pageNumber = 1, pageSize = 50) => ['mcp-servers', 'list', pageNumber, pageSize] as const,
    detail: (id: string) => ['mcp-servers', 'detail', id] as const,
  },
  chats: {
    all: ['chats'] as const,
    list: ['chats', 'list'] as const,
    detail: (chatId: string) => ['chats', 'detail', chatId] as const,
    threads: (chatId: string) => ['chats', 'threads', chatId] as const,
  },
  threads: {
    all: ['threads'] as const,
    snapshot: (threadId: string) => ['threads', 'snapshot', threadId] as const,
    entries: (threadId: string) => ['threads', 'entries', threadId] as const,
    systemPrompt: (threadId: string) => ['threads', 'system-prompt', threadId] as const,
  },
  comfyui: {
    workflows: ['comfyui', 'workflows'] as const,
  },
  systemSettings: {
    all: ['system-settings', 'aggregate'] as const,
    schema: ['system-settings', 'schema'] as const,
  },
  studio: {
    canvases: ['studio', 'canvases'] as const,
    canvas: (canvasId: string) => ['studio', 'canvas', canvasId] as const,
    canvasModels: ['studio', 'canvas-function-models'] as const,
    canvasResourcePreview: (canvasId: string, resourceId: string) => (
      ['studio', 'canvas-resource', canvasId, resourceId, 'preview'] as const
    ),
    canvasResourceOriginal: (canvasId: string, resourceId: string) => (
      ['studio', 'canvas-resource', canvasId, resourceId, 'original'] as const
    ),
  },
}
