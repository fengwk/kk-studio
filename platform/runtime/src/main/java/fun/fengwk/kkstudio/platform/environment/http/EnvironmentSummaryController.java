package fun.fengwk.kkstudio.platform.environment.http;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentSummaryDTO;
import fun.fengwk.kkstudio.platform.environment.dto.LiveEnvironmentCapabilityDTO;

import java.util.List;

/** Narrow environment view for workspace selection. */
@AllArgsConstructor
@RestController
public class EnvironmentSummaryController {

  private final EnvironmentService environmentService;

  @GetMapping("/api/platform/environment-summaries")
  public List<EnvironmentSummaryDTO> listEnvironmentSummaries() {
    return environmentService.list().stream()
        .map(
            environment ->
                new EnvironmentSummaryDTO(
                    environment.getId(),
                    environment.getName(),
                    environment.getStatus(),
                    environment.isReady(),
                    environment.getStatusExpiresAt(),
                    environment.getCapabilities().stream()
                        .map(LiveEnvironmentCapabilityDTO::getId)
                        .toList()))
        .toList();
  }
}
