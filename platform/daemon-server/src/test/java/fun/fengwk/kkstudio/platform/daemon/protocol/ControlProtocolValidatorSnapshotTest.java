package fun.fengwk.kkstudio.platform.daemon.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 运行快照配置状态、LSP 列表与 host 结构的分支契约。 */
class ControlProtocolValidatorSnapshotTest {

  private static Envelope runtimeStatus(RuntimeStatus status) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.RUNTIME_STATUS)
        .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
        .setRuntimeStatus(status)
        .build();
  }

  private static RuntimeSnapshot.Builder snapshot() {
    return RuntimeSnapshot.newBuilder().setRevision(1).setStatus(RuntimeBody.newBuilder());
  }

  private static RuntimeSnapshot.Builder withConfiguration(
      ConfigurationStatus.Builder configuration) {
    return RuntimeSnapshot.newBuilder()
        .setRevision(1)
        .setStatus(
            RuntimeBody.newBuilder().setDaemonVersion("1.2.3").setConfiguration(configuration));
  }

  @Test
  void acceptsAppliedVersionWithProxyAndStructuredError() {
    assertDoesNotThrow(
        () ->
            ControlProtocolCodec.encodeBinary(
                runtimeStatus(
                    RuntimeStatus.newBuilder()
                        .setRuntimeStatus(
                            withConfiguration(
                                ConfigurationStatus.newBuilder()
                                    .setAppliedVersion(2)
                                    .setProxyUrl("http://127.0.0.1:7890")
                                    .setError(
                                        ConfigurationError.newBuilder()
                                            .setVersion(2)
                                            .setCode("PROXY_UNREACHABLE")
                                            .setField("proxyUrl"))))
                        .build())));
  }

  @Test
  void rejectsProxyWithoutAppliedVersion() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                runtimeStatus(
                    RuntimeStatus.newBuilder()
                        .setRuntimeStatus(
                            withConfiguration(
                                ConfigurationStatus.newBuilder()
                                    .setProxyUrl("http://127.0.0.1:7890")))
                        .build())));
  }

  @Test
  void rejectsNonOriginProxyAndNonOriginStudioUrl() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                runtimeStatus(
                    RuntimeStatus.newBuilder()
                        .setRuntimeStatus(
                            withConfiguration(
                                ConfigurationStatus.newBuilder()
                                    .setAppliedVersion(1)
                                    .setProxyUrl("https://127.0.0.1:7890")))
                        .build())));

    EnvironmentConfig badStudio =
        EnvironmentConfig.newBuilder()
            .setVersion(1)
            .setConfig(DaemonConfiguration.newBuilder().setStudioUrl("https://a.b/path"))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                Envelope.newBuilder()
                    .setMessageType(MessageType.ENVIRONMENT_CONFIG)
                    .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
                    .setEnvironmentConfig(badStudio)
                    .build()));
  }

  @Test
  void rejectsConfigurationErrorWithUnstableCodeOrControlField() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                runtimeStatus(
                    RuntimeStatus.newBuilder()
                        .setRuntimeStatus(
                            withConfiguration(
                                ConfigurationStatus.newBuilder()
                                    .setError(
                                        ConfigurationError.newBuilder()
                                            .setVersion(1)
                                            .setCode("bad-code"))))
                        .build())));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                runtimeStatus(
                    RuntimeStatus.newBuilder()
                        .setRuntimeStatus(
                            withConfiguration(
                                ConfigurationStatus.newBuilder()
                                    .setError(
                                        ConfigurationError.newBuilder()
                                            .setVersion(1)
                                            .setCode("OK")
                                            .setField("a\tb"))))
                        .build())));
  }

  @Test
  void rejectsUpdateProgressWithTotalBelowDownloaded() {
    RuntimeSnapshot snapshot =
        snapshot()
            .setStatus(
                RuntimeBody.newBuilder()
                    .setUpdate(
                        UpdateProgress.newBuilder()
                            .setTargetVersion("1.2.3")
                            .setDownloadedBytes(10)
                            .setTotalBytes(5)))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                runtimeStatus(RuntimeStatus.newBuilder().setRuntimeStatus(snapshot).build())));
  }

  @Test
  void rejectsEmptyLspServersAndBadServerFields() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                environmentConfig(
                    DaemonConfiguration.newBuilder()
                        .setStudioUrl("https://studio.example.com")
                        .setLsp(LspConfiguration.getDefaultInstance()))));

    // 服务器 id 违反 [A-Za-z0-9_.-]+
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                environmentConfig(
                    DaemonConfiguration.newBuilder()
                        .setStudioUrl("https://studio.example.com")
                        .setLsp(lsp(LspServer.newBuilder().setId("bad id").addCommand("java"))))));

    // 重复 id
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                environmentConfig(
                    DaemonConfiguration.newBuilder()
                        .setStudioUrl("https://studio.example.com")
                        .setLsp(
                            LspConfiguration.newBuilder()
                                .addServers(LspServer.newBuilder().setId("java").addCommand("java"))
                                .addServers(
                                    LspServer.newBuilder().setId("java").addCommand("java"))))));

    // command 必填
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                environmentConfig(
                    DaemonConfiguration.newBuilder()
                        .setStudioUrl("https://studio.example.com")
                        .setLsp(lsp(LspServer.newBuilder().setId("java"))))));

    // extensions 必须以点开头
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                environmentConfig(
                    DaemonConfiguration.newBuilder()
                        .setStudioUrl("https://studio.example.com")
                        .setLsp(
                            lsp(
                                LspServer.newBuilder()
                                    .setId("java")
                                    .addCommand("java")
                                    .addExtensions("java"))))));
  }

  @Test
  void acceptsLspWithoutOptionalLists() {
    assertDoesNotThrow(
        () ->
            ControlProtocolCodec.encodeBinary(
                environmentConfig(
                    DaemonConfiguration.newBuilder()
                        .setStudioUrl("https://studio.example.com")
                        .setBashExecutable("/usr/bin/bash")
                        .setLsp(lsp(LspServer.newBuilder().setId("java").addCommand("java"))))));
  }

  @Test
  void rejectsSettingsWithoutBudgetsAndNonPositiveBudgets() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                Envelope.newBuilder()
                    .setMessageType(MessageType.SETTINGS)
                    .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
                    .setSettings(Settings.getDefaultInstance())
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                Envelope.newBuilder()
                    .setMessageType(MessageType.SETTINGS)
                    .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
                    .setSettings(
                        Settings.newBuilder()
                            .setResourceBudgets(
                                ResourceBudgets.newBuilder()
                                    .setTemporaryResourceTtlSeconds(0)
                                    .setTemporaryResourceCleanupIntervalSeconds(60)))
                    .build()));
  }

  @Test
  void rejectsReadyAndHeartbeatWithoutRequiredParts() {
    Ready full = ReadinessFixtures.ready();
    assertThrows(
        ControlProtocolException.class, () -> encodeReady(full.toBuilder().clearHostInfo()));
    assertThrows(
        ControlProtocolException.class, () -> encodeReady(full.toBuilder().clearRuntimeStatus()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            encodeReady(
                full.toBuilder()
                    .setRuntimeStatus(
                        full.getRuntimeStatus().toBuilder()
                            .setStatus(RuntimeBody.newBuilder().setDaemonVersion("9.9.9")))));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                Envelope.newBuilder()
                    .setMessageType(MessageType.HEARTBEAT)
                    .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
                    .setHeartbeat(Heartbeat.getDefaultInstance())
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                Envelope.newBuilder()
                    .setMessageType(MessageType.HEARTBEAT)
                    .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
                    .setHeartbeat(
                        Heartbeat.newBuilder()
                            .setRuntimeStatus(full.getRuntimeStatus())
                            .setIdeLeaseRound(0))
                    .build()));
  }

  @Test
  void rejectsHostInfoWithoutSystemAndInvalidTimezone() {
    Ready full = ReadinessFixtures.ready();
    HostInfo host = full.getHostInfo();
    assertThrows(
        ControlProtocolException.class,
        () ->
            encodeReady(
                full.toBuilder()
                    .setHostInfo(
                        host.toBuilder()
                            .setOperatingSystem(OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED))));
    assertThrows(
        ControlProtocolException.class,
        () ->
            encodeReady(
                full.toBuilder()
                    .setHostInfo(
                        host.toBuilder().setArchitecture(Architecture.ARCHITECTURE_UNSPECIFIED))));
    assertThrows(
        ControlProtocolException.class,
        () -> encodeReady(full.toBuilder().setHostInfo(host.toBuilder().setTimeZone("Not/AZone"))));
    assertThrows(
        ControlProtocolException.class,
        () -> encodeReady(full.toBuilder().setHostInfo(host.toBuilder().setUserName(" padded "))));
  }

  @Test
  void rejectsExpectedFileOutsideEditAndWriteOrWithoutSha() throws Exception {
    Envelope edit = ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.golden("invoke.bin"));
    Invoke invoke = edit.getInvoke();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                edit.toBuilder()
                    .setInvoke(
                        invoke.toBuilder()
                            .setCapabilityId("fs.read")
                            .setExpectedFile(
                                ExpectedFile.newBuilder()
                                    .setPath("/tmp/a")
                                    .setSha256("a".repeat(64))))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                edit.toBuilder()
                    .setInvoke(
                        invoke.toBuilder()
                            .setCapabilityId("fs.edit")
                            .setExpectedFile(ExpectedFile.newBuilder().setPath("/tmp/a")))
                    .build()));
  }

  private static Envelope environmentConfig(DaemonConfiguration.Builder configuration) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.ENVIRONMENT_CONFIG)
        .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
        .setEnvironmentConfig(EnvironmentConfig.newBuilder().setVersion(1).setConfig(configuration))
        .build();
  }

  private static LspConfiguration lsp(LspServer.Builder server) {
    return LspConfiguration.newBuilder().addServers(server).build();
  }

  private static void encodeReady(Ready.Builder ready) {
    ControlProtocolCodec.encodeBinary(
        Envelope.newBuilder()
            .setMessageType(MessageType.READY)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setReady(ready)
            .build());
  }

  /** 合法的 ready 基线：其余用例只改动被测的那一处。 */
  private static final class ReadinessFixtures {

    private static Ready ready() {
      RuntimeSnapshot snapshot =
          RuntimeSnapshot.newBuilder()
              .setRevision(1)
              .setStatus(RuntimeBody.newBuilder().setDaemonVersion("1.2.3"))
              .build();
      return Ready.newBuilder()
          .setDaemonVersion("1.2.3")
          .setHostInfo(
              HostInfo.newBuilder()
                  .setOperatingSystem(OperatingSystem.LINUX)
                  .setArchitecture(Architecture.AMD64)
                  .setTimeZone("Asia/Shanghai")
                  .setUserName("daemon")
                  .setHomeDirectory("/opt/daemon-home")
                  .setTempDirectory("/tmp"))
          .setRuntimeStatus(snapshot)
          .build();
    }
  }
}
