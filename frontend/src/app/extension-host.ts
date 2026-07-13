import { aiExtension } from '@/features/ai/extensions/ai-extension'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

export function createApplicationExtensionHost() {
  const host = new ExtensionHost()
  host.register(aiExtension)
  return host
}
