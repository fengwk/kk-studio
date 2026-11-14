import { useLayoutEffect, useState } from 'react'
import type { SecretUpdate, SecretUpdateOperation } from '@/shared/api/generated/contracts'

interface PluginSecretDraftOptions {
  configuredSecretNames: string[]
  isDeepLink: boolean
}

/**
 * 秘密字段的编辑草稿。
 *
 * <p>秘密值只存在于本组件内存：提交、关闭、卸载都会立即清空，绝不进入缓存或日志。
 * `KEEP`/`CLEAR` 之外的操作必须带值；`CLEAR` 还要求显式确认，缺一不可。
 */
export function usePluginSecretDraft({
  configuredSecretNames,
  isDeepLink,
}: PluginSecretDraftOptions) {
  const [secretOperations, setSecretOperations] = useState<
    Record<string, SecretUpdateOperation>
  >(() => {
    const initial: Record<string, SecretUpdateOperation> = Object.create(null)
    for (const key of configuredSecretNames) {
      initial[key] = 'KEEP'
    }
    return initial
  })
  const [secretValues, setSecretValues] = useState<Record<string, string>>({})
  const [clearConfirmed, setClearConfirmed] = useState<Record<string, boolean>>({})

  useLayoutEffect(() => {
    return () => {
      // 卸载时彻底清空内存中的秘密值，不进入全局缓存
      setSecretValues({})
    }
  }, [])

  function clearSecretValues() {
    setSecretValues({})
  }

  function changeOperation(key: string, operation: SecretUpdateOperation) {
    setSecretOperations((prev) => ({ ...prev, [key]: operation }))
    setSecretValues((prev) => {
      const remaining = { ...prev }
      delete remaining[key]
      return remaining
    })
    setClearConfirmed((prev) => {
      const remaining = { ...prev }
      delete remaining[key]
      return remaining
    })
  }

  function changeSecretValue(key: string, value: string) {
    setSecretValues((prev) => ({ ...prev, [key]: value }))
  }

  function changeClearConfirmed(key: string, confirmed: boolean) {
    setClearConfirmed((prev) => ({ ...prev, [key]: confirmed }))
  }

  /**
   * 组装提交用的 secretPatch。
   *
   * <p>返回错误时不提交任何秘密，避免部分字段按旧值落库。
   */
  function buildSecretPatch():
    | { ok: true; patch: Record<string, SecretUpdate> }
    | { ok: false; reason: 'mustConfirmClear' | 'secretRequired'; key: string } {
    const patch: Record<string, SecretUpdate> = Object.create(null)
    for (const [key, op] of Object.entries(secretOperations)) {
      if (isDeepLink || op === 'KEEP') {
        patch[key] = { operation: 'KEEP' }
      } else if (op === 'CLEAR') {
        // 在提交 handler 中严格检查确认勾选，不仅依靠 disabled
        if (!Object.hasOwn(clearConfirmed, key) || clearConfirmed[key] !== true) {
          return { ok: false, reason: 'mustConfirmClear', key }
        }
        patch[key] = { operation: 'CLEAR' }
      } else if (op === 'SET') {
        // SET 严格保留用户原值，严禁静默 trim，无 padding
        const value = Object.hasOwn(secretValues, key) ? secretValues[key] : undefined
        if (value === undefined || value === '') {
          return { ok: false, reason: 'secretRequired', key }
        }
        patch[key] = { operation: 'SET', value }
      }
    }
    return { ok: true, patch }
  }

  return {
    secretOperations,
    secretValues,
    clearConfirmed,
    clearSecretValues,
    changeOperation,
    changeSecretValue,
    changeClearConfirmed,
    buildSecretPatch,
  }
}
