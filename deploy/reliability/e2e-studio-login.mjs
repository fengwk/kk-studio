/** Sign in as the seeded administrator of a disposable Studio e2e stack only. */
export async function loginE2eStudio(baseUrl, timeoutMs = 60_000) {
  const origin = new URL(baseUrl).origin
  let response
  try {
    response = await fetch(`${origin}/api/auth/login`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'Content-Type': 'application/json', Origin: origin },
      body: JSON.stringify({ username: 'kkstudio', password: 'kkstudio' }),
      signal: AbortSignal.timeout(timeoutMs),
    })
    await response.body?.cancel()
  } catch {
    throw new Error('Studio login failed')
  }
  if (response.status !== 200) {
    throw new Error(`Studio login failed (HTTP ${response.status})`)
  }
  return sessionCookie(response.headers)
}

export function sessionCookie(headers) {
  const value = headers.get('set-cookie')
  const cookie = value?.split(';', 1)[0]
  if (!isStudioSessionCookie(cookie) || !/;\s*HttpOnly(?:;|$)/i.test(value)) {
    throw new Error('login response did not set an HttpOnly Studio session cookie')
  }
  return cookie
}

export function isStudioSessionCookie(value) {
  return /^studio_session=[A-Za-z0-9_-]+$/.test(value ?? '')
}
