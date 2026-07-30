package fun.fengwk.kkstudio.core.chat.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.chat.service.ChatService;
import fun.fengwk.kkstudio.core.chat.service.converter.ChatConverter;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;
import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;

import java.util.List;
import java.util.stream.Collectors;

/** Chat CRUD service. */
@Service
public class ChatServiceImpl implements ChatService {

  private static final String RESOURCE = "chat";

  private final ChatRepository repository;
  private final ChatConverter converter;
  private final ChatMutationFactory mutationFactory;
  private final ChatGuard guard;

  public ChatServiceImpl(
      ChatRepository repository,
      ChatConverter converter,
      ChatMutationFactory mutationFactory,
      ChatGuard guard) {
    this.repository = repository;
    this.converter = converter;
    this.mutationFactory = mutationFactory;
    this.guard = guard;
  }

  @Override
  public List<ChatDTO> listChats() {
    return repository.listNewestFirst().stream()
        .map(converter::convert)
        .collect(Collectors.toList());
  }

  @Override
  public ChatDTO getChat(String id) {
    return converter.convert(guard.requireChat(id));
  }

  @Override
  @Transactional
  public ChatDTO createChat(ChatCreateDTO createDTO) {
    Chat chat = mutationFactory.newChat(createDTO);
    guard.ensureDefaultAgentExistsIfPresent(chat.getDefaultAgentId());
    if (!repository.create(chat)) {
      throw new IllegalStateException("create chat failed");
    }
    return converter.convert(repository.getById(chat.getId()));
  }

  @Override
  @Transactional
  public ChatDTO updateChat(String id, ChatUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    Chat existing = guard.requireChat(id);
    ensureExpectedVersion(existing, id, rawExpected, expected);
    mutationFactory.apply(existing, updateDTO);
    guard.ensureDefaultAgentExistsIfPresent(existing.getDefaultAgentId());
    if (!repository.updateById(existing, expected)) {
      Chat reread = repository.getById(existing.getId());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
      }
      throw new AiVersionConflictException(
          RESOURCE, id, rawExpected, CatalogVersions.format(reread.getVersion()));
    }
    return converter.convert(repository.getById(existing.getId()));
  }

  @Override
  @Transactional
  public void deleteChat(String id, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    long parsed = ChatIds.parsePositive(id, "id");
    Chat existing = guard.requireChat(parsed);
    ensureExpectedVersion(existing, id, expectedVersion, expected);
    if (!repository.deleteById(existing.getId(), expected)) {
      Chat reread = repository.getById(parsed);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
      }
      throw new AiVersionConflictException(
          RESOURCE, id, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
  }

  private static void ensureExpectedVersion(
      Chat chat, String id, String expectedVersion, long expected) {
    if (chat.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, id, expectedVersion, CatalogVersions.format(chat.getVersion()));
    }
  }
}
