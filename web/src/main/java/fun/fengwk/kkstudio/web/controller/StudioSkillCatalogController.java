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

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.error.DomainException;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.share.ai.skill.SkillArchiveDownloadDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillEnvironmentSyncDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageFileDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageGitCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageGitPublishDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePreviewDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageSummaryDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUploadPreviewDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUploadPublishDTO;

import java.util.List;

/**
 * Platform 技能包 API：当前版本列表与详情、归档文件清单与下载、统一编辑、Git 渠道检查与发布、上传渠道预览/发布/取消与环境同步状态。
 *
 * <p>所有写操作都携带客户端已展示的 CAS {@code expectedVersion}（新建除外），陈旧卡片统一得到 version conflict；令牌永不出现在任何响应中。
 */
@AllArgsConstructor
@RequestMapping("/api")
@RestController
public class StudioSkillCatalogController {

  private final SkillCatalogService skillCatalogService;
  private final EnvironmentSkillSyncOrchestrator skillSyncOrchestrator;
  private final EnvironmentRegistry environmentRegistry;

  @GetMapping("/skill-packages")
  public ApiResponse<List<SkillPackageSummaryDTO>> listPackages() {
    return ApiResponse.ok(skillCatalogService.listPackages());
  }

  @GetMapping("/skill-packages/{name}")
  public ApiResponse<SkillPackageDetailDTO> getPackage(@PathVariable("name") String name) {
    return ApiResponse.ok(skillCatalogService.getPackage(name));
  }

  @GetMapping("/skill-packages/{name}/environments")
  public ApiResponse<List<SkillEnvironmentSyncDTO>> listEnvironmentSync(
      @PathVariable("name") String name) {
    return ApiResponse.ok(skillCatalogService.listEnvironmentSync(name));
  }

  @GetMapping("/skill-packages/{name}/files")
  public ApiResponse<List<SkillPackageFileDTO>> listFiles(@PathVariable("name") String name) {
    return ApiResponse.ok(skillCatalogService.listFiles(name));
  }

  @PostMapping("/skill-packages/{name}/archive-url")
  public ApiResponse<SkillArchiveDownloadDTO> archiveDownload(@PathVariable("name") String name) {
    return ApiResponse.ok(skillCatalogService.archiveDownload(name));
  }

  @PutMapping("/skill-packages/{name}")
  public ApiResponse<SkillPackageDetailDTO> editPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageEditDTO editDTO) {
    return ApiResponse.ok(skillCatalogService.editPackage(name, editDTO));
  }

  @PostMapping("/skill-packages/git")
  public ApiResponse<SkillPackageDetailDTO> createGitPackage(
      @RequestBody SkillPackageGitCreateDTO createDTO) {
    return ApiResponse.created(skillCatalogService.createGitPackage(createDTO));
  }

  @PostMapping("/skill-packages/git/{name}/check")
  public ApiResponse<SkillPackageDetailDTO> checkGitPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageCheckDTO checkDTO) {
    return ApiResponse.ok(skillCatalogService.checkGitPackage(name, checkDTO));
  }

  @PostMapping("/skill-packages/git/{name}/publish")
  public ApiResponse<SkillPackageDetailDTO> publishGitPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageGitPublishDTO publishDTO) {
    return ApiResponse.ok(skillCatalogService.publishGitPackage(name, publishDTO));
  }

  @PostMapping("/skill-packages/uploads/preview")
  public ApiResponse<SkillPackagePreviewDTO> previewUpload(
      @RequestBody SkillPackageUploadPreviewDTO previewDTO) {
    return ApiResponse.created(skillCatalogService.previewUpload(previewDTO));
  }

  @PostMapping("/skill-packages/uploads/publish")
  public ApiResponse<SkillPackageDetailDTO> publishUpload(
      @RequestBody SkillPackageUploadPublishDTO publishDTO) {
    return ApiResponse.created(skillCatalogService.publishUpload(publishDTO));
  }

  @PostMapping("/skill-packages/uploads/{stagingUploadId}/cancel")
  public ApiResponse<Void> cancelUpload(@PathVariable("stagingUploadId") String stagingUploadId) {
    skillCatalogService.cancelUpload(stagingUploadId);
    return ApiResponse.noContent();
  }

  @DeleteMapping("/skill-packages/{name}")
  public ApiResponse<Void> deletePackage(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    skillCatalogService.deletePackage(name, expectedVersion);
    return ApiResponse.noContent();
  }

  /** 用户点击「重新同步」：对该环境触发一次全量技能对账。 */
  @PostMapping("/environments/{environmentId}/skills/resync")
  public ApiResponse<Void> resyncEnvironment(@PathVariable("environmentId") String environmentId) {
    EnvironmentId id = EnvironmentId.parse(environmentId);
    if (environmentRegistry.find(id).isEmpty()) {
      throw DomainException.notFound("environment");
    }
    skillSyncOrchestrator.onEnvironmentReady(id);
    return ApiResponse.noContent();
  }
}
