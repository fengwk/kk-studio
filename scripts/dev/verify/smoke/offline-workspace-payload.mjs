/** Build the initial user message accepted by Chat Space session creation. */
export function buildOfflineSessionRequest({ marker, yoloEnabled = false, ...unexpected }) {
  if (Object.keys(unexpected).length > 0) {
    throw new TypeError(`unexpected option: ${Object.keys(unexpected).join(', ')}`)
  }
  return {
    message: [{ type: 'TEXT', text: marker }],
    yoloEnabled,
  }
}
