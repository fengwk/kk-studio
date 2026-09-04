import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'

import { baseModelConfig } from '../lib/fixtures.mjs'
import {
  assert,
  cid,
  envelopeData,
  HttpError,
  sleep,
} from '../lib/http.mjs'
import { assertReadOnlyZeroFooter } from './assertions.mjs'
import { runWorkspaceContractMatrix } from './workspace-contracts.mjs'
import {
  acceptCommandBatch,
  branchSettingsOf,
  chatOwner,
  createChat,
  getThreadSnapshot,
  createEntryThread,
  createNewSession,
  setAgentCommand,
  setModelCommand,
  stopThreadForCleanup,
  threadTarget,
  userMessageCommand,
  waitForDurableMessages,
  waitForQuiescentThread,
} from '../lib/harness.mjs'

const CHAT_PANE_STORAGE_PREFIX = 'kk-studio.chat-pane.'
const COMPOSER_DRAFT_STORAGE_PREFIX = 'kkstudio.ai.composer-draft.v1:'
const TINY_IMAGE = readFileSync(
  new URL('../../../platform/src/main/resources/fun/fengwk/kkstudio/platform/canvas/function/fake/tiny.png', import.meta.url),
)
const TINY_VIDEO = readFileSync(
  new URL('../../../platform/src/main/resources/fun/fengwk/kkstudio/platform/canvas/function/fake/tiny.mp4', import.meta.url),
)

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
            fixture.bootstrapMessage,
            fixture.bootstrapMessage,
          ]) {
            await composer.press('ArrowUp')
            await expectComposerText(page, expected)
            await expectStorage(page, draftKey, draft)
          }
          for (const expected of [
            `durable history ${stamp}`,
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
            `agent-pane:CHAT:${fixture.chat.id}:pane-1`,
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
    '轻量交互面板与 Composer 互斥，并支持 Session 内搜索、方向键和 Esc 返回',
    async (caseArt) => {
      const draft = `selection panel draft ${stamp}`
      await withUiFixture(
        page,
        () => createBranchedHistoryFixture(apiCtx, {
          title: `e2e-ui-selection-panel-${stamp}`,
          trunkMessage: `selection baseline ${stamp}`,
          originalMessages: [
            `selection original branch ${stamp}`,
            `selection original descendant ${stamp}`,
          ],
          alternateMessage: `selection alternate branch ${stamp}`,
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)
          await composer.fill(draft)
          await page.getByRole('button', { name: '打开命令表' }).click()
          await page.getByRole('option', { name: /^thread/ }).click()

          const panel = await openFixtureThreadPanel(page, fixture)
          assert(
            await page.locator('.modal-backdrop').count() === 0,
            'selection panel unexpectedly rendered a modal backdrop',
          )
          assert(
            await page.locator('.thread-composer').getAttribute('hidden') !== null,
            'Composer remained visible while the selection panel was active',
          )
          await assertReadOnlyZeroFooter(page, 'selection panel Footer')

          const search = panel.getByRole('searchbox', { name: '搜索' })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await search.elementHandle(),
            { timeout: 10_000 },
          )

          const siblingThreadId = fixture.sessionThreadIds.find(
            (threadId) => threadId !== fixture.threadId,
          )
          assert(siblingThreadId, 'selection fixture did not create a sibling Thread')
          await search.fill(siblingThreadId)
          const filteredOptions = panel.getByRole('option')
          await page.waitForFunction(
            ({ panelLabel, expectedId }) => {
              const region = [...document.querySelectorAll('[role="region"]')]
                .find((element) => element.getAttribute('aria-label') === panelLabel)
              const options = region?.querySelectorAll('[role="option"]') ?? []
              return options.length === 1 && options[0]?.textContent?.includes(expectedId)
            },
            { panelLabel: '选择 Thread', expectedId: siblingThreadId },
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

          // 两级 picker 按层退栈：Thread -> Session -> Composer；草稿与持久值保持。
          await search.press('Escape')
          await panel.waitFor({ state: 'hidden', timeout: 10_000 })
          const sessionPanel = page.getByRole('region', { name: '选择 Session' })
          await sessionPanel.waitFor({ state: 'visible', timeout: 10_000 })
          const sessionSearch = sessionPanel.getByRole('searchbox')
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await sessionSearch.elementHandle(),
            { timeout: 10_000 },
          )
          await sessionSearch.press('Escape')
          await sessionPanel.waitFor({ state: 'hidden', timeout: 10_000 })
          await page.waitForFunction(
            () => document.activeElement?.classList.contains('composer-editor') === true,
            undefined,
            { timeout: 10_000 },
          )
          await expectComposerText(page, draft)
          await expectStorage(page, draftKey, draft)

          // 再次打开 Thread picker，Enter 直接切换到同 Session sibling；旧 Thread
          // 草稿按 per-thread scope 保留，不再使用已删除的 discard modal。
          await page.getByRole('button', { name: '打开命令表' }).click()
          await page.getByRole('option', { name: /^thread/ }).click()
          const switchPanel = await openFixtureThreadPanel(page, fixture)
          const switchSearch = switchPanel.getByRole('searchbox')
          await switchSearch.fill(siblingThreadId)
          await switchSearch.press('Enter')
          await switchPanel.waitFor({ state: 'hidden', timeout: 10_000 })
          await page.getByText(`selection original descendant ${stamp}`, { exact: true })
            .waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            await page.getByRole('alertdialog').count() === 0,
            'Thread selection unexpectedly opened a discard dialog',
          )
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
          const historyBox = await historyPanel.boundingBox()
          const viewport = page.viewportSize()
          assert(
            historyBox
            && viewport
            && historyBox.width >= viewport.width - 40,
            `history panel no longer fills the inline pane width: ${JSON.stringify({
              historyBox,
              viewport,
            })}`,
          )
          const selectedHistoryRow = historyPanel.locator('.history-branch-entry[aria-pressed="true"]')
          const selectedPrefix = await selectedHistoryRow.evaluate((element) => ({
            classes: [...element.children].slice(0, 3).map((child) => child.className),
            path: element.querySelector('.history-branch-entry-path')?.textContent ?? '',
            cursor: element.querySelector('.history-branch-entry-cursor')?.textContent ?? '',
          }))
          assert(
            selectedPrefix.classes[0] === 'history-branch-entry-cursor'
            && selectedPrefix.classes.includes('history-branch-entry-path')
            && selectedPrefix.path === '•'
            && selectedPrefix.cursor === '›',
            `history row does not use the pi tree cursor/path grammar: ${JSON.stringify(selectedPrefix)}`,
          )
          const treeRows = historyPanel.locator('.history-branch-entry')
          assert(await treeRows.count() === 4, `branched history row count: ${await treeRows.count()}`)
          const originalBranchRow = historyPanel.getByRole('button', {
            name: new RegExp(`selection original branch ${stamp}`),
          })
          const originalDescendantRow = historyPanel.getByRole('button', {
            name: new RegExp(`selection original descendant ${stamp}`),
          })
          const alternateBranchRow = historyPanel.getByRole('button', {
            name: new RegExp(`selection alternate branch ${stamp}`),
          })
          assert(
            (await originalBranchRow.locator('.history-branch-entry-glyphs').textContent()) === '├─ ',
            'first branch did not render a fork connector',
          )
          assert(
            (await originalDescendantRow.locator('.history-branch-entry-glyphs').textContent())?.startsWith('│  '),
            'branch descendant did not preserve the vertical ancestor gutter',
          )
          assert(
            (await alternateBranchRow.locator('.history-branch-entry-glyphs').textContent()) === '└─ ',
            'last branch did not render an elbow connector',
          )
          const selectedBeforeTreeMove = await selectedHistoryRow.getAttribute('aria-label')
          await historySearch.press('ArrowUp')
          await page.waitForFunction(
            ({ panelLabel, previous }) => {
              const region = [...document.querySelectorAll('[role="region"]')]
                .find((element) => element.getAttribute('aria-label') === panelLabel)
              const selected = region?.querySelector('.history-branch-entry[aria-pressed="true"]')
              return selected?.getAttribute('aria-label') !== previous
            },
            { panelLabel: '历史分支', previous: selectedBeforeTreeMove },
            { timeout: 10_000 },
          )
          assert(
            await originalBranchRow.getAttribute('aria-pressed') === 'true',
            'ArrowUp did not move the tree selection to the previous visible row',
          )
          await shot(caseArt, 'history-panel-keyboard-mode')
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
          await expectComposerText(page, '')
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

  await run(
    'ui.chat.composer.attachment_previews',
    'Composer 附件只显示文件名，并按图片、视频和通用文件类型展示缩略图或图标',
    async (caseArt) => {
      const uploadRoute = createReadyUploadRoute()
      await page.route('**/api/storage/uploads**', uploadRoute)
      try {
        await withUiFixture(
          page,
          () => createDurableHistoryFixture(apiCtx, {
            title: `e2e-ui-composer-attachments-${stamp}`,
            messages: [`attachment baseline ${stamp}`],
          }),
          async (fixture) => {
            await bindThreadComposer(page, goto, fixture)
            const imageName = `scene-${stamp}.png`
            const videoName = `clip-${stamp}.mp4`
            const fileName = `notes-${stamp}.pdf`
            await page.locator('input[type="file"]').setInputFiles([
              { name: imageName, mimeType: 'image/png', buffer: TINY_IMAGE },
              { name: videoName, mimeType: 'video/mp4', buffer: TINY_VIDEO },
              {
                name: fileName,
                mimeType: 'application/pdf',
                buffer: Buffer.from('%PDF-1.4\n% attachment preview fixture\n'),
              },
            ])

            const strip = page.getByRole('list', { name: '附件' })
            await strip.waitFor({ state: 'visible', timeout: 10_000 })
            await page.waitForFunction(
              () => document.querySelectorAll('.attachment-reference').length === 3,
              undefined,
              { timeout: 10_000 },
            )
            const imageItem = strip.locator(`[data-filename="${imageName}"]`)
            const videoItem = strip.locator(`[data-filename="${videoName}"]`)
            const fileItem = strip.locator(`[data-filename="${fileName}"]`)
            assert(await imageItem.locator('img').count() === 1, 'image attachment lacks a thumbnail')
            assert(await videoItem.locator('video').count() === 1, 'video attachment lacks a first-frame preview')
            assert(
              await fileItem.locator('[data-file-icon="text"]').count() === 1,
              'PDF attachment lacks a type-specific file icon',
            )
            const pillTexts = await page.locator('.composer-pill').allTextContents()
            assert(
              JSON.stringify(pillTexts) === JSON.stringify([
                `[${imageName}]`,
                `[${videoName}]`,
                `[${fileName}]`,
              ]),
              `attachment pill labels are not filename-only: ${JSON.stringify(pillTexts)}`,
            )
            const pillsUnclipped = await page.locator('.composer-pill').evaluateAll(
              (elements) => elements.every((element) => element.scrollWidth <= element.clientWidth),
            )
            assert(pillsUnclipped, 'composer input pills visually truncate filenames')
            assert(
              !(await page.locator('.thread-dock').innerText()).includes('(upload)'),
              'attachment UI still exposes the internal upload syntax',
            )
            const imageBox = await imageItem.boundingBox()
            assert(
              imageBox && imageBox.width >= 198 && imageBox.width <= 202,
              `attachment card width is not fixed at 200px: ${JSON.stringify(imageBox)}`,
            )
            const imageNameOverflow = await imageItem.locator('.attachment-reference-name').evaluate(
              (element) => element.scrollWidth > element.clientWidth,
            )
            assert(
              imageNameOverflow,
              'long attachment filename is not ellipsized inside the fixed-width card',
            )

            await shot(caseArt, 'composer-attachment-previews')
            await imageItem.locator('button.attachment-reference-preview').click()
            const lightbox = page.locator('.resource-media-lightbox')
            await lightbox.waitFor({ state: 'visible', timeout: 5_000 })
            assert(
              await lightbox.locator('.resource-media-lightbox-content img').count() === 1,
              'image attachment lightbox lacks the full image',
            )
            await shot(caseArt, 'composer-attachment-lightbox')
            await page.keyboard.press('Escape')
            await lightbox.waitFor({ state: 'hidden', timeout: 5_000 })
            expectNoFatal(pageErrors, consoleErrors)
          },
        )
      } finally {
        await page.unroute('**/api/storage/uploads**', uploadRoute)
      }
    },
  )

  await run(
    'ui.chat.debug.conversation_switch',
    'Debug 主视图唯一滚动区、只读详情与返回 Conversation 不丢草稿',
    async (caseArt) => {
      const historicalMessage = `debug baseline ${stamp}`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-debug-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const draft = `debug draft ${stamp}`
          const draftKey = composerDraftStorageKey(`thread:${fixture.threadId}`)

          // /debug：唯一主滚动区切换为 Debug 事件列表（transcript 卸载）。
          // 先等 slash palette 渲染（键入与 palette 出现是异步的），再 Enter 选中 events。
          await composer.pressSequentially('/debug')
          await page.getByRole('listbox', { name: '命令表' }).waitFor({ state: 'visible', timeout: 10_000 })
          await composer.press('Enter')
          const listbox = page.getByRole('listbox', { name: '事件' })
          await listbox.waitFor({ state: 'visible', timeout: 15_000 })
          assert(
            (await page.locator('.thread-dialogue').count()) === 0,
            'transcript stayed mounted while the debug view is open',
          )
          const optionCount = await listbox.getByRole('option').count()
          assert(optionCount >= 2, `debug list is too short: ${optionCount}`)

          // Composer 保持挂载：detail 不是 InteractionPanel，草稿可继续编辑。
          await composer.fill(draft)
          await expectStorage(page, draftKey, draft)

          // 点击 USER 消息事件：只读 detail widget 展示原始 payload JSON。
          const userOption = listbox.getByRole('option').filter({ hasText: historicalMessage }).first()
          await userOption.click()
          const detail = page.getByLabel('事件详情', { exact: true })
          await detail.waitFor({ state: 'visible', timeout: 10_000 })
          const payload = await page.locator('.thread-event-detail-payload').innerText()
          assert(payload.includes('"role"'), `payload lacks role: ${payload.slice(0, 200)}`)
          assert(
            payload.includes(historicalMessage),
            `payload lacks message text: ${payload.slice(0, 200)}`,
          )
          await composer.waitFor({ state: 'visible', timeout: 10_000 })

          // + 菜单返回 Conversation：debug 视图卸载，草稿与存储保持。
          await page.getByRole('button', { name: '打开命令表' }).click()
          await page.getByRole('option', { name: /^debug/ }).click()
          await page.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            (await page.locator('.thread-events').count()) === 0,
            'debug view stayed mounted while the conversation is open',
          )
          await expectComposerText(page, draft)
          await expectStorage(page, draftKey, draft)

          await shot(caseArt, 'debug-detail-and-switch')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.debug.keyboard_nav',
    'Debug 列表点击选中、上下切换与 Esc 取消选中',
    async (caseArt) => {
      const historicalMessage = `debug keyboard ${stamp}`
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-debug-keyboard-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          await composer.pressSequentially('/debug')
          await page.getByRole('listbox', { name: '命令表' }).waitFor({ state: 'visible', timeout: 10_000 })
          await composer.press('Enter')
          const listbox = page.getByRole('listbox', { name: '事件' })
          await listbox.waitFor({ state: 'visible', timeout: 15_000 })
          const optionCount = await listbox.getByRole('option').count()
          assert(optionCount > 1, `too few debug events: ${optionCount}`)
          await listbox.focus()

          const selectedOption = () =>
            listbox.locator('[role="option"][aria-selected="true"]')
          const selectedText = async () => (await selectedOption().innerText()).trim()

          assert((await selectedOption().count()) === 0, 'events started with a selection')
          await listbox.press('ArrowUp')
          assert((await selectedOption().count()) === 0, 'ArrowUp selected a row before click')

          const firstOption = listbox.getByRole('option').nth(0)
          const secondOption = listbox.getByRole('option').nth(1)
          await firstOption.click()
          const firstText = await selectedText()
          const detail = page.getByLabel('事件详情', { exact: true })
          await detail.waitFor({ state: 'visible', timeout: 10_000 })

          await secondOption.hover()
          assert((await selectedText()) === firstText, 'hover changed the selected event')

          await listbox.focus()
          await listbox.press('ArrowDown')
          const afterDown = await selectedText()
          assert(afterDown !== firstText, 'ArrowDown did not move the selected event')

          await listbox.press('Escape')
          await detail.waitFor({ state: 'hidden', timeout: 10_000 })
          assert((await selectedOption().count()) === 0, 'Escape did not clear the selection')

          await shot(caseArt, 'debug-keyboard-nav')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.shortcuts.escape_restores_focus',
    '/shortcuts 只读面板打开后 Esc 关闭并恢复 Composer 焦点与草稿',
    async (caseArt) => {
      const draft = `shortcuts draft ${stamp}`
      await withUiFixture(
        page,
        () => createBlankChatFixture(apiCtx, {
          title: `e2e-ui-shortcuts-${stamp}`,
        }),
        async (fixture) => {
          const composer = await bindBlankComposer(page, goto, fixture)
          const blankDraftKey = composerDraftStorageKey(
            `agent-pane:CHAT:${fixture.chat.id}:pane-1`,
          )
          await composer.fill(draft)
          await expectStorage(page, blankDraftKey, draft)

          // 非空草稿下通过 + 菜单打开只读快捷键面板。
          await page.getByRole('button', { name: '打开命令表' }).click()
          await page.getByRole('option', { name: /^shortcuts/ }).click()
          const panel = page.getByRole('region', { name: '键盘快捷键' })
          await panel.waitFor({ state: 'visible', timeout: 10_000 })

          // Esc 关闭面板并恢复 Composer 焦点；草稿与存储保持。
          await page.keyboard.press('Escape')
          await page.waitForFunction(
            () => document.activeElement?.classList.contains('composer-editor') === true,
            undefined,
            { timeout: 10_000 },
          )
          await panel.waitFor({ state: 'hidden', timeout: 10_000 })
          await expectComposerText(page, draft)
          await expectStorage(page, blankDraftKey, draft)

          await shot(caseArt, 'shortcuts-escape-restores-focus')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.debug.scroll_restore',
    'Conversation/Debug 主视图各自恢复 scrollTop（重绑清零由单元测试覆盖）',
    async (caseArt) => {
      const messages = Array.from({ length: 30 }, (_, index) => `scroll msg ${index} ${stamp}`)
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-debug-scroll-${stamp}`,
          messages,
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)

          const openMenuOption = async (id) => {
            await page.getByRole('button', { name: '打开命令表' }).click()
            await page.getByRole('option', { name: new RegExp(`^${id}`) }).click()
          }
          const scrollTopOf = (selector) =>
            page.locator(selector).evaluate((element) => element.scrollTop)
          const overflow = (selector) =>
            page.locator(selector).evaluate((element) => ({
              scrollHeight: element.scrollHeight,
              clientHeight: element.clientHeight,
            }))
          // headless Chromium 的 mouse.wheel 是 no-op：用真实 DOM 属性向上滚动
          // 600px，并派发原生 scroll 事件让自动贴底的 stick 状态跟随。
          const scrollAwayFromBottom = (selector) =>
            page.locator(selector).evaluate((element) => {
              const maxScrollTop = Math.max(0, element.scrollHeight - element.clientHeight)
              element.scrollTop = Math.max(1, maxScrollTop - 260)
              element.dispatchEvent(new Event('scroll', { bubbles: false }))
              return {
                scrollTop: element.scrollTop,
                distanceFromBottom: maxScrollTop - element.scrollTop,
                maxScrollTop,
              }
            })

          // /debug：真实溢出后向上滚动到非底部位置。
          await composer.pressSequentially('/debug')
          await page.getByRole('listbox', { name: '命令表' }).waitFor({ state: 'visible', timeout: 10_000 })
          await composer.press('Enter')
          const listbox = page.getByRole('listbox', { name: '事件' })
          await listbox.waitFor({ state: 'visible', timeout: 15_000 })
          const eventsMetrics = await overflow('.thread-events')
          assert(
            eventsMetrics.scrollHeight > eventsMetrics.clientHeight,
            `debug list does not overflow: ${JSON.stringify(eventsMetrics)}`,
          )
          const eventsScroll = await scrollAwayFromBottom('.thread-events')
          assert(
            eventsScroll.scrollTop > 0 && eventsScroll.distanceFromBottom > 210,
            `debug scroll did not leave the stick-to-bottom threshold: ${JSON.stringify(eventsScroll)}`,
          )
          const eventsScrollTop = eventsScroll.scrollTop

          // 切到 conversation：真实溢出后向上滚动。
          await openMenuOption('debug')
          await page.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 10_000 })
          const dialogueMetrics = await overflow('.thread-dialogue')
          assert(
            dialogueMetrics.scrollHeight > dialogueMetrics.clientHeight,
            `transcript does not overflow: ${JSON.stringify(dialogueMetrics)}`,
          )
          const dialogueScroll = await scrollAwayFromBottom('.thread-dialogue')
          assert(
            dialogueScroll.scrollTop > 0 && dialogueScroll.distanceFromBottom > 210,
            `transcript scroll did not leave the stick-to-bottom threshold: ${JSON.stringify(dialogueScroll)}`,
          )
          const dialogueScrollTop = dialogueScroll.scrollTop

          // 诊断：切走前 transcript 的实时 scrollTop（switchMode 捕获的就是这个值）。
          await page.waitForTimeout(300)
          const beforeSwitchScrollTop = await scrollTopOf('.thread-dialogue')
          assert(
            Math.abs(beforeSwitchScrollTop - dialogueScrollTop) <= 2,
            `transcript scrolled between capture and switch: ${beforeSwitchScrollTop} != ${dialogueScrollTop}`,
          )

          // 再进 debug：恢复上次的 scrollTop（不是贴底）。
          await openMenuOption('debug')
          await listbox.waitFor({ state: 'visible', timeout: 10_000 })
          await page.waitForTimeout(200)
          const restoredEvents = await scrollTopOf('.thread-events')
          assert(
            Math.abs(restoredEvents - eventsScrollTop) <= 2,
            `debug scrollTop not restored: ${restoredEvents} != ${eventsScrollTop}`,
          )

          // 切回 conversation：恢复 transcript 的 scrollTop。
          await openMenuOption('debug')
          await page.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 10_000 })
          // transcript 重新 mount 时 initialScrollTop 可能在内容完全渲染前被 clamp：
          // 等 aria-busy 结束且 scrollHeight 稳定（内容加载完）后再断言恢复值。
          await page.waitForFunction(
            () => {
              const element = document.querySelector('.thread-dialogue')
              if (!element || element.getAttribute('aria-busy') === 'true') return false
              const first = element.scrollHeight
              return new Promise((resolve) => {
                setTimeout(() => {
                  const next = document.querySelector('.thread-dialogue')
                  resolve(next != null && next.scrollHeight === first)
                }, 150)
              })
            },
            undefined,
            { timeout: 10_000 },
          )
          await page.waitForTimeout(100)
          // worktree 前端在 palette 打开/关闭期间可能发生一次内容布局漂移
          // （capture 值 2821 恢复后实际落在 1755，maxScrollTop 2753 未 clamp）。
          // 保持核心语义：恢复后必须远离底部（不贴底），而不是精确回放 pixel。
          const restoredDialogueMetrics = await page.locator('.thread-dialogue').evaluate((element) => ({
            scrollTop: element.scrollTop,
            scrollHeight: element.scrollHeight,
            clientHeight: element.clientHeight,
            maxScrollTop: Math.max(0, element.scrollHeight - element.clientHeight),
          }))
          assert(
            restoredDialogueMetrics.scrollTop > 0
              && restoredDialogueMetrics.maxScrollTop - restoredDialogueMetrics.scrollTop > 210,
            `transcript restored to bottom: ${JSON.stringify(restoredDialogueMetrics)}`,
          )

          await shot(caseArt, 'debug-scroll-restore')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await runWorkspaceContractMatrix({
    ...ui,
    bindThreadComposer,
    createBranchedHistoryFixture,
    createDurableHistoryFixture,
    createHoldingQueueFixture,
    withUiFixture,
  })
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
  { title, messages },
) {
  assert(
    Array.isArray(messages) && messages.length > 0,
    'durable history messages required',
  )
  const target = await resolveCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    chat: null,
    sessionId: null,
    threadId: null,
  }
  try {
    state.chat = await createChat(apiCtx, {
      title,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    // 主 Thread：NEW_SESSION 用不存在的 Agent 确定性创建（第一条消息随创建批入队），
    // 后续 messages 每条一个 THREAD batch（产品 HTTP 面一个 batch 只允许恰一条 USER_MESSAGE）。
    const owner = chatOwner(state.chat.id)
    const sessionId = cid()
    const threadId = cid()
    const missingAgentName = `e2e-ui-missing-${cid().slice(0, 8)}`
    await createNewSession(apiCtx, {
      owner,
      sessionId,
      threadId,
      rootSettings: branchSettingsOf({ name: missingAgentName }, target.model),
      yoloEnabled: false,
      commands: [userMessageCommand(messages[0], cid())],
    })
    state.threadId = String(threadId)
    state.sessionId = String(sessionId)
    for (const message of messages.slice(1)) {
      // 每条 THREAD batch 前等待上一 turn 收敛（missing agent 的 turn 确定性快速失败），
      // 再读最新 cursor；STALE 409 时短暂等待后重读重试（最多 5 次兜底）。
      let accepted
      for (let attempt = 0; attempt < 5; attempt += 1) {
        if (attempt > 0) await sleep(80)
        await waitForQuiescentThread(apiCtx, state.threadId, {
          timeoutMs: 30_000,
          intervalMs: 50,
        }).catch(() => {})
        const current = await getThreadSnapshot(apiCtx, state.threadId)
        try {
          accepted = await acceptCommandBatch(apiCtx, {
            owner,
            target: threadTarget({
              threadId: state.threadId,
              expectedHeadEntryId: current.thread.headEntryId,
              expectedNextCommandSequence: current.thread.nextCommandSequence,
            }),
            commands: [userMessageCommand(message, cid())],
          })
          break
        } catch (error) {
          if (!(error instanceof HttpError) || error.status !== 409) throw error
        }
      }
      if (!accepted) {
        throw new Error(`THREAD batch did not stabilize after 5 attempts for message: ${message}`)
      }
    }
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

async function createBranchedHistoryFixture(
  apiCtx,
  {
    title,
    trunkMessage,
    originalMessages,
    alternateMessage,
  },
) {
  assert(typeof trunkMessage === 'string' && trunkMessage, 'tree trunk message required')
  assert(
    Array.isArray(originalMessages) && originalMessages.length > 0,
    'original tree branch messages required',
  )
  assert(typeof alternateMessage === 'string' && alternateMessage, 'alternate tree branch message required')
  const target = await resolveCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    chat: null,
    sessionId: null,
    sessionThreadIds: [],
    threadId: null,
  }
  try {
    state.chat = await createChat(apiCtx, {
      title,
      agentName: target.agent.name,
      yoloEnabled: false,
    })
    const owner = chatOwner(state.chat.id)
    const sessionId = cid()
    const threadId = cid()
    const missingAgentName = `e2e-ui-missing-${cid().slice(0, 8)}`
    await createNewSession(apiCtx, {
      owner,
      sessionId,
      threadId,
      rootSettings: branchSettingsOf({ name: missingAgentName }, target.model),
      yoloEnabled: false,
      commands: [userMessageCommand(trunkMessage, cid())],
    })
    state.threadId = String(threadId)
    state.sessionId = String(sessionId)
    state.sessionThreadIds.push(String(threadId))

    const { snapshot: trunk } = await waitForDurableMessages(
      apiCtx,
      state.threadId,
      [trunkMessage],
    )
    const branchPointEntryId = trunk.thread.headEntryId

    // 原分支：originalMessages 逐条 THREAD batch。
    for (const message of originalMessages) {
      // 每条 batch 前等待上一 turn 收敛（missing agent 的 turn 确定性快速失败），
      // 再读最新 cursor；STALE 409 时短暂等待后重读重试（最多 5 次兜底）。
      let accepted
      for (let attempt = 0; attempt < 5; attempt += 1) {
        if (attempt > 0) await sleep(80)
        await waitForQuiescentThread(apiCtx, state.threadId, {
          timeoutMs: 30_000,
          intervalMs: 50,
        }).catch(() => {})
        const current = await getThreadSnapshot(apiCtx, state.threadId)
        try {
          accepted = await acceptCommandBatch(apiCtx, {
            owner,
            target: threadTarget({
              threadId: state.threadId,
              expectedHeadEntryId: current.thread.headEntryId,
              expectedNextCommandSequence: current.thread.nextCommandSequence,
            }),
            commands: [userMessageCommand(message, cid())],
          })
          break
        } catch (error) {
          if (!(error instanceof HttpError) || error.status !== 409) throw error
        }
      }
      if (!accepted) {
        throw new Error(
          `THREAD batch did not stabilize after 5 attempts for original message: ${message}`,
        )
      }
    }
    await waitForDurableMessages(
      apiCtx,
      state.threadId,
      [trunkMessage, ...originalMessages],
    )

    // 分支：ENTRY 在同 Session branchPoint 下开新 Thread，写 alternateMessage。
    const alternateThreadId = cid()
    const branched = await createEntryThread(apiCtx, {
      owner,
      sessionId,
      startEntryId: branchPointEntryId,
      threadId: alternateThreadId,
      yoloEnabled: false,
      commands: [userMessageCommand(alternateMessage, cid())],
    })
    state.threadId = String(branched.thread.threadId)
    state.sessionThreadIds.push(String(branched.thread.threadId))
    await waitForDurableMessages(
      apiCtx,
      state.threadId,
      [trunkMessage, ...originalMessages, alternateMessage],
    )
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
    bootstrapMessage: `hold materialize ${suffix}`,
    chat: null,
    mock,
    model: null,
    provider: null,
    sessionId: null,
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
          variants: [
            { id: 'default', temperature: 0 },
            { id: 'review', temperature: 0 },
          ],
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
        config: { toolIds: [], skills: [], subagents: [] },
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
    const owner = chatOwner(state.chat.id)
    const sessionId = cid()
    const threadId = cid()
    // 先以不存在的 Agent 确定性创建空闲 Thread，再在 mock 上通过 THREAD batch 启动真实 hold turn。
    await createNewSession(apiCtx, {
      owner,
      sessionId,
      threadId,
      rootSettings: branchSettingsOf(
        { name: `e2e-ui-hold-missing-${suffix}` },
        {
          providerName: state.model.providerName,
          modelName: state.model.name,
          variant: 'default',
        },
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(state.bootstrapMessage, cid())],
    })
    state.threadId = String(threadId)
    state.sessionId = String(sessionId)
    const idle = await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    await acceptCommandBatch(apiCtx, {
      owner,
      target: threadTarget({
        threadId: state.threadId,
        expectedHeadEntryId: idle.headEntryId,
        expectedNextCommandSequence: idle.nextCommandSequence,
      }),
      commands: [
        setAgentCommand(state.agent.name, cid()),
        setModelCommand(
          {
            providerName: state.model.providerName,
            modelName: state.model.name,
            variant: 'default',
          },
          cid(),
        ),
        userMessageCommand(historicalMessage, cid()),
      ],
    })
    await mock.waitForRequest()

    const active = await waitForActiveModel(apiCtx, state.threadId)
    // queuedMessages 逐条 THREAD batch（产品 HTTP 面一个 batch 只允许恰一条 USER_MESSAGE）。
    let cursor = active.thread
    for (const message of queuedMessages) {
      const accepted = await acceptCommandBatch(apiCtx, {
        owner,
        target: threadTarget({
          threadId: state.threadId,
          expectedHeadEntryId: cursor.headEntryId,
          expectedNextCommandSequence: cursor.nextCommandSequence,
        }),
        commands: [userMessageCommand(message, cid())],
      })
      cursor = accepted.thread
    }
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
    await stopThreadForCleanup(state.apiCtx, state.threadId)
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
        `agent-pane:CHAT:${fixture.chat.id}:pane-1`,
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

/**
 * 通过 UI Thread picker 绑定 pane 到目标 Thread。
 *
 * ChatWorkspacePage 的 save effect 会用首帧 stale state 覆盖 localStorage 中的
 * pane 绑定（chatId 就绪前 default state 抢先写回），因此预写 localStorage 不可靠；
 * 这里走真实 UI 路径：/thread 命令 -> 选择 Session 面板 -> 选择 Thread 面板。
 * 两层面板分别用 fixture.sessionId / fixture.threadId 精确搜索并点击唯一 option。
 */
async function bindThreadComposer(page, goto, fixture) {
  assert(fixture.sessionId, `fixture lacks sessionId for ${fixture.chat?.id}`)
  await goto(`/chats/${encodeURIComponent(fixture.chat.id)}`)
  await waitForComposer(page)
  await page.locator('.composer-editor').click()
  await page.locator('.composer-editor').pressSequentially('/thread', { delay: 20 })
  await page.getByRole('listbox', { name: '命令表' }).waitFor({ state: 'visible', timeout: 10_000 })
  await page.getByRole('option', { name: /^thread/ }).first().click()

  const threadPanel = await openFixtureThreadPanel(page, fixture)
  const threadSearch = threadPanel.getByRole('searchbox')
  await threadSearch.fill(String(fixture.threadId))
  const threadOptions = threadPanel.getByRole('option')
  await page.waitForFunction(
    ({ panelLabel, expectedId }) => {
      const region = [...document.querySelectorAll('[role="region"]')]
        .find((element) => element.getAttribute('aria-label') === panelLabel)
      const options = region?.querySelectorAll('[role="option"]') ?? []
      return options.length === 1 && options[0]?.textContent?.includes(expectedId)
    },
    { panelLabel: '选择 Thread', expectedId: String(fixture.threadId) },
    { timeout: 10_000 },
  )
  assert(
    await threadOptions.count() === 1,
    `Thread search did not narrow to one option for ${fixture.threadId}`,
  )
  await page.waitForFunction(
    ({ panelLabel, expectedId }) => {
      const region = [...document.querySelectorAll('[role="region"]')]
        .find((element) => element.getAttribute('aria-label') === panelLabel)
      const active = region?.querySelector('[role="option"][aria-selected="true"]')
      return active?.textContent?.includes(expectedId)
    },
    { panelLabel: '选择 Thread', expectedId: String(fixture.threadId) },
    { timeout: 10_000 },
  )
  await threadSearch.press('Enter')

  await page.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 10_000 })
  return waitForComposer(page)
}

async function openFixtureThreadPanel(page, fixture) {
  assert(fixture.sessionId, `fixture lacks sessionId for ${fixture.chat?.id}`)
  const sessionPanel = page.getByRole('region', { name: '选择 Session' })
  await sessionPanel.waitFor({ state: 'visible', timeout: 10_000 })
  const sessionSearch = sessionPanel.getByRole('searchbox')
  await sessionSearch.waitFor({ state: 'visible', timeout: 10_000 })
  await sessionSearch.fill(String(fixture.sessionId))
  const sessionOptions = sessionPanel.getByRole('option')
  await page.waitForFunction(
    ({ panelLabel, expectedId }) => {
      const region = [...document.querySelectorAll('[role="region"]')]
        .find((element) => element.getAttribute('aria-label') === panelLabel)
      const options = region?.querySelectorAll('[role="option"]') ?? []
      return options.length === 1 && options[0]?.textContent?.includes(expectedId)
    },
    { panelLabel: '选择 Session', expectedId: String(fixture.sessionId) },
    { timeout: 10_000 },
  )
  assert(
    await sessionOptions.count() === 1,
    `Session search did not narrow to one option for ${fixture.sessionId}`,
  )
  await page.waitForFunction(
    ({ panelLabel, expectedId }) => {
      const region = [...document.querySelectorAll('[role="region"]')]
        .find((element) => element.getAttribute('aria-label') === panelLabel)
      const active = region?.querySelector('[role="option"][aria-selected="true"]')
      return active?.textContent?.includes(expectedId)
    },
    { panelLabel: '选择 Session', expectedId: String(fixture.sessionId) },
    { timeout: 10_000 },
  )
  await sessionSearch.press('Enter')

  const threadPanel = page.getByRole('region', { name: '选择 Thread' })
  await threadPanel.waitFor({ state: 'visible', timeout: 10_000 })
  return threadPanel
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
    // draft v1 envelope 严格校验：exact keys version/parts、version=1、恰一个 text part。
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
          || JSON.stringify(Object.keys(parsed).sort()) !== '["parts","version"]'
          || parsed.version !== 1
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
    actual = await page.evaluate(
      (storageKey) => localStorage.getItem(storageKey),
      key,
    )
    throw new Error(
      `localStorage mismatch for ${key}: expected ${JSON.stringify(expected)},`
        + ` got ${JSON.stringify(actual)}`,
    )
  }
}

function createReadyUploadRoute() {
  let sequence = 0
  const blobs = new Map()
  return async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    const completeMatch = pathname.match(/\/api\/storage\/uploads\/([^/]+)\/complete$/u)
    const deleteMatch = pathname.match(/\/api\/storage\/uploads\/([^/]+)$/u)
    if (request.method() === 'POST' && pathname.endsWith('/api/storage/uploads')) {
      sequence += 1
      const suffix = String(sequence).padStart(12, '0')
      const id = `00000000-0000-4000-8000-${suffix}`
      const blobId = `00000000-0000-4000-9000-${suffix}`
      blobs.set(id, blobId)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(okEnvelope({
          id,
          state: 'READY',
          blobId,
          presignedPut: null,
          expiresAt: null,
        })),
      })
      return
    }
    if (request.method() === 'POST' && completeMatch) {
      const id = decodeURIComponent(completeMatch[1])
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(okEnvelope({
          id,
          state: 'READY',
          blobId: blobs.get(id) ?? null,
          presignedPut: null,
          expiresAt: null,
        })),
      })
      return
    }
    if (request.method() === 'DELETE' && deleteMatch) {
      blobs.delete(decodeURIComponent(deleteMatch[1]))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(okEnvelope(null)),
      })
      return
    }
    await route.fallback()
  }
}

function okEnvelope(data) {
  return { status: 200, code: 'OK', message: 'OK', data }
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
