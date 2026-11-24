import { useLayoutEffect, useRef, useState } from 'react'
import { Square } from 'lucide-react'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { ApprovalCard } from '@/features/thread/runtime/interactions/ApprovalCard'
import { createModelAttemptFailureMessage } from '@/features/thread/runtime/thread-timeline/model-attempt-failure'
import { ThreadPanel } from '@/features/thread/runtime/thread-panel/ThreadPanel'
import { EntryBranchContext } from '@/features/thread/runtime/thread-panel/entry-branch-context'
import { RootThreadControlArea } from '@/features/thread/runtime/panes/RootThreadControlArea'
import { ThreadStatusFooter } from '@/features/thread/runtime/thread-panel/ThreadStatusFooter'
import { THREAD_COMMANDS } from '@/features/thread/runtime/thread-panel/thread-commands'
import { formatTurnUsageText } from '@/features/thread/runtime/thread-timeline/turn-usage-format'
import type { DialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { createTextPart, partsToText, type ComposerPart } from '@/features/thread/composer/composer-parts'
import { approvalArguments, contextLimits, defaultEnvironment, environments, models, usage } from '../mock/thread-data'
import type { ConversationPane } from './projection'

const workspaceCommands = THREAD_COMMANDS.filter(({ id }) => id === 'files')
const draftSettings = new Map<string, { modelName: string; variant?: string | null; env: string | null; yolo: boolean }>()

function projectMessages(pane: ConversationPane): DialogueMessage[] {
  const messages: DialogueMessage[] = []
  if (pane.kind === 'thread') {
    messages.push({ id: `${pane.id}-start`, role: 'entry', kind: 'root', title: `会话开始 · ${pane.agent ?? '编码助手'}`,
      text: '', subjectEntryId: null, createdAt: 1 })
  }
  pane.messages.forEach((message, index) => {
    const base = { id: `${pane.id}-${index}`, subjectEntryId: null, createdAt: index + 2 }
    messages.push({ ...base, role: message.role === 'user' ? 'user' : 'assistant', text: message.text })
    if (message.modelError) {
      messages.push(createModelAttemptFailureMessage({ ...base, id: `${base.id}-end`, attempt: 1,
        sequence: String(index + 1), text: '', thinking: '', errorCode: 'BILLING', errorMessage: message.modelError,
        failedAt: base.createdAt, nextAttempt: null, retryAt: null }))
    } else if (message.turnEnd != null) {
      const turnUsage = message.turnEnd === 'usage' ? usage : undefined
      messages.push({ ...base, id: `${base.id}-end`, role: 'meta', kind: 'turn_usage', endEntryId: String(index),
        text: turnUsage ? formatTurnUsageText(turnUsage) : '', turnUsage })
    }
  })
  return messages
}

export function ThreadPaneActions({ status, stop }: { status: ConversationPane['status']; stop: () => void }) {
  if (status !== 'running') return null
  return <IconButton size="compact" className="x-ib" label="停止" title="停止" onClick={stop}>
    <Square className="x-ic" size={14} aria-hidden="true" />
  </IconButton>
}

export function ThreadPane({ pane, openIde }: { pane: ConversationPane; openIde: (id: string) => void }) {
  const bodyRef = useRef<HTMLDivElement>(null)
  const [parts, setParts] = useState<ComposerPart[]>(() => pane.draft ? [createTextPart(pane.draft)] : [])
  const [settings, setSettings] = useState(() => draftSettings.get(pane.targetKey) ?? {
    modelName: models[0].name,
    variant: 'max', env: pane.status === 'waiting' ? 'build-server' : defaultEnvironment.name, yolo: false,
  })
  function changeSettings(patch: Partial<typeof settings>) {
    const next = { ...settings, ...patch }
    draftSettings.set(pane.targetKey, next)
    setSettings(next)
  }
  const environment = environments.find(({ name }) => name === settings.env)
  const messages = projectMessages(pane)
  const working = pane.status === 'running' || pane.status === 'needs' || pane.status === 'waiting'
  useLayoutEffect(() => {
    for (const node of pane.node.querySelectorAll('.thread-status-line[title], .thread-meta-text[title], .thread-entry-title[title], .thread-composer-model-control[title] > span, .thread-composer-environment-control[title] > span')) {
      node.setAttribute('data-allow-truncate', '')
    }
  }, [pane.node, settings, messages])

  const composer = (
    <div
      onPasteCapture={(event) => {
        if (event.clipboardData.files.length || [...event.clipboardData.items].some(({ kind }) => kind === 'file')) {
          event.preventDefault()
          event.stopPropagation()
        }
      }}
      onDropCapture={(event) => {
        if (event.dataTransfer.types.includes('Files')) {
          event.preventDefault()
          event.stopPropagation()
        }
      }}>
      <RootThreadControlArea rootThreadId={null} messages={messages} queuedMessages={pane.queuedMessages}
        composer={{
          parts, pending: false, disabled: false, suspended: !pane.visible, focusOnEscape: false, scope: pane.targetKey,
          onPartsChange: (next) => { setParts(next); pane.setDraft(partsToText(next)) },
          onSubmit: (next) => {
            const text = partsToText(next).trim()
            if (!text) return
            const accepted = pane.send(text)
            if (!accepted) return
            draftSettings.set(accepted, settings)
            setParts([])
          },
          commands: environment?.ready ? workspaceCommands : [],
          onCommand: ({ id }) => { if (id === 'files' && environment?.ready) openIde(environment.id) },
          settings: {
            modelSelection: { modelName: settings.modelName, variant: settings.variant }, models,
            yoloEnabled: settings.yolo, environmentName: settings.env, environments,
            onYoloChange: (yolo) => changeSettings({ yolo }),
            onEnvironmentChange: (env) => changeSettings({ env }),
            onModelChange: (next) => changeSettings(next),
          },
        }} />
    </div>
  )
  return <EntryBranchContext.Provider value={pane.kind === 'thread' ? (endEntry) => pane.branch(Number(endEntry)) : null}>
    <ThreadPanel
      transcript={{ messages, queuedMessages: pane.queuedMessages, loading: false, error: null, bodyRef }}
      activity={{ working, workingLabel: pane.status === 'needs' ? '等待审批' : pane.status === 'waiting' ? '等待环境' : undefined,
        animated: pane.status === 'running',
        widgets: pane.approval ? <ApprovalCard threadId={pane.approval.threadId}
          invocationId={pane.approval.invocationId} toolName="bash" argumentsJson={approvalArguments(pane.approval.command)} /> : null }}
      controls={composer}
      slots={{ footer: <ThreadStatusFooter
        environment={environment ? { environmentId: environment.id, environmentName: environment.name } : null}
        environmentReady={environment?.ready} contextWindow={contextLimits[models.findIndex(({ name }) => name === settings.modelName)]}
        branchUsage={pane.messages.some(({ turnEnd }) => turnEnd === 'usage') ? usage : null} /> }} />
  </EntryBranchContext.Provider>
}
