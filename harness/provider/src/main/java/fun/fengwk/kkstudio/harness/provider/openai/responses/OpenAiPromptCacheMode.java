package fun.fengwk.kkstudio.harness.provider.openai.responses;

/** OpenAI Responses 提示缓存控制模式枚举。 */
public enum OpenAiPromptCacheMode {
  /** 缺省模式：完全不向 upstream 发送任何 cache hint 或选项。 */
  AUTOMATIC,

  /** Legacy 缓存模式：非 NONE 时发送 prompt_cache_key 与 prompt_cache_retention。 */
  LEGACY,

  /** GPT-5/6 显式断点模式：发送 prompt_cache_options，并在 SYSTEM/CONVERSATION 断点处打 marker。 */
  GPT_5_6_EXPLICIT
}
