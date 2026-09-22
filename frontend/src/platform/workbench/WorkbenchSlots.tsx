import { useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'

export function OverlayHost() {
  const host = useExtensionHostSnapshot()
  const contributions = [...host.dialogs.list(), ...host.overlays.list()]

  return (
    <>
      {contributions.map((contribution) => {
        const Component = contribution.component
        return <Component key={contribution.id} />
      })}
    </>
  )
}
