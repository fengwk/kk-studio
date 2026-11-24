// Reuse the product composer; resolve command mode before keyboard and send decisions.
export function composerSlashPreviewPlugin(sourceRoot) {
  const composer = `${sourceRoot}/features/thread/runtime/thread-panel/ThreadComposer.tsx`
  return {
    name: 'composer-slash-preview',
    enforce: 'pre',
    transform(source, id) {
      if (id !== composer) return
      for (const [anchor, replacement] of [
        ['import { THREAD_COMMANDS }', 'import { filterThreadCommands, THREAD_COMMANDS }'],
        ['  const slashQuery = slashQueryOf(parts)', String.raw`  const candidate = slashQueryOf(parts)
  const slashQuery = candidate != null
    && !/[/\\\s]/.test(candidate)
    && filterThreadCommands(candidate, commands).length > 0
    ? candidate
    : null`],
      ]) {
        if (source.split(anchor).length !== 2) throw new Error(`Unexpected composer source: ${anchor}`)
        source = source.replace(anchor, replacement)
      }
      return { code: source, map: null }
    },
  }
}
