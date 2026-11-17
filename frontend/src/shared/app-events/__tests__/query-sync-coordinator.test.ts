import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { coordinateQuerySync } from '../query-sync-coordinator'

describe('coordinateQuerySync', () => {
  it('serializes bursts and performs one trailing reconciliation after the in-flight read', async () => {
    const client = new QueryClient()
    let release!: () => void
    const first = new Promise<void>((resolve) => { release = resolve })
    const sync = vi.fn()
      .mockImplementationOnce(() => first)
      .mockResolvedValue(undefined)

    const running = coordinateQuerySync(client, ['thread', 'snapshot', 'root'], sync)
    const joined = coordinateQuerySync(client, ['thread', 'snapshot', 'root'], sync)
    coordinateQuerySync(client, ['thread', 'snapshot', 'root'], sync)

    expect(sync).toHaveBeenCalledTimes(1)
    release()
    await Promise.all([running, joined])

    expect(sync).toHaveBeenCalledTimes(2)
    client.clear()
  })

  it('preserves an undefined rejection after coalesced trailing work', async () => {
    const client = new QueryClient()
    let reject!: (reason?: unknown) => void
    const first = new Promise<void>((_, fail) => { reject = fail })
    const sync = vi.fn()
      .mockImplementationOnce(() => first)
      .mockResolvedValue(undefined)
    const key = ['threads', 'snapshot', 'root']

    const firstCall = coordinateQuerySync(client, key, sync)
    const settled = Promise.allSettled([
      firstCall,
      coordinateQuerySync(client, key, sync),
      coordinateQuerySync(client, key, sync),
    ])
    reject(undefined)
    const outcomes = await settled

    expect(sync).toHaveBeenCalledTimes(2)
    expect(outcomes).toEqual([
      { status: 'rejected', reason: undefined },
      { status: 'rejected', reason: undefined },
      { status: 'rejected', reason: undefined },
    ])
    await coordinateQuerySync(client, key, sync)
    expect(sync).toHaveBeenCalledTimes(3)
    client.clear()
  })

  it('starts a new reconciliation in the microtask after a clean pass', async () => {
    // Re-enter after the run ends but before promise-finally cleanup used to run.
    const client = new QueryClient()
    let followup!: Promise<void>
    const firstResult = {
      then(resolve: (value: undefined) => void) {
        resolve(undefined)
        queueMicrotask(() => {
          followup = coordinateQuerySync(client, ['threads', 'snapshot', 'root'], sync)
        })
      },
    } as unknown as Promise<unknown>
    const sync = vi.fn()
      .mockImplementationOnce(() => firstResult)
      .mockResolvedValue(undefined)
    const first = coordinateQuerySync(client, ['threads', 'snapshot', 'root'], sync)

    await first
    await followup

    expect(sync).toHaveBeenCalledTimes(2)
    client.clear()
  })

  it('keeps different query clients and keys independent', async () => {
    const firstClient = new QueryClient()
    const secondClient = new QueryClient()
    const firstSync = vi.fn().mockResolvedValue(undefined)
    const secondSync = vi.fn().mockResolvedValue(undefined)

    await Promise.all([
      coordinateQuerySync(firstClient, ['tree', 'a'], firstSync),
      coordinateQuerySync(firstClient, ['tree', 'b'], secondSync),
      coordinateQuerySync(secondClient, ['tree', 'a'], secondSync),
    ])

    expect(firstSync).toHaveBeenCalledTimes(1)
    expect(secondSync).toHaveBeenCalledTimes(2)
    firstClient.clear()
    secondClient.clear()
  })
})
