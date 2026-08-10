import assert from 'node:assert/strict'
import test from 'node:test'

import { formatCaseList, parseArgs, selectCases } from '../cli.mjs'
import {
  CASES,
  MODELS,
  VARIANT,
  WRITE_PROOF,
  buildSystemPrompt,
  buildUserPrompt,
} from '../matrix.mjs'

test('CLI keeps the frozen eight-case MiniMax-only matrix', () => {
  assert.equal(CASES.length, 8)
  assert.deepEqual(
    CASES.map((testCase) => testCase.id),
    [
      'm27-pi-investigate',
      'm27-pi-base-investigate',
      'm27-pi-repair',
      'm27-pi-base-repair',
      'm3-pi-investigate',
      'm3-pi-base-investigate',
      'm3-pi-repair',
      'm3-pi-base-repair',
    ],
  )
  assert.deepEqual(
    MODELS.map((model) => model.ref),
    ['minimax/MiniMax-M2.7', 'minimax/MiniMax-M3'],
  )
  assert.ok(CASES.every((testCase) => testCase.model.variant === VARIANT))
  assert.match(formatCaseList(), /m27-pi-investigate/)
  assert.match(formatCaseList(), /minimax\/MiniMax-M3/)
})

test('CLI parses repeated --only and runner options deterministically', () => {
  const args = parseArgs(
    [
      '--only',
      'm3-pi-repair',
      '--only',
      'm27-pi-investigate',
      '--only',
      'm3-pi-repair',
      '--base-url',
      'http://127.0.0.1:19000/',
      '--daemon-env',
      'docker-reliability',
      '--report-root',
      'tmp/reports',
      '--max-cost-usd',
      '2.5',
    ],
    { cwd: '/repo' },
  )
  assert.deepEqual(args.only, ['m3-pi-repair', 'm27-pi-investigate'])
  assert.equal(args.baseUrl, 'http://127.0.0.1:19000')
  assert.equal(args.reportRoot, '/repo/tmp/reports')
  assert.equal(args.maxCostUsd, 2.5)
  assert.deepEqual(
    selectCases(args).map((testCase) => testCase.id),
    ['m27-pi-investigate', 'm3-pi-repair'],
  )
})

test('CLI rejects model override, unknown cases, and cost above the USD 5 hard cap', () => {
  assert.throws(() => parseArgs(['--model', 'openai/gpt-x']), /unknown argument/)
  assert.throws(() => parseArgs(['--only', 'not-a-case']), /unknown case id/)
  assert.throws(() => parseArgs(['--max-cost-usd', '5.01']), /between 0 and 5/)
})

test('all cases, models, and dates share one byte-identical stable System Prompt', () => {
  const prompt = buildSystemPrompt()
  assert.equal(prompt, buildSystemPrompt())
  assert.doesNotMatch(prompt, /\bdate\b|\d{4}-\d{2}-\d{2}/i)
  assert.doesNotMatch(
    prompt,
    /casePath|\/workspace\/cases|status=|ready=|working_directory|current_time|time_zone/i,
  )
  assert.match(prompt, /\/workspace\/anchors/)
})

test('repair USER message carries the exact proof bytes without an extra separator newline', () => {
  const repairCase = CASES.find((testCase) => testCase.id === 'm27-pi-repair')
  const prompt = buildUserPrompt(repairCase)
  const start = prompt.indexOf('BEGIN_WRITE_PROOF\n') + 'BEGIN_WRITE_PROOF\n'.length
  const end = prompt.indexOf('END_WRITE_PROOF', start)
  assert.equal(prompt.slice(start, end), WRITE_PROOF)
})
