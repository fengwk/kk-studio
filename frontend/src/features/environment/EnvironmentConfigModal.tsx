import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { environmentService } from '@/features/environment/environment-service'
import {
  EnvironmentConfigValidationError,
  validateDaemonConfig,
  validateEnvironmentName,
  validateEnvironmentNote,
} from '@/features/environment/environment-config-codec'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { HelpHint } from '@/shared/ui/controls/HelpHint'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n, type InterpolationValues, type TranslationKey } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import type { DaemonLspConfiguration } from '@/shared/api/generated/contracts'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import './environment-config.css'

type Translate = (key: TranslationKey, values?: InterpolationValues) => string

const LSP_EXAMPLE_TEXT = `{
  "jdtls": {
    "command": ["~/.local/share/nvim/mason/bin/jdtls"],
    "extensions": [".java"],
    "rootMarkers": ["pom.xml"]
  }
}`

/** 只展示字段级提示，不把内部路径或异常原文当作产品文案。 */
function fieldMessage(t: Translate, field: string): string {
  if (field === 'name') return t('ai.environment.form.invalidName')
  if (field === 'note') return t('ai.environment.form.invalidNote')
  if (field === 'daemonConfig') return t('ai.environment.form.invalidConfig')
  if (field === 'daemonConfig.studioUrl') return t('ai.environment.form.invalidStudioUrl')
  if (field === 'daemonConfig.bashExecutable') return t('ai.environment.form.invalidBash')
  if (field === 'daemonConfig.proxyUrl') return t('ai.environment.form.invalidProxy')
  if (field.startsWith('daemonConfig.lsp')) return t('ai.environment.form.invalidLsp')
  return t('ai.environment.form.invalidField', { field })
}

const optionalText = (value: string): string | null => (value.trim() ? value : null)

export interface EnvironmentConfigModalProps {
  /** 缺省为创建；提供时编辑该卡片，name 不可改。 */
  environment?: EnvironmentDTO
  onClose: () => void
  /** 创建成功后可继续生成安装命令；编辑成功直接关闭。 */
  onCreated?: (environment: EnvironmentDTO) => void
}

/**
 * 创建与编辑共用一张完整卡片表单：name 仅创建可编辑，其余属性整体替换。
 * 配置校验与后端 DaemonConfigCodec 对齐；保存成功只代表 Studio 已接受配置，
 * 是否在目标 Daemon 生效由卡片配置状态继续呈现。
 */
