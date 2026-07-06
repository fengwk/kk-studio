package fun.fengwk.kkstudio.web.controller;

import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author fengwk
 */
@AllArgsConstructor
@RequestMapping("/api/agent/sessions")
@RestController
public class StudioAgentSessionEventStreamController {

  private final StudioSessionEventStreamEmitter sessionEventStreamEmitter;

  @GetMapping(value = "/{sessionId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents(
      @PathVariable("sessionId") String sessionId,
      @RequestParam(value = "headEventId", required = false) String headEventId,
      @RequestParam(value = "idleTimeoutMillis", required = false) Long idleTimeoutMillis) {
    return sessionEventStreamEmitter.openStream(sessionId, headEventId, idleTimeoutMillis);
  }
}
