import { EventEmitter, once } from 'node:events'
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import path from 'node:path'

import { baseModelConfig } from '../lib/fixtures.mjs'
import {
  assert,
  assertDecimalVersion,
  cid,
  HttpError,
  sleep,
} from '../lib/http.mjs'
import { REPO_ROOT } from '../../../lib/repo-root.mjs'
import { runWorkspaceContractMatrix, waitForThreadSnapshot } from './workspace-contracts.mjs'
export { expectStorage } from './workspace-helpers.mjs'
import {
  activeOptionText,
  bindBlankComposer as bindWorkspaceBlankComposer,
  bindThreadComposer as bindWorkspaceThreadComposer,
  caretOffset,
  cleanupWorkspaceFixtureState,
  composerMetrics,
  expectComposerText as expectWorkspaceComposerText,
  expectStorage as expectWorkspaceStorage,
  previewDialogLabel,
  resolveCatalogTarget as resolveWorkspaceCatalogTarget,
  setCaretOffset,
  UI_LABELS,
  waitForActiveModel as waitForWorkspaceActiveModel,
  waitForComposer as waitForWorkspaceComposer,
  waitForPlaceholder as waitForWorkspacePlaceholder,
  waitForQueuedMessages as waitForWorkspaceQueuedMessages,
  withUiFixture as withWorkspaceUiFixture,
} from './workspace-helpers.mjs'
import {
  acceptCommandBatch,
  createWorkspaceSession,
  createWorkspaceSpace,
  getThreadSnapshot,
  setExecutionCommand,
  threadTarget,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'

const TINY_IMAGE = readFileSync(
  path.join(
    REPO_ROOT,
    'apps/canvas/runtime/src/main/resources/fun/fengwk/kkstudio/canvas/infra/function/fake/tiny.png',
  ),
)
const TINY_VIDEO = readFileSync(
  path.join(REPO_ROOT, 'scripts/dev/verify/e2e/ui/fixtures/tiny.mp4'),
)

/**
 * stopped Thread 的退出尚未收敛（conflict + EXIT_UNCONFIRMED）是唯一可等待后重试的冲突：
 * 单条 USER_MESSAGE 批次没有 cursor，其它 409 都是契约漂移，必须原样失败。
 */
function isExitUnconfirmedConflict(error) {
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

export async function runComposerMatrix(ui) {
  const {
    apiCtx,
    consoleErrors,
    ensureSignedIn,
    expectNoFatal,
    goto,
    page,
    pageErrors,
    run,
    shot,
    stamp,
  } = ui

  await run(
    'ui.workspace.composer.history_order_boundaries',
    'Composer 按 durable、queued、草稿顺序导航且两端不循环',
    async (caseArt) => {
      await ensureSignedIn?.()
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-composer-order-${stamp}`,
          historicalMessage: `durable history ${stamp}`,
          queuedMessages: [`queued 1 ${stamp}`, `queued 2 ${stamp}`],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draft = `local draft ${stamp}`
          const draftKey = { threadId: fixture.threadId }
          await composer.fill(draft)
          await expectWorkspaceStorage(page, draftKey, draft)

          for (const expected of [
            `queued 2 ${stamp}`,
            `queued 1 ${stamp}`,
            `durable history ${stamp}`,
            fixture.bootstrapMessage,
            fixture.bootstrapMessage,
          ]) {
            await composer.press('ArrowUp')
            await expectWorkspaceComposerText(page, expected)
            await expectWorkspaceStorage(page, draftKey, draft)
          }
          for (const expected of [
            `durable history ${stamp}`,
            `queued 1 ${stamp}`,
            `queued 2 ${stamp}`,
            draft,
            draft,
          ]) {
            await composer.press('ArrowDown')
            await expectWorkspaceComposerText(page, expected)
            await expectWorkspaceStorage(page, draftKey, draft)
          }

          await shot(caseArt, 'composer-history-order-boundaries')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.duplicate_entries',
    'Composer 对相同文本的 durable/queued 条目仍保持独立游标',
    async (caseArt) => {
      await ensureSignedIn?.()
      const duplicate = `duplicate message ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-composer-duplicate-${stamp}`,
          historicalMessage: duplicate,
          queuedMessages: [duplicate],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draft = `duplicate scratch ${stamp}`
          await composer.fill(draft)

          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, duplicate)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, duplicate)
          await composer.press('ArrowDown')
          await expectWorkspaceComposerText(page, duplicate)
          await composer.press('ArrowDown')
          await expectWorkspaceComposerText(page, draft)

          await shot(caseArt, 'composer-history-duplicate-entries')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.multiline_scroll',
    'Composer 召回溢出多行历史后光标与内部滚动同步到末尾',
    async (caseArt) => {
      await ensureSignedIn?.()
      const currentDraft = `current composer draft ${stamp}`
      const historicalMessage = Array.from(
        { length: 24 },
        (_, index) =>
          `UI-E2E-COMPOSER-HISTORY-${stamp}-${String(index + 1).padStart(2, '0')}`,
      ).join('\n')
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-scroll-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          await composer.fill(currentDraft)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, historicalMessage)

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
          await expectWorkspaceComposerText(page, currentDraft)
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.multiline_caret_boundaries',
    'Composer 多行中间位置保留原生方向键，仅首行边界召回历史',
    async (caseArt) => {
      await ensureSignedIn?.()
      const historicalMessage = `caret boundary history ${stamp}`
      const currentDraft = 'line 1\nline 2\nline 3'
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-caret-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          await composer.fill(currentDraft)

          await setCaretOffset(composer, currentDraft.length)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, currentDraft)
          const afterNativeUp = await caretOffset(composer)
          assert(
            afterNativeUp > 0 && afterNativeUp < currentDraft.length,
            `ArrowUp did not stay in multiline editing: ${afterNativeUp}`,
          )

          await setCaretOffset(composer, 0)
          await composer.press('ArrowDown')
          await expectWorkspaceComposerText(page, currentDraft)
          const afterNativeDown = await caretOffset(composer)
          assert(
            afterNativeDown > 0 && afterNativeDown < currentDraft.length,
            `ArrowDown did not stay in multiline editing: ${afterNativeDown}`,
          )

          await setCaretOffset(composer, 0)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, historicalMessage)
          await shot(caseArt, 'composer-history-multiline-caret-boundary')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.edit_recalled',
    '编辑召回消息后提升为新草稿并可刷新恢复',
    async (caseArt) => {
      await ensureSignedIn?.()
      const historicalMessage = `editable history ${stamp}`
      const originalDraft = `original scratch ${stamp}`
      const editedDraft = `${historicalMessage} edited`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-edit-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          let composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draftKey = { threadId: fixture.threadId }
          await composer.fill(originalDraft)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, historicalMessage)
          await page.keyboard.type(' edited')
          await expectWorkspaceComposerText(page, editedDraft)
          await expectWorkspaceStorage(page, draftKey, editedDraft)

          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, historicalMessage)
          await composer.press('ArrowDown')
          await expectWorkspaceComposerText(page, editedDraft)

          await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
          composer = await waitForWorkspaceComposer(page)
          await expectWorkspaceStorage(page, draftKey, editedDraft)
          await expectWorkspaceComposerText(page, editedDraft)
          await shot(caseArt, 'composer-history-edited-recalled')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.draft_persistence_boundaries',
    '空 Pane 草稿精确刷新恢复，空白内容清存储且无历史时上键不变',
    async (caseArt) => {
      await ensureSignedIn?.()
      await withWorkspaceUiFixture(
        page,
        () => createBlankWorkspaceFixture(apiCtx, {
          title: `e2e-ui-composer-persistence-${stamp}`,
        }),
        async (fixture) => {
          let { composer, draftKey } = await bindWorkspaceBlankComposer(page, goto, fixture)
          const exactDraft = '  first line\nsecond line  '

          await composer.fill(`no history ${stamp}`)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, `no history ${stamp}`)

          await composer.fill('  first line')
          await composer.press('Shift+Enter')
          await page.keyboard.type('second line  ')
          await expectWorkspaceStorage(page, draftKey, exactDraft)
          await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
          composer = await waitForWorkspaceComposer(page)
          await expectWorkspaceComposerText(page, exactDraft)
          await expectWorkspaceStorage(page, draftKey, exactDraft)

          await composer.fill(' ')
          await composer.press('Shift+Enter')
          await page.keyboard.type(' ')
          await expectWorkspaceStorage(page, draftKey, null)
          await waitForWorkspacePlaceholder(composer, true)
          await composer.fill('')
          await expectWorkspaceStorage(page, draftKey, null)
          await waitForWorkspacePlaceholder(composer, true)

          await shot(caseArt, 'composer-draft-persistence-boundaries')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.palette_precedence',
    '命令菜单打开时方向键只导航命令，不召回消息或覆盖草稿',
    async (caseArt) => {
      await ensureSignedIn?.()
      const historicalMessage = `palette history ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-palette-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draft = `palette scratch ${stamp}`
          const draftKey = { threadId: fixture.threadId }
          await composer.fill(draft)
          await page.getByRole('button', { name: UI_LABELS.openCommandsButton }).click()
          const palette = page.getByRole('listbox', { name: UI_LABELS.commandPalette })
          await palette.waitFor({ state: 'visible', timeout: 10_000 })
          const activeBefore = await activeOptionText(palette)
          await composer.focus()
          await composer.press('ArrowDown')
          const activeAfter = await activeOptionText(palette)
          assert(
            activeBefore !== activeAfter,
            `ArrowDown did not move the active command: ${activeBefore}`,
          )
          await expectWorkspaceComposerText(page, draft)
          await expectWorkspaceStorage(page, draftKey, draft)
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, draft)
          await composer.press('Escape')
          await palette.waitFor({ state: 'hidden', timeout: 10_000 })
          await expectWorkspaceComposerText(page, draft)

          await composer.fill('/sto')
          await palette.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            await page.locator('.thread-command-search').count() === 0,
            'slash palette duplicated the Composer query in a search header',
          )
          await shot(caseArt, 'composer-command-palette-without-duplicate-search')
          await composer.press('ArrowUp')
          await expectWorkspaceComposerText(page, '/sto')
          await composer.press('Escape')
          await palette.waitFor({ state: 'hidden', timeout: 10_000 })
          await expectWorkspaceComposerText(page, '/sto')
          await expectWorkspaceStorage(page, draftKey, '/sto')

          await shot(caseArt, 'composer-command-palette-precedence')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.escape_refocus',
    'Esc 从 transcript 文字选区回到当前 Pane Composer，并保留草稿',
    async (caseArt) => {
      await ensureSignedIn?.()
      const historicalMessage = `escape refocus history ${stamp}`
      const draft = `escape refocus draft ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-escape-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draftKey = { threadId: fixture.threadId }
          await composer.fill(draft)
          await expectWorkspaceStorage(page, draftKey, draft)

          const transcriptMessage = page
            .locator('[data-pane-active] .thread-dialogue')
            .getByText(historicalMessage, { exact: true })
            .first()
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
          await expectWorkspaceComposerText(page, draft)
          await expectWorkspaceStorage(page, draftKey, draft)
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
    'ui.workspace.composer.submit_clears_draft',
    '提交后清除 Composer 与 Thread 持久草稿，并在 timeline 中保留用户消息',
    async (caseArt) => {
      await ensureSignedIn?.()
      const submitted = `submitted composer message ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-submit-${stamp}`,
          messages: [`submit baseline ${stamp}`],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draftKey = { threadId: fixture.threadId }
          await composer.fill(submitted)
          await expectWorkspaceStorage(page, draftKey, submitted)
          await page.getByRole('button', { name: UI_LABELS.sendButton }).click()

          await expectWorkspaceComposerText(page, '')
          await expectWorkspaceStorage(page, draftKey, null)
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
    'ui.workspace.thread.snapshot_reload',
    '生成期间重载已绑定的 Thread，浏览器通过真实 WebSocket 快照恢复并完成对话',
    async (caseArt) => {
      await ensureSignedIn?.()
      const baseline = `reload baseline ${stamp}`
      const submitted = `reload input ${stamp}`
      const reply = `Reload reply ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-thread-reload-${stamp}`,
          historicalMessage: baseline,
        }),
        async (fixture) => {
          const { mock, threadId } = fixture
          mock.release(await mock.waitForRequest(baseline), 'Ready to reload.')
          await waitForQuiescentThread(apiCtx, threadId)
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const dialogue = page.locator('.thread-dialogue')
          const working = page.locator('.thread-working')
          await composer.fill(submitted)
          await page.getByRole('button', { name: UI_LABELS.sendButton }).click()
          const held = await mock.waitForRequest(submitted)
          await working.waitFor({ state: 'visible', timeout: 15_000 })

          let bootstrapped = false
          let navigations = 0
          let threadGetsAfterBootstrap = 0
          const threadPath = `/api/harness/threads/${threadId}`
          const onNavigation = (frame) => {
            if (frame === page.mainFrame()) navigations += 1
          }
          const onRequest = (request) => {
            if (bootstrapped && request.method() === 'GET' && new URL(request.url()).pathname === threadPath) {
              threadGetsAfterBootstrap += 1
            }
          }
          page.on('framenavigated', onNavigation)
          page.on('request', onRequest)
          try {
            // Arm before reload; an ACK from any other socket/resource cannot qualify the snapshot.
            const [, reload] = await Promise.all([
              waitForThreadSnapshot(page, threadId, (snapshot) => {
                bootstrapped = true
                assert(snapshot?.thread?.threadId === threadId, 'snapshot belongs to the wrong Thread')
                assertDecimalVersion(snapshot.version, 'snapshot.version')
                assertDecimalVersion(snapshot.thread.version, 'snapshot.thread.version')
                assertDecimalVersion(snapshot.thread.nextCommandSequence, 'snapshot.thread.nextCommandSequence')
                assert(snapshot.version === snapshot.thread.version, 'snapshot versions differ')
              }),
              page.reload({ waitUntil: 'domcontentloaded', timeout: 15_000 }),
            ])
            assert(reload?.ok(), 'Thread page reload failed')
            await working.waitFor({ state: 'visible', timeout: 15_000 })
            mock.release(held, reply)
            await dialogue.getByText(reply, { exact: true }).waitFor({ state: 'visible', timeout: 15_000 })
            await working.waitFor({ state: 'hidden', timeout: 15_000 })

            // Independently confirm durable completion without giving the browser an HTTP refresh.
            await waitForQuiescentThread(apiCtx, threadId)
            const final = await getThreadSnapshot(apiCtx, threadId)
            const head = final.entries.at(-1)
            assert(final.thread.status === 'IDLE' && head?.entryId === final.thread.headEntryId
              && head?.entryType === 'TURN_END', 'Thread must finish at a durable, IDLE TURN_END')
            await shot(caseArt, 'thread-snapshot-reload-complete')
            assert(navigations === 1, `expected only one reload, got ${navigations} main-frame navigations`)
            assert(threadGetsAfterBootstrap === 0, `unexpected Thread GETs after WS bootstrap: ${threadGetsAfterBootstrap}`)
            expectNoFatal(pageErrors, consoleErrors)
          } finally {
            page.off('framenavigated', onNavigation)
            page.off('request', onRequest)
          }
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.attachment_previews',
    'Composer 附件只显示文件名，并按图片、视频和通用文件类型展示缩略图或图标',
    async (caseArt) => {
      await ensureSignedIn?.()
      const uploadRoute = createReadyUploadRoute()
      await page.route('**/api/platform/storage/uploads**', uploadRoute)
      try {
        await withWorkspaceUiFixture(
          page,
          () => createWorkspaceDurableHistoryFixture(apiCtx, {
            title: `e2e-ui-composer-attachments-${stamp}`,
            messages: [`attachment baseline ${stamp}`],
          }),
          async (fixture) => {
            await bindWorkspaceThreadComposer(page, goto, fixture)
            // 图片名必须长到超出 200px 固定卡片的名字区，才能验证省略号截断而不是刚好放得下。
            const imageName = `scene-${stamp}-wide-establishing-shot-of-the-skyline.png`
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

            const strip = page.getByRole('list', { name: UI_LABELS.attachmentStrip })
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
            const lightbox = page.getByRole('dialog', { name: previewDialogLabel(imageName) })
            await lightbox.waitFor({ state: 'visible', timeout: 5_000 })
            assert(
              await lightbox.locator('.media-lightbox-content img').count() === 1,
              'image attachment lightbox lacks the full image',
            )
            await shot(caseArt, 'composer-attachment-lightbox')
            await page.keyboard.press('Escape')
            await lightbox.waitFor({ state: 'hidden', timeout: 5_000 })
            expectNoFatal(pageErrors, consoleErrors)
          },
        )
      } finally {
        await page.unroute('**/api/platform/storage/uploads**', uploadRoute)
      }
    },
  )

  await run(
    'ui.workspace.debug.conversation_switch',
    'Debug 独占工作区、只读详情与单顶栏关闭不丢草稿',
    async (caseArt) => {
      await ensureSignedIn?.()
      const historicalMessage = `debug baseline ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-debug-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const draft = `debug draft ${stamp}`
          const draftKey = { threadId: fixture.threadId }

          // plus 命令保留已写草稿；Debug 中 Composer 挂载但完全失活。
          await composer.fill(draft)
          await expectWorkspaceStorage(page, draftKey, draft)
          await page.getByRole('button', { name: UI_LABELS.openCommandsButton }).click()
          await page.getByRole('option', { name: /^debug/ }).click()
          const listbox = page.getByRole('listbox', { name: UI_LABELS.eventsTab })
          await listbox.waitFor({ state: 'visible', timeout: 15_000 })
          assert(
            (await page.locator('.thread-dialogue').count()) === 0,
            'transcript stayed mounted while the debug view is open',
          )
          const optionCount = await listbox.getByRole('option').count()
          assert(optionCount >= 2, `debug list is too short: ${optionCount}`)

          const hiddenComposer = page.locator('.composer-editor')
          assert((await hiddenComposer.count()) === 1 && !(await hiddenComposer.isVisible()), 'debug composer is not mounted and hidden')
          assert((await page.getByRole('button', { name: UI_LABELS.closeDebugButton, exact: true }).count()) === 1, 'debug must have one header exit')
          await expectWorkspaceStorage(page, draftKey, draft)

          // 点击 USER 消息事件：只读 detail widget 展示原始 payload JSON。
          const userOption = listbox.getByRole('option').filter({ hasText: historicalMessage }).first()
          await userOption.click()
          const detail = page.getByLabel(UI_LABELS.eventDetailsHeading, { exact: true })
          await detail.waitFor({ state: 'visible', timeout: 10_000 })
          const payload = await page.locator('.thread-event-detail-payload').innerText()
          assert(payload.includes('"role"'), `payload lacks role: ${payload.slice(0, 200)}`)
          assert(
            payload.includes(historicalMessage),
            `payload lacks message text: ${payload.slice(0, 200)}`,
          )
          // 单顶栏关闭：debug 视图卸载，原布局、草稿与存储保持。
          await page.getByRole('button', { name: UI_LABELS.closeDebugButton, exact: true }).click()
          await page.locator('.thread-dialogue').waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            (await page.locator('.thread-events').count()) === 0,
            'debug view stayed mounted while the conversation is open',
          )
          await expectWorkspaceComposerText(page, draft)
          await expectWorkspaceStorage(page, draftKey, draft)

          await shot(caseArt, 'debug-detail-and-switch')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.debug.keyboard_nav',
    'Debug 列表点击选中、上下切换与 Esc 取消选中',
    async (caseArt) => {
      await ensureSignedIn?.()
      const historicalMessage = `debug keyboard ${stamp}`
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-debug-keyboard-${stamp}`,
          messages: [historicalMessage],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          await composer.pressSequentially('/debug')
          await page.getByRole('listbox', { name: UI_LABELS.commandPalette }).waitFor({ state: 'visible', timeout: 10_000 })
          await composer.press('Enter')
          const listbox = page.getByRole('listbox', { name: UI_LABELS.eventsTab })
          await listbox.waitFor({ state: 'visible', timeout: 15_000 })
          const optionCount = await listbox.getByRole('option').count()
          assert(optionCount > 1, `too few debug events: ${optionCount}`)
          await listbox.focus()

          const selectedOption = () =>
            listbox.locator('[role="option"][aria-selected="true"]')
          const selectedText = async () => (await selectedOption().innerText()).trim()

          // 初选由 Debug 视图在事件加载后的 effect 写入：列表先渲染、选中随后到位，不能立刻计数。
          await selectedOption().first().waitFor({ state: 'visible', timeout: 10_000 })
          assert((await selectedOption().count()) === 1, 'latest model output was not initially selected')

          const firstOption = listbox.getByRole('option').nth(0)
          const secondOption = listbox.getByRole('option').nth(1)
          await firstOption.click()
          const firstText = await selectedText()
          const detail = page.getByLabel(UI_LABELS.eventDetailsHeading, { exact: true })
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
          assert(await page.getByRole('button', { name: UI_LABELS.closeDebugButton, exact: true }).isVisible(), 'inner Escape also exited Debug')
          await listbox.press('Escape')
          await composer.waitFor({ state: 'visible', timeout: 10_000 })
          await page.waitForFunction(() => document.activeElement?.classList.contains('composer-editor'))

          await shot(caseArt, 'debug-keyboard-nav')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.debug.scroll_restore',
    'Conversation/Debug 主视图各自恢复 scrollTop（重绑清零由单元测试覆盖）',
    async (caseArt) => {
      await ensureSignedIn?.()
      const messages = Array.from({ length: 30 }, (_, index) => `scroll msg ${index} ${stamp}`)
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-debug-scroll-${stamp}`,
          messages,
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)

          const openMenuOption = async (id) => {
            await page.getByRole('button', { name: UI_LABELS.openCommandsButton }).click()
            await page.getByRole('option', { name: new RegExp(`^${id}`) }).click()
          }
          const scrollTopOf = (selector) =>
            page.locator(selector).evaluate((element) => element.scrollTop)
          const overflow = (selector) =>
            page.locator(selector).evaluate((element) => ({
              scrollHeight: element.scrollHeight,
              clientHeight: element.clientHeight,
            }))
          // headless Chromium 的 mouse.wheel 是 no-op：先派发负向 wheel 意图，
          // 再修改 DOM scrollTop 并派发原生 scroll 事件，满足自动贴底的用户滚动意图契约。
          const scrollAwayFromBottom = (selector) =>
            page.locator(selector).evaluate((element) => {
              const maxScrollTop = Math.max(0, element.scrollHeight - element.clientHeight)
              element.dispatchEvent(new WheelEvent('wheel', { deltaY: -260, bubbles: true }))
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
          await page.getByRole('listbox', { name: UI_LABELS.commandPalette }).waitFor({ state: 'visible', timeout: 10_000 })
          await composer.press('Enter')
          const listbox = page.getByRole('listbox', { name: UI_LABELS.eventsTab })
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
          await page.getByRole('button', { name: UI_LABELS.closeDebugButton, exact: true }).click()
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

          // 切走前只校验仍处于回看状态；字体/内容布局可能触发浏览器 scroll anchoring，
          // switchMode 会捕获点击瞬间的真实 scrollTop，不应要求早先写入的像素值完全不变。
          await page.waitForTimeout(300)
          const beforeSwitchMetrics = await page.locator('.thread-dialogue').evaluate((element) => ({
            scrollTop: element.scrollTop,
            distanceFromBottom: Math.max(
              0,
              element.scrollHeight - element.clientHeight - element.scrollTop,
            ),
          }))
          assert(
            beforeSwitchMetrics.scrollTop > 0 && beforeSwitchMetrics.distanceFromBottom > 210,
            `transcript returned to bottom before switch: ${JSON.stringify(beforeSwitchMetrics)}`,
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
          await page.getByRole('button', { name: UI_LABELS.closeDebugButton, exact: true }).click()
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
    createWorkspaceDurableHistoryFixture,
    createWorkspaceHoldingQueueFixture,
  })
}

async function createWorkspaceHoldingQueueFixture(
  apiCtx,
  { title, historicalMessage, queuedMessages = [] },
) {
  const suffix = cid().slice(0, 8)
  const mock = new HoldingOpenAiMock()
  const state = {
    apiCtx,
    agent: null,
    bootstrapMessage: `hold materialize ${suffix}`,
    space: null,
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
      '/api/platform/providers',
      {
        name: `e2e-ui-hold-provider-${suffix}`,
        description: 'Local hold provider for free UI queue tests.',
        providerType: 'openai',
        baseUrl: mock.baseUrl('/v1'),
        credential: { operation: 'SET', value: `e2e-ui-hold-${suffix}` },
        modelCallTimeoutMillis: '60000',
        modelCallIdleTimeoutMillis: '60000',
      },
    )
    assert(
      providerResponse.status === 201,
      `create hold provider: ${JSON.stringify(providerResponse)}`,
    )
    state.provider = providerResponse.json

    const modelResponse = await apiCtx.call(
      'POST',
      '/api/platform/models',
      {
        type: 'MODEL',
        providerName: state.provider.name,
        name: `e2e-ui-hold-model-${suffix}`,
        modelId: `wire-ui-hold-${suffix}`,
        description: 'Local hold model for free UI queue tests.',
        config: baseModelConfig({
          limit: { context: 4096, output: 128 },
          abilities: {
            tools: false,
            reasoning: false,
            inputModalities: ['TEXT'],
          },
          variants: [{ id: 'default', promptCacheRetention: 'SHORT' }, { id: 'review', promptCacheRetention: 'SHORT' }],
        }),
      },
    )
    assert(
      modelResponse.status === 201,
      `create hold model: ${JSON.stringify(modelResponse)}`,
    )
    state.model = modelResponse.json

    const agentResponse = await apiCtx.call(
      'POST',
      '/api/platform/agents',
      {
        name: `e2e-ui-hold-agent-${suffix}`,
        description: 'Local hold agent for free UI queue tests.',
        systemPrompt: 'Wait for the local E2E stream.',
        execution: { driver: 'builtin', settings: { modelName: state.model.name, variant: 'default' } },
        config: { tools: [], skills: [], subagents: [] },
      },
    )
    assert(
      agentResponse.status === 201,
      `create hold agent: ${JSON.stringify(agentResponse)}`,
    )
    state.agent = agentResponse.json

    state.space = await createWorkspaceSpace(apiCtx, {
      title,
      defaultAgentName: state.agent.name,
    })
    const created = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: state.bootstrapMessage }],
      modelSelection: {
        modelName: `${state.model.name}-missing`,
        variant: 'default',
      },
      yoloEnabled: false,
    })
    state.threadId = String(created.threadId)
    state.sessionId = String(created.sessionId)
    const idle = await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 60_000,
      intervalMs: 100,
    })
    await acceptCommandBatch(apiCtx, {
      target: threadTarget({
        threadId: state.threadId,
        expectedHeadEntryId: idle.headEntryId,
        expectedNextCommandSequence: idle.nextCommandSequence,
      }),
      commands: [
        setExecutionCommand(state.agent.execution.settings, cid()),
        userMessageCommand(historicalMessage, cid()),
      ],
    })
    await mock.waitForRequest(historicalMessage)

    if (queuedMessages.length > 0) {
      await waitForWorkspaceActiveModel(apiCtx, state.threadId)
      for (const message of queuedMessages) {
        await acceptCommandBatch(apiCtx, {
          target: threadTarget({ threadId: state.threadId }),
          commands: [userMessageCommand(message, cid())],
        })
      }
      await waitForWorkspaceQueuedMessages(apiCtx, state.threadId, queuedMessages.length)
    }
    return {
      ...state,
      dispose: () => cleanupWorkspaceFixtureState(state),
    }
  } catch (error) {
    await cleanupWorkspaceFixtureState(state).catch(() => undefined)
    throw error
  }
}

