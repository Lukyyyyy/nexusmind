import { useWebSocket } from '@vueuse/core';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const input = ref<Api.Chat.Input>({ message: '' });
  const sessions = ref<Api.Chat.Session[]>([]);
  const draftSession = ref<Api.Chat.Session | null>(null);
  const activeSessionId = ref<number | null>(null);
  const messages = ref<Api.Chat.Message[]>([]);
  const loading = ref(false);
  const sessionLoading = ref(false);
  let sessionsRequestId = 0;

  const store = useAuthStore();

  const {
    status: wsStatus,
    data: wsData,
    send: wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(`/proxy-ws/chat/${store.token}`, {
    autoReconnect: true
  });

  const scrollToBottom = ref<null | (() => void)>(null);

  const activeSession = computed(() =>
    draftSession.value?.id === activeSessionId.value
      ? draftSession.value
      : sessions.value.find(item => item.id === activeSessionId.value) || null
  );

  function normalizeMessage(message: Api.Chat.Message): Api.Chat.Message {
    if (typeof message.agentTrace !== 'string') return message;
    try {
      return { ...message, agentTrace: JSON.parse(message.agentTrace) as Api.Chat.AgentStep[] };
    } catch {
      return { ...message, agentTrace: [] };
    }
  }

  async function loadSessions() {
    const requestId = ++sessionsRequestId;
    sessionLoading.value = true;
    const { error, data } = await request<Api.Chat.Session[]>({ url: 'chat/sessions' });
    if (!error && requestId === sessionsRequestId) {
      const loaded = data || [];
      const draft = draftSession.value;
      const draftPersisted = draft && loaded.some(item => item.id === draft.id);
      const draftVisible = draft && sessions.value.some(item => item.id === draft.id);
      sessions.value = draft && draftVisible && !draftPersisted
        ? [draft, ...loaded.filter(item => item.id !== draft.id)]
        : loaded;
      if (draftPersisted) {
        draftSession.value = null;
      }
    }
    if (requestId === sessionsRequestId) sessionLoading.value = false;
  }

  async function createSession(scope?: Api.Chat.ScopeSelection) {
    const { error, data } = await request<Api.Chat.Session>({
      url: 'chat/sessions',
      method: 'post',
      data: scope
    });
    if (error) return null;
    draftSession.value = data;
    activeSessionId.value = data.id;
    messages.value = [];
    return data;
  }

  async function applyScope(scope: Api.Chat.ScopeSelection) {
    if (!activeSessionId.value || messages.value.length > 0) {
      return createSession(scope);
    }
    const { error, data } = await request<Api.Chat.Session>({
      url: `chat/sessions/${activeSessionId.value}/scope`,
      method: 'patch',
      data: scope
    });
    if (error) return null;
    if (draftSession.value?.id === data.id) draftSession.value = data;
    else sessions.value = sessions.value.map(item => (item.id === data.id ? data : item));
    return data;
  }

  async function loadMessages(sessionId = activeSessionId.value) {
    if (!sessionId) {
      messages.value = [];
      return;
    }
    loading.value = true;
    const { error, data } = await request<Api.Chat.Message[]>({ url: `chat/sessions/${sessionId}/messages` });
    if (!error && activeSessionId.value === sessionId) {
      messages.value = (data || []).map(normalizeMessage);
    }
    loading.value = false;
  }

  async function selectSession(sessionId: number) {
    if (activeSessionId.value === sessionId) return;
    activeSessionId.value = sessionId;
    await loadMessages(sessionId);
  }

  async function renameSession(sessionId: number, title: string) {
    const { error, data } = await request<Api.Chat.Session>({
      url: `chat/sessions/${sessionId}`,
      method: 'patch',
      data: { title } satisfies Api.Chat.SessionUpdate
    });
    if (error) return false;
    if (draftSession.value?.id === sessionId) draftSession.value = data;
    sessions.value = sessions.value.map(item => (item.id === sessionId ? data : item));
    return true;
  }

  function applySessionTitle(sessionId: number, title: string) {
    if (draftSession.value?.id === sessionId) {
      draftSession.value = { ...draftSession.value, title };
      sessions.value = [draftSession.value, ...sessions.value.filter(item => item.id !== sessionId)];
      return;
    }
    sessions.value = sessions.value.map(item => (item.id === sessionId ? { ...item, title } : item));
  }

  async function deleteSession(sessionId: number) {
    const { error } = await request({
      url: `chat/sessions/${sessionId}`,
      method: 'delete'
    });
    if (error) return false;
    if (draftSession.value?.id === sessionId) draftSession.value = null;
    sessions.value = sessions.value.filter(item => item.id !== sessionId);
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = sessions.value[0]?.id || null;
      await loadMessages(activeSessionId.value);
    }
    return true;
  }

  async function ensureActiveSession() {
    if (activeSessionId.value) return activeSessionId.value;
    const created = await createSession();
    return created?.id || null;
  }

  async function refreshActiveSessionMessages() {
    const sessionId = activeSessionId.value;
    const timingByAssistantIndex = messages.value
      .filter(message => message.role === 'assistant')
      .map(message => ({
        thinkingStartedAt: message.thinkingStartedAt,
        thinkingDurationMs: message.thinkingDurationMs
      }));
    await loadMessages(sessionId);
    if (activeSessionId.value !== sessionId) return;
    let assistantIndex = 0;
    messages.value = messages.value.map(message => {
      if (message.role !== 'assistant') return message;
      const timing = timingByAssistantIndex[assistantIndex++];
      if (!timing) return message;
      return {
        ...message,
        thinkingStartedAt: timing.thinkingStartedAt,
        thinkingDurationMs: message.thinkingDurationMs ?? timing.thinkingDurationMs
      };
    });
    await loadSessions();
  }

  return {
    input,
    sessions,
    activeSessionId,
    activeSession,
    messages,
    loading,
    sessionLoading,
    wsStatus,
    wsData,
    wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    loadSessions,
    createSession,
    applyScope,
    selectSession,
    loadMessages,
    renameSession,
    applySessionTitle,
    deleteSession,
    ensureActiveSession,
    refreshActiveSessionMessages
  };
});
