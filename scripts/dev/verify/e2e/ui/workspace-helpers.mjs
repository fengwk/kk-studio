import {
  archiveWorkspaceSpace,
  deleteWorkspaceSession,
  stopThreadForCleanup,
  getThreadSnapshot,
} from '../lib/harness.mjs'
import { encodePathTail } from '../lib/fixtures.mjs'
import { assert, HttpError, sleep } from '../lib/http.mjs'

export const COMPOSER_DRAFT_STORAGE_PREFIX = 'kk-studio.composer-draft:'

export const UI_LABELS = {
  composerInput: /^(?:Send a message to AI|给 AI 发送消息)$/,
  sendButton: /^(?:Send message|发送消息)$/,
  commandPalette: /^(?:Command palette|命令表)$/,
  permissionButton: /^(?:Permission mode|权限模式)$/,
  permissionOptions: /^(?:Permission options|权限选项)$/,
  permissionDefault: /^(?:Default|默认)$/,
  permissionYolo: /^YOLO$/,
  environmentButton: /^(?:Environment|环境)$/,
  environmentOptions: /^(?:Environment options|环境选项)$/,
  environmentNone: /^(?:None|无)$/,
  modelButton: /^(?:Model and Variant|模型与预设|Model & Variant|Model 与 Variant)$/,
  modelOptions: /^(?:Model options|模型选项)$/,
  variantOptions: /^(?:Variant options|预设选项)$/,
  closeDebugButton: /^(?:Close Debug|关闭 Debug)$/,
  backToParentButton: /^(?:Back|返回)$/,
  splitRightButton: /^(?:Split right|向右拆分)$/,
  threadStatusFooter: /^(?:Thread status|Thread 状态)$/,
  settingsRouteAiRuntime: /^(?:AI Runtime|AI 运行时)$/,
  settingsSave: /^(?:Save|保存)$/,
  settingsConflictDialog: /^(?:Configuration conflict|配置冲突|Settings conflict|设置冲突|Conflict|冲突)$/,
  settingsReloadLatest: /^(?:Reload latest|刷新最新)$/,
  notificationSwitch: /^(?:Browser notifications|浏览器通知)$/,
}

export function composerDraftStorageKey(scope) {
  return `${COMPOSER_DRAFT_STORAGE_PREFIX}${scope}`
}

export function isExitUnconfirmedConflict(error) {
  if (!(error instanceof HttpError) || error.status !== 409) {
    return false
  }
  let problem = null
  try {
    problem = JSON.parse(error.body)
  } catch {
    problem = null
  }
  return problem?.code === 'conflict' && problem?.reason === 'EXIT_UNCONFIRMED'
}

export async function resolveCatalogTarget(apiCtx) {
  const agentsResponse = await apiCtx.call('GET', '/api/platform/agents')
  const modelsResponse = await apiCtx.call('GET', '/api/platform/models')
  assert(
    agentsResponse.status === 200 && modelsResponse.status === 200,
    `catalog lookup failed: ${JSON.stringify({ agents: agentsResponse, models: modelsResponse })}`,
  )
  const agents = Array.isArray(agentsResponse.json) ? agentsResponse.json : []
  const models = Array.isArray(modelsResponse.json) ? modelsResponse.json : []
  for (const agent of agents) {
    if (agent.execution?.driver !== 'builtin') continue
    const { modelName, variant } = agent.execution.settings || {}
    if (!modelName || (variant !== null && typeof variant !== 'string')) continue
    const model = models.find((candidate) => candidate.name === modelName)
    if (model) {
      return {
        agent,
        model: { modelName, variant },
      }
    }
  }
  throw new Error(
    `no resolvable Agent/Model in current Catalog: ${JSON.stringify({ agents, models })}`,
  )
}

export async function deleteCatalogResource(apiCtx, requestPath) {
  const response = await apiCtx.call('DELETE', requestPath)
  if (response.status === 404) {
    assert(
      response.json?.code === 'not_found',
      `delete catalog resource: ${JSON.stringify(response)}`,
    )
    return
  }
  assert(
    response.status === 200 || response.status === 204,
    `delete catalog resource: ${JSON.stringify(response)}`,
  )
}

async function cleanupStep(errors, label, action) {
  try {
    await action()
  } catch (error) {
    errors.push(`${label}: ${error?.message || error}`)
  }
}

