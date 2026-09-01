#!/usr/bin/env node

import { randomUUID } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { parseArgs, formatCaseList, selectCases, usage } from './cli.mjs'
import { DockerCaseHarness, summarizeCommand } from './docker-case.mjs'
import {
  AGENT_TOOL_IDS,
  CASE_TIMEOUT_MS,
  ENVIRONMENT_CAPABILITY_IDS,
  MODELS,
  MODEL_TOOL_NAMES,
  VARIANT,
  WRITE_PROOF_META,
  WRITE_PROOF_PATH,
  buildSystemPrompt,
  buildUserPrompt,
  casePromptSummary,
  sha256,
} from './matrix.mjs'
import {
  ReliabilityError,
  aggregateUsage,
  assertStatusAllowlist,
  extractDurableTrace,
  validateAnswerOracle,
  validateToolPolicy,
} from './policy.mjs'
import {
  createRunDirectory,
  publishLatest,
  writeCaseArtifacts,
  writeCaseResult,
  writeSummaryAndReport,
} from './report.mjs'
import { assert, envelopeData, httpJson, pageResults, cid } from '../e2e/lib/http.mjs'
import {
  branchSettingsOf,
  chatOwner,
  createChat,
  getThreadSnapshot,
  listEnvironments,
  createNewSession,
  stopThread,
  userMessageCommand,
  waitForQuiescentThread,
} from '../e2e/lib/harness.mjs'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const REPO_ROOT = path.resolve(__dirname, '../..')

export async function main(argv = process.argv.slice(2)) {
  let args
  try {
    args = parseArgs(argv, { cwd: REPO_ROOT })
  } catch (error) {
    console.error(`ERROR: ${error.message}`)
    console.error(usage())
    return 2
  }
  if (args.help) {
    console.log(usage())
    return 0
  }
  if (args.list) {
    console.log(formatCaseList(selectCases(args)))
    return 0
  }

  const selectedCases = selectCases(args)
  const startedAt = new Date().toISOString()
  const runId = createRunId()
  const runDir = createRunDirectory(args.reportRoot, runId)
  const systemPrompt = buildSystemPrompt()
  const systemPromptMeta = {
    byteLength: Buffer.byteLength(systemPrompt),
    sha256: sha256(systemPrompt),
  }
  const ctx = {
    baseUrl: args.baseUrl,
    call(method, requestPath, body, timeoutMs) {
      return httpJson(args.baseUrl, method, requestPath, body, timeoutMs)
    },
  }
  const docker = new DockerCaseHarness({ repoRoot: REPO_ROOT })
  const results = []
  const agents = new Map()
  let preflight = null
  let runError = null

  try {
    preflight = await validatePreflight(ctx, args.daemonEnv)
    const selectedModelRefs = new Set(selectedCases.map((testCase) => testCase.model.ref))
    for (const model of MODELS.filter((candidate) => selectedModelRefs.has(candidate.ref))) {
      const agent = await createTemporaryAgent(ctx, model, systemPrompt, runId)
      agents.set(model.ref, agent)
    }

    let incurredCost = 0
    let costAccountingBlocked = false
    for (const testCase of selectedCases) {
      if (costAccountingBlocked || incurredCost >= args.maxCostUsd) {
        const reason = costAccountingBlocked
          ? 'prior started case has no trustworthy durable cost metadata'
          : 'cost cap reached before case start'
        const skipped = skippedResult(testCase, 'cost-cap', reason)
        results.push(skipped)
        writeCaseResult(runDir, skipped)
        continue
      }
      const result = await executeCase({
        ctx,
        docker,
        runDir,
        testCase,
        agent: agents.get(testCase.model.ref),
        daemonEnv: args.daemonEnv,
      })
      results.push(result)
      incurredCost += result.metrics.costTotal
      if (result.turnStarted && !result.costKnown) costAccountingBlocked = true
      writeCaseResult(runDir, result)
    }
  } catch (error) {
    runError = error
    const completed = new Set(results.map((result) => result.id))
    for (const testCase of selectedCases) {
      if (completed.has(testCase.id)) continue
      const skipped = skippedResult(testCase, 'setup', `run setup failed: ${error.message}`)
      results.push(skipped)
      writeCaseResult(runDir, skipped)
    }
  } finally {
    for (const agent of [...agents.values()].reverse()) {
      try {
        await deleteAgent(ctx, agent)
      } catch (error) {
        runError ??= error
      }
    }
  }

  const finishedAt = new Date().toISOString()
  const summary = writeSummaryAndReport({
    runDir,
    runId,
    selectedCases,
    results,
    startedAt,
    finishedAt,
    args,
    preflight,
    runError,
    systemPromptMeta,
  })
  publishLatest(args.reportRoot, runDir, runId)
  console.log(`Agent reliability report: ${path.join(runDir, 'report.md')}`)
  console.log(
    `Result: ${summary.status.toUpperCase()} pass=${summary.totals.pass} fail=${summary.totals.fail} skip=${summary.totals.skip} costUsd=${summary.usage.costTotal.toFixed(6)}`,
  )
  return summary.status === 'pass' ? 0 : 1
}