async function createBlankWorkspaceFixture(apiCtx, { title }) {
  const target = await resolveWorkspaceCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    space: null,
    threadId: null,
  }
  try {
    state.space = await createWorkspaceSpace(apiCtx, {
      title,
      defaultAgentName: target.agent.name,
      defaultYoloEnabled: false,
    })
    return {
      ...state,
      dispose: () => cleanupWorkspaceFixtureState(state),
    }
  } catch (error) {
    await cleanupWorkspaceFixtureState(state).catch(() => undefined)
    throw error
  }
}

async function createWorkspaceDurableHistoryFixture(
  apiCtx,
  { title, messages },
) {
  assert(
    Array.isArray(messages) && messages.length > 0,
    'durable history messages required',
  )
  const target = await resolveWorkspaceCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    space: null,
    sessionId: null,
    threadId: null,
  }
  try {
    state.space = await createWorkspaceSpace(apiCtx, {
      title,
      defaultAgentName: target.agent.name,
      defaultYoloEnabled: false,
    })
    const created = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: messages[0] }],
      modelSelection: target.model,
      yoloEnabled: false,
    })
    state.threadId = String(created.threadId)
    state.sessionId = String(created.sessionId)
    for (const message of messages.slice(1)) {
      let accepted
      for (let attempt = 0; attempt < 5; attempt += 1) {
        if (attempt > 0) await sleep(80)
        await waitForQuiescentThread(apiCtx, state.threadId, {
          timeoutMs: 30_000,
          intervalMs: 50,
        }).catch(() => {})
        try {
          accepted = await acceptCommandBatch(apiCtx, {
            target: threadTarget({
              threadId: state.threadId,
            }),
            commands: [userMessageCommand(message, cid())],
          })
          break
        } catch (error) {
          if (!isExitUnconfirmedConflict(error)) throw error
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
    return {
      ...state,
      dispose: () => cleanupWorkspaceFixtureState(state),
    }
  } catch (error) {
    await cleanupWorkspaceFixtureState(state).catch(() => undefined)
    throw error
  }
}

function createReadyUploadRoute() {
  let sequence = 0
  const blobs = new Map()
  return async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    // 上传句柄沿用 platform 存储路由：complete/delete 都挂在 /api/platform/storage/uploads 下。
    const completeMatch = pathname.match(/\/api\/platform\/storage\/uploads\/([^/]+)\/complete$/u)
    const deleteMatch = pathname.match(/\/api\/platform\/storage\/uploads\/([^/]+)$/u)
    if (request.method() === 'POST' && pathname.endsWith('/api/platform/storage/uploads')) {
      sequence += 1
      const suffix = String(sequence).padStart(12, '0')
      const id = `00000000-0000-4000-8000-${suffix}`
      const blobId = `00000000-0000-4000-9000-${suffix}`
      blobs.set(id, blobId)
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(readyUploadBody(id, blobId)),
      })
      return
    }
    if (request.method() === 'POST' && completeMatch) {
      const id = decodeURIComponent(completeMatch[1])
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(readyUploadBody(id, blobs.get(id) ?? null)),
      })
      return
    }
    if (request.method() === 'DELETE' && deleteMatch) {
      blobs.delete(decodeURIComponent(deleteMatch[1]))
      await route.fulfill({
        status: 204,
      })
      return
    }
    await route.fallback()
  }
}

