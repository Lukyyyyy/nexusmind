<script setup lang="tsx">
import { NButton, NTag } from 'naive-ui';

const status = ref<'PENDING' | 'ALL'>('PENDING');
const page = ref(1);
const loading = ref(false);
const data = ref<Api.Organization.JoinRequest[]>([]);
const total = ref(0);
const pending = ref(0);

async function load() {
  loading.value = true;
  const { data: result, error } = await request<Api.Organization.RequestPage>({
    url: '/admin/organization-management/requests',
    params: { status: status.value, page: page.value, size: 20 }
  });
  if (!error) {
    data.value = result.content;
    total.value = result.totalElements;
    pending.value = result.pending || 0;
  }
  loading.value = false;
}

watch([status, page], load);
onMounted(load);

const decisionVisible = ref(false);
const decisionApprove = ref(false);
const selected = ref<Api.Organization.JoinRequest | null>(null);
const reason = ref('');

function openDecision(item: Api.Organization.JoinRequest, approve: boolean) {
  selected.value = item;
  decisionApprove.value = approve;
  reason.value = '';
  decisionVisible.value = true;
}

async function submitDecision() {
  if (!decisionApprove.value && !reason.value.trim()) {
    window.$message?.warning('请填写拒绝原因');
    return false;
  }
  const { error } = await request({
    url: `/admin/organization-management/requests/${selected.value?.id}/decision`,
    method: 'POST',
    data: { approve: decisionApprove.value, reason: reason.value.trim() || undefined }
  });
  if (!error) {
    decisionVisible.value = false;
    window.$message?.success('申请已处理');
    await load();
  }
  return !error;
}

const statusText: Record<Api.Organization.JoinRequest['status'], string> = {
  PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝', WITHDRAWN: '已撤回', ARCHIVED: '组织已归档', REMOVED_BY_ADMIN: '管理员移除'
};
const columns = [
  { title: '申请人', key: 'displayName', width: 160, render: (row: Api.Organization.JoinRequest) => `${row.displayName || row.username}（${row.username}）` },
  { title: '目标组织', key: 'organization', minWidth: 180, ellipsis: { tooltip: true } },
  { title: '申请理由', key: 'reason', minWidth: 220, ellipsis: { tooltip: true } },
  { title: '申请时间', key: 'createdAt', width: 165, render: (row: Api.Organization.JoinRequest) => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  { title: '状态', key: 'status', width: 100, render: (row: Api.Organization.JoinRequest) => <NTag size="small" type={row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'error' : row.status === 'PENDING' ? 'warning' : 'default'}>{statusText[row.status]}</NTag> },
  { title: '处理人', key: 'handledBy', width: 110, render: (row: Api.Organization.JoinRequest) => row.handledBy || '—' },
  { title: '操作', key: 'operate', width: 150, fixed: 'right' as const, render: (row: Api.Organization.JoinRequest) => row.status === 'PENDING' ? <div class="flex gap-2"><NButton size="small" type="primary" onClick={() => openDecision(row, true)}>批准</NButton><NButton size="small" type="error" secondary onClick={() => openDecision(row, false)}>拒绝</NButton></div> : '—' }
];
</script>

<template>
  <div>
    <div class="mb-14px flex items-center justify-between">
      <NRadioGroup v-model:value="status" size="small">
        <NRadioButton value="PENDING">待审批 {{ pending ? `(${pending})` : '' }}</NRadioButton>
        <NRadioButton value="ALL">全部记录</NRadioButton>
      </NRadioGroup>
      <NButton quaternary size="small" :loading="loading" @click="load"><template #icon><SvgIcon icon="solar:refresh-linear" /></template>刷新</NButton>
    </div>
    <NDataTable :columns="columns" :data="data" :loading="loading" :scroll-x="1050" :row-key="row => row.id" />
    <div v-if="total > 20" class="mt-16px flex justify-end"><NPagination v-model:page="page" :page-size="20" :item-count="total" /></div>

    <NModal
      v-model:show="decisionVisible"
      preset="dialog"
      :title="decisionApprove ? '批准入组申请' : '拒绝入组申请'"
      :positive-text="decisionApprove ? '确认批准' : '确认拒绝'"
      negative-text="取消"
      @positive-click="submitDecision"
    >
      <div class="mb-12px text-13px text-gray-500">{{ selected?.displayName || selected?.username }}（{{ selected?.username }}）· {{ selected?.organization }}</div>
      <NInput v-model:value="reason" type="textarea" maxlength="200" show-count :placeholder="decisionApprove ? '审批备注（可选）' : '拒绝原因（必填）'" />
    </NModal>
  </div>
</template>
