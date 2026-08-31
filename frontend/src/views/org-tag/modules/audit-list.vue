<script setup lang="tsx">
import dayjs from 'dayjs';

type AuditRow = {
  id: number;
  actor: string;
  actorRole: string;
  action: string;
  targetUserId?: number;
  targetOrgTag?: string;
  reason?: string;
  ipAddress?: string;
  createdAt: string;
};

const loading = ref(false);
const rows = ref<AuditRow[]>([]);
const keyword = ref('');
const pagination = reactive({ page: 1, pageSize: 20, itemCount: 0 });

const roleLabels: Record<string, string> = {
  USER: '普通用户',
  ADMIN: '管理员',
  SUPER_ADMIN: '超级管理员',
  SYSTEM: '系统'
};

const actionLabels: Record<string, string> = {
  ORG_JOIN_APPLIED: '申请加入组织',
  ORG_JOIN_WITHDRAWN: '撤回入组申请',
  ORG_EXITED: '退出组织',
  ORG_JOIN_APPROVED: '批准入组申请',
  ORG_JOIN_REJECTED: '拒绝入组申请',
  ORG_RESTORED: '恢复组织',
  ORG_ARCHIVED: '归档组织',
  ORG_APPLICATION_OPENED: '开放组织申请',
  ORG_APPLICATION_CLOSED: '关闭组织申请',
  ORG_MEMBERSHIP_ASSIGNED: '调整组织成员关系',
  SUPER_ADMIN_PROMOTED: '提升为超级管理员',
  SUPER_ADMIN_DEMOTED: '降级为管理员',
  PRIVATE_DOCUMENT_ACCESSED: '访问私人文档',
  SMTP_CONFIG_UPDATED: '更新邮件服务配置'
};

const columns = [
  { title: '时间', key: 'createdAt', width: 165, render: (row: AuditRow) => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm') },
  { title: '操作者', key: 'actor', width: 130 },
  { title: '角色', key: 'actorRole', width: 120, render: (row: AuditRow) => roleLabels[row.actorRole] || row.actorRole },
  { title: '事件', key: 'action', minWidth: 190, render: (row: AuditRow) => actionLabels[row.action] || row.action },
  { title: '目标用户', key: 'targetUserId', width: 110, render: (row: AuditRow) => row.targetUserId ?? '—' },
  { title: '目标组织', key: 'targetOrgTag', minWidth: 150, render: (row: AuditRow) => row.targetOrgTag || '—' },
  { title: '原因/资源', key: 'reason', minWidth: 220, ellipsis: { tooltip: true }, render: (row: AuditRow) => row.reason || '—' },
  { title: 'IP', key: 'ipAddress', width: 130, render: (row: AuditRow) => row.ipAddress || '—' }
];

async function load(page = pagination.page) {
  loading.value = true;
  const value = keyword.value.trim();
  const params: Record<string, string | number> = { page, size: pagination.pageSize };
  if (/^\d+$/.test(value)) params.userId = Number(value);
  else if (value) params.orgTag = value;
  const { data, error } = await request<{ content: AuditRow[]; totalElements: number }>({
    url: '/admin/organization-management/audit',
    params
  });
  loading.value = false;
  if (!error && data) {
    rows.value = data.content;
    pagination.page = page;
    pagination.itemCount = data.totalElements;
  }
}

onMounted(() => load());
</script>

<template>
  <div class="flex-col gap-12px">
    <div class="flex justify-end gap-8px">
      <NInput v-model:value="keyword" clearable class="w-260px" placeholder="用户 ID 或组织标签" @keyup.enter="load(1)" />
      <NButton type="primary" @click="load(1)">查询</NButton>
    </div>
    <NDataTable
      remote
      :columns="columns"
      :data="rows"
      :loading="loading"
      :pagination="pagination"
      :scroll-x="1195"
      :row-key="row => row.id"
      @update:page="load"
    />
  </div>
</template>
