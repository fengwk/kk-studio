export const sessionExpiry = new EventTarget()

export function notifySessionExpired() {
  sessionExpiry.dispatchEvent(new Event('expired'))
}
