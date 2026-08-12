export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
  errors?: Record<string, unknown> | null
}

export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalCount: number | string
  results: T[]
}

export type BackendDateTime = string | number[] | null

/** 后端 Jackson 配置发出的 Java {@code Instant} 时间戳。 */
export type InstantTimestamp = number | string | null

export type BackendLong = number | string

/** 非负十进制乐观锁 token。 */
export type CatalogVersion = string

/** 后端 Jackson 对 Java long/Long 的 wire 形态：canonical 非负十进制字符串。 */
export type DecimalLong = string

/** Canvas graph 单调版本：canonical 非负十进制字符串（数据库仍是 bigint，仅 wire 为字符串）。 */
export type CanvasVersion = DecimalLong
