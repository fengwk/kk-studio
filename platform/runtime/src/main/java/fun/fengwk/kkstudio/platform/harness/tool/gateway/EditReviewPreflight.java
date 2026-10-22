package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.runtime.invocation.codec.ToolEditReviewJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** 事务外只读预览：成功才进入审批，任何预览失败都明确拒绝本次编辑而非允许盲批。 */
final class EditReviewPreflight {

  static final String FAILED_KIND = "EDIT_REVIEW_FAILED";
  private static final ToolEditReviewJsonCodec JSON = new ToolEditReviewJsonCodec();

  private EditReviewPreflight() {}

  static ToolGateway.PreflightResult prepare(
      EnvironmentCapabilityTransport transport, ToolInvocationRequest request, String reason) {
    EnvironmentCapabilityDescriptor descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_EDIT_PREVIEW);
    String previewId = UUID.randomUUID().toString();
    CompletableFuture<EnvironmentCapabilityResult> completion = new CompletableFuture<>();
    EnvironmentCapabilityExecutionHandle handle;
    try {
      handle =
          transport.invoke(
              request.binding().environmentId(),
              new EnvironmentCapabilityExecutionRequest(
                  descriptor,
                  new EnvironmentCapabilityCall(previewId, request.call().argumentsJson()),
                  descriptor.defaultTimeout()),
              new EnvironmentCapabilityExecutionListener() {
                @Override
                public void onComplete(EnvironmentCapabilityResult result) {
                  completion.complete(result);
                }

                @Override
                public void onError(Throwable error) {
                  completion.completeExceptionally(error);
                }
              });
      if (handle == null) {
        throw new IllegalStateException("Edit preview transport returned no execution handle");
      }
    } catch (RuntimeException failure) {
      return denied(failure);
    }

    try {
      EnvironmentCapabilityResult result =
          completion.get(descriptor.defaultTimeout().toMillis(), TimeUnit.MILLISECONDS);
      ToolEditReview review = decode(previewId, result);
      // Use the real model call ID and a maximal replacement count to reserve the
      // complete canonical result before approval, including its structured review.
      ObjectNode details = JsonNodeFactory.instance.objectNode();
      details.set("editReview", JSON.encodeNode(review));
      ToolResult projected =
          new ToolResult(
              request.call().id(),
              List.of(
                  new TextResultContent("Edited successfully.\nReplacements: " + Long.MAX_VALUE)),
              false,
              JsonValues.write(details));
      if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
          projected, ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES)) {
        throw new IllegalArgumentException(
            "Complete edit review exceeds the result limit; split this edit into smaller changes");
      }
      return new ToolGateway.Ask(reason, review);
    } catch (InterruptedException | TimeoutException failure) {
      try {
        handle.cancel();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return denied(failure);
    } catch (ExecutionException failure) {
      return denied(failure.getCause());
    } catch (RuntimeException failure) {
      return denied(failure);
    }
  }

  private static ToolEditReview decode(String previewId, EnvironmentCapabilityResult result) {
    if (result == null || !previewId.equals(result.callId())) {
      throw new IllegalArgumentException("Edit preview returned a mismatched call ID");
    }
    if (result.error()) {
      String message =
          result.contents().stream()
              .filter(TextResultContent.class::isInstance)
              .map(TextResultContent.class::cast)
              .map(TextResultContent::text)
              .collect(Collectors.joining("\n"));
      throw new IllegalArgumentException(
          message.isBlank() ? "Edit preview failed without an error description" : message);
    }
    if (result.contents().size() != 1
        || !(result.contents().getFirst() instanceof JsonResultContent json)) {
      throw new IllegalArgumentException("Edit preview must return one complete JSON review");
    }
    return JSON.decode(json.json());
  }

  private static ToolGateway.Deny denied(Throwable failure) {
    String description = failure.getMessage();
    if (description == null || description.isBlank()) {
      description = failure.getClass().getSimpleName();
    }
    return new ToolGateway.Deny(
        new ToolInvocationError(
            FAILED_KIND,
            ToolErrorGuidance.message(
                "Could not prepare edit review: " + description,
                ExecutionFact.NOT_EXECUTED,
                "Inspect the target file and Environment, then request a new edit review")));
  }
}
