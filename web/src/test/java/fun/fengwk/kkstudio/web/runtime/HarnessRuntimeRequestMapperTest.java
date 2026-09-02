package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetWorkspacePathCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

class HarnessRuntimeRequestMapperTest {

  private static final String THREAD_ID = idText(1);

  @Test
  void mapsOwnerAndAllThreeTargets() {
    // 产品公开的 owner/target union 必须映射为精确的 sealed domain 类型。
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setType("CANVAS");
    owner.setId(idText(10));
    OwnerRef mappedOwner = HarnessRuntimeRequestMapper.toOwner(owner);
    assertEquals(OwnerType.CANVAS, mappedOwner.type());
    assertEquals(id(10), mappedOwner.id());

    AcceptCommandsCommand newSession =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(newSessionTarget(), userCommand("new-session")));
    AcceptCommandsCommand entry =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(entryTarget(), userCommand("entry")));
    AcceptCommandsCommand thread =
        HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
            request(threadTarget(), userCommand("thread")));

    assertInstanceOf(AcceptCommandsTarget.NewSession.class, newSession.target());
    assertInstanceOf(AcceptCommandsTarget.Entry.class, entry.target());
    assertInstanceOf(AcceptCommandsTarget.Thread.class, thread.target());
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          owner.setType("UNKNOWN");
          HarnessRuntimeRequestMapper.toOwner(owner);
        });
  }

  @Test
  void strictScalarParsersRejectNonCanonicalAndOverflowValues() {
    // UUID 与 decimal cursor 不能接受大小写、前导零、负数或 bigint 溢出。
    assertEquals(id(1), HarnessRuntimeRequestMapper.parseUuid(idText(1), "id"));
    assertThrows(
        IllegalArgumentException.class, () -> HarnessRuntimeRequestMapper.parseUuid(null, "id"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parseUuid("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "id"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parseUuid("not-a-uuid", "id"));

    assertEquals(1L, HarnessRuntimeRequestMapper.parsePositiveDecimal("1", "sequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parsePositiveDecimal("0", "sequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parsePositiveDecimal("01", "sequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parsePositiveDecimal("9223372036854775808", "sequence"));

    assertEquals(0L, HarnessRuntimeRequestMapper.parseNonNegativeDecimal("0", "version"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parseNonNegativeDecimal("-1", "version"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.parseNonNegativeDecimal("01", "version"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.parseNonNegativeDecimal("9223372036854775808", "version"));
  }

  @Test
  void mapsFixedSetPrefixEnvironmentAndRawRequestHash() {
    // 只允许稳定 SET_* 前缀，workspace path 与 USER_MESSAGE raw hash 必须保持精确。
    HarnessCommandBatchDTO request = request(threadTarget(), userCommand("user"));
    HarnessCommandCreateDTO workspace = command("SET_WORKSPACE_PATH", "workspace");
    workspace.setWorkspacePath("workspace");
    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    HarnessCommandCreateDTO model = command("SET_MODEL", "model");
    model.setModel(modelSelection());
    request.setCommands(List.of(workspace, agent, model, userCommand("user")));

    AcceptCommandsCommand mapped = HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request);

    assertEquals(
        List.of(
            ThreadCommandType.SET_WORKSPACE_PATH,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.USER_MESSAGE),
        mapped.commands().stream().map(command -> command.payload().type()).toList());
    SetWorkspacePathCommandPayload workspacePayload =
        assertInstanceOf(
            SetWorkspacePathCommandPayload.class, mapped.commands().getFirst().payload());
    assertEquals("workspace", workspacePayload.workspacePath());
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(mapped.commands().getLast().payload()),
        mapped.commands().getLast().requestHash());

    workspace.setWorkspacePath(null);
    mapped = HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request);
    workspacePayload =
        assertInstanceOf(
            SetWorkspacePathCommandPayload.class, mapped.commands().getFirst().payload());
    assertNull(workspacePayload.workspacePath());
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
    yolo.setExpectedVersion("9");
    yolo.setYoloEnabled(true);
    assertTrue(HarnessRuntimeRequestMapper.toSetThreadYoloCommand(THREAD_ID, yolo).enabled());

    HarnessToolApprovalDTO approval = new HarnessToolApprovalDTO();
    approval.setDecisionId(idText(30));
    approval.setActor("user");
    approval.setDecision("ALLOW");
    assertEquals(
        ToolApprovalDecision.ALLOWED,
        HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval)
            .decision());
    approval.setDecision("DENY");
    assertEquals(
        ToolApprovalDecision.DENIED,
        HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval)
            .decision());
    approval.setDecision("MAYBE");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), approval));
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
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), command))
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
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), command)));

    command.setContents(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), command)));

    HarnessCommandCreateDTO missingContents = command("USER_MESSAGE", "missing-contents");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(threadTarget(), missingContents)));

    HarnessUserMessageContentDTO unknown = new HarnessUserMessageContentDTO();
    unknown.setType("UNKNOWN");
    command.setContents(List.of(unknown));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), command)));

    HarnessUserMessageContentDTO missingName = new HarnessUserMessageContentDTO();
    missingName.setType("RESOURCE");
    missingName.setBlobId(idText(71));
    command.setContents(List.of(missingName));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), command)));

    command.setContents(Arrays.asList((HarnessUserMessageContentDTO) null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), command)));
  }

  @Test
  void rejectsUnsupportedCommandTypes() {
    // 产品 HTTP surface 既不开放 CUSTOM_MESSAGE，也不接受未知 discriminator。
    HarnessCommandCreateDTO custom = command("CUSTOM_MESSAGE", "custom");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), custom)));

    HarnessCommandCreateDTO unknown = command("UNKNOWN", "unknown");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), unknown)));
  }

  @Test
  void requiresOneTrailingUserMessageAndOrderedSetPrefix() {
    // batch 必须使用单调 SET_* 前缀，并且恰好以一条 USER_MESSAGE 收尾。
    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(threadTarget(), userCommand("user"), agent)));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request(threadTarget(), agent)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(threadTarget(), userCommand("one"), userCommand("two"))));

    HarnessCommandCreateDTO duplicateAgent = command("SET_AGENT", "duplicate-agent");
    duplicateAgent.setAgentName("other-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(threadTarget(), agent, duplicateAgent, userCommand("user"))));

    HarnessCommandCreateDTO model = command("SET_MODEL", "model");
    model.setModel(modelSelection());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(threadTarget(), model, agent, userCommand("user"))));

    HarnessCommandBatchDTO empty = request(threadTarget(), userCommand("unused"));
    empty.setCommands(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(empty));
  }

  @Test
  void rejectsCrossCommandFieldsAndMissingPayloads() {
    // 每个 command variant 只能携带自己的字段，显式 null 仅对 SET_WORKSPACE_PATH 表示清除。
    HarnessCommandCreateDTO user = userCommand("user-with-agent");
    user.setAgentName("forbidden");
    assertCommandRejected(user);

    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent-with-model");
    agent.setAgentName("default-assistant");
    agent.setModel(modelSelection());
    assertCommandRejected(agent);

    HarnessCommandCreateDTO model = command("SET_MODEL", "model-with-agent");
    model.setModel(modelSelection());
    model.setAgentName("forbidden");
    assertCommandRejected(model);

    HarnessCommandCreateDTO workspace = command("SET_WORKSPACE_PATH", "workspace-with-contents");
    workspace.setWorkspacePath(null);
    workspace.setContents(List.of());
    assertCommandRejected(workspace);

    assertCommandRejected(command("SET_WORKSPACE_PATH", "missing-workspace"));
    assertCommandRejected(command("USER_MESSAGE", "missing-contents"));

    HarnessCommandCreateDTO blankAgent = command("SET_AGENT", "blank-agent");
    blankAgent.setAgentName(" ");
    assertCommandRejected(blankAgent);

    HarnessCommandCreateDTO missingModel = command("SET_MODEL", "missing-model");
    missingModel.setModel(null);
    assertCommandRejected(missingModel);
  }

  @Test
  void rejectsTargetUnionViolationsAndUnknownType() {
    // NEW_SESSION/ENTRY/THREAD 的字段集合互斥，未知 target 不做兼容降级。
    HarnessCommandTargetDTO newSession = newSessionTarget();
    newSession.setStartEntryId(idText(8));
    assertTargetRejected(newSession);

    HarnessCommandTargetDTO entry = entryTarget();
    entry.setRootSettings(branchSettings());
    assertTargetRejected(entry);

    HarnessCommandTargetDTO thread = threadTarget();
    thread.setSessionId(idText(2));
    assertTargetRejected(thread);

    HarnessCommandTargetDTO unknown = new HarnessCommandTargetDTO();
    unknown.setType("UNKNOWN");
    assertTargetRejected(unknown);

    HarnessCommandTargetDTO missingYolo = new HarnessCommandTargetDTO();
    missingYolo.setType("NEW_SESSION");
    missingYolo.setSessionId(idText(2));
    missingYolo.setThreadId(idText(1));
    missingYolo.setRootSettings(branchSettings());
    assertTargetRejected(missingYolo);
  }

  @Test
  void rejectsNullBatchMembersAndControlBodies() {
    // mapper 自身必须拒绝缺失 DTO、target、command list、null command 与缺失控制请求体。
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(null));

    HarnessCommandBatchDTO missingTarget = request(threadTarget(), userCommand("user"));
    missingTarget.setTarget(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(missingTarget));

    HarnessCommandBatchDTO missingCommands = request(threadTarget(), userCommand("user"));
    missingCommands.setCommands(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(missingCommands));

    HarnessCommandBatchDTO nullCommand = request(threadTarget(), userCommand("user"));
    nullCommand.setCommands(Arrays.asList((HarnessCommandCreateDTO) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeRequestMapper.toAcceptCommandsCommand(nullCommand));

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
        () -> HarnessRuntimeRequestMapper.toToolApprovalCommand(THREAD_ID, idText(31), null));

    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setType("CHAT");
    assertThrows(IllegalArgumentException.class, () -> HarnessRuntimeRequestMapper.toOwner(owner));
    assertThrows(IllegalArgumentException.class, () -> HarnessRuntimeRequestMapper.toOwner(null));
  }

  private static void assertCommandRejected(HarnessCommandCreateDTO command) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(threadTarget(), command, userCommand("tail"))));
  }

  private static void assertTargetRejected(HarnessCommandTargetDTO target) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(target, userCommand("user"))));
  }

  private static HarnessCommandBatchDTO request(
      HarnessCommandTargetDTO target, HarnessCommandCreateDTO... commands) {
    HarnessCommandBatchDTO request = new HarnessCommandBatchDTO();
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setType("CHAT");
    owner.setId(idText(10));
    request.setOwner(owner);
    request.setTarget(target);
    request.setCommands(List.of(commands));
    return request;
  }

  private static HarnessCommandCreateDTO command(String type, String suffix) {
    HarnessCommandCreateDTO command = new HarnessCommandCreateDTO();
    command.setType(type);
    command.setIdempotencyKey(idText(Math.abs(suffix.hashCode()) + 100));
    return command;
  }

  private static HarnessCommandCreateDTO userCommand(String suffix) {
    HarnessUserMessageContentDTO content = new HarnessUserMessageContentDTO();
    content.setType("TEXT");
    content.setText("hello");
    HarnessCommandCreateDTO command = command("USER_MESSAGE", suffix);
    command.setContents(List.of(content));
    return command;
  }

  private static HarnessCommandTargetDTO newSessionTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("NEW_SESSION");
    target.setSessionId(idText(2));
    target.setThreadId(idText(1));
    target.setRootSettings(branchSettings());
    target.setYoloEnabled(true);
    return target;
  }

  private static HarnessCommandTargetDTO entryTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("ENTRY");
    target.setSessionId(idText(2));
    target.setStartEntryId(idText(3));
    target.setThreadId(idText(1));
    target.setYoloEnabled(false);
    return target;
  }

  private static HarnessCommandTargetDTO threadTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("THREAD");
    target.setThreadId(idText(1));
    target.setExpectedHeadEntryId(idText(3));
    target.setExpectedNextCommandSequence("4");
    return target;
  }

  private static HarnessBranchSettingsDTO branchSettings() {
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    settings.setWorkspacePath(null);
    settings.setAgentName("default-assistant");
    settings.setModel(modelSelection());
    return settings;
  }

  private static HarnessModelSelectionDTO modelSelection() {
    HarnessModelSelectionDTO selection = new HarnessModelSelectionDTO();
    selection.setProviderName("openai");
    selection.setModelName("gpt-5");
    selection.setVariant("default");
    return selection;
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }
}
