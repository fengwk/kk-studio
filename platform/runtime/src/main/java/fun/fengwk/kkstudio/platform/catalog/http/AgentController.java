package fun.fengwk.kkstudio.platform.catalog.http;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;

import java.util.List;

/** Agent 目录的 HTTP 契约。 */
@AllArgsConstructor
@RequestMapping("/api/platform/agents")
@RestController
public class AgentController {

  private final AgentService agentService;

  @GetMapping
  public List<AgentDTO> listAgents() {
    return agentService.listAgents();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AgentDTO createAgent(@RequestBody AgentCreateDTO createDTO) {
    return agentService.createAgent(createDTO);
  }

  @PutMapping("/{name}")
  public AgentDTO updateAgent(
      @PathVariable("name") String name, @RequestBody AgentUpdateDTO updateDTO) {
    return agentService.updateAgent(name, updateDTO);
  }

  @DeleteMapping("/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAgent(
      @PathVariable("name") String name, @RequestParam("expectedVersion") long expectedVersion) {
    agentService.deleteAgent(name, expectedVersion);
  }
}
