/**
 * 四指定模型声明式定义，严格匹配公共 seed/credential 契约：
 * - google/gemini-3.8-flash, variant minimal, provider type google
 * - openai/gpt-5.6-luna, variant off, provider type openai_response
 * - minimax-anthropic/MiniMax-M3, variant off, provider type anthropic
 * - deepseek/deepseek-v4-flash, variant off, provider type openai
 */
export const REAL_MODEL_DEFINITIONS = [
  {
    idSuffix: 'google_gemini',
    title: 'Google Gemini',
    providerName: 'google',
    modelName: 'gemini-3.8-flash',
    variant: 'minimal',
    providerType: 'google',
  },
  {
    idSuffix: 'openai_responses',
    title: 'OpenAI Responses',
    providerName: 'openai',
    modelName: 'gpt-5.6-luna',
    variant: 'off',
    providerType: 'openai_response',
  },
  {
    idSuffix: 'minimax_anthropic',
    title: 'MiniMax Anthropic',
    providerName: 'minimax-anthropic',
    modelName: 'MiniMax-M3',
    variant: 'off',
    providerType: 'anthropic',
  },
  {
    idSuffix: 'deepseek_chat',
    title: 'DeepSeek Chat',
    providerName: 'deepseek',
    modelName: 'deepseek-v4-flash',
    variant: 'off',
    providerType: 'openai',
  },
]

export const MINIMAX_ANTHROPIC_M3 = REAL_MODEL_DEFINITIONS.find(
  (def) => def.idSuffix === 'minimax_anthropic',
)
