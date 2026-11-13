function normalizeClaudeModelId(modelId: string): string {
  return modelId.toLowerCase().replace(/^~?anthropic\//, '')
}

/** Models that accept system messages and tool changes after the conversation starts. */
export function supportsClaudeMidConvoSystemMessages(modelId: string): boolean {
  const id = normalizeClaudeModelId(modelId)
  return (
    /^claude-opus-(4[.-]8|5([.-]5)?)(-\d{8})?$/.test(id) ||
    /^claude-(sonnet|haiku)-5[.-]5(-\d{8})?$/.test(id) ||
    /^claude-(fable|mythos)-5([.-][12])?(-\d{8})?$/.test(id)
  )
}
