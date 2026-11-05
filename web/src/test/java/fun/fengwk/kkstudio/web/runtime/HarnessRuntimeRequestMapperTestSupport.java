package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.share.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.runtime.HarnessUserMessageContentDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HarnessRuntimeRequestMapperTestSupport {

  static final String THREAD_ID = idText(1);

  private HarnessRuntimeRequestMapperTestSupport() {}

  static AgentMessageContent mapContent(HarnessUserMessageContentDTO content) {
    HarnessCommandCreateDTO command = command("USER_MESSAGE", "user-content");
    command.setContents(List.of(content));
    UserMessageCommandPayload payload =
        assertInstanceOf(
            UserMessageCommandPayload.class,
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                    THREAD_ID, threadBatch(command))
                .commands()
                .getFirst()
                .payload());
    return payload.message().contents().getFirst();
  }

  static void assertRejectedUserContent(HarnessUserMessageContentDTO content, String why) {
    HarnessCommandCreateDTO command = command("USER_MESSAGE", "rejected-user-content");
    command.setContents(List.of(content));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)),
        why);
  }

  static void assertCommandRejected(HarnessCommandCreateDTO command) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command, userCommand("tail"))));
  }

  static void assertTargetRejected(HarnessCommandTargetDTO target) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptCommandsCommand(
                request(target, userCommand("user"))));
  }

  /** 发送批次：单条纯 USER_MESSAGE 不带 cursor，其余批次带精确 cursor。 */
  static HarnessThreadCommandBatchDTO threadBatch(HarnessCommandCreateDTO... commands) {
    if (commands.length == 1 && "USER_MESSAGE".equals(commands[0].getType())) {
      HarnessThreadCommandBatchDTO batch = new HarnessThreadCommandBatchDTO();
      batch.setCommands(List.of(commands));
      return batch;
    }
    return cursorBatch(commands);
  }

  static HarnessThreadCommandBatchDTO cursorBatch(HarnessCommandCreateDTO... commands) {
    HarnessThreadCommandBatchDTO batch = new HarnessThreadCommandBatchDTO();
    batch.setExpectedHeadEntryId(idText(3));
    batch.setExpectedNextCommandSequence("4");
    batch.setCommands(List.of(commands));
    return batch;
  }

  static HarnessCommandBatchDTO request(
      HarnessCommandTargetDTO target, HarnessCommandCreateDTO... commands) {
    HarnessCommandBatchDTO request = new HarnessCommandBatchDTO();
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setKind("CHAT");
    owner.setId(idText(10));
    request.setOwner(owner);
    request.setTarget(target);
    request.setCommands(List.of(commands));
    return request;
  }

  static HarnessCommandCreateDTO command(String type, String suffix) {
    HarnessCommandCreateDTO command = new HarnessCommandCreateDTO();
    command.setType(type);
    command.setIdempotencyKey(idText(Math.abs(suffix.hashCode()) + 100));
    return command;
  }

  static HarnessCommandCreateDTO userCommand(String suffix) {
    HarnessCommandCreateDTO command = command("USER_MESSAGE", suffix);
    command.setContents(List.of(userContent()));
    return command;
  }

  static HarnessUserMessageContentDTO userContent() {
    HarnessUserMessageContentDTO content = new HarnessUserMessageContentDTO();
    content.setType("TEXT");
    content.setText("hello");
    return content;
  }

  static HarnessCommandTargetDTO newSessionTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("NEW_SESSION");
    target.setSessionId(idText(2));
    target.setThreadId(idText(1));
    target.setRootSettings(branchSettings());
    target.setYoloEnabled(true);
    return target;
  }

  static HarnessCommandTargetDTO newThreadTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("NEW_THREAD");
    target.setSessionId(idText(2));
    target.setStartEntryId(idText(3));
    target.setThreadId(idText(1));
    target.setThreadName("branch");
    target.setYoloEnabled(false);
    return target;
  }

  static HarnessCommandTargetDTO newForkedSessionTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("NEW_FORKED_SESSION");
    target.setSourceThreadId(idText(1));
    target.setStartEntryId(idText(2));
    target.setSessionId(idText(3));
    target.setThreadId(idText(4));
    target.setYoloEnabled(false);
    return target;
  }

  static HarnessBranchSettingsDTO branchSettings() {
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    settings.setAgentName("default-assistant");
    settings.setExecution(executionSelection());
    settings.setEnvironmentName(null);
    return settings;
  }

  static HarnessExecutionSelectionDTO executionSelection() {
    HarnessExecutionSelectionDTO selection = new HarnessExecutionSelectionDTO();
    selection.setDriver("builtin");
    selection.setSettings(Map.of("modelName", "gpt-5", "variant", "default"));
    return selection;
  }

  static HarnessModelSelectionDTO modelSelection() {
    HarnessModelSelectionDTO selection = new HarnessModelSelectionDTO();
    selection.setModelName("gpt-5");
    selection.setVariant("default");
    return selection;
  }

  static UUID id(long value) {
    return new UUID(0L, value);
  }

  static String idText(long value) {
    return id(value).toString();
  }
}
