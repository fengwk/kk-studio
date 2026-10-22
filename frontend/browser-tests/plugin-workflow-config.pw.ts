import { readFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from './fixture'
import type { PluginDTO } from '@/shared/api/generated/contracts'

/**
 * 真实 ComfyUI 插件配置：与 Java 侧共用同一份 HTTP golden（生产 mapper 产出），
 * 因此浏览器里编辑的就是真实 schema 与真实 H3 预置工作流，而不是测试专用的简化结构。
 */
function comfyuiPluginFromGolden(): PluginDTO {
  const source = readFileSync(
    resolve(
      dirname(fileURLToPath(import.meta.url)),
      '../../web/src/test/resources/fun/fengwk/kkstudio/web/plugin/comfyui-http.json',
    ),
    'utf8',
  )
  const envelope = JSON.parse(source) as { data: unknown }
  return envelope.data as PluginDTO
}

const comfyuiPlugin = comfyuiPluginFromGolden()
const originalWorkflowJson = (
  JSON.parse(comfyuiPlugin.configuration.valuesJson) as {
    workflows: { workflowJson: string }[]
  }
).workflows[0].workflowJson

interface Recorded {
  puts: Record<string, unknown>[]
}

/** 只放行 harness 需要的接口：任何额外请求都会让测试失败，避免把「没发请求」当成通过。 */
async function transport(page: Page, plugin: PluginDTO = comfyuiPlugin): Promise<Recorded> {
  const recorded: Recorded = { puts: [] }
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/plugins') {
      await route.fulfill({ json: { status: 200, data: [plugin] } })
      return
    }
    if (path === `/api/plugins/${plugin.pluginId}/configuration` && route.request().method() === 'PUT') {
      const body = route.request().postDataJSON() as Record<string, unknown>
      recorded.puts.push(body)
      await route.fulfill({
        json: {
          status: 200,
          data: {
            ...plugin,
            configuration: { ...plugin.configuration, valuesJson: body.valuesJson as string },
          },
        },
      })
      return
    }
    throw new Error(`Unexpected offline request: ${route.request().method()} ${path}`)
  })
  return recorded
}

