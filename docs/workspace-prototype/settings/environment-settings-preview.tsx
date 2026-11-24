import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Box, Download, Pencil, SlidersHorizontal, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { EnvironmentConfigurationSection } from '@/features/environment/EnvironmentConfigurationSection'
import { EnvironmentHostSection } from '@/features/environment/EnvironmentHostSection'
import {
  EnvironmentConfigValidationError,
  validateDaemonConfig,
  validateEnvironmentNote,
} from '@/features/environment/environment-config-codec'
import { testEnvironment, testRuntimeStatus } from '@/test-support/environment-test-fixtures'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { EmptyState } from '@/shared/ui/feedback/EmptyState'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Badge } from '@/shared/ui/data/Badge'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import '@/features/environment/environment-management.css'
import '@/features/environment/environment-config.css'

const initialEnvironment = testEnvironment({
  id: 'a1000000-0000-4000-8000-000000000001',
  name: 'mi-ubuntu',
  note: '用于演示的离线环境',
  status: 'READY',
  ready: true,
  statusExpiresAt: null,
  daemonVersion: '1.2.7',
  operatingSystem: 'linux',
  homeDirectory: '/home/user',
  userName: 'user',
  timeZone: 'Asia/Shanghai',
  lastSeenAt: Date.now() - 120_000,
  capabilities: [{ id: 'shell' }, { id: 'filesystem' }],
  daemonConfig: {
    studioUrl: 'https://studio.example.invalid',
    bashExecutable: null,
    lsp: null,
    proxyUrl: null,
  },
})

const previewQueries = new QueryClient({
  defaultOptions: { queries: { staleTime: Infinity, gcTime: Infinity } },
})
previewQueries.setQueryData(
  queryKeys.environments.runtime(initialEnvironment.id),
  testRuntimeStatus({ daemonVersion: '1.2.7' }),
)

type DialogState = 'edit' | 'manage' | 'install' | 'delete' | null
const formErrorKeys: Partial<Record<string, TranslationKey>> = {
  note: 'ai.environment.form.invalidNote',
  'daemonConfig.studioUrl': 'ai.environment.form.invalidStudioUrl',
  'daemonConfig.bashExecutable': 'ai.environment.form.invalidBash',
  'daemonConfig.proxyUrl': 'ai.environment.form.invalidProxy',
}

/**
 * Standalone, offline preview of the Environment settings section. All edits affect only local
 * component state; the runtime query is pre-seeded and no environment service is called.
 */
