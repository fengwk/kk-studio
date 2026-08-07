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
 * name 是 canonical 逻辑路由身份（也是唯一键）；不存在独立的展示名或 UUID。ready 是按统一
 * 可用性规则（READY + 连接打开 + 心跳未过期）计算的可用标记，供 UI 标注 unavailable。
 */
export interface LiveEnvironmentDTO {
  name: string
  status: string
  ready: boolean
  lastSeen: string | null
  tools: LiveEnvironmentToolDTO[]
  skills: LiveEnvironmentSkillDTO[]
}
