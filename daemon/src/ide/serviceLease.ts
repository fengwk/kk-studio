import type { IdeLease } from '../generated/control_pb.js'
import type { IdeScheduler } from './service.js'
import type { ViewRecord } from './serviceTypes.js'

/** Owns expiry timers and applies only fresh proofs for the current binding. */
export function createViewLease(
  views: Map<string, ViewRecord>,
  scheduler: IdeScheduler,
  expiryMillis: number,
  isClosed: () => boolean,
  expire: (viewId: string, epoch: string) => Promise<void>,
) {
  function clearTimer(view: ViewRecord): void {
    if (view.expiryTimer !== undefined) {
      scheduler.clearTimeout(view.expiryTimer)
      view.expiryTimer = undefined
    }
  }

  function scheduleExpiry(view: ViewRecord, notBefore = 0): void {
    clearTimer(view)
    if (view.state !== 'OPEN' || view.nativeClosePending || isClosed()) {
      return
    }
    const base = view.lastProofAt ?? view.createdAt
    view.expiresAt = Math.max(base + expiryMillis, notBefore)
    const delay = Math.max(0, view.expiresAt - scheduler.now())
    view.expiryTimer = scheduler.setTimeout(() => {
      view.expiryTimer = undefined
      void expire(view.viewId, view.bindingEpoch)
    }, delay)
  }

  function apply(lease: IdeLease, roundStartedAt: number): void {
    if (isClosed() || !Number.isFinite(roundStartedAt)) {
      return
    }
    for (const binding of lease.bindings) {
      const view = views.get(binding.viewId!)
      if (
        view === undefined ||
        view.state !== 'OPEN' ||
        view.documentId !== binding.documentId ||
        view.bindingEpoch !== binding.bindingEpoch!.toString()
      ) {
        continue
      }
      if (roundStartedAt < view.createdAt) {
        continue
      }
      if (view.lastProofAt !== null && roundStartedAt <= view.lastProofAt) {
        continue
      }
      view.lastProofAt = roundStartedAt
      scheduleExpiry(view)
    }
  }

  return { clearTimer, scheduleExpiry, apply }
}
