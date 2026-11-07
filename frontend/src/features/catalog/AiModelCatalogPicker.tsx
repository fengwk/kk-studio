import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronDown, ChevronRight, RefreshCw } from 'lucide-react'
import type {
  ModelCatalogPageDTO,
  ModelCatalogCandidateDTO,
} from '@/shared/api/generated/contracts'
import { agentService } from '@/shared/api/agent-service'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'
import { formatCatalogSupportIssue } from '@/features/catalog/catalog-utils'
import './catalog-form-layout.css'
import './ai-model-catalog-picker.css'

/**
 * 单 Model 的原位目录候选区：只读展示来源，逐行点击预览，每行一键填入。
 * 请求带 epoch；迟到响应不会混入当前草稿。
 */
export function ModelCatalogPicker({
  onApply,
}: {
  onApply: (candidate: ModelCatalogCandidateDTO) => void
}) {
  const { t } = useI18n()
  const [query, setQuery] = useState('')
  const [items, setItems] = useState<ModelCatalogCandidateDTO[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const requestEpoch = useRef(0)
  const currentQuery = useRef('')
  const generationRef = useRef('')
  const pageSize = 20

  const accept = useCallback((page: ModelCatalogPageDTO, epoch: number, append: boolean) => {
    if (epoch !== requestEpoch.current) {
      return
    }
    const isNewGeneration = generationRef.current !== '' && page.generation !== generationRef.current
    generationRef.current = page.generation
    setItems((current) => (append && !isNewGeneration ? [...current, ...page.items] : page.items))
    setNextCursor(page.nextCursor ?? null)
    setError(page.error ?? null)
  }, [])

  const load = useCallback(async (search: string, cursor: string | null, append: boolean) => {
    const epoch = requestEpoch.current + 1
    requestEpoch.current = epoch
    if (!append) {
      // 首页请求确立当前筛选词；load-more 游标只对产生它的搜索词有效。
      currentQuery.current = search
      setError(null)
    }
    setLoading(true)
    try {
      const page = await agentService.listModelCatalog(search, cursor, pageSize)
      accept(page, epoch, append)
    } catch (cause) {
      if (epoch === requestEpoch.current) {
        setError(toUserFacingErrorMessage(cause))
      }
    } finally {
      if (epoch === requestEpoch.current) {
        setLoading(false)
      }
    }
  }, [accept])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setExpandedId(null)
      void load(query.trim(), null, false)
    }, 250)
    return () => window.clearTimeout(timer)
  }, [load, query])

  useEffect(() => () => {
    requestEpoch.current += 1
  }, [])

  async function refresh() {
    const epoch = requestEpoch.current + 1
    requestEpoch.current = epoch
    setLoading(true)
    setError(null)
    try {
      await agentService.refreshModelCatalog()
    } catch (cause) {
      if (epoch === requestEpoch.current) {
        setError(toUserFacingErrorMessage(cause))
        setLoading(false)
      }
      return
    }
    if (epoch !== requestEpoch.current) {
      return
    }
    // 刷新响应是不带 query 的首页；直接采用会把未筛选结果混入当前搜索并让游标失去匹配，
    // 因此刷新成功后按当前搜索词重新读取第一页。
    await load(currentQuery.current, null, false)
  }

  return (
    <section className="structured-section catalog-picker" aria-label={t('ai.catalog.catalog.title')}>
      <div className="catalog-picker-toolbar">
        <TextInput
          type="search"
          value={query}
          aria-label={t('ai.catalog.catalog.searchPlaceholder')}
          placeholder={t('ai.catalog.catalog.searchPlaceholder')}
          onChange={(event) => setQuery(event.target.value)}
        />
        <span className="inline-hint">
          models.dev
        </span>
        <Button
          variant="ghost"
          size="compact"
          loading={loading}
          aria-label={t('ai.catalog.catalog.refresh')}
          onClick={() => void refresh()}
        >
          <RefreshCw aria-hidden="true" />
          {t('ai.catalog.catalog.refresh')}
        </Button>
      </div>

      {error ? (
        <div className="inline-hint danger" role="alert">
          {error}{' '}
          <Button variant="inline" onClick={() => void load(currentQuery.current, null, false)}>
            {t('ai.catalog.catalog.retry')}
          </Button>
        </div>
      ) : null}

      <ul className="catalog-candidate-list" aria-busy={loading}>
        {items.map((candidate) => {
          const expanded = expandedId === candidate.id
          return (
            <li key={candidate.id} className="catalog-candidate" data-expanded={expanded || undefined}>
              <div className="catalog-candidate-row">
                <button
                  type="button"
                  className="catalog-candidate-main"
                  aria-expanded={expanded}
                  onClick={() => setExpandedId(expanded ? null : candidate.id)}
                >
                  {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
                  <span className="catalog-candidate-identity">
                    {candidate.provider} / {candidate.name}
                  </span>
                </button>
                <Button
                  variant="inline"
                  onClick={(event) => {
                    event.stopPropagation()
                    onApply(candidate)
                  }}
                >
                  {t('ai.catalog.catalog.fill')}
                </Button>
              </div>
              {expanded ? <CandidatePreview candidate={candidate} /> : null}
            </li>
          )
        })}
      </ul>

      {!loading && items.length === 0 && !error ? (
        <span className="inline-hint">{t('ai.catalog.catalog.empty')}</span>
      ) : null}

      {nextCursor ? (
        <Button
          variant="ghost"
          disabled={loading}
          onClick={() => void load(currentQuery.current, nextCursor, true)}
        >
          {t('ai.catalog.catalog.loadMore')}
        </Button>
      ) : null}
    </section>
  )
}

