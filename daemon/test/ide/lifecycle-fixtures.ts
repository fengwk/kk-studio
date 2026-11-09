import type { IdeCommand, IdeReply, IdeService } from '../../src/contracts.js'

/** One actual process identity shared by every IDE command in tests. */
export const DAEMON_INSTANCE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

export function signal(): AbortSignal {
  return new AbortController().signal
}

export function openCommand(
  viewId: string,
  browserSessionId: string,
  claimId: string,
  directory: string | undefined,
  mode: 'create' | 'resume' = 'create',
  expectedBindingEpoch = '0',
): IdeCommand {
  return {
    action: 'OPEN_VIEW',
    viewId,
    browserSessionId,
    eventNodeId: '11111111-1111-4111-8111-111111111111',
    connectionId: '22222222-2222-4222-8222-222222222222',
    claimId,
    mode,
    expectedBindingEpoch,
    expectedDaemonInstanceId: DAEMON_INSTANCE_ID,
    ...(directory === undefined ? {} : { directory }),
  }
}

export async function listViews(service: IdeService): Promise<IdeReply> {
  return service.handle({ action: 'LIST_VIEWS' }, signal())
}
