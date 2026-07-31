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

/** Java {@code Instant} timestamp emitted by the backend's Jackson configuration. */
export type InstantTimestamp = number | string | null

/** Backend resource ids are positive decimal strings and must remain strings on the wire. */
export type AgentResourceId = string

export type BackendLong = number | string

export type BackendBigDecimal = number | string

/** Non-negative decimal optimistic-lock token. */
export type CatalogVersion = string
