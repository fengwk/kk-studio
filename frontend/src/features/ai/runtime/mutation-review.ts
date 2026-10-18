/**
 * Authoritative mutation review contract and parsing for write and edit tools.
 * Sourced strictly from approvalJson.editReview or terminal result details.editReview.
 */

export interface EditReview {
  path: string
  diff: string
  sha256: string
}

const SHA256_HEX_REGEX = /^[0-9a-f]{64}$/
const ALLOWED_KEYS = new Set(['path', 'diff', 'sha256'])

/**
 * Strictly parses and validates an EditReview object with canonical parity:
 * - Reject extra/unknown fields
 * - path must be non-empty and non-blank (path.trim() !== '') but preserved as raw string without silent trimming
 * - diff must be non-empty string
 * - sha256 must be exact 64 lowercase hex characters without silent trimming
 */
export function parseEditReview(value: unknown): EditReview | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  const keys = Object.keys(record)
  if (keys.length !== 3 || keys.some((key) => !ALLOWED_KEYS.has(key))) {
    return null
  }
  const path = record.path
  if (typeof path !== 'string' || path.length === 0 || path.trim().length === 0) {
    return null
  }
  const diff = record.diff
  if (typeof diff !== 'string' || diff.length === 0) {
    return null
  }
  const sha256 = record.sha256
  if (typeof sha256 !== 'string' || !SHA256_HEX_REGEX.test(sha256)) {
    return null
  }
  return {
    path,
    diff,
    sha256,
  }
}

/**
 * Extracts EditReview from an approval JSON string if present and valid.
 */
export function extractApprovalEditReview(approvalJson: string | null | undefined): EditReview | null {
  if (!approvalJson) {
    return null
  }
  try {
    const parsed = JSON.parse(approvalJson)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parseEditReview((parsed as Record<string, unknown>).editReview)
    }
  } catch {
    // Malformed approval JSON
  }
  return null
}

/**
 * Extracts EditReview strictly from canonical result details.editReview.
 * Wire result has details object; accepts either parsed ToolResult object or raw resultJson string.
 */
export function extractResultEditReview(payload: unknown): EditReview | null {
  if (!payload) {
    return null
  }
  let record: Record<string, unknown> | null = null
  if (typeof payload === 'string') {
    try {
      const parsed = JSON.parse(payload)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        record = parsed as Record<string, unknown>
      }
    } catch {
      return null
    }
  } else if (typeof payload === 'object' && !Array.isArray(payload)) {
    record = payload as Record<string, unknown>
  }

  if (!record) {
    return null
  }

  // Sourced strictly from canonical details object only
  const details = record.details
  if (details && typeof details === 'object' && !Array.isArray(details)) {
    return parseEditReview((details as Record<string, unknown>).editReview)
  }

  return null
}
