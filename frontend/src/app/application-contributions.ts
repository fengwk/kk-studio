import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { projectsExtension } from '@/features/projects/extensions/projects-extension.definition'
import { settingsExtension } from '@/features/settings/settings-extension'
import type {
  ApplicationContributions,
  DialogContribution,
  OverlayContribution,
  PageContribution,
  ToolRendererContribution,
  TrustedReactExtension,
} from '@/platform/extensions/types'

/**
 * 在唯一应用组合边界把编译期扩展压成只读表。
 * 重复 id 是组装错误，直接拒绝；这里没有运行时注册、卸载或优先级覆盖。
 */
export function composeApplicationContributions(
  extensions: readonly TrustedReactExtension[],
): ApplicationContributions {
  const pages: PageContribution[] = []
  const dialogs: DialogContribution[] = []
  const overlays: OverlayContribution[] = []
  const toolRenderers = new Map<string, ToolRendererContribution>()
  const extensionIds = new Set<string>()

  for (const extension of extensions) {
    if (extensionIds.has(extension.id)) {
      throw new Error(`Duplicate extension id: ${extension.id}`)
    }
    extensionIds.add(extension.id)
    appendUnique('pages', pages, extension.pages)
    appendUnique('dialogs', dialogs, extension.dialogs)
    appendUnique('overlays', overlays, extension.overlays)
    for (const renderer of extension.toolRenderers ?? []) {
      if (toolRenderers.has(renderer.id)) {
        throw new Error(`Duplicate contribution id in toolRenderers: ${renderer.id}`)
      }
      toolRenderers.set(renderer.id, renderer)
    }
  }

  return {
    pages: Object.freeze(pages),
    dialogs: Object.freeze(dialogs),
    overlays: Object.freeze(overlays),
    toolRenderers: readonlyMap(toolRenderers),
  }
}

/** 只暴露 ReadonlyMap 能力；返回对象没有 set/delete/clear，运行时也不能被外部修改。 */
function readonlyMap<K, V>(source: Map<K, V>): ReadonlyMap<K, V> {
  const view: ReadonlyMap<K, V> = Object.freeze({
    get size() { return source.size },
    get: (key: K) => source.get(key),
    has: (key: K) => source.has(key),
    forEach: (callback: (value: V, key: K, map: ReadonlyMap<K, V>) => void, thisArg?: unknown) =>
      source.forEach((value, key) => callback.call(thisArg, value, key, view)),
    keys: () => source.keys(),
    values: () => source.values(),
    entries: () => source.entries(),
    [Symbol.iterator]: () => source.entries(),
  })
  return view
}

function appendUnique<T extends { id: string }>(
  kind: string,
  target: T[],
  contributions: readonly T[] | undefined,
) {
  const ids = new Set(target.map((contribution) => contribution.id))
  for (const contribution of contributions ?? []) {
    if (ids.has(contribution.id)) {
      throw new Error(`Duplicate contribution id in ${kind}: ${contribution.id}`)
    }
    ids.add(contribution.id)
    target.push(contribution)
  }
}

export const applicationContributions = composeApplicationContributions([
  aiExtension,
  canvasExtension,
  projectsExtension,
  settingsExtension,
])
