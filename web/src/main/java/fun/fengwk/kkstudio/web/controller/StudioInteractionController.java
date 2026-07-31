package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.service.InteractionService;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionResponseDTO;

import java.util.Objects;
import java.util.function.Supplier;

/** Tool permission Interaction query and response API. */
@RestController
@RequestMapping("/api/ai/runtime/interactions")
public class StudioInteractionController {
  private final InteractionService interactionService;

  public StudioInteractionController(InteractionService interactionService) {
    this.interactionService = Objects.requireNonNull(interactionService, "interactionService");
  }

  @GetMapping("/{interactionId}")
  public Result<InteractionDTO> get(@PathVariable String interactionId) {
    return Results.ok(translate(() -> interactionService.get(interactionId)));
  }

  @GetMapping("/open")
  public Result<InteractionDTO> getOpenByToolInvocation(@RequestParam String toolInvocationId) {
    return Results.ok(
        translate(() -> interactionService.getOpenByToolInvocation(toolInvocationId)));
  }

  @PostMapping("/{interactionId}/response")
  public Result<InteractionDTO> respond(
      @PathVariable String interactionId, @RequestBody InteractionResponseDTO response) {
    return Results.ok(translate(() -> interactionService.respond(interactionId, response)));
  }

  private static <T> T translate(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      String message = error.getMessage();
      if (message != null
          && (message.startsWith("unknown interaction:")
              || message.startsWith("no open interaction for tool invocation"))) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, message, error);
      }
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message, error);
    }
  }
}
