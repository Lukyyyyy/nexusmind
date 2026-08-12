<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

const chatStore = useChatStore();
const { input, messages, activeSession, loading, wsStatus, wsData } = storeToRefs(chatStore);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();
const inputDockRef = ref<HTMLElement>();
const inputDockHeight = ref(112);
let inputDockResizeObserver: ResizeObserver | null = null;

const latestMessage = computed(() => {
  return messages.value[messages.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () => (!input.value.message.trim() && !isSending.value) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

const inputRef = ref<HTMLTextAreaElement>();
const isInputExpanded = ref(false);
const inputMinHeight = 24;
const inputMaxHeight = 200;

const scrollbarContentStyle = computed(() => ({
  padding: `28px 28px ${inputDockHeight.value + 30}px`
}));

function updateInputDockHeight() {
  nextTick(() => {
    const dock = inputDockRef.value;
    if (!dock) return;
    inputDockHeight.value = Math.ceil(dock.getBoundingClientRect().height);
  });
}

function resizeInput() {
  nextTick(() => {
    const textarea = inputRef.value;
    if (!textarea) {
      updateInputDockHeight();
      return;
    }

    textarea.style.height = 'auto';
    const nextHeight = Math.min(textarea.scrollHeight, inputMaxHeight);
    textarea.style.height = `${Math.max(inputMinHeight, nextHeight)}px`;
    textarea.style.overflowY = textarea.scrollHeight > inputMaxHeight ? 'auto' : 'hidden';
    isInputExpanded.value = textarea.scrollHeight > inputMinHeight + 2 || input.value.message.includes('\n');
    updateInputDockHeight();
  });
}

watch(wsData, val => {
  if (!val) return;
  const data = JSON.parse(val);
  if (data.type === 'title_updated') {
    chatStore.loadSessions();
    return;
  }

  const assistant = messages.value[messages.value.length - 1];

  if (data.type === 'completion' && data.status === 'finished') {
    if (assistant?.role === 'assistant' && assistant.status !== 'error') {
      assistant.status = 'finished';
    }
    chatStore.refreshActiveSessionMessages();
  } else if (data.error) {
    if (assistant) assistant.status = 'error';
  } else if (data.chunk) {
    if (!assistant) return;
    assistant.status = 'loading';
    assistant.content += data.chunk;
  }
  scrollToBottom();
});

watch(() => [...messages.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999,
      behavior: 'auto'
    });
  }, 80);
}

const handleSend = async () => {
  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));

    messages.value[messages.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) messages.value.pop();
    return;
  }

  const sessionId = await chatStore.ensureActiveSession();
  if (!sessionId) return;

  const content = input.value.message.trim();
  if (!content) return;

  messages.value.push({
    content,
    role: 'user',
    status: 'finished',
    timestamp: new Date().toISOString()
  });
  messages.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  chatStore.wsSend(
    JSON.stringify({
      type: 'message',
      sessionId,
      content
    } satisfies Api.Chat.SendPayload)
  );
  input.value.message = '';
  resizeInput();
};
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  if (!textarea) return;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
    resizeInput();
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.isComposing) return;

  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
  resizeInput();
  inputDockResizeObserver = new ResizeObserver(updateInputDockHeight);
  if (inputDockRef.value) inputDockResizeObserver.observe(inputDockRef.value);
  updateInputDockHeight();
});

watch(() => input.value.message, resizeInput);

onUnmounted(() => {
  inputDockResizeObserver?.disconnect();
  inputDockResizeObserver = null;
});
</script>

<template>
  <main class="chat-workspace relative min-w-0 flex flex-1 flex-col">
    <div class="chat-toolbar">
      <div class="min-w-0">
        <NText strong class="block truncate text-15px">{{ activeSession?.title || '新会话' }}</NText>
      </div>
      <div class="chat-connection" :class="`is-${wsStatus.toLowerCase()}`">
        <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
        <NText class="text-12px">{{ wsStatus === 'OPEN' ? '已连接' : wsStatus === 'CONNECTING' ? '连接中' : '未连接' }}</NText>
      </div>
    </div>

    <NScrollbar
      ref="scrollbarRef"
      class="min-h-0 flex-1"
      :content-style="scrollbarContentStyle"
    >
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in messages" :key="item.id || index" :msg="item" />
        </VueMarkdownItProvider>
        <NEmpty v-if="!messages.length" description="暂无消息" class="mt-30" />
      </NSpin>
    </NScrollbar>

    <div
      ref="inputDockRef"
      class="chat-input-dock pointer-events-none absolute inset-x-0 bottom-0 z-10 flex flex-col items-center px-4 pb-3 pt-4"
    >
      <div class="chat-input pointer-events-auto" :class="{ 'chat-input--expanded': isInputExpanded }">
        <textarea
          ref="inputRef"
          v-model="input.message"
          rows="1"
          placeholder="有问题，尽管问"
          class="chat-input__textarea"
          @keydown="handShortcut"
          @input="resizeInput"
        />
        <div class="chat-input__toolbar">
          <div class="chat-input__tools"></div>
          <NButton
            :disabled="sendable"
            strong
            circle
            type="primary"
            class="chat-input__send"
            @click="handleSend"
          >
            <template #icon>
              <icon-material-symbols:stop-rounded v-if="isSending" />
              <icon-guidance:send v-else />
            </template>
          </NButton>
        </div>
      </div>
      <p class="pointer-events-none mt-3 text-center text-13px color-#9ca3af dark:color-#6b7280">
        NexusMind 也可能会犯错。请核查重要信息。
      </p>
    </div>
  </main>
