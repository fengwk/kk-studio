import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Package, ServerCog } from 'lucide-react'
import { ProviderForm } from '@/features/catalog/AiProviderResourceForm'
import { toEditableProviderUpdate, toProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import { SkillPackageSourceFields } from '@/features/catalog/skills/SkillPackageSourceFields'
import { emptySkillPackageForm } from '@/features/catalog/skills/skill-package-create-form'
import { CreateMcpServerDialog } from '@/features/catalog/mcp/CreateMcpServerDialog'
import { EditMcpServerDialog } from '@/features/catalog/mcp/EditMcpServerDialog'
import { validateCreateMcpServer, validateUpdateMcpServer } from '@/features/catalog/mcp/mcp-server-form-validation'
import { emptyCreateMcpServerForm, pendingEditMcpServerForm, type EditMcpServerFormState } from '@/features/catalog/mcp/mcp-server-forms'
import { validateMcpUrl } from '@/features/catalog/mcp/mcp-utils'
import type { McpServerSummaryDTO, ProviderDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Button } from '@/shared/ui/controls/Button'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useUnsavedChangesConfirmation } from '@/shared/ui/overlays/useUnsavedChangesConfirmation'
import { EditButton } from './EditButton'
import '@/features/catalog/ai-console-resource-editor.css'
import '@/features/catalog/skills/skill-packages.css'
import '@/features/catalog/mcp/mcp-servers.css'

const exampleProvider: ProviderDTO = {
  name: 'example-provider', providerType: 'openai', baseUrl: 'https://api.example.invalid/v1',
  credential: { configured: false }, modelCallTimeoutMillis: '1800000', modelCallIdleTimeoutMillis: '120000',
  modelHttpRetryStatusCodes: null, promptCacheRetentions: [], version: '1', createdAt: 0, updatedAt: 0,
}

function usePreviewDialog() {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    const close = () => setOpen(false)
    document.addEventListener('htmlview:change', close)
    return () => document.removeEventListener('htmlview:change', close)
  }, [])
  return { open, openDialog: () => setOpen(true), closeDialog: () => setOpen(false) }
}

function PreviewPage({ children }: { children: ReactNode }) {
  return <div className="screen-body">
    <ResourceGrid>{children}</ResourceGrid>
  </div>
}

function ProviderEditor({ provider, onSave, onClose }: {
  provider: ProviderDTO; onSave: (provider: ProviderDTO) => void; onClose: () => void
}) {
  const { t } = useI18n()
  const [baseline] = useState(() => toProviderDraft(provider))
  const [draft, setDraft] = useState(baseline)
  const [baseUrlError, setBaseUrlError] = useState('')
  const [error, setError] = useState('')
  const { requestClose, confirmation } = useUnsavedChangesConfirmation(
    JSON.stringify(draft) !== JSON.stringify(baseline), false, onClose,
  )

  function submit(event: FormEvent) {
    event.preventDefault()
    const url = draft.baseUrl.trim()
    if (url) {
      const result = validateMcpUrl(url)
      if (!result.valid) {
        setBaseUrlError(result.error ?? t('ai.mcp.validation.urlInvalid'))
        return
      }
    }
    try {
      const { credential: _credential, ...data } = toEditableProviderUpdate(draft)
      onSave({ ...provider, ...data, updatedAt: Date.now() })
    } catch (error) {
      setError(toUserFacingErrorMessage(error))
    }
  }

  return <>
    <Dialog title={t('ai.catalog.editProviderTitle', { name: provider.name })}
      className="resource-modal-card modal-touch-target" onClose={requestClose}>
      <form className="modal-card-form" onSubmit={submit} noValidate>
        <div className="modal-body">
          <ProviderForm draft={draft} mode="edit" fieldErrors={{ baseUrl: baseUrlError }} onChange={(next) => {
            setDraft({ ...next, credential: '', clearCredential: false }); setBaseUrlError(''); setError('')
          }} />
          {error && <p className="field-error" role="alert">{error}</p>}
        </div>
        <div className="modal-footer">
          <Button variant="secondary" onClick={requestClose}>{t('shared.cancel')}</Button>
          <Button type="submit">{t('ai.catalog.action.saveChanges')}</Button>
        </div>
      </form>
    </Dialog>
    {confirmation}
  </>
}

export function ProviderSettingsPreview() {
  const { t } = useI18n()
  const { open, openDialog, closeDialog } = usePreviewDialog()
  const [provider, setProvider] = useState(exampleProvider)
  return <>
    <PreviewPage>
      <ResourceCard icon={<ServerCog />} title={provider.name} subtitle={provider.description ?? undefined}
        meta={[
          [t('ai.catalog.card.type'), provider.providerType],
          [t('ai.catalog.form.baseUrl'), provider.baseUrl],
          [t('ai.catalog.card.httpRetry'), provider.modelHttpRetryStatusCodes === null
            ? t('ai.catalog.card.httpRetryInherit')
            : provider.modelHttpRetryStatusCodes.join(', ') || t('ai.catalog.form.httpRetryCustom')],
        ]}
        actions={<EditButton name={provider.name} onClick={openDialog} />} />
    </PreviewPage>
    {open && <ProviderEditor provider={provider} onClose={closeDialog} onSave={(next) => { setProvider(next); closeDialog() }} />}
  </>
}