export function EnvironmentConfigModal({
  environment,
  onClose,
  onCreated,
}: EnvironmentConfigModalProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  const editing = environment != null
  const [name, setName] = useState(environment?.name ?? '')
  const [note, setNote] = useState(environment?.note ?? '')
  const [studioUrl, setStudioUrl] = useState(
    environment?.daemonConfig.studioUrl ?? window.location.origin,
  )
  const [bashExecutable, setBashExecutable] = useState(
    environment?.daemonConfig.bashExecutable ?? '',
  )
  const [proxyUrl, setProxyUrl] = useState(environment?.daemonConfig.proxyUrl ?? '')
  const [lspEnabled, setLspEnabled] = useState(environment?.daemonConfig.lsp != null)
  const [servers, setServers] = useState(
    environment?.daemonConfig.lsp
      ? JSON.stringify(environment.daemonConfig.lsp.servers, null, 2)
      : '',
  )
  const [version, setVersion] = useState(environment?.version ?? '')
  const [error, setError] = useState<string | null>(null)
  const [errorField, setErrorField] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)

  function readDaemonConfig() {
    let lsp: DaemonLspConfiguration | null = null
    if (lspEnabled) {
      let parsed: unknown
      try {
        parsed = JSON.parse(servers)
      } catch {
        throw new EnvironmentConfigValidationError('daemonConfig.lsp.servers')
      }
      lsp = { servers: parsed as DaemonLspConfiguration['servers'] }
    }
    return validateDaemonConfig({
      studioUrl: studioUrl.trim(),
      bashExecutable: optionalText(bashExecutable),
      lsp,
      proxyUrl: proxyUrl.trim() ? proxyUrl.trim() : null,
    })
  }

  const saveMutation = useMutation({
    mutationFn: async () => {
      const canonicalNote = validateEnvironmentNote(note)
      const daemonConfig = readDaemonConfig()
      if (environment) {
        return environmentService.updateEnvironment(environment.id, {
          expectedVersion: version,
          note: canonicalNote,
          daemonConfig,
        })
      }
      const created = await environmentService.createEnvironment({
        name: validateEnvironmentName(name.trim()),
        note: canonicalNote,
        daemonConfig,
      })
      // 创建响应含一次性 token；UI 只继续生成安装命令，绝不把凭据放进缓存或表单。
      return { ...created, registrationToken: null }
    },
    onSuccess: (card) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
      if (environment) {
        onClose()
      } else {
        onCreated?.(card)
      }
    },
    onError: (err: unknown) => {
      if (err instanceof EnvironmentConfigValidationError) {
        setErrorField(err.field)
        setError(fieldMessage(t, err.field))
        return
      }
      if (environment && isConflictError(err)) {
        setConflict(presentConflict(err))
        return
      }
      setErrorField(null)
      setError(err instanceof Error ? err.message : String(err))
    },
  })

  /** CAS 冲突刷新只更新期望版本，绝不替换用户草稿。 */
  async function refreshVersion() {
    if (!environment) return
    try {
      const fresh = await environmentService.getEnvironment(environment.id)
      setVersion(fresh.version)
      setConflict(null)
    } catch (err) {
      setConflict(null)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  function submit(event: React.FormEvent) {
    event.preventDefault()
    if (saveMutation.isPending) return
    setError(null)
    setErrorField(null)
    saveMutation.mutate()
  }

  return (
    <Dialog
      className="modal-touch-target environment-config-modal"
      title={
        environment
          ? t('ai.environment.form.editTitle', { name: environment.name })
          : t('ai.environment.create')
      }
      pending={saveMutation.isPending}
      onClose={onClose}
    >
      <form className="modal-card-form" onSubmit={submit} noValidate>
        <div className="modal-body env-config-body">
          <p className="field-label-row env-config-apply-note">
            <span>{t('ai.environment.form.applyNotice')}</span>
            <HelpHint label={t('shared.helpAbout', { topic: t('ai.environment.form.applyNotice') })}>
              {t('ai.environment.form.hotUpdateHelp')}
            </HelpHint>
          </p>

          {environment ? (
            <div className="form-group">
              <label className="field-label" htmlFor={`${id}-name`}>
                {t('ai.environment.name')}
              </label>
              <TextInput id={`${id}-name`} value={environment.name} readOnly disabled />
              <p className="field-help">{t('ai.environment.form.nameImmutable')}</p>
            </div>
          ) : (
            <label className="form-group">
              <FieldLabel required>{t('ai.environment.name')}</FieldLabel>
              <TextInput
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t('ai.environment.namePlaceholder')}
                maxLength={64}
                required
                autoFocus
                invalid={errorField === 'name'}
              />
            </label>
          )}

          <label className="form-group">
            <FieldLabel>{t('ai.environment.form.note')}</FieldLabel>
            <TextInput
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder={t('ai.environment.form.notePlaceholder')}
              maxLength={512}
              invalid={errorField === 'note'}
            />
          </label>

          <div className="form-group">
            <label className="field-label" htmlFor={`${id}-studio-url`}>
              <FieldLabel required>{t('ai.environment.form.studioUrl')}</FieldLabel>
            </label>
            <TextInput
              id={`${id}-studio-url`}
              value={studioUrl}
              onChange={(e) => setStudioUrl(e.target.value)}
              placeholder={t('ai.environment.form.studioUrlPlaceholder')}
              required
              invalid={errorField === 'daemonConfig.studioUrl'}
            />
            <p className="field-help">{t('ai.environment.form.studioUrlHelp')}</p>
          </div>

          <div className="form-group">
            <label className="field-label" htmlFor={`${id}-bash`}>
              <FieldLabel>{t('ai.environment.form.bashExecutable')}</FieldLabel>
            </label>
            <TextInput
              id={`${id}-bash`}
              value={bashExecutable}
              onChange={(e) => setBashExecutable(e.target.value)}
              placeholder="/bin/bash"
              invalid={errorField === 'daemonConfig.bashExecutable'}
            />
            <p className="field-help">{t('ai.environment.form.bashHelp')}</p>
          </div>

          <div className="form-group">
            <div className="field-label-row">
              <Checkbox
                checked={lspEnabled}
                onChange={setLspEnabled}
                label={t('ai.environment.form.lsp')}
                invalid={errorField?.startsWith('daemonConfig.lsp') ?? false}
              />
              <HelpHint label={t('shared.helpAbout', { topic: t('ai.environment.form.lsp') })}>
                {t('ai.environment.form.lspHelp')}
              </HelpHint>
            </div>
            {lspEnabled ? (
              <div className="env-config-lsp">
                <TextArea
                  rows={8}
                  value={servers}
                  onChange={(e) => setServers(e.target.value)}
                  placeholder={LSP_EXAMPLE_TEXT}
                  aria-label="LSP servers"
                  aria-describedby={`${id}-lsp-help`}
                  invalid={errorField?.startsWith('daemonConfig.lsp') ?? false}
                  spellCheck={false}
                />
                <p id={`${id}-lsp-help`} className="field-help">
                  {t('ai.environment.form.lspHelp')}
                </p>
              </div>
            ) : null}
          </div>

          <div className="form-group">
            <div className="field-label-row">
              <label className="field-label" htmlFor={`${id}-proxy-url`}>
                {t('ai.environment.form.proxyUrl')}
              </label>
              <HelpHint
                label={t('shared.helpAbout', { topic: t('ai.environment.form.proxyUrl') })}
              >
                {t('ai.environment.form.proxyHelp')}
              </HelpHint>
            </div>
            <TextInput
              id={`${id}-proxy-url`}
              value={proxyUrl}
              onChange={(e) => setProxyUrl(e.target.value)}
              placeholder={t('ai.environment.form.proxyPlaceholder')}
              invalid={errorField === 'daemonConfig.proxyUrl'}
            />
            <p className="field-help">{t('ai.environment.form.proxyHelp')}</p>
          </div>

          {error ? (
            <p className="field-error" role="alert">
              {error}
            </p>
          ) : null}
        </div>
        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose} disabled={saveMutation.isPending}>
            {t('shared.cancel')}
          </Button>
          <Button type="submit" loading={saveMutation.isPending}>
            {editing ? t('ai.environment.form.save') : t('ai.environment.form.create')}
          </Button>
        </div>
      </form>
      <ConflictPresenter
        className="modal-touch-target"
        conflict={conflict}
        onClose={() => setConflict(null)}
        onRefresh={() => {
          void refreshVersion()
        }}
      />
    </Dialog>
  )
}
