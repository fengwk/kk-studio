export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
  errors?: Record<string, unknown> | null
}

/** convention4j 分页结果；Java long 的 totalCount 在 wire 上是十进制字符串。 */
export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalCount: string
  results: T[]
}

/** 非负十进制乐观锁 token。 */
export type CatalogVersion = string

/** Canvas graph 单调修订号：Java long 的 canonical 非负十进制字符串。 */
export type CanvasRevision = string
