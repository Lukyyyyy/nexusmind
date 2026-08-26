<script setup lang="ts">
const chatStore = useChatStore();
const router = useRouter();
const { activeSession, messages } = storeToRefs(chatStore);

const open = ref(false);
const loading = ref(false);
const saving = ref(false);
const options = ref<Api.Chat.ScopeOptions>({ privateAvailable: false, organizations: [], documents: [] });
const type = ref<Api.Chat.ScopeType>('ALL');
const orgTag = ref<string | null>(null);
const documentIds = ref<number[]>([]);

const modes: Array<{ type: Api.Chat.ScopeType; label: string; description: string }> = [
  { type: 'ALL', label: '全部知识', description: '检索所有可访问内容' },
  { type: 'PRIVATE', label: '私人空间', description: '只检索我的私有文档' },
  { type: 'ORGANIZATION', label: '指定组织', description: '检索组织内所有内容' },
  { type: 'DOCUMENTS', label: '指定文档', description: '最多选择 10 份文档' }
];

const currentScope = computed<Api.Chat.ScopeView>(() =>
  activeSession.value?.scope || { type: 'ALL', label: '全部知识', documentIds: [], details: [] }
);
const documentOptions = computed(() => options.value.documents.map(document => ({
  label: document.fileName,
  value: document.id
})));
const organizationOptions = computed(() => options.value.organizations.map(organization => ({
  label: `${organization.name} · ${organization.documentCount} 份文档`,
  value: organization.tagId
})));
const applyDisabled = computed(() =>
  saving.value
  || (type.value === 'PRIVATE' && !options.value.privateAvailable)
  || (type.value === 'ORGANIZATION' && !orgTag.value)
  || (type.value === 'DOCUMENTS' && documentIds.value.length === 0)
);

watch(open, async visible => {
  if (!visible) return;
  type.value = currentScope.value.type;
  orgTag.value = currentScope.value.orgTag || null;
  documentIds.value = [...(currentScope.value.documentIds || [])];
  loading.value = true;
  const { error, data } = await request<Api.Chat.ScopeOptions>({ url: 'chat/sessions/scope-options' });
  if (!error) options.value = data;
  loading.value = false;
});

function selectMode(mode: Api.Chat.ScopeType) {
  if (mode === 'PRIVATE' && !options.value.privateAvailable) return;
  type.value = mode;
}

function handleDocumentUpdate(values: number[]) {
  if (values.length > 10) {
    window.$message?.warning('最多选择 10 份文档');
    return;
  }
  documentIds.value = values;
}

async function apply() {
  if (applyDisabled.value) return;
  saving.value = true;
  const scope: Api.Chat.ScopeSelection = {
    type: type.value,
    orgTag: type.value === 'ORGANIZATION' ? orgTag.value : null,
    documentIds: type.value === 'DOCUMENTS' ? documentIds.value : []
  };
  const createdNewSession = messages.value.length > 0;
  const session = await chatStore.applyScope(scope);
  saving.value = false;
  if (!session) return;
  open.value = false;
  window.$message?.success(createdNewSession ? '已创建使用新范围的会话' : '问答范围已更新');
}

function goToKnowledgeBase() {
  open.value = false;
  router.push({ name: 'knowledge-base' });
}
</script>

<template>
  <NPopover
    v-model:show="open"
    trigger="click"
    placement="top-start"
    :show-arrow="false"
    raw
    class="scope-popover"
  >
    <template #trigger>
      <button
        type="button"
        class="scope-trigger"
        :class="{ 'is-open': open }"
        :aria-label="`更改检索范围，当前为${currentScope.label}`"
        title="更改检索范围"
      >
        <icon-solar:layers-minimalistic-linear class="scope-trigger__icon" />
        <span class="max-w-190px truncate">{{ currentScope.label }}</span>
      </button>
    </template>

    <section class="scope-panel">
      <header class="scope-panel__header">
        <div><h3>检索范围</h3></div>
        <span v-if="type === 'DOCUMENTS'" class="scope-count">{{ documentIds.length }}/10</span>
        <button type="button" class="scope-close" aria-label="关闭" @click="open = false">
          <icon-material-symbols:close-rounded />
        </button>
      </header>

      <NSpin :show="loading">
        <div class="scope-modes" role="radiogroup" aria-label="选择问答范围">
          <button
            v-for="mode in modes"
            :key="mode.type"
            type="button"
            role="radio"
            :aria-checked="type === mode.type"
            :disabled="mode.type === 'PRIVATE' && !options.privateAvailable"
            class="scope-mode"
            :class="{ 'is-active': type === mode.type }"
            @click="selectMode(mode.type)"
          >
            <span class="scope-mode__icon">
              <icon-solar:global-linear v-if="mode.type === 'ALL'" />
              <icon-solar:lock-keyhole-linear v-else-if="mode.type === 'PRIVATE'" />
              <icon-solar:buildings-2-linear v-else-if="mode.type === 'ORGANIZATION'" />
              <icon-solar:documents-linear v-else />
            </span>
            <span class="min-w-0 text-left">
              <strong>{{ mode.label }}</strong>
              <small>{{ mode.type === 'PRIVATE' && !options.privateAvailable ? '暂无可检索文档' : mode.description }}</small>
            </span>
            <span class="scope-mode__check"><icon-material-symbols:check-rounded /></span>
          </button>
        </div>

        <div v-if="type === 'ORGANIZATION'" class="scope-field">
          <label>选择组织</label>
          <NSelect v-model:value="orgTag" :options="organizationOptions" filterable placeholder="搜索组织" />
          <NEmpty v-if="!organizationOptions.length" description="暂无包含已入库文档的组织" size="small" />
        </div>

        <div v-if="type === 'DOCUMENTS'" class="scope-field">
          <label>选择文档</label>
          <NSelect
            :value="documentIds"
            :options="documentOptions"
            multiple
            filterable
            clearable
            max-tag-count="responsive"
            placeholder="搜索并选择已入库文档"
            @update:value="handleDocumentUpdate"
          />
          <div v-if="!documentOptions.length" class="scope-empty">
            <span>暂无可选择的已入库文档</span>
            <NButton text type="primary" @click="goToKnowledgeBase">前往知识库上传</NButton>
          </div>
        </div>
      </NSpin>

      <footer class="scope-panel__footer">
        <span>{{ messages.length ? '切换后将创建新会话' : '仅限于当前会话' }}</span>
        <NButton type="primary" :loading="saving" :disabled="applyDisabled" @click="apply">应用</NButton>
      </footer>
    </section>
  </NPopover>
