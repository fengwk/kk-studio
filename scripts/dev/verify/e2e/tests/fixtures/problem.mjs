import { HttpError } from '../../lib/http.mjs'

/** Construct the same problem body that an HTTP test double would receive from Studio. */
export function problemError(status, code, detail, path) {
  return new HttpError(status, JSON.stringify({
    type: 'about:blank',
    title: 'The request failed.',
    status,
    code,
    detail,
  }), path)
}
