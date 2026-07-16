package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunQueryService;
import fun.fengwk.kkstudio.share.model.HarnessRunDTO;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** T15 harness run query controller. */
@AllArgsConstructor
@RestController
public class StudioHarnessRunController {

    private final HarnessRunQueryService runQueryService;

    @GetMapping("/api/runs/{id}")
    public Result<HarnessRunDTO> getRun(@PathVariable("id") String id) {
        return Results.ok(runQueryService.getRun(id));
    }

    @GetMapping("/api/sessions/{id}/runs")
    public Result<List<HarnessRunDTO>> listRuns(@PathVariable("id") String id) {
        return Results.ok(runQueryService.listRuns(id));
    }
}
