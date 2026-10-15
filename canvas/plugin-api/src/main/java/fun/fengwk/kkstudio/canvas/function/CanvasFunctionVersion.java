package fun.fengwk.kkstudio.canvas.function;

import java.util.regex.Pattern;

/**
 * 客户端从目录观察到的函数版本：目录 epoch 与该函数完整定义的 fingerprint。
 *
 * <p>{@code catalogEpoch} 是非负目录版本；{@code definitionFingerprint} 是 {@link
 * CanvasFunctionCatalog#definitionFingerprint(CanvasFunctionDefinition)} 产出的 64 位小写
 * SHA-256。启动执行时用它把「客户端看到的定义」 与「即将执行的冻结定义」对齐：epoch 或 fingerprint 任一过期都拒绝，绝不按函数名静默换成当前版本。
 */
public record CanvasFunctionVersion(long catalogEpoch, String definitionFingerprint) {

  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

  public CanvasFunctionVersion {
    if (catalogEpoch < 0) {
      throw new IllegalArgumentException("catalogEpoch must be non-negative");
    }
    if (definitionFingerprint == null || !FINGERPRINT.matcher(definitionFingerprint).matches()) {
      throw new IllegalArgumentException(
          "definitionFingerprint must be a 64-char lowercase SHA-256");
    }
  }
}
