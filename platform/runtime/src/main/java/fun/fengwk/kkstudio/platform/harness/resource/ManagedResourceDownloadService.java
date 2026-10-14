package fun.fengwk.kkstudio.platform.harness.resource;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;

import java.util.Objects;

/** 通过宿主 ResourceStore 按内容身份重建并读取 managed Resource 的 Platform 边界。 */
public class ManagedResourceDownloadService {

  private final ResourceStore resourceStore;

  public ManagedResourceDownloadService(ResourceStore resourceStore) {
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
  }

  public ManagedResourceDownload download(String sha256, String mediaType, long size, String name) {
    ResourceRef resource = resourceStore.reference(mediaType, name, size, sha256);
    byte[] content = resourceStore.read(resource);
    String filename = resource.name() == null ? resource.sha256() : resource.name();
    return new ManagedResourceDownload(filename, resource.mediaType(), resource.sha256(), content);
  }
}
