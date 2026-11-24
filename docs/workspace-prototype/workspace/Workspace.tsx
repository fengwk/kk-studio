import { useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { MemoryRouter } from 'react-router'
import { Settings } from 'lucide-react'
import kkLogo from '../../../frontend/public/favicon.svg?inline'
import '@/styles.css'
import '@/features/chat/chat-workspace.css'
import '@/features/thread/runtime/interactions/interaction-cards.css'
import '@/features/thread/runtime/interactions/interaction-card-content.css'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { CanvasResourceView } from '../canvas/CanvasResource'
import { EnvironmentList, VsCodeWorkbench } from './EnvironmentViews'
import { ThreadPane } from './ThreadPane'
import { readWorkspace, subscribeToWorkspace, type EnvironmentView } from './projection'
import { ChangePasswordDialog } from './AccountForms'
import { getSession, subscribeSession } from './session'
import { UserAvatar } from './UserAvatar'

const environmentRenderers = { vscode: VsCodeWorkbench } satisfies Record<EnvironmentView, typeof VsCodeWorkbench>

export function Workspace({ openEnvironment, openAccountMenu }: {
  openEnvironment: (view: EnvironmentView, id: string, anchor?: HTMLButtonElement) => void
  openAccountMenu: (anchor: HTMLButtonElement, changePassword: () => void) => void
}) {
  const { userId, users } = useSyncExternalStore(subscribeSession, getSession)
  const user = users.find((entry) => entry.id === userId)
  const [changingPassword, setChangingPassword] = useState(false)
  const snapshot = useSyncExternalStore(subscribeToWorkspace, readWorkspace)
  const brand = <img className="x-kk-mark" src={kkLogo} alt="KK Studio" width={24} height={24} />
  const openSettings = () => {
    if (snapshot?.narrow) document.querySelector<HTMLButtonElement>('#x-side [data-action="drawer-close"]')?.click()
    const { htmlviewUI } = window as Window & { htmlviewUI?: { go: (id: string) => void } }
    htmlviewUI?.go('scr-settings')
  }
  const settings = <IconButton label="设置" title="设置" onClick={openSettings}><Settings aria-hidden="true" /></IconButton>
  const avatar = user && <UserAvatar username={user.username} onClick={(anchor) => openAccountMenu(anchor, () => setChangingPassword(true))} />
  return <MemoryRouter>
    {['x-side-brand', 'x-rail-brand'].map((id) => createPortal(brand, document.getElementById(id)!, id))}
    {createPortal(<EnvironmentList open={openEnvironment} />, document.getElementById('x-environments')!)}
    {createPortal(<>{avatar}{settings}</>, document.getElementById('x-side-footer')!)}
    {createPortal(<>{settings}{avatar}</>, document.getElementById('x-rail-footer')!)}
    {changingPassword && <ChangePasswordDialog onClose={() => setChangingPassword(false)} />}
    {userId && snapshot?.panes.map((pane) => {
      if (pane.kind === 'environment') {
        const RenderView = environmentRenderers[pane.view]
        return createPortal(<RenderView key={`${userId}:${pane.targetKey}`} />, pane.node, pane.id)
      }
      const content = pane.kind === 'canvas'
        ? <CanvasResourceView key={`${userId}:${pane.targetKey}`} canvas={pane.canvas} />
        : <ThreadPane key={`${userId}:${pane.targetKey}`} pane={pane} openIde={(id) => openEnvironment('vscode', id)} />
      return createPortal(content, pane.node, pane.id)
    })}
  </MemoryRouter>
}
