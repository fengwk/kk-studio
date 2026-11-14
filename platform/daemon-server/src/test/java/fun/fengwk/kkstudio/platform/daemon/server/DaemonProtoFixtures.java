package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.protocol.CapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.ConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.protocol.ConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.protocol.Content;
import fun.fengwk.kkstudio.platform.daemon.protocol.JsonContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeBody;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.TextContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpdateProgress;

import java.util.UUID;

/**
 * 测试夹具的领域对象 → protobuf 映射。
 *
 * <p>生产路径只做 proto → 领域方向（入站信封），领域 → proto 仅用于测试构造 Daemon 将要上报的帧，因此两个方向都留在测试源集。
 */
final class DaemonProtoFixtures {

  private DaemonProtoFixtures() {}

  /** 由领域运行快照构造 wire 上的 {@code RuntimeSnapshot}。 */
  static RuntimeSnapshot runtimeSnapshot(DaemonRuntimeSnapshot snapshot) {
    RuntimeBody.Builder body = RuntimeBody.newBuilder();
    if (snapshot.status().daemonVersion() != null) {
      body.setDaemonVersion(snapshot.status().daemonVersion());
    }
    if (snapshot.status().update() != null) {
      DaemonUpdateProgress up = snapshot.status().update();
      UpdateProgress.Builder ub =
          UpdateProgress.newBuilder()
              .setTargetVersion(up.targetVersion())
              .setDownloadedBytes(up.downloadedBytes());
      if (up.totalBytes() != null) {
        ub.setTotalBytes(up.totalBytes());
      }
      body.setUpdate(ub.build());
    }
    if (snapshot.status().configuration() != null) {
      DaemonConfigurationStatus status = snapshot.status().configuration();
      ConfigurationStatus.Builder cb = ConfigurationStatus.newBuilder();
      if (status.appliedVersion() != null) {
        cb.setAppliedVersion(status.appliedVersion());
      }
      if (status.proxyUrl() != null) {
        cb.setProxyUrl(status.proxyUrl());
      }
      if (status.error() != null) {
        DaemonConfigurationError error = status.error();
        ConfigurationError.Builder eb =
            ConfigurationError.newBuilder().setVersion(error.version()).setCode(error.code());
        if (error.field() != null) {
          eb.setField(error.field());
        }
        cb.setError(eb.build());
      }
      body.setConfiguration(cb.build());
    }
    return RuntimeSnapshot.newBuilder()
        .setRevision(snapshot.revision())
        .setStatus(body.build())
        .build();
  }

  /** 由领域能力结果构造 wire 上的 {@code CapabilityResult}。 */
  static CapabilityResult capabilityResult(EnvironmentCapabilityResult result) {
    CapabilityResult.Builder builder =
        CapabilityResult.newBuilder()
            .setCallId(result.callId())
            .setError(result.error())
            .setDetailsJson(result.detailsJson());
    for (ResultContent content : result.contents()) {
      Content.Builder cb = Content.newBuilder();
      if (content instanceof TextResultContent text) {
        cb.setText(TextContent.newBuilder().setText(text.text()).build());
      } else if (content instanceof JsonResultContent json) {
        cb.setJson(JsonContent.newBuilder().setJson(json.json()).build());
      } else if (content instanceof ResourceResultContent res) {
        ResourceRef ref = res.resource();
        ResourceContent.Builder rcb =
            ResourceContent.newBuilder()
                .setUploadId(uploadId(ref))
                .setMediaType(ref.mediaType())
                .setSize(ref.size() != null ? ref.size() : 0L)
                .setSha256(ref.sha256() != null ? ref.sha256() : "");
        if (ref.name() != null) {
          rcb.setName(ref.name());
        }
        if (res.preview() != null) {
          rcb.setPreview(res.preview());
        }
        cb.setResource(rcb.build());
      }
      builder.addContents(cb.build());
    }
    return builder.build();
  }

  private static String uploadId(ResourceRef ref) {
    UUID uploadId = ref.blobUploadId();
    return uploadId != null ? uploadId.toString() : "";
  }
}
