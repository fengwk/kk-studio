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
 * 只读的实时 Environment 注册表条目。
 *
 * id 是规范的持久路由标识（小写 UUID）；name 仅是展示用标签，可能在不同的 id 之间复用。
 */
export interface LiveEnvironmentDTO {
  id: string
  name: string
  status: string
  lastSeen: string | null
  tools: LiveEnvironmentToolDTO[]
  skills: LiveEnvironmentSkillDTO[]
}
