package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionMessageCreateDTO;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** T15 harness session + entry command/query controller. */
@AllArgsConstructor
@RestController
@RequestMapping("/api/sessions")
public class StudioHarnessSessionController {

    private final HarnessSessionCommandService commandService;
    private final HarnessSessionQueryService queryService;

    @PostMapping
    public Result<HarnessSessionDTO> createSession(@RequestBody HarnessSessionCreateDTO createDTO) {
        return Results.created(commandService.createRootSession(createDTO));
    }

    @GetMapping("/{id}")
    public Result<HarnessSessionDTO> getSession(@PathVariable("id") String id) {
        return Results.ok(queryService.getSession(id));
    }

    @PostMapping("/{id}/messages")
    public Result<HarnessSessionEntryDTO> createMessage(
        @PathVariable("id") String id,
        @RequestBody HarnessSessionMessageCreateDTO createDTO) {
        return Results.created(commandService.submitUserMessage(id, createDTO));
    }

    @GetMapping("/{id}/entries")
    public Result<List<HarnessSessionEntryDTO>> listEntries(@PathVariable("id") String id) {
        return Results.ok(queryService.listEntries(id));
    }
}
