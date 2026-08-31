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

function formatSessionTime(timestamp: string) {
  const date = dayjs(timestamp);
  if (!date.isValid()) return '';
  if (date.isSame(dayjs(), 'day')) return date.format('HH:mm');
  if (date.isSame(dayjs().subtract(1, 'day'), 'day')) return '昨天';
  return date.format('MM/DD');
}

function setTitleScrollDuration(event: MouseEvent) {
  const title = (event.currentTarget as HTMLElement).querySelector<HTMLElement>('.chat-list__session-title')!;
  title.style.setProperty('--title-scroll-duration', `${Math.max(0, title.scrollWidth - title.clientWidth) / 40}s`);
}

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
      <NButton type="primary" block class="chat-list__create" @click="handleCreate">
        <template #icon><icon-material-symbols:add-rounded /></template>
        新建会话
      </NButton>
      <div class="chat-list__heading">
        <NText strong>历史会话</NText>
        <span>{{ sessions.length }}</span>
      </div>
    </div>

    <NSpin v-if="!collapsed" :show="sessionLoading" class="chat-list__spin" content-class="chat-list__spin-content">
      <NScrollbar class="chat-list__scroll">
        <div class="chat-list__sessions">
          <button
            v-for="session in sessions"
            :key="session.id"
            type="button"
            class="chat-list__session group"
            :class="{ 'is-active': activeSessionId === session.id }"
            @click="handleSelect(session.id)"
            @mouseenter="setTitleScrollDuration"
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
            <div v-else class="chat-list__session-row">
              <icon-solar:chat-round-line-duotone class="chat-list__session-icon" />
              <span class="chat-list__session-title" :title="session.title">
                <NText class="chat-list__session-title-text">{{ session.title }}</NText>
              </span>
              <time>{{ formatSessionTime(session.updatedAt) }}</time>
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
  display: flex;
  flex-direction: column;
  width: 264px;
  border-color: #e4e9f0;
  background: #fff;
  padding: 14px 12px 12px;
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

.chat-list__spin {
  flex: 1 1 auto;
  min-height: 0;
}

.chat-list__spin :deep(.n-spin-content) {
  height: 100%;
}

.chat-list__scroll {
  height: 100%;
}

.chat-list__header {
  flex-shrink: 0;
  margin-bottom: 8px;
  padding: 0 2px;
}

.chat-list__create {
  --n-height: 40px !important;
  --n-border-radius: 9px !important;
  font-size: 14px;
}

.chat-list__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 20px;
  padding: 0 8px 8px;
  color: #667085;
  font-size: 12px;
}

.chat-list__heading span {
  color: #98a2b3;
  font-variant-numeric: tabular-nums;
}

.chat-list__sessions { display: flex; flex-direction: column; gap: 4px; }

.chat-list__session {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  padding: 10px;
  text-align: left;
  transition: 150ms ease;
}

.chat-list__session:hover { background: #f4f6f9; }
.chat-list__session.is-active { border-color: #dbe5ff; background: #eef3ff; }
.chat-list__session-row { display: flex; min-width: 0; align-items: center; gap: 8px; }
.chat-list__session-icon { flex: 0 0 auto; color: #8a96a8; font-size: 17px; }
.chat-list__session-title { min-width: 0; flex: 1; overflow: hidden; container-type: inline-size; }
.chat-list__session-title-text {
  display: inline-block;
  min-width: max-content;
  font-size: 14px;
  white-space: nowrap;
  transition: transform 120ms ease-out;
}
.chat-list__session:hover .chat-list__session-title-text {
  transform: translateX(min(0px, calc(100cqw - 100%)));
  transition: transform var(--title-scroll-duration) linear 100ms;
}
.chat-list__session.is-active .chat-list__session-icon { color: #356ae6; }
.chat-list__session time { flex: 0 0 auto; color: #98a2b3; font-size: 11px; font-variant-numeric: tabular-nums; }
.chat-list__session:hover time { display: none; }
.chat-list__session .n-button { transition: opacity 120ms ease; }
.chat-list__session:not(:hover) .n-button { width: 0; margin: 0; overflow: hidden; }

.chat-list__toggle {
  position: absolute;
  top: 50%;
  right: -17px;
  z-index: 10;
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e7eb;
  border-radius: 50%;
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

:global(.dark) .chat-list__session:hover { background: #24272d; }
:global(.dark) .chat-list__session.is-active { border-color: #3c527f; background: #202b40; }

@media (prefers-reduced-motion: reduce) {
  .chat-list__session-title-text { transition: none; }
}

.chat-list.is-collapsed .chat-list__toggle {
  right: -28px;
  border-radius: 0 8px 8px 0;
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
