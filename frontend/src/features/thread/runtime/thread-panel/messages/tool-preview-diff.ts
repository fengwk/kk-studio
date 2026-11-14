/** diff 行按前缀着色：新增/删除/hunk 头/上下文。 */
export function diffLineClass(line: string): string {
  if (line.startsWith('+')) {
    return 'is-added'
  }
  if (line.startsWith('-')) {
    return 'is-removed'
  }
  if (line.startsWith('@')) {
    return 'is-hunk-header'
  }
  return 'is-context'
}
