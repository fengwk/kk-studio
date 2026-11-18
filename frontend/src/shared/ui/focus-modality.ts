let installed = false

/** Use focus-visible rings only after keyboard navigation; pointer focus keeps a neutral border. */
export function installFocusModality() {
  if (installed) {
    return
  }
  installed = true
  const root = document.documentElement
  root.dataset.focusModality = 'pointer'

  document.addEventListener(
    'keydown',
    (event) => {
      if (event.key === 'Tab' || event.key.startsWith('Arrow') || event.key === 'Enter' || event.key === ' ') {
        root.dataset.focusModality = 'keyboard'
      }
    },
    true,
  )
  document.addEventListener(
    'pointerdown',
    () => {
      root.dataset.focusModality = 'pointer'
    },
    true,
  )
}