async function validatePreflight(ctx, daemonEnv) {
  const [providersResponse, modelsResponse, toolsResponse, environments] = await Promise.all([
    ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100'),
    ctx.call('GET', '/api/ai/catalog/models?pageNumber=1&pageSize=100'),
    ctx.call('GET', '/api/ai/catalog/tools'),
    listEnvironments(ctx),
  ])
  const providers = pageResults(providersResponse.json)
  const models = pageResults(modelsResponse.json)
  const toolCatalog = envelopeData(toolsResponse.json)
  assert(
    Array.isArray(toolCatalog),
    `expected tool catalog array: ${JSON.stringify(toolsResponse.json)}`,
  )

  const provider = providers.find((candidate) => candidate.name === 'minimax')
  assert(provider, 'required provider is missing: minimax')
  assert(provider.configured === true, 'required provider is not configured: minimax')
  const checkedModels = []
  for (const expected of MODELS) {
    const model = models.find(
      (candidate) =>
        candidate.providerName === expected.providerName && candidate.name === expected.modelName,
    )
    assert(model, `required model is missing: ${expected.ref}`)
    assert(model.config?.abilities?.tools === true, `model lacks tools ability: ${expected.ref}`)
    assert(model.config?.abilities?.reasoning === true, `model lacks reasoning ability: ${expected.ref}`)
    assert(
      model.config?.variants?.some((variant) => variant.id === VARIANT),
      `model lacks required high variant: ${expected.ref}`,
    )
    checkedModels.push(expected.ref)
  }

  const environment = environments.find((candidate) => candidate.name === daemonEnv)
  assert(environment, `required Environment is missing: ${daemonEnv}`)
  assert(
    environment.ready === true && environment.status === 'READY',
    `required Environment is not READY: ${daemonEnv}`,
  )
  const environmentCapabilities = new Set(
    (environment.capabilities ?? []).map((capability) => capability.id),
  )
  const catalogById = new Map(toolCatalog.map((tool) => [tool.id, tool]))
  for (let index = 0; index < AGENT_TOOL_IDS.length; index += 1) {
    const agentToolId = AGENT_TOOL_IDS[index]
    const modelToolName = MODEL_TOOL_NAMES[index]
    const catalogEntry = catalogById.get(agentToolId)
    assert(
      catalogEntry?.name === modelToolName,
      `tool catalog mapping mismatch: ${agentToolId} != ${modelToolName}: ${JSON.stringify(catalogEntry)}`,
    )
    assert(
      environmentCapabilities.has(ENVIRONMENT_CAPABILITY_IDS[index]),
      `Environment lacks required capability: ${ENVIRONMENT_CAPABILITY_IDS[index]}`,
    )
  }
  return {
    provider: { name: 'minimax', configured: true },
    models: checkedModels,
    environment: { name: daemonEnv, status: 'READY', ready: true },
    agentToolIds: [...AGENT_TOOL_IDS],
    modelToolNames: [...MODEL_TOOL_NAMES],
  }
}

