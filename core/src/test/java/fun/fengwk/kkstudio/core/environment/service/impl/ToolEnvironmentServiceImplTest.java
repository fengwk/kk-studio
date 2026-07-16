package fun.fengwk.kkstudio.core.environment.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.DefaultPage;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.environment.repo.ToolEnvironmentRepository;
import fun.fengwk.kkstudio.core.environment.service.converter.ToolEnvironmentConverter;
import fun.fengwk.kkstudio.core.environment.service.model.ToolEnvironment;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentUpdateDTO;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolEnvironmentServiceImplTest {

  private ToolEnvironmentRepository repository;
  private ToolEnvironmentGuard guard;
  private ToolEnvironmentServiceImpl service;
  private ToolEnvironmentConverter converter;
  private ToolEnvironmentMutationFactory mutationFactory;

  @BeforeEach
  void setUp() {
    repository = mock(ToolEnvironmentRepository.class);
    guard = mock(ToolEnvironmentGuard.class);
    converter = mock(ToolEnvironmentConverter.class);
    mutationFactory =
        new ToolEnvironmentMutationFactory(new AgentEditableSupport(new ObjectMapper()), () -> 1L);
    service = new ToolEnvironmentServiceImpl(repository, converter, mutationFactory, guard);
  }

  @Test
  void createPersistsCanonicalEmptyCapabilitiesAndReturnsDTO() {
    ToolEnvironmentCreateDTO createDTO = new ToolEnvironmentCreateDTO();
    createDTO.setName("env-a");
    createDTO.setDescription("desc");
    when(repository.create(any(ToolEnvironment.class)))
        .thenAnswer(
            inv -> {
              ToolEnvironment row = inv.getArgument(0);
              row.setId(1L);
              return true;
            });
    when(repository.getByName("env-a")).thenReturn(null);
    when(repository.getById(1L))
        .thenAnswer(
            inv -> {
              ToolEnvironment row = new ToolEnvironment();
              row.setId(1L);
              row.setName("env-a");
              row.setDescription("desc");
              row.setCapabilitiesJson("{\"tools\":[]}");
              return row;
            });
    ToolEnvironmentDTO dto = new ToolEnvironmentDTO();
    dto.setId("1");
    when(converter.convert(any(ToolEnvironment.class))).thenReturn(dto);

    ToolEnvironmentDTO created = service.createEnvironment(createDTO);

    assertSame(dto, created);
    ArgumentCaptor<ToolEnvironment> captor = ArgumentCaptor.forClass(ToolEnvironment.class);
    verify(repository).create(captor.capture());
    assertEquals("{\"tools\":[]}", captor.getValue().getCapabilitiesJson());
    verify(guard).ensureNameAvailable("env-a");
  }

  @Test
  void createRejectsBlankName() {
    ToolEnvironmentCreateDTO createDTO = new ToolEnvironmentCreateDTO();
    createDTO.setName("   ");
    assertThrows(IllegalArgumentException.class, () -> service.createEnvironment(createDTO));
    verify(repository, never()).create(any());
  }

  @Test
  void updateRejectsUnknownId() {
    when(guard.requireEnvironment("999")).thenThrow(new NoSuchElementException("missing"));
    ToolEnvironmentUpdateDTO updateDTO = new ToolEnvironmentUpdateDTO();
    updateDTO.setName("env-a");
    assertThrows(NoSuchElementException.class, () -> service.updateEnvironment("999", updateDTO));
    verify(repository, never()).updateById(any());
  }

  @Test
  void updateAppliesEditableFieldsOnlyAndKeepsCapabilities() {
    ToolEnvironment existing = new ToolEnvironment();
    existing.setId(7L);
    existing.setName("env-a");
    existing.setDescription("old");
    existing.setCapabilitiesJson("{\"tools\":[]}");
    when(guard.requireEnvironment("7")).thenReturn(existing);

    ToolEnvironmentUpdateDTO updateDTO = new ToolEnvironmentUpdateDTO();
    updateDTO.setName("env-a-renamed");
    updateDTO.setDescription("new");
    when(repository.updateById(any(ToolEnvironment.class))).thenAnswer(inv -> true);
    when(repository.getById(7L)).thenReturn(existing);
    when(converter.convert(any(ToolEnvironment.class))).thenReturn(new ToolEnvironmentDTO());

    service.updateEnvironment("7", updateDTO);

    assertEquals("env-a-renamed", existing.getName());
    assertEquals("new", existing.getDescription());
    assertEquals("{\"tools\":[]}", existing.getCapabilitiesJson());
    verify(guard).ensureNameAvailable("env-a", "env-a-renamed");
  }

  @Test
  void updatePreservesExistingNameWhenNameAbsentOrBlank() {
    ToolEnvironment existing = new ToolEnvironment();
    existing.setId(7L);
    existing.setName("env-a");
    existing.setDescription("old");
    existing.setCapabilitiesJson("{\"tools\":[]}");
    when(guard.requireEnvironment("7")).thenReturn(existing);
    when(repository.updateById(any(ToolEnvironment.class))).thenAnswer(inv -> true);
    when(repository.getById(7L)).thenReturn(existing);
    when(converter.convert(any(ToolEnvironment.class))).thenReturn(new ToolEnvironmentDTO());

    ToolEnvironmentUpdateDTO blankName = new ToolEnvironmentUpdateDTO();
    blankName.setName("   ");
    blankName.setDescription("renamed desc");
    service.updateEnvironment("7", blankName);
    assertEquals("env-a", existing.getName());
    assertEquals("renamed desc", existing.getDescription());
    // When name is unchanged, ensureNameAvailable is still invoked by the service to surface
    // uniqueness issues, but the mutation factory itself never overwrites the existing name.
    verify(guard).ensureNameAvailable("env-a", "env-a");
  }

  @Test
  void deleteRejectsReferencedEnvironment() {
    ToolEnvironment existing = new ToolEnvironment();
    existing.setId(7L);
    when(guard.requireEnvironment("7")).thenReturn(existing);
    doThrow(new IllegalStateException("environment is referenced by tool invocations: 7"))
        .when(guard)
        .ensureDeletable(7L);
    assertThrows(IllegalStateException.class, () -> service.deleteEnvironment("7"));
    verify(repository, never()).deleteById(anyLong());
  }

  @Test
  void deleteSucceedsForUnreferencedEnvironment() {
    ToolEnvironment existing = new ToolEnvironment();
    existing.setId(7L);
    when(guard.requireEnvironment("7")).thenReturn(existing);
    when(repository.deleteById(7L)).thenAnswer(inv -> true);
    service.deleteEnvironment("7");
    verify(guard).ensureDeletable(7L);
    verify(repository).deleteById(7L);
  }

  @Test
  void pageMapsResultsThroughConverter() {
    PageQuery pageQuery = new PageQuery(1, 50);
    ToolEnvironment row = new ToolEnvironment();
    row.setId(1L);
    row.setName("env-a");
    when(repository.page(pageQuery)).thenReturn(new DefaultPage<>(1, 50, List.of(row), 1L));
    when(converter.convert(any(ToolEnvironment.class))).thenReturn(new ToolEnvironmentDTO());

    Page<ToolEnvironmentDTO> page = service.pageEnvironments(pageQuery);
    assertNotNull(page);
    assertEquals(1, page.getResults().size());
  }
}