export async function cleanupWorkspaceFixtureState(state) {
  const errors = []
  const threadIds = [...new Set([
    ...(state.threadIds ?? []),
    ...(state.threadId ? [state.threadId] : []),
  ])]
  for (const threadId of threadIds) {
    await cleanupStep(errors, `stop thread ${threadId}`, async () => {
      await stopThreadForCleanup(state.apiCtx, threadId)
    })
  }
  await cleanupStep(errors, 'hold provider', async () => state.mock?.close())
  const sessionIds = [...new Set([
    ...(state.sessionIds ?? []),
    ...(state.sessionId ? [state.sessionId] : []),
  ])]
  if (state.space?.id) {
    for (const sessionId of sessionIds) {
      await cleanupStep(errors, `delete session ${sessionId}`, async () => {
        await deleteWorkspaceSession(state.apiCtx, state.space.id, sessionId)
      })
    }
    await cleanupStep(errors, `archive space ${state.space.id}`, async () => {
      await archiveWorkspaceSpace(state.apiCtx, state.space.id)
    })
  }
  const agents = [...(state.agents ?? []), ...(state.parentAgent ? [state.parentAgent] : []), ...(state.childAgent ? [state.childAgent] : []), ...(state.agent ? [state.agent] : [])]
  for (const agent of agents) {
    if (!agent?.name) continue
    await cleanupStep(errors, `agent ${agent.name}`, async () => {
      await deleteCatalogResource(
        state.apiCtx,
        `/api/platform/agents/${encodeURIComponent(agent.name)}`
          + `?expectedVersion=${encodeURIComponent(agent.version)}`,
      )
    })
  }
  const models = [...(state.models ?? []), ...(state.model ? [state.model] : [])]
  for (const model of models) {
    if (!model?.name) continue
    await cleanupStep(errors, `model ${model.name}`, async () => {
      await deleteCatalogResource(
        state.apiCtx,
        `/api/platform/models/${encodePathTail(model.name)}`
          + `?expectedVersion=${encodeURIComponent(model.version)}`,
      )
    })
  }
  const providers = [...(state.providers ?? []), ...(state.provider ? [state.provider] : [])]
  for (const provider of providers) {
    if (!provider?.name) continue
    await cleanupStep(errors, `provider ${provider.name}`, async () => {
      await deleteCatalogResource(
        state.apiCtx,
        `/api/platform/providers/${encodeURIComponent(provider.name)}`
          + `?expectedVersion=${encodeURIComponent(provider.version)}`,
      )
    })
  }
  const environments = [
    ...(state.environments ?? []),
    ...(state.otherEnvironment ? [state.otherEnvironment] : []),
    ...(state.environment ? [state.environment] : []),
  ]
  for (const environment of environments) {
    if (!environment?.id) continue
    await cleanupStep(errors, `environment ${environment.id}`, async () => {
      await deleteCatalogResource(
        state.apiCtx,
        `/api/platform/environments/${encodeURIComponent(environment.id)}`
          + `?expectedVersion=${encodeURIComponent(environment.version)}`,
      )
    })
  }
  if (errors.length > 0) {
    throw new Error(`workspace UI fixture cleanup failed: ${errors.join(' | ')}`)
  }
}

export async function clearWorkspaceBrowserState(page, fixture) {
  const spaceId = fixture?.space?.id ?? null
  const draftScopes = fixture?.draftScopes ?? []
  await page.evaluate(({ targetSpaceId, scopes, prefix }) => {
    for (let index = localStorage.length - 1; index >= 0; index -= 1) {
      const key = localStorage.key(index)
      if (!key) continue
      if (targetSpaceId && key.startsWith('workspace-panes:') && key.includes(targetSpaceId)) {
        localStorage.removeItem(key)
      }
      if (targetSpaceId && key.startsWith('workspace-sidebar:') && key.includes(targetSpaceId)) {
        localStorage.removeItem(key)
      }
    }
    for (const scope of scopes) {
      localStorage.removeItem(`${prefix}${scope}`)
    }
  }, { targetSpaceId: spaceId, scopes: draftScopes, prefix: COMPOSER_DRAFT_STORAGE_PREFIX }).catch(() => undefined)
}

export async function withUiFixture(page, createFixture, test) {
  let fixture = null
  let primaryError = null
  try {
    fixture = await createFixture()
    await test(fixture)
  } catch (error) {
    primaryError = error
  } finally {
    if (fixture) {
      try {
        try {
          if (page.url() !== 'about:blank') await clearWorkspaceBrowserState(page, fixture)
        } finally {
          await fixture.dispose()
        }
      } catch (cleanupError) {
        if (primaryError == null) {
          primaryError = cleanupError
        } else {
          primaryError.message =
            `${primaryError.message} | cleanup: ${cleanupError?.message || cleanupError}`
        }
      }
    }
  }
  if (primaryError) throw primaryError
}

