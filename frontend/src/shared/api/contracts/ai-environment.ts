export interface LiveEnvironmentToolDTO {
  name: string
  version: string | null
  description: string | null
}

export interface LiveEnvironmentSkillDTO {
  name: string
  description: string | null
}

/**
 * Read-only live Environment registry entry.
 *
 * id is the canonical durable route identity (lowercase UUID); name is a display-only label
 * that may be reused across different ids.
 */
export interface LiveEnvironmentDTO {
  id: string
  name: string
  status: string
  lastSeen: string | null
  tools: LiveEnvironmentToolDTO[]
  skills: LiveEnvironmentSkillDTO[]
}
