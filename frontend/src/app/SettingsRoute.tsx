import { ArrowLeft } from 'lucide-react'
import { version } from '../../package.json'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router'
import { AgentsPage } from '@/features/catalog/AgentsPage'
import { ModelsPage } from '@/features/catalog/ModelsPage'
import { ProvidersPage } from '@/features/catalog/ProvidersPage'
import { EnvironmentsPage } from '@/features/environment/EnvironmentsPage'
import { SkillPackagesPage } from '@/features/catalog/skills/SkillPackagesPage'
import { McpServersPage } from '@/features/catalog/mcp/McpServersPage'
import { PluginsTab } from '@/features/extensions/PluginsTab'
import { UsersPage } from '@/features/account/UsersPage'
import { GeneralTab } from '@/features/settings/tabs/GeneralTab'
import { SettingsToolbar } from '@/features/settings/SettingsToolbar'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import { SyncTab } from '@/features/settings/sync/SyncTab'
import { useSystemSettingsEditor } from '@/features/settings/useSystemSettingsEditor'
import { useAccount } from '@/features/account/account-context'
import { OverlayHost } from '@/platform/workbench/WorkbenchSlots'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { requireTranslationKey, useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Select } from '@/shared/ui/controls/Select'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { useUnsavedChangesConfirmation } from '@/shared/ui/overlays/useUnsavedChangesConfirmation'
import '@/features/settings/settings.css'
import './SettingsRoute.css'

const harness = [
  ['agents', 'settings.sections.agents', AgentsPage],
  ['models', 'settings.sections.models', ModelsPage],
  ['providers', 'settings.sections.providers', ProvidersPage],
  ['skill-packages', 'settings.sections.skillPackages', SkillPackagesPage],
  ['environments', 'settings.sections.environments', EnvironmentsPage],
  ['mcp-servers', 'settings.sections.mcpServers', McpServersPage],
] as const

export function SettingsRoute({ reloadPage = () => window.location.reload() }: { reloadPage?: () => void } = {}) {
  const { section = 'preferences' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { user } = useAccount()
  const { t } = useI18n()
  const admin = user?.role === 'ADMIN'
  const editor = useSystemSettingsEditor(admin)
  const serverSections = editor.schema?.sections ?? []
  const from = (location.state as { from?: string } | null)?.from
  const backTo = from?.startsWith('/') && !from.startsWith('//') && !from.startsWith('/settings/') ? from : '/'
  const leave = () => navigate(backTo, { replace: true })
  const { requestClose, confirmation } = useUnsavedChangesConfirmation(editor.dirty, editor.saving, leave)
  const groups = [
    { title: t('settings.groups.interface'), options: [{ value: 'preferences', label: t('settings.sections.preferences') }] },
    ...(admin ? [
      { title: t('settings.groups.harness'), options: harness.map(([value, key]) => ({ value, label: t(key) })) },
      { title: t('settings.groups.system'), options: [
        { value: 'general', label: t('settings.tabs.general') },
        { value: 'users', label: t('settings.sections.users') },
        { value: 'plugins', label: t('settings.tabs.plugins') },
        ...serverSections.map((item) => ({ value: item.key, label: item.key === 'environment' ? t('settings.sections.environmentDefaults') : t(requireTranslationKey(item.labelKey)) })),
        { value: 'sync', label: t('settings.tabs.sync') },
      ] },
    ] : []),
  ]
  const options = groups.flatMap((group) => group.options)
  const valid = options.some((option) => option.value === section)
  const error = editor.loadError ?? editor.schemaError
  if (!valid && (!admin || (!editor.loading && !error))) {
    return <Navigate to="/settings/preferences" state={location.state} replace />
  }
  const back = <IconButton className="settings-back" label={t('settings.back')} onClick={requestClose}><ArrowLeft aria-hidden="true" /></IconButton>
  const category = t('settings.categories')
  const selected = serverSections.find((item) => item.key === section)
  const CatalogPage = harness.find(([value]) => value === section)?.[2]
  let content
  if (section === 'preferences') content = <GeneralTab />
  else if (CatalogPage) content = <CatalogPage><OverlayHost /></CatalogPage>
  else if (section === 'users') content = <UsersPage />
  else if (section === 'plugins') content = <PluginsTab />
  else if (section === 'sync') content = <SyncTab reloadSettings={editor.reload} settingsDirty={editor.dirty} />
  else if (error) content = <div className="settings-error-state"><StateBlock title={error} tone="danger" /><Button onClick={editor.retryLoad}>{t('settings.retry')}</Button></div>
  else if (section === 'general') content = <section className="settings-card settings-deployment">
    <header className="settings-card-header"><h2>{t('settings.about.title')}</h2></header>
    <dl><div><dt>{t('settings.about.version')}</dt><dd>{version}</dd></div>
      <div><dt>{t('settings.about.deploymentAddress')}</dt><dd>{window.location.origin}</dd></div></dl>
  </section>
  else if (!editor.draft || !selected) content = <StateBlock title={t('settings.loading')} />
  else content = <div className="settings-section-stack">
    <SettingsToolbar editor={editor} />
    <SystemSettingsSchemaRenderer schema={{ sections: [selected] }} draft={editor.draft} fieldErrors={editor.fieldErrors} onChange={editor.updateDraft} />
  </div>

  return <main className="settings-route">
    <nav className="settings-route-nav" aria-label={category}>
      {back}
      {groups.map((group) => <div key={group.title}>
        <h2>{group.title}</h2>
        {group.options.map(({ value, label }) => <Link to={`/settings/${value}`} state={location.state} replace key={value} aria-current={value === section ? 'page' : undefined} data-ui-focus="row">{label}</Link>)}
      </div>)}
    </nav>
    <div className="settings-route-mobile">
      {back}
      <Select touch aria-label={category} value={section} options={options} onChange={(value) => navigate(`/settings/${value}`, { state: location.state, replace: true })} />
    </div>
    <section className="settings-route-content" aria-label={t('settings.content')}>
      {section === 'preferences' && admin && error && <div className="settings-error-state"><StateBlock title={error} tone="danger" /><Button onClick={editor.retryLoad}>{t('settings.retry')}</Button></div>}
      {content}
    </section>
    {confirmation}
    <ConflictPresenter className="modal-touch-target" conflict={editor.conflict} onRefresh={reloadPage} onClose={editor.dismissConflict} />
  </main>
}
