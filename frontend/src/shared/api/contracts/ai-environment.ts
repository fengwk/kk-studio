export interface LiveEnvironmentToolDTO {
  name: string
  version: string | null
  description: string | null
}

export interface LiveEnvironmentSkillDTO {
  name: string
  description: string | null
}

/** Read-only live Environment registry entry. */
export interface LiveEnvironmentDTO {
  name: string
  status: string
  lastSeen: string | null
  tools: LiveEnvironmentToolDTO[]
  skills: LiveEnvironmentSkillDTO[]
}
