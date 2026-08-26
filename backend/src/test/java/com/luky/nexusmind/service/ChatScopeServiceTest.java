package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.ChatScopeType;
import com.luky.nexusmind.model.ChatSession;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatScopeServiceTest {
    private final AtomicReference<List<FileUpload>> accessible = new AtomicReference<>(List.of());
    private final AtomicReference<Set<String>> indexed = new AtomicReference<>(Set.of());
    private final AtomicReference<User> currentUser = new AtomicReference<>();
    private ChatScopeService service;

    @BeforeEach
    void setUp() {
        DocumentService documents = new DocumentService() {
            @Override
            public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
                return accessible.get();
            }
        };
        DocumentVectorRepository vectors = proxy(DocumentVectorRepository.class, (method, args) ->
                "findIndexedFileMd5s".equals(method) ? indexed.get() : null);
        UserRepository users = proxy(UserRepository.class, (method, args) ->
                "findByUsername".equals(method) ? Optional.ofNullable(currentUser.get()) : null);
        OrganizationTagRepository organizations = proxy(OrganizationTagRepository.class, (method, args) ->
                "findById".equals(method) ? Optional.empty() : List.of());
        service = new ChatScopeService(documents, vectors, organizations, users);
    }

    @Test
    void selectedDocumentsFailClosedWhenOneLeavesTheAccessibleReadySet() {
        FileUpload first = file(1L, "a", "第一份.md", "alice", "org-a");
        FileUpload second = file(2L, "b", "第二份.md", "alice", "org-a");
        accessible.set(List.of(first, second));
        indexed.set(Set.of("a", "b"));

        ChatScopeService.ScopeSelection selection = service.select(
                "alice", ChatScopeType.DOCUMENTS, null, List.of(1L, 2L));
        ChatSession session = session(selection);
        accessible.set(List.of(first));

        assertThrows(CustomException.class, () -> service.resolveFiles("alice", session));
    }

    @Test
    void privateScopeNeverIncludesAnotherUsersPrivateDocumentForAdmin() {
        FileUpload mine = file(1L, "a", "我的.md", "1", "PRIVATE_1");
        FileUpload theirs = file(2L, "b", "他人.md", "2", "PRIVATE_2");
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        currentUser.set(admin);
        accessible.set(List.of(mine, theirs));
        indexed.set(Set.of("a", "b"));

        ChatSession session = session(service.select("admin", ChatScopeType.PRIVATE, null, List.of()));

        assertEquals(List.of(1L), service.resolveFiles("admin", session).stream().map(FileUpload::getId).toList());
    }

    @Test
    void allScopeUsesCurrentLabelForLegacySessions() {
        ChatSession session = new ChatSession();
        session.setScopeType(ChatScopeType.ALL);
        session.setScopeLabel("全部可访问知识");

        assertEquals("全部知识", ChatScopeService.view(session).get("label"));
    }

    private ChatSession session(ChatScopeService.ScopeSelection selection) {
        ChatSession session = new ChatSession();
        session.setScopeType(selection.type());
        session.setScopeValue(selection.value());
        session.setScopeLabel(selection.label());
        session.setScopeDetails(selection.details());
        return session;
    }

    private FileUpload file(Long id, String md5, String name, String owner, String orgTag) {
        FileUpload file = new FileUpload();
        file.setId(id);
        file.setFileMd5(md5);
        file.setFileName(name);
        file.setUserId(owner);
        file.setOrgTag(orgTag);
        file.setStatus(1);
        return file;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, RepositoryCall call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Object value = call.invoke(method.getName(), args);
                    if (value != null) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                });
    }

    private interface RepositoryCall {
        Object invoke(String method, Object[] args);
    }
}
