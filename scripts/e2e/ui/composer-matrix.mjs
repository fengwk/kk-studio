import { createServer } from 'node:http'

import { baseModelConfig } from '../lib/fixtures.mjs'
import {
  assert,
  cid,
  envelopeData,
  sleep,
} from '../lib/http.mjs'
import {
  branchSettingsOf,
  createChat,
  createChatThread,
  enqueueCommands,
  getThreadSnapshot,
  setAgentCommand,
  stopThread,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'

const CHAT_PANE_STORAGE_PREFIX = 'kk-studio.chat-pane.'
const COMPOSER_DRAFT_STORAGE_PREFIX = 'kkstudio.ai.composer-draft.v1:'

export async function runComposerMatrix(ui) {
  const {
    apiCtx,
    consoleErrors,
    expectNoFatal,
    goto,
    page,
    pageErrors,
    run,
    shot,
    stamp,
  } = ui

  await run(
    'ui.chat.composer.history_order_boundaries',
    'Composer 按 durable、queued、草稿顺序导航且两端不循环',
    async (caseArt) => {
      await withUiFixture(
        page,
        () => createHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-composer-order-${stamp}`,
          historicalMessage: `durable history ${stamp}`,
          queuedMessages: [`queued 1 ${stamp}`, `queued 2 ${stamp}`],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draft = `local draft ${stamp}`
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(draft)
          await expectStorage(page, draftKey, draft)

          for (const expected of [
            `queued 2 ${stamp}`,
            `queued 1 ${stamp}`,
            `durable history ${stamp}`,
            `durable history ${stamp}`,
          ]) {
            await composer.press('ArrowUp')
            await expectComposerText(page, expected)
            await expectStorage(page, draftKey, draft)
          }
          for (const expected of [
            `queued 1 ${stamp}`,
            `queued 2 ${stamp}`,
            draft,
            draft,
          ]) {
            await composer.press('ArrowDown')
            await expectComposerText(page, expected)
            await expectStorage(page, draftKey, draft)
          }

          await shot(caseArt, 'composer-history-order-boundaries')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.duplicate_entries',
    'Composer 对相同文本的 durable/queued 条目仍保持独立游标',
    async (caseArt) => {
      const duplicate = `duplicate message ${stamp}`
      await withUiFixture(
        page,
        () => createHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-composer-duplicate-${stamp}`,
          historicalMessage: duplicate,
          queuedMessages: [duplicate],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draft = `duplicate scratch ${stamp}`
          await composer.fill(draft)

          await composer.press('ArrowUp')
          await expectComposerText(page, duplicate)
          await composer.press('ArrowUp')
          await expectComposerText(page, duplicate)
          await composer.press('ArrowDown')
          await expectComposerText(page, duplicate)
          await composer.press('ArrowDown')
          await expectComposerText(page, draft)

          await shot(caseArt, 'composer-history-duplicate-entries')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.multiline_scroll',
    'Composer 召回溢出多行历史后光标与内部滚动同步到末尾',
    async (caseArt) => {
      const currentDraft = `current composer draft ${stamp}`
      const historicalMessage = Array.from(
        { length: 24 },
        (_, index) =>
          `UI-E2E-COMPOSER-HISTORY-${stamp}-${String(index + 1).padStart(2, '0')}`,
      ).join('\n')
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-scroll-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          await composer.fill(currentDraft)
          await composer.press('ArrowUp')
          await expectComposerText(page, historicalMessage)

          const metrics = await composerMetrics(composer)
          assert(
            metrics.scrollHeight > metrics.clientHeight,
            `fixture did not overflow composer: ${JSON.stringify(metrics)}`,
          )
          assert(
            metrics.bottomGap <= 1,
            `recalled composer did not scroll to bottom: ${JSON.stringify(metrics)}`,
          )
          assert(
            metrics.selectionCollapsed && metrics.selectionAtEnd,
            `recalled composer caret is not at the end: ${JSON.stringify(metrics)}`,
          )
          await shot(caseArt, 'composer-history-multiline-bottom')

          await composer.press('ArrowDown')
          await expectComposerText(page, currentDraft)
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.multiline_caret_boundaries',
    'Composer 多行中间位置保留原生方向键，仅首行边界召回历史',
    async (caseArt) => {
      const historicalMessage = `caret boundary history ${stamp}`
      const currentDraft = 'line 1\nline 2\nline 3'
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-caret-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          await composer.fill(currentDraft)

          await setCaretOffset(composer, currentDraft.length)
          await composer.press('ArrowUp')
          await expectComposerText(page, currentDraft)
          const afterNativeUp = await caretOffset(composer)
          assert(
            afterNativeUp > 0 && afterNativeUp < currentDraft.length,
            `ArrowUp did not stay in multiline editing: ${afterNativeUp}`,
          )

          await setCaretOffset(composer, 0)
          await composer.press('ArrowDown')
          await expectComposerText(page, currentDraft)
          const afterNativeDown = await caretOffset(composer)
          assert(
            afterNativeDown > 0 && afterNativeDown < currentDraft.length,
            `ArrowDown did not stay in multiline editing: ${afterNativeDown}`,
          )

          await setCaretOffset(composer, 0)
          await composer.press('ArrowUp')
          await expectComposerText(page, historicalMessage)
          await shot(caseArt, 'composer-history-multiline-caret-boundary')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.edit_recalled',
    '编辑召回消息后提升为新草稿并可刷新恢复',
    async (caseArt) => {
      const historicalMessage = `editable history ${stamp}`
      const originalDraft = `original scratch ${stamp}`
      const editedDraft = `${historicalMessage} edited`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-edit-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          let composer = await bindThreadComposer(page, goto, fixture)
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(originalDraft)
          await composer.press('ArrowUp')
          await expectComposerText(page, historicalMessage)
          await page.keyboard.type(' edited')
          await expectComposerText(page, editedDraft)
          await expectStorage(page, draftKey, editedDraft)

          await composer.press('ArrowUp')
          await expectComposerText(page, historicalMessage)
          await composer.press('ArrowDown')
          await expectComposerText(page, editedDraft)

          await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
          composer = await waitForComposer(page)
          await expectComposerText(page, editedDraft)
          await expectStorage(page, draftKey, editedDraft)
          await shot(caseArt, 'composer-history-edited-recalled')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.draft_persistence_boundaries',
    '空 Pane 草稿精确刷新恢复，空白内容清存储且无历史时上键不变',
    async (caseArt) => {
      await withUiFixture(
        page,
        () => createBlankChatFixture(apiCtx, {
          title: `e2e-ui-composer-persistence-${stamp}`,
        }),
        async (fixture) => {
          let composer = await bindBlankComposer(page, goto, fixture)
          const draftKey = composerDraftStorageKey(
            `chat:${fixture.chat.id}:pane:pane-1`,
          )
          const exactDraft = '  first line\nsecond line  '

          await composer.fill(`no history ${stamp}`)
          await composer.press('ArrowUp')
          await expectComposerText(page, `no history ${stamp}`)

          await composer.fill('  first line')
          await composer.press('Shift+Enter')
          await page.keyboard.type('second line  ')
          await expectStorage(page, draftKey, exactDraft)
          await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
          composer = await waitForComposer(page)
          await expectComposerText(page, exactDraft)
          await expectStorage(page, draftKey, exactDraft)

          await composer.fill(' ')
          await composer.press('Shift+Enter')
          await page.keyboard.type(' ')
          await expectStorage(page, draftKey, null)
          await waitForPlaceholder(composer, true)
          await composer.fill('')
          await expectStorage(page, draftKey, null)
          await waitForPlaceholder(composer, true)

          await shot(caseArt, 'composer-draft-persistence-boundaries')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.palette_precedence',
    '命令菜单打开时方向键只导航命令，不召回消息或覆盖草稿',
    async (caseArt) => {
      const historicalMessage = `palette history ${stamp}`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-palette-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draft = `palette scratch ${stamp}`
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(draft)
          await page.getByRole('button', { name: '打开命令表' }).click()
          const palette = page.getByRole('listbox', { name: '命令表' })
          await palette.waitFor({ state: 'visible', timeout: 10_000 })
          const activeBefore = await activeOptionText(palette)
          await composer.focus()
          await composer.press('ArrowDown')
          const activeAfter = await activeOptionText(palette)
          assert(
            activeBefore !== activeAfter,
            `ArrowDown did not move the active command: ${activeBefore}`,
          )
          await expectComposerText(page, draft)
          await expectStorage(page, draftKey, draft)
          await composer.press('ArrowUp')
          await expectComposerText(page, draft)
          await composer.press('Escape')
          await palette.waitFor({ state: 'hidden', timeout: 10_000 })
          await expectComposerText(page, draft)

          await composer.fill('/sto')
          await palette.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            await page.locator('.thread-command-search').count() === 0,
            'slash palette duplicated the Composer query in a search header',
          )
          await shot(caseArt, 'composer-command-palette-without-duplicate-search')
          await composer.press('ArrowUp')
          await expectComposerText(page, '/sto')
          await composer.press('Escape')
          await expectComposerText(page, '')
          await expectStorage(page, draftKey, null)

          await shot(caseArt, 'composer-command-palette-precedence')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.selection_panel.keyboard_mode',
    '轻量交互面板与 Composer 互斥，并支持搜索、排序、方向键和 Esc 返回',
    async (caseArt) => {
      const draft = `selection panel draft ${stamp}`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-selection-panel-${stamp}`,
          messages: [`selection baseline ${stamp}`],
          extraThreadCount: 1,
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(draft)
          await page.getByRole('button', { name: '打开命令表' }).click()
          await page.getByRole('option', { name: /^thread/ }).click()

          const panel = page.getByRole('region', { name: '选择 Thread' })
          await panel.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            await page.locator('.modal-backdrop').count() === 0,
            'selection panel unexpectedly rendered a modal backdrop',
          )
          assert(
            await page.locator('.thread-composer').getAttribute('hidden') !== null,
            'Composer remained visible while the selection panel was active',
          )
          await page.getByLabel('会话状态').waitFor({ state: 'visible', timeout: 10_000 })

          const search = panel.getByRole('searchbox', { name: '搜索' })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await search.elementHandle(),
            { timeout: 10_000 },
          )

          const createdSort = panel.getByRole('button', { name: '创建时间' })
          assert(
            await createdSort.getAttribute('aria-pressed') === 'false',
            'Thread picker did not start from recent sort',
          )
          await search.press('Tab')
          await page.waitForFunction(
            (element) => element?.getAttribute('aria-pressed') === 'true',
            await createdSort.elementHandle(),
            { timeout: 10_000 },
          )

          const extraThreadId = fixture.extraThreadIds[0]
          assert(extraThreadId, 'selection fixture did not create an extra Thread')
          await search.fill(extraThreadId)
          const filteredOptions = panel.getByRole('option')
          await page.waitForFunction(
            ({ panelLabel, expectedId }) => {
              const region = [...document.querySelectorAll('[role="region"]')]
                .find((element) => element.getAttribute('aria-label') === panelLabel)
              const options = region?.querySelectorAll('[role="option"]') ?? []
              return options.length === 1 && options[0]?.textContent?.includes(expectedId)
            },
            { panelLabel: '选择 Thread', expectedId: extraThreadId },
            { timeout: 10_000 },
          )
          assert(await filteredOptions.count() === 1, 'Thread search did not narrow to one option')

          await search.fill('')
          await page.waitForFunction(
            (panelLabel) => {
              const region = [...document.querySelectorAll('[role="region"]')]
                .find((element) => element.getAttribute('aria-label') === panelLabel)
              return region?.querySelectorAll('[role="option"]').length === 2
            },
            '选择 Thread',
            { timeout: 10_000 },
          )
          const activeBefore = await activeOptionText(panel)
          await search.press('ArrowDown')
          await page.waitForFunction(
            ({ panelLabel, previous }) => {
              const region = [...document.querySelectorAll('[role="region"]')]
                .find((element) => element.getAttribute('aria-label') === panelLabel)
              const active = region?.querySelector('[role="option"][aria-selected="true"]')
              return active?.textContent?.trim() !== previous
            },
            { panelLabel: '选择 Thread', previous: activeBefore },
            { timeout: 10_000 },
          )
          const activeAfter = await activeOptionText(panel)
          assert(
            activeBefore !== activeAfter,
            `ArrowDown did not move the Thread selection: ${activeBefore}`,
          )
          await shot(caseArt, 'selection-panel-keyboard-mode')

          await search.fill(extraThreadId)
          await search.press('Enter')
          const discardDialog = page.getByRole('alertdialog', {
            name: '丢弃未发送的修改？',
          })
          await discardDialog.waitFor({ state: 'visible', timeout: 10_000 })
          const cancelDiscard = discardDialog.getByRole('button', { name: '取消' })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await cancelDiscard.elementHandle(),
            { timeout: 10_000 },
          )
          await cancelDiscard.press('Escape')
          await discardDialog.waitFor({ state: 'hidden', timeout: 10_000 })
          await panel.waitFor({ state: 'visible', timeout: 10_000 })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await search.elementHandle(),
            { timeout: 10_000 },
          )

          await search.press('Escape')
          await panel.waitFor({ state: 'hidden', timeout: 10_000 })
          await page.waitForFunction(
            () => document.activeElement?.classList.contains('composer-editor') === true,
            undefined,
            { timeout: 10_000 },
          )
          await expectComposerText(page, draft)
          await expectStorage(page, draftKey, draft)

          await page.getByRole('button', { name: '打开命令表' }).click()
          await page.getByRole('option', { name: /^tree/ }).click()
          const historyPanel = page.getByRole('region', { name: '历史分支' })
          await historyPanel.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            await page.locator('.modal-backdrop').count() === 0,
            'history panel unexpectedly rendered a modal backdrop',
          )
          assert(
            await page.locator('.thread-composer').getAttribute('hidden') !== null,
            'Composer remained visible while the history panel was active',
          )
          const historySearch = historyPanel.getByRole('searchbox', { name: '搜索记录' })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await historySearch.elementHandle(),
            { timeout: 10_000 },
          )
          await historySearch.press('Escape')
          await historyPanel.waitFor({ state: 'hidden', timeout: 10_000 })
          await page.waitForFunction(
            () => document.activeElement?.classList.contains('composer-editor') === true,
            undefined,
            { timeout: 10_000 },
          )
          await expectComposerText(page, draft)
          await expectStorage(page, draftKey, draft)
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.escape_refocus',
    'Esc 从 transcript 文字选区回到当前 Pane Composer，并保留草稿',
    async (caseArt) => {
      const historicalMessage = `escape refocus history ${stamp}`
      const draft = `escape refocus draft ${stamp}`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-escape-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(draft)
          await expectStorage(page, draftKey, draft)

          const transcriptMessage = page.getByText(historicalMessage, { exact: true }).first()
          await transcriptMessage.waitFor({ state: 'visible', timeout: 10_000 })
          const selectedText = await transcriptMessage.evaluate((element) => {
            const active = element.ownerDocument.activeElement
            if (active instanceof HTMLElement) {
              active.blur()
            }
            const range = element.ownerDocument.createRange()
            range.selectNodeContents(element)
            const selection = element.ownerDocument.getSelection()
            selection?.removeAllRanges()
            selection?.addRange(range)
            return selection?.toString() ?? ''
          })
          assert(
            selectedText.includes(historicalMessage),
            `transcript text was not selected: ${JSON.stringify(selectedText)}`,
          )

          await page.keyboard.press('Escape')
          await page.waitForFunction(
            () => document.activeElement?.classList.contains('composer-editor') === true,
            undefined,
            { timeout: 10_000 },
          )
          await expectComposerText(page, draft)
          await expectStorage(page, draftKey, draft)
          const metrics = await composerMetrics(composer)
          assert(
            metrics.focused && metrics.selectionCollapsed && metrics.selectionAtEnd,
            `Escape did not restore a writable Composer caret: ${JSON.stringify(metrics)}`,
          )

          await shot(caseArt, 'composer-escape-refocus')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.submit_clears_draft',
    '提交后立即清除 Composer 与 localStorage，并在 timeline 中保留用户消息',
    async (caseArt) => {
      const submitted = `submitted composer message ${stamp}`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-submit-${stamp}`,
          messages: [`submit baseline ${stamp}`],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(submitted)
          await expectStorage(page, draftKey, submitted)
          await page.getByRole('button', { name: '发送消息' }).click()

          await expectComposerText(page, '')
          await expectStorage(page, draftKey, null)
          await page.getByText(submitted, { exact: true }).first().waitFor({
            state: 'visible',
            timeout: 15_000,
          })

          await shot(caseArt, 'composer-submit-clears-draft')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )
}

async function createBlankChatFixture(apiCtx, { title }) {
  const target = await resolveCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    chat: null,
    threadId: null,
  }
  try {
    state.chat = await createChat(apiCtx, {
      title,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    return fixtureOf(state)
  } catch (error) {
    await cleanupFixture(state).catch(() => undefined)
    throw error
  }
}

async function createDurableHistoryFixture(
  apiCtx,
  { title, messages, extraThreadCount = 0 },
) {
  assert(
    Array.isArray(messages) && messages.length > 0,
    'durable history messages required',
  )
  assert(
    Number.isSafeInteger(extraThreadCount) && extraThreadCount >= 0,
    'extraThreadCount must be a non-negative integer',
  )
  const target = await resolveCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    chat: null,
    extraThreadIds: [],
    threadId: null,
  }
  try {
    state.chat = await createChat(apiCtx, {
      title,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    const branchSettings = branchSettingsOf(target.agent, target.model)
    const created = await createChatThread(apiCtx, state.chat.id, {
      title: null,
      yoloEnabled: false,
      branchSettings,
    })
    state.threadId = created.thread.threadId
    for (let index = 0; index < extraThreadCount; index += 1) {
      const extra = await createChatThread(apiCtx, state.chat.id, {
        title: null,
        yoloEnabled: false,
        branchSettings,
      })
      state.extraThreadIds.push(extra.thread.threadId)
    }
    await enqueueCommands(apiCtx, state.threadId, {
      expectedHeadEntryId: created.thread.headEntryId,
      expectedNextCommandSequence: created.thread.nextCommandSequence,
      commands: [
        setAgentCommand(`e2e-ui-missing-${cid().slice(0, 8)}`, cid()),
        ...messages.map((message) => userMessageCommand(message, cid())),
      ],
    })
    await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    return fixtureOf(state)
  } catch (error) {
    await cleanupFixture(state).catch(() => undefined)
    throw error
  }
}

async function createHoldingQueueFixture(
  apiCtx,
  { title, historicalMessage, queuedMessages },
) {
  assert(
    Array.isArray(queuedMessages) && queuedMessages.length > 0,
    'queued messages required',
  )
  const suffix = cid().slice(0, 8)
  const mock = new HoldingOpenAiMock()
  const state = {
    apiCtx,
    agent: null,
    chat: null,
    mock,
    model: null,
    provider: null,
    threadId: null,
  }
  try {
    await mock.start()
    const providerResponse = await apiCtx.call(
      'POST',
      '/api/ai/catalog/providers',
      {
        name: `e2e-ui-hold-provider-${suffix}`,
        description: 'Local hold provider for free UI queue tests.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: `e2e-ui-hold-${suffix}`,
        modelCallTimeoutMillis: 60_000,
        modelCallIdleTimeoutMillis: 60_000,
      },
    )
    assert(
      providerResponse.status === 201,
      `create hold provider: ${JSON.stringify(providerResponse)}`,
    )
    state.provider = envelopeData(providerResponse.json)

    const modelResponse = await apiCtx.call(
      'POST',
      '/api/ai/catalog/models',
      {
        providerName: state.provider.name,
        name: `e2e-ui-hold-model-${suffix}`,
        description: 'Local hold model for free UI queue tests.',
        config: baseModelConfig({
          limit: { context: 4096, output: 128 },
          abilities: {
            tools: false,
            reasoning: false,
            inputModalities: ['TEXT'],
          },
          variants: [{ id: 'default', temperature: 0 }],
        }),
      },
    )
    assert(
      modelResponse.status === 201,
      `create hold model: ${JSON.stringify(modelResponse)}`,
    )
    state.model = envelopeData(modelResponse.json)

    const agentResponse = await apiCtx.call(
      'POST',
      '/api/ai/catalog/agents',
      {
        name: `e2e-ui-hold-agent-${suffix}`,
        description: 'Local hold agent for free UI queue tests.',
        systemPrompt: 'Wait for the local E2E stream.',
        model: `${state.model.providerName}/${state.model.name}`,
        variant: 'default',
        config: { tools: [], skills: [], subagents: [] },
      },
    )
    assert(
      agentResponse.status === 201,
      `create hold agent: ${JSON.stringify(agentResponse)}`,
    )
    state.agent = envelopeData(agentResponse.json)

    state.chat = await createChat(apiCtx, {
      title,
      agentName: state.agent.name,
      yoloEnabled: false,
    })
    const created = await createChatThread(apiCtx, state.chat.id, {
      title: null,
      yoloEnabled: false,
      branchSettings: branchSettingsOf(state.agent, {
        providerName: state.model.providerName,
        modelName: state.model.name,
        variant: 'default',
      }),
    })
    state.threadId = created.thread.threadId
    await enqueueCommands(apiCtx, state.threadId, {
      expectedHeadEntryId: created.thread.headEntryId,
      expectedNextCommandSequence: created.thread.nextCommandSequence,
      commands: [userMessageCommand(historicalMessage, cid())],
    })
    await mock.waitForRequest()

    const active = await waitForActiveModel(apiCtx, state.threadId)
    await enqueueCommands(apiCtx, state.threadId, {
      expectedHeadEntryId: active.thread.headEntryId,
      expectedNextCommandSequence: active.thread.nextCommandSequence,
      commands: queuedMessages.map((message) => userMessageCommand(message, cid())),
    })
    await waitForQueuedMessages(apiCtx, state.threadId, queuedMessages.length)
    return fixtureOf(state)
  } catch (error) {
    await cleanupFixture(state).catch(() => undefined)
    throw error
  }
}

function fixtureOf(state) {
  return {
    ...state,
    dispose: () => cleanupFixture(state),
  }
}

async function withUiFixture(page, createFixture, test) {
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
        await clearBrowserFixtureState(page, fixture)
        await fixture.dispose()
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

async function cleanupFixture(state) {
  const errors = []
  await cleanupStep(errors, 'stop thread', async () => {
    if (!state.threadId) return
    const snapshot = await getThreadSnapshot(state.apiCtx, state.threadId)
    if (
      snapshot.thread.status !== 'IDLE'
      || snapshot.thread.processing
      || snapshot.queuedCommands.length > 0
      || snapshot.modelInvocation !== null
      || snapshot.toolInvocations.length > 0
    ) {
      await stopThread(state.apiCtx, state.threadId, {
        stopRequestId: cid(),
        expectedRevision: snapshot.thread.revision,
      })
    }
  })
  await cleanupStep(errors, 'hold provider', async () => state.mock?.close())
  await cleanupStep(errors, 'chat', async () => deleteChat(state.apiCtx, state.chat))
  await cleanupStep(errors, 'agent', async () => {
    if (!state.agent) return
    await deleteCatalogResource(
      state.apiCtx,
      `/api/ai/catalog/agents/${encodeURIComponent(state.agent.name)}`
        + `?expectedVersion=${encodeURIComponent(state.agent.version)}`,
    )
  })
  await cleanupStep(errors, 'model', async () => {
    if (!state.model) return
    await deleteCatalogResource(
      state.apiCtx,
      '/api/ai/catalog/models'
        + `?providerName=${encodeURIComponent(state.model.providerName)}`
        + `&modelName=${encodeURIComponent(state.model.name)}`
        + `&expectedVersion=${encodeURIComponent(state.model.version)}`,
    )
  })
  await cleanupStep(errors, 'provider', async () => {
    if (!state.provider) return
    await deleteCatalogResource(
      state.apiCtx,
      `/api/ai/catalog/providers/${encodeURIComponent(state.provider.name)}`
        + `?expectedVersion=${encodeURIComponent(state.provider.version)}`,
    )
  })
  if (errors.length > 0) {
    throw new Error(`composer fixture cleanup failed: ${errors.join(' | ')}`)
  }
}

async function cleanupStep(errors, label, action) {
  try {
    await action()
  } catch (error) {
    errors.push(`${label}: ${error?.message || error}`)
  }
}

async function deleteChat(apiCtx, chat) {
  if (!chat?.id) return
  const currentResponse = await apiCtx.call(
    'GET',
    `/api/ai/chat/${encodeURIComponent(chat.id)}`,
  )
  if (currentResponse.status === 404) return
  assert(
    currentResponse.status === 200,
    `read Chat for cleanup: ${JSON.stringify(currentResponse)}`,
  )
  const current = envelopeData(currentResponse.json)
  const response = await apiCtx.call(
    'DELETE',
    `/api/ai/chat/${encodeURIComponent(chat.id)}`
      + `?expectedVersion=${encodeURIComponent(current.version)}`,
  )
  assert(
    response.status === 200 || response.status === 204,
    `delete Chat: ${JSON.stringify(response)}`,
  )
}

async function deleteCatalogResource(apiCtx, requestPath) {
  const response = await apiCtx.call('DELETE', requestPath)
  assert(
    response.status === 200
      || response.status === 204
      || response.status === 404,
    `delete catalog resource: ${JSON.stringify(response)}`,
  )
}

async function clearBrowserFixtureState(page, fixture) {
  if (!fixture.chat?.id) return
  await page.evaluate(
    ({ paneKey, blankDraftKey, threadDraftKey }) => {
      localStorage.removeItem(paneKey)
      localStorage.removeItem(blankDraftKey)
      if (threadDraftKey) {
        localStorage.removeItem(threadDraftKey)
      }
    },
    {
      paneKey: `${CHAT_PANE_STORAGE_PREFIX}${fixture.chat.id}`,
      blankDraftKey: composerDraftStorageKey(
        `chat:${fixture.chat.id}:pane:pane-1`,
      ),
      threadDraftKey: fixture.threadId
        ? composerDraftStorageKey(`thread:${fixture.threadId}`)
        : null,
    },
  ).catch(() => undefined)
}

async function resolveCatalogTarget(apiCtx) {
  const agentsResponse = await apiCtx.call(
    'GET',
    '/api/ai/catalog/agents?pageNumber=1&pageSize=50',
  )
  const modelsResponse = await apiCtx.call(
    'GET',
    '/api/ai/catalog/models?pageNumber=1&pageSize=50',
  )
  assert(
    agentsResponse.status === 200 && modelsResponse.status === 200,
    `catalog lookup failed: ${JSON.stringify({
      agents: agentsResponse,
      models: modelsResponse,
    })}`,
  )
  const agents = agentsResponse.json?.data?.results || []
  const models = modelsResponse.json?.data?.results || []
  for (const agent of agents) {
    const separator = String(agent.model || '').indexOf('/')
    if (separator <= 0) continue
    const providerName = String(agent.model).slice(0, separator)
    const modelName = String(agent.model).slice(separator + 1)
    const model = models.find(
      (candidate) =>
        candidate.providerName === providerName
        && candidate.name === modelName,
    )
    const variant = agent.variant || model?.config?.defaultVariant
    if (model && variant) {
      return {
        agent,
        model: { providerName, modelName, variant },
      }
    }
  }
  throw new Error(
    `no resolvable Agent/Model in current Catalog: ${JSON.stringify({ agents, models })}`,
  )
}

async function bindThreadComposer(page, goto, fixture) {
  await goto('/chats')
  await page.evaluate(
    ({ paneKey, threadId }) => {
      localStorage.setItem(
        paneKey,
        JSON.stringify({
          layout: 'single',
          focusedPaneId: 'pane-1',
          panes: Array.from({ length: 8 }, (_, index) => ({
            id: `pane-${index + 1}`,
            threadId: index === 0 ? threadId : null,
          })),
          threadSort: 'recent',
        }),
      )
    },
    {
      paneKey: `${CHAT_PANE_STORAGE_PREFIX}${fixture.chat.id}`,
      threadId: fixture.threadId,
    },
  )
  await goto(`/chats/${encodeURIComponent(fixture.chat.id)}`)
  return waitForComposer(page)
}

async function bindBlankComposer(page, goto, fixture) {
  await goto('/chats')
  await page.evaluate(
    (paneKey) => localStorage.removeItem(paneKey),
    `${CHAT_PANE_STORAGE_PREFIX}${fixture.chat.id}`,
  )
  await goto(`/chats/${encodeURIComponent(fixture.chat.id)}`)
  return waitForComposer(page)
}

async function waitForComposer(page) {
  const composer = page.getByRole('textbox', { name: '给 AI 发送消息' })
  await composer.waitFor({ state: 'visible', timeout: 15_000 })
  await page.waitForFunction(
    () =>
      document.querySelector('.composer-editor')
        ?.getAttribute('contenteditable') === 'true',
    undefined,
    { timeout: 15_000 },
  )
  return composer
}

async function expectComposerText(page, expected) {
  await page.waitForFunction(
    (text) =>
      document.querySelector('.composer-editor')?.textContent === text,
    expected,
    { timeout: 10_000 },
  )
}

async function expectStorage(page, key, expected) {
  try {
    await page.waitForFunction(
      ({ storageKey, storageValue }) =>
        localStorage.getItem(storageKey) === storageValue,
      { storageKey: key, storageValue: expected },
      { timeout: 10_000 },
    )
  } catch {
    const actual = await page.evaluate(
      (storageKey) => localStorage.getItem(storageKey),
      key,
    )
    throw new Error(
      `localStorage mismatch for ${key}: expected ${JSON.stringify(expected)},`
        + ` got ${JSON.stringify(actual)}`,
    )
  }
}

async function waitForPlaceholder(composer, visible) {
  await composer.page().waitForFunction(
    ({ selector, expected }) =>
      document.querySelector(selector)
        ?.getAttribute('data-placeholder-visible') === String(expected),
    { selector: '.composer-editor', expected: visible },
    { timeout: 10_000 },
  )
}

function composerDraftStorageKey(scope) {
  return `${COMPOSER_DRAFT_STORAGE_PREFIX}${scope}`
}

async function setCaretOffset(composer, targetOffset) {
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

async function caretOffset(composer) {
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

async function composerMetrics(composer) {
  return composer.evaluate((root) => {
    const selection = root.ownerDocument.getSelection()
    const range =
      selection && selection.rangeCount > 0
        ? selection.getRangeAt(0)
        : null
    let selectionAtEnd = false
    if (
      range
      && range.collapsed
      && root.contains(range.commonAncestorContainer)
    ) {
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

async function activeOptionText(palette) {
  const option = palette.locator('[role="option"][aria-selected="true"]')
  await option.waitFor({ state: 'visible', timeout: 10_000 })
  return (await option.innerText()).trim()
}

async function waitForActiveModel(apiCtx, threadId, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() <= deadline) {
    last = await getThreadSnapshot(apiCtx, threadId)
    if (last.modelInvocation != null) return last
    await sleep(50)
  }
  throw new Error(`model invocation did not become active: ${JSON.stringify(last)}`)
}

async function waitForQueuedMessages(
  apiCtx,
  threadId,
  expectedCount,
  timeoutMs = 10_000,
) {
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

class HoldingOpenAiMock {
  constructor() {
    this.requests = []
    this.responses = new Set()
    this.sockets = new Set()
    this.listening = false
    this.base = null
    this.resolveFirstRequest = null
    this.firstRequest = new Promise((resolve) => {
      this.resolveFirstRequest = resolve
    })
    this.server = createServer((request, response) => {
      void this.handle(request, response)
    })
    this.server.on('connection', (socket) => {
      this.sockets.add(socket)
      socket.once('close', () => this.sockets.delete(socket))
    })
  }

  async start() {
    await new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(0, '127.0.0.1', () => {
        this.server.removeListener('error', reject)
        resolve()
      })
    })
    const address = this.server.address()
    assert(address && typeof address === 'object', 'hold server did not bind')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'hold server is not started')
    return `${this.base}${suffix}`
  }

  async waitForRequest(timeoutMs = 15_000) {
    if (this.requests.length > 0) return
    await Promise.race([
      this.firstRequest,
      sleep(timeoutMs).then(() => {
        throw new Error('hold provider did not receive a request')
      }),
    ])
  }

  async handle(request, response) {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(request.method === 'POST' ? 404 : 405, {
        Connection: 'close',
      })
      response.end()
      return
    }
    let body
    try {
      body = JSON.parse(await readRequestBody(request))
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(
        JSON.stringify({ error: { message: `invalid hold JSON: ${error.message}` } }),
      )
      return
    }
    this.requests.push(body)
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(': hold\n\n')
    this.responses.add(response)
    response.once('close', () => this.responses.delete(response))
    this.resolveFirstRequest?.()
  }

  async close() {
    for (const response of this.responses) response.destroy()
    this.responses.clear()
    for (const socket of this.sockets) socket.destroy()
    this.sockets.clear()
    if (!this.listening) return
    await new Promise((resolve, reject) => {
      this.server.close((error) => (error ? reject(error) : resolve()))
    })
    this.listening = false
  }
}

async function readRequestBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}