function SkillPackageEditor({ title, onClose }: { title: string; onClose: () => void }) {
  const { t } = useI18n()
  const [form, setForm] = useState(emptySkillPackageForm)
  const fileInputRef = useRef<HTMLInputElement>(null)
  return <Dialog title={title} className="resource-modal-card skill-package-modal modal-touch-target" onClose={onClose}>
    <div className="modal-body">
      <SkillPackageSourceFields form={form} setForm={setForm} busy={false} fileInputRef={fileInputRef} />
    </div>
    <div className="modal-footer"><Button variant="secondary" onClick={onClose}>{t('shared.close')}</Button></div>
  </Dialog>
}

export function SkillPackageSettingsPreview() {
  const { locale } = useI18n()
  const { open, openDialog, closeDialog } = usePreviewDialog()
  const title = locale === 'zh-CN' ? '技能包表单预览' : 'Skill package form preview'
  return <>
    <PreviewPage>
      <ResourceCard icon={<Package />} title={title} actions={<Button onClick={openDialog}>
        {locale === 'zh-CN' ? '预览表单' : 'Preview form'}
      </Button>} />
    </PreviewPage>
    {open && <SkillPackageEditor title={title} onClose={closeDialog} />}
  </>
}

type LocalMcpServer = McpServerSummaryDTO & { url: string }
const noOnlineMcpActions = () => undefined // The offline edit dialog renders neither discovery nor loading/retry.

export function McpServerSettingsPreview() {
  const { t, locale } = useI18n()
  const { open, openDialog, closeDialog } = usePreviewDialog()
  const [servers, setServers] = useState<LocalMcpServer[]>([])
  const [form, setForm] = useState(emptyCreateMcpServerForm)
  const [edit, setEdit] = useState<EditMcpServerFormState | null>(null)

  function startEdit(server: LocalMcpServer) {
    const { url, timeoutMillis, enabled, version } = server
    setEdit({ ...pendingEditMcpServerForm(server), url, loading: false, loadedVersion: version,
      loaded: { url, timeoutMillis, enabled, headersConfigured: false } })
    openDialog()
  }

  function create(event: FormEvent) {
    event.preventDefault()
    const result = validateCreateMcpServer(form)
    if (!result.ok) return setForm({ ...form, error: result.message })
    const { name, url, timeoutMillis, enabled } = result.value
    if (servers.some((server) => server.name === name)) {
      return setForm({ ...form, error: locale === 'zh-CN' ? '该服务名称已存在。' : 'This server name is already in use.' })
    }
    const now = Date.now()
    setServers([...servers, { name, url, timeoutMillis, enabled, version: '1', createdAt: now, updatedAt: now,
      discoveryStatus: 'UNVERIFIED', toolCount: 0 }])
    closeDialog()
  }

  function saveEdit(event: FormEvent) {
    event.preventDefault()
    if (!edit) return
    const result = validateUpdateMcpServer(edit)
    if (!result.ok) return setEdit({ ...edit, error: result.message })
    const { url, timeoutMillis, enabled } = result.value
    setServers(servers.map((server) => server.name !== edit.server.name ? server : {
      ...server, url, timeoutMillis, enabled, updatedAt: Date.now(), version: String(Number(server.version) + 1),
    }))
    closeDialog()
  }

  return <>
    <PreviewPage>
      <CreateCard title={t('ai.mcp.create')} subtitle={locale === 'zh-CN' ? '添加离线示例，不会连接服务。' : 'Add an offline example without connecting.'}
        onClick={() => { setForm(emptyCreateMcpServerForm()); setEdit(null); openDialog() }} />
      {servers.map((server) => <ResourceCard key={server.name} icon={<ServerCog />} title={server.name}
        meta={[[t('ai.mcp.url'), server.url], [t('ai.mcp.timeout'), `${server.timeoutMillis} ms`],
          [t('ai.mcp.enabledState'), t(server.enabled ? 'ai.mcp.enabled' : 'ai.mcp.disabled')]]}
        actions={<EditButton name={server.name} onClick={() => startEdit(server)} />} />)}
    </PreviewPage>
    {open && (edit
      ? <EditMcpServerDialog form={edit} saving={false} onChange={setEdit} onSubmit={saveEdit}
          onRequestClose={closeDialog} onDiscover={noOnlineMcpActions} onRetryLoad={noOnlineMcpActions} />
      : <CreateMcpServerDialog form={form} pending={false} onChange={setForm} onSubmit={create} onRequestClose={closeDialog} />)}
  </>
}
