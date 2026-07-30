package fun.fengwk.kkstudio.core.harness.interaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerAction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;

/** Strict user-facing projection and deterministic target selection for Tool permission prompts. */
class ToolPermissionInteractionHandlerTest {

  private final ToolPermissionInteractionHandler handler =
      new ToolPermissionInteractionHandler(new ObjectMapper());

  @Test
  void approvalProjectsSafePreviewAndResumesOnlyItsToolTarget() {
    InteractionRequest request = request();

    assertEquals(
        "{\"tool\":\"bash\",\"workdir\":\"/workspace\",\"arguments\":\"{\\\"command\\\":\\\"rm -rf tmp\\\"}\"}",
        handler.project(request).json());

    var resolution = handler.resolve(request, new InteractionResponse("{\"approved\":true}"));

    assertEquals(
        InteractionOwnerAction.APPROVE_TOOL_PERMISSION, resolution.ownerDirective().action());
    assertEquals(ExecutionTargetKind.TOOL_INVOCATION, resolution.nextTarget().kind());
    assertEquals(41L, resolution.nextTarget().id());
  }

  @Test
  void denialResumesOnlyTheOwningThreadForTerminalApplication() {
    var resolution = handler.resolve(request(), new InteractionResponse("{\"approved\":false}"));

    assertEquals(InteractionOwnerAction.DENY_TOOL_PERMISSION, resolution.ownerDirective().action());
    assertEquals(ExecutionTargetKind.THREAD, resolution.nextTarget().kind());
    assertEquals(7L, resolution.nextTarget().id());
  }

  @Test
  void rejectsMalformedOrAmbiguousPersistedRequestAndResponse() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            handler.project(
                new InteractionRequest(
                    "{\"invocationId\":41,\"threadId\":7,\"tool\":\"bash\",\"workdir\":\"/workspace\"}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            handler.resolve(request(), new InteractionResponse("{\"approved\":true,\"extra\":1}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> handler.resolve(request(), new InteractionResponse("{\"approved\":\"true\"}")));
  }

  private static InteractionRequest request() {
    return new InteractionRequest(
        """
        {
          "invocationId": 41,
          "threadId": 7,
          "tool": "bash",
          "workdir": "/workspace",
          "arguments": "{\\\"command\\\":\\\"rm -rf tmp\\\"}"
        }
        """);
  }
}
