import {
  useCallback,
  useRef,
  useState,
  type PropsWithChildren,
} from 'react'
import {
  useBlocker,
  type BlockerFunction,
} from 'react-router'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import {
  IdeLifecycleContext,
  type IdeLifecycleItem,
} from '@/features/ai/ide/ide-lifecycle-context'

interface RegisteredEntry extends IdeLifecycleItem {
  token: symbol
}

export function IdeLifecycleProvider({ children }: PropsWithChildren) {
  const { t } = useI18n()
  const [entries, setEntries] = useState<Map<string, RegisteredEntry>>(() => new Map())

  const [isClosingViews, setIsClosingViews] = useState(false)
  const [routeLeaveError, setRouteLeaveError] = useState<string | null>(null)
  const closingRef = useRef(false)

  const registerIdeView = useCallback((item: IdeLifecycleItem) => {
    const token = Symbol('ide-view-token')
    setEntries((prev) => {
      const next = new Map(prev)
      next.set(item.viewId, { ...item, token })
      return next
    })

    return () => {
      setEntries((prev) => {
        const current = prev.get(item.viewId)
        // 使用 token identity 确保旧 effect 清理不会删除同 viewId 的新注册
        if (current?.token === token) {
          const next = new Map(prev)
          next.delete(item.viewId)
          return next
        }
        return prev
      })
    }
  }, [])

  const isPaneActive = useCallback((paneId: string) => {
    for (const item of entries.values()) {
      if (item.paneId === paneId && item.active) {
        return true
      }
    }
    return false
  }, [entries])

  const hasActiveIdeViews = Array.from(entries.values()).some((i) => i.active)

  const shouldBlock = useCallback<BlockerFunction>(
    ({ currentLocation, nextLocation }) => {
      if (!hasActiveIdeViews) {
        return false
      }
      if (
        currentLocation.pathname === nextLocation.pathname
        && currentLocation.search === nextLocation.search
        && currentLocation.hash === nextLocation.hash
      ) {
        return false
      }
      return true
    },
    [hasActiveIdeViews],
  )

  const blocker = useBlocker(shouldBlock)

  const handleCancelLeave = useCallback(() => {
    // 关闭期间加锁：禁止取消以防旧 blocker 被 reset 后又被 proceed
    if (closingRef.current) {
      return
    }
    setRouteLeaveError(null)
    if (blocker.state === 'blocked') {
      blocker.reset()
    }
  }, [blocker])

  const handleConfirmLeave = useCallback(async () => {
    // closing 锁防重入
    if (closingRef.current) {
      return
    }
    closingRef.current = true
    setIsClosingViews(true)
    setRouteLeaveError(null)

    const activeItems = Array.from(entries.values()).filter((i) => i.active)
    let allSuccess = true
    for (const item of activeItems) {
      try {
        const success = await item.requestClose()
        if (!success) {
          allSuccess = false
          break
        }
      } catch {
        allSuccess = false
        break
      }
    }

    closingRef.current = false
    setIsClosingViews(false)

    if (allSuccess) {
      if (blocker.state === 'blocked') {
        blocker.proceed()
      }
    } else {
      // 存在未保存修改或关闭失败：保持原有 DOM 与路由位置，绝不 proceed
      setRouteLeaveError(t('ai.ide.dirtyCloseWarning'))
    }
  }, [entries, blocker, t])

  return (
    <IdeLifecycleContext.Provider
      value={{
        registerIdeView,
        isPaneActive,
        hasActiveIdeViews,
      }}
    >
      {children}
      {blocker.state === 'blocked' ? (
        <Dialog
          title={t('ai.ide.leaveConfirmTitle')}
          onClose={() => {
            if (!closingRef.current) {
              handleCancelLeave()
            }
          }}
          role="alertdialog"
        >
          <div className="ide-confirm-dialog-content">
            <p>{t('ai.ide.leaveConfirmMessage')}</p>
            {routeLeaveError ? (
              <div className="ide-panel-error-banner" data-testid="ide-route-leave-error">
                {routeLeaveError}
              </div>
            ) : null}
            <div className="ide-confirm-dialog-actions">
              <Button
                variant="ghost"
                size="compact"
                onClick={handleCancelLeave}
                disabled={isClosingViews}
              >
                {t('ai.ide.cancel')}
              </Button>
              <Button
                variant="danger"
                size="compact"
                onClick={handleConfirmLeave}
                loading={isClosingViews}
                disabled={isClosingViews}
                data-testid="ide-confirm-leave-btn"
              >
                {t('ai.ide.confirm')}
              </Button>
            </div>
          </div>
        </Dialog>
      ) : null}
    </IdeLifecycleContext.Provider>
  )
}
