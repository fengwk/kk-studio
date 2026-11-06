/** A fake `request` recorder for testing HTTP dispatch and payload transmission. */
export function makeMockHttp({ rows, putError, getError } = {}) {
  const calls = []
  const request = async (url, { body, headers } = {}) => {
    const payload = body !== undefined ? JSON.parse(body) : null
    calls.push([url, payload, headers])
    if (body === undefined) {
      if (getError) {
        throw getError
      }
      return rows ?? []
    }
    if (putError) {
      throw putError
    }
    return { credential: { configured: true }, baseUrl: payload.baseUrl }
  }
  return { request, calls }
}
