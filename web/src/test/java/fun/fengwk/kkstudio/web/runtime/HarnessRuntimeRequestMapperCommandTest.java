package fun.fengwk.kkstudio.web.runtime;

import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.THREAD_ID;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.assertCommandRejected;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.assertRejectedUserContent;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.command;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.cursorBatch;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.executionSelection;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.id;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.idText;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.mapContent;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.newThreadTarget;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.request;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.threadBatch;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.userCommand;
import static fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapperTestSupport.userContent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessUserMessageContentDTO;

import java.util.Arrays;
import java.util.List;

class HarnessRuntimeRequestMapperCommandTest {

  @Test
  void mapsFixedSetPrefixAndRawRequestHash() {
    // 只允许稳定 SET_AGENT -> SET_EXECUTION -> SET_ENVIRONMENT 前缀，USER_MESSAGE raw hash 必须保持精确。
    HarnessThreadCommandBatchDTO request = cursorBatch(userCommand("user"));
    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    HarnessCommandCreateDTO execution = command("SET_EXECUTION", "execution");
    execution.setExecution(executionSelection());
    HarnessCommandCreateDTO environment = command("SET_ENVIRONMENT", "environment");
    environment.setEnvironmentName("local");
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

  /** 测试意图：SET_ENVIRONMENT 必须显式携带 environmentName（显式 null 合法表示解除选择），其他命令携带即拒绝。 */
  @Test
  void environmentCommandRequiresExplicitNullableFieldAndRejectsCrossCommandFields() {
    HarnessCommandCreateDTO cleared = command("SET_ENVIRONMENT", "clear");
    cleared.setEnvironmentName(null);
    HarnessThreadCommandBatchDTO request = threadBatch(cleared, userCommand("tail"));
    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, request);
    assertEquals(new SetEnvironmentCommandPayload(null), mapped.commands().getFirst().payload());

    // 字段缺失必须拒绝（无法区分「未选择」与「请求未携带」）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID,
                threadBatch(command("SET_ENVIRONMENT", "missing"), userCommand("tail"))));

    for (String otherType : List.of("USER_MESSAGE", "SET_AGENT", "SET_EXECUTION")) {
      HarnessCommandCreateDTO crossField;
      if ("USER_MESSAGE".equals(otherType)) {
        crossField = userCommand("with-environment");
      } else if ("SET_AGENT".equals(otherType)) {
        crossField = command("SET_AGENT", "with-environment");
        crossField.setAgentName("default-assistant");
      } else {
        crossField = command("SET_EXECUTION", "with-environment");
        crossField.setExecution(executionSelection());
      }
      crossField.setEnvironmentName("local");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                  THREAD_ID, threadBatch(crossField, userCommand("tail"))),
          otherType + " must reject environmentName");
    }

    // SET_ENVIRONMENT 与 SET_EXECUTION 的顺序必须固定：environment 不允许出现在 execution 之前。
    HarnessCommandCreateDTO execution = command("SET_EXECUTION", "execution-order");
    execution.setExecution(executionSelection());
    HarnessCommandCreateDTO environment = command("SET_ENVIRONMENT", "environment-order");
    environment.setEnvironmentName("local");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(environment, execution, userCommand("tail"))));
  }

  @Test
  void mapsControlCommandsAndBothApprovalDecisions() {
    // compact/stop/yolo/approval 的 CAS 与幂等 id 必须经过同一严格解析入口。
    HarnessThreadCompactDTO compact = new HarnessThreadCompactDTO();
    compact.setExpectedVersion("7");
    assertEquals(
        7L,
        HarnessRuntimeRequestMapper.toCompactThreadCommand(THREAD_ID, compact).expectedVersion());

    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setExpectedVersion("8");
    stop.setStopRequestId(idText(20));
    assertEquals(8L, HarnessRuntimeRequestMapper.toStopCommand(THREAD_ID, stop).expectedVersion());

    HarnessThreadYoloUpdateDTO yolo = new HarnessThreadYoloUpdateDTO();
    yolo.setYoloEnabled(true);
    assertTrue(HarnessRuntimeRequestMapper.toSetThreadYoloCommand(THREAD_ID, yolo).enabled());

    HarnessToolApprovalDTO approval = new HarnessToolApprovalDTO();
    approval.setDecisionId(idText(30));
    approval.setDecision("ALLOW");
    // 操作者不在 DTO 中：只能由调用方传入的服务端解析结果决定。
    ToolApprovalCommand allowed =
        HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval, "user");
    assertEquals(ToolApprovalDecision.ALLOWED, allowed.decision());
    assertEquals("user", allowed.actor());
    approval.setDecision("DENY");
    ToolApprovalCommand denied =
        HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval, "user");
    assertEquals(ToolApprovalDecision.DENIED, denied.decision());
    assertEquals("user", denied.actor());
    approval.setDecision("MAYBE");
    assertThrows(
        IllegalArgumentException.class,
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
        HarnessRuntimeRequestMapper.toRenameSessionCommand(idText(2), body);
    assertEquals(id(2), sessionCommand.sessionId());
    assertEquals("my session", sessionCommand.name());

    RenameThreadCommand threadCommand =
        HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, body);
    assertEquals(id(1), threadCommand.threadId());
    assertEquals("my session", threadCommand.name());

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameSessionCommand("not-a-uuid", body));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(null, body));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameSessionCommand(idText(2), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, null));
  }

  @Test
  void rejectsBlankNameWithoutTouchingCoreNormalization() {
    // mapper 不能替 Core 做长度裁剪：blank 直接 400，非空白超长在命令构造器（Core 权威）中被拒绝。
    HarnessNameUpdateDTO blank = new HarnessNameUpdateDTO();
    blank.setName("   ");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameSessionCommand(idText(2), blank));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, blank));

    HarnessNameUpdateDTO overlong = new HarnessNameUpdateDTO();
    overlong.setName("n".repeat(257));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toRenameThreadCommand(THREAD_ID, overlong),
        "超长 name 必须由 Core 命令构造器拒绝，而不是在 web 层重复实现");
  }

  @Test
  void mapsAllUserContentKindsInOrder() {
    // TEXT/ATTACHMENT/RESOURCE 必须按原顺序映射为精确 domain content 类型。
    HarnessUserMessageContentDTO text = new HarnessUserMessageContentDTO();
    text.setType("TEXT");
    text.setText("hello");
    HarnessUserMessageContentDTO attachment = new HarnessUserMessageContentDTO();
    attachment.setType("ATTACHMENT");
    attachment.setUploadId(idText(70));
    HarnessUserMessageContentDTO resource = new HarnessUserMessageContentDTO();
    resource.setType("RESOURCE");
    resource.setBlobId(idText(71));
    resource.setName("report.txt");
    resource.setPreview(null);
    HarnessCommandCreateDTO command = command("USER_MESSAGE", "contents");
    command.setContents(List.of(text, attachment, resource));

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
   * 图片输入档位的 wire 契约：ATTACHMENT / RESOURCE 缺省即平台默认 720P，显式取值必须无损映射；未知名称一律 fail closed。
   *
   * <p>测试意图：档位决定真正送达的像素尺寸，因此缺省绝不能被解释成「原图」，并且客户端拼错档位名时必须在接受命令前拒绝，而不是静默降级。
   */
  @Test
  void mapsUserContentImageTierWithPlatformDefaultAndStrictValidation() {
    HarnessUserMessageContentDTO attachment = new HarnessUserMessageContentDTO();
    attachment.setType("ATTACHMENT");
    attachment.setUploadId(idText(70));
    assertEquals(
        ImageInputTier.P720,
        assertInstanceOf(AttachmentMessageContent.class, mapContent(attachment)).imageTier());

    attachment.setImageTier("1080P");
    assertEquals(
        ImageInputTier.P1080,
        assertInstanceOf(AttachmentMessageContent.class, mapContent(attachment)).imageTier());

    HarnessUserMessageContentDTO resource = new HarnessUserMessageContentDTO();
    resource.setType("RESOURCE");
    resource.setBlobId(idText(71));
    resource.setName("photo.png");
    ResourceMessageContent defaultTier =
        assertInstanceOf(ResourceMessageContent.class, mapContent(resource));
    assertEquals(ImageInputTier.P720, defaultTier.imageTier());
    assertEquals("photo.png", defaultTier.name());

    resource.setImageTier("ORIGINAL");
    assertEquals(
        ImageInputTier.ORIGINAL,
        assertInstanceOf(ResourceMessageContent.class, mapContent(resource)).imageTier());

    // 未知名称、枚举名、空白与非文本取值都必须拒绝：档位不能被猜测。
    for (String imageTier : List.of("4K", "P720", "720p", "  ", "")) {
      resource.setImageTier(imageTier);
      assertRejectedUserContent(resource, imageTier);
    }
    resource.setImageTier(null);
    assertRejectedUserContent(resource, "explicit null");
    // 档位只对附件/资源有意义：TEXT 上出现该字段一律拒绝。
    HarnessUserMessageContentDTO text = new HarnessUserMessageContentDTO();
    text.setType("TEXT");
    text.setText("hello");
    text.setImageTier("720P");
    assertRejectedUserContent(text, "text");
  }

  @Test
  void rejectsInvalidUserContentShapes() {
    // discriminator、必填字段、非空列表与跨 kind 字段都必须 fail closed。
    HarnessUserMessageContentDTO resource = new HarnessUserMessageContentDTO();
    resource.setType("RESOURCE");
    resource.setBlobId(idText(71));
    resource.setName("report.txt");
    resource.setUploadId(idText(72));
    HarnessCommandCreateDTO command = command("USER_MESSAGE", "forbidden-resource-field");
    command.setContents(List.of(resource));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)));

    command.setContents(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)));

    HarnessCommandCreateDTO missingContents = command("USER_MESSAGE", "missing-contents");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(missingContents)));

    HarnessUserMessageContentDTO unknown = new HarnessUserMessageContentDTO();
    unknown.setType("UNKNOWN");
    command.setContents(List.of(unknown));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)));

    HarnessUserMessageContentDTO missingName = new HarnessUserMessageContentDTO();
    missingName.setType("RESOURCE");
    missingName.setBlobId(idText(71));
    command.setContents(List.of(missingName));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)));

    command.setContents(Arrays.asList((HarnessUserMessageContentDTO) null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)));
  }

  @Test
  void rejectsUnsupportedCommandTypes() {
    // 内部命令与未知 discriminator 均不可由产品 HTTP 面提交。
    for (String type : List.of("CUSTOM_MESSAGE", "NOTIFICATION")) {
      HarnessCommandCreateDTO rejected = command(type, type);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                  THREAD_ID, threadBatch(rejected)),
          type + " must be rejected");
    }

    HarnessCommandCreateDTO unknown = command("UNKNOWN", "unknown");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(unknown)),
        "unknown command discriminator must be rejected");
  }

  @Test
  void rejectsCrossCommandFieldsAndMissingPayloads() {
    // 每个 command variant 只能携带自己的字段（SET_ENVIRONMENT 只允许 environmentName）。
    HarnessCommandCreateDTO user = userCommand("user-with-agent");
    user.setAgentName("forbidden");
    assertCommandRejected(user);

    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent-with-execution");
    agent.setAgentName("default-assistant");
    agent.setExecution(executionSelection());
    assertCommandRejected(agent);

    HarnessCommandCreateDTO execution = command("SET_EXECUTION", "execution-with-agent");
    execution.setExecution(executionSelection());
    execution.setAgentName("forbidden");
    assertCommandRejected(execution);

    // SET_ENVIRONMENT 必须携带 environmentName；缺失与跨字段都拒绝，canonical 约束由 domain payload 承担。
    assertCommandRejected(command("SET_ENVIRONMENT", "missing-environment"));
    HarnessCommandCreateDTO blankEnvironment = command("SET_ENVIRONMENT", "blank-environment");
    blankEnvironment.setEnvironmentName(" ");
    assertCommandRejected(blankEnvironment);
    HarnessCommandCreateDTO slashEnvironment = command("SET_ENVIRONMENT", "slash-environment");
    slashEnvironment.setEnvironmentName("a/b");
    assertCommandRejected(slashEnvironment);
    assertCommandRejected(command("USER_MESSAGE", "missing-contents"));

    HarnessCommandCreateDTO blankAgent = command("SET_AGENT", "blank-agent");
    blankAgent.setAgentName(" ");
    assertCommandRejected(blankAgent);

    HarnessCommandCreateDTO missingExecution = command("SET_EXECUTION", "missing-execution");
    missingExecution.setExecution(null);
    assertCommandRejected(missingExecution);
  }

  @Test
  void rejectsNullBatchMembersAndControlBodies() {
    // mapper 自身必须拒绝缺失 DTO、target、command list、null command 与缺失控制请求体。
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, null));

    HarnessCommandBatchDTO missingTarget = request(newThreadTarget(), userCommand("user"));
    missingTarget.setTarget(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(missingTarget));

    HarnessThreadCommandBatchDTO missingCommands = threadBatch(userCommand("user"));
    missingCommands.setCommands(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, missingCommands));

    HarnessThreadCommandBatchDTO nullCommand = threadBatch(userCommand("user"));
    nullCommand.setCommands(Arrays.asList((HarnessCommandCreateDTO) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, nullCommand));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toCompactThreadCommand(THREAD_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toStopCommand(THREAD_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toSetThreadYoloCommand(THREAD_ID, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), null, "user"));

    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setKind("CHAT");
    assertThrows(IllegalArgumentException.class, () -> HarnessRuntimeRequestMapper.toOwner(owner));
    assertThrows(IllegalArgumentException.class, () -> HarnessRuntimeRequestMapper.toOwner(null));
  }

  /**
   * 测试意图：typed GOAL 必须显式携带 nullable text——显式 null 表示清除，canonical 文本表示设置；字段缺失、空白文本、与 USER_MESSAGE
   * 共存或非末位都必须 fail closed。
   */
  @Test
  void goalCommandRequiresExplicitNullableTextAndRejectsInvalidBatchShapes() {
    HarnessCommandCreateDTO set = command("GOAL", "goal-set");
    set.setText("ship the release");
    AcceptCommandsCommand mapped =
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, threadBatch(set));
    assertEquals(ThreadCommandType.GOAL, mapped.commands().getFirst().payload().type());
    assertEquals(
        new GoalCommandPayload("ship the release"), mapped.commands().getFirst().payload());
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(mapped.commands().getFirst().payload()),
        mapped.commands().getFirst().requestHash());

    HarnessCommandCreateDTO cleared = command("GOAL", "goal-clear");
    cleared.setText(null);
    assertEquals(
        new GoalCommandPayload(null),
        HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(THREAD_ID, threadBatch(cleared))
            .commands()
            .getFirst()
            .payload());

    // 字段缺失必须拒绝：无法区分「清除」与「请求未携带」。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command("GOAL", "goal-missing"))));

    // 空白文本不是清除，必须由 Core canonical 规则拒绝。
    HarnessCommandCreateDTO blank = command("GOAL", "goal-blank");
    blank.setText("   ");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(blank)));

    // GOAL 与 USER_MESSAGE 都是 user-like 终止输入：恰有一条且必须是最后一条。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(set, userCommand("tail"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(userCommand("head"), set)));

    // 跨命令字段：GOAL 不携带 contents，其他命令不携带 text。
    HarnessCommandCreateDTO goalWithContents = command("GOAL", "goal-contents");
    goalWithContents.setText("ship");
    goalWithContents.setContents(List.of(userContent()));
    assertCommandRejected(goalWithContents);
    HarnessCommandCreateDTO userWithText = userCommand("user-text");
    userWithText.setText("ship");
    assertCommandRejected(userWithText);
  }
}
