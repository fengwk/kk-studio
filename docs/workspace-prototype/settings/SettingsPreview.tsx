import { ArrowLeft, ChevronDown, Plug } from 'lucide-react'
import { useRef, useState, useSyncExternalStore } from 'react'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { BrowserNotificationsCard } from '@/features/settings/browser-notifications-card'
import { SettingsCard } from '@/features/settings/settings-primitives'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Badge } from '@/shared/ui/data/Badge'
import { AgentCardsPreview, ModelCardsPreview } from './catalog-editor-previews'
import { EnvironmentSettingsPreview } from './environment-settings-preview'
import { McpServerSettingsPreview, ProviderSettingsPreview, SkillPackageSettingsPreview } from './extra-config-previews'
import { SettingsSyncPreview, SystemSettingsPreview, usePreviewSettings, type SystemSection } from './system-settings-preview'
import { getSession, subscribeSession } from '../workspace/session'
import { UsersPreview } from './UsersPreview'
import './settings-preview.css'

const systemSections = [
  ['aiRuntime', 'AI 运行时'], ['tool', '工具与权限'], ['environment', '环境默认值'],
  ['backendNetwork', '后端网络'], ['storageMedia', '存储与媒体'], ['advanced', '高级'],
] as const satisfies ReadonlyArray<readonly [SystemSection, string]>

const groups = [
  { label: '界面', items: [['preferences', '偏好设置']] },
  { label: 'Harness', items: [
    ['agent', 'Agent'], ['model', '模型'], ['provider', '提供商'], ['skills', '技能包'],
    ['hosts', '环境'], ['mcp', 'MCP 服务'],
  ] },
  { label: '系统', items: [
    ['general', '常规'], ['users', '用户'], ['plugins', '插件'], ...systemSections, ['sync', '同步'],
  ] },
] as const
type Page = typeof groups[number]['items'][number][0]

const catalogPages = {
  agent: AgentCardsPreview,
  model: ModelCardsPreview,
  provider: ProviderSettingsPreview,
  skills: SkillPackageSettingsPreview,
  hosts: EnvironmentSettingsPreview,
  mcp: McpServerSettingsPreview,
} as const

const plugins = [
  { name: 'ComfyUI', connection: '未连接', capabilities: '运行 ComfyUI 工作流' },
  { name: 'Media', capabilities: '本地图像裁剪' },
  { name: 'MiniMax Mavis', capabilities: '语音转写、网页提取' },
  { name: 'OpenCLI', capabilities: '图像与视频生成' },
]

function PluginsPreview() {
  return <ResourceGrid>{plugins.map((plugin) => <ResourceCard key={plugin.name} icon={<Plug aria-hidden="true" />}
    title={plugin.name} subtitle={plugin.capabilities}
    badge={plugin.connection && <Badge tone="warning">{plugin.connection}</Badge>} />)}</ResourceGrid>
}

function PreferencesPreview() {
  return <div className="screen-body">
    <div className="settings-panel settings-section-stack">
      <BrowserPreferencesProvider><BrowserNotificationsCard /></BrowserPreferencesProvider>
      <SettingsCard title="工作区快捷键">
        <dl className="x-settings-facts">
          <div><dt>上下选择资源</dt><dd><kbd>j</kbd> / <kbd>k</kbd></dd></div>
          <div><dt>收起或展开目录</dt><dd><kbd>h</kbd> / <kbd>l</kbd></dd></div>
          <div><dt>在窗格标题向右或向下拆分</dt><dd><kbd>v</kbd> / <kbd>s</kbd></dd></div>
          <div><dt>重命名或删除</dt><dd><kbd>r</kbd> / <kbd>d</kbd></dd></div>
          <div><dt>打开目录树选中项的操作菜单</dt><dd><kbd>Shift</kbd> + <kbd>F10</kbd></dd></div>
        </dl>
      </SettingsCard>
    </div>
  </div>
}

function GeneralPreview() {
  return <div className="screen-body">
    <div className="settings-panel">
      <SettingsCard title="关于 KK Studio">
        <dl className="x-settings-facts">
          <div><dt>设计版本</dt><dd>1.3.0</dd></div>
          <div><dt>运行模式</dt><dd>离线预览</dd></div>
        </dl>
      </SettingsCard>
    </div>
  </div>
}

export function ProviderLoadingPreview() {
  return <div className="screen-body">
    <ResourceCardSkeleton label="正在加载提供商…" />
  </div>
}

export function SettingsPreview() {
  const { userId, users } = useSyncExternalStore(subscribeSession, getSession)
  const admin = users.some((user) => user.id === userId && !user.disabled && user.role === 'ADMIN')
  const allowedGroups = admin ? groups : groups.slice(0, 1)
  const [page, setActive] = useState<Page>('preferences')
  const active = admin ? page : 'preferences'
  if (page !== active) setActive(active)
  const [visited, setVisited] = useState<ReadonlySet<Page>>(() => new Set(['preferences']))
  const system = systemSections.find(([id]) => id === active)
  const contentRef = useRef<HTMLElement>(null)
  const systemForm = usePreviewSettings()

  function navigate(page: Page) {
    if (page === active) return
    if (contentRef.current) contentRef.current.scrollTop = 0
    setActive(page)
    setVisited((previous) => previous.has(page) ? previous : new Set([...previous, page]))
  }

  return <div className="x-settings-shell" data-note="系统设置只保存在本次预览中。同步只读写原型导出的配置文件，不连接 Studio。环境操作不安装或连接主机。">
    <aside className="x-settings-sidebar">
      <IconButton className="x-settings-back" label="返回对话" title="返回对话" onClick={() => {
        const { htmlviewUI } = window as Window & { htmlviewUI?: { go: (screen: string) => void } }
        htmlviewUI?.go('scr-work')
      }}><ArrowLeft aria-hidden="true" /></IconButton>
      <nav className="x-settings-navigation" aria-label="设置分类">
        {allowedGroups.map((group) => <div key={group.label} className="x-settings-nav-group">
          <p>{group.label}</p>
          {group.items.map(([id, label]) => <button key={id} type="button"
            aria-current={active === id ? 'page' : undefined} onClick={() => navigate(id)}>{label}</button>)}
        </div>)}
      </nav>
      <div className="x-settings-mobile-menu">
        <select aria-label="设置分类" value={active}
          onChange={(event) => navigate(event.target.value as Page)}>
          {allowedGroups.map((group) => <optgroup key={group.label} label={group.label}>
            {group.items.map(([id, label]) => <option key={id} value={id}>{label}</option>)}
          </optgroup>)}
        </select>
        <ChevronDown aria-hidden="true" size={16} />
      </div>
    </aside>
    <main ref={contentRef} className="x-settings-content" aria-label="设置">
      {active === 'preferences' && <PreferencesPreview />}
      {active === 'general' && <GeneralPreview />}
      {active === 'users' && <UsersPreview users={users} />}
      {active === 'sync' && <SettingsSyncPreview dirty={systemForm.dirty} reloadSettings={systemForm.reloadSettings} />}
      {active === 'plugins' && <div className="screen-body"><PluginsPreview /></div>}
      {(Object.keys(catalogPages) as (keyof typeof catalogPages)[]).map((page) => {
        if (!admin || !visited.has(page)) return null
        const Content = catalogPages[page]
        return <div key={page} hidden={active !== page}><Content /></div>
      })}
      <SystemSettingsPreview sectionKey={system?.[0]} form={systemForm} />
    </main>
  </div>
}
