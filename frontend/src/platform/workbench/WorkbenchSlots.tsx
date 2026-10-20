import { useApplicationContributions } from '@/platform/extensions/ApplicationContributionsContext'

export function OverlayHost() {
  const { dialogs, overlays } = useApplicationContributions()
  const contributions = [...dialogs, ...overlays]

  return (
    <>
      {contributions.map((contribution) => {
        const Component = contribution.component
        return <Component key={contribution.id} />
      })}
    </>
  )
}
