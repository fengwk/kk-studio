import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

export function createApplicationExtensionHost() {
  const host = new ExtensionHost()
  host.register(aiExtension)
  host.register(canvasExtension)
  return host
}
