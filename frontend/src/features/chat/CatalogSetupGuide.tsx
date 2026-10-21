import { Link } from 'react-router'
import { useI18n } from '@/shared/i18n'
import type { CatalogSetupState } from '@/features/chat/catalog-setup'


/**
 * 新库的首次使用引导：按 Provider → Model → Agent → Chat 顺序展示进度并突出下一步。
 * 调用方只在 {@link isCatalogSetupComplete} 为 false 时渲染。
 */
export function CatalogSetupGuide({ state }: { state: CatalogSetupState }) {
  const { t } = useI18n()
  const steps = [
    { key: 'provider', done: state.hasProvider, to: '/providers' },
    { key: 'model', done: state.hasModel, to: '/models' },
    { key: 'agent', done: state.hasAgent, to: '/agents' },
  ] as const
  const currentIndex = steps.findIndex((step) => !step.done)

  return (
    <section className="catalog-setup-guide" aria-labelledby="catalog-setup-title">
      <h2 id="catalog-setup-title">{t('ai.setup.title')}</h2>
      <p className="inline-hint">{t('ai.setup.description')}</p>
      <ol>
        {steps.map((step, index) => (
          <li
            key={step.key}
            data-state={step.done ? 'done' : index === currentIndex ? 'current' : 'pending'}
          >
            <span className="catalog-setup-step-title">{t(`ai.setup.${step.key}.title`)}</span>
            <span className="inline-hint">{t(`ai.setup.${step.key}.description`)}</span>
            {step.done ? (
              <span className="catalog-setup-step-status">{t('ai.setup.done')}</span>
            ) : (
              <Link className={index === currentIndex ? 'btn-primary' : 'ghost-btn'} to={step.to}>
                {t(`ai.setup.${step.key}.action`)}
              </Link>
            )}
          </li>
        ))}
        <li data-state="pending">
          <span className="catalog-setup-step-title">{t('ai.setup.chat.title')}</span>
          <span className="inline-hint">{t('ai.setup.chat.description')}</span>
        </li>
      </ol>
    </section>
  )
}
