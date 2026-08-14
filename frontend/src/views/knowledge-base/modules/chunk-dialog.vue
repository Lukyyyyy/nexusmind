<script setup lang="ts">
import type { DataTableColumns, PaginationProps } from 'naive-ui';
import type { Directive } from 'vue';
import { h } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';

defineOptions({
  name: 'ChunkDialog'
});

const visible = defineModel<boolean>('visible', { default: false });

const props = defineProps<{
  fileMd5: string;
  fileName: string;
  actualParseEngine?: Api.KnowledgeBase.UploadTask['actualParseEngine'];
}>();

const loading = ref(false);
const detailLoading = ref(false);
const keyword = ref('');
const searchedKeyword = ref('');
const chunkPage = ref<Api.KnowledgeBase.DocumentChunkPage | null>(null);
const selectedChunk = ref<Api.KnowledgeBase.DocumentChunk | null>(null);
let detailRequestId = 0;
const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 20,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50]
});

const actualParseEngine = computed(() => chunkPage.value?.actualParseEngine ?? props.actualParseEngine ?? null);
const actualChunkSize = computed(() => chunkPage.value?.actualChunkSize ?? chunkPage.value?.configuredChunkSize ?? null);
const isSelectedMarkdown = computed(() => selectedChunk.value?.contentFormat === 'MARKDOWN');

const columns: DataTableColumns<Api.KnowledgeBase.DocumentChunk> = [
  {
    key: 'chunkId',
    title: '切片',
    width: 80,
    render: row => `#${row.chunkId}`
  },
  {
    key: 'contentLength',
    title: '字符数',
    width: 90
  },
  {
    key: 'byteSize',
    title: '字节数',
    width: 90,
    render: row => formatByteSize(row.byteSize)
  },
  {
    key: 'contentPreview',
    title: '内容预览',
    render: row => renderHighlightedText(row.contentPreview),
    ellipsis: {
      tooltip: true
    }
  }
];

async function fetchChunks() {
  if (!props.fileMd5) return;

  loading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.DocumentChunkPage>({
    url: `/documents/${props.fileMd5}/chunks`,
    params: {
      page: Number(pagination.page || 1) - 1,
      size: pagination.pageSize,
      keyword: searchedKeyword.value || undefined
    }
  });

  if (!error) {
    chunkPage.value = data;
    pagination.itemCount = data.totalChunks;
    selectedChunk.value = null;

    if (data.chunks.length > 0) {
      await fetchChunkDetail(data.chunks[0].chunkId);
    }
  }

  loading.value = false;
}

async function fetchChunkDetail(chunkId: number) {
  const requestId = ++detailRequestId;
  detailLoading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.DocumentChunk>({
    url: `/documents/${props.fileMd5}/chunks/${chunkId}`
  });

  if (requestId !== detailRequestId) return;

  if (!error) {
    selectedChunk.value = data;
  }

  detailLoading.value = false;
}

function handleSearch() {
  searchedKeyword.value = keyword.value.trim();
  pagination.page = 1;
  fetchChunks();
}

function handleClear() {
  keyword.value = '';
  handleSearch();
}

function handlePageChange(page: number) {
  pagination.page = page;
  fetchChunks();
}

function handlePageSizeChange(pageSize: number) {
  pagination.page = 1;
  pagination.pageSize = pageSize;
  fetchChunks();
}

function handleRowClick(row: Api.KnowledgeBase.DocumentChunk) {
  fetchChunkDetail(row.chunkId);
}

function formatByteSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function getHighlightParts(content: string, searchTerm: string) {
  const term = searchTerm.trim();
  if (!term) return [{ content, highlighted: false }];

  const parts: Array<{ content: string; highlighted: boolean }> = [];
  const normalizedContent = content.toLocaleLowerCase();
  const normalizedTerm = term.toLocaleLowerCase();
  let start = 0;
  let matchIndex = normalizedContent.indexOf(normalizedTerm);

  while (matchIndex !== -1) {
    if (matchIndex > start) {
      parts.push({ content: content.slice(start, matchIndex), highlighted: false });
    }
    parts.push({ content: content.slice(matchIndex, matchIndex + term.length), highlighted: true });
    start = matchIndex + term.length;
    matchIndex = normalizedContent.indexOf(normalizedTerm, start);
  }

  if (start < content.length) {
    parts.push({ content: content.slice(start), highlighted: false });
  }

  return parts.length > 0 ? parts : [{ content, highlighted: false }];
}

