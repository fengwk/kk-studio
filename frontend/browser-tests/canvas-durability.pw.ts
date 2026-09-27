import { test, expect } from '@playwright/test'
import type { CanvasCommandDTO, UUIDString } from '../src/shared/api/contracts/studio'

const NODE_ID = 'c9e3b7f1-2a4d-4e6f-8a9b-0c1d2e3f4a5b' as UUIDString

const RENAME_CMD: CanvasCommandDTO = {
  type: 'RENAME_NODE',
  nodeId: NODE_ID,
  expectedName: 'Initial Node Name',
  name: 'Updated Node Name',
}

test.describe('Canvas Command Durability Real Browser Regression', () => {
  test('(a) lost response -> persisted pending operation -> page reload replays it with ORIGINAL idempotencyKey and original commands', async ({
    page,
  }) => {
    await page.goto('/browser-tests/canvas-durability-harness.html')
    await page.locator('#harness-ready').waitFor()
    await page.evaluate(() => window.__canvasDurability.resetData())

    // 1. 设置网络异常模式并构建队列
    await page.evaluate(() => {
      window.__canvasDurability.setMode('network-error')
      window.__canvasDurability.buildQueue()
    })

    // 2. 入队重命名命令 -> 预期网络失败，但已持久化至 IndexedDB
    const enqueueRes = await page.evaluate(
      (cmd) => window.__canvasDurability.enqueue([cmd]),
      RENAME_CMD,
    )
    expect(enqueueRes.ok).toBe(false)
    expect(enqueueRes.errorMessage).toBe('network down')

    // 3. 断言 IndexedDB 中存在 1 条待确认操作，记录其 idempotencyKey 与 commands
    const persistedBefore = await page.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedBefore).toHaveLength(1)
    const originalKey = persistedBefore[0].idempotencyKey
    expect(originalKey).toBeTruthy()
    expect(persistedBefore[0].commands).toEqual([RENAME_CMD])

    // 断言网络请求曾尝试发送
    const callsBefore = await page.evaluate(() => window.__canvasDurability.calls())
    expect(callsBefore).toHaveLength(1)
    expect(callsBefore[0].idempotencyKey).toBe(originalKey)

    // 4. 模拟页面刷新 (reload)，验证 sessionStorage 保留，会话 ID 延续
    await page.reload()
    await page.locator('#harness-ready').waitFor()

    // 5. 刷新后在正常网络模式 (mode: 'ok') 下构建队列并执行 recover()
    await page.evaluate(() => {
      window.__canvasDurability.setMode('ok')
      window.__canvasDurability.buildQueue()
    })

    const recoverRes = await page.evaluate(() => window.__canvasDurability.recover())
    expect(recoverRes.replayed).toBe(1)
    expect(recoverRes.conflicted).toBe(0)
    expect(recoverRes.failed).toBe(0)

    // 6. 断言重放时的 apply 调用使用了完全相同的 idempotencyKey 与命令体
    expect(recoverRes.applyCalls).toHaveLength(1)
    expect(recoverRes.applyCalls[0].idempotencyKey).toBe(originalKey)
    expect(recoverRes.applyCalls[0].commands).toEqual([RENAME_CMD])

    // 7. 重放成功并 settle 后，IndexedDB 中该操作已被移除
    const persistedAfter = await page.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedAfter).toHaveLength(0)
  })

  test('(b) two tabs with independent sessionStorage are isolated', async ({
    context,
  }) => {
    // 1. 打开标签页 A，重置数据并入队网络失败操作
    const pageA = await context.newPage()
    await pageA.goto('/browser-tests/canvas-durability-harness.html')
    await pageA.locator('#harness-ready').waitFor()
    await pageA.evaluate(() => window.__canvasDurability.resetData())

    await pageA.evaluate(() => {
      window.__canvasDurability.setMode('network-error')
      window.__canvasDurability.buildQueue()
    })
    const sessionA = await pageA.evaluate(() => window.__canvasDurability.sessionId())

    const enqueueRes = await pageA.evaluate(
      (cmd) => window.__canvasDurability.enqueue([cmd]),
      RENAME_CMD,
    )
    expect(enqueueRes.ok).toBe(false)

    const persistedA = await pageA.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedA).toHaveLength(1)

    // 2. 在同一浏览器 context 打开独立的标签页 B（拥有独立的 sessionStorage）
    const pageB = await context.newPage()
    await pageB.goto('/browser-tests/canvas-durability-harness.html')
    await pageB.locator('#harness-ready').waitFor()

    // 绝不调用 resetData 避免清空 A 的持久化记录
    await pageB.evaluate(() => {
      window.__canvasDurability.buildQueue()
    })
    const sessionB = await pageB.evaluate(() => window.__canvasDurability.sessionId())

    // 3. 断言 session ID 相互隔离
    expect(sessionB).not.toBe(sessionA)

    // 4. 断言 B 的会话下无法检索到 A 的待确认操作 (无跨会话重放)
    const persistedB = await pageB.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedB).toHaveLength(0)

    const recoverB = await pageB.evaluate(() => window.__canvasDurability.recover())
    expect(recoverB.replayed).toBe(0)
    expect(recoverB.applyCalls).toHaveLength(0)

    // 5. 确认 A 的操作依然完整保留
    const persistedAStill = await pageA.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedAStill).toHaveLength(1)
  })

  test('(c) a duplicated tab that shares (copies) sessionStorage is force-isolated', async ({
    context,
  }) => {
    // 1. 打开标签页 A 并记录 session ID 与入队网络失败操作
    const pageA = await context.newPage()
    await pageA.goto('/browser-tests/canvas-durability-harness.html')
    await pageA.locator('#harness-ready').waitFor()
    await pageA.evaluate(() => window.__canvasDurability.resetData())

    await pageA.evaluate(() => {
      window.__canvasDurability.setMode('network-error')
      window.__canvasDurability.buildQueue()
    })
    const sessionA = await pageA.evaluate(() => window.__canvasDurability.sessionId())

    await pageA.evaluate(
      (cmd) => window.__canvasDurability.enqueue([cmd]),
      RENAME_CMD,
    )

    const persistedA = await pageA.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedA).toHaveLength(1)

    // 2. 模拟复制标签页：打开标签页 B，并在初始化队列前注入 A 的 sessionStorage
    const pageB = await context.newPage()
    await pageB.addInitScript((sId) => {
      window.sessionStorage.setItem('kkstudio.canvas.editingSessionId', sId)
    }, sessionA)

    await pageB.goto('/browser-tests/canvas-durability-harness.html')
    await pageB.locator('#harness-ready').waitFor()

    // 3. 标签页 B 构建队列并获取会话 ID
    await pageB.evaluate(() => {
      window.__canvasDurability.buildQueue()
    })
    const sessionB = await pageB.evaluate(() => window.__canvasDurability.sessionId())

    // 4. 断言：由于 A 依然活跃并持有所有权租约，B 检测到冲突并强制重新生成隔离的 sessionId
    expect(sessionB).not.toBe(sessionA)

    // 5. 断言 B 无法恢复 A 的操作
    const persistedB = await pageB.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedB).toHaveLength(0)

    const recoverB = await pageB.evaluate(() => window.__canvasDurability.recover())
    expect(recoverB.replayed).toBe(0)

    // 6. 断言 A 的会话 ID 与持久化操作不受影响
    const sessionAAfter = await pageA.evaluate(() => window.__canvasDurability.sessionId())
    expect(sessionAAfter).toBe(sessionA)

    const persistedAAfter = await pageA.evaluate(() =>
      window.__canvasDurability.listPersistedOperations(),
    )
    expect(persistedAAfter).toHaveLength(1)
  })

  test('(d) IndexedDB unavailable => persist path rejects, network request NOT sent, storage error reported', async ({
    context,
  }) => {
    // 1. 创建页面并通过 addInitScript 在加载前彻底移除/禁用 IndexedDB
    const page = await context.newPage()
    await page.addInitScript(() => {
      try {
        delete (window as unknown as { indexedDB?: unknown }).indexedDB
      } catch {
        // ignore delete failures
      }
      try {
        Object.defineProperty(window, 'indexedDB', {
          get: () => undefined,
          set: () => undefined,
          configurable: true,
        })
      } catch {
        // ignore redefine failures
      }
    })

    await page.goto('/browser-tests/canvas-durability-harness.html')
    await page.locator('#harness-ready').waitFor()

    // 2. 构建队列并入队操作
    await page.evaluate(() => {
      window.__canvasDurability.buildQueue()
    })

    const enqueueRes = await page.evaluate(
      (cmd) => window.__canvasDurability.enqueue([cmd]),
      RENAME_CMD,
    )

    // 3. 断言 enqueue 抛出 CanvasStorageUnavailableError 异常
    expect(enqueueRes.ok).toBe(false)
    expect(enqueueRes.errorName).toBe('CanvasStorageUnavailableError')

    // 4. 断言网络请求未发出 (calls 为空)
    const calls = await page.evaluate(() => window.__canvasDurability.calls())
    expect(calls).toHaveLength(0)

    // 5. 断言 onCanvasStorageError 监听器被触发并记录了异常
    const storageErrors = await page.evaluate(() =>
      window.__canvasDurability.getStorageErrors(),
    )
    expect(storageErrors.length).toBeGreaterThan(0)
    expect(storageErrors).toContain('CanvasStorageUnavailableError')
  })
})
