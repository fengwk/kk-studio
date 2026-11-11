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

import fun.fengwk.kkstudio.platform.catalog.dto.SkillArchiveDownloadDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageEditDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageFileDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackagePublishDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingPublishDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;

import java.util.List;
import java.util.UUID;

/**
 * Platform 技能包 API：当前版本列表与详情、归档文件清单与下载、统一编辑、Git 渠道检查与发布、上传渠道暂存/发布/取消。
 *
 * <p>所有写操作都携带客户端已展示的 CAS {@code expectedVersion}（新建除外），陈旧卡片统一得到 version conflict；令牌永不出现在任何响应中，只以
 * {@code configured} 事实表达。
 */
@AllArgsConstructor
@RequestMapping("/api/platform")
@RestController
public class SkillCatalogController {

  private final SkillCatalogService skillCatalogService;

  @GetMapping("/skill-packages")
  public List<SkillPackageSummaryDTO> listPackages() {
    return skillCatalogService.listPackages();
  }

  @PostMapping("/skill-packages")
  @ResponseStatus(HttpStatus.CREATED)
  public SkillPackageDetailDTO createPackage(@RequestBody SkillPackageCreateDTO createDTO) {
    return skillCatalogService.createGitPackage(createDTO);
  }

  @GetMapping("/skill-packages/{name}")
  public SkillPackageDetailDTO getPackage(@PathVariable("name") String name) {
    return skillCatalogService.getPackage(name);
  }

  @PutMapping("/skill-packages/{name}")
  public SkillPackageDetailDTO editPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageEditDTO editDTO) {
    return skillCatalogService.editPackage(name, editDTO);
  }

  @DeleteMapping("/skill-packages/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePackage(
      @PathVariable("name") String name, @RequestParam("expectedVersion") long expectedVersion) {
    skillCatalogService.deletePackage(name, expectedVersion);
  }

  @GetMapping("/skill-packages/{name}/files")
  public List<SkillPackageFileDTO> listFiles(@PathVariable("name") String name) {
    return skillCatalogService.listFiles(name);
  }

  @PostMapping("/skill-packages/{name}/archive-url")
  public SkillArchiveDownloadDTO archiveDownload(@PathVariable("name") String name) {
    return skillCatalogService.archiveDownload(name);
  }

  @PostMapping("/skill-packages/{name}/check")
  public SkillPackageDetailDTO checkPackage(
      @PathVariable("name") String name, @RequestBody SkillPackageCheckDTO checkDTO) {
    return skillCatalogService.checkGitPackage(name, checkDTO);
  }

  @PostMapping("/skill-packages/{name}/publish")
  public SkillPackageDetailDTO publishPackage(
      @PathVariable("name") String name, @RequestBody SkillPackagePublishDTO publishDTO) {
    return skillCatalogService.publishGitPackage(name, publishDTO);
  }

  @PostMapping("/skill-package-stagings")
  @ResponseStatus(HttpStatus.CREATED)
  public SkillPackageStagingDTO createStaging(@RequestBody SkillPackageStagingCreateDTO createDTO) {
    return skillCatalogService.previewUpload(createDTO);
  }

  @PostMapping("/skill-package-stagings/{stagingId}/publish")
  public SkillPackageDetailDTO publishStaging(
      @PathVariable("stagingId") UUID stagingId,
      @RequestBody SkillPackageStagingPublishDTO publishDTO) {
    return skillCatalogService.publishUpload(stagingId, publishDTO);
  }

  @DeleteMapping("/skill-package-stagings/{stagingId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteStaging(@PathVariable("stagingId") UUID stagingId) {
    skillCatalogService.cancelUpload(stagingId);
  }
}
