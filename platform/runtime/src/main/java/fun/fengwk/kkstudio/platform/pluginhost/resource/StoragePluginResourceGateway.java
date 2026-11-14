package fun.fengwk.kkstudio.platform.pluginhost.resource;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginMediaFamily;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.pluginhost.PluginProperties;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Platform 的 {@link PluginResourceGateway} 生产实现：Session Resource 授权下载 + 远端媒体真实暂存。
 *
 * <p>两条路径都不信任调用方给出的字符串或响应头：会话资源只对确实持有该 blob 引用的 Session 签发短期预签名 GET；远端地址必须是 HTTPS 且没有敏感成分，地址准入在真正解析
 * socket 目标时执行，宿主授权检查在每一次真正出网前执行，下载受整体期限与字节上限约束，媒体类型由 magic 特征判定并复核调用方声明的族。
 *
 * <p>本类是组合根：签发票据、远端暂存与关闭传输分别由同包协作者承担，失败语义（收敛为 {@link
 * fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException}，凭据不可用异常原样传播，
 * 临时文件与已 reserve 的上传必被清理）由协作者保证。
 */
public final class StoragePluginResourceGateway implements PluginResourceGateway, AutoCloseable {

  private final PluginSessionResourceSigner signer;
  private final PluginRemoteMediaStaging staging;
  private final RemoteMediaTransport transport;

  public StoragePluginResourceGateway(
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties) {
    this(
        sessionBlobRefManager,
        storageBlobManager,
        storageUploadService,
        properties,
        new HttpRemoteMediaTransport(
            properties.getResource().getConnectTimeout(),
            new PublicAddressDnsResolver(new PublicAddressPolicy(HostResolver.system()))));
  }

  /** 测试用装配：传输可替换，使安全规则无需真实网络即可验证。 */
  StoragePluginResourceGateway(
      SessionBlobRefManager sessionBlobRefManager,
      StorageBlobManager storageBlobManager,
      StorageUploadService storageUploadService,
      PluginProperties properties,
      RemoteMediaTransport transport) {
    Objects.requireNonNull(sessionBlobRefManager, "sessionBlobRefManager");
    Objects.requireNonNull(storageBlobManager, "storageBlobManager");
    Objects.requireNonNull(storageUploadService, "storageUploadService");
    Objects.requireNonNull(properties, "properties");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.signer = new PluginSessionResourceSigner(sessionBlobRefManager, storageBlobManager);
    this.staging =
        new PluginRemoteMediaStaging(
            storageUploadService,
            properties,
            transport,
            resolveTempDirectory(properties.getResource().getTempDirectory()));
  }

  /** 关闭下游传输持有的 HTTP 客户端（Spring 组合根按 inferred destroy method 调用），重复关闭必须安全。 */
  @Override
  public void close() throws IOException {
    transport.close();
  }

  @Override
  public URI resolveSessionResource(UUID sessionId, String resourceUri) {
    return signer.resolve(sessionId, resourceUri);
  }

  @Override
  public ResourceRef stageRemoteMedia(
      URI remoteUri, PluginMediaFamily family, String name, Runnable beforeRequest) {
    return staging.stage(remoteUri, family, name, beforeRequest);
  }

  private static Path resolveTempDirectory(String configured) {
    Path directory =
        configured == null || configured.isBlank()
            ? Path.of(System.getProperty("java.io.tmpdir"))
            : Path.of(configured);
    return directory.toAbsolutePath().normalize();
  }
}