</template>

<style scoped lang="scss">
.chat-input-dock {
  isolation: isolate;
  background: linear-gradient(to bottom, rgb(255 255 255 / 0%), #fff 30%, #fff 100%);

  &::before {
    content: '';
    position: absolute;
    top: 28px;
    bottom: 0;
    left: 50%;
    z-index: 0;
    width: min(calc(100% - 32px), 860px);
    border-radius: 18px 18px 0 0;
    background: transparent;
    transform: translateX(-50%);
  }
}

:global(.dark) .chat-input-dock::before {
  background: #18181c;
}

.chat-input,
.chat-input-dock > p {
  position: relative;
  z-index: 1;
}

.chat-input {
  display: flex;
  width: 100%;
  max-width: 860px;
  min-height: 68px;
  align-items: center;
  gap: 12px;
  border: 1px solid #dfe6eb;
  border-radius: 999px;
  background: #fff;
  padding: 11px 12px 11px 18px;
  box-shadow: 0 10px 30px rgb(15 23 42 / 8%);
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.chat-input:focus-within {
  border-color: #84a6f2;
  box-shadow: 0 0 0 3px rgb(36 91 219 / 10%), 0 12px 32px rgb(15 23 42 / 8%);
}

:global(.dark) .chat-input {
  background: #18181c;
  box-shadow: 0 0 0 1px #2b2b31;
}

.chat-input--expanded {
  min-height: 136px;
  align-items: stretch;
  flex-direction: column;
  gap: 14px;
  border-radius: 24px;
  padding: 18px 14px 12px 22px;
}

.chat-input__textarea {
  min-height: 24px;
  max-height: 200px;
  flex: 1;
  resize: none;
  overflow-y: hidden;
  border: 0;
  background: transparent;
  padding: 0;
  color: #333;
  font-size: 15px;
  line-height: 24px;
  outline: none;
  caret-color: rgb(var(--primary-color));
}

.chat-input__textarea::placeholder {
  color: #9ca3af;
}

:global(.dark) .chat-input__textarea {
  color: #f1f1f1;
}

:global(.dark) .chat-input__textarea::placeholder {
  color: #6b7280;
}

.chat-input--expanded .chat-input__textarea {
  width: 100%;
  flex: none;
}

.chat-input__toolbar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
}

.chat-input--expanded .chat-input__toolbar {
  width: 100%;
  justify-content: space-between;
}

.chat-input__tools {
  min-width: 1px;
}

.chat-input__send {
  flex-shrink: 0;
  --n-width: 38px !important;
  --n-height: 38px !important;
  --n-border-radius: 999px !important;
  border-radius: 50% !important;
}

.chat-workspace {
  background: #fff;
}

.chat-toolbar {
  display: flex;
  min-height: 62px;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid #e7ebef;
  background: #fff;
  padding: 10px 24px;
}

.chat-connection {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #dce6e4;
  border-radius: 999px;
  background: #f6faf9;
  padding: 5px 9px;
  color: #64748b;
}

:global(.dark) .chat-workspace,
:global(.dark) .chat-toolbar {
  border-color: #2b3440;
  background: #181e25;
}

@media (max-width: 640px) {
  .chat-toolbar {
    min-height: 56px;
    padding-right: 14px;
    padding-left: 48px;
  }

  .chat-connection {
    border: 0;
    background: transparent;
    padding-inline: 0;
  }

  .chat-input-dock {
    padding-inline: 10px;
  }

  .chat-input {
    border-radius: 999px;
  }

  .chat-input--expanded {
    border-radius: 22px;
  }
}
</style>
