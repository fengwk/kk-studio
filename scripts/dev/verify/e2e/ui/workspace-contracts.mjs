import { createServer } from 'node:http'
import { mkdir, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'

import { baseModelConfig, environmentCreateBody } from '../lib/fixtures.mjs'
import {
  acceptCommandBatch,
  assertRootYoloEnabled,
  createWorkspaceSession,
  createWorkspaceSpace,
  getThreadSnapshot,
  listEnvironments,
  setEnvironmentCommand,
  setExecutionCommand,
  threadTarget,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import {
  assert,
  cid,
  sleep,
} from '../lib/http.mjs'
import {
  bindSplitThreads as bindWorkspaceSplitThreads,
  bindThreadComposer as bindWorkspaceThreadComposer,
  cleanupWorkspaceFixtureState,
  resolveCatalogTarget,
  UI_LABELS,
  waitForThreadSubscription as waitForWorkspaceThreadSubscription,
  withUiFixture as withWorkspaceUiFixture,
} from './workspace-helpers.mjs'

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export async function runWorkspaceContractMatrix(ui) {
  const {
    apiCtx,
    consoleErrors,
    createWorkspaceDurableHistoryFixture,
    createWorkspaceHoldingQueueFixture,
    daemonEnv,
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
    'ui.workspace.composer.settings_controls_batch',
    'Composer 双层控制栏、Default/YOLO 与 Model→Variant 菜单形成同批设置命令',
    async (caseArt) => {
      await ensureSignedIn?.()
      await withWorkspaceUiFixture(
        page,
        () => createWorkspaceHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-composer-settings-${stamp}`,
          historicalMessage: `composer settings hold ${stamp}`,
          queuedMessages: [`existing queued message ${stamp}`],
        }),
        async (fixture) => {
          const composer = await bindWorkspaceThreadComposer(page, goto, fixture)
          const pane = page.locator('[data-pane-active]').first()
          const add = pane.getByRole('button', { name: UI_LABELS.openCommandsButton })
          const permission = pane.getByRole('button', { name: UI_LABELS.permissionButton })
          const model = pane.getByRole('button', { name: UI_LABELS.modelButton })
          const send = pane.getByRole('button', { name: UI_LABELS.sendButton })

          const geometry = await composer.evaluate((editor) => {
            const dock = editor.closest('.thread-dock')
            const controls = dock?.querySelector('.thread-dock-controls')
            if (!(dock instanceof HTMLElement) || !(controls instanceof HTMLElement)) {
              return null
            }
            const dockRect = dock.getBoundingClientRect()
            const editorRect = editor.getBoundingClientRect()
            const controlsRect = controls.getBoundingClientRect()
            return {
              dockHeight: dockRect.height,
              editorHeight: editorRect.height,
              editorTop: editorRect.top,
              editorBottom: editorRect.bottom,
              controlsTop: controlsRect.top,
            }
          })
          assert(geometry != null, 'Composer did not render a dock + controls structure')
          assert(
            geometry.dockHeight >= 90
            && geometry.dockHeight <= 104
            && geometry.editorHeight >= 32
            && geometry.editorHeight <= 42
            && geometry.editorTop < geometry.controlsTop
            && geometry.editorBottom <= geometry.controlsTop + 2,
            `Composer is not a compact input row + controls row surface: ${JSON.stringify(geometry)}`,
          )
          const controlBoxes = await Promise.all([
            add.boundingBox(),
            permission.boundingBox(),
            model.boundingBox(),
            send.boundingBox(),
          ])
          assert(
            controlBoxes.every(Boolean)
            && controlBoxes[0].x < controlBoxes[1].x
            && controlBoxes[1].x < controlBoxes[2].x
            && controlBoxes[2].x < controlBoxes[3].x,
            `Composer control order is invalid: ${JSON.stringify(controlBoxes)}`,
          )

          await permission.click()
          const permissionOptions = page.getByRole('listbox', { name: UI_LABELS.permissionOptions })
          await permissionOptions.waitFor({ state: 'visible', timeout: 10_000 })
          const permissionTexts = await permissionOptions.getByRole('option').allTextContents()
          assert(
            permissionTexts.length === 2
            && UI_LABELS.permissionDefault.test(permissionTexts[0]?.trim() ?? '')
            && UI_LABELS.permissionYolo.test(permissionTexts[1]?.trim() ?? ''),
            `Permission menu must contain only Default/YOLO: ${JSON.stringify(permissionTexts)}`,
          )
          await permissionOptions.getByRole('option', { name: UI_LABELS.permissionYolo, exact: true }).click()
          assert((await permission.innerText()).includes('YOLO'), 'YOLO selection was not retained')

          await model.click()
          const modelOptions = page.getByRole('listbox', { name: UI_LABELS.modelOptions })
          await modelOptions.waitFor({ state: 'visible', timeout: 10_000 })
          await modelOptions.getByRole('option', {
            name: fixture.model.name,
            exact: true,
          }).click()
          const variantOptions = page.getByRole('listbox', { name: UI_LABELS.variantOptions })
          await variantOptions.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            (await variantOptions.getByRole('option').allTextContents()).includes('review'),
            'two-level Model menu did not expose the review Variant',
          )
          await variantOptions.getByRole('option', { name: 'review', exact: true }).click()
          assert(
            (await model.innerText()).includes(`${fixture.model.name} (review)`),
            'selected Model/Variant was not retained in the Composer',
          )

          const marker = `composer settings batch ${stamp}`
          await composer.fill(marker)
          await send.click()
          const queued = await waitForQueuedSettingsBatch(apiCtx, fixture.threadId, marker)
          assert(
            queued.map((command) => command.type).join(',') === 'SET_EXECUTION,USER_MESSAGE',
            `settings and message were not queued in frozen order: ${JSON.stringify(queued)}`,
          )
          const executionPayload = queued[0].payload
          assert(
            executionPayload.execution?.driver === 'builtin'
            && executionPayload.execution?.settings?.modelName === fixture.model.name
            && executionPayload.execution?.settings?.variant === 'review',
            `queued SET_EXECUTION does not match the UI selection: ${JSON.stringify(executionPayload)}`,
          )
          // yolo 是直接控制面：选择 YOLO 立即 PUT /yolo（基于 snapshot version 的 CAS），
          // 绝不进入 command batch；Thread 快照反映权威值。
          const fresh = await getThreadSnapshot(apiCtx, fixture.threadId)
          assertRootYoloEnabled(fresh.thread, true)

          await shot(caseArt, 'composer-settings-controls-batch')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.composer.multi_pane_settings_isolation',
    '双 Pane Composer 的 Permission 菜单、状态与 Escape 焦点互不串扰',
    async (caseArt) => {
      await ensureSignedIn?.()
      await withWorkspaceUiFixture(
        page,
        () => createTwoWorkspaceSessionsFixture(apiCtx, {
          title: `e2e-ui-composer-multipane-${stamp}`,
          firstMessage: `multi pane first ${stamp}`,
          secondMessage: `multi pane second ${stamp}`,
        }),
        async (fixture) => {
          const { pane1, pane2 } = await bindWorkspaceSplitThreads(
            page,
            goto,
            fixture.space.id,
            fixture.threadId,
            fixture.secondSessionId,
            fixture.secondThreadId,
          )
          const permission1 = pane1.getByRole('button', { name: UI_LABELS.permissionButton })
          const permission2 = pane2.getByRole('button', { name: UI_LABELS.permissionButton })
          await permission1.waitFor({ state: 'visible', timeout: 15_000 })
          await permission2.waitFor({ state: 'visible', timeout: 15_000 })

          await permission1.click()
          await page.getByRole('listbox', { name: UI_LABELS.permissionOptions })
            .getByRole('option', { name: UI_LABELS.permissionYolo, exact: true })
            .click()
          assert((await permission1.innerText()).includes('YOLO'), 'pane-1 did not retain YOLO')
          assert(
            UI_LABELS.permissionDefault.test((await permission2.innerText()).trim()),
            `pane-2 permission was polluted: ${await permission2.innerText()}`,
          )

          await permission2.click()
          assert(
            await page.getByRole('listbox', { name: UI_LABELS.permissionOptions }).count() === 1,
            'more than one Composer settings menu owned keyboard focus',
          )
          assert(
            await permission1.getAttribute('aria-expanded') === 'false'
            && await permission2.getAttribute('aria-expanded') === 'true',
            'opening pane-2 did not close pane-1 menu',
          )
          await page.getByRole('listbox', { name: UI_LABELS.permissionOptions }).press('Escape')
          const composer2 = pane2.getByRole('textbox', { name: UI_LABELS.composerInput })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await composer2.elementHandle(),
            { timeout: 10_000 },
          )
          assert((await permission1.innerText()).includes('YOLO'), 'pane-1 draft changed after pane-2 Escape')
          assert(
            UI_LABELS.permissionDefault.test((await permission2.innerText()).trim()),
            'pane-2 draft changed after Escape',
          )

          await shot(caseArt, 'composer-multi-pane-settings-isolation')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.footer.readonly_facts',
    'Footer 只展示真实 Environment/Workspace、usage/context/cache facts 且无交互',
    async (caseArt) => {
      await ensureSignedIn?.()
      await withWorkspaceUiFixture(
        page,
        () => createCompletedUsageFixture(apiCtx, stamp),
        async (fixture) => {
          await bindWorkspaceThreadComposer(page, goto, fixture)
          const footer = page.getByLabel(UI_LABELS.threadStatusFooter)
          await footer.waitFor({ state: 'visible', timeout: 15_000 })
          // 只读单行 Footer：组分隔符固定为 U+2223（∣），组内统计项固定为 U+00B7（·）。
          const line = footer.locator('.thread-status-line')
          assert(await line.count() === 1, `Footer must render exactly one status line: ${await footer.innerText()}`)
          const escapedEnvName = escapeRegExp(fixture.otherEnvironment.name)
          const expectedStats = '∣ ctx 30/4.1k ∣ ↑16 · ↓9 · R14 · W0 · $0.000034 · cache 46.67% · '
          const expectedPattern = new RegExp(
            `^${escapedEnvName} · (?:unavailable|不可用) ${escapeRegExp(expectedStats)}(?:—|\\d+(?:\\.\\d+)?) tok\\/s$`,
          )
          // Environment 由当前 branch settings 显式绑定，fixture 已切到另一 Environment，
          // Footer 必须投影该 Environment（无 daemon 连接 => unavailable）。
          const lineText = (await line.innerText()).replace(/\s+/g, ' ').trim()
          assert(expectedPattern.test(lineText), `Footer facts are incorrect: ${lineText}`)
          const footerTitle = await line.getAttribute('title')
          assert(
            new RegExp(`^(?:Environment:|环境：) ?${escapedEnvName}`).test(footerTitle ?? '')
              && footerTitle.includes('30 / 4096 tokens')
              && footerTitle.includes('46.67%'),
            `Footer hover readout is incomplete: ${footerTitle}`,
          )
          assert(await footer.getByRole('button').count() === 0, 'readonly Footer rendered a button')
          const footerText = await footer.innerText()
          for (const forbidden of [
            fixture.agent.name,
            `${fixture.provider.name}/${fixture.model.name}`,
            'Default',
            'YOLO',
          ]) {
            assert(!footerText.includes(forbidden), `Footer leaked interactive setting: ${forbidden}`)
          }

          await shot(caseArt, 'footer-readonly-facts')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.workspace.tool_card.streaming_layout_scroll',
    'Tool 卡流式输入、审查布局、write/edit/bash 审批与有界滚动',
    async (caseArt) => {
      await ensureSignedIn?.()
      const originalViewport = page.viewportSize()
      await page.setViewportSize({ width: 720, height: 420 })
      try {
        await withWorkspaceUiFixture(
          page,
          () => createToolCardFixture(apiCtx, stamp, daemonEnv),
          async (fixture) => {
            await fixture.start('edit')
            await Promise.all([
              bindWorkspaceThreadComposer(page, goto, fixture),
              waitForWorkspaceThreadSubscription(page, fixture.threadId),
            ])
            await page.locator('.thread-working').waitFor({ state: 'visible', timeout: 10_000 })
            await fixture.stream()

            const card = page.locator('.thread-block-tool').filter({
              has: page.locator('.thread-tool-name', { hasText: 'edit' }),
            }).last()
            const streamingPreview = card.locator('.thread-tool-preview-body.is-input-new')
            await streamingPreview.waitFor({ state: 'visible', timeout: 15_000 })
            await page.waitForFunction(
              () => document.querySelector('.thread-tool-preview-body.is-input-new')?.textContent?.includes('new-8'),
              undefined,
              { timeout: 15_000 },
            )
            await page.waitForFunction(
              (expected) => [...document.querySelectorAll('.thread-block-tool')]
                .some((surface) => surface.querySelector('.thread-tool-name')?.textContent === 'edit'
                  && surface.querySelector('.thread-tool-summary-detail')?.textContent === expected),
              fixture.path,
              { timeout: 15_000 },
            )
            const streaming = await card.evaluate((surface) => {
              const header = surface.querySelector('.thread-disclosure-header')
              const summary = surface.querySelector('.thread-tool-summary')
              const detail = surface.querySelector('.thread-tool-summary-detail')
              const toggle = surface.querySelector('.thread-disclosure-toggle')
              const preview = surface.querySelector('.thread-tool-preview-body.is-input-new')
              if (
                !(header instanceof HTMLElement)
                || !(summary instanceof HTMLElement)
                || !(detail instanceof HTMLElement)
                || !(toggle instanceof HTMLElement)
                || !(preview instanceof HTMLElement)
              ) {
                return null
              }
              const detailStyle = getComputedStyle(detail)
              return {
                name: surface.querySelector('.thread-tool-name')?.textContent ?? '',
                previewLines: (preview.textContent ?? '').split('\n'),
                detail: detail.textContent ?? '',
                detailHeight: detail.getBoundingClientRect().height,
                detailLineHeight: Number.parseFloat(detailStyle.lineHeight),
                detailWhiteSpace: detailStyle.whiteSpace,
                headerWidth: header.getBoundingClientRect().width,
                summaryWidth: summary.getBoundingClientRect().width,
                togglePosition: getComputedStyle(toggle).position,
              }
            })
            assert(streaming != null, 'streaming edit card did not expose the expected DOM')
            assert(streaming.name === 'edit', `streamed tool name was duplicated: ${streaming.name}`)
            assert(
              streaming.previewLines.length === 8
              && streaming.previewLines[0] === 'new-1'
              && streaming.previewLines.at(-1) === 'new-8'
              && await card.getByText(UI_LABELS.threadToolProposedChanges, { exact: false }).count() === 1
              && await card.locator('.thread-tool-preview-body.is-edit').count() === 0,
              `edit streaming input was truncated or presented as a diff: ${JSON.stringify(streaming.previewLines)}`,
            )
            assert(
              // edit 使用绝对 path；长路径必须完整折行，不能被截断。
              streaming.detail === fixture.path
              && streaming.detailWhiteSpace === 'pre-wrap'
              && streaming.detailHeight > streaming.detailLineHeight * 1.5,
              `long tool header did not wrap completely: ${JSON.stringify(streaming)}`,
            )
            assert(
              streaming.summaryWidth > 0
              && streaming.summaryWidth < streaming.headerWidth
              && streaming.togglePosition === 'static',
              `tool header does not reserve room for its toggle: ${JSON.stringify(streaming)}`,
            )

            await fixture.finish()
            const approval = page.locator('.interaction-approval-card')
            await approval.waitFor({ state: 'visible', timeout: 15_000 })
            const completedPreview = approval.locator('.thread-tool-preview-body.is-edit')
            try {
              await completedPreview.waitFor({ state: 'visible', timeout: 15_000 })
            } catch {
              const interactions = await apiCtx.call('GET', `/api/harness/interactions?rootThreadId=${encodeURIComponent(fixture.threadId)}`)
              throw new Error(`edit review missing from approval: ${await approval.innerText()}; ${JSON.stringify(interactions.json)}`)
            }
            const completedText = await completedPreview.textContent() ?? ''
            assert(
              completedText.includes('- 1|old-1')
              && completedText.includes('- 8|old-8')
              && completedText.includes('+ 1|new-1')
              && completedText.includes('+ 8|new-8')
              && !completedText.includes('more lines'),
              `completed edit diff is not fully visible: ${completedText}`,
            )
            assert(
              await approval.getByRole('button', { name: UI_LABELS.allowButton, exact: true }).count() === 1
              && await approval.getByRole('button', { name: UI_LABELS.denyButton, exact: true }).count() === 1,
              `edit did not enter approval: ${await approval.innerText()}`,
            )
            const editDeny = approval.getByRole('button', { name: UI_LABELS.denyButton, exact: true })
            // The default danger secondary button uses a tinted background and red text, not an outline.
            await page.mouse.move(0, 0)
            const denyStyle = await editDeny.evaluate(async (element) => {
              await new Promise(requestAnimationFrame)
              await Promise.all(element.getAnimations().map((animation) => animation.finished))
              const probe = document.createElement('span')
              probe.style.backgroundColor = 'var(--danger-soft)'
              probe.style.color = 'var(--danger)'
              element.parentElement.appendChild(probe)
              const style = getComputedStyle(element)
              const expected = getComputedStyle(probe)
              const result = {
                background: style.backgroundColor,
                dangerBackground: expected.backgroundColor,
                color: style.color,
                dangerColor: expected.color,
                borderWidths: [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth],
                danger: element.classList.contains('danger'),
                hover: element.matches(':hover'),
              }
              probe.remove()
              return result
            })
            assert(
              denyStyle.danger && !denyStyle.hover
                && denyStyle.background === denyStyle.dangerBackground
                && denyStyle.color === denyStyle.dangerColor
                && denyStyle.borderWidths.every((width) => width === '0px'),
              `deny button does not use the borderless danger secondary style: ${JSON.stringify(denyStyle)}`,
            )
            await editDeny.click()
            await waitForQuiescentThread(apiCtx, fixture.threadId, {
              timeoutMs: 30_000,
              intervalMs: 100,
            })
            await page.locator('.thread-working').waitFor({ state: 'hidden', timeout: 10_000 })

            await fixture.start('write')
            await page.locator('.thread-working').waitFor({ state: 'visible', timeout: 10_000 })
            await fixture.stream()
            const writeCard = page.locator('.thread-block-tool').filter({
              has: page.locator('.thread-tool-name', { hasText: 'write' }),
            }).last()
            await fixture.finish()
            const writeApproval = page.locator('.interaction-approval-card')
            await writeApproval.waitFor({ state: 'visible', timeout: 15_000 })
            assert(
              await writeCard.locator('.thread-tool-name').textContent() === 'write'
              && await writeApproval.getByRole('button', { name: UI_LABELS.allowButton, exact: true }).count() === 1
              && await writeApproval.getByRole('button', { name: UI_LABELS.denyButton, exact: true }).count() === 1,
              `write did not enter approval: ${await writeCard.innerText()}`,
            )
            await writeApproval.getByRole('button', { name: UI_LABELS.denyButton, exact: true }).click()
            await waitForQuiescentThread(apiCtx, fixture.threadId, {
              timeoutMs: 30_000,
              intervalMs: 100,
            })
            await page.locator('.thread-working').waitFor({ state: 'hidden', timeout: 10_000 })

            await fixture.start('bash')
            await page.locator('.thread-working').waitFor({ state: 'visible', timeout: 10_000 })
            await fixture.stream()
            const bashCard = page.locator('.thread-block-tool').filter({
              has: page.locator('.thread-tool-name', { hasText: 'bash' }),
            }).last()
            await fixture.finish()
            const bashApproval = page.locator('.interaction-approval-card')
            await bashApproval.waitFor({ state: 'visible', timeout: 15_000 })
            assert(
              await bashCard.locator('.thread-tool-name').textContent() === 'bash'
              && await bashApproval.getByRole('button', { name: UI_LABELS.allowButton, exact: true }).count() === 1
              && await bashApproval.getByRole('button', { name: UI_LABELS.denyButton, exact: true }).count() === 1,
              `bash did not enter approval: ${await bashCard.innerText()}`,
            )
            await bashApproval.getByRole('button', { name: UI_LABELS.allowButton, exact: true }).click()
            await waitForQuiescentThread(apiCtx, fixture.threadId, {
              timeoutMs: 30_000,
              intervalMs: 100,
            })
            await page.locator('.thread-working').waitFor({ state: 'hidden', timeout: 10_000 })

            await bashCard.locator('.thread-disclosure-toggle').click()
            const output = bashCard.locator('.thread-tool-details')
            await output.waitFor({ state: 'visible', timeout: 15_000 })
            const outputText = await output.textContent() ?? ''
            assert(
              outputText.includes('tool-card-bash-1\n') && outputText.includes('tool-card-bash-80'),
              'expanded bash output did not retain the first and last fixture lines',
            )
            const outputStyle = await output.evaluate((element) => ({
              overflow: getComputedStyle(element).overflow,
              tabIndex: element.getAttribute('tabindex'),
              scrollable: element.scrollHeight > element.clientHeight,
            }))
            assert(
              outputStyle.overflow === 'auto' && outputStyle.tabIndex === '0' && outputStyle.scrollable,
              `tool output is not a bounded keyboard-accessible viewport: ${JSON.stringify(outputStyle)}`,
            )
            const transcript = page.locator('.thread-dialogue')
            await output.hover()
            const before = await transcript.evaluate((element) => element.scrollTop)
            assert(before > 0, `fixture did not overflow transcript: scrollTop=${before}`)
            await page.mouse.wheel(0, -180)
            await page.waitForFunction(
              ({ selector, previous }) => {
                const element = document.querySelector(selector)
                return element instanceof HTMLElement && element.scrollTop < previous
              },
              { selector: '.thread-dialogue', previous: before },
              { timeout: 5_000 },
            )

            await shot(caseArt, 'tool-card-streaming-layout-scroll')
            expectNoFatal(pageErrors, consoleErrors)
          },
        )
      } finally {
        if (originalViewport) await page.setViewportSize(originalViewport)
      }
    },
    { requiresTools: true },
  )

  await run(
    'ui.workspace.task_status.async_thread_approval',
    'Task 回执链接子 Thread；根控制区汇聚并向子 Thread 提交审批',
    async (caseArt) => {
      await ensureSignedIn?.()
      await withWorkspaceUiFixture(
        page,
        () => createActiveTaskFixture(apiCtx, stamp),
        async (fixture) => {
          await bindWorkspaceThreadComposer(page, goto, fixture)
          await fixture.start()

          const taskCard = page.locator('.thread-block-tool').filter({
            has: page.locator('.thread-tool-name', { hasText: 'task' }),
          }).last()
          await taskCard.waitFor({ state: 'visible', timeout: 25_000 })
          const link = taskCard.locator('.thread-tool-thread-link')
          await link.waitFor({ state: 'visible', timeout: 10_000 })
          const href = await link.getAttribute('href')
          const childThreadId = href?.split('/').at(-1)
          assert(
            /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(childThreadId ?? '')
              && href === `/threads/${childThreadId}`,
            `unexpected thread link href: ${href}`,
          )
          await link.click()
          const childView = page.locator('[data-pane-active] .bound-thread-view')
          await childView.waitFor({ state: 'visible', timeout: 15_000 })
          assert(
            new URL(page.url()).pathname === `/chat-spaces/${fixture.space.id}`
              && await childView.locator('.composer-editor').count() === 0,
            'child thread must open as a read-only pane without changing the Workspace route',
          )

          let resolveApproval
          const approvalPromise = new Promise((resolve) => {
            resolveApproval = resolve
          })

          const invocationId = cid()
          let decided = false
          const interactionsMatcher = (url) => url.pathname === '/api/harness/interactions'
            && url.searchParams.get('rootThreadId') === fixture.threadId
          const interactionsHandler = async (route) => {
            await route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify({
                items: decided ? [] : [{
                  type: 'APPROVAL',
                  toolInvocationId: invocationId,
                  threadId: childThreadId,
                  rootThreadId: fixture.threadId,
                  sessionId: fixture.sessionId,
                  owner: {
                    kind: 'CHAT_SPACE',
                    id: fixture.space.id,
                    title: fixture.space.title,
                    link: `/chat-spaces/${fixture.space.id}`,
                  },
                  toolCallId: 'call-bash-1',
                  toolName: 'bash',
                  argumentsJson: '{"command":"echo approval"}',
                  approval: { required: true, decision: null, reason: 'Permission rules require approval' },
                  createdAt: 1,
                }],
                nextCursor: null,
                total: decided ? 0 : 1,
                freshnessAt: null,
              }),
            })
          }

          const approvalMatcher = (url) =>
            url.pathname.startsWith(`/api/harness/threads/${childThreadId}/tool-invocations/`)
            && url.pathname.endsWith('/approval')
          const approvalHandler = async (route) => {
            const request = route.request()
            decided = true
            resolveApproval({
              target: request.url(),
              body: request.postDataJSON(),
            })
            await route.fulfill({
              status: 200,
              contentType: 'application/json',
              body: JSON.stringify({
                id: invocationId,
                status: 'RUNNING',
              }),
            })
          }

          await page.route(interactionsMatcher, interactionsHandler)
          await page.route(approvalMatcher, approvalHandler)

          try {
            await page.reload({ waitUntil: 'networkidle', timeout: 30_000 })
            const approval = page.locator('.interaction-approval-card')
            await approval.waitFor({ state: 'visible', timeout: 15_000 })
            assert(
              await approval.getByRole('button', { name: UI_LABELS.allowButton, exact: true }).count() === 1
              && await approval.getByRole('button', { name: UI_LABELS.denyButton, exact: true }).count() === 1,
              'root control area did not expose child approval controls',
            )

            await approval.getByRole('button', { name: UI_LABELS.allowButton, exact: true }).click()
            const { target: approvalTarget, body: approvalBody } = await Promise.race([
              approvalPromise,
              sleep(10_000).then(() => {
                throw new Error('child thread approval request timed out')
              }),
            ])
            assert(
              approvalTarget.includes(`/threads/${childThreadId}/tool-invocations/${invocationId}/approval`),
              `approval did not target the child thread: ${approvalTarget}`,
            )
            assert(
              approvalBody?.decision === 'ALLOW',
              `unexpected approval payload: ${JSON.stringify(approvalBody)}`,
            )

            await shot(caseArt, 'child-thread-approval-routed')
            expectNoFatal(pageErrors, consoleErrors)
          } finally {
            await page.unroute(interactionsMatcher, interactionsHandler).catch(() => undefined)
            await page.unroute(approvalMatcher, approvalHandler).catch(() => undefined)
          }
        },
      )
    },
  )

  await run(
    'ui.settings.system_contract_cas',
    'Settings server tab 与 /api/platform/settings 契约：完整聚合 CAS、冲突确认后刷新，并在收尾恢复全局状态',
    async (caseArt) => {
      await ensureSignedIn?.()
      const readSettings = async () => (await apiCtx.call('GET', '/api/platform/settings')).json
      const before = await readSettings()
      const original = before.aiRuntime.retryBaseDelayMillis
      // Long 在 wire 上是十进制字符串。
      assert(
        typeof original === 'string' && /^\d+$/.test(original),
        `retryBaseDelayMillis must be a decimal string: ${JSON.stringify(original)}`,
      )
      // 确定性可逆的新值：与原始值不同的正整数字符串。
      const changed = original === '2501' ? '2502' : '2501'
      const openAiRuntime = () => page.getByRole('link', { name: /^(?:AI Runtime|AI 运行时)$/ }).click()
      try {
        await goto('/settings/preferences')
        await openAiRuntime()
        const base = page.getByLabel(UI_LABELS.settingsBaseDelayLabel)
        await base.waitFor({ state: 'visible', timeout: 10_000 })
        // GET hydration：draft 与权威聚合一致。
        assert(
          (await base.inputValue()) === original,
          `hydration mismatch: ${await base.inputValue()} !== ${original}`,
        )
        await base.fill(changed)
        await page.getByRole('button', { name: UI_LABELS.settingsSave, exact: true }).click()
        await page.getByText(UI_LABELS.settingsAllSaved).waitFor({ state: 'visible', timeout: 15_000 })
        await shot(caseArt, 'settings-system-contract-cas')

        const after = await readSettings()
        assert(after.version !== before.version, 'CAS PUT must advance the version')
        assert(after.aiRuntime.retryBaseDelayMillis === changed, `change not persisted: ${after.aiRuntime.retryBaseDelayMillis}`)
        // 完整聚合六 section + wire 形态（Long 字符串 / version 字符串）。
        for (const section of ['tool', 'aiRuntime', 'environment', 'backendNetwork', 'storageMedia', 'advanced']) {
          assert(after[section] != null, `missing section ${section} in GET response`)
        }
        assert(typeof after.version === 'string' && /^\d+$/.test(after.version), 'version must be a decimal string')
        assert(typeof after.aiRuntime.retryBaseDelayMillis === 'string', 'Long field must stay a decimal string on the wire')

        // 外部写入推进版本后，页面中的旧 draft 保存必须先弹窗说明；用户确认后才刷新并读取最新值。
        const externallyChanged = '2503'
        const staleDraft = '2504'
        const externalUpdate = {
          tool: after.tool,
          aiRuntime: { ...after.aiRuntime, retryBaseDelayMillis: externallyChanged },
          environment: after.environment,
          backendNetwork: after.backendNetwork,
          storageMedia: after.storageMedia,
          advanced: after.advanced,
          expectedVersion: after.version,
        }
        const externalRes = await apiCtx.call('PUT', '/api/platform/settings', externalUpdate)
        assert(externalRes.status === 200, `external settings update failed: ${externalRes.status}`)
        await base.fill(staleDraft)
        await page.getByRole('button', { name: UI_LABELS.settingsSave, exact: true }).click()
        const conflictDialog = page.getByRole('alertdialog', {
          name: UI_LABELS.settingsConflictDialog,
        })
        await conflictDialog.waitFor({ state: 'visible', timeout: 15_000 })
        assert(
          (await base.inputValue()) === staleDraft,
          'stale draft must remain visible before reload confirmation',
        )
        await shot(caseArt, 'settings-system-conflict-dialog')
        await Promise.all([
          page.waitForEvent('load'),
          conflictDialog.getByRole('button', { name: UI_LABELS.settingsReloadLatest }).click(),
        ])
        // Server settings navigation is populated from the schema after login and reload.
        await openAiRuntime()
        const refreshedBase = page.getByLabel(UI_LABELS.settingsBaseDelayLabel)
        await refreshedBase.waitFor({ state: 'visible', timeout: 10_000 })
        assert(
          (await refreshedBase.inputValue()) === externallyChanged,
          `reload did not hydrate latest settings: ${await refreshedBase.inputValue()}`,
        )
        expectNoFatal(pageErrors, consoleErrors)
      } finally {
        // 无论本用例产物读/断言是否失败，只要保存可能已写入都必须恢复全局聚合：在 finally 内重新强读最新
        // settings，用最新版本做 CAS 恢复原字段；绝不留下被修改的全局状态。409（并发推进版本）时重读重试。
        for (let attempt = 0; attempt < 3; attempt++) {
          const latest = await readSettings()
          if (latest.aiRuntime.retryBaseDelayMillis === original) {
            break
          }
          const restore = {
            tool: latest.tool,
            aiRuntime: { ...latest.aiRuntime, retryBaseDelayMillis: original },
            environment: latest.environment,
            backendNetwork: latest.backendNetwork,
            storageMedia: latest.storageMedia,
            advanced: latest.advanced,
            expectedVersion: latest.version,
          }
          const res = await apiCtx.call('PUT', '/api/platform/settings', restore)
          if (res.status === 200) {
            const restored = res.json
            assert(restored.aiRuntime.retryBaseDelayMillis === original, 'restore was not applied')
            break
          }
          assert(
            res.status === 409 && res.json?.code === 'version_conflict',
            `restore PUT failed: ${res.status}`,
          )
        }
        const verified = await readSettings()
        assert(
          verified.aiRuntime.retryBaseDelayMillis === original,
          `restore retries exhausted without restoring retryBaseDelayMillis: ${verified.aiRuntime.retryBaseDelayMillis}`,
        )
      }
    },
  )
}

async function createTwoWorkspaceSessionsFixture(
  apiCtx,
  { title, firstMessage, secondMessage },
) {
  const target = await resolveCatalogTarget(apiCtx)
  const state = {
    apiCtx,
    space: null,
    sessionIds: [],
    threadIds: [],
    sessionId: null,
    secondSessionId: null,
    threadId: null,
    secondThreadId: null,
  }
  try {
    state.space = await createWorkspaceSpace(apiCtx, {
      title,
      defaultAgentName: target.agent.name,
    })
    const first = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: firstMessage }],
      modelSelection: target.model,
      yoloEnabled: false,
    })
    const second = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: secondMessage }],
      modelSelection: target.model,
      yoloEnabled: false,
    })
    state.sessionId = String(first.sessionId)
    state.threadId = String(first.threadId)
    state.secondSessionId = String(second.sessionId)
    state.secondThreadId = String(second.threadId)
    state.sessionIds.push(state.sessionId, state.secondSessionId)
    state.threadIds.push(state.threadId, state.secondThreadId)
    await Promise.all([
      waitForQuiescentThread(apiCtx, state.threadId, { timeoutMs: 60_000, intervalMs: 100 }),
      waitForQuiescentThread(apiCtx, state.secondThreadId, { timeoutMs: 60_000, intervalMs: 100 }),
    ])
    return {
      ...state,
      dispose: () => cleanupWorkspaceFixtureState(state),
    }
  } catch (error) {
    await cleanupWorkspaceFixtureState(state).catch(() => undefined)
    throw error
  }
}

async function waitForQueuedSettingsBatch(apiCtx, threadId, marker, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    last = await getThreadSnapshot(apiCtx, threadId)
    const commands = [...last.queuedCommands].sort(
      (left, right) => Number(BigInt(left.sequence) - BigInt(right.sequence)),
    )
    const userIndex = commands.findIndex((command) =>
      command.type === 'USER_MESSAGE' && JSON.stringify(command.payload).includes(marker),
    )
    if (userIndex > 0 && commands[userIndex - 1]?.type === 'SET_EXECUTION') {
      // 只返回本次 batch：marker USER 与其紧邻的 preceding SET_EXECUTION，
      // 不把更早已入队的 queued message 卷进来。
      return [commands[userIndex - 1], commands[userIndex]]
    }
    await sleep(50)
  }
  throw new Error(`Composer settings batch did not stabilize: ${JSON.stringify(last)}`)
}

async function createCompletedUsageFixture(apiCtx, stamp) {
  const suffix = cid().slice(0, 8)
  const mock = new CompletingOpenAiMock()
  const state = {
    apiCtx,
    agent: null,
    space: null,
    environment: null,
    otherEnvironment: null,
    mock,
    model: null,
    provider: null,
    sessionId: null,
    threadId: null,
  }
  try {
    await mock.start()
    const environmentResponse = await apiCtx.call(
      'POST',
      '/api/platform/environments',
      environmentCreateBody(`offline-${suffix}`),
    )
    assert(
      environmentResponse.status === 201,
      `create usage environment: ${JSON.stringify(environmentResponse)}`,
    )
    state.environment = environmentResponse.json

    const providerResponse = await apiCtx.call('POST', '/api/platform/providers', {
      name: `e2e-ui-usage-provider-${suffix}`,
      description: 'Local completion provider for free Footer usage tests.',
      providerType: 'openai',
      baseUrl: mock.baseUrl('/v1'),
      credential: { operation: 'SET', value: `e2e-ui-usage-${suffix}` },
      modelCallTimeoutMillis: '30000',
      modelCallIdleTimeoutMillis: '10000',
    })
    assert(providerResponse.status === 201, `create usage provider: ${JSON.stringify(providerResponse)}`)
    state.provider = providerResponse.json

    const modelResponse = await apiCtx.call('POST', '/api/platform/models', {
      type: 'MODEL',
      providerName: state.provider.name,
      name: `e2e-ui-usage-model-${suffix}`,
      modelId: `wire-ui-usage-${suffix}`,
      description: 'Local completion model for free Footer usage tests.',
      config: baseModelConfig({
        limit: { context: 4096, output: 128 },
        abilities: {
          tools: false,
          reasoning: false,
          inputModalities: ['TEXT'],
        },
        variants: [{ id: 'default', promptCacheRetention: 'NONE' }, { id: 'review', promptCacheRetention: 'NONE' }],
      }),
    })
    assert(modelResponse.status === 201, `create usage model: ${JSON.stringify(modelResponse)}`)
    state.model = modelResponse.json

    const agentResponse = await apiCtx.call('POST', '/api/platform/agents', {
      name: `e2e-ui-usage-agent-${suffix}`,
      description: 'Local completion agent for free Footer usage tests.',
      systemPrompt: 'Return the deterministic local response.',
      execution: { driver: 'builtin', settings: { modelName: state.model.name, variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
    })
    assert(agentResponse.status === 201, `create usage agent: ${JSON.stringify(agentResponse)}`)
    state.agent = agentResponse.json

    state.space = await createWorkspaceSpace(apiCtx, {
      title: `e2e-ui-footer-${stamp}-${suffix}`,
      defaultAgentName: state.agent.name,
    })
    const created = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: `footer usage ${stamp}` }],
      modelSelection: state.agent.execution.settings,
      environmentId: state.environment.id,
      yoloEnabled: false,
    })
    state.threadId = String(created.threadId)
    state.sessionId = String(created.sessionId)
    const completed = await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 30_000,
      intervalMs: 100,
    })
    // 切换只读 Footer 的 Environment 事实通过 branch settings 的 setEnvironmentCommand 表达。
    const otherEnvironmentResponse = await apiCtx.call(
      'POST',
      '/api/platform/environments',
      environmentCreateBody(`offline-alt-${suffix}`),
    )
    assert(
      otherEnvironmentResponse.status === 201,
      `create alt usage environment: ${JSON.stringify(otherEnvironmentResponse)}`,
    )
    state.otherEnvironment = otherEnvironmentResponse.json
    const switched = await acceptCommandBatch(apiCtx, {
      target: threadTarget({
        threadId: state.threadId,
        expectedHeadEntryId: completed.headEntryId,
        expectedNextCommandSequence: completed.nextCommandSequence,
      }),
      commands: [
        setEnvironmentCommand(state.otherEnvironment.name, cid()),
        userMessageCommand(`footer environment ${stamp}`, cid()),
      ],
    })
    assert(
      switched.acceptedCommands.map((command) => command.type).join(',')
        === 'SET_ENVIRONMENT,USER_MESSAGE',
      JSON.stringify(switched.acceptedCommands),
    )
    await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 30_000,
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

async function createActiveTaskFixture(apiCtx, stamp) {
  const suffix = cid().slice(0, 8)
  const parentMarker = `active task ${stamp} ${suffix}`
  const childName = `e2e-ui-task-child-${suffix}`
  const mock = new TaskHoldingOpenAiMock({ parentMarker, childName })
  const state = {
    apiCtx,
    childAgent: null,
    space: null,
    mock,
    model: null,
    parentAgent: null,
    provider: null,
    sessionId: null,
    threadId: null,
  }
  try {
    await mock.start()
    const providerResponse = await apiCtx.call('POST', '/api/platform/providers', {
      name: `e2e-ui-task-provider-${suffix}`,
      description: 'Local task provider for free TaskStatus UI tests.',
      providerType: 'openai',
      baseUrl: mock.baseUrl('/v1'),
      credential: { operation: 'SET', value: `e2e-ui-task-${suffix}` },
      modelCallTimeoutMillis: '60000',
      modelCallIdleTimeoutMillis: '60000',
    })
    assert(providerResponse.status === 201, `create task provider: ${JSON.stringify(providerResponse)}`)
    state.provider = providerResponse.json

    const modelResponse = await apiCtx.call('POST', '/api/platform/models', {
      type: 'MODEL',
      providerName: state.provider.name,
      name: `e2e-ui-task-model-${suffix}`,
      modelId: `wire-ui-task-${suffix}`,
      description: 'Local task model for free TaskStatus UI tests.',
      config: baseModelConfig({
        limit: { context: 4096, output: 128 },
        abilities: {
          tools: true,
          reasoning: false,
          inputModalities: ['TEXT'],
        },
        variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
      }),
    })
    assert(modelResponse.status === 201, `create task model: ${JSON.stringify(modelResponse)}`)
    state.model = modelResponse.json

    const childResponse = await apiCtx.call('POST', '/api/platform/agents', {
      name: childName,
      description: 'Held child used by the TaskStatus browser fixture.',
      systemPrompt: 'Stay in the deterministic local model call.',
      execution: { driver: 'builtin', settings: { modelName: state.model.name, variant: 'default' } },
      config: { tools: [], skills: [], subagents: [] },
    })
    assert(childResponse.status === 201, `create task child: ${JSON.stringify(childResponse)}`)
    state.childAgent = childResponse.json

    const parentResponse = await apiCtx.call('POST', '/api/platform/agents', {
      name: `e2e-ui-task-parent-${suffix}`,
      description: 'Parent used by the TaskStatus browser fixture.',
      systemPrompt: 'Call the configured task subagent exactly once.',
      execution: { driver: 'builtin', settings: { modelName: state.model.name, variant: 'default' } },
      config: {
        tools: [],
        skills: [],
        subagents: [state.childAgent.name],
      },
    })
    assert(parentResponse.status === 201, `create task parent: ${JSON.stringify(parentResponse)}`)
    state.parentAgent = parentResponse.json

    state.space = await createWorkspaceSpace(apiCtx, {
      title: `e2e-ui-task-${stamp}-${suffix}`,
      defaultAgentName: state.parentAgent.name,
      defaultYoloEnabled: true,
    })
    // 先用不可用的模型创建空闲 Thread，避免 bootstrap 消费 mock；浏览器绑定后再启动任务。
    const created = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: `task materialize ${suffix}` }],
      modelSelection: {
        modelName: `${state.model.name}-missing`,
        variant: 'default',
      },
      yoloEnabled: true,
    })
    state.threadId = String(created.threadId)
    state.sessionId = String(created.sessionId)
    return {
      ...state,
      // 浏览器先绑定 Thread，再显式启动 task。
      start: async () => {
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
            setExecutionCommand(state.parentAgent.execution.settings, cid()),
            userMessageCommand(parentMarker, cid()),
          ],
        })
        await mock.waitForChildRequest()
      },
      dispose: () => cleanupWorkspaceFixtureState(state),
    }
  } catch (error) {
    await cleanupWorkspaceFixtureState(state).catch(() => undefined)
    throw error
  }
}

async function createToolCardFixture(apiCtx, stamp, daemonEnv) {
  assert(
    typeof daemonEnv === 'string' && daemonEnv.trim().length > 0,
    `tool-card fixture requires a non-blank daemonEnv: ${JSON.stringify(daemonEnv)}`,
  )
  const suffix = cid().slice(0, 8)
  const markers = {
    bash: `bash tool card ${stamp} ${suffix}`,
    edit: `edit tool card ${stamp} ${suffix}`,
    write: `write tool card ${stamp} ${suffix}`,
  }
  // 工具 arguments 必须显式携带目标 Daemon 上已存在的绝对 workdir；Environment 保留的 homeDirectory 就是该 Daemon 上的绝对宿主目录。
  const environments = await listEnvironments(apiCtx)
  const matchedEnv = environments.find((e) => e.name === daemonEnv)
  assert(matchedEnv, `daemonEnv missing: ${daemonEnv}`)
  assert(
    typeof matchedEnv.homeDirectory === 'string' && matchedEnv.homeDirectory.startsWith('/'),
    `daemonEnv '${daemonEnv}' must project an absolute homeDirectory for explicit workdir arguments: ${JSON.stringify(matchedEnv)}`,
  )
  const workdir = process.env.DAEMON_ENV_ROOT
  assert(typeof workdir === 'string' && workdir.startsWith('/'), 'tool fixture requires an isolated DAEMON_ENV_ROOT')
  const fixtureDir = `${workdir}/e2e-tool-card-${suffix}`
  const filePath =
    `${fixtureDir}/${'very-long-directory-segment/'.repeat(12)}`
    + 'QuickSort.java'
  const mock = new ToolCardOpenAiMock({
    markers,
    path: filePath,
    workdir,
    oldText: Array.from({ length: 8 }, (_, index) => `old-${index + 1}`).join('\n'),
    newText: Array.from({ length: 8 }, (_, index) => `new-${index + 1}`).join('\n'),
  })
  const state = {
    agent: null,
    apiCtx,
    markers,
    mock,
    model: null,
    path: filePath,
    fixtureDir,
    provider: null,
    sessionId: null,
    threadId: null,
    workdir,
  }
  try {
    await mkdir(path.dirname(filePath), { recursive: true })
    await writeFile(filePath, Array.from({ length: 8 }, (_, index) => `old-${index + 1}`).join('\n'))
    await mock.start()
    const providerResponse = await apiCtx.call('POST', '/api/platform/providers', {
      name: `e2e-ui-tool-card-provider-${suffix}`,
      description: 'Local streaming provider for Tool card browser contracts.',
      providerType: 'openai',
      baseUrl: mock.baseUrl('/v1'),
      credential: { operation: 'SET', value: `e2e-ui-tool-card-${suffix}` },
      modelCallTimeoutMillis: '60000',
      modelCallIdleTimeoutMillis: '60000',
    })
    assert(providerResponse.status === 201, `create tool-card provider: ${JSON.stringify(providerResponse)}`)
    state.provider = providerResponse.json

    const modelResponse = await apiCtx.call('POST', '/api/platform/models', {
      type: 'MODEL',
      providerName: state.provider.name,
      name: `e2e-ui-tool-card-model-${suffix}`,
      modelId: `wire-ui-tool-card-${suffix}`,
      description: 'Local streaming model for Tool card browser contracts.',
      config: baseModelConfig({
        limit: { context: 4096, output: 256 },
        abilities: {
          tools: true,
          reasoning: false,
          inputModalities: ['TEXT'],
        },
        variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
      }),
    })
    assert(modelResponse.status === 201, `create tool-card model: ${JSON.stringify(modelResponse)}`)
    state.model = modelResponse.json


    const agentResponse = await apiCtx.call('POST', '/api/platform/agents', {
      name: `e2e-ui-tool-card-agent-${suffix}`,
      description: 'Agent used by deterministic Tool card browser contracts.',
      systemPrompt: 'Follow each deterministic Tool request exactly once.',
      execution: { driver: 'builtin', settings: { modelName: state.model.name, variant: 'default' } },
      config: {
        tools: ['write', 'edit', 'bash'],
        skills: [],
        subagents: [],
      },
    })
    assert(agentResponse.status === 201, `create tool-card agent: ${JSON.stringify(agentResponse)}`)
    state.agent = agentResponse.json

    state.space = await createWorkspaceSpace(apiCtx, {
      title: `e2e-ui-tool-card-${stamp}-${suffix}`,
      defaultAgentName: state.agent.name,
    })
    const created = await createWorkspaceSession(apiCtx, state.space.id, {
      idempotencyKey: cid(),
      message: [{ type: 'TEXT', text: `tool card materialize ${suffix}` }],
      modelSelection: {
        modelName: `${state.model.name}-missing`,
        variant: 'default',
      },
      environmentId: matchedEnv.id,
      yoloEnabled: false,
    })
    state.threadId = String(created.threadId)
    state.sessionId = String(created.sessionId)
    return {
      ...state,
      start: async (toolName) => {
        assert(markers[toolName], `unknown tool-card fixture call: ${toolName}`)
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
            userMessageCommand(markers[toolName], cid()),
          ],
        })
        await mock.waitForCall(toolName)
      },
      stream: () => mock.streamCall(),
      finish: () => mock.finishCall(),
      dispose: async () => {
        try {
          await rm(state.fixtureDir, { recursive: true, force: true })
        } finally {
          await cleanupWorkspaceFixtureState(state)
        }
      },
    }
  } catch (error) {
    await rm(state.fixtureDir, { recursive: true, force: true }).catch(() => undefined)
    await cleanupWorkspaceFixtureState(state).catch(() => undefined)
    throw error
  }
}

class CompletingOpenAiMock {
  constructor() {
    this.base = null
    this.listening = false
    this.requestCount = 0
    this.sockets = new Set()
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
    assert(address && typeof address === 'object', 'completion server did not bind')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'completion server is not started')
    return `${this.base}${suffix}`
  }

  async handle(request, response) {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(request.method === 'POST' ? 404 : 405, { Connection: 'close' })
      response.end()
      return
    }
    let body
    try {
      body = JSON.parse(await readRequestBody(request))
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: `invalid completion JSON: ${error.message}` } }))
      return
    }
    this.requestCount += 1
    const created = Math.floor(Date.now() / 1000)
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(sseFrame({
      id: `e2e-ui-usage-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-usage-model',
      choices: [{
        index: 0,
        delta: { role: 'assistant', content: 'E2E_USAGE_COMPLETE' },
        finish_reason: null,
      }],
    }))
    const finalChunk = {
      id: `e2e-ui-usage-final-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-usage-model',
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
    }
    if (this.requestCount === 1) {
      finalChunk.usage = {
        prompt_tokens: 30,
        completion_tokens: 9,
        total_tokens: 39,
        prompt_tokens_details: { cached_tokens: 14 },
        completion_tokens_details: { reasoning_tokens: 0 },
      }
    }
    response.write(sseFrame(finalChunk))
    response.end('data: [DONE]\n\n')
  }

  async close() {
    for (const socket of this.sockets) socket.destroy()
    this.sockets.clear()
    if (!this.listening) return
    await new Promise((resolve, reject) => {
      this.server.close((error) => (error ? reject(error) : resolve()))
    })
    this.listening = false
  }
}

class ToolCardOpenAiMock {
  constructor({ markers, path, workdir, oldText, newText }) {
    this.calls = {
      bash: {
        marker: markers.bash,
        argumentsJson: JSON.stringify({
          command: 'printf "tool-card-bash-%s\\n" {1..80}',
          workdir,
        }),
      },
      edit: {
        marker: markers.edit,
        argumentsJson: JSON.stringify({
          path,
          edits: [{ old_string: oldText, new_string: newText }],
        }),
      },
      write: {
        marker: markers.write,
        argumentsJson: JSON.stringify({
          path,
          content: Array.from({ length: 8 }, (_, index) => `write-${index + 1}`).join('\n'),
        }),
      },
    }
    this.base = null
    this.callResponse = null
    this.callWaiters = []
    this.heartbeat = null
    this.listening = false
    this.sockets = new Set()
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
    assert(address && typeof address === 'object', 'tool-card server did not bind')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'tool-card server is not started')
    return `${this.base}${suffix}`
  }

  async waitForCall(toolName, timeoutMs = 20_000) {
    if (this.callResponse?.toolName === toolName) return
    await Promise.race([
      new Promise((resolve) => {
        this.callWaiters.push({ resolve, toolName })
      }),
      sleep(timeoutMs).then(() => {
        throw new Error(`streaming ${toolName} Provider request did not start`)
      }),
    ])
  }

  finishCall() {
    assert(this.callResponse, 'streaming Tool response is not pending')
    assert(this.callResponse.streamed, 'Tool arguments were not streamed')
    this.stopHeartbeat()
    const {
      body,
      callId,
      completionId,
      created,
      response,
      toolName,
    } = this.callResponse
    response.write(sseFrame({
      id: completionId,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-tool-card-model',
      choices: [{
        index: 0,
        delta: {
          tool_calls: [{
            index: 0,
            id: callId,
            type: 'function',
            function: { name: toolName, arguments: '' },
          }],
        },
        finish_reason: 'tool_calls',
      }],
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    }))
    response.end('data: [DONE]\n\n')
    this.callResponse = null
  }

  async handle(request, response) {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(request.method === 'POST' ? 404 : 405, { Connection: 'close' })
      response.end()
      return
    }
    let body
    try {
      body = JSON.parse(await readRequestBody(request))
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: `invalid tool-card JSON: ${error.message}` } }))
      return
    }
    if (body.messages?.at(-1)?.role === 'tool') {
      this.sendCompletion(response, body)
      return
    }
    const latestUser = [...(body.messages ?? [])].reverse()
      .find((message) => message.role === 'user')
    const serializedUser = JSON.stringify(latestUser ?? {})
    const toolName = Object.keys(this.calls)
      .find((candidate) => serializedUser.includes(this.calls[candidate].marker))
    if (!toolName) {
      this.sendCompletion(response, body)
      return
    }
    assert(this.callResponse == null, 'tool-card mock received a duplicate active Tool call')
    const created = Math.floor(Date.now() / 1000)
    const callId = `call_${toolName}_${cid().replaceAll('-', '')}`
    const completionId = `e2e-ui-tool-card-${cid()}`
    request.socket.setNoDelay(true)
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.flushHeaders()
    response.write(': tool-card request ready\n\n')
    this.callResponse = {
      body,
      callId,
      completionId,
      created,
      response,
      streamed: false,
      toolName,
    }
    response.once('close', () => {
      if (this.callResponse?.response === response) this.callResponse = null
    })
    const readyWaiters = this.callWaiters.filter((waiter) => waiter.toolName === toolName)
    this.callWaiters = this.callWaiters.filter((waiter) => waiter.toolName !== toolName)
    readyWaiters.forEach((waiter) => waiter.resolve())
  }

  async streamCall() {
    assert(this.callResponse, 'Tool response is not pending')
    assert(!this.callResponse.streamed, 'Tool arguments were already streamed')
    const {
      body,
      callId,
      completionId,
      created,
      response,
      toolName,
    } = this.callResponse
    const chunks = chunkText(this.calls[toolName].argumentsJson, 48)
    for (const [index, argumentsChunk] of chunks.entries()) {
      this.writeToolDelta(
        { body, callId, completionId, created, response, toolName },
        argumentsChunk,
        index === 0,
      )
      await sleep(20)
    }
    this.callResponse.streamed = true
    // MODEL_DELTA is lossy; trailing JSON whitespace replays the accumulated call without changing it.
    this.heartbeat = setInterval(() => {
      if (!this.callResponse?.streamed) return
      this.writeToolDelta(this.callResponse, ' ', false)
    }, 100)
  }

  writeToolDelta(
    { body, callId, completionId, created, response, toolName },
    argumentsChunk,
    withRole,
  ) {
    response.write(sseFrame({
      id: completionId,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-tool-card-model',
      choices: [{
        index: 0,
        delta: {
          ...(withRole ? { role: 'assistant' } : {}),
          tool_calls: [{
            index: 0,
            ...(withRole ? { id: callId, type: 'function' } : {}),
            function: {
              ...(withRole ? { name: toolName } : {}),
              arguments: argumentsChunk,
            },
          }],
        },
        finish_reason: null,
      }],
    }))
  }

  stopHeartbeat() {
    if (this.heartbeat) clearInterval(this.heartbeat)
    this.heartbeat = null
  }

  sendCompletion(response, body) {
    const created = Math.floor(Date.now() / 1000)
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(sseFrame({
      id: `e2e-ui-tool-card-complete-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-tool-card-model',
      choices: [{
        index: 0,
        delta: { role: 'assistant', content: 'Tool card fixture completed.' },
        finish_reason: null,
      }],
    }))
    response.write(sseFrame({
      id: `e2e-ui-tool-card-stop-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-tool-card-model',
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    }))
    response.end('data: [DONE]\n\n')
  }

  async close() {
    this.stopHeartbeat()
    if (this.callResponse) this.callResponse.response.destroy()
    this.callResponse = null
    for (const socket of this.sockets) socket.destroy()
    this.sockets.clear()
    if (!this.listening) return
    await new Promise((resolve, reject) => {
      this.server.close((error) => (error ? reject(error) : resolve()))
    })
    this.listening = false
  }
}

class TaskHoldingOpenAiMock {
  constructor({ parentMarker, childName }) {
    this.parentMarker = parentMarker
    this.childName = childName
    this.base = null
    this.listening = false
    this.responses = new Set()
    this.sockets = new Set()
    this.resolveChildRequest = null
    this.childRequest = new Promise((resolve) => {
      this.resolveChildRequest = resolve
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
    assert(address && typeof address === 'object', 'task server did not bind')
    this.base = `http://127.0.0.1:${address.port}`
    this.listening = true
  }

  baseUrl(suffix = '') {
    assert(this.base, 'task server is not started')
    return `${this.base}${suffix}`
  }

  async waitForChildRequest(timeoutMs = 20_000) {
    await Promise.race([
      this.childRequest,
      sleep(timeoutMs).then(() => {
        throw new Error('task child Provider request did not start')
      }),
    ])
  }

  async handle(request, response) {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(request.method === 'POST' ? 404 : 405, { Connection: 'close' })
      response.end()
      return
    }
    let body
    try {
      body = JSON.parse(await readRequestBody(request))
    } catch (error) {
      response.writeHead(400, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ error: { message: `invalid task JSON: ${error.message}` } }))
      return
    }
    const messages = body.messages || []
    if (messages.some((m) => m.role === 'tool')) {
      this.sendCompletion(response, body)
      return
    }
    if (JSON.stringify(messages).includes(this.parentMarker)) {
      this.sendTaskCall(response, body)
      return
    }
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(': hold child model\n\n')
    this.responses.add(response)
    response.once('close', () => this.responses.delete(response))
    this.resolveChildRequest?.()
  }

  sendCompletion(response, body) {
    const created = Math.floor(Date.now() / 1000)
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(sseFrame({
      id: `e2e-ui-task-complete-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-task-model',
      choices: [{
        index: 0,
        delta: { role: 'assistant', content: 'Task delegation initiated.' },
        finish_reason: null,
      }],
    }))
    response.write(sseFrame({
      id: `e2e-ui-task-stop-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-task-model',
      choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    }))
    response.end('data: [DONE]\n\n')
  }

  sendTaskCall(response, body) {
    const created = Math.floor(Date.now() / 1000)
    const callId = `call_task_${cid().replaceAll('-', '')}`
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    response.write(sseFrame({
      id: `e2e-ui-task-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-task-model',
      choices: [{
        index: 0,
        delta: {
          role: 'assistant',
          tool_calls: [{
            index: 0,
            id: callId,
            type: 'function',
            function: {
              name: 'task',
              arguments: JSON.stringify({
                subagent_type: this.childName,
                prompt: 'Remain active while the parent UI renders task status.',
                max_turns: 20,
              }),
            },
          }],
        },
        finish_reason: null,
      }],
    }))
    response.write(sseFrame({
      id: `e2e-ui-task-final-${cid()}`,
      object: 'chat.completion.chunk',
      created,
      model: body.model || 'e2e-ui-task-model',
      choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }],
      usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
    }))
    response.end('data: [DONE]\n\n')
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

function sseFrame(payload) {
  return `data: ${JSON.stringify(payload)}\n\n`
}

function chunkText(text, size) {
  const chunks = []
  for (let offset = 0; offset < text.length; offset += size) {
    chunks.push(text.slice(offset, offset + size))
  }
  return chunks
}

export function waitForThreadSubscription(page, threadId, timeoutMs = 15_000) {
  return waitForThreadFrame(page, threadId, null, timeoutMs)
}

export function waitForThreadSnapshot(page, threadId, onSnapshot, timeoutMs = 15_000) {
  return waitForThreadFrame(page, threadId, onSnapshot, timeoutMs)
}

function waitForThreadFrame(page, threadId, onSnapshot, timeoutMs) {
  return new Promise((resolve, reject) => {
    const listeners = new Map()
    let settled = false
    let timer = null
    const finish = (error) => {
      if (settled) return
      settled = true
      if (timer != null) clearTimeout(timer)
      page.off('websocket', onWebSocket)
      for (const [socket, onFrame] of listeners) socket.off('framereceived', onFrame)
      if (error) {
        reject(error)
      } else {
        resolve()
      }
    }
    const onWebSocket = (socket) => {
      if (new URL(socket.url()).pathname !== '/ws/events') return
      let subscribed = false
      const onFrame = (event) => {
        let message
        try {
          message = JSON.parse(String(event.payload))
        } catch {
          return
        }
        if (message?.resource?.kind !== 'thread' || message.resource.id !== threadId) return
        if (message.type === 'subscribed') {
          subscribed = true
          if (!onSnapshot) finish()
        } else if (onSnapshot && subscribed && message.type === 'event' && message.name === 'snapshot') {
          try {
            onSnapshot(message.data)
            finish()
          } catch (error) {
            finish(error)
          }
        }
      }
      listeners.set(socket, onFrame)
      socket.on('framereceived', onFrame)
    }
    page.on('websocket', onWebSocket)
    timer = setTimeout(() => {
      const expected = onSnapshot ? 'snapshot after subscription' : 'subscription'
      finish(new Error(`thread realtime ${expected} not received: ${threadId}`))
    }, timeoutMs)
  })
}

async function readRequestBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}