async function createTemporaryAgent(ctx, model, systemPrompt, runId) {
  const suffix = runId.replace(/[^a-z0-9]/g, '').slice(-12)
  const name = `reliability-${model.key}-${suffix}`
  const body = {
    name,
    description: 'Temporary Docker reliability matrix agent.',
    systemPrompt,
    model: model.ref,
    variant: VARIANT,
    config: { toolIds: [...AGENT_TOOL_IDS], skills: [], subagents: [] },
  }
  const { status, json } = await ctx.call('POST', '/api/ai/catalog/agents', body)
  assert(status === 201, `create Agent status ${status}`)
  const agent = envelopeData(json)
  assert(agent?.name === name, `created Agent identity mismatch: ${JSON.stringify(agent)}`)
  assert(agent.systemPrompt === systemPrompt, 'created Agent systemPrompt mismatch')
  assert(agent.model === model.ref && agent.variant === VARIANT, 'created Agent model mismatch')
  assert(
    JSON.stringify(agent.config) === JSON.stringify(body.config),
    `created Agent config mismatch: ${JSON.stringify(agent.config)}`,
  )
  return agent
}

async function deleteAgent(ctx, agent) {
  await ctx.call(
    'DELETE',
    `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
  )
}

async function executeCase({ ctx, docker, runDir, testCase, agent, daemonEnv }) {
  const startedAt = new Date().toISOString()
  const result = baseResult(testCase, startedAt)
  const artifacts = {}
  let chat = null
  let threadId = null
  let latestSnapshot = null
  let primaryError = null

  try {
    artifacts.precheck = await prepareCase(docker, testCase)
    result.tests.precheck =
      testCase.taskClass === 'repair' ? 'expected-fail-confirmed' : 'clean-read-only'

    chat = await createChat(ctx, {
      title: `reliability-${testCase.id}`,
      agentName: agent.name,
      yoloEnabled: true,
      environment: { name: daemonEnv, workspacePath: '.' },
    })
    const request = buildNewSessionRequest({
      chat,
      agent,
      testCase,
      daemonEnv,
      prompt: buildUserPrompt(testCase),
    })
    const accepted = await createNewSession(ctx, request)
    threadId = accepted.thread.threadId
    assertThreadSettings(accepted, testCase, daemonEnv, agent.name)
    result.turnStarted = true

    try {
      await waitForQuiescentThread(ctx, threadId, {
        timeoutMs: CASE_TIMEOUT_MS,
        intervalMs: 1000,
      })
      latestSnapshot = await getThreadSnapshot(ctx, threadId)
    } catch (error) {
      primaryError = new ReliabilityError('model', `model turn did not become quiescent: ${error.message}`)
      await bestEffortStop(ctx, threadId)
      latestSnapshot = await bestEffortSnapshot(ctx, threadId)
    }

    if (latestSnapshot) {
      try {
        const traceData = extractDurableTrace(latestSnapshot.entries)
        artifacts.trace = {
          caseId: testCase.id,
          model: testCase.model.ref,
          variant: VARIANT,
          prompt: casePromptSummary(testCase),
          events: traceData.trace,
          writeDiagnostics: traceData.writeDiagnostics,
          finalText: traceData.finalTextSummary,
          usage: traceData.usage,
        }
        result.metrics = traceData.usage
        result.costKnown = traceData.costKnown
        result.toolOrder = traceData.toolCalls.map((call) => call.toolName)
        result.toolCounts = countTools(result.toolOrder)

        if (!primaryError) {
          assertNoAssistantError(latestSnapshot.entries)
          validateToolPolicy(testCase, traceData)
          validateAnswerOracle(testCase, traceData.finalText)
        }
      } catch (error) {
        primaryError ??= normalizeError(error, 'oracle')
      }
    } else if (!primaryError) {
      primaryError = new ReliabilityError('model', 'no durable snapshot was available after the turn')
    }

    try {
      artifacts.postcheck = await postcheckCase(docker, testCase, artifacts.precheck)
      artifacts['git-diff-summary'] = artifacts.postcheck.gitDiff
      result.tests.postcheck = 'pass'
      result.tests.diffCheck = 'pass'
    } catch (error) {
      result.tests.postcheck = 'fail'
      if (error?.details?.gitDiff) artifacts['git-diff-summary'] = error.details.gitDiff
      primaryError ??= normalizeError(error, 'postcheck')
    }

    if (primaryError) throw primaryError
    result.status = 'pass'
  } catch (error) {
    const normalized = normalizeError(
      error,
      result.turnStarted ? 'model' : 'setup',
    )
    if (artifacts.precheck && !artifacts.postcheck) {
      try {
        artifacts.postcheck = await postcheckCase(docker, testCase, artifacts.precheck)
        artifacts['git-diff-summary'] = artifacts.postcheck.gitDiff
        result.tests.postcheck = 'pass'
        result.tests.diffCheck = 'pass'
      } catch (postcheckError) {
        result.tests.postcheck = 'fail'
        if (postcheckError?.details?.gitDiff) {
          artifacts['git-diff-summary'] = postcheckError.details.gitDiff
        }
      }
    }
    result.status = 'fail'
    result.failureCategory = normalized.category
    result.error = normalized.message
    result.errorDetails = normalized.details
  } finally {
    if (threadId && !latestSnapshot) {
      await bestEffortStop(ctx, threadId)
      latestSnapshot = await bestEffortSnapshot(ctx, threadId)
    }
    if (latestSnapshot && !artifacts.trace) {
      try {
        const traceData = extractDurableTrace(latestSnapshot.entries)
        artifacts.trace = {
          caseId: testCase.id,
          model: testCase.model.ref,
          variant: VARIANT,
          prompt: casePromptSummary(testCase),
          events: traceData.trace,
          writeDiagnostics: traceData.writeDiagnostics,
          finalText: traceData.finalTextSummary,
          usage: traceData.usage,
        }
        result.metrics = traceData.usage
        result.costKnown = traceData.costKnown
        result.toolOrder = traceData.toolCalls.map((call) => call.toolName)
        result.toolCounts = countTools(result.toolOrder)
      } catch {
        // Preserve the primary failure while still attempting cleanup.
      }
    }
    if (chat?.id) {
      try {
        await ctx.call(
          'DELETE',
          `/api/ai/chat/${encodeURIComponent(chat.id)}?expectedVersion=${encodeURIComponent(chat.version)}`,
        )
      } catch (error) {
        if (result.status !== 'fail') {
          result.status = 'fail'
          result.failureCategory = 'setup'
          result.error = `Chat cleanup failed: ${error.message}`
        }
      }
    }
  }

  result.finishedAt = new Date().toISOString()
  result.durationMs = new Date(result.finishedAt) - new Date(result.startedAt)
  writeCaseArtifacts(runDir, testCase.id, {
    'trace.json': artifacts.trace ?? emptyTrace(testCase),
    'precheck.json': artifacts.precheck ?? { status: 'not-completed' },
    'postcheck.json': artifacts.postcheck ?? { status: 'not-completed' },
    'git-diff-summary.json': artifacts['git-diff-summary'] ?? { status: 'not-collected' },
  })
  return result
}

async function prepareCase(docker, testCase) {
  const reset = summarizeCommand(await docker.reset(testCase))
  const initialStatusResult = await docker.gitStatus(testCase)
  assertCommandSuccess(initialStatusResult, 'initial git status')
  if (initialStatusResult.stdout.trim()) {
    throw new ReliabilityError('setup', `reset case is dirty: ${initialStatusResult.stdout}`)
  }
  const precheck = {
    reset,
    dependencyInstall: null,
    initialStatus: '',
    baselineSource: null,
    seededSource: null,
    targetTest: null,
  }
  if (testCase.taskClass !== 'repair') return precheck

  precheck.dependencyInstall = summarizeCommand(await docker.installDependencies(testCase))
  const afterDeps = await docker.gitStatus(testCase)
  assertCommandSuccess(afterDeps, 'post-dependency git status')
  if (afterDeps.stdout.trim()) {
    throw new ReliabilityError('setup', `dependency install dirtied case: ${afterDeps.stdout}`)
  }

  const baseline = await docker.inspectFiles(testCase, [
    {
      path: testCase.sourcePath,
      needles: [testCase.correctSnippet, testCase.defectSnippet],
    },
  ])
  const baselineSource = requiredFile(baseline, testCase.sourcePath, 'setup')
  if (
    baselineSource.counts[testCase.correctSnippet] !== 1
    || baselineSource.counts[testCase.defectSnippet] !== 0
  ) {
    throw new ReliabilityError('setup', 'source baseline does not contain the exact expected snippet')
  }
  precheck.baselineSource = baselineSource
  precheck.seededSource = await docker.seedDefect(testCase)

  const seededStatus = await docker.gitStatus(testCase)
  assertCommandSuccess(seededStatus, 'seeded git status')
  assertStatusAllowlist(
    seededStatus.stdout,
    { [testCase.sourcePath]: [' M', 'M ', 'MM'] },
    { required: [testCase.sourcePath] },
  )
  const targetTest = await docker.runTargetTest(testCase)
  precheck.targetTest = summarizeCommand(targetTest)
  if (targetTest.code === 0) {
    throw new ReliabilityError(
      'setup',
      'repair precheck unexpectedly passed after deterministic defect seed',
    )
  }
  return precheck
}

async function postcheckCase(docker, testCase, precheck) {
  const status = await docker.gitStatus(testCase)
  assertCommandSuccess(status, 'postcheck git status', 'postcheck')
  if (testCase.taskClass === 'investigate') {
    assertStatusAllowlist(status.stdout, {})
    return {
      status: 'pass',
      gitStatus: '',
      targetTest: null,
      diffCheck: null,
      source: null,
      writeProof: null,
      gitDiff: { exitCode: 0, output: '', note: 'read-only case remained clean' },
    }
  }

  const inspected = await docker.inspectFiles(testCase, [
    {
      path: testCase.sourcePath,
      needles: [testCase.correctSnippet, testCase.defectSnippet],
    },
    { path: WRITE_PROOF_PATH, needles: [] },
  ])
  const source = requiredFile(inspected, testCase.sourcePath, 'postcheck')
  const proof = requiredFile(inspected, WRITE_PROOF_PATH, 'postcheck')
  if (
    source.sha256 !== precheck.baselineSource.sha256
    || source.byteLength !== precheck.baselineSource.byteLength
    || source.counts[testCase.correctSnippet] !== 1
    || source.counts[testCase.defectSnippet] !== 0
  ) {
    throw new ReliabilityError('postcheck', 'source was not restored to the exact baseline bytes')
  }
  if (
    proof.byteLength !== WRITE_PROOF_META.byteLength
    || proof.sha256 !== WRITE_PROOF_META.sha256
  ) {
    throw new ReliabilityError(
      'postcheck',
      'write proof file bytes do not match the expected payload; intact arguments did not survive execution/transport',
    )
  }
  assertStatusAllowlist(
    status.stdout,
    {
      [testCase.sourcePath]: [' M', 'M ', 'MM'],
      [WRITE_PROOF_PATH]: ['??'],
    },
    { required: [WRITE_PROOF_PATH] },
  )

  const targetTest = await docker.runTargetTest(testCase)
  const diffCheck = await docker.gitDiffCheck(testCase)
  const gitDiff = await docker.gitDiff(testCase, testCase.sourcePath)
  const gitDiffSummary = summarizeCommand(gitDiff)
  if (targetTest.code !== 0) {
    throw new ReliabilityError('postcheck', 'target test failed after repair', {
      targetTest: summarizeCommand(targetTest),
      gitDiff: gitDiffSummary,
    })
  }
  if (diffCheck.code !== 0) {
    throw new ReliabilityError('postcheck', 'git diff --check failed after repair', {
      diffCheck: summarizeCommand(diffCheck),
      gitDiff: gitDiffSummary,
    })
  }
  if (gitDiff.code !== 0 || gitDiff.stdout.trim()) {
    throw new ReliabilityError('postcheck', 'source diff is not empty after exact restoration', {
      gitDiff: gitDiffSummary,
    })
  }
  return {
    status: 'pass',
    gitStatus: status.stdout.trim(),
    targetTest: summarizeCommand(targetTest),
    diffCheck: summarizeCommand(diffCheck),
    source,
    writeProof: proof,
    gitDiff: gitDiffSummary,
  }
}

/** 构造原子 NEW_SESSION 提交：预分配 sessionId/threadId，rootSettings 绑定环境，yolo true，首条 USER command。 */
export function buildNewSessionRequest({ chat, agent, testCase, daemonEnv, prompt }) {
  return {
    owner: chatOwner(chat.id),
    sessionId: cid(),
    threadId: cid(),
    rootSettings: branchSettingsOf(
      agent,
      {
        providerName: testCase.model.providerName,
        modelName: testCase.model.modelName,
        variant: VARIANT,
      },
      { environment: { name: daemonEnv, workspacePath: '.' } },
    ),
    yoloEnabled: true,
    commands: [userMessageCommand(prompt, cid())],
  }
}

export function assertThreadSettings(accepted, testCase, daemonEnv, agentName) {
  const thread = accepted.thread
  const firstAccepted = accepted.acceptedCommands?.[0]
  assert(
    firstAccepted && String(thread.threadId) === String(firstAccepted.threadId),
    'Thread identity mismatch',
  )
  // 首条 USER command 已接受：nextCommandSequence 精确为 2，version 至少 1。
  // 不锁定瞬时 status/head（processor 可能已异步消费）。
  assert(
    String(thread.nextCommandSequence) === '2',
    `Thread nextCommandSequence must be 2: ${JSON.stringify(thread)}`,
  )
  assert(
    Number(thread.version) >= 1,
    `Thread version must be at least 1: ${JSON.stringify(thread)}`,
  )
  assert(thread.yoloEnabled === true, 'Thread yoloEnabled must be true')
  assert(
    thread.branchSettings?.environment?.name === daemonEnv,
    'Thread Environment mismatch',
  )
  assert(thread.branchSettings?.agentName === agentName, 'Thread Agent mismatch')
  assert(
    JSON.stringify(thread.branchSettings?.model)
      === JSON.stringify({
        providerName: testCase.model.providerName,
        modelName: testCase.model.modelName,
        variant: VARIANT,
      }),
    'Thread model selection mismatch',
  )
  assert(
    Object.keys(thread.branchSettings ?? {}).sort().join(',')
      === 'agentName,environment,model',
    `Thread branch settings shape mismatch: ${JSON.stringify(thread.branchSettings)}`,
  )
}

function assertNoAssistantError(entries) {
  for (const entry of entries ?? []) {
    if (String(entry?.entryType || '').toUpperCase() !== 'ASSISTANT_ERROR') continue
    let message = 'assistant error'
    try {
      message = JSON.parse(entry.payloadJson)?.error?.message || message
    } catch {
      // Keep the stable fallback.
    }
    throw new ReliabilityError('model', message)
  }
}

async function bestEffortStop(ctx, threadId) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const snapshot = await getThreadSnapshot(ctx, threadId)
      await stopThread(ctx, threadId, {
        stopRequestId: cid(),
        expectedVersion: snapshot.thread.version,
      })
      return
    } catch {
      // Retry once with the newest durable version.
    }
  }
}

async function bestEffortSnapshot(ctx, threadId) {
  try {
    return await getThreadSnapshot(ctx, threadId)
  } catch {
    return null
  }
}

function requiredFile(inspected, filePath, category) {
  const file = inspected.files?.find((candidate) => candidate.path === filePath)
  if (!file?.exists) {
    throw new ReliabilityError(category, `required case file is missing: ${filePath}`)
  }
  return file
}

function assertCommandSuccess(result, label, category = 'setup') {
  if (result.code !== 0) {
    throw new ReliabilityError(category, `${label} failed: ${JSON.stringify(summarizeCommand(result))}`)
  }
}

function normalizeError(error, fallbackCategory) {
  if (error instanceof ReliabilityError) return error
  return new ReliabilityError(fallbackCategory, error?.message ?? String(error))
}

function countTools(order) {
  const counts = {}
  for (const name of order) counts[name] = (counts[name] ?? 0) + 1
  return counts
}

function baseResult(testCase, startedAt) {
  return {
    id: testCase.id,
    title: testCase.title,
    model: testCase.model.ref,
    variant: VARIANT,
    anchor: testCase.anchor,
    taskClass: testCase.taskClass,
    status: 'fail',
    failureCategory: null,
    error: null,
    errorDetails: null,
    turnStarted: false,
    costKnown: false,
    startedAt,
    finishedAt: null,
    durationMs: 0,
    toolOrder: [],
    toolCounts: {},
    metrics: aggregateUsage([]),
    tests: { precheck: null, postcheck: null, diffCheck: null },
  }
}

function skippedResult(testCase, category, message) {
  const now = new Date().toISOString()
  return {
    ...baseResult(testCase, now),
    status: 'skip',
    failureCategory: category,
    error: message,
    costKnown: true,
    finishedAt: now,
  }
}

function emptyTrace(testCase) {
  return {
    caseId: testCase.id,
    model: testCase.model.ref,
    variant: VARIANT,
    prompt: casePromptSummary(testCase),
    events: [],
    writeDiagnostics: [],
    finalText: '',
    usage: aggregateUsage([]),
  }
}

function createRunId() {
  return `${new Date().toISOString().replace(/[-:.]/g, '').replace('T', 'T').slice(0, 15)}Z-${randomUUID().slice(0, 8)}`
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const exitCode = await main()
  process.exitCode = exitCode
}
