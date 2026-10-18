import { describe, expect, it } from 'vitest'
import {
  extractApprovalEditReview,
  extractResultEditReview,
  parseEditReview,
} from './mutation-review'

describe('mutation-review parser and extractor', () => {
  const validSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

  it('strictly validates editReview structure and fields without silent trimming', () => {
    // Valid case
    expect(
      parseEditReview({
        path: '/workspace/src/app.ts',
        diff: '-1|const a = 1\n+1|const a = 2',
        sha256: validSha,
      }),
    ).toEqual({
      path: '/workspace/src/app.ts',
      diff: '-1|const a = 1\n+1|const a = 2',
      sha256: validSha,
    })

    // Missing or invalid path
    expect(parseEditReview({ path: '', diff: '+a', sha256: validSha })).toBeNull()
    expect(parseEditReview({ diff: '+a', sha256: validSha })).toBeNull()

    // Missing, empty or non-string diff (diff must be non-empty)
    expect(parseEditReview({ path: '/foo', diff: '', sha256: validSha })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: null, sha256: validSha })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: 123, sha256: validSha })).toBeNull()

    // Missing, padded or invalid sha256 (must be exact 64 lowercase hex, no trim acceptance)
    expect(parseEditReview({ path: '/foo', diff: '+a', sha256: '' })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: '+a', sha256: 'abc' })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: '+a', sha256: ` ${validSha}` })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: '+a', sha256: `${validSha} ` })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: '+a', sha256: validSha.toUpperCase() })).toBeNull()
    expect(parseEditReview({ path: '/foo', diff: '+a', sha256: validSha + '0' })).toBeNull()

    // Non-object or null
    expect(parseEditReview(null)).toBeNull()
    expect(parseEditReview(undefined)).toBeNull()
    expect(parseEditReview('string')).toBeNull()
    expect(parseEditReview([])).toBeNull()
  })

  it('extracts editReview from approvalJson string', () => {
    const approvalJson = JSON.stringify({
      required: true,
      editReview: {
        path: '/workspace/file.txt',
        diff: '-old\n+new',
        sha256: validSha,
      },
    })
    expect(extractApprovalEditReview(approvalJson)).toEqual({
      path: '/workspace/file.txt',
      diff: '-old\n+new',
      sha256: validSha,
    })

    // Returns null for invalid or missing approvalJson or review
    expect(extractApprovalEditReview(null)).toBeNull()
    expect(extractApprovalEditReview('invalid json')).toBeNull()
    expect(extractApprovalEditReview('{}')).toBeNull()
    expect(extractApprovalEditReview(JSON.stringify({ required: true }))).toBeNull()
    expect(extractApprovalEditReview(JSON.stringify({ editReview: { path: '' } }))).toBeNull()
  })

  it('extracts editReview strictly from canonical details object', () => {
    // Sourced strictly from details object
    expect(
      extractResultEditReview({
        toolCallId: 'call-1',
        contents: [{ type: 'text', text: 'ok' }],
        error: false,
        details: {
          editReview: {
            path: '/file.ts',
            diff: '+added',
            sha256: validSha,
          },
        },
      }),
    ).toEqual({
      path: '/file.ts',
      diff: '+added',
      sha256: validSha,
    })

    // Works with JSON string payload directly
    const rawResultJson = JSON.stringify({
      toolCallId: 'call-1',
      contents: [{ type: 'text', text: 'ok' }],
      error: false,
      details: {
        editReview: {
          path: '/file.ts',
          diff: '+added',
          sha256: validSha,
        },
      },
    })
    expect(extractResultEditReview(rawResultJson)).toEqual({
      path: '/file.ts',
      diff: '+added',
      sha256: validSha,
    })

    // Rejects non-canonical locations (direct editReview, detailsJson, metadata, etc.)
    expect(
      extractResultEditReview({
        editReview: {
          path: '/file.ts',
          diff: '+added',
          sha256: validSha,
        },
      }),
    ).toBeNull()

    expect(
      extractResultEditReview({
        detailsJson: JSON.stringify({
          editReview: {
            path: '/file.ts',
            diff: '+added',
            sha256: validSha,
          },
        }),
      }),
    ).toBeNull()

    expect(
      extractResultEditReview({
        metadata: {
          editReview: {
            path: '/file.ts',
            diff: '+added',
            sha256: validSha,
          },
        },
      }),
    ).toBeNull()
  })
})
