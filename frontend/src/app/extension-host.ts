import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { comfyuiExtension } from '@/features/comfyui/extensions/comfyui-extension.definition'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

export function createApplicationExtensionHost() {
  const host = new ExtensionHost()
  host.register(aiExtension)
  host.register(canvasExtension)
  host.register(comfyuiExtension)
  return host
}
