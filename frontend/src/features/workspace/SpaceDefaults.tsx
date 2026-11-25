import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { request } from '@/shared/api/client'
import type { ChatSpaceDTO, ChatSpacePatchDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'

export function SpaceDefaults({ space, onClose, save }: {
  space: ChatSpaceDTO; onClose: () => void; save: (body: ChatSpacePatchDTO) => Promise<ChatSpaceDTO>
}) {
  const { t } = useI18n()
  const agents = useQuery({ queryKey: ['workspace-agent-summaries'], queryFn: ({ signal }) => request('GET /api/platform/agent-summaries', {}, { signal }) })
  const environments = useQuery({ queryKey: ['workspace-environment-summaries'], queryFn: ({ signal }) => request('GET /api/platform/environment-summaries', {}, { signal }) })
  const [agent, setAgent] = useState(space.defaultAgentName ?? '')
  const [environment, setEnvironment] = useState(space.defaultEnvironmentId ?? '')
  const [yolo, setYolo] = useState(space.defaultYoloEnabled)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const none = { label: t('workspace.none'), value: '' }
  return <Dialog title={t('workspace.spaceDefaults')} pending={pending} onClose={onClose}>
    <form className="modal-body workspace-default-form" onSubmit={event => {
      event.preventDefault()
      if (pending) return
      setPending(true)
      void save({ expectedVersion: space.version, defaultAgentName: agent || null, defaultEnvironmentId: environment || null, defaultYoloEnabled: yolo })
        .then(onClose).catch((failure: Error) => setError(failure.message)).finally(() => setPending(false))
    }}>
      <FieldLabel htmlFor="workspace-default-agent">{t('workspace.agent')}</FieldLabel>
      <Select id="workspace-default-agent" aria-label={t('workspace.agent')} value={agent} onChange={setAgent}
        disabled={!agents.data || pending} options={[none, ...(agents.data ?? []).map(value => ({ label: value.name, value: value.name }))]} />
      <FieldLabel htmlFor="workspace-default-environment">{t('workspace.environment')}</FieldLabel>
      <Select id="workspace-default-environment" aria-label={t('workspace.environment')} value={environment} onChange={setEnvironment}
        disabled={!environments.data || pending} options={[none, ...(environments.data ?? []).map(value => ({ label: value.name, value: value.id }))]} />
      <Checkbox checked={yolo} disabled={pending} onChange={setYolo} label={t('workspace.yolo')} />
      {(error || agents.isError || environments.isError) && <div role="alert" className="workspace-field-error">{error || t('shared.requestFailed')}</div>}
      <div className="modal-footer"><Button variant="secondary" onClick={onClose} disabled={pending}>{t('shared.cancel')}</Button>
        <Button type="submit" loading={pending} disabled={!agents.data || !environments.data}>{t('shared.confirm')}</Button></div>
    </form>
  </Dialog>
}
