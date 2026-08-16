import { createServer } from 'node:http'

import { baseModelConfig } from '../lib/fixtures.mjs'
import {
  branchSettingsOf,
  createChat,
  createChatThread,
  customMessageCommand,
  enqueueCommands,
  getThreadSnapshot,
  setEnvironmentCommand,
  stopThread,
  userMessageCommand,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import {
  assert,
  cid,
  envelopeData,
  sleep,
} from '../lib/http.mjs'

const CHAT_PANE_STORAGE_PREFIX = 'kk-studio.chat-pane.'

export async function runRefactorContractMatrix(ui) {
  const {
    apiCtx,
    bindThreadComposer,
    consoleErrors,
    createDurableHistoryFixture,
    createHoldingQueueFixture,
    expectNoFatal,
    goto,
    page,
    pageErrors,
    run,
    shot,
    stamp,
    withUiFixture,
  } = ui

  await run(
    'ui.chat.composer.settings_controls_batch',
    'Composer 双层控制栏、Default/YOLO 与 Model→Variant 菜单形成同批设置命令',
    async (caseArt) => {
      await withUiFixture(
        page,
        () => createHoldingQueueFixture(apiCtx, {
          title: `e2e-ui-composer-settings-${stamp}`,
          historicalMessage: `composer settings hold ${stamp}`,
          queuedMessages: [`existing queued message ${stamp}`],
        }),
        async (fixture) => {
          const composer = await bindThreadComposer(page, goto, fixture)
          const pane = page.locator('[data-pane-id="pane-1"]')
          const add = pane.getByRole('button', { name: '打开命令表' })
          const permission = pane.getByRole('button', { name: '权限模式' })
          const model = pane.getByRole('button', { name: 'Model 与 Variant' })
          const send = pane.getByRole('button', { name: '发送消息' })

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
            geometry.dockHeight >= 92
            && geometry.dockHeight <= 96
            && geometry.editorHeight >= 34
            && geometry.editorHeight <= 38
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
          const permissionOptions = page.getByRole('listbox', { name: '权限选项' })
          await permissionOptions.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            JSON.stringify(await permissionOptions.getByRole('option').allTextContents())
              === JSON.stringify(['Default', 'YOLO']),
            `Permission menu must contain only Default/YOLO: ${JSON.stringify(
              await permissionOptions.getByRole('option').allTextContents(),
            )}`,
          )
          await permissionOptions.getByRole('option', { name: 'YOLO', exact: true }).click()
          assert((await permission.innerText()).includes('YOLO'), 'YOLO selection was not retained')

          await model.click()
          const modelOptions = page.getByRole('listbox', { name: 'Model 选项' })
          await modelOptions.waitFor({ state: 'visible', timeout: 10_000 })
          await modelOptions.getByRole('option', {
            name: `${fixture.provider.name}/${fixture.model.name}`,
            exact: true,
          }).click()
          const variantOptions = page.getByRole('listbox', { name: 'Variant 选项' })
          await variantOptions.waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            (await variantOptions.getByRole('option').allTextContents()).includes('review'),
            'two-level Model menu did not expose the review Variant',
          )
          await variantOptions.getByRole('option', { name: 'review', exact: true }).click()
          assert(
            (await model.innerText()).includes(`${fixture.provider.name}/${fixture.model.name} · review`),
            'selected Model/Variant was not retained in the Composer',
          )

          const marker = `composer settings batch ${stamp}`
          await composer.fill(marker)
          await send.click()
          const queued = await waitForQueuedSettingsBatch(apiCtx, fixture.threadId, marker)
          assert(
            queued.map((command) => command.type).join(',')
              === 'SET_MODEL,SET_YOLO,USER_MESSAGE',
            `settings and message were not queued in frozen order: ${JSON.stringify(queued)}`,
          )
          const modelPayload = JSON.parse(queued[0].payloadJson)
          const yoloPayload = JSON.parse(queued[1].payloadJson)
          assert(
            modelPayload.model?.providerName === fixture.provider.name
            && modelPayload.model?.modelName === fixture.model.name
            && modelPayload.model?.variant === 'review',
            `queued SET_MODEL does not match the UI selection: ${JSON.stringify(modelPayload)}`,
          )
          assert(
            yoloPayload.yoloEnabled === true,
            `queued SET_YOLO does not match the UI selection: ${JSON.stringify(yoloPayload)}`,
          )

          await shot(caseArt, 'composer-settings-controls-batch')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.composer.multi_pane_settings_isolation',
    '双 Pane Composer 的 Permission 菜单、状态与 Escape 焦点互不串扰',
    async (caseArt) => {
      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-composer-multipane-${stamp}`,
          messages: [`multi pane baseline ${stamp}`],
          extraThreadCount: 1,
        }),
        async (fixture) => {
          const secondThreadId = fixture.extraThreadIds[0]
          assert(secondThreadId, 'multi-pane fixture did not create a second Thread')
          await bindSplitThreads(page, goto, fixture.chat.id, fixture.threadId, secondThreadId)
          const pane1 = page.locator('[data-pane-id="pane-1"]')
          const pane2 = page.locator('[data-pane-id="pane-2"]')
          const permission1 = pane1.getByRole('button', { name: '权限模式' })
          const permission2 = pane2.getByRole('button', { name: '权限模式' })
          await permission1.waitFor({ state: 'visible', timeout: 15_000 })
          await permission2.waitFor({ state: 'visible', timeout: 15_000 })

          await permission1.click()
          await page.getByRole('listbox', { name: '权限选项' })
            .getByRole('option', { name: 'YOLO', exact: true })
            .click()
          assert((await permission1.innerText()).includes('YOLO'), 'pane-1 did not retain YOLO')
          assert((await permission2.innerText()).includes('Default'), 'pane-2 permission was polluted')

          await permission2.click()
          assert(
            await page.getByRole('listbox', { name: '权限选项' }).count() === 1,
            'more than one Composer settings menu owned keyboard focus',
          )
          assert(
            await permission1.getAttribute('aria-expanded') === 'false'
            && await permission2.getAttribute('aria-expanded') === 'true',
            'opening pane-2 did not close pane-1 menu',
          )
          await page.getByRole('listbox', { name: '权限选项' }).press('Escape')
          const composer2 = pane2.getByRole('textbox', { name: '给 AI 发送消息' })
          await page.waitForFunction(
            (element) => document.activeElement === element,
            await composer2.elementHandle(),
            { timeout: 10_000 },
          )
          assert((await permission1.innerText()).includes('YOLO'), 'pane-1 draft changed after pane-2 Escape')
          assert((await permission2.innerText()).includes('Default'), 'pane-2 draft changed after Escape')

          await shot(caseArt, 'composer-multi-pane-settings-isolation')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.chat.footer.readonly_facts',
    'Footer 只展示真实 Environment/Workspace、usage/context/cache facts 且无交互',
    async (caseArt) => {
      await withUiFixture(
        page,
        () => createCompletedUsageFixture(apiCtx, stamp),
        async (fixture) => {
          await bindThreadComposer(page, goto, fixture)
          const footer = page.getByLabel('会话状态')
          await footer.waitFor({ state: 'visible', timeout: 15_000 })
          const environment = footer.locator('.thread-status-environment .thread-status-seg')
          const environmentText = await environment.innerText()
          assert(environmentText.includes('…'), `long Workspace path was not middle-ellipsized: ${environmentText}`)
          assert(
            await environment.getAttribute('title')
              === `env:${fixture.environment.name} · @/${fixture.environment.workspacePath} (unavailable)`,
            `Footer title did not retain the complete safe wire path: ${await environment.getAttribute('title')}`,
          )
          assert(
            (await footer.locator('.thread-status-usage').innerText()).includes('↑16')
            && (await footer.locator('.thread-status-usage').innerText()).includes('↓9')
            && (await footer.locator('.thread-status-usage').innerText()).includes('R14'),
            `Footer usage facts are incomplete: ${await footer.innerText()}`,
          )
          assert(
            (await footer.locator('.thread-status-context').innerText()).includes('ctx 30/4.1k'),
            `Footer context fact is incorrect: ${await footer.innerText()}`,
          )
          assert(
            (await footer.locator('.thread-status-cache').innerText()).includes('cache 47%'),
            `Footer cache hit fact is incorrect: ${await footer.innerText()}`,
          )
          assert(await footer.locator('.thread-status-git').count() === 0, 'missing Git fact was fabricated')
          assert(await footer.getByRole('button').count() === 0, 'readonly Footer rendered a button')
          const footerText = await footer.innerText()
          for (const forbidden of [
            fixture.agent.name,
            `${fixture.provider.name}/${fixture.model.name}`,
            'Default',
            'YOLO',
            'notify:',
            '浏览器通知',
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
    'ui.chat.task_status.bound_widget',
    'Bound ChatPanel 通过应用事件永久呈现活动 task 状态',
    async (caseArt) => {
      await withUiFixture(
        page,
        () => createActiveTaskFixture(apiCtx, stamp),
        async (fixture) => {
          await bindThreadComposer(page, goto, fixture)
          await fixture.start()
          const widget = page.locator('.task-status-widget')
          await widget.waitFor({ state: 'visible', timeout: 20_000 })
          await widget.getByText(fixture.childAgent.name, { exact: true })
            .waitFor({ state: 'visible', timeout: 10_000 })
          await widget.getByText('模型运行中', { exact: true })
            .waitFor({ state: 'visible', timeout: 10_000 })
          assert(
            (await widget.innerText()).includes('1 个运行中'),
            `TaskStatus did not project the active child: ${await widget.innerText()}`,
          )
          assert(
            (await widget.innerText()).includes('模型运行中'),
            `TaskStatus did not retain the child state: ${await widget.innerText()}`,
          )
          await shot(caseArt, 'bound-task-status-widget')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )

  await run(
    'ui.settings.notifications_single_entry',
    '浏览器通知只在 Settings 暴露唯一开关，Thread Footer 不再提供入口',
    async (caseArt) => {
      await goto('/settings')
      const switches = page.getByRole('switch', { name: '浏览器通知' })
      await switches.waitFor({ state: 'visible', timeout: 10_000 })
      assert(await switches.count() === 1, 'Settings must expose exactly one notification switch')

      await withUiFixture(
        page,
        () => createDurableHistoryFixture(apiCtx, {
          title: `e2e-ui-notification-entry-${stamp}`,
          messages: [`notification entry baseline ${stamp}`],
        }),
        async (fixture) => {
          await bindThreadComposer(page, goto, fixture)
          const panel = page.locator('.thread-panel')
          assert(await panel.getByRole('switch').count() === 0, 'Thread panel exposed a notification switch')
          assert(
            await panel.locator('.thread-status-footer button').count() === 0,
            'Thread Footer exposed an interactive notification control',
          )
          assert(!(await panel.innerText()).includes('notify:'), 'legacy notification Footer label remains')
          await shot(caseArt, 'settings-notification-single-entry')
          expectNoFatal(pageErrors, consoleErrors)
        },
      )
    },
  )
}

async function bindSplitThreads(page, goto, chatId, firstThreadId, secondThreadId) {
  await goto('/chats')
  await page.evaluate(
    ({ key, first, second }) => {
      localStorage.setItem(
        key,
        JSON.stringify({
          layout: 'split-2',
          focusedPaneId: 'pane-1',
          panes: Array.from({ length: 8 }, (_, index) => ({
            id: `pane-${index + 1}`,
            threadId: index === 0 ? first : index === 1 ? second : null,
          })),
          threadSort: 'recent',
        }),
      )
    },
    {
      key: `${CHAT_PANE_STORAGE_PREFIX}${chatId}`,
      first: firstThreadId,
      second: secondThreadId,
    },
  )
  await goto(`/chats/${encodeURIComponent(chatId)}`)
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
      command.type === 'USER_MESSAGE' && command.payloadJson.includes(marker),
    )
    if (userIndex >= 2) {
      return commands.slice(userIndex - 2, userIndex + 1)
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
    chat: null,
    environment: {
      name: `offline-${suffix}`,
      workspacePath: 'projects/very-long-directory-name/packages/runtime/thread-panel',
    },
    mock,
    model: null,
    provider: null,
    threadId: null,
  }
  try {
    await mock.start()
    const providerResponse = await apiCtx.call('POST', '/api/ai/catalog/providers', {
      name: `e2e-ui-usage-provider-${suffix}`,
      description: 'Local completion provider for free Footer usage tests.',
      providerType: 'openai',
      baseUrl: mock.baseUrl('/v1'),
      credential: `e2e-ui-usage-${suffix}`,
      modelCallTimeoutMillis: 30_000,
      modelCallIdleTimeoutMillis: 10_000,
    })
    assert(providerResponse.status === 201, `create usage provider: ${JSON.stringify(providerResponse)}`)
    state.provider = envelopeData(providerResponse.json)

    const modelResponse = await apiCtx.call('POST', '/api/ai/catalog/models', {
      providerName: state.provider.name,
      name: `e2e-ui-usage-model-${suffix}`,
      description: 'Local completion model for free Footer usage tests.',
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
    })
    assert(modelResponse.status === 201, `create usage model: ${JSON.stringify(modelResponse)}`)
    state.model = envelopeData(modelResponse.json)

    const agentResponse = await apiCtx.call('POST', '/api/ai/catalog/agents', {
      name: `e2e-ui-usage-agent-${suffix}`,
      description: 'Local completion agent for free Footer usage tests.',
      systemPrompt: 'Return the deterministic local response.',
      model: `${state.model.providerName}/${state.model.name}`,
      variant: 'default',
      config: { tools: [], skills: [], subagents: [] },
    })
    assert(agentResponse.status === 201, `create usage agent: ${JSON.stringify(agentResponse)}`)
    state.agent = envelopeData(agentResponse.json)

    state.chat = await createChat(apiCtx, {
      title: `e2e-ui-footer-${stamp}-${suffix}`,
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
      commands: [userMessageCommand(`footer usage ${stamp}`, cid())],
    })
    const completed = await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 30_000,
      intervalMs: 100,
    })
    await enqueueCommands(apiCtx, state.threadId, {
      expectedHeadEntryId: completed.headEntryId,
      expectedNextCommandSequence: completed.nextCommandSequence,
      commands: [
        setEnvironmentCommand(state.environment, cid()),
        customMessageCommand('SYSTEM', `footer environment ${stamp}`, cid()),
      ],
    })
    await waitForQuiescentThread(apiCtx, state.threadId, {
      timeoutMs: 30_000,
      intervalMs: 100,
    })
    return {
      ...state,
      dispose: () => cleanupCompletedUsageFixture(state),
    }
  } catch (error) {
    await cleanupCompletedUsageFixture(state).catch(() => undefined)
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
    chat: null,
    mock,
    model: null,
    parentAgent: null,
    provider: null,
    threadId: null,
  }
  try {
    await mock.start()
    const providerResponse = await apiCtx.call('POST', '/api/ai/catalog/providers', {
      name: `e2e-ui-task-provider-${suffix}`,
      description: 'Local task provider for free TaskStatus UI tests.',
      providerType: 'openai',
      baseUrl: mock.baseUrl('/v1'),
      credential: `e2e-ui-task-${suffix}`,
      modelCallTimeoutMillis: 60_000,
      modelCallIdleTimeoutMillis: 60_000,
    })
    assert(providerResponse.status === 201, `create task provider: ${JSON.stringify(providerResponse)}`)
    state.provider = envelopeData(providerResponse.json)

    const modelResponse = await apiCtx.call('POST', '/api/ai/catalog/models', {
      providerName: state.provider.name,
      name: `e2e-ui-task-model-${suffix}`,
      description: 'Local task model for free TaskStatus UI tests.',
      config: baseModelConfig({
        limit: { context: 4096, output: 128 },
        abilities: {
          tools: true,
          reasoning: false,
          inputModalities: ['TEXT'],
        },
        variants: [{ id: 'default', temperature: 0 }],
      }),
    })
    assert(modelResponse.status === 201, `create task model: ${JSON.stringify(modelResponse)}`)
    state.model = envelopeData(modelResponse.json)

    const childResponse = await apiCtx.call('POST', '/api/ai/catalog/agents', {
      name: childName,
      description: 'Held child used by the TaskStatus browser fixture.',
      systemPrompt: 'Stay in the deterministic local model call.',
      model: `${state.model.providerName}/${state.model.name}`,
      variant: 'default',
      config: { tools: [], skills: [], subagents: [] },
    })
    assert(childResponse.status === 201, `create task child: ${JSON.stringify(childResponse)}`)
    state.childAgent = envelopeData(childResponse.json)

    const parentResponse = await apiCtx.call('POST', '/api/ai/catalog/agents', {
      name: `e2e-ui-task-parent-${suffix}`,
      description: 'Parent used by the TaskStatus browser fixture.',
      systemPrompt: 'Call the configured task subagent exactly once.',
      model: `${state.model.providerName}/${state.model.name}`,
      variant: 'default',
      config: { tools: [], skills: [], subagents: [state.childAgent.name] },
    })
    assert(parentResponse.status === 201, `create task parent: ${JSON.stringify(parentResponse)}`)
    state.parentAgent = envelopeData(parentResponse.json)

    state.chat = await createChat(apiCtx, {
      title: `e2e-ui-task-${stamp}-${suffix}`,
      agentName: state.parentAgent.name,
      yoloEnabled: true,
    })
    const created = await createChatThread(apiCtx, state.chat.id, {
      title: null,
      yoloEnabled: true,
      branchSettings: branchSettingsOf(
        state.parentAgent,
        {
          providerName: state.model.providerName,
          modelName: state.model.name,
          variant: 'default',
        },
        { activeTools: ['task'] },
      ),
    })
    state.threadId = created.thread.threadId
    return {
      ...state,
      // TOOL_PARTIAL/task.status 是 lossy realtime：浏览器先绑定 Thread，再显式启动 task。
      start: async () => {
        await enqueueCommands(apiCtx, state.threadId, {
          expectedHeadEntryId: created.thread.headEntryId,
          expectedNextCommandSequence: created.thread.nextCommandSequence,
          commands: [userMessageCommand(parentMarker, cid())],
        })
        await mock.waitForChildRequest()
      },
      dispose: () => cleanupActiveTaskFixture(state),
    }
  } catch (error) {
    await cleanupActiveTaskFixture(state).catch(() => undefined)
    throw error
  }
}

async function cleanupCompletedUsageFixture(state) {
  const errors = []
  await cleanup('stop thread', errors, async () => {
    if (!state.threadId) return
    const snapshot = await getThreadSnapshot(state.apiCtx, state.threadId)
    if (
      snapshot.thread.status !== 'IDLE'
      || snapshot.thread.processing
      || snapshot.queuedCommands.length > 0
      || snapshot.modelInvocation !== null
    ) {
      await stopThread(state.apiCtx, state.threadId, {
        stopRequestId: cid(),
        expectedRevision: snapshot.thread.revision,
      })
    }
  })
  await cleanup('mock', errors, () => state.mock.close())
  await cleanup('chat', errors, async () => {
    if (state.chat?.id) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/chat/${encodeURIComponent(state.chat.id)}?expectedVersion=${encodeURIComponent(state.chat.version)}`,
      )
    }
  })
  await cleanup('agent', errors, async () => {
    if (state.agent?.name) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/catalog/agents/${encodeURIComponent(state.agent.name)}?expectedVersion=${encodeURIComponent(state.agent.version)}`,
      )
    }
  })
  await cleanup('model', errors, async () => {
    if (state.model?.providerName && state.model?.name) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/catalog/models?providerName=${encodeURIComponent(state.model.providerName)}&modelName=${encodeURIComponent(state.model.name)}&expectedVersion=${encodeURIComponent(state.model.version)}`,
      )
    }
  })
  await cleanup('provider', errors, async () => {
    if (state.provider?.name) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/catalog/providers/${encodeURIComponent(state.provider.name)}?expectedVersion=${encodeURIComponent(state.provider.version)}`,
      )
    }
  })
  if (errors.length > 0) {
    throw new Error(`Footer fixture cleanup failed: ${errors.join('; ')}`)
  }
}

async function cleanupActiveTaskFixture(state) {
  const errors = []
  await cleanup('stop parent task thread', errors, async () => {
    if (!state.threadId) return
    const snapshot = await getThreadSnapshot(state.apiCtx, state.threadId)
    if (
      snapshot.thread.status !== 'IDLE'
      || snapshot.thread.processing
      || snapshot.queuedCommands.length > 0
      || snapshot.modelInvocation !== null
    ) {
      await stopThread(state.apiCtx, state.threadId, {
        stopRequestId: cid(),
        expectedRevision: snapshot.thread.revision,
      })
    }
  })
  await cleanup('task mock', errors, () => state.mock.close())
  await cleanup('task chat', errors, async () => {
    if (state.chat?.id) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/chat/${encodeURIComponent(state.chat.id)}?expectedVersion=${encodeURIComponent(state.chat.version)}`,
      )
    }
  })
  for (const [label, agent] of [
    ['task parent', state.parentAgent],
    ['task child', state.childAgent],
  ]) {
    await cleanup(label, errors, async () => {
      if (agent?.name) {
        await state.apiCtx.call(
          'DELETE',
          `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
        )
      }
    })
  }
  await cleanup('task model', errors, async () => {
    if (state.model?.providerName && state.model?.name) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/catalog/models?providerName=${encodeURIComponent(state.model.providerName)}&modelName=${encodeURIComponent(state.model.name)}&expectedVersion=${encodeURIComponent(state.model.version)}`,
      )
    }
  })
  await cleanup('task provider', errors, async () => {
    if (state.provider?.name) {
      await state.apiCtx.call(
        'DELETE',
        `/api/ai/catalog/providers/${encodeURIComponent(state.provider.name)}?expectedVersion=${encodeURIComponent(state.provider.version)}`,
      )
    }
  })
  if (errors.length > 0) {
    throw new Error(`Task fixture cleanup failed: ${errors.join('; ')}`)
  }
}

async function cleanup(label, errors, action) {
  try {
    await action()
  } catch (error) {
    errors.push(`${label}: ${error?.message || String(error)}`)
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
    if (JSON.stringify(body.messages || []).includes(this.parentMarker)) {
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
                maxTurns: 20,
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

async function readRequestBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}