async function openConfigDialog(page: Page) {
  await page.goto('/browser-tests/resource-card-harness.html?plugins')
  await page.getByRole('button', { name: '配置 ComfyUI', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  return dialog
}

/**
 * 共享 Checkbox 用视觉隐藏的原生 input 表达可访问性，真实浏览器无法直接点击，
 * 因此点击它绑定的 label 文本（原生行为同样会切换状态）。
 */
async function toggleValidateJson(editorBox: import('@playwright/test').Locator) {
  await editorBox.getByText('按 JSON 校验', { exact: true }).click()
}

/** 横向溢出检查：文档与弹窗主体都不得出现横向滚动，表单内深嵌套标题与动作亦不得溢出容器。 */
async function expectNoHorizontalOverflow(page: Page) {
  const result = await page.evaluate(() => {
    const body = document.querySelector('.resource-modal-card .modal-body')
    if (!body) {
      throw new Error('expected the plugin configuration modal body')
    }

    // 同时检查内容宽度与真实边界，避免外层裁剪掩盖嵌套字段或按钮的越界。
    const selectors = [
      '.plugin-config-field-header',
      '.plugin-config-field-actions',
      '.plugin-config-array-actions',
      '.plugin-config-array-item-actions',
      '.plugin-config-text-editor-toolbar',
    ].join(', ')
    const nestedViolations = Array.from(body.querySelectorAll<HTMLElement>(selectors))
      .map((element) => {
        const parent = element.parentElement
        if (!parent) {
          throw new Error('expected a connected plugin configuration control')
        }
        const rect = element.getBoundingClientRect()
        const parentRect = parent.getBoundingClientRect()
        return {
          item: element.className,
          label: element.querySelector('.field-label, label')?.textContent?.trim() ?? '',
          scrollOverflow: element.scrollWidth - element.clientWidth,
          edgeOverflow: Math.max(parentRect.left - rect.left, rect.right - parentRect.right, 0),
        }
      })
      .filter(({ scrollOverflow, edgeOverflow }) => scrollOverflow > 1 || edgeOverflow > 1)

    return {
      document: document.documentElement.scrollWidth - window.innerWidth,
      body: body.scrollWidth - body.clientWidth,
      nestedViolations,
    }
  })

  expect(result.document).toBeLessThanOrEqual(0)
  expect(result.body).toBeLessThanOrEqual(1)
  expect(result.nestedViolations).toEqual([])
}

test('desktop: edits the real H3 preset workflow, imports a graph file and saves once', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 1000 })
  const recorded = await transport(page)
  const dialog = await openConfigDialog(page)

  const workflow = dialog.getByRole('group', { name: 'workflows[0]', exact: true })
  await expect(workflow.getByRole('textbox', { name: 'id', exact: true })).toHaveValue('minimax-h3-ref2va')

  // 折叠态是单行输入：真实图原文没有换行，说明存的仍是驱动后端的那份 JSON 文本。
  const compactInput = workflow.getByRole('textbox', { name: 'workflowJson', exact: true })
  const compactValue = await compactInput.inputValue()
  expect(compactValue).not.toContain('\n')
  expect(compactValue).toContain('"class_type"')

  await workflow.getByRole('button', { name: 'workflowJson 的多行/JSON 文本编辑器', exact: true }).click()
  const graphEditor = workflow.getByRole('textbox', { name: 'workflowJson', exact: true })
  await expect(graphEditor).toHaveValue(originalWorkflowJson)
  // 文本编辑器容器就是多行输入框的直接父节点，按字段定位才不会受 schema 属性顺序影响。
  const editorBox = graphEditor.locator('..')
  await toggleValidateJson(editorBox)

  // 直接编辑真实图：长整数、精确小数与指数形式都必须原样保留。
  const editedGraph =
    '{\n  "1": {"class_type": "SaveVideo", "inputs": {"seed": 9007199254740993123456789, "ratio": 1.230000}},\n' +
    '  "2": {"class_type": "KSampler", "inputs": {"scale": 1e1000}}\n}'
  await graphEditor.fill(editedGraph)

  // 真实本地 JSON 文件导入：直接替换编辑器内容，不需要手工转义。
  const scratch = mkdtempSync(join(tmpdir(), 'kk-plugin-workflow-'))
  try {
    const importedPath = join(scratch, 'imported-graph.json')
    writeFileSync(
      importedPath,
      '{"3":{"class_type":"ImportedNode","inputs":{"seed":9007199254740993,"exact":1.230000}}}',
    )
    await workflow
      .locator('input[type="file"]')
      .first()
      .setInputFiles(importedPath)
    await expect(graphEditor).toHaveValue(
      '{"3":{"class_type":"ImportedNode","inputs":{"seed":9007199254740993,"exact":1.230000}}}',
    )
    await expect(workflow.locator('[role="alert"]')).toHaveCount(0)

    // 编辑画布函数名：与图内容互不影响。
    await workflow.getByRole('textbox', { name: 'id', exact: true }).fill('minimax-h3-ref2va-edited')

    // argsSchemaJson 同样按 JSON 校验后编辑。
    await workflow.getByRole('button', { name: 'workflowJson 的多行/JSON 文本编辑器', exact: true }).click()
    await workflow.getByRole('button', { name: 'argsSchemaJson 的多行/JSON 文本编辑器', exact: true }).click()
    const argsEditor = workflow.getByRole('textbox', { name: 'argsSchemaJson', exact: true })
    const argsBox = argsEditor.locator('..')
    await toggleValidateJson(argsBox)
    await argsEditor.fill(
      '{"type":"object","properties":{"prompt":{"type":"string"},"duration":{"type":"integer","default":9007199254740993}}}',
    )
    await expect(workflow.locator('[role="alert"]')).toHaveCount(0)

    // 输出选择器：新增一条并把枚举输出字段显式选中。
    const outputs = workflow.getByRole('group', { name: 'outputs', exact: true })
    await outputs.getByRole('button', { name: '添加 outputs 条目', exact: true }).click()
    const added = workflow.getByRole('group', { name: 'outputs[1]', exact: true })
    await added.getByRole('button', { name: 'field', exact: true }).click()
    await page.getByRole('option', { name: 'images', exact: true }).click()
    await added.getByRole('textbox', { name: 'nodeId', exact: true }).fill('92')
    await added.getByRole('textbox', { name: 'index', exact: true }).fill('1')

    // 没有任何未解决的草稿错误，满足保存前置条件。
    await expect(workflow.locator('[role="alert"]')).toHaveCount(0)

    await dialog.getByRole('button', { name: '保存配置', exact: true }).click()
    await expect.poll(() => recorded.puts.length).toBe(1)

    const payload = recorded.puts[0]
    const valuesJson = payload.valuesJson as string
    expect(payload.secretPatch).toEqual({ apiKey: { operation: 'KEEP' } })
    const values = JSON.parse(valuesJson) as {
      workflows: {
        id: string
        workflowJson: string
        argsSchemaJson: string
        outputs: Record<string, unknown>[]
      }[]
    }
    expect(values.workflows[0].id).toBe('minimax-h3-ref2va-edited')
    expect(values.workflows[0].workflowJson).toBe(
      '{"3":{"class_type":"ImportedNode","inputs":{"seed":9007199254740993,"exact":1.230000}}}',
    )
    expect(values.workflows[0].argsSchemaJson).toContain('9007199254740993')
    // 长整数与精确小数以原文进入 valuesJson（在 valuesJson 里表现为转义后的片段），
    // 没有被 JSON.parse/stringify 往返改写。
    expect(valuesJson).toContain('\\"seed\\":9007199254740993')
    expect(valuesJson).toContain('\\"exact\\":1.230000')
    // 单一匿名输出仍然没有 name 键；新增条目也没有被发明出可选名字。
    expect(Object.keys(values.workflows[0].outputs[0])).not.toContain('name')
    expect(Object.keys(values.workflows[0].outputs[1]).sort()).toEqual([
      'field',
      'index',
      'kind',
      'nodeId',
    ])
    expect(values.workflows[0].outputs[1].field).toBe('images')
  } finally {
    rmSync(scratch, { recursive: true, force: true })
  }
})

