import { describe, expect, it } from 'vitest'

import { encodeCompletedProto } from '../src/runtime-encoding.js'

const MIB = 1_048_576

const result = {
  callId: 'call-1',
  error: false,
  details: {},
  contents: [
    {
      type: 'resource' as const,
      uploadId: 'upload-1',
      mediaType: 'text/plain',
      name: 'large.txt',
      size: 16 * MIB + 1,
      sha256: '0'.repeat(64),
    },
  ],
}

describe('runtime result encoding', () => {
  it('reports oversized output with the shared tool output limit message', async () => {
    await expect(encodeCompletedProto(result, 16 * MIB, null)).rejects.toThrow(
      'Tool output is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.',
    )
  })
})