function renderHighlightedText(content: string) {
  return h(
    'span',
    getHighlightParts(content, searchedKeyword.value).map(part =>
      part.highlighted ? h('mark', { class: 'chunk-search-highlight' }, part.content) : part.content
    )
  );
}

function removeHighlights(element: HTMLElement) {
  element.querySelectorAll('mark[data-chunk-search-highlight]').forEach(mark => {
    mark.replaceWith(document.createTextNode(mark.textContent || ''));
  });
  element.normalize();
}

function highlightElement(element: HTMLElement, searchTerm: string) {
  removeHighlights(element);

  const term = searchTerm.trim();
  if (!term) return;

  const textNodes: Text[] = [];
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, {
    acceptNode: node => {
      const parent = node.parentElement;
      if (!node.textContent || parent?.closest('script, style, mark[data-chunk-search-highlight]')) {
        return NodeFilter.FILTER_REJECT;
      }
      return NodeFilter.FILTER_ACCEPT;
    }
  });

  let currentNode = walker.nextNode();
  while (currentNode) {
    textNodes.push(currentNode as Text);
    currentNode = walker.nextNode();
  }

  textNodes.forEach(textNode => {
    const text = textNode.data;
    const parts = getHighlightParts(text, term);
    if (!parts.some(part => part.highlighted)) return;

    const fragment = document.createDocumentFragment();
    parts.forEach(part => {
      if (part.highlighted) {
        const mark = document.createElement('mark');
        mark.dataset.chunkSearchHighlight = '';
        mark.className = 'chunk-search-highlight';
        mark.textContent = part.content;
        fragment.append(mark);
      } else {
        fragment.append(document.createTextNode(part.content));
      }
    });
    textNode.replaceWith(fragment);
  });
}

interface HighlightObserverState {
  keyword: string;
  observer: MutationObserver;
}

const highlightObserverStates = new WeakMap<HTMLElement, HighlightObserverState>();

function refreshObservedHighlight(element: HTMLElement, state: HighlightObserverState) {
  // Markdown rendering is asynchronous. Pause observation while adding marks so
  // our own DOM changes do not recursively trigger another highlight pass.
  state.observer.disconnect();
  highlightElement(element, state.keyword);
  state.observer.observe(element, { childList: true, characterData: true, subtree: true });
}

const vHighlight: Directive<HTMLElement, string> = {
  mounted: (element, binding) => {
    const state: HighlightObserverState = {
      keyword: binding.value,
      observer: new MutationObserver(() => refreshObservedHighlight(element, state))
    };
    highlightObserverStates.set(element, state);
    refreshObservedHighlight(element, state);
  },
  updated: (element, binding) => {
    const state = highlightObserverStates.get(element);
    if (!state) return;

    state.keyword = binding.value;
    refreshObservedHighlight(element, state);
  },
  beforeUnmount: element => {
    highlightObserverStates.get(element)?.observer.disconnect();
    highlightObserverStates.delete(element);
  }
};

function formatParseEngine(engine?: Api.KnowledgeBase.UploadTask['actualParseEngine']) {
  const record: Record<string, string> = {
    MINERU: 'MinerU',
    TIKA: 'Apache Tika',
    AUTO: '自动'
  };
  return engine ? record[engine] || engine : '未知';
}

function parseEngineTagType(engine?: Api.KnowledgeBase.UploadTask['actualParseEngine']) {
  if (engine === 'MINERU') return 'success';
  if (engine === 'TIKA') return 'info';
  if (engine === 'AUTO') return 'warning';
  return 'default';
}

function rowProps(row: Api.KnowledgeBase.DocumentChunk) {
  return {
    class: 'cursor-pointer',
    onClick: () => handleRowClick(row)
  };
}

function copySelectedChunk() {
  const content = selectedChunk.value?.content;
  if (!content) return;

  navigator.clipboard.writeText(content);
  window.$message?.success('切片内容已复制');
}

