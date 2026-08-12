<script setup lang="ts">
defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const appStore = useAppStore();
const { sessions, activeSessionId, sessionLoading } = storeToRefs(chatStore);

const editingId = ref<number | null>(null);
const editingTitle = ref('');
const collapsedStorageKey = 'nexusmind-chat-panel-collapsed';
const collapsed = ref(
  appStore.isMobile || (typeof window !== 'undefined' && window.localStorage.getItem(collapsedStorageKey) === 'true')
);

watch(
  () => appStore.isMobile,
  isMobile => {
    if (isMobile) collapsed.value = true;
  },
  { immediate: true }
);

function toggleCollapsed() {
  collapsed.value = !collapsed.value;
  window.localStorage.setItem(collapsedStorageKey, String(collapsed.value));
  editingId.value = null;
}

async function handleCreate() {
  await chatStore.createSession();
}

async function handleSelect(sessionId: number) {
  if (editingId.value) return;
  await chatStore.selectSession(sessionId);
}

function startRename(session: Api.Chat.Session) {
  editingId.value = session.id;
  editingTitle.value = session.title;
}

async function submitRename(sessionId: number) {
  const title = editingTitle.value.trim();
  if (!title) {
    window.$message?.error('会话标题不能为空');
    return;
  }
  const ok = await chatStore.renameSession(sessionId, title);
  if (ok) {
    editingId.value = null;
    editingTitle.value = '';
  }
}

async function handleDelete(sessionId: number) {
  window.$dialog?.warning({
    title: '删除会话',
    content: '删除后该会话将不再显示。',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await chatStore.deleteSession(sessionId);
    }
  });
}
</script>

<template>
  <aside
    class="chat-list relative h-full shrink-0 b-r b-#e5e7eb bg-white dark:b-#2b2b31 dark:bg-#18181c"
    :class="{ 'is-collapsed': collapsed }"
  >
    <NTooltip placement="right">
      <template #trigger>
        <button type="button" class="chat-list__toggle" @click="toggleCollapsed">
          <icon-material-symbols:keyboard-arrow-right v-if="collapsed" />
          <icon-material-symbols:keyboard-arrow-left v-else />
        </button>
      </template>
      {{ collapsed ? '展开会话列表' : '折叠会话列表' }}
    </NTooltip>

    <div v-if="!collapsed" class="chat-list__header">
      <div>
        <NText strong class="block text-14px">历史会话</NText>
      </div>
      <NButton size="small" type="primary" circle aria-label="新建会话" @click="handleCreate">
        <template #icon>
          <icon-material-symbols:add />
        </template>
      </NButton>
    </div>

    <NSpin v-if="!collapsed" :show="sessionLoading">
      <NScrollbar class="chat-list__scroll">
        <div class="flex flex-col gap-2">
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="group w-full rounded-6px px-3 py-2 text-left transition-colors"
            :class="
              activeSessionId === session.id
                ? 'bg-primary/12 color-[rgb(var(--primary-color))]'
                : 'hover:bg-#f1f3f7 dark:hover:bg-#24242a'
            "
            @click="handleSelect(session.id)"
          >
            <div v-if="editingId === session.id" class="flex items-center gap-1">
              <NInput
                v-model:value="editingTitle"
                size="small"
                autofocus
                @keydown.enter.stop="submitRename(session.id)"
                @keydown.esc.stop="editingId = null"
              />
              <NButton size="tiny" quaternary circle @click.stop="submitRename(session.id)">
                <template #icon>
                  <icon-material-symbols:check />
                </template>
              </NButton>
            </div>
            <div v-else class="flex items-center gap-2">
              <NText class="min-w-0 flex-1 truncate text-14px">{{ session.title }}</NText>
              <NButton
                size="tiny"
                quaternary
                circle
                class="opacity-0 group-hover:opacity-100"
                @click.stop="startRename(session)"
              >
                <template #icon>
                  <icon-material-symbols:edit-outline />
                </template>
              </NButton>
              <NButton
                size="tiny"
                quaternary
                circle
                class="opacity-0 group-hover:opacity-100"
                @click.stop="handleDelete(session.id)"
              >
                <template #icon>
                  <icon-material-symbols:delete-outline />
                </template>
              </NButton>
            </div>
          </button>
          <NEmpty v-if="!sessions.length" description="暂无会话" class="mt-20" />
        </div>
      </NScrollbar>
    </NSpin>
  </aside>
</template>

<style scoped lang="scss">
.chat-list {
  width: 248px;
  padding: 16px 12px 12px;
  transition:
    width 180ms ease,
    padding 180ms ease;
  overflow: visible;
}

.chat-list.is-collapsed {
  width: 0;
  padding-right: 0;
  padding-left: 0;
}

.chat-list__scroll {
  height: calc(100vh - 174px);
}

.chat-list__header {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  padding: 0 5px;
}

.chat-list__toggle {
  position: absolute;
  top: 50%;
  right: -29px;
  z-index: 10;
  display: flex;
  width: 29px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e7eb;
  border-radius: 0 8px 8px 0;
  background: #fff;
  color: #60636f;
  font-size: 20px;
  line-height: 1;
  box-shadow: 0 3px 10px rgb(15 23 42 / 6%);
  transform: translateY(-50%);
  transition:
    color 160ms ease,
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.chat-list__toggle:hover {
  border-color: rgb(var(--primary-color));
  color: rgb(var(--primary-color));
  box-shadow: 0 4px 12px rgb(15 23 42 / 9%);
}

:global(.dark) .chat-list__toggle {
  border-color: #2b2b31;
  background: #18181c;
  color: #c9ccd6;
}

@media (max-width: 760px) {
  .chat-list:not(.is-collapsed) {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 20;
    box-shadow: 10px 0 30px rgb(15 23 42 / 12%);
  }
}
</style>
