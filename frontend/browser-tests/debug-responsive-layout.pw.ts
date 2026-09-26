import { readFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { test, expect } from '@playwright/test'

const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const reportsDir = resolve(new URL('.', import.meta.url).pathname, '../../reports/layout')
try {
  mkdirSync(reportsDir, { recursive: true })
} catch {
  // directory exists
}

function generateFixtureHtml(paneWidthStyle = 'width: 100%;') {
  const tools = [
    { name: 'read', env: 'P+E', filtered: false },
    { name: 'bash', env: 'E', filtered: false },
    { name: 'edit', env: 'P+E', filtered: false },
    { name: 'write', env: 'P+E', filtered: false },
    { name: 'find', env: 'P', filtered: false },
    { name: 'grep', env: 'P', filtered: false },
    { name: 'eval', env: 'E', filtered: true },
    { name: 'docker', env: 'E', filtered: true },
    { name: 'network', env: 'E', filtered: true },
  ]

  const skills = [
    { name: 'dev', type: 'local' },
    { name: 'git-workspace', type: 'local' },
    { name: 'opencli-browser', type: 'local' },
    { name: 'minimax-mavis', type: 'platform' },
  ]

  const events = Array.from({ length: 48 }, (_, i) => ({
    id: `ev-${i + 1}`,
    time: `10:0${Math.floor(i / 10)}:${(i % 60).toString().padStart(2, '0')}`,
    title: i % 4 === 0 ? 'TOOL_CALL' : i % 4 === 1 ? 'MESSAGE' : i % 4 === 2 ? 'TOOL_RESULT' : 'TURN_START',
    summary: `Detailed summary for event step ${i + 1} processing runtime prompt context and status update verification.`,
    status: i === 2 ? 'running' : i === 7 ? 'failed' : 'completed',
  }))

  const toolsHtml = tools
    .map(
      (t) => `
      <button type="button" class="ghost-inline-btn thread-debug-chip ${t.filtered ? 'is-filtered' : ''}" data-tool="${t.name}">
        ${t.filtered ? '<span>⊘ </span>' : ''}<code>${t.name}</code>
        <span class="thread-debug-chip-badge">${t.env}</span>
      </button>
    `,
    )
    .join('')

  const skillsHtml = skills
    .map(
      (s) => `
      <button type="button" class="ghost-inline-btn thread-debug-chip" data-skill="${s.name}">
        <code>${s.name}</code>
        <span class="thread-debug-chip-badge">· ${s.type}</span>
      </button>
    `,
    )
    .join('')

  const eventsHtml = events
    .map(
      (e) => `
      <div id="thread-event-${e.id}" data-event-id="${e.id}" role="option" aria-selected="false" class="thread-event kind-entry status-${e.status}">
        <span class="thread-event-time" data-event-time>${e.time}</span>
        <span class="thread-event-badge">${e.title}</span>
        <span class="thread-event-summary">${e.summary}</span>
        ${e.status === 'running' ? '<span class="thread-event-pulse" aria-hidden="true"></span>' : ''}
      </div>
    `,
    )
    .join('')

  return `
    <!DOCTYPE html>
    <html lang="zh-CN">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <style>
        * { box-sizing: border-box; }
        html, body {
          margin: 0;
          padding: 0;
          width: 100%;
          height: 100%;
          overflow: hidden;
          background: #101412;
          color: #edf2ed;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        }
        .app-root {
          width: 100%;
          height: 100%;
          display: flex;
          align-items: stretch;
          justify-content: flex-start;
        }
      </style>
    </head>
    <body>
      <div class="app-root">
        <section class="chat-pane focused" style="${paneWidthStyle} height: 100%; display: flex; flex-direction: column; overflow: hidden;">
          <section class="chat-shell thread-panel" style="flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden;">
            <main class="chat-main thread-panel-main" style="flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden;">
              <header class="agent-pane-thread-heading" style="flex-shrink: 0; padding: 10px 16px; border-bottom: 1px solid var(--border);">
                <h2 class="agent-pane-thread-title" style="margin: 0; font-size: 14px;">main</h2>
              </header>

              <!-- Debug 主体 3 区容器 -->
              <div class="thread-events-shell" id="debugShell" data-layout="wide" data-active-tab="events">
                <!-- 窄面板 Tabs -->
                <div class="thread-debug-tabs" role="tablist" aria-label="Debug views">
                  <button type="button" role="tab" id="tab-preview" class="thread-debug-tab" data-tab="preview" aria-selected="false">请求预览</button>
                  <button type="button" role="tab" id="tab-events" class="thread-debug-tab active" data-tab="events" aria-selected="true">事件</button>
                  <button type="button" role="tab" id="tab-detail" class="thread-debug-tab" data-tab="detail" aria-selected="false">详情</button>
                </div>

                <!-- 3 区内容网格 -->
                <div class="thread-debug-grid" id="debugGrid" data-active-tab="events">
                  <!-- 列 1: 请求预览 -->
                  <section id="col-preview" role="tabpanel" class="thread-debug-col thread-debug-col-preview">
                    <section class="thread-system-prompt thread-model-request-debug" aria-label="Next Request Preview">
                      <header class="thread-debug-preview-header">
                        <div class="thread-debug-preview-title-group">
                          <span class="thread-debug-preview-title">DEBUG · NEXT REQUEST PREVIEW</span>
                          <span class="status-pill is-ready">env: dev-node</span>
                        </div>
                        <div class="thread-debug-preview-actions">
                          <button type="button" class="ghost-inline-btn" id="btnViewRequest">Request</button>
                          <button type="button" class="ghost-inline-btn">Copy</button>
                        </div>
                      </header>

                      <div class="thread-debug-section">
                        <div class="thread-debug-section-header"><span>System Prompt</span></div>
                        <pre class="thread-system-prompt-body" id="systemPromptBody">You are **JIJI**, a coding agent. You are expected to be precise, safe, and helpful.
**KK** is your owner and an expert programmer. You assist him with coding and a wide variety of software engineering tasks.

Your core responsibility is to inspect repository evidence, execute commands, modify files cautiously, and verify changes thoroughly before declaring completion.

Guidelines:
1. Always establish the current state before editing.
2. Read before writing; use exact string replacement for edits.
3. Keep increments logically coherent and minimal.
4. Verify tests and browser behaviors accurately.
5. Respect git hygiene and never push without authorization.

Long paragraph: The model request preview provides transparency into system prompt instructions, active tools configured for execution, local and platform skills injected into the turn context, and subagents available for delegation. Long lines must wrap properly across narrow columns without sacrificing content readability or breaking layout containment. No horizontal overflow is permitted. All tokens and descriptions remain completely accessible to the user.

Additional section instructions:
- Ensure all repository inspections are grounded in workspace files.
- Prefer explicit string replacements with surrounding context.
- Never weaken test assertions to mask architectural failures.
- Provide comprehensive diff reviews before final delivery.
- Maintain responsive container query adaptation across all viewports.
- Keep individual scroll owners isolated without nested overflows.
- Render json payloads with word-break and wrapped formatting.
- Guard against regression in conversation and debug panel switches.

Extended Operational Rules:
- Rule A1: Every turn context must retain complete deterministic state.
- Rule A2: Request previews must show frozen model parameters including system prompt, active tools, local skills, platform skills, and subagents.
- Rule A3: Scroll ownership must belong to the respective column wrapper and never propagate as nested scroll traps.
- Rule A4: The composer at the bottom must remain statically docked, fully accessible, and unobstructed by debug inspectors.
- Rule A5: Keyboard navigation across tabs must implement WAI-ARIA roving tabindex (ArrowLeft, ArrowRight, Home, End).
- Rule A6: Esc key in detail view or inspector must cleanly return focus to the invoking trigger element without leaking global events.
- Rule A7: Wide layouts (>=1100px) render three synchronized columns simultaneously; tabs are completely suppressed.
- Rule A8: Narrow layouts (<1100px) dynamically adapt to single-column tab views with automatic detail activation.
- Rule A9: Empty detail states display informative placeholders without breaking column dimensions.
- Rule A10: All ID attributes across multiple pane instances must remain uniquely scoped via React useId or unique container namespaces.</pre>
                      </div>

                      <div class="thread-debug-section">
                        <div class="thread-debug-section-header"><span>TOOLS 6 sent · 3 filtered</span></div>
                        <div class="thread-debug-rail" data-testid="debug-tools-rail">
                          ${toolsHtml}
                        </div>
                      </div>

                      <div class="thread-debug-section">
                        <div class="thread-debug-section-header"><span>SKILLS 4</span></div>
                        <div class="thread-debug-rail" data-testid="debug-skills-rail">
                          ${skillsHtml}
                        </div>
                      </div>

                      <div class="thread-debug-meta-row">
                        <div><span class="thread-debug-meta-label">Subagents:</span> helper, explorer, searcher</div>
                        <div><span class="thread-debug-meta-label">Cache:</span> SHORT (prefix-key-1)</div>
                      </div>
                    </section>
                  </section>

                  <!-- 列 2: 事件列表 -->
                  <section id="col-events" role="tabpanel" class="thread-debug-col thread-debug-col-events">
                    <div class="thread-events" id="eventsList" role="listbox" tabindex="0" aria-label="事件">
                      ${eventsHtml}
                    </div>
                  </section>

                  <!-- 列 3: 详情 -->
                  <section id="col-detail" role="tabpanel" class="thread-debug-col thread-debug-col-detail">
                    <section class="thread-event-detail" id="detailSection">
                      <header class="thread-event-detail-header">
                        <h3 id="detailTitle">MESSAGE · Turn 3</h3>
                        <button type="button" class="thread-interaction-close" id="btnCloseDetail" aria-label="关闭事件详情">✕</button>
                      </header>
                      <dl class="thread-event-detail-rows">
                        <div class="thread-event-detail-row">
                          <dt>Entry ID</dt>
                          <dd>bcc4af8f-8515-4471-ba56-be4aa14f8c84</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Entry 类型</dt>
                          <dd>MESSAGE</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>回合</dt>
                          <dd>3</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>角色</dt>
                          <dd>ASSISTANT</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>时间</dt>
                          <dd>1790431548.912</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Tokens Input</dt>
                          <dd>12,482 tokens</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Tokens Output</dt>
                          <dd>1,230 tokens</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Model ID</dt>
                          <dd>claude-3-5-sonnet-20241022</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Stop Reason</dt>
                          <dd>end_turn</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Latency</dt>
                          <dd>2.34s</dd>
                        </div>
                        <div class="thread-event-detail-row">
                          <dt>Prompt Cache Read</dt>
                          <dd>9,120 tokens</dd>
                        </div>
                      </dl>
                      <pre class="thread-event-detail-payload" id="detailPayload">{
  "message": {
    "role": "ASSISTANT",
    "contents": [
      {
        "type": "thinking",
        "text": "The user shows an image of an anthropomorphic animal character - looks like a raccoon or badger-like creature with striped face mask. It's drinking a canned beverage, wearing a hoodie/jacket, with odd eyes. The user asks \\"老鼠么\\" (is it a mouse/rat?). Should describe what it actually is. The animal has a dark eye mask stripe pattern, pointed snout, grayish fur - that's characteristic of a raccoon (浣熊) or a badger (獾). Actually the face has a distinctive dark stripe from forehead to nose, white cheeks/muzzle... That's typical of a badger (European badger has white face with two black stripes) or a raccoon (dark mask around eyes)."
      },
      {
        "type": "text",
        "text": "这不是老鼠，看起来更像一只拟人化的北美浣熊（Raccoon）或欧亚獾（Badger）。\\n\\n特征分析：\\n1. 面部有非常典型的深色眼罩花纹；\\n2. 鼻吻部向前突出，两侧面颊呈白色绒毛；\\n3. 毛色呈灰黑色交织；\\n4. 穿着连帽衫饮用易拉罐饮料，属于风格化的拟人插画角色设计。"
      },
      {
        "type": "trace",
        "meta": {
          "inferenceSteps": 42,
          "contextWindowUsage": "24%",
          "streamDurationMs": 2341,
          "traceId": "tr-998877665544332211"
        }
      }
    ]
  }
}</pre>
                    </section>
                  </section>
                </div>
              </div>

              <!-- 底部 Composer 区域 -->
              <div class="thread-composer" id="composerSection" style="flex-shrink: 0; padding: 12px 16px; border-top: 1px solid var(--border); background: var(--surface);">
                <div style="display: flex; gap: 8px; align-items: center;">
                  <input type="text" id="composerInput" placeholder="输入任务 (/打开命令)" style="flex: 1; padding: 8px 12px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg); color: var(--fg);" />
                  <button type="button" id="composerSubmit" style="padding: 8px 16px; border-radius: 6px; background: var(--accent); color: #000; font-weight: 600; border: none; cursor: pointer;">发送</button>
                </div>
              </div>
            </main>
          </section>
        </section>
      </div>

      <script>
        const shell = document.getElementById('debugShell');
        const grid = document.getElementById('debugGrid');
        const tabs = document.querySelectorAll('.thread-debug-tab');
        const events = document.querySelectorAll('.thread-event');
        const btnClose = document.getElementById('btnCloseDetail');
        let lastSource = 'events';

        function setTab(tab) {
          shell.setAttribute('data-active-tab', tab);
          grid.setAttribute('data-active-tab', tab);
          tabs.forEach(t => {
            const active = t.getAttribute('data-tab') === tab;
            t.classList.toggle('active', active);
            t.setAttribute('aria-selected', active ? 'true' : 'false');
          });
        }

        tabs.forEach(tab => {
          tab.addEventListener('click', () => {
            setTab(tab.getAttribute('data-tab'));
          });
        });

        events.forEach(ev => {
          ev.addEventListener('click', () => {
            events.forEach(e => e.classList.remove('active'));
            ev.classList.add('active');
            lastSource = 'events';
            setTab('detail');
          });
        });

        document.querySelectorAll('[data-tool]').forEach(btn => {
          btn.addEventListener('click', () => {
            lastSource = 'preview';
            setTab('detail');
          });
        });

        document.getElementById('btnViewRequest').addEventListener('click', () => {
          lastSource = 'preview';
          setTab('detail');
        });

        btnClose.addEventListener('click', () => {
          events.forEach(e => e.classList.remove('active'));
          setTab(lastSource);
        });

        // 监听实际宽度并设置 data-layout 兜底
        const observer = new ResizeObserver(entries => {
          for (const entry of entries) {
            const width = entry.contentRect.width;
            const mode = width >= 1100 ? 'wide' : 'narrow';
            shell.setAttribute('data-layout', mode);
          }
        });
        observer.observe(shell);
      </script>
    </body>
    </html>
  `
}

test.describe('Responsive Debug Layout Regression', () => {
  // Case 1: 1920x900 宽桌面 (宽面板 >= 1100px)
  test('1920x900 wide layout: 3 equal-width columns, independent scroll, no outer overflow, composer usable', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1920, height: 900 })
    await page.setContent(generateFixtureHtml())
    await page.addStyleTag({ content: css })
    await page.waitForTimeout(50)

    const shell = page.locator('#debugShell')
    const colPreview = page.locator('#col-preview')
    const colEvents = page.locator('#col-events')
    const colDetail = page.locator('#col-detail')
    const composer = page.locator('#composerSection')
    const tabs = page.locator('.thread-debug-tabs')

    // 1. 宽屏下 tabs 必须隐藏
    expect(await tabs.isVisible()).toBe(false)

    // 2. 三列必须同时可见
    expect(await colPreview.isVisible()).toBe(true)
    expect(await colEvents.isVisible()).toBe(true)
    expect(await colDetail.isVisible()).toBe(true)

    // 3. 断言三等宽 repeat(3, minmax(0, 1fr))
    const boxPreview = (await colPreview.boundingBox())!
    const boxEvents = (await colEvents.boundingBox())!
    const boxDetail = (await colDetail.boundingBox())!

    expect(Math.abs(boxPreview.width - boxEvents.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(boxEvents.width - boxDetail.width)).toBeLessThanOrEqual(2)
    expect(boxPreview.width).toBeGreaterThan(600)

    // 4. 断言外框无纵向及横向滚动 (overflow hidden)
    const shellScroll = await shell.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(shellScroll.scrollHeight).toBeLessThanOrEqual(shellScroll.clientHeight + 1)
    expect(shellScroll.scrollWidth).toBeLessThanOrEqual(shellScroll.clientWidth + 1)

    // 5. 各列独立纵向滚动验证 (且每列内部无嵌套纵向滚动)
    const previewScrollBefore = await colPreview.evaluate((el) => el.scrollTop)
    const eventsScrollBefore = await page.locator('#eventsList').evaluate((el) => el.scrollTop)
    const detailScrollBefore = await colDetail.evaluate((el) => el.scrollTop)
    expect(previewScrollBefore).toBe(0)

    // 滚动 Preview 列
    await colPreview.evaluate((el) => {
      el.scrollTop = 150
    })
    const previewScrollAfter = await colPreview.evaluate((el) => el.scrollTop)
    expect(previewScrollAfter).toBeGreaterThan(0)
    // 其他两列滚动不受影响
    expect(await page.locator('#eventsList').evaluate((el) => el.scrollTop)).toBe(eventsScrollBefore)
    expect(await colDetail.evaluate((el) => el.scrollTop)).toBe(detailScrollBefore)

    // 滚动 Events 列表
    await page.locator('#eventsList').evaluate((el) => {
      el.scrollTop = 200
    })
    expect(await page.locator('#eventsList').evaluate((el) => el.scrollTop)).toBeGreaterThan(0)
    expect(await colDetail.evaluate((el) => el.scrollTop)).toBe(detailScrollBefore)

    // 滚动 Detail 列
    await colDetail.evaluate((el) => {
      el.scrollTop = 100
    })
    expect(await colDetail.evaluate((el) => el.scrollTop)).toBeGreaterThan(0)

    // 6. 验证每列最多一纵向 scroll owner（内部元素无单独的纵向滚动条）
    const promptPreScrollable = await page.locator('#systemPromptBody').evaluate((el) => {
      const style = window.getComputedStyle(el)
      return style.overflowY === 'auto' || style.overflowY === 'scroll'
    })
    expect(promptPreScrollable).toBe(false)

    const payloadPreScrollable = await page.locator('#detailPayload').evaluate((el) => {
      const style = window.getComputedStyle(el)
      return style.overflowY === 'auto' || style.overflowY === 'scroll'
    })
    expect(payloadPreScrollable).toBe(false)

    // 7. Composer 可用且位于视口底部，无遮挡不穿透
    const composerBox = (await composer.boundingBox())!
    expect(composerBox.y + composerBox.height).toBeLessThanOrEqual(900)
    expect(composerBox.y).toBeGreaterThan(boxPreview.y + boxPreview.height - 2)
    const composerInput = page.locator('#composerInput')
    await composerInput.fill('测试任务指令')
    expect(await composerInput.inputValue()).toBe('测试任务指令')

    // 8. 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-wide-1920x900.png') })
  })

  // Case 2: 3834x681 超宽矮窗口 (对应用户截图 2 问题)
  test('3834x681 ultrawide short layout: 3 equal-width columns without squishing composer', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 3834, height: 681 })
    await page.setContent(generateFixtureHtml())
    await page.addStyleTag({ content: css })
    await page.waitForTimeout(50)

    const colPreview = page.locator('#col-preview')
    const colEvents = page.locator('#col-events')
    const colDetail = page.locator('#col-detail')
    const composer = page.locator('#composerSection')

    // 三列并排等宽
    const boxPreview = (await colPreview.boundingBox())!
    const boxEvents = (await colEvents.boundingBox())!
    const boxDetail = (await colDetail.boundingBox())!

    expect(Math.abs(boxPreview.width - boxEvents.width)).toBeLessThanOrEqual(2)
    expect(Math.abs(boxEvents.width - boxDetail.width)).toBeLessThanOrEqual(2)
    expect(boxPreview.width).toBeGreaterThan(1200)

    // 外层容器无滚动
    const shell = page.locator('#debugShell')
    const shellScroll = await shell.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }))
    expect(shellScroll.scrollHeight).toBeLessThanOrEqual(shellScroll.clientHeight + 1)

    // Composer 位于底部且可见可用
    const composerBox = (await composer.boundingBox())!
    expect(composerBox.y + composerBox.height).toBeLessThanOrEqual(681)
    expect(await page.locator('#composerSubmit').isVisible()).toBe(true)

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-ultrawide-3834x681.png') })
  })

  // Case 3: 954x934 窄窗口 (对应用户截图 1 问题)
  test('954x934 narrow layout: tabs switch cleanly, single column full width, detail activation on click', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 954, height: 934 })
    await page.setContent(generateFixtureHtml())
    await page.addStyleTag({ content: css })
    await page.waitForTimeout(50)

    const tabs = page.locator('.thread-debug-tabs')
    expect(await tabs.isVisible()).toBe(true)

    // 默认展示事件列表单列
    const colEvents = page.locator('#col-events')
    const colPreview = page.locator('#col-preview')
    const colDetail = page.locator('#col-detail')

    expect(await colEvents.isVisible()).toBe(true)
    expect(await colPreview.isVisible()).toBe(false)
    expect(await colDetail.isVisible()).toBe(false)

    // 事件列占满可用宽度
    const boxEvents = (await colEvents.boundingBox())!
    expect(boxEvents.width).toBeGreaterThan(900)

    // 点击事件项，自动激活详情页签，且详情单列占满
    await page.locator('#thread-event-ev-3').click()
    expect(await colDetail.isVisible()).toBe(true)
    expect(await colEvents.isVisible()).toBe(false)
    const boxDetail = (await colDetail.boundingBox())!
    expect(boxDetail.width).toBeGreaterThan(900)

    // 点击关闭，回退到事件列表
    await page.locator('#btnCloseDetail').click()
    expect(await colEvents.isVisible()).toBe(true)
    expect(await colDetail.isVisible()).toBe(false)

    // 点击 Tool 唤起详情并关闭回退到请求预览
    await page.locator('#tab-preview').click()
    expect(await colPreview.isVisible()).toBe(true)
    await page.locator('[data-tool="read"]').click()
    expect(await colDetail.isVisible()).toBe(true)
    await page.locator('#btnCloseDetail').click()
    expect(await colPreview.isVisible()).toBe(true)

    // 外层无纵向横向滚动
    const shell = page.locator('#debugShell')
    const shellScroll = await shell.evaluate((el) => ({
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(shellScroll.scrollHeight).toBeLessThanOrEqual(shellScroll.clientHeight + 1)
    expect(shellScroll.scrollWidth).toBeLessThanOrEqual(shellScroll.clientWidth + 1)

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-narrow-954x934.png') })
  })

  // Case 4: 360x740 手机屏幕
  test('360x740 mobile layout: no horizontal overflow, tabs cleanly switch', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.setContent(generateFixtureHtml())
    await page.addStyleTag({ content: css })
    await page.waitForTimeout(50)

    // tabs 可见
    const tabs = page.locator('.thread-debug-tabs')
    expect(await tabs.isVisible()).toBe(true)

    // 无横向溢出
    const shell = page.locator('#debugShell')
    const shellScroll = await shell.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(shellScroll.scrollWidth).toBeLessThanOrEqual(shellScroll.clientWidth + 1)

    // Composer 位于底部可见
    const composerInput = page.locator('#composerInput')
    expect(await composerInput.isVisible()).toBe(true)

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-mobile-360x740.png') })
  })

  // Case 5: 1920x1080 大窗口内 480 宽 pane (严格按容器实际宽度判定，非 window viewport)
  test('480px pane inside 1920x1080 window: must use narrow tab layout based on container width', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1920, height: 1080 })
    await page.setContent(generateFixtureHtml('width: 480px;'))
    await page.addStyleTag({ content: css })
    await page.waitForTimeout(50)

    const shell = page.locator('#debugShell')
    expect(await shell.getAttribute('data-layout')).toBe('narrow')
    const tabs = page.locator('.thread-debug-tabs')
    const colEvents = page.locator('#col-events')
    const colPreview = page.locator('#col-preview')

    // 即使 window 是 1920px，pane 容器只有 480px，必须走窄模式！
    expect(await tabs.isVisible()).toBe(true)
    expect(await colEvents.isVisible()).toBe(true)
    expect(await colPreview.isVisible()).toBe(false)

    const boxEvents = (await colEvents.boundingBox())!
    // 单列占满 480px，绝不会被压缩成 480/3 = 160px 的惨状！
    expect(boxEvents.width).toBeGreaterThan(450)
    expect(boxEvents.width).toBeLessThanOrEqual(480)

    // 保存截图
    await page.screenshot({ path: resolve(reportsDir, 'debug-pane-480px.png') })
  })
})
