import { registerHooks } from 'node:module'

// Replace only the Playwright driver. The actual UI runner, HTTP requests and report writer stay in use.
registerHooks({
  resolve(specifier, context, nextResolve) {
    return specifier === '@playwright/test'
      ? { url: new URL('./results-only-browser.cjs', import.meta.url).href, shortCircuit: true }
      : nextResolve(specifier, context)
  },
})
