package fun.fengwk.kkstudio.platform.daemon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.Architecture;
import fun.fengwk.kkstudio.platform.daemon.protocol.CapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.protocol.ConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.protocol.ConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.protocol.Content;
import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.platform.daemon.protocol.HostInfo;
import fun.fengwk.kkstudio.platform.daemon.protocol.JsonContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.OperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.ResourceContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeBody;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.TextContent;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpdateProgress;

/** proto → 领域映射的完整分支：每种 OS/arch、可选 update/configuration/error 与三种结果内容。 */
class DaemonProtoMappingTest {

  @Test
  void mapsEveryOperatingSystem() {
    assertEquals(
        DaemonOperatingSystem.WINDOWS, DaemonProtoMapping.toDomainOs(OperatingSystem.WINDOWS));
    assertEquals(DaemonOperatingSystem.WSL, DaemonProtoMapping.toDomainOs(OperatingSystem.WSL));
    assertEquals(DaemonOperatingSystem.LINUX, DaemonProtoMapping.toDomainOs(OperatingSystem.LINUX));
    assertEquals(DaemonOperatingSystem.MACOS, DaemonProtoMapping.toDomainOs(OperatingSystem.MACOS));
    assertThrows(
        ControlProtocolException.class,
        () -> DaemonProtoMapping.toDomainOs(OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED));
  }

  @Test
  void mapsEveryArchitecture() {
    assertEquals("amd64", DaemonProtoMapping.toDomainArch(Architecture.AMD64));
    assertEquals("arm64", DaemonProtoMapping.toDomainArch(Architecture.ARM64));
    assertNull(DaemonProtoMapping.toDomainArch(Architecture.ARCHITECTURE_UNSPECIFIED));
  }

  @Test
  void mapsHostInfo() {
    var host =
        DaemonProtoMapping.toDomainHostInfo(
            HostInfo.newBuilder()
                .setOperatingSystem(OperatingSystem.LINUX)
                .setArchitecture(Architecture.ARM64)
                .setTimeZone("UTC")
                .setUserName("daemon")
                .setHomeDirectory("/home/daemon")
                .setTempDirectory("/tmp")
                .build());
    assertEquals(DaemonOperatingSystem.LINUX, host.operatingSystem());
    assertEquals("arm64", host.architecture());
    assertEquals("UTC", host.timeZone());
  }

  @Test
  void mapsSnapshotWithUpdateAndConfigurationError() {
    RuntimeSnapshot proto =
        RuntimeSnapshot.newBuilder()
            .setRevision(7)
            .setStatus(
                RuntimeBody.newBuilder()
                    .setDaemonVersion("1.2.3")
                    .setUpdate(
                        UpdateProgress.newBuilder()
                            .setTargetVersion("1.3.0")
                            .setDownloadedBytes(10)
                            .setTotalBytes(100))
                    .setConfiguration(
                        ConfigurationStatus.newBuilder()
                            .setAppliedVersion(3)
                            .setProxyUrl("http://127.0.0.1:7890")
                            .setError(
                                ConfigurationError.newBuilder()
                                    .setVersion(3)
                                    .setCode("PROXY_UNREACHABLE")
                                    .setField("proxyUrl"))))
            .build();

    DaemonRuntimeSnapshot domain = DaemonProtoMapping.toDomainSnapshot(proto);
    assertEquals(7, domain.revision());
    assertEquals("1.2.3", domain.status().daemonVersion());
    assertEquals("1.3.0", domain.status().update().targetVersion());
    assertEquals(100L, domain.status().update().totalBytes());
    assertEquals(3L, domain.status().configuration().appliedVersion());
    assertEquals("PROXY_UNREACHABLE", domain.status().configuration().error().code());
    assertEquals("proxyUrl", domain.status().configuration().error().field());
  }

  @Test
  void mapsSnapshotWithoutOptionalParts() {
    DaemonRuntimeSnapshot domain =
        DaemonProtoMapping.toDomainSnapshot(
            RuntimeSnapshot.newBuilder()
                .setRevision(1)
                .setStatus(
                    RuntimeBody.newBuilder()
                        .setDaemonVersion("1.0.0")
                        .setUpdate(
                            UpdateProgress.newBuilder()
                                .setTargetVersion("1.1.0")
                                .setDownloadedBytes(5)))
                .build());
    assertNull(domain.status().update().totalBytes());
    assertNull(domain.status().configuration());

    DaemonRuntimeSnapshot bare =
        DaemonProtoMapping.toDomainSnapshot(
            RuntimeSnapshot.newBuilder()
                .setRevision(1)
                .setStatus(RuntimeBody.newBuilder().setDaemonVersion("1.0.0"))
                .build());
    assertNull(bare.status().update());
  }

  @Test
  void mapsResultContentsOfEveryKind() {
    CapabilityResult proto =
        CapabilityResult.newBuilder()
            .setCallId("call-1")
            .setError(true)
            .setDetailsJson("{\"k\":1}")
            .addContents(
                Content.newBuilder()
                    .setText(TextContent.newBuilder().setText("hello").build())
                    .build())
            .addContents(
                Content.newBuilder()
                    .setJson(JsonContent.newBuilder().setJson("{\"a\":1}").build())
                    .build())
            .addContents(
                Content.newBuilder()
                    .setResource(
                        ResourceContent.newBuilder()
                            .setUploadId("11111111-1111-4111-8111-111111111111")
                            .setMediaType("text/plain")
                            .setSize(3)
                            .setSha256("a".repeat(64))
                            .setName("a.txt")
                            .setPreview("pre"))
                    .build())
            .addContents(Content.getDefaultInstance())
            .build();

    EnvironmentCapabilityResult result = DaemonProtoMapping.toDomainResult(proto);
    assertEquals("call-1", result.callId());
    assertEquals(true, result.error());
    assertEquals(3, result.contents().size(), "empty content body is dropped");
    assertInstanceOf(TextResultContent.class, result.contents().get(0));
    assertInstanceOf(JsonResultContent.class, result.contents().get(1));
    ResourceResultContent resource =
        assertInstanceOf(ResourceResultContent.class, result.contents().get(2));
    assertEquals("a.txt", resource.resource().name());
    assertEquals("pre", resource.preview());
  }

  @Test
  void defaultsMissingDetailsJsonToEmptyObject() {
    EnvironmentCapabilityResult result =
        DaemonProtoMapping.toDomainResult(
            CapabilityResult.newBuilder().setCallId("call-2").build());
    assertEquals("{}", result.detailsJson());
    assertEquals(false, result.error());
  }
}
