/**
 * Generic RFC 3986 URL splitting for dev tooling input validation.
 *
 * The WHATWG `URL` parser rewrites special schemes (for example it accepts `http:///host` and
 * normalizes it to a valid origin), which would hide the malformed values these validators exist
 * to reject. This splitter only separates the components and never repairs the input.
 */

/**
 * Split a URL into `{ scheme, netloc, hostname, port, path, query, fragment }`.
 * `port` is undefined when absent and may be a non-numeric string; callers validate it.
 */
export function splitUrl(raw) {
  const match = /^(?:([A-Za-z][A-Za-z0-9+.-]*):)?(?:\/\/([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/s.exec(raw)
  const [, scheme = '', netloc = '', path = '', query = '', fragment = ''] = match
  const hostPort = netloc.slice(netloc.lastIndexOf('@') + 1)
  const bracketed = /^\[([^\]]*)\](?::(.*))?$/.exec(hostPort)
  const colon = hostPort.lastIndexOf(':')
  return {
    scheme,
    netloc,
    hostname: bracketed ? bracketed[1] : colon >= 0 ? hostPort.slice(0, colon) : hostPort,
    port: bracketed ? bracketed[2] : colon >= 0 ? hostPort.slice(colon + 1) : undefined,
    path,
    query,
    fragment,
  }
}

/** True when `port` is absent, empty or a decimal number in 0..65535. */
export function isValidPort(port) {
  return port === undefined || port === '' || (/^\d+$/.test(port) && Number(port) <= 65535)
}