</template>

<style scoped lang="scss">
:global(.scope-popover) { box-shadow: none !important; }

.scope-trigger {
  display: inline-flex;
  height: 32px;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 999px;
  background: #fff;
  padding: 0 11px;
  color: #6b6b6b;
  font-size: 13px;
  font-weight: 500;
  transition: 160ms ease;

  &:hover {
    background: #f2f2f2;
    color: #303030;
  }

  &.is-open { background: #ededed; color: #202020; }
  &:focus-visible { outline: 2px solid #84a6f2; outline-offset: 2px; }
}

.scope-trigger__icon { flex: none; color: #7b7b7b; font-size: 17px; }

.scope-panel {
  width: min(92vw, 376px);
  overflow: clip;
  border: 1px solid #e5e9f0;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 16px 40px rgb(15 23 42 / 14%);
}

.scope-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 17px 18px 12px;

  > div { min-width: 0; flex: 1; }

  h3 { margin: 0; color: #182230; font-size: 16px; font-weight: 700; }
  p { margin: 5px 0 0; color: #7b8794; font-size: 12px; }
}

.scope-close {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  margin: -3px -5px 0 2px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #7b8794;
  font-size: 18px;
}
.scope-close:hover { background: #f2f4f7; color: #344054; }

.scope-count {
  border-radius: 999px;
  background: #edf3ff;
  padding: 3px 8px;
  color: #245bdb;
  font-size: 12px;
  font-weight: 700;
}

.scope-modes { display: grid; gap: 6px; padding: 0 12px; }
.scope-mode {
  display: grid;
  min-height: 54px;
  grid-template-columns: 32px 1fr 18px;
  align-items: center;
  gap: 9px;
  border: 1px solid #e7ebf0;
  border-radius: 10px;
  background: #fff;
  padding: 7px 10px;
  transition: 160ms ease;

  &:hover:not(:disabled) { border-color: #b8c8ed; background: #fafbff; }
  &.is-active { border-color: #84a6f2; background: #f4f7ff; box-shadow: 0 0 0 2px rgb(36 91 219 / 8%); }
  &:disabled { cursor: not-allowed; opacity: 0.45; }
  strong { display: block; color: #344054; font-size: 13px; line-height: 18px; }
  small { display: block; margin-top: 2px; overflow: hidden; color: #8a94a2; font-size: 11px; line-height: 15px; text-overflow: ellipsis; white-space: nowrap; }
}
.scope-mode__icon { display: grid; height: 30px; place-items: center; border-radius: 8px; background: #f2f4f7; color: #667085; font-size: 17px; }
.scope-mode.is-active .scope-mode__icon { background: #e5edff; color: #245bdb; }
.scope-mode__check { display: grid; height: 17px; place-items: center; border: 1px solid #d0d5dd; border-radius: 50%; color: transparent; font-size: 13px; }
.scope-mode.is-active .scope-mode__check { border-color: #245bdb; background: #245bdb; color: #fff; }

.scope-field { padding: 13px 16px 2px; }
.scope-field label { display: block; margin-bottom: 7px; color: #475467; font-size: 12px; font-weight: 650; }
.scope-empty { display: flex; justify-content: space-between; padding: 12px 2px 2px; color: #98a2b3; font-size: 12px; }
.scope-panel__footer { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; border-top: 1px solid #edf0f4; padding: 12px 16px; }
.scope-panel__footer > span { color: #98a2b3; font-size: 12px; }

:global(.dark .scope-trigger) { background: #2b2d31; color: #c5c8ce; }
:global(.dark .scope-trigger:hover),
:global(.dark .scope-trigger.is-open) { background: #383b40; color: #f1f2f4; }
:global(.dark .scope-trigger__icon) { color: #aeb6c2; }
:global(.dark .scope-panel) { border-color: #30343b; background: #181a1f; box-shadow: 0 20px 60px rgb(0 0 0 / 42%); }
:global(.dark .scope-panel__header h3),
:global(.dark .scope-mode strong) { color: #edf0f5; }
:global(.dark .scope-mode) { border-color: #30343b; background: #1c1f24; }
:global(.dark .scope-mode:hover:not(:disabled)),
:global(.dark .scope-mode.is-active) { border-color: #597ed5; background: #202838; }
:global(.dark .scope-mode__icon) { background: #292d34; color: #aeb6c2; }
:global(.dark .scope-panel__footer) { border-color: #2e3239; }

@media (max-width: 560px) {
  .scope-panel { width: calc(100vw - 48px); }
  .scope-trigger span { max-width: 120px; }
}
</style>
