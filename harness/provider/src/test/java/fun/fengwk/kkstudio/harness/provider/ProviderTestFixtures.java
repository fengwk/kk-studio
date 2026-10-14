package fun.fengwk.kkstudio.harness.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 读取按包组织的测试 fixture，避免在测试类里手拼重复或长结构化 JSON。 */
public final class ProviderTestFixtures {

  private ProviderTestFixtures() {}

  /** 读取与给定测试类同包（或显式绝对路径）的 classpath 文本 fixture。 */
  public static String read(Class<?> anchor, String name) {
    try (InputStream in = anchor.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException(
            "missing test fixture: " + name + " for " + anchor.getName());
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
