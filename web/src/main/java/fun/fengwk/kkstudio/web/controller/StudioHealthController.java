package fun.fengwk.kkstudio.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供不依赖外部资源的进程存活探针。 */
@RestController
public class StudioHealthController {

  @GetMapping("/healthz")
  public ResponseEntity<Void> health() {
    return ResponseEntity.ok().build();
  }
}
