import { describe, expect, it } from 'vitest'
import {
  fileOutputExceededMessage,
  toolOutputExceededMessage,
} from '../src/tool-output-limit.js'

describe('tool output limit messages', () => {
  // These vectors are mirrored in common/src/test/.../ToolOutputLimitTest.java.
  it('formats the shared sizes and exact model-visible messages', () => {
    const limit = 16 * 1_048_576

    expect(toolOutputExceededMessage(17_000_000, limit)).toBe(
      'Tool output is 16.2 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.',
    )
    expect(toolOutputExceededMessage(16_777_217, limit)).toBe(
      'Tool output is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.',
    )
    expect(fileOutputExceededMessage(16_777_217, limit)).toBe(
      'File is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.',
    )
  })
})
