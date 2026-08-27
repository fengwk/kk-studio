import { describe, expect, it } from 'vitest'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import type { BranchDraft } from '@/features/ai/chat/branch-draft'
import type {
  HarnessBranchSettingsDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'

function settings(overrides: Partial<HarnessBranchSettingsDTO> = {}): HarnessBranchSettingsDTO {
  return {
    environment: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    ...overrides,
  }
}

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't1',
    sessionId: 's1',
    headEntryId: 'root',
    yoloEnabled: false,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: settings(),
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function draftOf(overrides: Partial<BranchDraft> = {}): BranchDraft {
  return {
    environment: null,
    agentName: 'assistant',
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    yoloEnabled: false,
    ...overrides,
  }
}

function partsOf(...parts: ComposerPart[]): ComposerPart[] {
  return parts
}

function plan(
  options: Partial<Parameters<typeof buildMessageBatchPlan>[0]> = {},
): CommandBatchPlan {
  return buildMessageBatchPlan({
    thread: thread(),
    effectiveBase: draftOf(),
    draft: draftOf(),
    parts: partsOf(createTextPart('hello')),
    createCommandId: () => 'stable-command',
    ...options,
  })
}

describe('command batch replay identity', () => {
  it('keeps identity stable for the same immutable content and branch intent', () => {
    const first = plan()
    const second = plan()
    expect(second.identity).toBe(first.identity)
    expect(second.request.commands[0]?.type).toBe('USER_MESSAGE')
    expect(second.request.target).toMatchObject({
      type: 'THREAD',
      threadId: 't1',
      expectedHeadEntryId: 'root',
      expectedNextCommandSequence: '1',
    })
  })

  it('changes identity when content, target draft, or Thread changes', () => {
    const original = plan()
    expect(plan({ parts: partsOf(createTextPart('changed')) }).identity)
      .not.toBe(original.identity)
    expect(plan({ draft: draftOf({ agentName: 'coder' }) }).identity)
      .not.toBe(original.identity)
    expect(plan({ thread: thread({ threadId: 't2' }) }).identity)
      .not.toBe(original.identity)
  })

  it('keeps settings diff commands ordered before the USER_MESSAGE', () => {
    const batch = plan({
      effectiveBase: draftOf(),
      draft: draftOf({
        environment: { name: 'local', workspacePath: '.' },
        agentName: 'coder',
        model: { providerName: 'openai', modelName: 'GPT-5', variant: 'v2' },
      }),
    })
    expect(batch.request.commands.map((command) => command.type)).toEqual([
      'SET_ENVIRONMENT',
      'SET_AGENT',
      'SET_MODEL',
      'USER_MESSAGE',
    ])
  })

  it('keeps the exact frozen request available for a controller replay', () => {
    const batch = plan()
    expect(batch.frozen.request).toBe(batch.request)
    expect(batch.frozen.identity).toBe(batch.identity)
    expect(batch.frozen.composerParts).toEqual(batch.frozen.composerParts)
  })
})

describe('ordered USER_MESSAGE contents serialization', () => {
  it('rejects an empty message before creating a request', () => {
    expect(() => plan({ parts: [] })).toThrow('message content')
  })

  it('serializes text, attachment, and text in order without non-contract fields', () => {
    const batch = plan({
      parts: partsOf(
        createTextPart('before'),
        createAttachmentPart('upload-1', 'a.png'),
        createTextPart('after'),
      ),
    })
    const command = batch.request.commands.at(-1)
    expect(command).toMatchObject({
      type: 'USER_MESSAGE',
      contents: [
        { type: 'TEXT', text: 'before' },
        { type: 'ATTACHMENT', uploadId: 'upload-1' },
        { type: 'TEXT', text: 'after' },
      ],
    })
    expect(command).not.toHaveProperty('content')
    expect(command).not.toHaveProperty('role')
  })

  it('trims outer text while preserving attachment order', () => {
    const batch = plan({
      parts: partsOf(
        createTextPart('  '),
        createTextPart('hello\n'),
        createAttachmentPart('upload-1', 'a.png'),
        createTextPart('   '),
      ),
    })
    expect(batch.request.commands.at(-1)).toMatchObject({
      contents: [
        { type: 'TEXT', text: 'hello\n' },
        { type: 'ATTACHMENT', uploadId: 'upload-1' },
      ],
    })
  })

  it('supports attachment-only messages', () => {
    const batch = plan({
      parts: partsOf(createAttachmentPart('upload-9', 'clip.mp4')),
    })
    expect(batch.request.commands.at(-1)).toMatchObject({
      type: 'USER_MESSAGE',
      contents: [{ type: 'ATTACHMENT', uploadId: 'upload-9' }],
    })
  })
})
