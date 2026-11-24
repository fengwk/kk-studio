const assert = require('node:assert/strict');
const path = require('node:path');
const { createRequire } = require('node:module');
const { pathToFileURL } = require('node:url');
const requireFrontend = createRequire(path.resolve(__dirname, '../../frontend/package.json'));
const { chromium, expect } = requireFrontend('@playwright/test');

const url = process.argv.includes('--live') ? 'http://127.0.0.1:5176/'
  : pathToFileURL(path.resolve(__dirname, '../../reports/workspace-prototype/kk-studio-workspace.html')).href;

async function point(row, offset = 0.5) {
  const { x, y, width, height } = await row.boundingBox();
  return { x: Math.round(x + width / 2), y: Math.round(y + height * offset) };
}

function directory(page) {
  const tree = page.getByRole('tree', { name: '资源目录' });
  const row = (name) => tree.getByRole('treeitem').filter({ has: page.getByText(name, { exact: true }) });
  const key = (name) => row(name).getAttribute('data-key');
  const keys = () => tree.locator('.x-row').evaluateAll((rows) => rows.map((element) => element.dataset.key));
  return { tree, row, key, keys };
}

async function checkTouch(browser) {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, reducedMotion: 'reduce' });
  const session = await page.context().newCDPSession(page);
  const send = (type, ...points) => session.send('Input.dispatchTouchEvent', { type, touchPoints: points });
  const { tree, row, key, keys } = directory(page);
  await page.goto(url);
  await page.getByRole('button', { name: '打开侧边栏' }).tap();
  const release = '整理 1.3.0 发布说明';
  const before = await keys();

  // Native scrolling must win as soon as a finger moves, without moving a resource or activating a row.
  const origin = await point(row('调研'));
  await send('touchStart', origin);
  for (let dy = 28; dy <= 168; dy += 28) await send('touchMove', { x: origin.x, y: origin.y - dy });
  await send('touchEnd');
  await expect.poll(() => tree.evaluate((element) => element.scrollTop)).toBeGreaterThan(50);
  await expect(tree.locator('.x-dragging')).toHaveCount(0);
  assert.deepEqual(await keys(), before);
  await expect(page.getByRole('dialog', { name: '打开到窗格' })).toHaveCount(0);
  await expect(page.getByRole('menu')).toBeHidden();
  await row('默认').scrollIntoViewIfNeeded();
  const offsetBeforeHold = await tree.evaluate((element) => element.scrollTop);
  const treeBox = await tree.boundingBox();
  const releasePoint = await point(row(release));
  await send('touchStart', releasePoint);
  await expect(row(release)).toHaveClass(/x-dragging/);
  await send('touchMove', { x: releasePoint.x, y: Math.floor(treeBox.y + treeBox.height - 6) });
  await expect.poll(() => tree.evaluate((element) => element.scrollTop)).toBeGreaterThan(offsetBeforeHold + 50);
  await send('touchCancel');
  await expect(tree.locator('.x-dragging')).toHaveCount(0);
  assert.deepEqual(await keys(), before);
  await row('默认').scrollIntoViewIfNeeded();

  async function drag(from, to, offset = 0.5, expectedClass = 'into') {
    const start = await point(row(from));
    const end = await point(row(to), offset);
    await send('touchStart', start);
    await expect(row(from)).toHaveClass(/x-dragging/);
    await send('touchMove', end);
    await expect(row(to)).toHaveClass(new RegExp(`x-drop-${expectedClass}`));
    await send('touchEnd');
    await expect(tree.locator('.x-dragging')).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: '打开到窗格' })).toHaveCount(0);
    await expect(page.getByRole('menu')).toBeHidden();
  }

  await drag(release, '调研');
  const order = await keys();
  assert.equal(order[order.indexOf(await key('调研')) + 1], await key(release));
  await row('Prompt cache 命中率').tap();
  await drag('默认', '调研');
  await expect(row('默认')).toHaveAttribute('aria-level', '2');
  await row('默认').scrollIntoViewIfNeeded();
  await drag('默认', '调研', 0.12, 'before');
  await expect(row('默认')).toHaveAttribute('aria-level', '1');
  await page.close();
}

async function checkMouse(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(url);
  const { tree, row, keys, key } = directory(page);
  const canvas = row('1.3.0 工作流');
  await canvas.dragTo(row('默认'));
  const order = await keys();
  assert.equal(order[order.indexOf(await key('默认')) + 1], await key('1.3.0 工作流'));
  for (const name of ['默认', '1.3.0 工作流']) {
    await row(name).click({ button: 'right' });
    await expect(page.getByRole('menu')).toBeVisible();
    await expect(page.getByRole('menuitem', { name: /上移|下移|移动到/ })).toHaveCount(0);
    await page.keyboard.press('Escape');
  }
  await expect(tree.locator('.x-dragging')).toHaveCount(0);
  await page.close();
}

async function run() {
  const browser = await chromium.launch();
  try {
    await checkTouch(browser);
    await checkMouse(browser);
    console.log('Touch scrolling, hold-to-drag, folder reparenting, native mouse drag and menu actions passed.');
  } finally { await browser.close(); }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
