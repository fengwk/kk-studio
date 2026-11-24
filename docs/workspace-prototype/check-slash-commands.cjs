const assert = require('node:assert/strict');
const path = require('node:path');
const { createRequire } = require('node:module');
const { pathToFileURL } = require('node:url');
const requireFrontend = createRequire(path.resolve(__dirname, '../../frontend/package.json'));
const { chromium, expect } = requireFrontend('@playwright/test');

const reports = path.resolve(__dirname, '../../reports/workspace-prototype');
const url = pathToFileURL(path.join(reports, 'kk-studio-workspace.html')).href;

async function check() {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, offline: true });
    const requests = [], errors = [];
    page.on('request', (request) => {
      if (/^https?:/i.test(request.url())) requests.push(request.url());
    });
    page.on('pageerror', (error) => errors.push(error.message));
    page.setDefaultTimeout(5000);
    const app = page.locator('#x-app');
    const pane = app.locator('.x-pane.x-focus');
    const editor = pane.getByRole('textbox', { name: '给 AI 发送消息' });
    const sessions = app.locator('#x-tree .x-row-session');
    const palette = pane.getByRole('listbox', { name: '命令表' });
    const files = palette.getByRole('option', { name: /^files\b/ });
    const send = pane.getByRole('button', { name: '发送消息', exact: true });
    const sent = (text) => pane.locator('.thread-block-user').getByText(text, { exact: true });
    const capture = (name) => page.screenshot({ path: path.join(reports, 'shots', `${name}.png`) });
    const expectPlainDraft = async (text) => {
      await expect(palette, `${JSON.stringify(text)} should not open the slash palette`).toHaveCount(0);
      await expect(editor).toHaveText(text, { useInnerText: true });
      await expect(editor).toBeFocused();
      await expect(editor).not.toHaveAttribute('aria-controls');
      await expect(pane.getByRole('button', { name: '打开命令表' })).toHaveAttribute('aria-expanded', 'false');
      await expect(send).toBeEnabled();
    };

    await page.goto(url);
    const newDraft = async () => {
      await app.getByRole('button', { name: '打开侧边栏' }).click();
      const folder = app.locator('.x-row-folder').filter({ has: page.getByText('默认', { exact: true }) });
      await folder.getByRole('button', { name: '更多操作' }).click();
      await app.getByRole('menuitem', { name: '新建会话', exact: true }).click();
      // The pane already shows content, so the draft opens through the pane choice.
      const destination = app.getByRole('dialog', { name: '打开到窗格', exact: true });
      await expect(destination.locator('[aria-current="true"]')).toBeFocused();
      await page.keyboard.press('Enter');
    };
    await expect(sessions.first()).toBeAttached();
    const before = await sessions.count();
    await newDraft();
    await editor.click();
    await page.keyboard.type('/');
    await expect(files).toBeVisible();
    await page.keyboard.type('fi');
    await expect(files).toBeVisible();
    await expect(send).toBeDisabled();

    // No results must release the composer immediately; editing back must restore keyboard selection.
    await page.keyboard.type('x');
    await expectPlainDraft('/fix');
    await page.keyboard.press('Backspace');
    await expect(editor).toHaveText('/fi');
    await expect(files).toBeVisible();
    await expect(editor).toHaveAttribute('aria-activedescendant', await files.getAttribute('id'));
    await capture('slash-390-matching');
    await page.keyboard.press('Enter');
    await expect(pane.getByRole('img', { name: 'VS Code 工作台静态画面' })).toBeVisible();
    await expect(app.locator('#x-panes .x-pane')).toHaveCount(1);
    await expect(sessions).toHaveCount(before);
    await newDraft();
    await expect(editor).toBeEmpty();

    // A path stays text even if its first component is itself a command name.
    for (const text of ['//files', '/files/', '/files 请作为普通文字']) {
      await editor.fill(text);
      await expectPlainDraft(text);
    }
    const pathMessage = '/files/workspace/README.md';
    await editor.fill(pathMessage);
    await expectPlainDraft(pathMessage);
    await capture('slash-390-path');
    await page.keyboard.press('Enter');
    await expect(sent(pathMessage)).toBeVisible();
    await expect(sessions).toHaveCount(before + 1);

    // An existing Thread can also send an unmatched one-component path with the send button.
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(editor).toBeEditable();
    await editor.fill('/home');
    await expectPlainDraft('/home');
    await send.click();
    await expect(sent('/home')).toBeVisible();
    await expect(sessions).toHaveCount(before + 1);
    assert.deepEqual(requests, []);
    assert.deepEqual(errors, []);
    console.log('Slash menu hides and reopens while editing; paths send from new and existing Threads.');
  } finally {
    await browser.close();
  }
}

check().catch((error) => { console.error(error); process.exitCode = 1; });
