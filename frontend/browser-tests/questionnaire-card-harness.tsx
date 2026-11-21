import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ApplicationEventProvider } from '@/shared/app-events'
import { QuestionnaireCard } from '@/features/thread/runtime/interactions/QuestionnaireCard'
import { setLocale, type AppLocale } from '@/shared/i18n'

/** 问卷卡真实浏览器基座：`?locale=zh-CN|en-US&state=single|multi|answered` 直接渲染三种状态。 */
type QuestionnaireState = 'single' | 'multi' | 'answered'

interface QuestionnaireFixture {
  argumentsJson: string
  result?: unknown
}

const params = new URLSearchParams(window.location.search)
const locale: AppLocale = params.get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
const stateParam = params.get('state')
const state: QuestionnaireState =
  stateParam === 'multi' || stateParam === 'answered' ? stateParam : 'single'
setLocale(locale)

const SINGLE_ZH = JSON.stringify({
  questions: [
    {
      question: '请选择本次发布窗口',
      options: [
        { label: '本周六 01:00', description: '保留两小时回滚窗口', recommended: true },
        { label: '本周日 01:00' },
        { label: '下个版本发布后' },
      ],
    },
  ],
})

const SINGLE_EN = JSON.stringify({
  questions: [
    {
      question: 'Choose the release window',
      options: [
        { label: 'Saturday 01:00', description: 'Keeps a two-hour rollback window', recommended: true },
        { label: 'Sunday 01:00' },
        { label: 'After the next release' },
      ],
    },
  ],
})

const fixtures: Record<AppLocale, Record<QuestionnaireState, QuestionnaireFixture>> = {
  'zh-CN': {
    single: { argumentsJson: SINGLE_ZH },
    multi: {
      argumentsJson: JSON.stringify({
        questions: [
          {
            question: '请选择需要归档的产物',
            multiple: true,
            options: [{ label: '构建日志' }, { label: '测试报告' }, { label: '界面截图' }],
          },
        ],
      }),
    },
    answered: {
      argumentsJson: SINGLE_ZH,
      result: { declined: false, answers: [['本周日 01:00']] },
    },
  },
  'en-US': {
    single: { argumentsJson: SINGLE_EN },
    multi: {
      argumentsJson: JSON.stringify({
        questions: [
          {
            question: 'Choose the artifacts to archive',
            multiple: true,
            options: [{ label: 'Build log' }, { label: 'Test report' }, { label: 'UI screenshots' }],
          },
        ],
      }),
    },
    answered: {
      argumentsJson: SINGLE_EN,
      result: { declined: false, answers: [['Sunday 01:00']] },
    },
  },
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

const fixture = fixtures[locale][state]
const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <div className="questionnaire-harness">
          <div className="interaction-feed-item">
            <QuestionnaireCard
              interactionId={`int-questionnaire-${state}`}
              threadId="thread-questionnaire-harness"
              argumentsJson={fixture.argumentsJson}
              result={fixture.result}
            />
          </div>
        </div>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
}
