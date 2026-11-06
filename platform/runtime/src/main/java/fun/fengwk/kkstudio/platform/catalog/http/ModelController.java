package fun.fengwk.kkstudio.platform.catalog.http;

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

import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;

import java.util.List;

/** 统一 MODEL / MODEL GROUP CRUD API；逻辑名称可包含斜杠。 */
@RestController
@RequestMapping("/api/platform/models")
public class ModelController {

  private final ModelService modelService;

  public ModelController(ModelService modelService) {
    this.modelService = modelService;
  }

  @GetMapping
  public List<ModelDTO> listModels() {
    return modelService.listModels();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ModelDTO createModel(@RequestBody ModelCreateDTO createDTO) {
    return modelService.createModel(createDTO);
  }

  @PutMapping("/{*name}")
  public ModelDTO updateModel(
      @PathVariable("name") String name, @RequestBody ModelUpdateDTO updateDTO) {
    return modelService.updateModel(stripLeadingSlash(name), updateDTO);
  }

  @DeleteMapping("/{*name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteModel(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    modelService.deleteModel(stripLeadingSlash(name), expectedVersion);
  }

  private static String stripLeadingSlash(String name) {
    return name != null && name.startsWith("/") ? name.substring(1) : name;
  }
}
