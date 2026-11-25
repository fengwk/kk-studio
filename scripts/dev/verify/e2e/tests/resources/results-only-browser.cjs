const { writeFileSync } = require('node:fs')

function newPage() {
  const handlers = new Map()
  let url
  let echo
  const waitFor = async () => {}
  const button = { waitFor, click: async () => {}, last: () => button }
  const createButton = {
    ...button,
    evaluate: async () => ({ first: true, card: true, visible: true, contained: true }),
  }
  const card = {
    locator: () => ({ allInnerTexts: async () => ['Install', 'Uninstall', 'Edit', 'Manage'] }),
    getByRole: () => button,
  }
  const manageDialog = {
    waitFor,
    locator: () => ({ allInnerTexts: async () => ['Update Daemon', 'Rotate Token', 'Delete'] }),
    getByRole: () => button,
  }
  return {
    on: (event, handler) => handlers.set(event, handler),
    async goto(target) {
      url = target
      if (new URL(target).pathname === '/settings/environments') {
        const response = await fetch(new URL('/mock-provider/responses', target))
        echo = await response.text()
        handlers.get('pageerror')(new Error(echo))
        handlers.get('console')({ type: () => 'error', text: () => `TypeError: ${echo}` })
      }
      return { ok: () => true, status: () => 200 }
    },
    waitForFunction: waitFor,
    waitForTimeout: waitFor,
    locator(selector) {
      if (selector === 'main.account-gate form.sign-in-form') return { count: async () => 0 }
      if (selector === '.resource-grid') return { waitFor }
      if (selector === '.environment-card') return { count: async () => 1, first: () => card }
      if (selector === 'body') return { innerText: async () => echo }
      throw new Error(`Unexpected fixture selector: ${selector}`)
    },
    getByRole: (role) => role === 'dialog' ? manageDialog : createButton,
    title: async () => 'Fixture',
    url: () => url,
    screenshot: async ({ path }) => writeFileSync(path, echo),
  }
}

exports.chromium = {
  launch: async () => ({
    newContext: async () => ({ newPage: async () => newPage() }),
    close: async () => {},
  }),
}
