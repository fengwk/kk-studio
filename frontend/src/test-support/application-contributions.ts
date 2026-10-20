import type { ApplicationContributions, ToolRendererContribution } from '@/platform/extensions/types'

/** 为测试注入与生产同形的只读 contribution fixture，不复制应用组合根。 */
export function makeApplicationContributions({
  pages = [],
  dialogs = [],
  overlays = [],
  toolRenderers = new Map<string, ToolRendererContribution>(),
}: Partial<ApplicationContributions> = {}): ApplicationContributions {
  return { pages, dialogs, overlays, toolRenderers }
}