watch(visible, show => {
  if (show) {
    pagination.page = 1;
    keyword.value = '';
    searchedKeyword.value = '';
    fetchChunks();
  } else {
    detailRequestId += 1;
    detailLoading.value = false;
    chunkPage.value = null;
    selectedChunk.value = null;
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    :title="fileName"
    class="chunk-dialog"
    :style="{ width: 'min(1200px, 94vw)', height: 'min(760px, 88vh)' }"
  >
    <div class="h-full min-h-0 flex flex-col gap-12px">
      <div class="flex flex-wrap items-center justify-between gap-12px">
        <NSpace size="small">
          <NTag type="info">总切片：{{ chunkPage?.totalChunks ?? 0 }}</NTag>
          <NTag>切片大小：{{ actualChunkSize ?? '-' }}</NTag>
          <NTag :type="parseEngineTagType(actualParseEngine)">解析：{{ formatParseEngine(actualParseEngine) }}</NTag>
        </NSpace>
        <NSpace size="small">
          <NInput
            v-model:value="keyword"
            clearable
            placeholder="搜索切片内容"
            class="w-260px"
            @keyup.enter="handleSearch"
            @clear="handleClear"
          />
          <NButton type="primary" ghost :loading="loading" @click="handleSearch">
            <template #icon>
              <icon-ic-round-search class="text-icon" />
            </template>
            搜索
          </NButton>
        </NSpace>
      </div>

      <div class="chunk-main grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_minmax(360px,0.85fr)] gap-12px overflow-hidden lt-lg:grid-cols-1">
        <div class="min-h-0 overflow-hidden">
          <NDataTable
            :key="`chunks-${searchedKeyword}`"
            size="small"
            striped
            remote
            flex-height
            class="h-full"
            :loading="loading"
            :columns="columns"
            :data="chunkPage?.chunks || []"
            :row-key="row => row.chunkId"
            :pagination="pagination"
            :scroll-x="720"
            :row-props="rowProps"
            @update:page="handlePageChange"
            @update:page-size="handlePageSizeChange"
          />
        </div>

        <div class="chunk-detail-panel h-full min-h-0 flex flex-col overflow-hidden rounded-6px border border-[var(--n-border-color)]">
          <div class="flex shrink-0 items-center justify-between border-b border-[var(--n-border-color)] px-12px py-8px">
            <NSpace size="small">
              <NTag v-if="selectedChunk" type="success">#{{ selectedChunk.chunkId }}</NTag>
              <NTag v-if="selectedChunk" :type="isSelectedMarkdown ? 'success' : 'default'">
                {{ isSelectedMarkdown ? 'Markdown' : 'Plain text' }}
              </NTag>
              <NTag v-if="selectedChunk">字符：{{ selectedChunk.contentLength }}</NTag>
              <NTag v-if="selectedChunk">大小：{{ formatByteSize(selectedChunk.byteSize) }}</NTag>
              <NSpin v-if="detailLoading" size="small" />
            </NSpace>
            <NButton size="small" :disabled="!selectedChunk?.content" @click="copySelectedChunk">
              <template #icon>
                <icon-ic-round-content-copy class="text-icon" />
              </template>
              复制
            </NButton>
          </div>
          <div class="chunk-detail-scroll min-h-0 flex-1 overflow-y-auto">
            <NEmpty v-if="!selectedChunk" description="暂无切片内容" class="py-80px" />
            <div
              v-else-if="isSelectedMarkdown"
              :key="`markdown-${selectedChunk.chunkId}-${searchedKeyword}`"
              v-highlight="searchedKeyword"
              class="markdown-body chunk-markdown p-12px text-13px leading-6"
            >
              <VueMarkdownIt :content="selectedChunk.content || ''" />
            </div>
            <pre
              v-else
              :key="`plain-text-${selectedChunk.chunkId}-${searchedKeyword}`"
              v-highlight="searchedKeyword"
              class="m-0 whitespace-pre-wrap break-words p-12px text-13px leading-6"
            >{{
              selectedChunk.content
            }}</pre>
          </div>
        </div>
      </div>
    </div>
  </NModal>
</template>

<style scoped>
:global(.chunk-dialog > .n-card__content) {
  height: calc(100% - 57px);
  min-height: 0;
  overflow: hidden;
}

.chunk-detail-scroll {
  max-height: 100%;
  scrollbar-width: thin;
  overscroll-behavior: contain;
}

.chunk-main,
.chunk-detail-panel {
  height: 100%;
}

.chunk-markdown {
  background: transparent;
  color: inherit;
  font-size: 13px;
}

.chunk-markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  white-space: nowrap;
}

:deep(.chunk-search-highlight) {
  border-radius: 2px;
  background-color: #fadb14;
  color: #1f2329;
  box-shadow: 0 0 0 1px rgb(250 173 20 / 35%);
}
</style>