for (const width of [360, 320] as const) {
  test(`narrow ${width}px: workflow editing stays inside the viewport without horizontal scroll`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height: 720 })
    await transport(page)
    const dialog = await openConfigDialog(page)
    const workflow = dialog.getByRole('group', { name: 'workflows[0]', exact: true })

    await workflow.getByRole('textbox', { name: 'id', exact: true }).fill('narrow-layout-probe')
    await workflow.getByRole('button', { name: 'workflowJson 的多行/JSON 文本编辑器', exact: true }).click()
    const graphEditor = workflow.getByRole('textbox', { name: 'workflowJson', exact: true })
    await expect(graphEditor).toBeVisible()
    const editorBox = workflow.locator('.plugin-config-text-editor').first()
    await toggleValidateJson(editorBox)
    await graphEditor.fill('{"1":{"class_type":"SaveVideo","inputs":{"seed":1}}}')

    const outputs = workflow.getByRole('group', { name: 'outputs', exact: true })
    await outputs.getByRole('button', { name: '添加 outputs 条目', exact: true }).click()
    await expect(workflow.getByRole('group', { name: 'outputs[1]', exact: true })).toBeVisible()

    // 保存按钮必须始终可见可用，且窄屏下不产生横向滚动。
    const save = dialog.getByRole('button', { name: '保存配置', exact: true })
    await expect(save).toBeInViewport()
    await expect(save).toBeEnabled()
    await expectNoHorizontalOverflow(page)

    for (const control of [
      workflow.getByRole('textbox', { name: 'id', exact: true }),
      graphEditor,
      editorBox.getByRole('button', { name: '从文件导入 JSON', exact: true }),
      outputs.getByRole('button', { name: '添加 outputs 条目', exact: true }),
      workflow.getByRole('group', { name: 'outputs[1]', exact: true }),
    ]) {
      await control.scrollIntoViewIfNeeded()
      await expect(control).toBeVisible()
    }
    await expectNoHorizontalOverflow(page)

    await info.attach(`workflow-config-${width}`, {
      body: await page.screenshot(),
      contentType: 'image/png',
    })
  })
}

test('narrow 320px: invalid JSON and oversized values block saving without any request', async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 720 })
  const recorded = await transport(page)
  const dialog = await openConfigDialog(page)
  const workflow = dialog.getByRole('group', { name: 'workflows[0]', exact: true })

  await workflow.getByRole('button', { name: 'workflowJson 的多行/JSON 文本编辑器', exact: true }).click()
  const graphEditor = workflow.getByRole('textbox', { name: 'workflowJson', exact: true })
  const editorBox = graphEditor.locator('..')
  await toggleValidateJson(editorBox)

  const save = dialog.getByRole('button', { name: '保存配置', exact: true })
  await graphEditor.fill('{"1":')
  await expect(workflow.locator('[role="alert"]')).toContainText('JSON 内容无效')
  await expect(save).toBeDisabled()
  await save.click({ force: true })
  expect(recorded.puts).toHaveLength(0)

  // 关掉 JSON 校验后文本按普通字符串落值：转义开销本身就会撑爆 64KiB 预算，
  // 因此字符数远小于上限也必须拦截。
  await toggleValidateJson(editorBox)
  await graphEditor.fill(`"${'"'.repeat(40000)}`)
  const banner = dialog.locator('.plugin-config-warning-banner')
  await expect(banner).toContainText('不得超过 65536 字节')
  await expect(save).toBeDisabled()
  await save.click({ force: true })
  expect(recorded.puts).toHaveLength(0)
  await expectNoHorizontalOverflow(page)
})
