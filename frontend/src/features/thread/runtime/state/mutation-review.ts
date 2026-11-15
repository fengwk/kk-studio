/**
 * Authoritative mutation review contract and parsing for write and edit tools.
 * Sourced strictly from approval.editReview or terminal result details.editReview
 * (edit) / details.writeReview (write).
 */

export interface EditReviewLossyDecode {
  encoding: string
  invalidSequences: number
}

export interface EditReview {
  path: string
  diff: string
  sha256: string | null
  /** Present only when the original file decoded with replacement loss. */
  lossyDecode?: EditReviewLossyDecode
}

const SHA256_HEX_REGEX = /^[0-9a-f]{64}$/
const REQUIRED_KEYS = ['path', 'diff', 'sha256'] as const
const ALLOWED_KEYS = new Set([...REQUIRED_KEYS, 'lossyDecode'])
const LOSSY_DECODE_KEYS = new Set(['encoding', 'invalidSequences'])

function parseLossyDecode(value: unknown): EditReviewLossyDecode | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  const keys = Object.keys(record)
  if (keys.length !== LOSSY_DECODE_KEYS.size || keys.some((key) => !LOSSY_DECODE_KEYS.has(key))) {
    return null
  }
  const encoding = record.encoding
  if (typeof encoding !== 'string' || encoding.trim().length === 0) {
    return null
  }
  const invalidSequences = record.invalidSequences
  if (typeof invalidSequences !== 'number'
    || !Number.isInteger(invalidSequences)
    || invalidSequences <= 0) {
    return null
  }
  return { encoding, invalidSequences }
}

/**
 * Strictly parses and validates an EditReview object with canonical parity:
 * - Reject extra/unknown fields and missing required fields
 * - path must be non-empty and non-blank (path.trim() !== '') but preserved as raw string without silent trimming
 * - diff must be non-empty string
 * - sha256 is null for a reviewed new file, or exact 64 lowercase hex without silent trimming
 * - lossyDecode is omitted, or exactly {encoding: non-blank string, invalidSequences: positive integer};
 *   a malformed value invalidates the whole review (fail closed)
 */
export function parseEditReview(value: unknown): EditReview | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }
  const record = value as Record<string, unknown>
  const keys = Object.keys(record)
  if (keys.some((key) => !ALLOWED_KEYS.has(key))
    || REQUIRED_KEYS.some((key) => !Object.hasOwn(record, key))) {
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
  if (sha256 !== null && (typeof sha256 !== 'string' || !SHA256_HEX_REGEX.test(sha256))) {
    return null
  }
  let lossyDecode: EditReviewLossyDecode | undefined
  if (Object.hasOwn(record, 'lossyDecode')) {
    const parsed = parseLossyDecode(record.lossyDecode)
    if (parsed == null) {
      return null
    }
    lossyDecode = parsed
  }
  return {
    path,
    diff,
    sha256,
    ...(lossyDecode === undefined ? {} : { lossyDecode }),
  }
}

/**
 * Extracts EditReview from an approval JSON node if present and valid.
 */
export function extractApprovalEditReview(approval: unknown): EditReview | null {
  if (approval && typeof approval === 'object' && !Array.isArray(approval)) {
    return parseEditReview((approval as Record<string, unknown>).editReview)
  }
  return null
}

/**
 * Extracts EditReview strictly from a canonical result details object:
 * edits persist `details.editReview`; writes persist `details.writeReview`.
 */
export function extractResultEditReview(payload: unknown): EditReview | null {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    return null
  }
  // Sourced strictly from canonical details object only
  const details = (payload as Record<string, unknown>).details
  if (details && typeof details === 'object' && !Array.isArray(details)) {
    const record = details as Record<string, unknown>
    if (Object.hasOwn(record, 'editReview')) {
      return parseEditReview(record.editReview)
    }
    return parseEditReview(record.writeReview)
  }

  return null
}
