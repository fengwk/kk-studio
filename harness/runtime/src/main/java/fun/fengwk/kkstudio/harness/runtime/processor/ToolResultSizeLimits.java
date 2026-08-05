package fun.fengwk.kkstudio.harness.runtime.processor;

/**
 * Tool 结果持久化 / 实时投影的确定性编码尺寸上限（纯值，无配置框架依赖）。
 *
 * <p>上限按 canonical {@link
 * fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec#exceedsEncodedUtf8Bytes ToolResult
 * JSON} UTF-8 字节数计算：bounded 编码器在超过上限时立即中止序列化，不物化完整 JSON String / byte[]；该编码与 PostgreSQL
 * 持久化字节一一对应，因此 N×data URI / 内联大文本无法把单行撑爆。transient Binary 在 Gateway 外部化之前不参与 编码，不受本上限约束。
 */
public final class ToolResultSizeLimits {

  /** terminal ToolResult 的 canonical JSON UTF-8 字节上限；超过即确定性 INVALID_RESULT。 */
  public static final int MAX_TERMINAL_RESULT_UTF8_BYTES = 1024 * 1024;

  /** partial ToolResult 的 canonical JSON UTF-8 字节上限；超过即确定性 INVALID_PARTIAL，绝不发布实时事件。 */
  public static final int MAX_PARTIAL_RESULT_UTF8_BYTES = 256 * 1024;

  private ToolResultSizeLimits() {}
}
