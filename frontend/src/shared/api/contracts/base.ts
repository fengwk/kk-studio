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

export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
}

export type BackendDateTime = string | number[] | null

/** Java {@code Instant} timestamp emitted by the backend's Jackson configuration. */
export type InstantTimestamp = number | string | null

export type BackendLong = number | string

export type BackendBigDecimal = number | string

/** Non-negative decimal optimistic-lock token. */
export type CatalogVersion = string