function CandidatePreview({ candidate }: { candidate: ModelCatalogCandidateDTO }) {
  const { t } = useI18n()
  const abilities = candidate.abilities
  const capabilityParts: string[] = []
  if (abilities?.tools != null) {
    capabilityParts.push(`${t('ai.catalog.form.tools')}: ${abilities.tools ? '✓' : '✗'}`)
  }
  if (abilities?.reasoning != null) {
    capabilityParts.push(`${t('ai.catalog.form.reasoning')}: ${abilities.reasoning ? '✓' : '✗'}`)
  }
  if (abilities?.inputModalities?.length) {
    capabilityParts.push(abilities.inputModalities.join(', '))
  }
  const variants = candidate.variants ?? []
  const defaultVariant = candidate.defaultVariant ?? ''
  const variantSummary = variants.length > 0 && defaultVariant
    ? `${defaultVariant} · ${variants.map((variant) => variant.id).join(', ')}`
    : t('ai.catalog.catalog.noVariants')

  return (
    <div className="catalog-candidate-preview">
      <span>
        {t('ai.catalog.form.limit')}:{' '}
        {candidate.limit?.context ? `${candidate.limit.context} / ` : ''}
        {candidate.limit?.output ?? '—'}
      </span>
      {capabilityParts.length > 0 ? <span>{capabilityParts.join(' · ')}</span> : null}
      {candidate.pricing ? (
        <span>
          {t('ai.catalog.catalog.priceSummary', {
            input: candidate.pricing.base.input,
            output: candidate.pricing.base.output,
            cacheRead: candidate.pricing.base.cacheRead,
            cacheWrite: candidate.pricing.base.cacheWrite,
            overrides: candidate.pricing.overrides.length,
            multipliers: candidate.pricing.multipliers.length,
          })}
        </span>
      ) : null}
      <span>{variantSummary}</span>
      {!candidate.pricingSupported ? (
        <span className="inline-hint">{t('ai.catalog.catalog.unsupportedPricing')}</span>
      ) : null}
      {candidate.supportIssues.length > 0 ? (
        <ul className="catalog-candidate-issues" role="status">
          {candidate.supportIssues.map((issue) => (
            <li key={issue}>{formatCatalogSupportIssue(issue, t)}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
