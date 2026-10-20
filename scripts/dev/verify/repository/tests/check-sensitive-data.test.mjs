/** Unit tests for the repository sensitive-data gate. */

import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { accessSync, constants, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { repositoryRoot } from '../../../lib/repo-root.mjs'

const REPOSITORY_ROOT = repositoryRoot()
const SCANNER = path.join(
  REPOSITORY_ROOT,
  'scripts/dev/verify/repository/check-sensitive-data.mjs',
)
const { formatFinding, scanRepository, scanText } = await import(SCANNER)

const BS = '\\'

/** Build positive samples at runtime so the clean-tree test has no fixtures. */
function sampleValues() {
  return {
    'private-key-open-ssh': ['private-key', '-----BEGIN ' + 'OPENSSH PRIVATE KEY-----'],
    'private-key-pgp': ['private-key', '-----BEGIN ' + 'PGP PRIVATE KEY BLOCK-----'],
    'aws-access-key': ['aws-access-key', 'AKIA' + 'IOSFODNN7EXAMPLE'],
    'google-api-key': ['google-api-key', 'AIza' + 'a'.repeat(35)],
    'gitlab-token': ['gitlab-token', 'glpat-' + 'a'.repeat(20)],
    'github-token': ['github-token', 'ghp_' + 'a'.repeat(36)],
    'slack-token': ['slack-token', 'xoxb-' + '1234567890ABCDEFGHIJ'],
    'stripe-live-token': ['stripe-live-token', 'sk_live_' + 'a'.repeat(24)],
    jwt: ['jwt', 'eyJ' + 'a'.repeat(8) + '.' + 'b'.repeat(8) + '.' + 'c'.repeat(8)],
    'slack-webhook': [
      'slack-webhook',
      'https://hooks.' + 'slack.com/services/T00000000/B00000000/abcdefghijklmnop',
    ],
    'discord-webhook': [
      'discord-webhook',
      'https://discord.com/api/webhooks/' + '123456789012345678/' + 'abcdefghijklmnop',
    ],
    'telegram-webhook': [
      'telegram-webhook',
      'https://api.' + 'telegram.org/bot123456789:abcdefghijklmnopqrst/sendMessage',
    ],
    'dingtalk-webhook': [
      'dingtalk-webhook',
      'https://oapi.dingtalk.com/robot/send?access_token=' + 'a'.repeat(32),
    ],
    'feishu-webhook': [
      'feishu-webhook',
      'https://open.feishu.cn/open-apis/bot/v2/hook/' + 'a'.repeat(32),
    ],
    'lark-webhook': [
      'feishu-webhook',
      'https://open.larksuite.com/open-apis/bot/v2/hook/' + 'a'.repeat(32),
    ],
    'wecom-webhook': [
      'wecom-webhook',
      'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=' + 'a'.repeat(32),
    ],
    'private-opencli-host': ['private-environment', 'vps-' + 'opencli-hub'],
    'private-maven-host': ['private-environment', 'mvn.' + 'hz.' + 'netease.com'],
    'unix-personal-path': ['personal-path', '/home/' + 'alice/project'],
    'mac-personal-path': ['personal-path', '/Users/' + 'alice/project'],
    'windows-personal-path': ['personal-path', 'C:' + BS + 'Users' + BS + 'alice/project'],
    'windows-escaped-personal-path': [
      'personal-path',
      'C:' + BS + BS + 'Users' + BS + BS + 'alice/project',
    ],
    'wsl-mount-personal-path': ['personal-path', '/mnt/' + 'c/Users/' + 'alice/project'],
    'wsl-unc-personal-path': [
      'personal-path',
      BS + BS + 'wsl.localhost' + BS + 'Ubuntu' + BS + 'home' + BS + 'alice',
    ],
    'wsl-unc-escaped-personal-path': [
      'personal-path',
      BS + BS + 'wsl.localhost' + BS + BS + 'Ubuntu' + BS + BS + 'home' + BS + BS + 'alice',
    ],
  }
}

test('test intent: each supported high-confidence shape yields only a redacted location', () => {
  for (const [sampleName, [ruleName, value]] of Object.entries(sampleValues())) {
    const findings = scanText(`fixture=${value}`, 'fixture.txt')
    assert.deepEqual(
      findings.map((finding) => finding.rule),
      [ruleName],
      sampleName,
    )
    const output = findings.map(formatFinding).join('\n')
    assert.ok(output.includes(`${ruleName} fixture.txt:1`), sampleName)
    assert.ok(!output.includes(value), sampleName)
  }
})

test('test intent: known synthetic and container paths stay quiet while a user path is blocked', () => {
  const allowedPaths = []
  for (const user of ['dev', 'test', 'user', 'kkdaemon']) {
    allowedPaths.push(
      '/home/' + user,
      '/Users/' + user,
      'C:' + BS + 'Users' + BS + user,
      '/mnt/' + 'c/Users/' + user,
      BS + BS + 'wsl.localhost' + BS + 'Ubuntu' + BS + 'home' + BS + user,
    )
  }
  assert.deepEqual(scanText(allowedPaths.join('\n'), 'paths.txt'), [])

  for (const blocked of [
    '/home/' + 'alice',
    '/Users/' + 'alice',
    'C:' + BS + 'Users' + BS + 'alice',
    '/mnt/' + 'c/Users/' + 'alice',
    BS + BS + 'wsl.localhost' + BS + 'Ubuntu' + BS + 'home' + BS + 'alice',
  ]) {
    const findings = scanText('root="' + blocked + '"', 'paths.txt')
    assert.deepEqual(
      findings.map((finding) => finding.rule),
      ['personal-path'],
      blocked,
    )
  }
})

test('test intent: scanner source does not match its own rules', () => {
  assert.deepEqual(scanText(readFileSync(SCANNER, 'utf8'), 'scanner.mjs'), [])
})

test('test intent: CLI returns nonzero and redacts a detected value', () => {
  const value = 'AKIA' + 'IOSFODNN7EXAMPLE'
  const root = mkdtempSync(path.join(tmpdir(), 'kk-sensitive-data-'))
  try {
    assert.equal(spawnSync('git', ['init', '--quiet'], { cwd: root }).status, 0)
    writeFileSync(path.join(root, 'fixture.txt'), `key=${value}\n`)

    const completed = spawnSync(process.execPath, [SCANNER, '--root', root], {
      encoding: 'utf8',
    })

    assert.notEqual(completed.status, 0)
    assert.ok(completed.stdout.split('\n').includes('aws-access-key fixture.txt:1'))
    assert.ok(!completed.stdout.includes(value))
    assert.ok(!completed.stderr.includes(value))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('test intent: the committed and development-visible tree passes its own gate', () => {
  accessSync(SCANNER, constants.X_OK)
  assert.deepEqual(scanRepository(REPOSITORY_ROOT), [])
})
