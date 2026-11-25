/**
 * 搜索命中可见性用例共用的 fixture 文本。
 *
 * 浏览器 spec 与 harness 都从这里取词：断言里的搜索词、渲染出来的标题与预览、以及
 * 历史 Entry 正文只有一份来源，改动 fixture 不会让断言悄悄失去覆盖。
 */

/** 深层命中词：会话/Thread 标题与历史预览都把命中放在长前文之后。 */
export const DEEP_HIT = '停机耗时'

/** 标题深处的命中：整行标题远超窄行宽度，命中原本会被单行省略裁掉。 */
export const DEEP_TITLE = `灰度切换演练记录 ${'含压力测试窗口的回归清单 '.repeat(3)}${DEEP_HIT}排序`

/** 副标题预览深处的命中词。 */
export const DEEP_PREVIEW_HIT = '回滚窗口'

/** 会话/Thread 的副标题预览：命中词在 40 多个字符之后。 */
export const DEEP_PREVIEW = `先确认发布范围与影响面，再检查灰度比例与监控面板，最后在 ${DEEP_PREVIEW_HIT} 内完成回滚验证`

/** 短命中：命中词就在标题开头，没有前文框。 */
export const SHORT_HIT = '巡检'

export const SHORT_TITLE = `${SHORT_HIT}结论`

export const SHORT_PREVIEW = `${SHORT_HIT}完成，无异常；报告已归档`

/** 历史 Entry 正文：命中词在第 40 个字符处，仍在 220 字预览上限之内。 */
export const HISTORY_TEXT = `结论 服务停机时先停止接收新请求，再等待在途请求完成，最后释放连接池并记录${DEEP_HIT}`

/** 长拉丁命中词：位于 220 字预览上限之外，普通预览里根本没有它。 */
export const LONG_HIT = 'terminationGracePeriodSeconds'

/** 历史 Entry 正文：长命中词在 240 字前文之后，只有从全文截取的预览才看得到它。 */
export const LONG_HISTORY_TEXT = `${'背景说明'.repeat(60)}\n\`\`\`yaml\n${LONG_HIT}: 30\n\`\`\``

export const HISTORY_MARKDOWN = `## **结论**\n${HISTORY_TEXT.slice('结论 '.length)}`
export const HISTORY_TABLE = '| 阶段 | 状态 |\n| --- | --- |\n| 停机 | 完成 |'
export const HISTORY_TABLE_TEXT = '阶段 状态 停机 完成'
export const HISTORY_VIZ = '```mermaid\ngraph TD; Start-->Stop\n```'
