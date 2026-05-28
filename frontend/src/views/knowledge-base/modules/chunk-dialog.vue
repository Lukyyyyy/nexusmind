<script setup lang="ts">
import type { DataTableColumns, PaginationProps } from 'naive-ui';

defineOptions({
  name: 'ChunkDialog'
});

const visible = defineModel<boolean>('visible', { default: false });

const props = defineProps<{
  fileMd5: string;
  fileName: string;
}>();

const loading = ref(false);
const detailLoading = ref(false);
const keyword = ref('');
const chunkPage = ref<Api.KnowledgeBase.DocumentChunkPage | null>(null);
const selectedChunk = ref<Api.KnowledgeBase.DocumentChunk | null>(null);
const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 20,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50]
});

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
      keyword: keyword.value || undefined
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
  detailLoading.value = true;
  const { error, data } = await request<Api.KnowledgeBase.DocumentChunk>({
    url: `/documents/${props.fileMd5}/chunks/${chunkId}`
  });

  if (!error) {
    selectedChunk.value = data;
  }

  detailLoading.value = false;
}

function handleSearch() {
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
    fetchChunks();
  } else {
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
          <NTag>chunkSize：{{ chunkPage?.configuredChunkSize ?? '-' }}</NTag>
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
            <pre v-else class="m-0 whitespace-pre-wrap break-words p-12px text-13px leading-6">{{
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
</style>
