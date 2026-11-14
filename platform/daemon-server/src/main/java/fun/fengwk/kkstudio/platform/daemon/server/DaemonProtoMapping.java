package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonHostInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.protocol.Architecture;
import fun.fengwk.kkstudio.platform.daemon.protocol.CapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.ConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.protocol.ConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.protocol.Content;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.HostInfo;
import fun.fengwk.kkstudio.platform.daemon.protocol.OperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeBody;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpdateProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 控制协议的 proto → 领域映射。
 *
 * <p>会话只消费领域对象；wire 类型到领域类型的转换集中在这里，保持入站分派代码面向领域模型。反向映射仅测试需要，留在测试源集。
 */
final class DaemonProtoMapping {

  private DaemonProtoMapping() {}

  /** READY 上报的主机描述。 */
  static DaemonHostInfo toDomainHostInfo(HostInfo info) {
    return new DaemonHostInfo(
        toDomainOs(info.getOperatingSystem()),
        info.getTimeZone(),
        info.getUserName(),
        info.getHomeDirectory(),
        info.getTempDirectory(),
        toDomainArch(info.getArchitecture()));
  }

  static DaemonOperatingSystem toDomainOs(OperatingSystem os) {
    return switch (os) {
      case WINDOWS -> DaemonOperatingSystem.WINDOWS;
      case WSL -> DaemonOperatingSystem.WSL;
      case LINUX -> DaemonOperatingSystem.LINUX;
      case MACOS -> DaemonOperatingSystem.MACOS;
      default -> throw new ControlProtocolException("unsupported operating system: " + os);
    };
  }

  static String toDomainArch(Architecture arch) {
    return switch (arch) {
      case AMD64 -> "amd64";
      case ARM64 -> "arm64";
      default -> null;
    };
  }

  static DaemonRuntimeSnapshot toDomainSnapshot(RuntimeSnapshot proto) {
    long revision = proto.getRevision();
    RuntimeBody body = proto.getStatus();
    String daemonVersion = body.getDaemonVersion();
    DaemonUpdateProgress update = null;
    if (body.hasUpdate()) {
      UpdateProgress up = body.getUpdate();
      update =
          new DaemonUpdateProgress(
              up.getTargetVersion(),
              up.getDownloadedBytes(),
              up.hasTotalBytes() ? up.getTotalBytes() : null);
    }
    DaemonConfigurationStatus configuration = null;
    if (body.hasConfiguration()) {
      ConfigurationStatus status = body.getConfiguration();
      DaemonConfigurationError error = null;
      if (status.hasError()) {
        ConfigurationError protoError = status.getError();
        error =
            new DaemonConfigurationError(
                protoError.getVersion(),
                protoError.getCode(),
                protoError.hasField() ? protoError.getField() : null);
      }
      configuration =
          new DaemonConfigurationStatus(
              status.hasAppliedVersion() ? status.getAppliedVersion() : null,
              status.hasProxyUrl() ? status.getProxyUrl() : null,
              error);
    }
    return new DaemonRuntimeSnapshot(
        revision, new DaemonRuntimeStatus(daemonVersion, update, configuration));
  }

  static EnvironmentCapabilityResult toDomainResult(CapabilityResult result) {
    String callId = result.getCallId();
    boolean error = result.getError();
    String detailsJson = result.hasDetailsJson() ? result.getDetailsJson() : "{}";
    List<ResultContent> contents = new ArrayList<>(result.getContentsCount());
    for (Content c : result.getContentsList()) {
      switch (c.getBodyCase()) {
        case TEXT -> contents.add(new TextResultContent(c.getText().getText()));
        case JSON -> contents.add(new JsonResultContent(c.getJson().getJson()));
        case RESOURCE -> {
          ResourceContent rc = c.getResource();
          String uri = ResourceRef.blobUploadUri(UUID.fromString(rc.getUploadId()));
          ResourceRef ref =
              new ResourceRef(
                  uri,
                  rc.getMediaType(),
                  rc.hasName() ? rc.getName() : null,
                  rc.getSize(),
                  rc.getSha256());
          contents.add(new ResourceResultContent(ref, rc.hasPreview() ? rc.getPreview() : null));
        }
        default -> {}
      }
    }
    return new EnvironmentCapabilityResult(callId, contents, error, detailsJson);
  }
}
