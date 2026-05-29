<script setup lang="tsx">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NEllipsis, NModal, NPopconfirm, NProgress, NTag, NTooltip, NUpload } from 'naive-ui';
import { uploadAccept } from '@/constants/common';
import { fakePaginationRequest } from '@/service/request';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import { getToken } from '@/store/modules/auth/shared';
import { getServiceBaseURL } from '@/utils/service';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';
import ChunkDialog from './modules/chunk-dialog.vue';

const appStore = useAppStore();

// 文件预览相关状态
const previewVisible = ref(false);
const previewFileName = ref('');
const chunkVisible = ref(false);
const chunkFileMd5 = ref('');
const chunkFileName = ref('');
const chunkActualParseEngine = ref<Api.KnowledgeBase.UploadTask['actualParseEngine']>(null);

function apiFn() {
  return fakePaginationRequest<Api.KnowledgeBase.List>({ url: '/documents/uploads' });
}

function renderIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="mx-4 text-12" />;
    return <SvgIcon localIcon="dflt" class="mx-4 text-12" />;
  }
  return null;
}

// 处理文件预览
function handleFilePreview(fileName: string) {
  previewFileName.value = fileName;
  previewVisible.value = true;
}

// 关闭文件预览
function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
}

function handleChunkView(row: Api.KnowledgeBase.UploadTask) {
  chunkFileMd5.value = row.fileMd5;
  chunkFileName.value = row.fileName;
  chunkActualParseEngine.value = row.actualParseEngine ?? row.parseEngine ?? null;
  chunkVisible.value = true;
}

