export function uniqueNodeAlias(
  preferred: string,
  existingNames: Iterable<string>,
): string {
  const base = canonicalNodeAlias(preferred)
  const occupied = new Set([...existingNames].map(normalizeNodeAlias))
  if (!occupied.has(normalizeNodeAlias(base))) {
    return base
  }
  for (let suffix = 2; ; suffix += 1) {
    const marker = ` ${suffix}`
    const candidate = `${base.slice(0, Math.max(0, 256 - marker.length))}${marker}`
    if (!occupied.has(normalizeNodeAlias(candidate))) {
      return candidate
    }
  }
}

export function canonicalNodeAlias(value: string): string {
  return value.normalize('NFKC').trim().slice(0, 256)
}

export function normalizeNodeAlias(value: string): string {
  return canonicalNodeAlias(value).toLowerCase()
}
