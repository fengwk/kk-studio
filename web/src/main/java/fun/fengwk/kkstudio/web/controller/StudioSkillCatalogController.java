package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.share.ai.skill.SkillDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUpdateDTO;

import java.util.List;

/** Platform 全局 Skill 目录 API。 */
@AllArgsConstructor
@RequestMapping("/api/ai/catalog")
@RestController
public class StudioSkillCatalogController {

  private final SkillCatalogService skillCatalogService;

  @GetMapping("/skills")
  public Result<List<SkillDTO>> listSkills() {
    return Results.ok(skillCatalogService.listSkills());
  }

  @GetMapping("/skill-packages")
  public Result<List<SkillPackageDTO>> listPackages() {
    return Results.ok(skillCatalogService.listPackages());
  }

  @GetMapping("/skill-packages/{name}")
  public Result<SkillPackageDetailDTO> getPackage(@PathVariable("name") String name) {
    return Results.ok(skillCatalogService.getPackage(name));
  }

  @PostMapping("/skill-packages")
  public Result<SkillPackageDetailDTO> createPackage(@RequestBody SkillPackageCreateDTO createDTO) {
    return Results.created(skillCatalogService.createPackage(createDTO));
  }

  @PutMapping("/skill-packages/{name}")
  public Result<SkillPackageDetailDTO> updatePackage(
      @PathVariable("name") String name, @RequestBody SkillPackageUpdateDTO updateDTO) {
    return Results.ok(skillCatalogService.updatePackage(name, updateDTO));
  }

  @DeleteMapping("/skill-packages/{name}")
  public Result<Void> deletePackage(
      @PathVariable("name") String name,
      @RequestParam("expectedPackageVersion") String expectedPackageVersion) {
    skillCatalogService.deletePackage(name, expectedPackageVersion);
    return Results.noContent();
  }
}
