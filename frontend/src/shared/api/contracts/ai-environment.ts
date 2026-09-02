export interface LiveEnvironmentCapabilityDTO {
  id: string
  version: string
}

export interface LiveEnvironmentSkillDTO {
  name: string
  description: string | null
}

/**
 * 完整 Environment binding 的公开表示：canonical 路由名称 + canonical workspace path。
 *
 * 对象本身可空（null 表示未选择 Environment）；非 null 时两字段都必须提供。
 * workspacePath 是 Environment Root 下的 canonical 相对 wire 路径（{@code '.'} 表示 root）。
 */
export interface EnvironmentBindingDTO {
  /** canonical bounded 小写 Environment 路由名称（LiveEnvironmentDTO.name 的同一身份）。 */
  name: string
  /** Environment Root 下 canonical 相对 wire workspace 路径（{@code '.'} 表示 root）。 */
  workspacePath: string
}

/** 单层目录列表中的单个子目录条目。 */
export interface EnvironmentDirectoryEntryDTO {
  /** 子目录名（{@code path} 的最后一段）。 */
  name: string
  /** 子目录的 canonical 相对路径，可直接继续作为 {@code path} 参数浏览。 */
  path: string
}

/**
 * Environment Root 下单层目录浏览结果（control-plane 只读）。
 *
 * {@code path} / {@code parentPath} 是 canonical 相对 wire 路径（{@code '.'} 表示 root）；
 * {@code displayPath} 是请求 {@code path} 的最后一段（root 为 {@code '.'}），只作展示、
 * 绝不暴露 daemon 本地绝对路径；{@code truncated} 表示条目数超过单层上限被截断；
 * {@code gitBranch} 可空，是浏览目录所在 git 仓库的当前分支。
 */
export interface EnvironmentDirectoryDTO {
  /** 被浏览目录的 canonical 相对路径（{@code '.'} 表示 root）。 */
  path: string
  /** 被浏览目录的展示名：请求 {@code path} 的最后一段（root 为 {@code '.'}），绝不暴露本地绝对路径。 */
  displayPath: string
  /** 父目录的 canonical 相对路径（root 为 {@code '.'}）。 */
  parentPath: string
  /** 条目数超过单层上限时为 true。 */
  truncated: boolean
  /** 浏览目录所在 git 仓库的当前分支；未知时为空。 */
  gitBranch: string | null
  /** 按名称稳定排序的直属子目录，不含 symlink。 */
  entries: EnvironmentDirectoryEntryDTO[]
}

/**
 * 只读的实时 Environment 注册表条目。
 *
 * name 是 canonical 逻辑路由身份（也是唯一键）；不存在独立的展示名或 UUID。ready 是按统一
 * 可用性规则（READY + 连接打开 + 心跳未过期）计算的可用标记，供 UI 标注 unavailable。
 */
export interface LiveEnvironmentDTO {
  name: string
  rootPath: string | null
  status: string
  ready: boolean
  lastSeen: string | null
  capabilities: LiveEnvironmentCapabilityDTO[]
  skills: LiveEnvironmentSkillDTO[]
}
