import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { CASE_NODE_SCRIPT, runCommand } from '../docker-case.mjs'

test('case inspector accepts absolute paths only when they stay inside the disposable case', async () => {
  const base = mkdtempSync(path.join(tmpdir(), 'kk-studio-reliability-case-'))
  const root = path.join(base, 'case')
  const outsideRoot = path.join(base, 'outside')
  try {
    mkdirSync(root)
    mkdirSync(outsideRoot)
    const file = path.join(root, 'proof.txt')
    writeFileSync(file, 'proof', 'utf8')

    const inside = await inspect(root, file)
    assert.equal(inside.code, 0)
    assert.deepEqual(JSON.parse(inside.stdout), {
      files: [
        {
          path: file,
          exists: true,
          byteLength: 5,
          sha256: 'c1cda26362828b69266512052b97cb3729e3b052e4ade47c0a1e3383defe73c7',
          counts: {},
        },
      ],
    })

    const dotDotNamedFile = path.join(root, '..proof.txt')
    writeFileSync(dotDotNamedFile, 'proof', 'utf8')
    assert.equal((await inspect(root, dotDotNamedFile)).code, 0)

    const outside = await inspect(root, path.join(root, '..', 'outside.txt'))
    assert.notEqual(outside.code, 0)
    assert.match(outside.stderr, /path escapes case root/)

    writeFileSync(path.join(outsideRoot, 'secret.txt'), 'secret', 'utf8')
    symlinkSync(outsideRoot, path.join(root, 'outside-link'), 'dir')
    const symlinkEscape = await inspect(root, path.join(root, 'outside-link', 'secret.txt'))
    assert.notEqual(symlinkEscape.code, 0)
    assert.match(symlinkEscape.stderr, /path escapes case root/)
  } finally {
    rmSync(base, { recursive: true, force: true })
  }
})

function inspect(root, requestedPath) {
  return runCommand(
    process.execPath,
    ['--input-type=module', '-e', CASE_NODE_SCRIPT],
    {
      cwd: root,
      input: JSON.stringify({
        op: 'inspect',
        files: [{ path: requestedPath, needles: [] }],
      }),
    },
  )
}
