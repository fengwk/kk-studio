import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { ApplicationEventProvider } from '@/shared/app-events'
import { QuestionnaireCard } from '@/features/thread/runtime/interactions/QuestionnaireCard'
import { AskUserRecord } from '@/features/thread/runtime/thread-panel/messages/AskUserRecord'
import '@/features/thread/runtime/thread-panel/messages/tool-card.css'
import { setLocale, type AppLocale } from '@/shared/i18n'

/** 问卷卡真实浏览器基座：`?locale=zh-CN|en-US&state=single|multi|answered|invalid` 直接渲染各状态。 */
type QuestionnaireState = 'single' | 'multi' | 'answered' | 'invalid'

interface QuestionnaireFixture {
  argumentsJson: string
  result?: unknown
}

const params = new URLSearchParams(window.location.search)
const locale: AppLocale = params.get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
const stateParam = params.get('state')
const state: QuestionnaireState =
  stateParam === 'multi' || stateParam === 'answered' || stateParam === 'invalid' ? stateParam : 'single'
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

/** 无法解析的问卷参数：单行截断的 JSON，展开诊断时必须以换行完整呈现。 */
const INVALID_ZH =
  '{"questions":[{"question":"请选择本次发布窗口","options":[{"label":"本周六 01:00","description":"保留两小时回滚窗口","recommended":true},{"label":"本周日 01:00"}'
const INVALID_EN =
  '{"questions":[{"question":"Choose the release window","options":[{"label":"Saturday 01:00","description":"Keeps a two-hour rollback window","recommended":true},{"label":"Sunday 01:00"}'

const fixtures: Record<AppLocale, Record<QuestionnaireState, QuestionnaireFixture>> = {
  'zh-CN': {
    single: { argumentsJson: SINGLE_ZH },
    multi: {
      argumentsJson: JSON.stringify({
        questions: [
          {
            question: '请选择本次发布需要归档到长期存储并同步给值班同学的产物',
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
    invalid: { argumentsJson: INVALID_ZH },
  },
  'en-US': {
    single: { argumentsJson: SINGLE_EN },
    multi: {
      argumentsJson: JSON.stringify({
        questions: [
          {
            question: 'Choose the build artifacts to archive in long-term storage and share with on-call',
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
    invalid: { argumentsJson: INVALID_EN },
  },
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

const fixture = fixtures[locale][state]
const longResult = { declined: false, answers: [[locale === 'zh-CN' ? '测试报告' : 'Test report']] }
const titleView = params.get('titleView')
const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <div className="questionnaire-harness" data-invalid-arguments={fixture.argumentsJson}>
          <div className="interaction-feed-item">
            {titleView === 'submitted' ? (
              <AskUserRecord arguments={fixture.argumentsJson} contents={[{ type: 'json', value: longResult }]} />
            ) : (
              <QuestionnaireCard
                interactionId={`int-questionnaire-${state}`}
                threadId="thread-questionnaire-harness"
                argumentsJson={fixture.argumentsJson}
                result={titleView === 'completed' ? longResult : fixture.result}
              />
            )}
          </div>
        </div>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
}
