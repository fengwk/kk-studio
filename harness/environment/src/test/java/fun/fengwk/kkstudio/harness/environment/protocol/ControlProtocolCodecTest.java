package fun.fengwk.kkstudio.harness.environment.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/** 控制消息的 binary golden 与应用层边界测试。 */
class ControlProtocolCodecTest {

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

    UpgradeRequired upgrade =
        UpgradeRequired.newBuilder()
            .setTargetProtocolVersion(ControlProtocolCodec.PROTOCOL_VERSION)
            .setArtifactUrl(
                "https://github.com/fengwk/kk-studio/releases/download/v1.2.3/"
                    + "kk-studio-daemon-v1.2.3-linux-amd64.tar.gz")
            .setArtifactSha256("a".repeat(64))
            .build();
    ControlProtocolCodec.validateUpgrade(upgrade);
    ControlProtocolCodec.validateUpgrade(
        upgrade.toBuilder().setTargetProtocolVersion(Integer.MAX_VALUE).build());
    ControlProtocolCodec.validateUpgrade(
        UpgradeRequired.newBuilder().setTargetProtocolVersion(1).build());
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.validateUpgrade(
                upgrade.toBuilder().clearArtifactSha256().build()));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlProtocolCodec.validateUpgrade(upgrade.toBuilder().clearArtifactUrl().build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.validateUpgrade(
                upgrade.toBuilder().clearTargetProtocolVersion().build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.validateUpgrade(
                upgrade.toBuilder().setTargetProtocolVersion(0).build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.validateUpgrade(
                upgrade.toBuilder().setTargetProtocolVersion(-1).build()));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.validateUpgrade(
                upgrade.toBuilder().setArtifactUrl("https://example.invalid/a.tar.gz").build()));

    Envelope update =
        ControlProtocolCodec.decodeBinary(ControlProtocolFixtures.goldenUnchecked("update.bin"));
    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                update.toBuilder()
                    .setUpdate(update.getUpdate().toBuilder().setArtifactSha256("zz"))
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
    ControlProtocolCodec.encodeBinary(
        command.toBuilder()
            .setIdeCommand(
                IdeCommand.newBuilder()
                    .setAction(IdeAction.DETACH_VIEW)
                    .setViewId(open.getViewId())
                    .setDocumentId(open.getDocumentId())
                    .setBindingEpoch(1))
            .build());
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

    assertThrows(ControlProtocolException.class, () -> ControlProtocolCodec.validate(null));
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.decodeBinary(new byte[0]));
    assertThrows(
        ControlProtocolException.class, () -> ControlProtocolCodec.decodeBinary(new byte[] {0x0a}));
  }

  /** 三条消息的 Java 构造结果必须与 TS 生成的共享 golden 逐字节一致。 */
  @Test
  void encodesFixtureMessagesToSharedGolden() throws IOException {
    assertArrayEquals(
        ControlProtocolFixtures.golden("invoke.bin"),
        ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.invokeEnvelope()));
    assertArrayEquals(
        ControlProtocolFixtures.golden("completed.bin"),
        ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.completedEnvelope()));
    assertArrayEquals(
        ControlProtocolFixtures.golden("execution-exited.bin"),
        ControlProtocolCodec.encodeBinary(ControlProtocolFixtures.executionExitedEnvelope()));
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
          golden, ControlProtocolCodec.encodeBinary(ControlProtocolCodec.decodeFrame(framed)));
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
            .setInvoke(
                Invoke.newBuilder()
                    .setCapabilityVersion(ControlProtocolFixtures.CAPABILITY_VERSION)
                    .setArgumentsJson("{}")
                    .setTimeoutMillis(0))
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
                    .setTimeoutMillis(ControlProtocolCodec.MAX_SAFE_INTEGER + 1))
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
                                    "x".repeat(ControlProtocolCodec.MAX_NAME_UTF8_BYTES + 1),
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
    for (int index = 0; index <= ControlProtocolCodec.MAX_CONTENT_ITEMS; index++) {
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
            .setCapabilityVersion(ControlProtocolFixtures.CAPABILITY_VERSION)
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
            .setCapabilityVersion(ControlProtocolFixtures.CAPABILITY_VERSION)
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
                ControlProtocolFixtures.invokeEnvelope().toBuilder()
                    .setInvoke(
                        ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                            .setCapabilityVersion("x".repeat(129)))
                    .build()));

    assertThrows(
        ControlProtocolException.class,
        () ->
            ControlProtocolCodec.encodeBinary(
                ControlProtocolFixtures.invokeEnvelope().toBuilder()
                    .setInvoke(
                        ControlProtocolFixtures.invokeEnvelope().getInvoke().toBuilder()
                            .setCapabilityVersion("\uD800"))
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
        "{\"a\":\"" + "x".repeat(ControlProtocolCodec.MAX_DETAILS_JSON_UTF8_BYTES) + "\"}";
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
