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

import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;

import java.util.List;

/** Provider 全局 CRUD API；{@code name} 与 {@code providerType} 只在创建时设置。 */
@RestController
@RequestMapping("/api/platform/providers")
public class ProviderController {

  private final ProviderService providerService;

  public ProviderController(ProviderService providerService) {
    this.providerService = providerService;
  }

  @GetMapping
  public List<ProviderDTO> listProviders() {
    return providerService.listProviders();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProviderDTO createProvider(@RequestBody ProviderCreateDTO createDTO) {
    return providerService.createProvider(createDTO);
  }

  @PutMapping("/{name}")
  public ProviderDTO updateProvider(
      @PathVariable("name") String name, @RequestBody ProviderUpdateDTO updateDTO) {
    return providerService.updateProvider(name, updateDTO);
  }

  @GetMapping("/{name}/remote-models")
  public List<String> listRemoteModels(@PathVariable("name") String name) {
    return providerService.listRemoteModelIds(name);
  }

  @DeleteMapping("/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteProvider(
      @PathVariable("name") String name, @RequestParam("expectedVersion") long expectedVersion) {
    providerService.deleteProvider(name, expectedVersion);
  }
}
