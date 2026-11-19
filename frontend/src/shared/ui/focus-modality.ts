let installed = false

/** Suppress native text-input focus rings only for the input directly focused by a pointer. */
export function installFocusModality() {
  if (installed) {
    return
  }
  installed = true

  document.addEventListener(
    'pointerdown',
    (event) => {
      const target = event.target
      if (
        (target instanceof HTMLInputElement &&
          /^(text|search|email|url|tel|password|number)$/.test(target.type)) ||
        target instanceof HTMLTextAreaElement
      ) {
        target.dataset.pointerFocus = 'true'
      }
    },
    true,
  )
  document.addEventListener(
    'keydown',
    () => {
      document.querySelectorAll('[data-pointer-focus]').forEach((element) => {
        delete (element as HTMLElement).dataset.pointerFocus
      })
    },
    true,
  )
  document.addEventListener(
    'focusout',
    (event) => {
      const target = event.target
      if (target instanceof HTMLElement) {
        delete target.dataset.pointerFocus
      }
    },
    true,
  )
}