export function EnvironmentSettingsPreview() {
  const { t } = useI18n()
  const [environment, setEnvironment] = useState(initialEnvironment)
  const [present, setPresent] = useState(true)
  const [dialog, setDialog] = useState<DialogState>(null)
  const [note, setNote] = useState(initialEnvironment.note ?? '')
  const [studioUrl, setStudioUrl] = useState(initialEnvironment.daemonConfig.studioUrl)
  const [bashExecutable, setBashExecutable] = useState(initialEnvironment.daemonConfig.bashExecutable ?? '')
  const [proxyUrl, setProxyUrl] = useState(initialEnvironment.daemonConfig.proxyUrl ?? '')
  const [formError, setFormError] = useState('')

  useEffect(() => {
    const close = () => setDialog(null)
    document.addEventListener('htmlview:change', close)
    return () => document.removeEventListener('htmlview:change', close)
  }, [])

  function openEdit() {
    setNote(environment.note ?? '')
    setStudioUrl(environment.daemonConfig.studioUrl)
    setBashExecutable(environment.daemonConfig.bashExecutable ?? '')
    setProxyUrl(environment.daemonConfig.proxyUrl ?? '')
    setFormError('')
    setDialog('edit')
  }

  function saveExample(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')
    try {
      const daemonConfig = validateDaemonConfig({
        studioUrl: studioUrl.trim(),
        bashExecutable: bashExecutable.trim() || null,
        lsp: environment.daemonConfig.lsp,
        proxyUrl: proxyUrl.trim() || null,
      })
      const canonicalNote = validateEnvironmentNote(note)
      setEnvironment((current) => ({ ...current, note: canonicalNote, daemonConfig }))
      setDialog(null)
    } catch (error) {
      if (!(error instanceof EnvironmentConfigValidationError)) throw error
      setFormError(t(formErrorKeys[error.field] ?? 'ai.environment.form.invalidConfig'))
    }
  }

  return (
    <section className="screen-body" aria-label="执行环境">
      {present ? <ResourceGrid>
          <ResourceCard
            className="environment-card"
            icon={<Box aria-hidden="true" />}
            title={environment.name}
            subtitle={environment.note ?? undefined}
            badge={<Badge tone="success">就绪</Badge>}
            meta={[[ 'Daemon 版本', environment.daemonVersion ?? '—' ], [ '连接状态', '已连接' ]]}
            actions={(
              <>
                <Button size="compact" aria-label={`安装 / 覆盖 ${environment.name}`} onClick={() => setDialog('install')}>
                  <Download aria-hidden="true" />安装 / 覆盖
                </Button>
                <Button size="compact" variant="secondary" onClick={() => setDialog('manage')}>
                  <SlidersHorizontal aria-hidden="true" />管理
                </Button>
                <Button size="compact" variant="secondary" onClick={openEdit}>
                  <Pencil aria-hidden="true" />编辑
                </Button>
                <Button size="compact" variant="secondary" danger onClick={() => setDialog('delete')}>
                  <Trash2 aria-hidden="true" />删除
                </Button>
              </>
            )}
          />
      </ResourceGrid> : <EmptyState title="没有执行环境示例" />}

      {dialog === 'edit' && (
        <Dialog className="environment-config-modal modal-touch-target" title={`编辑环境：${environment.name}`} onClose={() => setDialog(null)}>
          <form className="modal-card-form" onSubmit={saveExample} noValidate>
            <div className="modal-body env-config-body">
              <div className="form-group">
                <FieldLabel htmlFor="environment-preview-name">环境名称</FieldLabel>
                <TextInput id="environment-preview-name" value={environment.name} readOnly />
              </div>
              <label className="form-group">
                <FieldLabel>备注</FieldLabel>
                <TextInput value={note} maxLength={512} onChange={(event) => setNote(event.target.value)} />
              </label>
              <div className="form-group">
                <FieldLabel required htmlFor="environment-preview-studio-url">Studio 地址</FieldLabel>
                <TextInput id="environment-preview-studio-url" value={studioUrl} placeholder="https://studio.example.com" required onChange={(event) => setStudioUrl(event.target.value)} />
              </div>
              <div className="form-group">
                <FieldLabel htmlFor="environment-preview-bash">Shell 可执行文件</FieldLabel>
                <TextInput id="environment-preview-bash" value={bashExecutable} placeholder="Daemon 默认 Shell" onChange={(event) => setBashExecutable(event.target.value)} />
              </div>
              <div className="form-group">
                <FieldLabel htmlFor="environment-preview-proxy">代理地址</FieldLabel>
                <TextInput id="environment-preview-proxy" value={proxyUrl} placeholder="直连" onChange={(event) => setProxyUrl(event.target.value)} />
              </div>
              {formError && <p className="field-error" role="alert">{formError}</p>}
            </div>
            <div className="modal-footer">
              <Button variant="secondary" onClick={() => setDialog(null)}>取消</Button>
              <Button type="submit">保存示例</Button>
            </div>
          </form>
        </Dialog>
      )}

      {dialog === 'install' && (
        <Dialog className="environment-install-modal modal-touch-target" title="安装 / 覆盖环境" onClose={() => setDialog(null)}>
          <div className="modal-body">
            <p className="modal-body-text">覆盖安装会中断目标主机上正在运行的工具调用，但会保留环境配置和数据。</p>
          </div>
          <div className="modal-footer">
            <Button variant="secondary" onClick={() => setDialog(null)}>关闭</Button>
          </div>
        </Dialog>
      )}

      {dialog === 'manage' && (
        <Dialog className="env-management-modal-card modal-touch-target" title={`管理环境：${environment.name}`} onClose={() => setDialog(null)}>
          <div className="modal-body env-mgmt-body">
            <div className="environment-diagnostics-content">
              <EnvironmentHostSection environment={environment} />
              <QueryClientProvider client={previewQueries}>
                <EnvironmentConfigurationSection environment={environment} />
              </QueryClientProvider>
              <section className="env-mgmt-section" aria-label="环境事件">
                <div className="env-mgmt-section-header"><h3>环境事件</h3></div>
                <div className="env-events-list">
                  <div className="env-event-item">
                    <div className="env-event-header"><span className="env-event-level is-info">信息</span><strong>环境已连接</strong><span className="env-event-time">刚刚</span></div>
                    <div className="env-event-message">Daemon 已连接到 KK Studio。</div>
                  </div>
                </div>
              </section>
            </div>
          </div>
          <div className="modal-footer"><Button variant="secondary" onClick={() => setDialog(null)}>关闭</Button></div>
        </Dialog>
      )}

      {dialog === 'delete' && (
        <ConfirmActionModal
          className="modal-touch-target"
          pending={false}
          onClose={() => setDialog(null)}
          modal={{
            title: '删除环境',
            description: `确认从此预览中移除环境「${environment.name}」？不会发送请求或删除真实数据。`,
            confirmLabel: '删除示例',
            icon: 'delete',
            tone: 'danger',
            onConfirm: () => {
              setPresent(false)
              setDialog(null)
            },
          }}
        />
      )}
    </section>
  )
}
