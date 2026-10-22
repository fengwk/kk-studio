/* eslint-disable react-refresh/only-export-components */
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ModelsPage } from '@/features/catalog/ModelsPage'
import { ResourceEditorDialog } from '@/app/ai-console/ai-extension'
import {
  ThreadComposerControls,
  type ThreadComposerControlMenu,
  type ThreadComposerModelSelection,
} from '@/features/chat/runtime/thread-panel/ThreadComposerControls'
import { FIXTURE_MODELS } from './model-browser-fixtures'

const params = new URLSearchParams(location.search)
setLocale(params.get('locale') === 'en-US' ? 'en-US' : 'zh-CN')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, gcTime: 0 },
    mutations: { retry: false },
  },
})

/** 真实模型页 + 真实编辑弹窗；目录数据由 Playwright 的离线 API fixture 提供。 */
function ModelsHarness() {
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <ModelsPage>
        <ResourceEditorDialog />
      </ModelsPage>
    </div>
  )
}

/** 真实聊天模型菜单：组应直接选中并关闭菜单，不进入 Variant 二级菜单。 */
function ComposerHarness() {
  const [model, setModel] = useState<ThreadComposerModelSelection>({
    modelName: 'main',
    variant: 'standard',
  })
  const [menu, setMenu] = useState<ThreadComposerControlMenu>(null)

  return (
    <main
      style={{
        height: '100%',
        boxSizing: 'border-box',
        padding: 24,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'flex-end',
        gap: 12,
      }}
    >
      <pre id="composer-selection" style={{ margin: 0 }}>
        {JSON.stringify(model)}
      </pre>
      {/* 真实 Composer 位于窗口底部；模型菜单向上展开，才能在窄屏截图中完整检查。 */}
      <div style={{ width: '100%' }}>
        <ThreadComposerControls
          settings={{
            model,
            models: FIXTURE_MODELS,
            yoloEnabled: false,
            environmentName: null,
            environments: [],
            onModelChange: setModel,
            onYoloChange: () => undefined,
            onEnvironmentChange: () => undefined,
          }}
          menu={menu}
          disabled={false}
          onMenuChange={setMenu}
        />
      </div>
    </main>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/models']}>
        {params.get('view') === 'composer' ? <ComposerHarness /> : <ModelsHarness />}
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
