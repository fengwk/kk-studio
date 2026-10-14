package fun.fengwk.kkstudio.platform.plugin.catalog;

import java.util.Objects;

/**
 * Plugin catalog 权威聚合的完整读取投影：唯一全局 epoch 加冻结 manifest。
 *
 * <p>epoch=0 是唯一未初始化形态，必须对应空 plugins 与 null catalogFingerprint；任何 initialized epoch 必须携带 64
 * 位小写目标指纹， 且每个 entry 的 configRevision 都不得晚于该 epoch——revision 就是撰写它的全局 epoch，领先 epoch 说明记录已损坏。
 */
public record PluginCatalogRecord(long epoch, PluginCatalogManifest manifest) {

  public PluginCatalogRecord {
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch must not be negative");
    }
    Objects.requireNonNull(manifest, "manifest");
    if (epoch == 0) {
      if (manifest.catalogFingerprint() != null || !manifest.plugins().isEmpty()) {
        throw new IllegalArgumentException(
            "epoch 0 must be the uninitialized catalog with no plugins and no fingerprint");
      }
    } else {
      if (manifest.catalogFingerprint() == null) {
        throw new IllegalArgumentException(
            "an initialized catalog must declare a catalogFingerprint");
      }
      for (PluginCatalogManifest.Entry entry : manifest.plugins().values()) {
        if (entry.configRevision() > epoch) {
          throw new IllegalArgumentException(
              "a catalog entry revision must not exceed the epoch it was recorded at");
        }
      }
    }
  }
}
