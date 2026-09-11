package fun.fengwk.kkstudio.harness.provider.openai.responses;

/** OpenAI Responses 提示缓存控制模式枚举。 */
public enum OpenAiPromptCacheMode {
  /** 缺省（Pi-like）模式：仅发送 runtime 派生的 prompt_cache_key，不发送 retention/options/breakpoint。 */
  AUTOMATIC,

  /** Legacy 缓存模式：非 NONE 时发送 prompt_cache_key 与 prompt_cache_retention。 */
  LEGACY,

  /** GPT-5/6 显式断点模式：发送 prompt_cache_options，并在 SYSTEM/CONVERSATION 断点处打 marker。 */
  GPT_5_6_EXPLICIT
}
