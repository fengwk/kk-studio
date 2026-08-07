export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
  errors?: Record<string, unknown>
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
