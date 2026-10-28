import type { IdeCommand, IdeReply, IdeService } from '../../src/contracts.js'

export function signal(): AbortSignal {
  return new AbortController().signal
}

export function openCommand(
  viewId: string,
  documentId: string,
  claimId: string,
  directory: string | undefined,
  mode: 'create' | 'resume' = 'create',
): IdeCommand {
  return {
    action: 'OPEN_VIEW',
    viewId,
    documentId,
    eventNodeId: '11111111-1111-4111-8111-111111111111',
    connectionId: '22222222-2222-4222-8222-222222222222',
    claimId,
    mode,
    ...(directory === undefined ? {} : { directory }),
  }
}

export async function listViews(service: IdeService): Promise<IdeReply> {
  return service.handle({ action: 'LIST_VIEWS' }, signal())
}
