import { readFile } from 'node:fs/promises'
import { expect, it } from 'vitest'

it('contributes only lightweight native defaults without disabling Git or bridge activation', async () => {
  // Manifest contributions are consumed by VS Code, not imperative settings writes at activation.
  const manifest = JSON.parse(await readFile(new URL('../../ide-extension/package.json', import.meta.url), 'utf8'))
  expect(manifest.contributes.configurationDefaults).toEqual({
    'task.autoDetect': 'off',
    'npm.autoDetect': 'off',
    'npm.fetchOnlinePackageInfo': false,
    'typescript.disableAutomaticTypeAcquisition': true,
  })
  expect(manifest.activationEvents).toEqual(['*'])
  expect(manifest.main).toBe('./extension.cjs')
  expect(manifest.contributes.commands.map((command: { command: string }) => command.command)).toEqual([
    'kkStudio.changeDirectory', 'kkStudio.focusTerminal', 'kkStudio.closeView',
  ])
})
