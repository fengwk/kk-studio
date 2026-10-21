import { useState } from 'react'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'

type LoadState =
  | { status: 'idle' }
  | { status: 'loading'; providerName: string }
  | { status: 'loaded'; providerName: string; ids: string[] }
  | { status: 'failed'; providerName: string; message: string }

function failureMessage(error: unknown): string {
  if (error instanceof ApiError && typeof error.errors?.detail === 'string') {
    return `${error.message} ${error.errors.detail}`
  }
  return error instanceof Error ? error.message : String(error)
}

/**
 * 从已保存的 Provider 拉取上游模型 ID，填充给 Model ID 输入框关联的 datalist。
 * 结果只属于拉取时的 Provider；切换 Provider 后需要重新拉取。
 */
export function RemoteModelIdPicker({
  listId,
  providerName,
  load = agentService.listRemoteModels,
}: {
  listId: string
  providerName: string
  load?: (providerName: string) => Promise<string[]>
}) {
  const { t } = useI18n()
  const [state, setState] = useState<LoadState>({ status: 'idle' })
  const current = state.status !== 'idle' && state.providerName === providerName ? state : null

  async function fetchIds() {
    const target = providerName
    setState({ status: 'loading', providerName: target })
    try {
      const ids = await load(target)
      setState({ status: 'loaded', providerName: target, ids })
    } catch (error) {
      setState({ status: 'failed', providerName: target, message: failureMessage(error) })
    }
  }

  return (
    <div className="remote-model-ids">
      <Button
        variant="inline"
        disabled={!providerName || current?.status === 'loading'}
        onClick={() => void fetchIds()}
      >
        {current?.status === 'loading'
          ? t('ai.catalog.form.remoteModelsLoading')
          : t('ai.catalog.form.remoteModelsFetch')}
      </Button>
      {current?.status === 'loaded' ? (
        <span className="inline-hint" role="status">
          {current.ids.length > 0
            ? t('ai.catalog.form.remoteModelsLoaded', { count: current.ids.length })
            : t('ai.catalog.form.remoteModelsEmpty')}
        </span>
      ) : null}
      {current?.status === 'failed' ? (
        <span className="field-error" role="alert">
          {current.message}
        </span>
      ) : null}
      <datalist id={listId}>
        {current?.status === 'loaded'
          ? current.ids.map((id) => <option key={id} value={id} />)
          : null}
      </datalist>
    </div>
  )
}
