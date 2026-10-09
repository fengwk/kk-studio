package fun.fengwk.kkstudio.share.ai.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 内置 Agent prompt 共享资源契约：压缩 system prompt 必须随模块资源打包并可读取，读取结果稳定（同一实例、非空白）， 因此内置目录初始化与
 * 其他消费方共享同一份只读文本，而不是各自持有副本；资源缺失或不可读必须以显式异常失败，绝不静默退化为空 prompt。
 */
class BuiltinAgentPromptsTest {

  private static final String RESOURCE =
      "fun/fengwk/kkstudio/share/ai/catalog/prompts/compaction-summarization-system.md";

  @Test
  void compactionSummarizationSystemPromptIsPackagedAndStable() {
    String prompt = BuiltinAgentPrompts.compactionSummarizationSystemPrompt();

    assertFalse(prompt.isBlank());
    assertTrue(prompt.contains("summarization assistant"));
    assertSame(prompt, BuiltinAgentPrompts.compactionSummarizationSystemPrompt());
  }

  @Test
  void missingResourceFailsExplicitly() {
    assertThrows(
        IllegalStateException.class,
        () ->
            BuiltinAgentPrompts.read(
                "fun/fengwk/kkstudio/share/ai/catalog/prompts/absent.md",
                BuiltinAgentPromptsTest.class.getClassLoader()));
  }

  @Test
  void unreadableResourceFailsExplicitly() {
    ClassLoader failing =
        new ClassLoader(BuiltinAgentPromptsTest.class.getClassLoader()) {
          @Override
          public InputStream getResourceAsStream(String name) {
            return new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("resource stream failure");
              }
            };
          }
        };

    assertThrows(UncheckedIOException.class, () -> BuiltinAgentPrompts.read(RESOURCE, failing));
  }

  @Test
  void readsResourceVerbatim() throws IOException {
    // 不做 trim / 转义：访问器必须逐字返回资源文本，保持与内置目录初始 prompt 完全一致。
    try (InputStream input =
        BuiltinAgentPromptsTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(raw, BuiltinAgentPrompts.compactionSummarizationSystemPrompt());
    }
  }
}
