package fun.fengwk.kkstudio.canvas;

import java.util.regex.Pattern;

/**
 * 客户端从目录观察到的函数版本：该函数完整定义的 fingerprint（{@link
 * CanvasFunctionCatalog#definitionFingerprint(CanvasFunctionDefinition)}，64 位小写 SHA-256）。
 *
 * <p>启动执行时用它把「客户端看到的定义」与「即将执行的定义」对齐，不一致即拒绝。
 */
public record CanvasFunctionVersion(String definitionFingerprint) {

  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

  public CanvasFunctionVersion {
    if (definitionFingerprint == null || !FINGERPRINT.matcher(definitionFingerprint).matches()) {
      throw new IllegalArgumentException(
          "definitionFingerprint must be a 64-char lowercase SHA-256");
    }
  }
}