const { columns, columnChecks, data, getData, loading } = useTable({
  apiFn,
  immediate: false,
  columns: () => [
    {
      key: 'fileName',
      title: '文件名',
      minWidth: 400,
      render: row => (
        <div class="flex items-center">
          {renderIcon(row.fileName)}
          <NEllipsis lineClamp={2} tooltip>
            <span
              class="cursor-pointer hover:text-primary transition-colors"
              onClick={() => handleFilePreview(row.fileName)}
            >
              {row.fileName}
            </span>
          </NEllipsis>
        </div>
      )
    },
    {
      key: 'totalSize',
      title: '文件大小',
      width: 100,
      render: row => fileSize(row.totalSize)
    },
    {
      key: 'status',
      title: '上传状态',
      width: 100,
      render: row => renderStatus(row.status, row.progress)
    },
    {
      key: 'processingState',
      title: '处理状态',
      width: 150,
      render: row => renderProcessingStatus(row)
    },
    {
      key: 'processingDurationMillis',
      title: '耗时',
      width: 110,
      render: row => formatDuration(resolveProcessingDuration(row, durationNow.value))
    },
    {
      key: 'uploaderName',
      title: '上传人',
      width: 110,
      ellipsis: { tooltip: true },
      render: row => row.uploaderName || row.userId || '-'
    },
    {
      key: 'orgTagName',
      title: '组织标签',
      width: 150,
      ellipsis: { tooltip: true, lineClamp: 2 }
    },
    {
      key: 'isPublic',
      title: '是否公开',
      width: 100,
      render: row => (row.public || row.isPublic ? <NTag type="success">公开</NTag> : <NTag type="warning">私有</NTag>)
    },
    {
      key: 'createdAt',
      title: '上传时间',
      width: 100,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD')
    },
    {
      key: 'operate',
      title: '操作',
      width: 240,
      render: row => (
        <div class="flex gap-4">
          {renderResumeUploadButton(row)}
          <NButton
            type="primary"
            ghost
            size="small"
            onClick={() => handleFilePreview(row.fileName)}
          >
            预览
          </NButton>
          <NButton
            type="info"
            ghost
            size="small"
            disabled={row.status !== UploadStatus.Completed}
            onClick={() => handleChunkView(row)}
          >
            切片
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.fileMd5)}>
            {{
              default: () => '确认删除当前文件吗？',
              trigger: () => (
                <NButton type="error" ghost size="small">
                  删除
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const durationNow = ref(Date.now());
const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
let durationRefreshTimer: ReturnType<typeof setInterval> | null = null;
let statusEventSource: EventSource | null = null;
onMounted(async () => {
  await getList();
  startProcessingStatusEvents();
  durationRefreshTimer = setInterval(() => {
    durationNow.value = Date.now();
  }, 1000);
});

onUnmounted(() => {
  if (durationRefreshTimer) clearInterval(durationRefreshTimer);
  statusEventSource?.close();
  statusEventSource = null;
});

/** 异步获取列表函数 该函数主要用于更新或初始化上传任务列表 它首先调用getData函数获取数据，然后根据获取到的数据状态更新任务列表 */
async function getList() {
  // 等待获取最新数据
  await getData();

  if (data.value.length === 0) {
    tasks.value = [];
    return;
  }

  // 遍历获取到的数据，以处理每个项目
  data.value.forEach(item => {
    // 检查项目状态是否为已完成
    if (item.status === UploadStatus.Completed) {
      // 查找任务列表中是否有匹配的文件MD5
      const index = tasks.value.findIndex(task => task.fileMd5 === item.fileMd5);
      // 如果找到匹配项，则更新其状态
      if (index !== -1) {
        Object.assign(tasks.value[index], item);
      } else {
        // 如果没有找到匹配项，则将该项目添加到任务列表中
        tasks.value.push(item);
      }
    } else if (!tasks.value.some(task => task.fileMd5 === item.fileMd5)) {
      // 如果项目状态不是已完成，并且任务列表中没有相同的文件MD5，则将该项目的状态设置为中断，并添加到任务列表中
      item.status = UploadStatus.Break;
      tasks.value.push(item);
    }
  });
}

async function handleDelete(fileMd5: string) {
  const index = tasks.value.findIndex(task => task.fileMd5 === fileMd5);

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // 如果文件一个分片也没有上传完成，则直接删除
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/documents/${fileMd5}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('删除成功');
    await getData();
  }
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region 检索知识库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

// 渲染上传状态
function renderStatus(status: UploadStatus, percentage: number) {
  if (status === UploadStatus.Completed) return <NTag type="success">已完成</NTag>;
  else if (status === UploadStatus.Break) return <NTag type="error">上传中断</NTag>;
  return <NProgress percentage={percentage} processing />;
}

function renderProcessingStatus(row: Api.KnowledgeBase.UploadTask) {
  if (!row.processingStage) {
    if (row.status === UploadStatus.Completed) return <NTag type="info">处理中</NTag>;
    return <NTag>未开始</NTag>;
  }

  const stageText = processingStageText(row.processingStage);
  if (row.processingState === 'FAILED') {
    return (
      <NTooltip>
        {{
          trigger: () => <NTag type="error">{stageText}失败</NTag>,
          default: () => row.processingError || row.processingMessage || '处理失败'
        }}
      </NTooltip>
    );
  }
  if (row.processingState === 'SUCCEEDED') return <NTag type="success">已入库</NTag>;
  if (row.processingState === 'RUNNING' || row.processingState === 'PENDING') return <NTag type="info">处理中</NTag>;
  return <NTag type="info">处理中</NTag>;
}

function processingStageText(stage: Api.KnowledgeBase.UploadTask['processingStage']) {
  const record: Record<string, string> = {
    QUEUED: '等待处理',
    PARSING: '解析中',
    CHUNKING: '切片完成',
    VECTORIZING: '向量化中',
    INDEXING: '入库中',
    COMPLETED: '处理完成',
    FAILED: '处理'
  };
  return stage ? record[stage] || stage : '未开始';
}

function startProcessingStatusEvents() {
  const token = getToken();
  if (!token || statusEventSource) return;

  const query = new URLSearchParams({ token });
  statusEventSource = new EventSource(`${baseURL}/upload/status/events?${query.toString()}`);

  statusEventSource.addEventListener('connected', () => {
    refreshProcessingStatusesSilently();
  });
  statusEventSource.addEventListener('processing-status', event => {
    const data = JSON.parse((event as MessageEvent).data) as Api.KnowledgeBase.ProcessingStatus;
    applyProcessingStatus(data);
  });
  statusEventSource.onerror = () => {
    refreshProcessingStatusesSilently();
  };
}

async function refreshProcessingStatusesSilently() {
  const activeTasks = tasks.value.filter(task => {
    if (task.status !== UploadStatus.Completed) return false;
    return !task.processingState || task.processingState === 'PENDING' || task.processingState === 'RUNNING';
  });

  await Promise.all(activeTasks.map(refreshProcessingStatusSilently));
}

async function refreshProcessingStatusSilently(task: Api.KnowledgeBase.UploadTask) {
  const { error, data } = await request<Api.KnowledgeBase.ProcessingStatus>({
    url: '/upload/status/processing',
    params: { file_md5: task.fileMd5 }
  });
  if (error || !data) return;

  task.processingStage = data.processingStage;
  task.processingState = data.processingState;
  task.processingMessage = data.processingMessage;
  task.processingError = data.processingError;
  task.parseEngine = data.parseEngine ?? task.parseEngine;
  task.actualParseEngine = data.actualParseEngine ?? task.actualParseEngine ?? task.parseEngine;
  task.processingDurationMillis = data.processingDurationMillis ?? task.processingDurationMillis;
  task.processingStartedAt = data.processingStartedAt ?? task.processingStartedAt;
  task.processingUpdatedAt = data.processingUpdatedAt ?? task.processingUpdatedAt;
  task.processingCompletedAt = data.processingCompletedAt ?? task.processingCompletedAt;
  task.serverTime = data.serverTime ?? task.serverTime;
  task.esDocumentCount = data.esDocumentCount;
  if (typeof data.parsedChunkCount === 'number') task.parsedChunkCount = data.parsedChunkCount;
  if (typeof data.vectorizedCount === 'number') task.vectorizedCount = data.vectorizedCount;
  if (typeof data.dbChunkCount === 'number') task.parsedChunkCount = data.dbChunkCount;
  if (typeof data.esDocumentCount === 'number') task.vectorizedCount = data.esDocumentCount;
}

function applyProcessingStatus(status: Api.KnowledgeBase.ProcessingStatus) {
  if (!status.fileMd5) return;
  const task = tasks.value.find(item => item.fileMd5 === status.fileMd5);
  if (!task) {
    refreshProcessingStatusesSilently();
    return;
  }

  task.processingStage = status.processingStage;
  task.processingState = status.processingState;
  task.processingMessage = status.processingMessage;
  task.processingError = status.processingError;
  task.parseEngine = status.parseEngine ?? task.parseEngine;
  task.actualParseEngine = status.actualParseEngine ?? task.actualParseEngine ?? task.parseEngine;
  task.processingDurationMillis = status.processingDurationMillis ?? task.processingDurationMillis;
  task.processingStartedAt = status.processingStartedAt ?? task.processingStartedAt;
  task.processingUpdatedAt = status.processingUpdatedAt ?? task.processingUpdatedAt;
  task.processingCompletedAt = status.processingCompletedAt ?? task.processingCompletedAt;
  task.serverTime = status.serverTime ?? task.serverTime;
  task.esDocumentCount = status.esDocumentCount ?? task.esDocumentCount;
  if (typeof status.parsedChunkCount === 'number') task.parsedChunkCount = status.parsedChunkCount;
  if (typeof status.vectorizedCount === 'number') task.vectorizedCount = status.vectorizedCount;
  if (typeof status.dbChunkCount === 'number') task.parsedChunkCount = status.dbChunkCount;
  if (typeof status.esDocumentCount === 'number') task.vectorizedCount = status.esDocumentCount;
}

function resolveProcessingDuration(row: Api.KnowledgeBase.UploadTask, now: number) {
  const startedAt = parseDate(row.processingStartedAt);
  if (startedAt) {
    const completedAt = parseDate(row.processingCompletedAt);
    const failedAt = row.processingState === 'FAILED' ? parseDate(row.processingUpdatedAt) : null;
    const endedAt = completedAt ?? failedAt ?? now;
    return Math.max(0, endedAt - startedAt);
  }

  return row.processingDurationMillis;
}

function parseDate(value?: string | null) {
  if (!value) return null;
  const time = dayjs(value).valueOf();
  return Number.isFinite(time) ? time : null;
}

function formatDuration(milliseconds?: number | null) {
  if (typeof milliseconds !== 'number' || milliseconds < 0) return '-';
  const totalSeconds = Math.floor(milliseconds / 1000);
  if (totalSeconds < 1) return '0s';
  if (totalSeconds < 60) return `${totalSeconds}s`;

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return seconds > 0 ? `${minutes}m${seconds}s` : `${minutes}m`;

  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  const parts = [`${hours}h`];
  if (remainingMinutes > 0) parts.push(`${remainingMinutes}m`);
  if (seconds > 0) parts.push(`${seconds}s`);
  return parts.join('');
}

// #region 文件续传
function renderResumeUploadButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" ghost onClick={() => resumeUpload(row)}>
          续传
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" ghost>
          续传
        </NButton>
      </NUpload>
    );
  }
  return null;
}

// 任务列表存在文件，直接续传
function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.KnowledgeBase.UploadTask
) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="文件列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleUpload" @refresh="getList">
          <template #prefix>
            <NButton size="small" ghost type="primary" @click="handleSearch">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              检索知识库
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>
      <NDataTable
        striped
        :columns="columns"
        :data="tasks"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="1450"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="false"
        class="sm:h-full"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />
    <ChunkDialog
      v-model:visible="chunkVisible"
      :file-md5="chunkFileMd5"
      :file-name="chunkFileName"
      :actual-parse-engine="chunkActualParseEngine"
    />
    
    <!-- 文件预览弹窗 -->
    <NModal
      v-model:show="previewVisible"
      preset="card"
      :title="previewFileName || '文件预览'"
      class="file-preview-modal"
      :style="{ width: 'min(1280px, 92vw)', height: 'min(900px, 92vh)', maxWidth: '1280px' }"
      @after-leave="closeFilePreview"
    >
      <FilePreview
        :file-name="previewFileName"
        :visible="previewVisible"
      />
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

:deep() {
  .n-progress-icon.n-progress-icon--as-text {
    white-space: nowrap;
  }
}

:global(.file-preview-modal) {
  max-height: 92vh;
  display: flex;
  flex-direction: column;
}

:global(.file-preview-modal > .n-card-header) {
  flex-shrink: 0;
}

:global(.file-preview-modal > .n-card__content) {
  flex: 1;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
</style>
