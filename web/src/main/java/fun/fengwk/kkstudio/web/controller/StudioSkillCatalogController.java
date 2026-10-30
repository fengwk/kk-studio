package fun.fengwk.kkstudio.web.controller;

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

import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.util.List;

/**
 * Platform 全局 Git Skill Package API。
 *
 * <p>同一 Package 资源上暴露七个入口：列表、读取、创建、编辑（description / branch）、删除、检查 branch HEAD 与发布 exact
 * commit。所有写操作都携带客户端已展示的 CAS {@code expectedVersion}，陈旧 Card 统一得到 version conflict。
 */
@AllArgsConstructor
@RequestMapping("/api")
@RestController
public class StudioSkillCatalogController {

  private final SkillCatalogService skillCatalogService;

  @GetMapping("/skill-packages")
  public ApiResponse<List<SkillPackageDTO>> listPackages() {
    return ApiResponse.ok(skillCatalogService.listPackages());
  }

  @GetMapping("/skill-packages/{name}")
  public ApiResponse<SkillPackageDTO> getPackage(@PathVariable("name") String name) {
    return ApiResponse.ok(skillCatalogService.getPackage(name));
  }

  @PostMapping("/skill-packages")
  public ApiResponse<SkillPackageDTO> createPackage(@RequestBody SkillPackageCreateDTO createDTO) {
    return ApiResponse.created(skillCatalogService.createPackage(createDTO));
  }

  @PutMapping("/skill-packages/{name}")
  public ApiResponse<SkillPackageDTO> editPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageEditDTO editDTO) {
    return ApiResponse.ok(skillCatalogService.editPackage(name, editDTO));
  }

  @DeleteMapping("/skill-packages/{name}")
  public ApiResponse<Void> deletePackage(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    skillCatalogService.deletePackage(name, expectedVersion);
    return ApiResponse.noContent();
  }

  @PostMapping("/skill-packages/{name}/check")
  public ApiResponse<SkillPackageDTO> checkPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageCheckDTO checkDTO) {
    return ApiResponse.ok(skillCatalogService.checkPackage(name, checkDTO));
  }

  @PostMapping("/skill-packages/{name}/update")
  public ApiResponse<SkillPackageDTO> updatePackage(
      @PathVariable("name") String name, @RequestBody SkillPackagePublishDTO publishDTO) {
    return ApiResponse.ok(skillCatalogService.updatePackage(name, publishDTO));
  }
}
