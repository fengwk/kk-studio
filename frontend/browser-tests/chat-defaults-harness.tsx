import { useState, useMemo, type FormEventHandler } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'
import { ChatCard } from '@/features/ai/chat/ChatCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

const params = new URLSearchParams(window.location.search)
const initialLocale = params.get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
setLocale(initialLocale)

const testAgents: AgentDefinitionDTO[] = [
  {
    name: 'assistant',
    model: {
      providerName: 'minimax',
      modelName: 'MiniMax',
      variant: 'default',
    },
    tools: [],
    skills: [],
    instruction: '',
    version: '1',
    createTime: null,
    updateTime: null,
  },
]

const initialEnvironments: EnvironmentCardDTO[] = [
  {
    id: 'env-docker-node',
    name: 'docker-node',
    status: 'ONLINE',
    version: '1',
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
  },
  {
    id: 'env-ubuntu',
    name: 'ubuntu',
    status: 'ONLINE',
    version: '1',
    createTime: '2026-10-01T00:00:00Z',
    updateTime: '2026-10-01T00:00:00Z',
  },
]

interface SubmittedPayload {
  mode: 'create' | 'edit'
  title: string
  agentName: string
  yoloEnabled: boolean
  environmentName: string | null
}

export function ChatDefaultsHarnessApp() {
  const [modalMode, setModalMode] = useState<'create' | 'edit' | null>(() => {
    const openParam = params.get('open')
    if (openParam === 'create') return 'create'
    if (openParam === 'edit' || openParam === 'edit-unavailable') return 'edit'
    return null
  })

  const [title, setTitle] = useState(() => {
    const openParam = params.get('open')
    if (openParam === 'edit') return 'Existing Chat'
    if (openParam === 'edit-unavailable') return 'Legacy Chat'
    return ''
  })
  const [selectedAgentName, setSelectedAgentName] = useState('assistant')
  const [yoloEnabled, setYoloEnabled] = useState(() => {
    return params.get('open') === 'edit'
  })
  const [selectedEnvironmentName, setSelectedEnvironmentName] = useState<string | null>(() => {
    const openParam = params.get('open')
    if (openParam === 'edit') return 'docker-node'
    if (openParam === 'edit-unavailable') return 'deleted-env'
    return null
  })

  const [submittedPayload, setSubmittedPayload] = useState<SubmittedPayload | null>(null)

  function openCreate() {
    setModalMode('create')
    setTitle('')
    setSelectedAgentName('assistant')
    setYoloEnabled(false)
    setSelectedEnvironmentName(null)
    setSubmittedPayload(null)
  }

  function openEdit() {
    setModalMode('edit')
    setTitle('Existing Chat')
    setSelectedAgentName('assistant')
    setYoloEnabled(true)
    setSelectedEnvironmentName('docker-node')
    setSubmittedPayload(null)
  }

  function openEditUnavailable() {
    setModalMode('edit')
    setTitle('Legacy Chat')
    setSelectedAgentName('assistant')
    setYoloEnabled(false)
    setSelectedEnvironmentName('deleted-env')
    setSubmittedPayload(null)
  }

  const handleSubmit: FormEventHandler<HTMLFormElement> = (event) => {
    event.preventDefault()
    if (!modalMode) return
    setSubmittedPayload({
      mode: modalMode,
      title,
      agentName: selectedAgentName,
      yoloEnabled,
      environmentName: selectedEnvironmentName,
    })
    setModalMode(null)
  }

  const sampleChats: ChatDTO[] = useMemo(() => [
    {
      id: 'chat-yolo-node',
      title: 'YOLO Docker Chat',
      agentName: 'assistant',
      yoloEnabled: true,
      environmentName: 'docker-node',
      version: '1',
      createTime: '2026-10-01T00:00:00Z',
      updateTime: '2026-10-01T00:00:00Z',
    },
    {
      id: 'chat-safe-null',
      title: 'Safe Null Env Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      environmentName: null,
      version: '2',
      createTime: '2026-10-02T00:00:00Z',
      updateTime: '2026-10-02T00:00:00Z',
    },
  ], [])

  return (
    <div className="chat-defaults-harness-frame" style={{ padding: 24, maxWidth: 1280, margin: '0 auto' }}>
      <header style={{ marginBottom: 20, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <button
          type="button"
          data-testid="open-create-btn"
          className="btn-primary"
          onClick={openCreate}
        >
          新建 Chat
        </button>
        <button
          type="button"
          data-testid="open-edit-btn"
          className="ghost-btn"
          onClick={openEdit}
        >
          编辑 Chat (YOLO开启+指定环境)
        </button>
        <button
          type="button"
          data-testid="open-edit-unavailable-btn"
          className="ghost-btn"
          onClick={openEditUnavailable}
        >
          编辑 Chat (不可用环境)
        </button>
      </header>

      <section style={{ marginBottom: 24 }}>
        <h2>Chat 列表卡片</h2>
        <ResourceGrid>
          {sampleChats.map((chat) => (
            <ChatCard
              key={chat.id}
              chat={chat}
              agents={testAgents}
              onEdit={() => {
                setModalMode('edit')
                setTitle(chat.title ?? '')
                setSelectedAgentName(chat.agentName)
                setYoloEnabled(chat.yoloEnabled)
                setSelectedEnvironmentName(chat.environmentName)
              }}
            />
          ))}
        </ResourceGrid>
      </section>

      <div style={{ marginTop: 24 }}>
        <h3>提交数据回显</h3>
        <pre data-testid="submitted-payload" style={{ background: '#1c211f', padding: 12, borderRadius: 8 }}>
          {submittedPayload ? JSON.stringify(submittedPayload) : 'none'}
        </pre>
      </div>

      <CreateChatModal
        open={modalMode !== null}
        mode={modalMode ?? 'create'}
        agents={testAgents}
        selectedAgentName={selectedAgentName}
        title={title}
        yoloEnabled={yoloEnabled}
        selectedEnvironmentName={selectedEnvironmentName}
        pending={false}
        onClose={() => setModalMode(null)}
        onSelectAgent={setSelectedAgentName}
        onTitleChange={setTitle}
        onYoloChange={setYoloEnabled}
        onSelectEnvironment={setSelectedEnvironmentName}
        onSubmit={handleSubmit}
      />
    </div>
  )
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
    },
  },
})
queryClient.setQueryData(queryKeys.environments.list, initialEnvironments)

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <MemoryRouter>
      <ChatDefaultsHarnessApp />
    </MemoryRouter>
  </QueryClientProvider>,
)
