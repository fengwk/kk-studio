const BYTES_PER_MIB = 1_048_576n

/** Formats a measured size without encoding or copying the reported content. */
function formatActualMib(actualBytes: number, limitBytes: number): string {
  const actual = BigInt(actualBytes)
  const limitMib = BigInt(limitBytes) / BYTES_PER_MIB
  for (let decimals = 1; decimals <= 6; decimals++) {
    const multiplier = 10n ** BigInt(decimals)
    const rounded = (actual * multiplier + BYTES_PER_MIB / 2n) / BYTES_PER_MIB
    if (rounded !== limitMib * multiplier || decimals === 6) {
      const whole = rounded / multiplier
      const fraction = (rounded % multiplier).toString().padStart(decimals, '0').replace(/0+$/, '')
      return fraction.length > 0 ? `${whole}.${fraction}` : whole.toString()
    }
  }
  throw new Error('unreachable')
}

function formatLimitMib(limitBytes: number): string {
  return (BigInt(limitBytes) / BYTES_PER_MIB).toString()
}

export function toolOutputExceededMessage(actualBytes: number, limitBytes: number): string {
  return `Tool output is ${formatActualMib(actualBytes, limitBytes)} MiB, which exceeds the tool output size limit of ${formatLimitMib(limitBytes)} MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.`
}

export function fileOutputExceededMessage(actualBytes: number, limitBytes: number): string {
  return `File is ${formatActualMib(actualBytes, limitBytes)} MiB, which exceeds the tool output size limit of ${formatLimitMib(limitBytes)} MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.`
}
