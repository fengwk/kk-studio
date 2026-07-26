package fun.fengwk.kkstudio.core.chat.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
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
    Chat existing = guard.requireChat(id);
    mutationFactory.apply(existing, updateDTO);
    guard.ensureDefaultAgentExistsIfPresent(existing.getDefaultAgentId());
    if (!repository.updateById(existing)) {
      throw new IllegalStateException("update chat failed: " + id);
    }
    return converter.convert(repository.getById(existing.getId()));
  }

  @Override
  @Transactional
  public void deleteChat(String id) {
    Chat existing = guard.requireChat(id);
    if (!repository.deleteById(existing.getId())) {
      throw new IllegalStateException("delete chat failed: " + id);
    }
  }
}