export async function waitForComposer(page, scope = page) {
  const composer = scope.getByRole('textbox', { name: UI_LABELS.composerInput })
  await composer.waitFor({ state: 'visible', timeout: 15_000 })
  await composer.evaluate((element) => element.getAttribute('contenteditable') === 'true').then(async (ready) => {
    if (ready) return
    await page.waitForFunction(
      () => document.querySelector('[data-pane-active] .composer-editor, .composer-editor')
        ?.getAttribute('contenteditable') === 'true',
      undefined,
      { timeout: 15_000 },
    )
  })
  return composer
}

export async function bindThreadComposer(page, goto, fixture) {
  assert(fixture.space?.id, 'fixture lacks space.id')
  assert(fixture.threadId, `fixture lacks threadId for space ${fixture.space?.id}`)
  if (page.url() !== 'about:blank') {
    await clearWorkspaceBrowserState(page, fixture)
  }
  await goto(`/chat-spaces/${encodeURIComponent(fixture.space.id)}?thread=${encodeURIComponent(fixture.threadId)}`)
  await page.locator('.workspace-shell').waitFor({ state: 'visible', timeout: 15_000 })
  await page.waitForFunction(
    () => !new URLSearchParams(window.location.search).has('thread'),
    undefined,
    { timeout: 15_000 },
  )
  const activePane = page.locator('[data-pane-active]').first()
  await activePane.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 15_000 })
  return waitForComposer(page, activePane)
}

export async function bindBlankComposer(page, goto, fixture) {
  assert(fixture.space?.id, 'fixture lacks space.id')
  if (page.url() !== 'about:blank') {
    await clearWorkspaceBrowserState(page, fixture)
  }
  await goto(`/chat-spaces/${encodeURIComponent(fixture.space.id)}`)
  await page.locator('.workspace-shell').waitFor({ state: 'visible', timeout: 15_000 })
  const activePane = page.locator('[data-pane-active]').first()
  const composer = await waitForComposer(page, activePane)
  const draftId = await page.evaluate((spaceId) => {
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (!key || !key.startsWith('workspace-panes:') || !key.includes(spaceId)) continue
      const parsed = JSON.parse(localStorage.getItem(key) || 'null')
      const focused = parsed?.bindings?.[parsed?.focusedPaneId]
      if (focused?.kind === 'draft' && typeof focused.draftId === 'string') {
        return focused.draftId
      }
    }
    return null
  }, fixture.space.id)
  assert(draftId, `active blank pane in space ${fixture.space.id} has no draftId in workspace-panes state`)
  const scope = `workspace-draft:${draftId}`
  fixture.draftScopes = [...new Set([...(fixture.draftScopes ?? []), scope])]
  return {
    composer,
    draftId,
    scope,
    draftKey: composerDraftStorageKey(scope),
  }
}

export async function bindSplitThreads(page, goto, spaceId, firstThreadId, secondSessionId, secondThreadId) {
  await goto(`/chat-spaces/${encodeURIComponent(spaceId)}?thread=${encodeURIComponent(firstThreadId)}`)
  await page.locator('.workspace-shell').waitFor({ state: 'visible', timeout: 15_000 })
  await page.waitForFunction(
    () => !new URLSearchParams(window.location.search).has('thread'),
    undefined,
    { timeout: 15_000 },
  )
  const firstPane = page.locator('[data-pane-active]').first()
  await firstPane.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 15_000 })
  await waitForComposer(page, firstPane)
  const pane1Id = await firstPane.getAttribute('data-pane-id')
  assert(pane1Id, 'first pane missing data-pane-id')

  await firstPane.locator('[data-pane-header]').getByRole('button', { name: UI_LABELS.splitRightButton }).click()
  await page.waitForFunction(
    () => document.querySelectorAll('[data-pane-id]').length === 2,
    undefined,
    { timeout: 10_000 },
  )
  const paneIds = await page.evaluate(() =>
    Array.from(document.querySelectorAll('[data-pane-id]')).map((el) => el.getAttribute('data-pane-id')),
  )
  const pane2Id = paneIds.find((id) => id && id !== pane1Id)
  assert(pane2Id, `split did not create a distinct second pane: ${JSON.stringify(paneIds)}`)
  await page.waitForFunction(
    (expectedId) => document.querySelector('[data-pane-active]')?.getAttribute('data-pane-id') === expectedId,
    pane2Id,
    { timeout: 10_000 },
  )

  const sessionRow = page.locator(`[data-tree-row="${secondSessionId}"]`)
  await sessionRow.waitFor({ state: 'visible', timeout: 10_000 })
  if ((await sessionRow.getAttribute('aria-expanded')) !== 'true') {
    await sessionRow.click()
  }
  const threadRow = page.locator(`[data-tree-row="${secondThreadId}"]`)
  await threadRow.waitFor({ state: 'visible', timeout: 10_000 })
  await threadRow.click()

  const pane1 = page.locator(`[data-pane-id="${pane1Id}"]`)
  const pane2 = page.locator(`[data-pane-id="${pane2Id}"]`)
  await pane2.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 15_000 })
  await waitForComposer(page, pane1)
  await waitForComposer(page, pane2)
  return { pane1, pane2, pane1Id, pane2Id }
}

