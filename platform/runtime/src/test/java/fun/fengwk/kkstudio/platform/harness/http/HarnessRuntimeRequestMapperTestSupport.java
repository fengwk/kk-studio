package fun.fengwk.kkstudio.platform.harness.http;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HarnessRuntimeRequestMapperTestSupport {

  static final String THREAD_ID = idText(1);

  private HarnessRuntimeRequestMapperTestSupport() {}

  static AgentMessageContent mapContent(HarnessUserMessageContentDTO content) {
    HarnessCommandCreateDTO command = userCommand("user-content", List.of(content));
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
    HarnessCommandCreateDTO command = userCommand("rejected-user-content", List.of(content));
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command)),
        why);
  }

  static void assertCommandRejected(HarnessCommandCreateDTO command) {
    assertThrows(
        DomainException.class,
        () ->
            HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(
                THREAD_ID, threadBatch(command, userCommand("tail"))));
  }

  /** 发送批次：单条纯 USER_MESSAGE 不带 cursor，其余批次带精确 cursor。 */
  static HarnessThreadCommandBatchDTO threadBatch(HarnessCommandCreateDTO... commands) {
    if (commands.length == 1 && commands[0] instanceof HarnessCommandCreateDTO.UserMessageDTO) {
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

  static String idempotencyKey(String suffix) {
    return idText(Math.abs(suffix.hashCode()) + 100);
  }

  static HarnessCommandCreateDTO goal(String suffix, String text) {
    return new HarnessCommandCreateDTO.GoalDTO(idempotencyKey(suffix), text);
  }

  static HarnessCommandCreateDTO setAgent(String suffix, String agentName) {
    return new HarnessCommandCreateDTO.SetAgentDTO(idempotencyKey(suffix), agentName);
  }

  static HarnessCommandCreateDTO setExecution(
      String suffix, HarnessExecutionSelectionDTO execution) {
    return new HarnessCommandCreateDTO.SetExecutionDTO(idempotencyKey(suffix), execution);
  }

  static HarnessCommandCreateDTO setEnvironment(String suffix, String environmentName) {
    return new HarnessCommandCreateDTO.SetEnvironmentDTO(idempotencyKey(suffix), environmentName);
  }

  static HarnessCommandCreateDTO userCommand(String suffix) {
    return userCommand(suffix, List.of(userContent()));
  }

  static HarnessCommandCreateDTO userCommand(
      String suffix, List<HarnessUserMessageContentDTO> contents) {
    return new HarnessCommandCreateDTO.UserMessageDTO(idempotencyKey(suffix), contents);
  }

  static HarnessUserMessageContentDTO userContent() {
    return new HarnessUserMessageContentDTO.TextDTO("hello");
  }

  static HarnessCommandTargetDTO newSessionTarget() {
    return new HarnessCommandTargetDTO.NewSessionDTO(idText(2), idText(1), branchSettings(), true);
  }

  static HarnessCommandTargetDTO newThreadTarget() {
    return new HarnessCommandTargetDTO.NewThreadDTO(
        idText(2), idText(3), idText(1), "branch", false);
  }

  static HarnessCommandTargetDTO newForkedSessionTarget() {
    return new HarnessCommandTargetDTO.NewForkedSessionDTO(
        idText(1), idText(2), idText(3), idText(4), false);
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
