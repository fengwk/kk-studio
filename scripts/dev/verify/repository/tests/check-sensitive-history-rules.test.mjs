/** Rule-level tests for the full-history sensitive-data scanner. */

import assert from 'node:assert/strict'
import { accessSync, constants, readFileSync } from 'node:fs'
import test from 'node:test'

import { RULES as BASE_RULES, scanRepository } from '../check-sensitive-data.mjs'
import * as history from '../check-sensitive-history.mjs'
import { HISTORY_SCANNER, REPOSITORY_ROOT, render, sampleToken } from './support/history-fixture.mjs'

const BS = '\\'
const ruleNames = (findings) => findings.map((finding) => finding.rule)

test('test intent: history mode reuses every current-tree rule and is never weaker than the CI gate', () => {
  const allRules = new Set(history.ALL_RULES.map(([rule]) => rule))
  assert.ok(BASE_RULES.every(([rule]) => allRules.has(rule)))
})

test('test intent: every rule has a prefilter so a new rule cannot run unguarded', () => {
  for (const [rule] of history.ALL_RULES) {
    assert.ok(rule in history.PREFILTERS, rule)
  }
})

test('test intent: each supported shape yields only a redacted location', () => {
  const samples = {
    token: [sampleToken(), 'aws-access-key'],
    'private-key': ['-----BEGIN ' + 'OPENSSH PRIVATE KEY-----', 'private-key'],
    'credential-url': ['https://user' + ':' + 'verysecretvalue123' + '@api.vendor-corp.net/v1', 'credential-url'],
    'generic-secret': ['api_key=' + 'abcdef0123456789abcdef', 'generic-secret'],
    'personal-path': ['/home/' + 'alice/work', 'personal-path'],
  }
  for (const [name, [value, rule]] of Object.entries(samples)) {
    const findings = history.findingsInText(`x=${value}`, 'fixture.txt')
    assert.ok(ruleNames(findings).includes(rule), name)
    assert.ok(!render(findings).includes(value), name)
  }
})

test('test intent: only fixture personal-path users are allowlisted', () => {
  const quiet = ['/home/' + 'dev', '/Users/' + 'kkdaemon'].join('\n')
  assert.deepEqual(history.findingsInText(quiet, 'fixture.txt'), [])
})

test('test intent: reserved-host credentials stay candidates and only gain a hint', () => {
  const samples = [
    'https://user' + ':' + 'password' + '@example.com/mcp',
    'https://user' + ':' + 'pw' + '@127.0.0.1:15432/db',
  ]
  for (const sample of samples) {
    const groups = new history.ValueGroups()
    const findings = history.findingsInText(sample, 'fixture.txt', '', '', groups)
    assert.ok(ruleNames(findings).includes('credential-url'))
    const rendered = groups.render()
    assert.deepEqual(rendered.map((group) => group.rule), ['credential-url'])
    assert.deepEqual(rendered.map((group) => group.status), [history.STATUS_PENDING])
    assert.ok(rendered[0].hint.includes('reserved-host'))
    assert.ok(!render(findings).includes(sample))
  }
})

test('test intent: quoted and camelCase keys are matched', () => {
  // These are exactly the shapes that used to escape the generic rule.
  const samples = [
    '{"apiKey": ' + '"abcdef0123456789abcdef"}',
    "{'apiKey': " + "'abcdef0123456789abcdef'}",
    'sensitiveKey = ' + '"abcdef0123456789abcdef";',
    '{"clientSecret":"abcdef0123456789abcdef"}',
    'accessToken=' + 'abcdef0123456789abcdef',
    'refreshToken: ' + 'abcdef0123456789abcdef',
  ]
  for (const sample of samples) {
    const findings = history.findingsInText(sample, 'fixture.txt')
    assert.ok(ruleNames(findings).includes('generic-secret'), sample)
    assert.ok(!render(findings).includes('abcdef0123456789abcdef'))
  }
})

test('test intent: the key separator never spans a line break', () => {
  const crossLine = 'MY_API_KEY=\n  internalSchemaIdentifier9999\n'
  assert.deepEqual(ruleNames(history.findingsInText(crossLine, 'fixture.txt')), [])
  const assignment = 'MY_API_KEY=internalSchemaIdentifier9999\n'
  assert.ok(ruleNames(history.findingsInText(assignment, 'fixture.txt')).includes('generic-secret'))
})

test('test intent: the generic-secret group hint names the key, never the value', () => {
  const groups = new history.ValueGroups()
  history.findingsInText('sensitiveKey = ' + '"abcdef0123456789abcdef";', 'fixture.txt', '', '', groups)
  const rendered = groups.render()
  assert.ok(rendered[0].hint.includes('key-name:sensitivekey'))
  assert.ok(!JSON.stringify(rendered).includes('abcdef0123456789abcdef'))
})

test('test intent: overlapping locator matches are masked as a union', () => {
  const path = 'aaaa' + 'SECRET' + 'token' + '9999'
  const original = [...history.ALL_RULES]
  history.LOCATOR_CACHE.clear()
  history.ALL_RULES.splice(0, history.ALL_RULES.length, ['synthetic-a', /SECRETtoken/g], ['synthetic-b', /token9999/g])
  let locator
  try {
    locator = history.redactedLocator(path)
  } finally {
    history.ALL_RULES.splice(0, history.ALL_RULES.length, ...original)
    history.LOCATOR_CACHE.clear()
  }
  assert.ok(!locator.includes('SECRETtoken'))
  assert.ok(!locator.includes('token9999'))
  assert.ok(!locator.includes('9999'))
  assert.ok(locator.includes('<redacted:synthetic-a+synthetic-b>'))
})

test('test intent: Windows and WSL personal paths are covered, not only /home', () => {
  for (const path of ['C:' + BS + 'Users' + BS + 'alice', '/mnt/' + 'c/Users/' + 'alice']) {
    const findings = history.findingsInText(`root="${path}"`, 'fixture.txt')
    assert.deepEqual(ruleNames(findings), ['personal-path'], path)
  }
})

test('test intent: scanner source does not match its own rules', () => {
  assert.deepEqual(history.findingsInText(readFileSync(HISTORY_SCANNER, 'utf8'), 'scanner.mjs'), [])
})

test('test intent: value groups keep a hash and locations only', () => {
  const groups = new history.ValueGroups()
  const value = 'api_key=' + 'abcdef0123456789abcdef'
  history.findingsInText(value, 'fixture.txt', '', '', groups)
  const rendered = groups.render()
  assert.equal(rendered.length, 1)
  assert.equal(rendered[0].rule, 'generic-secret')
  assert.equal(rendered[0].status, history.STATUS_PENDING)
  assert.equal(rendered[0].value_sha256_12.length, 12)
  assert.ok(!JSON.stringify(rendered).includes(value))
})

test('test intent: a personal path is never graded as a credential', () => {
  const groups = new history.ValueGroups()
  history.findingsInText('/home/' + 'alice/work', 'fixture.txt', '', '', groups)
  assert.deepEqual(groups.render().map((group) => group.status), [history.STATUS_PERSONAL_DATA])
})

test('test intent: a filename that itself contains a secret is masked', () => {
  const locator = history.redactedLocator(sampleToken() + '.txt')
  assert.ok(!locator.includes(sampleToken()))
  assert.ok(locator.includes('<redacted:aws-access-key>'))
})

test('test intent: the audit entry only reads and the shared CI gate it reuses is intact', () => {
  accessSync(HISTORY_SCANNER, constants.X_OK)
  assert.deepEqual(scanRepository(REPOSITORY_ROOT), [])
})