export async function expectComposerText(page, expected, scopeSelector = '[data-pane-active] .composer-editor, .composer-editor') {
  try {
    await page.waitForFunction(
      ({ text, selector }) => {
        const editor = document.querySelector(selector)
        return editor?.textContent === text
      },
      { text: expected, selector: scopeSelector },
      { timeout: 10_000 },
    )
  } catch {
    const actual = await page.evaluate(() => {
      const editors = Array.from(document.querySelectorAll('.composer-editor'))
      return editors.map((editor) => ({
        text: editor.textContent,
        html: editor.innerHTML,
      }))
    })
    throw new Error(
      `composer text mismatch: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    )
  }
}

export async function expectStorage(page, key, expected) {
  if (typeof key === 'object') {
    await expectThreadDraft(page, key.threadId, expected)
    return
  }
  let actual = null
  try {
    if (expected === null) {
      await page.waitForFunction(
        (storageKey) => localStorage.getItem(storageKey) === null,
        key,
        { timeout: 10_000 },
      )
      return
    }
    await page.waitForFunction(
      ({ storageKey, text }) => {
        const raw = localStorage.getItem(storageKey)
        if (raw == null) return false
        let parsed
        try {
          parsed = JSON.parse(raw)
        } catch {
          return false
        }
        if (
          parsed == null
          || typeof parsed !== 'object'
          || Array.isArray(parsed)
          || JSON.stringify(Object.keys(parsed).sort()) !== '["parts"]'
          || !Array.isArray(parsed.parts)
          || parsed.parts.length !== 1
        ) {
          return false
        }
        const part = parsed.parts[0]
        return (
          part != null
          && typeof part === 'object'
          && !Array.isArray(part)
          && JSON.stringify(Object.keys(part).sort()) === '["text","type"]'
          && part.type === 'text'
          && part.text === text
        )
      },
      { storageKey: key, text: expected },
      { timeout: 10_000 },
    )
  } catch {
    actual = await page.evaluate((storageKey) => localStorage.getItem(storageKey), key)
    throw new Error(
      `localStorage mismatch for ${key}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    )
  }
}

async function expectThreadDraft(page, threadId, expected) {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    const persisted = await page.evaluate(async ({ id, text }) => {
      const db = await new Promise((resolve, reject) => {
        const request = indexedDB.open('kk-studio.thread-drafts', 1)
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      })
      try {
        const record = await new Promise((resolve, reject) => {
          const request = db.transaction('threadDrafts', 'readonly').objectStore('threadDrafts').get(id)
          request.onsuccess = () => resolve(request.result)
          request.onerror = () => reject(request.error)
        })
        if (!record || record.threadId !== id || !Array.isArray(record.parts)
          || !Array.isArray(record.appliedStopRequestIds)) return false
        if (text === null) return record.parts.length === 0
        return record.parts.length === 1
          && record.parts[0].type === 'text'
          && typeof record.parts[0].partId === 'string'
          && Object.keys(record.parts[0]).sort().join(',') === 'partId,text,type'
          && record.parts[0].text === text
      } finally {
        db.close()
      }
    }, { id: threadId, text: expected })
    if (persisted) return
    await sleep(50)
  }
  throw new Error(`Thread ${threadId} draft was not persisted within 10000ms`)
}

export async function waitForPlaceholder(composer, visible) {
  await composer.page().waitForFunction(
    ({ selector, expected }) =>
      document.querySelector(selector)?.getAttribute('data-placeholder-visible') === String(expected),
    { selector: '[data-pane-active] .composer-editor, .composer-editor', expected: visible },
    { timeout: 10_000 },
  )
}

