export type Role = 'ADMIN' | 'USER'
export type User = { id: string; username: string; role: Role; disabled: boolean }
export const builtinAdminId = 'u-kkstudio'

// Offline review only: passwords are validated by the forms, not stored or authenticated.
let snapshot = {
  userId: builtinAdminId as string | null,
  users: [
    { id: builtinAdminId, username: 'kkstudio', role: 'ADMIN', disabled: false },
    { id: 'u-chen', username: 'chen', role: 'USER', disabled: false },
  ] as User[],
}
let nextUserId = 0
const listeners = new Set<() => void>()

export const getSession = () => snapshot
export function subscribeSession(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function publish(next: typeof snapshot) {
  snapshot = next
  listeners.forEach((listener) => listener())
}

export function setSessionUser(userId: string | null) {
  if (userId !== null && !snapshot.users.some((user) => user.id === userId && !user.disabled)) return false
  publish({ ...snapshot, userId })
  return true
}

export function createUser(username: string, role: Role) {
  const user = { id: `u-${++nextUserId}`, username, role, disabled: false }
  publish({ ...snapshot, users: [...snapshot.users, user] })
}

export const enabledAdminRequired = '至少保留一名管理员。'
const isEnabledAdmin = (user: User) => user.role === 'ADMIN' && !user.disabled

export function lastAdminField(user: User) {
  if (isEnabledAdmin(user) || snapshot.users.some((entry) => entry.id !== user.id && isEnabledAdmin(entry))) return null
  return user.disabled ? 'status' : 'role'
}

export function updateUser(user: User) {
  const invalid = lastAdminField(user)
  if (invalid) return invalid
  publish({
    userId: user.disabled && snapshot.userId === user.id ? null : snapshot.userId,
    users: snapshot.users.map((entry) => entry.id === user.id ? user : entry),
  })
  return null
}

export function usernameError(username: string, userId?: string) {
  if (!/^[a-z0-9][a-z0-9._-]{0,31}(?![\s\S])/.test(username)) {
    return '用户名只能包含小写字母、数字和 . _ -，并以字母或数字开头。'
  }
  return snapshot.users.some((user) => user.id !== userId && user.username === username) ? '用户名已存在。' : ''
}

export function passwordError(password: string) {
  const length = Array.from(password).length
  if (length < 8) return '密码至少 8 个字符。'
  if (length > 128) return '密码最多 128 个字符。'
  return ''
}

export function reviewUI() {
  return (window as Window & { htmlviewUI?: { go: (screen: string) => void; toast: (text: string) => void } }).htmlviewUI!
}
