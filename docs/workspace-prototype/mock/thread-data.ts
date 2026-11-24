import type { EnvironmentStatus } from '@/shared/api/generated/contracts'
import type { ThreadComposerModelOption } from '@/features/thread/runtime/thread-panel/thread-composer-control-types'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

const environmentFixtures = [
  { id: 'a1000000-0000-4000-8000-000000000001', name: 'archlinux', status: 'READY' },
  { id: 'a1000000-0000-4000-8000-000000000002', name: 'dev-workstation', status: 'READY' },
  { id: 'a1000000-0000-4000-8000-000000000004', name: 'ci-runner', status: 'CONNECTING' },
  { id: 'a1000000-0000-4000-8000-000000000003', name: 'build-server', status: 'OFFLINE' },
] satisfies { id: string; name: string; status: EnvironmentStatus }[]

export const environments = environmentFixtures.map((environment) => ({ ...environment, ready: environment.status === 'READY' }))
export const defaultEnvironment = environments[0]
export const environmentActions = [{ view: 'vscode', label: 'VS Code' }] as const
export const approvalArguments = (command: string) => JSON.stringify({ command, workdir: '/workspace/kk-studio' })

export const models: ThreadComposerModelOption[] = [
  { name: 'openai/gpt-6.1-sol', type: 'MODEL', config: { defaultVariant: 'max', variants: [{ id: 'default' }, { id: 'max' }] } },
  { name: 'minimax/MiniMax-M3.1-Flash', type: 'MODEL', config: { defaultVariant: 'max', variants: [{ id: 'default' }, { id: 'max' }] } },
]
export const contextLimits = [272000, 400000]
export const usage: TurnUsage = {
  input: 11100, output: 371, cacheRead: 86000, cacheWrite: 0, reasoning: 150,
  providerTotal: null, cost: { currency: 'USD', amount: '0.021344' },
  decodeTokens: 371, decodeDurationMillis: 930, contextInputTokens: 97100,
}
