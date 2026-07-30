export function naturalNameCompare(left: string, right: string): number {
  return left.localeCompare(right, undefined, { numeric: true, sensitivity: 'base' })
}

export function includesSearch(value: string, search: string): boolean {
  const needle = search.trim().toLowerCase()
  return !needle || value.toLowerCase().includes(needle)
}
