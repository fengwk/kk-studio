package fun.fengwk.kkstudio.platform.harness.http;

import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.THREAD_ID;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.assertCommandRejected;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.cursorBatch;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.executionSelection;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.id;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.idText;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.mapContent;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.newThreadTarget;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.setAgent;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.setEnvironment;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.setExecution;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.threadBatch;
import static fun.fengwk.kkstudio.platform.harness.http.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessImageInputTier;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO;

import java.util.Arrays;
import java.util.List;

class HarnessRuntimeRequestMapperCommandTest {

  @Test
  void mapsFixedSetPrefixAndRawRequestHash() {
    // 只允许稳定 SET_AGENT -> SET_EXECUTION -> SET_ENVIRONMENT 前缀，USER_MESSAGE raw hash 必须保持精确。
    HarnessThreadCommandBatchDTO request = cursorBatch(userCommand("user"));
    HarnessCommandCreateDTO agent = setAgent("agent", "default-assistant");
    HarnessCommandCreateDTO execution = setExecution("execution", executionSelection());
    HarnessCommandCreateDTO environment = setEnvironment("environment", "local");
    request.setCommands(List.of(agent, execution, environment, userCommand("user")));

    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, request);

    assertEquals(
        List.of(
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_EXECUTION,
            ThreadCommandType.SET_ENVIRONMENT,
            ThreadCommandType.USER_MESSAGE),
        mapped.commands().stream().map(command -> command.payload().type()).toList());
    assertEquals(new SetEnvironmentCommandPayload("local"), mapped.commands().get(2).payload());
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(mapped.commands().getLast().payload()),
        mapped.commands().getLast().requestHash());
  }

  /** SET_ENVIRONMENT 的 null 表示解除选择；SET_* 顺序固定。 */
  @Test
  void environmentCommandMapsNullableSelectionAndRequiresFixedOrder() {
    HarnessCommandCreateDTO cleared = setEnvironment("clear", null);
    HarnessThreadCommandBatchDTO request = threadBatch(cleared, userCommand("tail"));
    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, request);
    assertEquals(new SetEnvironmentCommandPayload(null), mapped.commands().getFirst().payload());

    HarnessCommandCreateDTO execution = setExecution("execution-order", executionSelection());
    HarnessCommandCreateDTO environment = setEnvironment("environment-order", "local");
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(environment, execution, userCommand("tail"))));
  }

  @Test
  void mapsControlCommandsAndBothApprovalDecisions() {
    // compact/stop/yolo/approval 的 CAS 与幂等 id 必须经过同一严格解析入口。
    HarnessThreadCompactDTO compact = new HarnessThreadCompactDTO();
    compact.setExpectedVersion(7L);
    assertEquals(
        7L,
        HarnessRuntimeRequestMapper.toCompactThreadCommand(THREAD_ID, compact).expectedVersion());

    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setExpectedVersion(8L);
    stop.setStopRequestId(idText(20));
    assertEquals(8L, HarnessRuntimeRequestMapper.toStopCommand(THREAD_ID, stop).expectedVersion());

    HarnessThreadYoloUpdateDTO yolo = new HarnessThreadYoloUpdateDTO();
    yolo.setYoloEnabled(true);
    assertTrue(HarnessRuntimeRequestMapper.toSetThreadYoloCommand(THREAD_ID, yolo).enabled());

    HarnessToolApprovalDTO approval = new HarnessToolApprovalDTO();
    approval.setDecisionId(idText(30));
    approval.setDecision(HarnessToolApprovalDTO.Decision.ALLOW);
    // 操作者不在 DTO 中：只能由调用方传入的服务端解析结果决定。
    ToolApprovalCommand allowed =
        HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval, "user");
    assertEquals(ToolApprovalDecision.ALLOWED, allowed.decision());
    assertEquals("user", allowed.actor());
    approval.setDecision(HarnessToolApprovalDTO.Decision.DENY);
    ToolApprovalCommand denied =
        HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval, "user");
    assertEquals(ToolApprovalDecision.DENIED, denied.decision());
    assertEquals("user", denied.actor());
    approval.setDecision(null);
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toToolApprovalCommand(
                THREAD_ID, idText(31), approval, "user"));
  }

  @Test
  void mapsRenameCommandsThroughTheSameStrictUuidAndPresenceBoundary() {
    // Session/Thread rename 共用同一 {name} bean；id 走 canonical UUID 解析，name 经 Core 权威规范化（构造器即折叠空白）。
    HarnessNameUpdateDTO body = new HarnessNameUpdateDTO();
    body.setName("  my session  ");

    RenameSessionCommand sessionCommand =
        HarnessRuntimeRequestMapper.toRenameSessionCommand(id(2), body);
    assertEquals(id(2), sessionCommand.sessionId());
    assertEquals("my session", sessionCommand.name());

    RenameThreadCommand threadCommand =
        HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, body);
    assertEquals(id(1), threadCommand.threadId());
    assertEquals("my session", threadCommand.name());

    assertThrows(
        DomainException.class, () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(null, body));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toRenameSessionCommand(id(2), null));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, null));
  }

  @Test
  void rejectsBlankNameWithoutTouchingCoreNormalization() {
    // mapper 不能替 Core 做长度裁剪：blank 直接 400，非空白超长在命令构造器（Core 权威）中被拒绝。
    HarnessNameUpdateDTO blank = new HarnessNameUpdateDTO();
    blank.setName("   ");
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toRenameSessionCommand(id(2), blank));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, blank));

    HarnessNameUpdateDTO overlong = new HarnessNameUpdateDTO();
    overlong.setName("n".repeat(257));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, overlong),
        "超长 name 必须由 Core 命令构造器拒绝，而不是在 web 层重复实现");
  }

  @Test
  void mapsAllUserContentKindsInOrder() {
    // TEXT/ATTACHMENT/RESOURCE 必须按原顺序映射为精确 domain content 类型。
    HarnessUserMessageContentDTO text = new HarnessUserMessageContentDTO.TextDTO("hello");
    HarnessUserMessageContentDTO attachment =
        new HarnessUserMessageContentDTO.AttachmentDTO(idText(70), null);
    HarnessUserMessageContentDTO resource =
        new HarnessUserMessageContentDTO.ResourceDTO(
            idText(71), "report.txt", null, HarnessImageInputTier.P720);
    HarnessCommandCreateDTO command = userCommand("contents", List.of(text, attachment, resource));

    UserMessageCommandPayload payload =
        assertInstanceOf(
            UserMessageCommandPayload.class,
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                    THREAD_ID, threadBatch(command))
                .commands()
                .getFirst()
                .payload());
    assertInstanceOf(TextMessageContent.class, payload.message().contents().get(0));
    assertInstanceOf(AttachmentMessageContent.class, payload.message().contents().get(1));
    ResourceMessageContent mapped =
        assertInstanceOf(ResourceMessageContent.class, payload.message().contents().get(2));
    assertEquals(id(71), mapped.blobId());
    assertEquals("report.txt", mapped.name());
    assertNull(mapped.preview());
  }

  /**
   * 图片输入档位的 wire 契约：ATTACHMENT / RESOURCE 缺省即平台默认 720P，显式取值必须无损映射。
   *
   * <p>测试意图：档位决定真正送达的像素尺寸，因此缺省绝不能被解释成「原图」。
   */
  @Test
  void mapsUserContentImageTierWithPlatformDefault() {
    HarnessUserMessageContentDTO attachment =
        new HarnessUserMessageContentDTO.AttachmentDTO(idText(70), null);
    assertEquals(
        ImageInputTier.P720,
        assertInstanceOf(AttachmentMessageContent.class, mapContent(attachment)).imageTier());

    attachment =
        new HarnessUserMessageContentDTO.AttachmentDTO(idText(70), HarnessImageInputTier.P1080);
    assertEquals(
        ImageInputTier.P1080,
        assertInstanceOf(AttachmentMessageContent.class, mapContent(attachment)).imageTier());

    HarnessUserMessageContentDTO resource =
        new HarnessUserMessageContentDTO.ResourceDTO(idText(71), "photo.png", null, null);
    ResourceMessageContent defaultTier =
        assertInstanceOf(ResourceMessageContent.class, mapContent(resource));
    assertEquals(ImageInputTier.P720, defaultTier.imageTier());
    assertEquals("photo.png", defaultTier.name());

    resource =
        new HarnessUserMessageContentDTO.ResourceDTO(
            idText(71), "photo.png", null, HarnessImageInputTier.ORIGINAL);
    assertEquals(
        ImageInputTier.ORIGINAL,
        assertInstanceOf(ResourceMessageContent.class, mapContent(resource)).imageTier());
  }

  @Test
  void rejectsInvalidUserContentShapes() {
    // 必填字段、非空列表与 null 元素都必须 fail closed。
    HarnessCommandCreateDTO command = userCommand("empty", List.of());
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)));

    HarnessUserMessageContentDTO missingName =
        new HarnessUserMessageContentDTO.ResourceDTO(idText(71), null, null, null);
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(userCommand("missing-name", List.of(missingName)))));

    HarnessCommandCreateDTO nullContent =
        userCommand("null-content", Arrays.asList((HarnessUserMessageContentDTO) null));
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(nullContent)));
  }

  @Test
  void rejectsCrossCommandFieldsAndMissingPayloads() {
    // 每个 command variant 只能携带自己的字段；必填 payload 缺失必须拒绝。
    HarnessCommandCreateDTO blankEnvironment =
        new HarnessCommandCreateDTO.SetEnvironmentDTO(idText(50), " ");
    assertCommandRejected(blankEnvironment);
    HarnessCommandCreateDTO slashEnvironment =
        new HarnessCommandCreateDTO.SetEnvironmentDTO(idText(51), "a/b");
    assertCommandRejected(slashEnvironment);

    HarnessCommandCreateDTO blankAgent = new HarnessCommandCreateDTO.SetAgentDTO(idText(52), " ");
    assertCommandRejected(blankAgent);

    HarnessCommandCreateDTO missingExecution =
        new HarnessCommandCreateDTO.SetExecutionDTO(idText(53), null);
    assertCommandRejected(missingExecution);
  }

  @Test
  void rejectsNullBatchMembersAndControlBodies() {
    // mapper 自身必须拒绝缺失 DTO、target、command list、null command 与缺失控制请求体。
    assertThrows(
        DomainException.class, () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(null));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, null));

    HarnessCommandBatchDTO missingTarget = request(newThreadTarget(), userCommand("user"));
    missingTarget.setTarget(null);
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(missingTarget));

    HarnessThreadCommandBatchDTO missingCommands = threadBatch(userCommand("user"));
    missingCommands.setCommands(null);
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, missingCommands));

    HarnessThreadCommandBatchDTO nullCommand = threadBatch(userCommand("user"));
    nullCommand.setCommands(Arrays.asList((HarnessCommandCreateDTO) null));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, nullCommand));

    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toCompactThreadCommand(THREAD_ID, null));
    assertThrows(
        DomainException.class, () -> HarnessRuntimeRequestMapper.toStopCommand(THREAD_ID, null));
    assertThrows(
        DomainException.class,
        () -> HarnessRuntimeRequestMapper.toSetThreadYoloCommand(THREAD_ID, null));
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), null, "user"));

    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setKind("CHAT");
    assertThrows(DomainException.class, () -> HarnessRuntimeRequestMapper.toOwner(owner));
    assertThrows(DomainException.class, () -> HarnessRuntimeRequestMapper.toOwner(null));
  }
}
