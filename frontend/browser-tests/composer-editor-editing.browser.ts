import { test, expect, type Page } from './fixture'

/**
 * ComposerEditor / ThreadComposer 真实 contenteditable 回归。
 *
 * 运行（在 `frontend/`）：
 *   npx vite build --config vite.composer-editor.config.ts
 *   npx playwright test --config playwright.composer-editor.config.ts
 *
 * 覆盖 jsdom 无法真实复现的部分：contenteditable 光标/换行、Chromium IME 组合、
 * 真实文件选择产生的 pill 与整颗删除、上传失败与组合态并发的回滚时序、
 * 隐藏根（active=false）不抢焦点。
 */

async function partsSnapshot(page: Page): Promise<Array<Record<string, string>>> {
  const raw = await page.locator('#parts-json').textContent()
  return JSON.parse(raw ?? '[]') as Array<Record<string, string>>
}

async function partsText(page: Page): Promise<string> {
  return (await partsSnapshot(page))
    .map((part) => (part.type === 'text' ? part.text : `[${part.type}]`))
    .join('')
}

async function openHarness(page: Page) {
  await page.goto('/browser-tests/composer-editor-harness.html')
  await expect(page.locator('[data-testid="root-pane"] .composer-editor')).toBeVisible()
  await expect(page.locator('#submit-count')).toHaveText('0')
}