/** READY 上传是按 `type` 判别的 sealed wire：只携带 id、blobId 与清理截止快照。 */
function readyUploadBody(id, blobId) {
  return { type: 'READY', id, blobId, expiresAt: Date.now() + 300_000 }
}

class HoldingOpenAiMock {
  constructor() {
    this.responses = new Map()
    this.events = new EventEmitter()
    this.sockets = new Set()
    this.listening = false
    this.base = null
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

  async waitForRequest(message, timeoutMs = 15_000) {
    const signal = AbortSignal.timeout(timeoutMs)
    for (;;) {
      for (const [response, held] of this.responses) {
        if (held.userText.includes(message)) return response
      }
      try {
        await once(this.events, 'held', { signal })
      } catch (error) {
        if (!signal.aborted) throw error
        throw new Error('hold provider did not receive the expected user message')
      }
    }
  }

  release(response, text) {
    const held = this.responses.get(response)
    assert(held && !response.destroyed && !response.writableEnded, 'model response is no longer held')
    const chunk = {
      id: `e2e-ui-hold-${cid()}`,
      object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000),
      model: held.model,
    }
    response.write(`data: ${JSON.stringify({
      ...chunk,
      choices: [{ index: 0, delta: { role: 'assistant', content: text }, finish_reason: null }],
    })}\n\n`)
    response.write(`data: ${JSON.stringify({
      ...chunk,
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
    })}\n\n`)
    response.write(`data: ${JSON.stringify({
      ...chunk,
      choices: [],
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    })}\n\n`)
    response.end('data: [DONE]\n\n')
    this.responses.delete(response)
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
    } catch {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: 'invalid hold JSON' } }))
      return
    }
    const content = body.messages?.findLast((message) => message.role === 'user')?.content
    const userText = typeof content === 'string' ? [content] : (content ?? []).map((part) => part.text)
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(': hold\n\n')
    this.responses.set(response, { model: body.model, userText })
    response.once('close', () => this.responses.delete(response))
    this.events.emit('held')
  }

  async close() {
    for (const response of this.responses.keys()) response.destroy()
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
