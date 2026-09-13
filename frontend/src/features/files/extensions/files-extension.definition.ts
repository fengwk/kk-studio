import {
  CloudFilesInvalidationBridge,
  FilesRoute,
} from '@/features/files/extensions/files-extension'
import type { TrustedReactExtension } from '@/platform/extensions/types'

export const filesExtension: TrustedReactExtension = {
  id: 'builtin.files',
  pages: [{ id: 'files.home', path: 'files', component: FilesRoute, priority: 80 }],
  overlays: [{ id: 'files.invalidation', component: CloudFilesInvalidationBridge }],
}
