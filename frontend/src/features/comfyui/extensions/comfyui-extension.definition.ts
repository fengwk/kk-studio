import {
  ComfyuiRoute,
  ComfyuiDeleteDialog,
  ComfyuiWorkflowEditorDialog,
} from '@/features/comfyui/extensions/comfyui-extension'
import type { TrustedReactExtension } from '@/platform/extensions/types'

export const comfyuiExtension: TrustedReactExtension = {
  id: 'builtin.comfyui',
  pages: [
    {
      id: 'ai.comfyui',
      path: 'comfyui',
      component: ComfyuiRoute,
      priority: 100,
    },
  ],
  dialogs: [
    { id: 'ai.comfyui-editor', component: ComfyuiWorkflowEditorDialog },
    { id: 'ai.comfyui-delete', component: ComfyuiDeleteDialog },
  ],
}
