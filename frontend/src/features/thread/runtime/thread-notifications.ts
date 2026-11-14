export type BrowserNotificationPermission = NotificationPermission | 'unsupported'

export function browserNotificationPermission(): BrowserNotificationPermission {
  return typeof window !== 'undefined' && 'Notification' in window
    ? window.Notification.permission
    : 'unsupported'
}

export async function requestBrowserNotificationPermission(): Promise<BrowserNotificationPermission> {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return 'unsupported'
  }
  if (window.Notification.permission !== 'default') {
    return window.Notification.permission
  }
  try {
    return await window.Notification.requestPermission()
  } catch {
    return window.Notification.permission
  }
}
