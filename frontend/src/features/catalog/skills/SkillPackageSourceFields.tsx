import type { Dispatch, RefObject, SetStateAction } from 'react'
import { GitBranch, Upload } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { formatByteCount as formatBytes } from '@/shared/lib/format-bytes'
import { useI18n } from '@/shared/i18n'
import type { SkillPackageFormState } from './skill-package-create-form'

/**
 * 新建技能包的来源与字段：两个来源共享包名与描述，各自再有一组专属字段。
 * 受控输入，编辑时整体替换所属字段。
 */
export function SkillPackageSourceFields({
  form,
  setForm,
  busy,
  fileInputRef,
}: {
  form: SkillPackageFormState
  setForm: Dispatch<SetStateAction<SkillPackageFormState>>
  busy: boolean
  fileInputRef: RefObject<HTMLInputElement | null>
}) {
  const { t } = useI18n()

  function edit(patch: Partial<SkillPackageFormState>) {
    setForm((current) => ({ ...current, ...patch }))
  }

  return (
    <Tabs
      ariaLabel={t('ai.skillPackages.source')}
      activeId={form.source === 'GIT' ? 'git' : 'upload'}
      onChange={(id) => {
        if (!busy) {
          edit({ source: id === 'git' ? 'GIT' : 'UPLOAD' })
        }
      }}
      className="skill-source-tabs"
      panelClassName="skill-source-panel"
      tabs={[
        {
          id: 'git',
          label: (
            <span>
              <GitBranch aria-hidden="true" />
              {t('ai.skillPackages.source.GIT')}
            </span>
          ),
        },
        {
          id: 'upload',
          label: (
            <span>
              <Upload aria-hidden="true" />
              {t('ai.skillPackages.source.UPLOAD')}
            </span>
          ),
        },
      ]}
    >
      <label className="form-group">
        <FieldLabel required>{t('ai.skillPackages.name')}</FieldLabel>
        <TextInput
          value={form.packageName}
          placeholder="my-skills"
          disabled={busy}
          required
          onChange={(event) => edit({ packageName: event.target.value })}
        />
      </label>

      <label className="form-group">
        <FieldLabel>{t('ai.skillPackages.descriptionLabel')}</FieldLabel>
        <TextArea
          value={form.description}
          placeholder={t('ai.catalog.form.descriptionPlaceholder')}
          rows={2}
          disabled={busy}
          onChange={(event) => edit({ description: event.target.value })}
        />
      </label>

      {form.source === 'GIT' ? (
        <>
          <label className="form-group">
            <FieldLabel required>{t('ai.skillPackages.repositoryUrl')}</FieldLabel>
            <TextInput
              value={form.repositoryUrl}
              placeholder="https://github.com/org/repo.git"
              disabled={busy}
              required
              onChange={(event) => edit({ repositoryUrl: event.target.value })}
            />
          </label>

          <label className="form-group">
            <FieldLabel required>{t('ai.skillPackages.branch')}</FieldLabel>
            <TextInput
              value={form.branch}
              placeholder="main"
              disabled={busy}
              required
              onChange={(event) => edit({ branch: event.target.value })}
            />
          </label>

          <label className="form-group">
            <FieldLabel>{t('ai.skillPackages.token')}</FieldLabel>
            <TextInput
              type="password"
              autoComplete="off"
              value={form.token}
              placeholder={t('ai.skillPackages.tokenPlaceholder')}
              disabled={busy}
              onChange={(event) => edit({ token: event.target.value })}
            />
            <small>{t('ai.skillPackages.tokenCreateHint')}</small>
          </label>
        </>
      ) : (
        <>
          <div className="form-group">
            <FieldLabel required>{t('ai.skillPackages.archiveFile')}</FieldLabel>
            <div className="skill-file-picker">
              <Button variant="secondary" disabled={busy} onClick={() => fileInputRef.current?.click()}>
                {t('ai.skillPackages.chooseFile')}
              </Button>
              <span className="skill-file-name" title={form.file?.name}>
                {form.file
                  ? `${form.file.name} · ${formatBytes(form.file.size)}`
                  : t('ai.skillPackages.noFile')}
              </span>
              <input
                ref={fileInputRef}
                type="file"
                accept=".zip,.tar.gz,application/zip,application/gzip"
                className="skill-file-input"
                aria-label={t('ai.skillPackages.archiveFile')}
                disabled={busy}
                onChange={(event) => {
                  edit({ file: event.target.files?.[0] ?? null })
                  event.target.value = ''
                }}
              />
            </div>
            <small>{t('ai.skillPackages.archiveFileHint')}</small>
          </div>

          <label className="form-group">
            <FieldLabel required>{t('ai.skillPackages.packageVersion')}</FieldLabel>
            <TextInput
              value={form.packageVersion}
              placeholder="1.0.0"
              disabled={busy}
              required
              onChange={(event) => edit({ packageVersion: event.target.value })}
            />
          </label>
        </>
      )}
    </Tabs>
  )
}
