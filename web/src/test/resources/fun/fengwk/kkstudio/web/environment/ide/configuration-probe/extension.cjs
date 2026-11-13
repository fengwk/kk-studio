// Workbench configuration probe for the production IDE acceptance.
//
// It runs inside the real managed code-server extension host and reports the registered default,
// the explicit user value and the effective value of every setting the bundled bridge contributes.
// It also activates the public built-in Git extension and reports the repositories it discovered, so
// the acceptance proves that the bridge defaults neither override user settings nor disable Git.
// The snapshot is rewritten atomically whenever the observed configuration changes, which lets the
// acceptance observe a later explicit user value without restarting the managed code-server.

const vscode = require('vscode')
const fs = require('node:fs')
const path = require('node:path')

const SETTINGS = [
  'workbench.colorTheme',
  'window.density.editorTabHeight',
  'chat.disableAIFeatures',
  'extensions.ignoreRecommendations',
  'files.watcherExclude',
  'task.autoDetect',
  'npm.autoDetect',
  'npm.fetchOnlinePackageInfo',
  'typescript.disableAutomaticTypeAcquisition',
  'git.autoRepositoryDetection',
]

const pollIntervalMs = 500
const pollBudgetMs = 300000

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function waitFor(predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (!(await predicate())) {
    if (Date.now() > deadline) {
      return false
    }
    await delay(100)
  }
  return true
}

/** Activates the public built-in Git extension and collects the repositories it discovered. */
async function inspectGit() {
  const extension = vscode.extensions.getExtension('vscode.git')
  if (!extension) {
    return { present: false, discovered: false, repositories: [] }
  }
  const api = await extension.activate()
  const exported = api && typeof api.getAPI === 'function' ? api.getAPI(1) : api
  const discovered = await waitFor(() => exported.repositories.length > 0, 30000)
  return {
    present: true,
    discovered,
    repositories: exported.repositories.map((repository) => repository.rootUri.fsPath),
  }
}

exports.activate = async () => {
  const folder = (vscode.workspace.workspaceFolders || [])[0]
  if (!folder) {
    return
  }
  const config = vscode.workspace.getConfiguration()
  // Git registers its configuration when its extension activates; wait for the real registration.
  const registered = await waitFor(
    () => config.inspect('git.autoRepositoryDetection')?.defaultValue !== undefined,
    30000,
  )
  if (!registered) {
    return
  }
  let git = null
  let published = null
  let stopped = false
  const publish = async () => {
    if (git === null) {
      git = await inspectGit()
    }
    const snapshots = {}
    for (const key of SETTINGS) {
      const inspection = config.inspect(key)
      snapshots[key] = inspection
        ? {
            defaultValue: inspection.defaultValue,
            globalValue: inspection.globalValue,
            effective: config.get(key),
          }
        : null
    }
    const payload = JSON.stringify({ snapshots, git }, null, 2)
    if (payload === published) {
      return
    }
    published = payload
    const target = path.join(folder.uri.fsPath, 'config-probe.json')
    const staging = `${target}.tmp`
    fs.writeFileSync(staging, payload)
    fs.renameSync(staging, target)
  }
  const subscription = vscode.workspace.onDidChangeConfiguration(() => void publish())
  await publish()
  void (async () => {
    const deadline = Date.now() + pollBudgetMs
    while (!stopped && Date.now() < deadline) {
      await delay(pollIntervalMs)
      if (stopped) {
        return
      }
      await publish()
    }
  })()
  exports.deactivate = () => {
    stopped = true
    subscription.dispose()
  }
}