export async function waitForActiveModel(apiCtx, threadId, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThreadSnapshot(apiCtx, threadId)
    if (last.activeAssistantOutput != null) return last
    await sleep(50)
  }
  throw new Error(`model invocation did not become active: ${JSON.stringify(last)}`)
}

export async function waitForQueuedMessages(apiCtx, threadId, expectedCount, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThreadSnapshot(apiCtx, threadId)
    if (
      last.queuedCommands.length === expectedCount
      && last.queuedCommands.every((command) => command.state === 'QUEUED')
    ) {
      return last
    }
    await sleep(50)
  }
  throw new Error(`queued messages did not stabilize: ${JSON.stringify(last)}`)
}

export async function waitForThreadSnapshot(apiCtx, threadId, predicate, description, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThreadSnapshot(apiCtx, threadId)
    if (predicate(last)) return last
    await sleep(50)
  }
  throw new Error(`${description}: ${JSON.stringify(last)}`)
}

export async function setCaretOffset(composer, targetOffset) {
  const placed = await composer.evaluate((root, requestedOffset) => {
    const document = root.ownerDocument
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    let remaining = requestedOffset
    let node = walker.nextNode()
    while (node) {
      const length = node.textContent?.length ?? 0
      if (remaining <= length) {
        const range = document.createRange()
        range.setStart(node, remaining)
        range.collapse(true)
        const selection = document.getSelection()
        selection?.removeAllRanges()
        selection?.addRange(range)
        root.focus({ preventScroll: true })
        return true
      }
      remaining -= length
      node = walker.nextNode()
    }
    return false
  }, targetOffset)
  assert(placed, `could not place composer caret at ${targetOffset}`)
}

export async function caretOffset(composer) {
  return composer.evaluate((root) => {
    const selection = root.ownerDocument.getSelection()
    if (!selection || selection.rangeCount === 0) return -1
    const caret = selection.getRangeAt(0)
    if (!caret.collapsed || !root.contains(caret.startContainer)) return -1
    const prefix = root.ownerDocument.createRange()
    prefix.selectNodeContents(root)
    prefix.setEnd(caret.startContainer, caret.startOffset)
    return prefix.toString().length
  })
}

export async function composerMetrics(composer) {
  return composer.evaluate((root) => {
    const selection = root.ownerDocument.getSelection()
    const range = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null
    let selectionAtEnd = false
    if (range && range.collapsed && root.contains(range.commonAncestorContainer)) {
      const trailing = root.ownerDocument.createRange()
      trailing.selectNodeContents(root)
      trailing.setStart(range.endContainer, range.endOffset)
      selectionAtEnd = trailing.toString().length === 0
    }
    return {
      focused: root.ownerDocument.activeElement === root,
      scrollTop: root.scrollTop,
      scrollHeight: root.scrollHeight,
      clientHeight: root.clientHeight,
      bottomGap: root.scrollHeight - root.clientHeight - root.scrollTop,
      selectionCollapsed: range?.collapsed ?? false,
      selectionAtEnd,
    }
  })
}

export async function activeOptionText(palette) {
  const option = palette.locator('[role="option"][aria-selected="true"]')
  await option.waitFor({ state: 'visible', timeout: 10_000 })
  return (await option.innerText()).trim()
}

export function waitForThreadSubscription(page, threadId) {
  return new Promise((resolve, reject) => {
    let settled = false
    const tracked = new Set()
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error(`WebSocket thread subscription was not confirmed within 15000ms for ${threadId}`))
    }, 15_000)

    function finish() {
      if (settled) return
      settled = true
      cleanup()
      resolve()
    }

    function onFrame(frame) {
      if (typeof frame.payload !== 'string') return
      try {
        const message = JSON.parse(frame.payload)
        if (
          message?.type === 'subscribed'
          && message.resource?.kind === 'thread'
          && message.resource?.id === threadId
        ) {
          finish()
        }
      } catch {
        // Ignore non-JSON frames.
      }
    }

    function onWebSocket(ws) {
      let pathname = ''
      try {
        pathname = new URL(ws.url()).pathname
      } catch {
        pathname = ws.url()
      }
      if (!pathname.endsWith('/ws/events')) return
      tracked.add(ws)
      ws.on('framereceived', onFrame)
    }

    function cleanup() {
      clearTimeout(timer)
      page.off('websocket', onWebSocket)
      for (const ws of tracked) {
        ws.off('framereceived', onFrame)
      }
      tracked.clear()
    }

    page.on('websocket', onWebSocket)
  })
}
