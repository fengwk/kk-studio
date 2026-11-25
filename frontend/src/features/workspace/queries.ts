/** React Query keys shared by the workspace shell and the pane renderers. */
export const workspaceKeys = {
  spaces: () => ['chat-spaces'] as const,
  tree: (spaceId: string) => ['chat-spaces', spaceId, 'tree'] as const,
}
