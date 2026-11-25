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
import '@/features/settings/settings.css'
import './SettingsRoute.css'

const harness = [
  ['agents', 'Agent', 'Agent', AgentsPage],
  ['models', '模型', 'Models', ModelsPage],
  ['providers', '提供商', 'Providers', ProvidersPage],
  ['skill-packages', '技能包', 'Skill packages', SkillPackagesPage],
  ['environments', '环境', 'Environments', EnvironmentsPage],
  ['mcp-servers', 'MCP 服务', 'MCP servers', McpServersPage],
] as const

export function SettingsRoute({ reloadPage = () => window.location.reload() }: { reloadPage?: () => void } = {}) {
  const { section = 'preferences' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { user } = useAccount()
  const { t, locale } = useI18n()
  const zh = locale === 'zh-CN'
  const admin = user?.role === 'ADMIN'
  const editor = useSystemSettingsEditor(admin)
  const serverSections = editor.schema?.sections ?? []
  const groups = [
    { title: zh ? '界面' : 'Interface', options: [{ value: 'preferences', label: zh ? '偏好' : 'Preferences' }] },
    ...(admin ? [
      { title: 'Harness', options: harness.map(([value, cn, en]) => ({ value, label: zh ? cn : en })) },
      { title: zh ? '系统' : 'System', options: [
        { value: 'general', label: t('settings.tabs.general') },
        { value: 'users', label: zh ? '用户' : 'Users' },
        { value: 'plugins', label: t('settings.tabs.plugins') },
        ...serverSections.map((item) => ({ value: item.key, label: item.key === 'environment' ? (zh ? '环境默认值' : 'Environment defaults') : t(requireTranslationKey(item.labelKey)) })),
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
  const from = (location.state as { from?: string } | null)?.from
  const backTo = from?.startsWith('/') && !from.startsWith('//') && !from.startsWith('/settings/') ? from : '/'
  const back = <IconButton className="settings-back" label={zh ? '返回' : 'Back'} onClick={() => {
    if (!editor.dirty || window.confirm(zh ? '放弃未保存的更改？' : 'Discard unsaved changes?')) navigate(backTo, { replace: true })
  }}><ArrowLeft aria-hidden="true" /></IconButton>
  const category = zh ? '设置分类' : 'Settings categories'
  const selected = serverSections.find((item) => item.key === section)
  const CatalogPage = harness.find(([value]) => value === section)?.[3]
  let content
  if (section === 'preferences') content = <GeneralTab />
  else if (CatalogPage) content = <CatalogPage><OverlayHost /></CatalogPage>
  else if (section === 'users') content = <UsersPage />
  else if (section === 'plugins') content = <PluginsTab />
  else if (section === 'sync') content = <SyncTab reloadSettings={editor.reload} settingsDirty={editor.dirty} />
  else if (error) content = <div className="settings-error-state"><StateBlock title={error} tone="danger" /><Button onClick={editor.retryLoad}>{t('settings.retry')}</Button></div>
  else if (section === 'general') content = <section className="settings-card settings-deployment">
    <header className="settings-card-header"><h2>{zh ? '关于 KK Studio' : 'About KK Studio'}</h2></header>
    <dl><div><dt>{zh ? 'Studio 版本' : 'Studio version'}</dt><dd>{version}</dd></div>
      <div><dt>{zh ? '部署地址' : 'Deployment address'}</dt><dd>{window.location.origin}</dd></div></dl>
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
    <section className="settings-route-content" aria-label={zh ? '设置内容' : 'Settings content'}>
      {section === 'preferences' && admin && error && <div className="settings-error-state"><StateBlock title={error} tone="danger" /><Button onClick={editor.retryLoad}>{t('settings.retry')}</Button></div>}
      {content}
    </section>
    <ConflictPresenter className="modal-touch-target" conflict={editor.conflict} onRefresh={reloadPage} onClose={editor.dismissConflict} />
  </main>
}
