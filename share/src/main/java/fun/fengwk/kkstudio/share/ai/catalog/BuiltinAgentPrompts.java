package fun.fengwk.kkstudio.share.ai.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 内置 Agent 初始 prompt 的共享资源入口。
 *
 * <p>内置 Agent 的初始系统提示词属于内置目录资产，按模块资源路径唯一持有；压缩执行与请求预览等消费方按需读取这里的只读文本， 不再由运行时的压缩执行路径持有或暴露该
 * prompt。资源文本逐字读取（不做 trim 或转义），因此与目录初始化写入的初始 prompt 完全一致。
 */
public final class BuiltinAgentPrompts {

  private static final String COMPACTION_SUMMARIZATION_SYSTEM =
      "fun/fengwk/kkstudio/share/ai/catalog/prompts/compaction-summarization-system.md";

  private static final String COMPACTION_SUMMARIZATION_SYSTEM_PROMPT =
      read(COMPACTION_SUMMARIZATION_SYSTEM, BuiltinAgentPrompts.class.getClassLoader());

  private BuiltinAgentPrompts() {}

  /** 压缩内置 Agent 的初始系统提示词（无变量，逐字来自共享资源）。 */
  public static String compactionSummarizationSystemPrompt() {
    return COMPACTION_SUMMARIZATION_SYSTEM_PROMPT;
  }

  static String read(String resource, ClassLoader classLoader) {
    try (InputStream input = classLoader.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IllegalStateException("missing builtin agent prompt resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(
          "cannot read builtin agent prompt resource: " + resource, error);
    }
  }
}
