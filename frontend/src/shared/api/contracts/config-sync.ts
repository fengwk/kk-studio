/**
 * 设置同步的严格 wire 契约（对齐 /api/settings/sync 四端点）。
 *
 * - `GET /api/settings/sync` 返回可导出的库存条目与其依赖；
 * - `POST /api/settings/sync/export` 提交选中的引用，返回 YAML 文本；
 * - `POST /api/settings/sync/import/check` 提交 YAML 文本，返回将新增/覆盖/跳过的计划；
 * - `POST /api/settings/sync/import` 提交 YAML 文本，返回已导入与跳过条目。
 *
 * 预检查不写库，只给出计划；执行会重新校验。库存与检查结果都不含配置值与凭据：
 * 凭据只随导出 YAML 出现，UI 不渲染 YAML。
 */
export type ConfigSyncKind =
  | 'providers'
  | 'models'
  | 'agents'
  | 'skillPackages'
  | 'environments'
  | 'mcpServers'
  | 'settings'

/** 配置引用的业务标识：模型为 `providerName/modelName`，设置为 `settings`。 */
export interface ConfigSyncRef {
  kind: ConfigSyncKind
  name: string
}

/** 库存条目：在引用之上附带其依赖，用于 UI 展示导出范围。 */
export interface ConfigSyncItem extends ConfigSyncRef {
  dependencies: ConfigSyncRef[]
}

/** 导入时被跳过的条目及原因（未知类别/字段、引用不支持项等）。 */
export interface ConfigSyncSkipped {
  kind: string
  name: string
  reason: string
}

export interface ConfigSyncInventoryDTO {
  items: ConfigSyncItem[]
}

export interface ConfigSyncExportRequestDTO {
  items: ConfigSyncRef[]
}

export interface ConfigSyncExportResponseDTO {
  yaml: string
}

export interface ConfigSyncImportRequestDTO {
  yaml: string
  /** 缺省 false：存在跳过条目时，未显式授权就不执行部分导入。 */
  allowPartial?: boolean
}

export interface ConfigSyncImportResponseDTO {
  imported: ConfigSyncRef[]
  skipped: ConfigSyncSkipped[]
}

/** 预检查请求：只提交 YAML 文本，不落库。 */
export interface ConfigSyncImportCheckRequestDTO {
  yaml: string
}

/** 预检查结果：将新增、将覆盖（同名且可写）与将跳过的条目。 */
export interface ConfigSyncImportCheckDTO {
  created: ConfigSyncRef[]
  updated: ConfigSyncRef[]
  skipped: ConfigSyncSkipped[]
}
