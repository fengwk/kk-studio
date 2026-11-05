package fun.fengwk.kkstudio.platform.daemon.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillPackage;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillStatePackage;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.platform.daemon.DaemonUpdateProgress;

import java.io.IOException;
import java.util.List;

/** 控制消息的 binary golden 与应用层边界测试。 */
class ControlProtocolCodecTest {

  /** 目录结果与视图回执互斥，新命令遵守绑定和路径边界。 */
  @Test
  void validatesDirectoryListingAndMobileCommands() {
    Envelope command =
        ControlProtocolCodec.decodeBinary(
            ControlProtocolFixtures.goldenUnchecked("ide-command.bin"));
    IdeCommand list =
        IdeCommand.newBuilder().setAction(IdeAction.LIST_DIRECTORIES).setDirectory("/a b").build();
    ControlProtocolCodec.encodeBinary(command.toBuilder().setIdeCommand(list).build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder()
                    .setIdeCommand(list.toBuilder().setViewId("unexpected"))
                    .build()));
    IdeCommand mobile =
        IdeCommand.newBuilder()
            .setAction(IdeAction.COLLAPSE_MOBILE)
            .setViewId(command.getIdeCommand().getViewId())
            .setDocumentId(command.getIdeCommand().getDocumentId())
            .setBindingEpoch(1)
            .build();
    ControlProtocolCodec.encodeBinary(command.toBuilder().setIdeCommand(mobile).build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder().setIdeCommand(mobile.toBuilder().setDirectory("/x")).build()));
    Envelope result =
        ControlProtocolCodec.decodeBinary(
            ControlProtocolFixtures.goldenUnchecked("ide-result.bin"));
    IdeDirectoryListing listing =
        IdeDirectoryListing.newBuilder()
            .setPath("/")
            .setTruncated(false)
            .addChildren(IdeDirectoryEntry.newBuilder().setName("x").setPath("/x"))
            .build();
    IdeResult reply = IdeResult.newBuilder().setOk(true).setDirectoryListing(listing).build();
    ControlProtocolCodec.decodeBinary(
        ControlProtocolCodec.encodeBinary(result.toBuilder().setIdeResult(reply).build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(reply.toBuilder().setViewId(mobile.getViewId()))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(
                        reply.toBuilder().setDirectoryListing(listing.toBuilder().clearTruncated()))
                    .build()));
    IdeDirectoryListing.Builder large = listing.toBuilder();
    for (int i = 0; i < 1000; i++) {
      large.addChildren(listing.getChildren(0));
    }
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(reply.toBuilder().setDirectoryListing(large))
                    .build()));
  }

  /** 各类消息的非法枚举、缺失必填和越界都拒绝，合法边界可以编码。 */
  @Test
  void validatesMessageBoundaries() {
    Envelope settings =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("settings.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                settings.toBuilder()
                    .setInvocationId(ControlProtocolFixtures.INVOCATION_ID)
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                settings.toBuilder()
                    .setSettings(
                        settings.getSettings().toBuilder()
                            .setResourceBudgets(
                                settings.getSettings().getResourceBudgets().toBuilder()
                                    .setMaxResourceBytes(0)))
                    .build()));

    Envelope ready =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("ready.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ready.toBuilder()
                    .setReady(
                        ready.getReady().toBuilder()
                            .setEnvironment(
                                ready.getReady().getEnvironment().toBuilder()
                                    .setOperatingSystem(
                                        OperatingSystem.OPERATING_SYSTEM_UNSPECIFIED)))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ready.toBuilder()
                    .setReady(ready.getReady().toBuilder().setDaemonVersion("other"))
                    .build()));

    Envelope heartbeat =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("heartbeat.bin"));
    ControlProtocolCodec.encodeBinary(
        heartbeat.toBuilder()
            .setHeartbeat(heartbeat.getHeartbeat().toBuilder().setIdeLeaseRound(1))
            .build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                heartbeat.toBuilder()
                    .setHeartbeat(heartbeat.getHeartbeat().toBuilder().setIdeLeaseRound(0))
                    .build()));

    Envelope started =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("started.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                started.toBuilder()
                    .setStarted(started.getStarted().toBuilder().setReplayed(false))
                    .build()));

    Envelope progress =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("progress.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                progress.toBuilder()
                    .setProgress(
                        progress.getProgress().toBuilder()
                            .setResult(
                                progress.getProgress().getResult().toBuilder()
                                    .addContents(
                                        ControlProtocolFixtures.resource(
                                            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                            "text/plain",
                                            null,
                                            0,
                                            "b".repeat(64),
                                            null))))
                    .build()));

    Envelope ticket =
        ControlProtocolCodec.decodeBinary(
            ControlProtocolFixtures.goldenUnchecked("upload-ticket.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ticket.toBuilder()
                    .setResourceUploadTicket(
                        ticket.getResourceUploadTicket().toBuilder()
                            .setState(TicketState.TICKET_STATE_UNSPECIFIED))
                    .build()));
    Envelope readyTicket =
        ticket.toBuilder()
            .setResourceUploadTicket(
                ResourceUploadTicket.newBuilder()
                    .setTransferId(ticket.getResourceUploadTicket().getTransferId())
                    .setState(TicketState.TICKET_READY)
                    .setUploadId(ticket.getResourceUploadTicket().getUploadId()))
            .build();
    ControlProtocolCodec.encodeBinary(readyTicket);
    Envelope failedTicket =
        ticket.toBuilder()
            .setResourceUploadTicket(
                ResourceUploadTicket.newBuilder()
                    .setTransferId(ticket.getResourceUploadTicket().getTransferId())
                    .setState(TicketState.TICKET_FAILED)
                    .setMessage("nope"))
            .build();
    ControlProtocolCodec.encodeBinary(failedTicket);

    Envelope command =
        ControlProtocolCodec.decodeBinary(
            ControlProtocolFixtures.goldenUnchecked("ide-command.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder()
                    .setIdeCommand(
                        command.getIdeCommand().toBuilder()
                            .setAction(IdeAction.LIST_VIEWS)
                            .setDirectory("/x"))
                    .build()));
    ControlProtocolCodec.encodeBinary(
        command.toBuilder()
            .setIdeCommand(
                IdeCommand.newBuilder()
                    .setAction(IdeAction.CHANGE_DIRECTORY)
                    .setViewId(command.getIdeCommand().getViewId())
                    .setDocumentId(command.getIdeCommand().getDocumentId())
                    .setBindingEpoch(1)
                    .setDirectory("/work"))
            .build());
    ControlProtocolCodec.encodeBinary(
        command.toBuilder()
            .setIdeCommand(
                IdeCommand.newBuilder()
                    .setAction(IdeAction.CLOSE_VIEW)
                    .setViewId(command.getIdeCommand().getViewId())
                    .setDocumentId(command.getIdeCommand().getDocumentId())
                    .setBindingEpoch(1)
                    .setDiscard(true))
            .build());

    Envelope result =
        ControlProtocolCodec.decodeBinary(
            ControlProtocolFixtures.goldenUnchecked("ide-result.bin"));
    ControlProtocolCodec.encodeBinary(
        result.toBuilder()
            .setIdeResult(IdeResult.newBuilder().setOk(false).setCode("BUSY").setMessage("later"))
            .build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(
                        IdeResult.newBuilder().setOk(false).setCode("busy").setMessage("later"))
                    .build()));
    ControlProtocolCodec.encodeBinary(
        result.toBuilder().setIdeResult(IdeResult.newBuilder().setOk(true)).build());

    Envelope update =
        Envelope.newBuilder()
            .setMessageType(MessageType.UPDATE)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setUpdate(
                Update.newBuilder()
                    .setOperationId("11111111-1111-1111-1111-111111111111")
                    .setTargetVersion("1.0.9"))
            .build();
    assertEquals(
        update, ControlProtocolCodec.decodeBinary(ControlProtocolCodec.encodeBinary(update)));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                update.toBuilder()
                    .setUpdate(update.getUpdate().toBuilder().setTargetVersion("bad version"))
                    .build()));
    Envelope updateResult =
        ControlProtocolCodec.decodeBinary(
            ControlProtocolFixtures.goldenUnchecked("update-result.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                updateResult.toBuilder()
                    .setUpdateResult(
                        updateResult.getUpdateResult().toBuilder()
                            .setPhase(UpdatePhase.UPDATE_PHASE_UNSPECIFIED))
                    .build()));
    ControlProtocolCodec.encodeBinary(
        updateResult.toBuilder()
            .setUpdateResult(updateResult.getUpdateResult().toBuilder().setMessage("prepared"))
            .build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                updateResult.toBuilder()
                    .setUpdateResult(updateResult.getUpdateResult().toBuilder().setMessage("bad\n"))
                    .build()));

    IdeCommand open = command.getIdeCommand();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder()
                    .setIdeCommand(open.toBuilder().setAction(IdeAction.IDE_ACTION_UNSPECIFIED))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder().setIdeCommand(open.toBuilder().setBindingEpoch(1)).build()));
    ControlProtocolCodec.encodeBinary(
        command.toBuilder()
            .setIdeCommand(
                IdeCommand.newBuilder()
                    .setAction(IdeAction.FOCUS_TERMINAL)
                    .setViewId(open.getViewId())
                    .setDocumentId(open.getDocumentId())
                    .setBindingEpoch(1))
            .build());
    // 未声明的枚举值必须被拒绝，即使其它字段形状与 bound 动作一致。
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder()
                    .setIdeCommand(
                        IdeCommand.newBuilder()
                            .setActionValue(999)
                            .setViewId(open.getViewId())
                            .setDocumentId(open.getDocumentId())
                            .setBindingEpoch(1))
                    .build()),
        "undeclared action value 999 must be rejected");
    ControlProtocolCodec.encodeBinary(
        command.toBuilder()
            .setIdeCommand(IdeCommand.newBuilder().setAction(IdeAction.LIST_VIEWS))
            .build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder()
                    .setIdeCommand(
                        IdeCommand.newBuilder()
                            .setAction(IdeAction.CHANGE_DIRECTORY)
                            .setViewId(open.getViewId())
                            .setDocumentId(open.getDocumentId())
                            .setBindingEpoch(1)
                            .setDiscard(true)
                            .setDirectory("/work"))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                command.toBuilder()
                    .setIdeCommand(
                        IdeCommand.newBuilder()
                            .setAction(IdeAction.CLOSE_VIEW)
                            .setViewId(open.getViewId())
                            .setDocumentId(open.getDocumentId())
                            .setBindingEpoch(1))
                    .build()));

    IdeViewSummary view = result.getIdeResult().getViews(0);
    ControlProtocolCodec.encodeBinary(
        result.toBuilder()
            .setIdeResult(
                IdeResult.newBuilder()
                    .setOk(true)
                    .setViewId(view.getViewId())
                    .setDocumentId(view.getDocumentId())
                    .setEventNodeId(view.getEventNodeId())
                    .setConnectionId(view.getConnectionId())
                    .setClaimId(view.getClaimId())
                    .setBindingEpoch(view.getBindingEpoch())
                    .setDirectory(view.getDirectory())
                    .setWorkspacePath(view.getWorkspacePath()))
            .build());
    ControlProtocolCodec.encodeBinary(
        result.toBuilder()
            .setIdeResult(
                IdeResult.newBuilder()
                    .setOk(true)
                    .setViewId(view.getViewId())
                    .setDirectory("/work"))
            .build());
    ControlProtocolCodec.encodeBinary(
        result.toBuilder()
            .setIdeResult(IdeResult.newBuilder().setOk(true).setViewId(view.getViewId()))
            .build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder().setIdeResult(IdeResult.newBuilder()).build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(
                        result.getIdeResult().toBuilder()
                            .addViews(view.toBuilder().setViewId(view.getViewId())))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(
                        IdeResult.newBuilder()
                            .setOk(false)
                            .setCode("BUSY")
                            .setViewId(view.getViewId()))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                result.toBuilder()
                    .setIdeResult(result.getIdeResult().toBuilder().setMessage("extra"))
                    .build()));

    Envelope lease =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("ide-lease.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                lease.toBuilder()
                    .setIdeLease(
                        lease.getIdeLease().toBuilder()
                            .addBindings(lease.getIdeLease().getBindings(0)))
                    .build()));

    Envelope error =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("error.bin"));
    assertEquals("retry", error.getError().getMessage());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                error.toBuilder().setError(error.getError().toBuilder().setMessage("")).build()));

    assertThrows(ControlProtocolException.class, () -> ControlProtocolValidator.validate(null));
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.decodeBinary(new byte[0]));
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.decodeBinary(new byte[] {0x0a}));
  }

  /** Java 构造结果必须与 TS 生成的共享 golden 逐字节一致。 */
  @Test
  void encodesFixtureMessagesToSharedGolden() throws IOException {
    Envelope settings =
        Envelope.newBuilder()
            .setMessageType(MessageType.SETTINGS)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setSettings(
                Settings.newBuilder()
                    .setDisplayName("dev-box")
                    .setProxyUrl("http://proxy.invalid:8080")
                    .setNoProxyHosts("localhost,10.0.0.0/8")
                    .setResourceBudgets(
                        ResourceBudgets.newBuilder()
                            .setMaxResourceBytes(1048576)
                            .setTemporaryResourceTtlSeconds(3600)
                            .setTemporaryResourceCleanupIntervalSeconds(60)))
            .build();
    assertArrayEquals(
        ControlProtocolFixtures.golden("settings.bin"),
        ControlProtocolCodec.encodeBinary(settings));
    assertArrayEquals(
        ControlProtocolFixtures.golden("invoke.bin"),
        ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.invokeEnvelope()));
    assertArrayEquals(
        ControlProtocolFixtures.golden("completed.bin"),
        ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.completedEnvelope()));
    assertArrayEquals(
        ControlProtocolFixtures.golden("execution-exited.bin"),
        ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.executionExitedEnvelope()));
    DaemonUpdateCommand command =
        new DaemonUpdateCommand("88888888-8888-4888-8888-888888888888", "1.2.3");
    Envelope update =
        Envelope.newBuilder()
            .setMessageType(MessageType.UPDATE)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setUpdate(
                Update.newBuilder()
                    .setOperationId(command.operationId())
                    .setTargetVersion(command.targetVersion())
                    .build())
            .build();
    assertArrayEquals(
        ControlProtocolFixtures.golden("update.bin"), ControlProtocolCodec.encodeBinary(update));
  }

  /** 共享 golden 解码后重新编码必须逐字节相同，裸消息和帧路径都要覆盖。 */
  @Test
  void roundtripsSharedGoldenByteForByte() throws IOException {
    for (String name : ControlProtocolFixtures.GOLDEN_NAMES) {
      byte[] golden = ControlProtocolFixtures.golden(name);
      assertArrayEquals(
          golden, ControlProtocolCodec.encodeBinary(ControlProtocolCodec.decodeBinary(golden)));
      byte[] framed = ControlProtocolCodec.encodeFrame(ControlProtocolCodec.decodeBinary(golden));
      assertArrayEquals(
          golden,
          ControlProtocolCodec.encodeBinary(
              ControlProtocolCodec.decodeBinary(ControlFraming.unframe(framed))));
    }
  }

  /** opaque JSON 只校验、不重序列化：指数、高精度小数和 -0 必须逐字保留。 */
  @Test
  void preservesOpaqueJsonText() throws IOException {
    Envelope invoke =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.golden("invoke.bin"));
    assertEquals(ControlProtocolFixtures.ARGUMENTS_JSON, invoke.getInvoke().getArgumentsJson());

    Envelope completed =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.golden("completed.bin"));
    CapabilityResult result = completed.getCompleted().getResult();
    assertEquals(ControlProtocolFixtures.DETAILS_JSON, result.getDetailsJson());
    assertEquals(ControlProtocolFixtures.JSON_CONTENT, result.getContents(1).getJson().getJson());
  }

  /** UPDATE 仅包含关联 ID 和版本，旧制品字段及其他未知字段均不得通过边界。 */
  @Test
  void validatesUpdateCommandAndRejectsUnknownFields() {
    DaemonUpdateCommand command =
        new DaemonUpdateCommand("11111111-1111-1111-1111-111111111111", "1.0.9");
    Update update =
        Update.newBuilder()
            .setOperationId(command.operationId())
            .setTargetVersion(command.targetVersion())
            .build();
    assertEquals(2, update.getAllFields().size());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolValidator.validateUpdate(update.toBuilder().clearOperationId().build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolValidator.validateUpdate(
                update.toBuilder().setOperationId("bad").build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolValidator.validateUpdate(
                update.toBuilder().clearTargetVersion().build()));
    assertThrows(IllegalArgumentException.class, () -> new DaemonUpdateCommand("bad", "1.0.9"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonUpdateCommand(command.operationId(), "bad version"));
    for (int fieldNumber : new int[] {3, 4, 100}) {
      UnknownFieldSet unknown =
          UnknownFieldSet.newBuilder()
              .addField(fieldNumber, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
              .build();
      Update invalid = update.toBuilder().mergeUnknownFields(unknown).build();
      assertThrows(
          ControlProtocolException.class, () -> ControlProtocolValidator.validateUpdate(invalid));
      Envelope envelope =
          Envelope.newBuilder()
              .setMessageType(MessageType.UPDATE)
              .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
              .setUpdate(invalid)
              .build();
      assertThrows(
          ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(envelope));
      assertThrows(
          ControlProtocolException.class,
          () -> ControlProtocolCodec.decodeBinary(envelope.toByteArray()));
    }
  }

  /** 空 text 是合法业务值；present 与内容非空不是同一个约束。 */
  @Test
  void allowsEmptyTextContent() {
    ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.completedEnvelopeWithText(""));
  }

  /** envelope、Invoke、Content 的未知 protobuf 字段都递归拒绝。 */
  @Test
  void rejectsUnknownFieldsRecursively() throws IOException {
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    Envelope envelopeUnknown =
        ControlProtocolFixtures.executionExitedEnvelope().toBuilder()
            .mergeUnknownFields(unknown)
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(envelopeUnknown));

    Envelope invokeUnknown =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setInvoke(
                ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                    .mergeUnknownFields(unknown))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(invokeUnknown));

    Envelope completed = ControlProtocolFixtures.completedEnvelope();
    CapabilityResult result = completed.getCompleted().getResult();
    Content contentWithUnknown =
        result.getContents(0).toBuilder().mergeUnknownFields(unknown).build();
    CapabilityResult resultWithUnknown =
        result.toBuilder().setContents(0, contentWithUnknown).build();
    Envelope completedUnknown =
        completed.toBuilder()
            .setCompleted(completed.getCompleted().toBuilder().setResult(resultWithUnknown))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(completedUnknown));

    byte[] golden = ControlProtocolFixtures.golden("invoke.bin");
    byte[] withUnknown = new byte[golden.length + 3];
    System.arraycopy(golden, 0, withUnknown, 0, golden.length);
    withUnknown[golden.length] = (byte) 0xa0;
    withUnknown[golden.length + 1] = 0x06;
    withUnknown[golden.length + 2] = 0x01;
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.decodeBinary(withUnknown));
  }

  /** 嵌入 JSON 的重复 key、非法 JSON 和缺失必填都必须拒绝。 */
  @Test
  void rejectsInvalidJsonAndMissingRequiredFields() {
    Envelope duplicateKey =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setInvoke(
                ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                    .setArgumentsJson("{\"a\":1,\"a\":2}"))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(duplicateKey));

    Envelope invalidJson =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setInvoke(
                ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                    .setArgumentsJson("{\"a\":}"))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(invalidJson));

    Envelope missingCapabilityId =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setInvoke(Invoke.newBuilder().setArgumentsJson("{}").setTimeoutMillis(0))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(missingCapabilityId));
  }

  /** 安全整数、UTF-8 字节上限、媒体类型、SHA 和 UUID 都在应用层拒绝。 */
  @Test
  void rejectsOutOfRangeAndInvalidText() {
    Envelope unsafeTimeout =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setInvoke(
                ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                    .setTimeoutMillis(DaemonUpdateProgress.MAX_SAFE_BYTE_COUNT + 1))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(unsafeTimeout));

    Envelope longName =
        ControlProtocolFixtures.completedEnvelope().toBuilder()
            .setCompleted(
                Completed.newBuilder()
                    .setResult(
                        CapabilityResult.newBuilder()
                            .setCallId(ControlProtocolFixtures.CALL_ID)
                            .setError(false)
                            .setDetailsJson("{}")
                            .addContents(
                                ControlProtocolFixtures.resource(
                                    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                    "text/plain",
                                    "x".repeat(ResourceRef.MAX_NAME_UTF8_BYTES + 1),
                                    0,
                                    "b".repeat(64),
                                    null))))
            .build();
    assertThrows(ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(longName));

    Envelope badResource =
        ControlProtocolFixtures.completedEnvelope().toBuilder()
            .setCompleted(
                Completed.newBuilder()
                    .setResult(
                        CapabilityResult.newBuilder()
                            .setCallId(ControlProtocolFixtures.CALL_ID)
                            .setError(false)
                            .setDetailsJson("{}")
                            .addContents(
                                ControlProtocolFixtures.resource(
                                    "not-a-uuid", "TEXT/PLAIN", null, 0, "not-sha", null))))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(badResource));
  }

  /** messageType、payload 与 scope 必须相互匹配，且 envelope 必须是最新协议版本。 */
  @Test
  void rejectsProtocolAndScopeMismatches() {
    Envelope payloadMismatch =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setMessageType(MessageType.COMPLETED)
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(payloadMismatch));

    Envelope unspecified =
        ControlProtocolFixtures.invokeEnvelope().toBuilder()
            .setMessageType(MessageType.MESSAGE_TYPE_UNSPECIFIED)
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(unspecified));

    Envelope missingInvocation =
        ControlProtocolFixtures.invokeEnvelope().toBuilder().clearInvocationId().build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(missingInvocation));

    for (String malformedInvocationId :
        new String[] {
          "not-a-uuid",
          "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
          "{" + ControlProtocolFixtures.INVOCATION_ID + "}"
        }) {
      Envelope invalidInvocation =
          ControlProtocolFixtures.invokeEnvelope().toBuilder()
              .setInvocationId(malformedInvocationId)
              .build();
      assertThrows(
          ControlProtocolException.class,
          () -> ControlProtocolCodec.encodeBinary(invalidInvocation));
    }

    Envelope invalidEnvironment =
        ControlProtocolFixtures.invokeEnvelope().toBuilder().setEnvironmentId("not-a-uuid").build();
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.encodeBinary(invalidEnvironment));
  }

  /** 非法 UTF-8 wire、超过内容条数和空裸消息都在解析/长度边界拒绝。 */
  @Test
  void rejectsMalformedWireAndContentOverflow() {
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.decodeBinary(new byte[] {0x1a, 0x01, (byte) 0xff}));

    CapabilityResult.Builder result =
        CapabilityResult.newBuilder()
            .setCallId(ControlProtocolFixtures.CALL_ID)
            .setError(false)
            .setDetailsJson("{}");
    for (int index = 0; index <= EnvironmentCapabilityResult.MAX_CONTENT_ITEMS; index++) {
      result.addContents(ControlProtocolFixtures.text("x"));
    }
    Envelope tooManyContents =
        ControlProtocolFixtures.completedEnvelope().toBuilder()
            .setCompleted(Completed.newBuilder().setResult(result))
            .build();
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(tooManyContents));

    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.decodeBinary(new byte[0]));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.decodeBinary(new byte[ControlFraming.MAX_MESSAGE_BYTES + 1]));
  }

  /** 结构缺字段、空 oneof body 和非法 capability/expectedFile 都必须拒绝。 */
  @Test
  void rejectsMissingStructureAndInvalidInvokeMetadata() {
    assertThrows(ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(null));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.decodeBinary(new byte[] {(byte) 0xff}));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.COMPLETED,
                    Envelope.newBuilder().setCompleted(Completed.getDefaultInstance()))));

    CapabilityResult noError =
        CapabilityResult.newBuilder()
            .setCallId(ControlProtocolFixtures.CALL_ID)
            .setDetailsJson("{}")
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.COMPLETED,
                    Envelope.newBuilder()
                        .setCompleted(Completed.newBuilder().setResult(noError)))));

    CapabilityResult noBody =
        CapabilityResult.newBuilder()
            .setCallId(ControlProtocolFixtures.CALL_ID)
            .setError(false)
            .setDetailsJson("{}")
            .addContents(Content.getDefaultInstance())
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.COMPLETED,
                    Envelope.newBuilder().setCompleted(Completed.newBuilder().setResult(noBody)))));

    CapabilityResult missingJson =
        CapabilityResult.newBuilder()
            .setCallId(ControlProtocolFixtures.CALL_ID)
            .setError(false)
            .setDetailsJson("{}")
            .addContents(Content.newBuilder().setJson(JsonContent.getDefaultInstance()))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.COMPLETED,
                    Envelope.newBuilder()
                        .setCompleted(Completed.newBuilder().setResult(missingJson)))));

    Invoke missingArguments =
        Invoke.newBuilder()
            .setCapabilityId(ControlProtocolFixtures.CAPABILITY_ID)
            .setTimeoutMillis(0)
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.INVOKE, Envelope.newBuilder().setInvoke(missingArguments))));

    Invoke invalidCapability =
        ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
            .setCapabilityId("FS.EDIT")
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.INVOKE, Envelope.newBuilder().setInvoke(invalidCapability))));

    Invoke expectedFileOnOtherCapability =
        Invoke.newBuilder()
            .setCapabilityId("fs.read")
            .setArgumentsJson("{}")
            .setTimeoutMillis(0)
            .setExpectedFile(
                ExpectedFile.newBuilder()
                    .setPath(ControlProtocolFixtures.EXPECTED_PATH)
                    .setSha256(ControlProtocolFixtures.SHA_A))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.INVOKE,
                    Envelope.newBuilder().setInvoke(expectedFileOnOtherCapability))));

    Invoke missingExpectedSha =
        ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
            .setExpectedFile(
                ExpectedFile.newBuilder().setPath(ControlProtocolFixtures.EXPECTED_PATH))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.INVOKE, Envelope.newBuilder().setInvoke(missingExpectedSha))));

    Invoke invalidExpectedSha =
        ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
            .setExpectedFile(
                ExpectedFile.newBuilder()
                    .setPath(ControlProtocolFixtures.EXPECTED_PATH)
                    .setSha256("A".repeat(64)))
            .build();
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.envelope(
                    MessageType.INVOKE, Envelope.newBuilder().setInvoke(invalidExpectedSha))));
  }

  /** 文本 blank、字符/UTF-8 上限、非法 Unicode 和非法资源元数据都必须拒绝。 */
  @Test
  void rejectsInvalidTextAndResourceMetadata() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.invokeEnvelope().toBuilder()
                    .setInvocationId("   ")
                    .build()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson("{}")
                                    .addContents(
                                        ControlProtocolFixtures.resource(
                                            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                            "text/plain",
                                            "a\u0000b",
                                            0,
                                            "b".repeat(64),
                                            null))))
                    .build()));

    for (String invalidName : new String[] {"", "\uD800"}) {
      assertThrows(
          ControlProtocolException.class,
          () ->
              ControlProtocolCodec.encodeBinary(
                  ControlProtocolFixtures.completedEnvelope().toBuilder()
                      .setCompleted(
                          Completed.newBuilder()
                              .setResult(
                                  CapabilityResult.newBuilder()
                                      .setCallId(ControlProtocolFixtures.CALL_ID)
                                      .setError(false)
                                      .setDetailsJson("{}")
                                      .addContents(
                                          ControlProtocolFixtures.resource(
                                              "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                              "text/plain",
                                              invalidName,
                                              0,
                                              "b".repeat(64),
                                              null))))
                      .build()));
    }

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson("{}")
                                    .addContents(
                                        ControlProtocolFixtures.resource(
                                            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                            "TEXT/PLAIN",
                                            null,
                                            0,
                                            "b".repeat(64),
                                            null))))
                    .build()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson("{}")
                                    .addContents(
                                        Content.newBuilder()
                                            .setResource(
                                                ResourceContent.newBuilder()
                                                    .setMediaType("text/plain")
                                                    .setSize(0)
                                                    .setSha256("b".repeat(64))))))
                    .build()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson("{}")
                                    .addContents(
                                        ControlProtocolFixtures.resource(
                                            "7C9E6679-7425-40DE-944B-E07FC1F90AE7",
                                            "text/plain",
                                            null,
                                            0,
                                            "b".repeat(64),
                                            null))))
                    .build()));
  }

  /** 应用层校验通过但 protobuf 序列化结果超过 16 MiB 时仍必须拒绝。 */
  /** 技能期望集合与状态回报可往返，非法包名、摘要、大小、地址与状态配对在编码前被拒绝。 */
  @Test
  void roundtripsSkillSyncAndSkillState() {
    String sha = "a".repeat(64);
    DaemonSkillPackage pkg =
        new DaemonSkillPackage("pkg-a", sha, 11L, "https://s3.example.com/a?signature=x");
    Envelope sync =
        Envelope.newBuilder()
            .setMessageType(MessageType.SKILL_SYNC)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setSkillSync(ControlProtocolCodec.toProtoSkillSync(List.of(pkg)))
            .build();
    SkillSync decodedSync =
        ControlProtocolCodec.decodeBinary(ControlProtocolCodec.encodeBinary(sync)).getSkillSync();
    assertEquals(1, decodedSync.getPackagesCount());
    assertEquals(pkg.packageName(), decodedSync.getPackages(0).getPackageName());
    assertEquals(pkg.sha256(), decodedSync.getPackages(0).getSha256());
    assertEquals(pkg.sizeBytes(), decodedSync.getPackages(0).getSizeBytes());
    assertEquals(pkg.downloadUrl(), decodedSync.getPackages(0).getDownloadUrl());

    DaemonSkillStatePackage installed =
        new DaemonSkillStatePackage("pkg-a", sha, "/home/dev/skills/pkg-a", null);
    DaemonSkillStatePackage failed =
        new DaemonSkillStatePackage("pkg-b", null, null, "ARCHIVE_DIGEST_MISMATCH");
    SkillState statePayload =
        SkillState.newBuilder()
            .addPackages(
                SkillPackageState.newBuilder()
                    .setPackageName("pkg-a")
                    .setInstalledSha256(sha)
                    .setLocalPath("/home/dev/skills/pkg-a")
                    .build())
            .addPackages(
                SkillPackageState.newBuilder()
                    .setPackageName("pkg-b")
                    .setError("ARCHIVE_DIGEST_MISMATCH")
                    .build())
            .build();
    Envelope state =
        Envelope.newBuilder()
            .setMessageType(MessageType.SKILL_STATE)
            .setEnvironmentId(ControlProtocolFixtures.ENVIRONMENT_ID)
            .setSkillState(statePayload)
            .build();
    assertEquals(
        List.of(installed, failed),
        ControlProtocolCodec.toDomainSkillState(
            ControlProtocolCodec.decodeBinary(ControlProtocolCodec.encodeBinary(state))
                .getSkillState()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                sync.toBuilder()
                    .setSkillSync(
                        ControlProtocolCodec.toProtoSkillSync(
                            List.of(
                                new DaemonSkillPackage(
                                    "pkg-a", sha, 11L, "ftp://s3.example.com/a"))))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                sync.toBuilder()
                    .setSkillSync(
                        ControlProtocolCodec.toProtoSkillSync(
                            List.of(new DaemonSkillPackage("Pkg.A", sha, 11L, "https://x/a"))))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                sync.toBuilder()
                    .setSkillSync(
                        ControlProtocolCodec.toProtoSkillSync(
                            List.of(
                                new DaemonSkillPackage(
                                    "pkg-a", sha.toUpperCase(), 11L, "https://x/a"))))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                sync.toBuilder()
                    .setSkillSync(
                        SkillSync.newBuilder()
                            .addPackages(
                                SkillPackage.newBuilder()
                                    .setPackageName("pkg-a")
                                    .setSha256(sha)
                                    .setSizeBytes(-1)
                                    .setDownloadUrl("https://x/a")))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                sync.toBuilder()
                    .setSkillSync(
                        ControlProtocolCodec.toProtoSkillSync(
                            List.of(
                                new DaemonSkillPackage("pkg-a", sha, 1L, "https://x/a"),
                                new DaemonSkillPackage("pkg-a", sha, 1L, "https://x/b"))))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                state.toBuilder()
                    .setSkillState(
                        SkillState.newBuilder()
                            .addPackages(
                                SkillPackageState.newBuilder()
                                    .setPackageName("pkg-a")
                                    .setInstalledSha256(sha)
                                    .build()))
                    .build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                state.toBuilder()
                    .setSkillState(
                        SkillState.newBuilder()
                            .addPackages(
                                SkillPackageState.newBuilder()
                                    .setPackageName("pkg-a")
                                    .setInstalledSha256(sha)
                                    .setLocalPath("/home/dev/pkg-a")
                                    .setError("line\nbreak")
                                    .build()))
                    .build()));
  }

  @Test
  void rejectsOversizedSerializedEnvelope() {
    String tooLarge = "x".repeat(ControlFraming.MAX_MESSAGE_BYTES);
    Envelope envelope =
        ControlProtocolFixtures.completedEnvelope().toBuilder()
            .setCompleted(
                Completed.newBuilder()
                    .setResult(
                        CapabilityResult.newBuilder()
                            .setCallId(ControlProtocolFixtures.CALL_ID)
                            .setError(false)
                            .setDetailsJson("{}")
                            .addContents(ControlProtocolFixtures.text(tooLarge))))
            .build();
    assertThrows(ControlProtocolException.class, () -> ControlProtocolCodec.encodeBinary(envelope));
  }

  /** opaque JSON 的 blank、非法 UTF-8、超限和非法 value 都只在边界拒绝，不做重序列化。 */
  @Test
  void rejectsOpaqueJsonBoundaries() {
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.invokeEnvelope().toBuilder()
                    .setInvoke(
                        ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                            .setArgumentsJson("{\"a\":\"\uD800\"}"))
                    .build()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson("   ")))
                    .build()));

    String oversizedDetails =
        "{\"a\":\"" + "x".repeat(EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES) + "\"}";
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson(oversizedDetails)))
                    .build()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.completedEnvelope().toBuilder()
                    .setCompleted(
                        Completed.newBuilder()
                            .setResult(
                                CapabilityResult.newBuilder()
                                    .setCallId(ControlProtocolFixtures.CALL_ID)
                                    .setError(false)
                                    .setDetailsJson("{}")
                                    .addContents(ControlProtocolFixtures.json("{"))))
                    .build()));
  }
}
