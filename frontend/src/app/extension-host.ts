import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { projectsExtension } from '@/features/projects/extensions/projects-extension.definition'
import { settingsExtension } from '@/features/settings/settings-extension'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

export function createApplicationExtensionHost() {
  const host = new ExtensionHost()
  host.register(aiExtension)
  host.register(canvasExtension)
  host.register(projectsExtension)
  host.register(settingsExtension)
  return host
}