test.describe('ComposerEditor real contenteditable regression', () => {
  test.beforeEach(async ({ page }) => {
    await openHarness(page)
  })

  test('真实输入回流 ordered parts，Enter 提交一次并清空草稿', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    await editor.click()
    await page.keyboard.type('hello world')

    await expect.poll(() => partsText(page)).toBe('hello world')

    await page.keyboard.press('Enter')
    await expect(page.locator('#submit-count')).toHaveText('1')
    await expect.poll(() => partsText(page)).toBe('')
  })

  test('Shift+Enter 换行归一化为纯文本，不残留块元素', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    await editor.click()
    await page.keyboard.type('line1')
    await page.keyboard.press('Shift+Enter')
    await page.keyboard.type('line2')

    await expect.poll(() => partsText(page)).toBe('line1\nline2')
    expect(await editor.textContent()).toBe('line1\nline2')
    // 规范化后 DOM 只有文本节点，不保留浏览器默认的 div/br 结构。
    expect(await editor.locator('div, br, p').count()).toBe(0)
    await expect(page.locator('#submit-count')).toHaveText('0')
  })

  test('IME 组合期间不回流草稿，组合中 Enter 不提交，候选确认后一次性上屏', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    const client = await page.context().newCDPSession(page)
    await editor.click()

    // 真实 Chromium 组合态：拼音未确认时不产生草稿。
    await client.send('Input.imeSetComposition', {
      text: 'ceshi',
      selectionStart: 5,
      selectionEnd: 5,
    })
    await expect(editor).toHaveText('ceshi')
    await expect.poll(() => partsText(page)).toBe('')

    // 组合中的 Enter 由 IME 确认候选，不得提交消息。
    await editor.evaluate((element) => {
      element.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Enter',
          isComposing: true,
          bubbles: true,
          cancelable: true,
        }),
      )
    })
    await expect(page.locator('#submit-count')).toHaveText('0')

    // 上屏：组合结束，汉字完整保留且只出现一次。
    await client.send('Input.insertText', { text: '测试' })
    await expect.poll(() => partsText(page)).toBe('测试')
    expect(await editor.textContent()).toBe('测试')

    // 组合结束后的 Enter 正常提交。
    await page.keyboard.press('Enter')
    await expect(page.locator('#submit-count')).toHaveText('1')
    expect(await page.locator('#last-submitted').textContent()).toContain('测试')
  })

  test('文件选择插入 pill，光标就位后 Backspace 整颗删除', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    await editor.click()
    await page.keyboard.type('ab')

    await page.setInputFiles('input[type="file"]', {
      name: 'shot.png',
      mimeType: 'image/png',
      buffer: Buffer.from([1, 2, 3]),
    })
    await expect(page.locator('.composer-pill')).toHaveCount(1)
    await expect(page.locator('.composer-pill')).toHaveText('[shot.png]')
    await expect.poll(() => partsText(page)).toBe('ab[attachment]')

    // 光标落在 pill 之后：Backspace 删除整颗 pill 并回流草稿。
    await page.keyboard.press('End')
    await page.keyboard.press('Backspace')
    await expect(page.locator('.composer-pill')).toHaveCount(0)
    await expect.poll(() => partsText(page)).toBe('ab')

    // pill 删除后仍可继续输入，光标与草稿保持一致。
    await page.keyboard.type(' tail')
    await expect.poll(() => partsText(page)).toBe('ab tail')
  })

  test('上传失败与 IME 组合并发时保留组合态，组合结束才回滚 pill', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    const client = await page.context().newCDPSession(page)
    await editor.click()
    await page.click('#fail-upload-btn')

    await page.setInputFiles('input[type="file"]', {
      name: 'failing.bin',
      mimeType: 'application/octet-stream',
      buffer: Buffer.from([1]),
    })
    await expect(page.locator('.composer-pill')).toHaveCount(1)

    // 上传仍在途时开始中文组合输入。
    await client.send('Input.imeSetComposition', {
      text: 'ceshi',
      selectionStart: 5,
      selectionEnd: 5,
    })
    await expect(editor).toContainText('ceshi')

    // 失败到达：错误提示出现，但组合态 DOM 与 pill 都保持原状。
    await expect(page.getByRole('alert')).toContainText('failing.bin')
    await expect(editor).toContainText('ceshi')
    await expect(page.locator('.composer-pill')).toHaveCount(1)
    expect(await partsText(page)).toBe('[attachment]')

    // 组合结束：汉字上屏，失败的 pill 此时才被清理。
    await client.send('Input.insertText', { text: '测试' })
    await expect(page.locator('.composer-pill')).toHaveCount(0)
    await expect.poll(() => partsText(page)).toBe('测试')
    expect(await editor.textContent()).toBe('测试')
  })

  test('active=false 的隐藏根不响应全局 Escape、不抢焦点，恢复可见后回到编辑器', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    await expect(editor).toBeVisible()

    await page.uncheck('#active-toggle')
    await expect(page.locator('.thread-composer')).toHaveAttribute('hidden', '')

    await page.click('#outside-btn')
    await expect(page.locator('#outside-btn')).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(editor).not.toBeFocused()

    // 恢复可见：既有 active 语义仍然把焦点恢复到编辑器末尾。
    await page.check('#active-toggle')
    await expect(editor).toBeFocused()
  })

  test('禁用草稿时 paste/drop/Enter 不编排附件、不提交、不改草稿', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    await editor.click()
    await page.keyboard.type('锁定草稿')

    await page.click('#disabled-toggle')
    await expect(editor).toHaveAttribute('contenteditable', 'false')

    // 真实浏览器里 contentEditable=false 仍会收到 drop/paste：不得产生 pill、不得改写草稿，
    // 也不得 preventDefault 伪装成已处理。
    const prevented = await page.evaluate(() => {
      const target = document.querySelector('.composer-editor') as HTMLElement
      const makeTransfer = (name: string) => {
        const transfer = new DataTransfer()
        transfer.items.add(new File([new Uint8Array([1])], name, { type: 'image/png' }))
        transfer.setData('text/plain', '粘贴文本')
        return transfer
      }
      const dropPrevented = !target.dispatchEvent(
        new DragEvent('drop', {
          bubbles: true,
          cancelable: true,
          dataTransfer: makeTransfer('dropped.png'),
        }),
      )
      const pastePrevented = !target.dispatchEvent(
        new ClipboardEvent('paste', {
          bubbles: true,
          cancelable: true,
          clipboardData: makeTransfer('pasted.png'),
        }),
      )
      return { dropPrevented, pastePrevented }
    })
    expect(prevented).toEqual({ dropPrevented: false, pastePrevented: false })

    await page.keyboard.press('Enter')

    await expect(page.locator('.composer-pill')).toHaveCount(0)
    await expect(page.locator('#submit-count')).toHaveText('0')
    await expect.poll(() => partsText(page)).toBe('锁定草稿')
  })

  test('active=false 切换时已开始的 IME 组合不丢文本、不报错、不提交', async ({ page }) => {
    const editor = page.locator('.composer-editor')
    const client = await page.context().newCDPSession(page)
    await editor.click()

    // 组合进行中（拼音未确认，尚未进入草稿）。
    await client.send('Input.imeSetComposition', {
      text: 'ceshi',
      selectionStart: 5,
      selectionEnd: 5,
    })
    await expect(editor).toContainText('ceshi')
    await expect.poll(() => partsText(page)).toBe('')

    // 根被隐藏：contenteditable 失去可见性/焦点时组合必须被完整结算，
    // 既不抛错、不提交，也不丢字或留下半成品 DOM。
    await page.uncheck('#active-toggle')
    await expect(page.locator('.thread-composer')).toHaveAttribute('hidden', '')
    await expect.poll(() => partsText(page)).toBe('ceshi')
    await expect(page.locator('#submit-count')).toHaveText('0')

    // 恢复可见后草稿与组合文本一致，可继续作为正常草稿提交。
    await page.check('#active-toggle')
    await expect(editor).toBeFocused()
    expect(await editor.textContent()).toBe('ceshi')
    await expect.poll(() => partsText(page)).toBe('ceshi')
    await page.keyboard.press('Enter')
    await expect(page.locator('#submit-count')).toHaveText('1')
    expect(await page.locator('#last-submitted').textContent()).toContain('ceshi')
  })
})
