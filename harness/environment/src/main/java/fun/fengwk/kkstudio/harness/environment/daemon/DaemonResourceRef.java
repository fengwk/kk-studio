package fun.fengwk.kkstudio.harness.environment.daemon;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

/**
 * Daemon 存储的不可变 resource 引用。
 *
 * <p>提供给 DaemonResourceStore 与 EnvironmentCapabilityResult 作为对不可变导出的强类型引用。
 */
public record DaemonResourceRef(
    String uri, String mediaType, String name, Long size, String sha256) {

  public DaemonResourceRef {
    ResourceRef validated = new ResourceRef(uri, mediaType, name, size, sha256);
    uri = validated.uri();
    mediaType = validated.mediaType();
    name = validated.name();
    size = validated.size();
    sha256 = validated.sha256();
  }
}
