import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useState, type PropsWithChildren } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasController } from '@/features/canvas/useCanvasController'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'
import {
  applyCanvasCommands,
  cancelCanvasFunctionRun,
  completeCanvasUpload,
  getCanvas,
  getCanvasFunctionRun,
  listCanvasFunctionModels,
  reserveCanvasUpload,
  startCanvasFunctionRun,
  uploadCanvasFile,
} from '@/shared/api/studio-service'

vi.mock('@/shared/api/studio-service', () => ({
  applyCanvasCommands: vi.fn(),
  cancelCanvasFunctionRun: vi.fn(),
  completeCanvasUpload: vi.fn(),
  getCanvas: vi.fn(),
  getCanvasFunctionRun: vi.fn(),
  listCanvasFunctionModels: vi.fn(),
  reserveCanvasUpload: vi.fn(),
  startCanvasFunctionRun: vi.fn(),
  uploadCanvasFile: vi.fn(),
}))

function snapshot(revision = '0'): CanvasSnapshotDTO {
  return {
    document: {
      id: '1',
      title: 'Board',
      graphRevision: revision,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [{
      id: '2',
      canvasId: '1',
      name: 'Note',
      transform: { x: 20, y: 30, width: 320, height: 260 },
      groupId: null,
      resources: [{
        id: '20',
        canvasId: '1',
        kind: 'TEXT',
        mediaType: 'text/markdown',
        name: 'note.md',
        size: '4',
        textContent: 'note',
        metadataJson: '{}',
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: null,
      run: null,
    }, {
      id: '3',
      canvasId: '1',
      name: 'Function',
      transform: { x: 400, y: 30, width: 320, height: 260 },
      groupId: '4',
      resources: [{
        id: '30',
        canvasId: '1',
        kind: 'IMAGE',
        mediaType: 'image/png',
        name: 'old-output.png',
        size: '3',
        textContent: null,
        metadataJson: '{}',
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: {
        modelKey: 'fake-image',
        configJson: JSON.stringify({
          prompt: { segments: [{ type: 'TEXT', text: 'old prompt' }] },
          parameters: { ratio: 'AUTO' },
        }),
      },
      run: null,
    }, {
      id: '5',
      canvasId: '1',
      name: 'Image',
      transform: { x: 60, y: 360, width: 320, height: 260 },
      groupId: '4',
      resources: [{
        id: '50',
        canvasId: '1',
        kind: 'IMAGE',
        mediaType: 'image/png',
        name: 'image.png',
        size: '3',
        textContent: null,
        metadataJson: '{}',
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: null,
      run: null,
    }],
    groups: [{
      id: '4',
      canvasId: '1',
      title: 'Group',
      transform: { x: 0, y: 0, width: 800, height: 700 },
    }],
    links: [{
      canvasId: '1',
      sourceNodeId: '2',
      targetNodeId: '3',
    }],
  }
}

function Wrapper({ children }: PropsWithChildren) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  }))
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useCanvasController real snapshot runtime', () => {
  let revision: bigint
  let commands: ApplyCanvasCommandsRequestDTO[]

  beforeEach(() => {
    vi.clearAllMocks()
    revision = 0n
    commands = []
    vi.mocked(getCanvas).mockImplementation(async () => snapshot(revision.toString()))
    vi.mocked(listCanvasFunctionModels).mockResolvedValue([{
      key: 'fake-image',
      label: 'Fake Image',
      outputKind: 'IMAGE',
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
      parameters: [{
        key: 'ratio',
        label: 'Ratio',
        type: 'ENUM',
        required: false,
        defaultValue: 'AUTO',
        options: ['AUTO'],
        min: null,
        max: null,
      }],
      available: true,
      unavailableReason: null,
    }])
    vi.mocked(applyCanvasCommands).mockImplementation(async (_canvasId, request) => {
      commands.push(request)
      revision += 1n
      return snapshot(revision.toString())
    })
    vi.mocked(reserveCanvasUpload).mockResolvedValue({
      uploadId: '9',
      method: 'PUT',
      url: 'https://s3.example/upload',
      headers: { 'If-None-Match': '*' },
      expiresAt: '2026-08-10T00:15:00Z',
    })
    vi.mocked(uploadCanvasFile).mockResolvedValue()
    vi.mocked(completeCanvasUpload).mockResolvedValue({
      id: '9',
      canvasId: '1',
      kind: 'IMAGE',
      mediaType: 'image/png',
      name: 'upload.png',
      size: '3',
      textContent: null,
      metadataJson: '{}',
      createdAt: '2026-08-10T00:00:00Z',
    })
    vi.mocked(startCanvasFunctionRun).mockResolvedValue({
      nodeId: '3',
      requestId: 'request-started',
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    vi.mocked(getCanvasFunctionRun).mockResolvedValue({
      nodeId: '3',
      requestId: 'request-started',
      status: 'FAILED',
      stage: 'FAILED',
      error: 'fake failure',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    vi.mocked(cancelCanvasFunctionRun).mockResolvedValue({
      nodeId: '3',
      requestId: 'request-started',
      status: 'CANCELLED',
      stage: 'CANCELLED',
      error: null,
      updatedAt: '2026-08-10T00:00:02Z',
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('drives commands, local UI state, uploads, and keyboard actions from one server snapshot', async () => {
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor('1'))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe('1'))
    await waitFor(() => expect(result.current.models).toHaveLength(1))

    act(() => {
      result.current.setSelection(['2'])
      result.current.nodeCallbacks.renameNode('2', ' Renamed ')
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'RENAME_NODE'
    ))).toBe(true))

    act(() => result.current.nodeCallbacks.editTextNode(
      result.current.snapshot ? {
        ...result.current.snapshot.nodes[0],
        resources: [{
          id: '20',
          canvasId: '1',
          kind: 'TEXT',
          mediaType: 'text/markdown',
          name: 'note.md',
          size: '4',
          text: 'note',
          metadata: {},
          createdAt: '2026-08-10T00:00:00Z',
        }],
        function: null,
        run: null,
      } : (() => {
        throw new Error('missing snapshot')
      })(),
    ))
    act(() => {
      result.current.setTextEditorDraft({ markdown: 'updated' })
    })
    act(() => {
      result.current.saveTextEditor()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UPDATE_TEXT_NODE'
    ))).toBe(true))

    act(() => {
      result.current.createTextNode()
      result.current.createFunctionNode('IMAGE')
      result.current.createFunctionNode('VIDEO')
    })
    act(() => {
      result.current.saveTextEditor()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_FUNCTION_NODE'
    ))).toBe(true))
    expect(result.current.state.toast).toContain('没有可用的视频模型')

    act(() => {
      result.current.setSelection([])
    })
    act(() => {
      result.current.createGroup()
      result.current.ungroupSelection()
    })
    act(() => {
      result.current.setSelection(['2'])
    })
    act(() => {
      result.current.createGroup()
    })
    act(() => {
      result.current.setSelection(['3', '5'])
    })
    act(() => {
      result.current.ungroupSelection()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_GROUP'
    ))).toBe(true))
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'UNGROUP'
    ))).toBe(true))

    await act(async () => {
      await result.current.uploadFiles([
        new File(['bad'], 'bad.bin', { type: 'application/octet-stream' }),
        new File(['png'], 'upload.png'),
      ])
    })
    expect(reserveCanvasUpload).toHaveBeenCalledOnce()
    expect(uploadCanvasFile).toHaveBeenCalledOnce()
    expect(completeCanvasUpload).toHaveBeenCalledOnce()
    expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_RESOURCE_NODE'
    ))).toBe(true)

    act(() => {
      result.current.createLink('2', '3')
      result.current.deleteLink('2', '3')
      result.current.setSelection(['group:4', '2'])
    })
    act(() => {
      result.current.deleteSelection()
    })
    await waitFor(() => expect(commands.some((request) => (
      request.commands.some((command) => command.type === 'DELETE_GROUP')
    ))).toBe(true))

    act(() => {
      result.current.setAgentPrompt('')
      result.current.sendAgent()
      result.current.setAgentPrompt('inspect')
      result.current.sendAgent()
      result.current.setContextMode('whole')
      result.current.toggleAddMenu()
      result.current.closeAddMenu()
      result.current.setAddMenuIndex(2)
      result.current.openThread()
      result.current.collapseThread()
      result.current.setTool('hand')
      result.current.setViewport({ x: 1, y: 2, zoom: 0.5 })
    })
    expect(result.current.state.messages).toHaveLength(2)
    expect(result.current.contextCount).toBe(3)
    expect(result.current.state.viewport).toEqual({ x: 1, y: 2, zoom: 0.5 })

    const fit = vi.fn()
    const focus = vi.fn()
    const zoom = vi.fn()
    result.current.fitViewRef.current = fit
    result.current.focusSelectionRef.current = focus
    result.current.zoomRef.current = zoom
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '0' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '1' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'v' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'h' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 't' }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true }))
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    })
    expect(fit).toHaveBeenCalledOnce()
    expect(focus).toHaveBeenCalledOnce()
    expect(zoom).toHaveBeenCalledWith(1)
    expect(result.current.state.selectedIds).toEqual([])

    act(() => result.current.openLibrary())
    expect(result.current.state.view).toBe('library')
  })

  it('allocates unique aliases from the command queue snapshot across rapid creates and uploads', async () => {
    // Queue-owned snapshots plus in-flight reservations cover both synchronous clicks and sequential files.
    let current = snapshot()
    let resourceId = 90n
    vi.mocked(getCanvas).mockImplementation(async () => current)
    vi.mocked(completeCanvasUpload).mockImplementation(async () => {
      resourceId += 1n
      return {
        id: resourceId.toString() as `${bigint}`,
        canvasId: '1',
        kind: 'IMAGE',
        mediaType: 'image/heic',
        name: 'same.heic',
        size: '3',
        textContent: null,
        metadataJson: '{}',
        createdAt: '2026-08-10T00:00:00Z',
      }
    })
    vi.mocked(applyCanvasCommands).mockImplementation(async (_canvasId, request) => {
      commands.push(request)
      let nodes = current.nodes
      for (const command of request.commands) {
        if (
          command.type === 'CREATE_FUNCTION_NODE'
          || command.type === 'CREATE_TEXT_NODE'
          || command.type === 'CREATE_RESOURCE_NODE'
        ) {
          nodes = [...nodes, {
            id: String(nodes.length + 10) as `${bigint}`,
            canvasId: '1',
            name: command.name,
            transform: command.transform,
            groupId: null,
            resources: [],
            function: command.type === 'CREATE_FUNCTION_NODE'
              ? { modelKey: command.modelKey, configJson: command.configJson }
              : null,
            run: null,
          }]
        }
      }
      revision += 1n
      current = {
        ...current,
        document: { ...current.document, graphRevision: revision.toString() as `${bigint}` },
        nodes,
      }
      return current
    })

    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor('1'))
    await waitFor(() => expect(result.current.models).toHaveLength(1))

    act(() => {
      result.current.createFunctionNode('IMAGE')
      result.current.createFunctionNode('IMAGE')
    })
    await waitFor(() => expect(commands.filter((request) => (
      request.commands[0]?.type === 'CREATE_FUNCTION_NODE'
    ))).toHaveLength(2))

    act(() => {
      result.current.createTextNode()
      result.current.setTextEditorDraft({ name: '  图片生成  ' })
    })
    act(() => result.current.saveTextEditor())
    await waitFor(() => expect(commands.some((request) => (
      request.commands[0]?.type === 'CREATE_TEXT_NODE'
    ))).toBe(true))

    await act(async () => {
      await result.current.uploadFiles([
        new File(['one'], 'same.heic'),
        new File(['two'], 'same.heic'),
      ])
    })

    const aliases = commands.flatMap((request) => request.commands.flatMap((command) => (
      command.type === 'CREATE_FUNCTION_NODE'
      || command.type === 'CREATE_TEXT_NODE'
      || command.type === 'CREATE_RESOURCE_NODE'
        ? [command.name]
        : []
    )))
    expect(aliases).toEqual([
      '图片生成',
      '图片生成 2',
      '图片生成 3',
      'same.heic',
      'same.heic 2',
    ])
    expect(reserveCanvasUpload).toHaveBeenNthCalledWith(1, '1', expect.objectContaining({
      kind: 'IMAGE',
      mediaType: 'image/heic',
    }))
    expect(reserveCanvasUpload).toHaveBeenNthCalledWith(2, '1', expect.objectContaining({
      kind: 'IMAGE',
      mediaType: 'image/heic',
    }))
  })

  it('flushes debounced config before UUID start, polls only run state, preserves old output on failure, and cancels', async () => {
    // The call order and unchanged Resource id prove config/run orchestration never introduces a second snapshot.
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor('1'))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe('1'))

    act(() => {
      result.current.scheduleFunctionConfig('3', 'fake-image', {
        prompt: { segments: [{ type: 'TEXT', text: 'new prompt' }] },
        parameters: { ratio: 'AUTO' },
      })
    })
    await act(async () => {
      await result.current.startFunctionRun('3')
    })

    expect(commands.at(-1)?.commands).toEqual([{
      type: 'UPDATE_FUNCTION',
      nodeId: '3',
      modelKey: 'fake-image',
      configJson: JSON.stringify({
        prompt: { segments: [{ type: 'TEXT', text: 'new prompt' }] },
        parameters: { ratio: 'AUTO' },
      }),
    }])
    expect(startCanvasFunctionRun).toHaveBeenCalledWith(
      '1',
      '3',
      { requestId: expect.stringMatching(/^[0-9a-f-]{36}$/i) },
    )
    expect(vi.mocked(applyCanvasCommands).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(startCanvasFunctionRun).mock.invocationCallOrder[0] ?? Number.MAX_SAFE_INTEGER,
    )

    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === '3')?.run?.status).toBe('FAILED')
    })
    expect(result.current.snapshot?.nodes.find((node) => node.id === '3')?.resources[0]?.id).toBe('30')
    expect(getCanvasFunctionRun).toHaveBeenCalledWith('1', '3', expect.objectContaining({
      signal: expect.any(AbortSignal),
    }))

    await act(async () => {
      await result.current.cancelFunctionRun('3', 'request-started')
    })
    expect(cancelCanvasFunctionRun).toHaveBeenCalledWith(
      '1',
      '3',
      { requestId: 'request-started' },
    )
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === '3')?.run?.status).toBe('CANCELLED')
    })
  })

  it('refetches the authoritative snapshot after a successful polled run', async () => {
    // Success is complete only when the snapshot query replaces old output with backend materialized resources.
    vi.mocked(getCanvasFunctionRun).mockResolvedValue({
      nodeId: '3',
      requestId: 'request-started',
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:01Z',
    })
    vi.mocked(getCanvas).mockImplementation(async () => {
      const current = snapshot(revision.toString())
      if (vi.mocked(getCanvas).mock.calls.length < 2) {
        return current
      }
      return {
        ...current,
        nodes: current.nodes.map((node) => node.id === '3' ? {
          ...node,
          resources: [{
            id: '31',
            canvasId: '1',
            kind: 'IMAGE',
            mediaType: 'image/png',
            name: 'new-output.png',
            size: '4',
            textContent: null,
            metadataJson: '{}',
            createdAt: '2026-08-10T00:00:01Z',
          }],
          run: {
            nodeId: '3',
            requestId: 'request-started',
            status: 'SUCCEEDED',
            stage: 'SUCCEEDED',
            error: null,
            updatedAt: '2026-08-10T00:00:01Z',
          },
        } : node),
      }
    })
    const { result } = renderHook(() => useCanvasController(), { wrapper: Wrapper })
    act(() => result.current.openEditor('1'))
    await waitFor(() => expect(result.current.snapshot?.document.id).toBe('1'))
    await act(async () => {
      await result.current.startFunctionRun('3')
    })
    await waitFor(() => {
      expect(result.current.snapshot?.nodes.find((node) => node.id === '3')?.resources[0]?.id).toBe('31')
    })
    expect(getCanvas).toHaveBeenCalledTimes(2)
  })
})
