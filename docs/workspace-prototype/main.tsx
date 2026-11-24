import './presentation/shell.css'
import { flushSync } from 'react-dom'
import { createRoot } from 'react-dom/client'
import { setLocale } from '@/shared/i18n'
import { AuthScreen } from './workspace/AccountForms'
import { Workspace } from './workspace/Workspace'
import { ScheduleControls } from './workspace/schedule-controls'
import { ProviderLoadingPreview, SettingsPreview } from './settings/SettingsPreview'
import { startPresentation } from './presentation/runtime.js'
import { startWorkspaceController } from './workspace/controller.js'
import './workspace/appearance.css'
import './workspace/focus.css'

export function mountPrototype() {
  const host = document.getElementById('x-workspace-host')
  if (!host) return
  setLocale('zh-CN')
  document.body.classList.add('x-prototype')
  const frame = document.getElementById('ui-frame')
  const stage = frame?.parentElement
  frame?.classList.add('x-flat-frame')
  stage?.classList.add('x-flat-stage')
  stage?.parentElement?.classList.add('x-flat-shell')
  document.getElementById('ui-notes')?.classList.add('x-flat-notes')
  const { openEnvironment, openAccountMenu } = startWorkspaceController()
  startPresentation()
  if (!host.isConnected) return
  flushSync(() => {
    createRoot(host).render(<Workspace openEnvironment={openEnvironment} openAccountMenu={openAccountMenu} />)
    createRoot(document.getElementById('x-schedule-controls')!).render(<ScheduleControls />)
    createRoot(document.getElementById('x-settings-preview')!).render(<SettingsPreview />)
    createRoot(document.getElementById('x-provider-preview')!).render(<ProviderLoadingPreview />)
    createRoot(document.getElementById('x-login')!).render(<AuthScreen />)
  })
}
