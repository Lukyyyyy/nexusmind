<script setup lang="tsx">
import { NButton, NTag } from 'naive-ui';
import UserSearch from './modules/user-search.vue';
import OrgTagSettingDialog from './modules/org-tag-setting-dialog.vue';
import UserAuditDrawer from './modules/user-audit-drawer.vue';

const appStore = useAppStore();
const authStore = useAuthStore();

function apiFn(params: Api.User.SearchParams) {
  return request<Api.User.List>({ url: '/admin/users/list', params });
}

const { columns, columnChecks, data, getData, getDataByPage, loading, mobilePagination, searchParams, resetSearchParams, updateSearchParams } = useTable({
  apiFn,
  apiParams: {
    keyword: null,
    orgTag: null,
    status: null,
    sortField: null,
    sortOrder: null
  },
  columns: () => [
    {
      key: 'index',
      title: '序号',
      width: 64
    },
    {
      key: 'displayName',
      title: '昵称',
      width: 120
    },
    {
      key: 'username',
      title: '用户名',
      width: 120
    },
    {
      key: 'orgTags',
      title: '组织',
      width: 180,
      render: row => (
        <div class="flex flex-wrap gap-2">
          {row.orgTags.map(tag => (
            <NTag key={tag.tagId} type={tag.tagId === row.primaryOrg ? 'primary' : 'default'}>
              {tag.name}
            </NTag>
          ))}
        </div>
      )
    },
    {
      key: 'email',
      title: '邮箱',
      width: 200
    },
    {
      key: 'status',
      title: '是否启用',
      width: 100,
      render: row => <NTag type={row.status ? 'success' : 'warning'}>{row.status ? '已启用' : '已禁用'}</NTag>
    },
    {
      key: 'createTime',
      title: '创建时间',
      width: 200,
      sorter: true,
      defaultSortOrder: 'ascend',
      render: row => (row.createTime ? dayjs(row.createTime).format('YYYY-MM-DD HH:mm:ss') : '-')
    },
    {
      key: 'lastLoginTime',
      title: '最后登录时间',
      width: 200,
      sorter: true,
      render: row => (row.lastLoginTime ? dayjs(row.lastLoginTime).format('YYYY-MM-DD HH:mm:ss') : '从未登录')
    },
    {
      key: 'operate',
      title: '操作',
      fixed: 'right',
      width: 250,
      render: row => (
        <div class="flex gap-2">
          <NButton type="primary" ghost size="small" onClick={() => handleOrgTag(row)}>管理组织</NButton>
          <NButton ghost size="small" onClick={() => handleAudit(row)}>组织记录</NButton>
          {authStore.isSuperAdmin && row.role !== 'USER' && String(row.userId) !== String(authStore.userInfo.id) ? <NButton ghost size="small" type="warning" onClick={() => handleRole(row)}>角色</NButton> : null}
        </div>
      )
    }
  ]
});

type UserListSorterState = { columnKey: string | number; order?: 'ascend' | 'descend' | false };

/** 表头排序：更新查询参数并回到第一页重新加载（取消排序时后端按创建时间升序返回） */
function handleSorterChange(sorter: UserListSorterState | UserListSorterState[] | null) {
  const states = Array.isArray(sorter) ? sorter : sorter ? [sorter] : [];
  const active = states.find(item => item.order);
  updateSearchParams({
    sortField: active ? (String(active.columnKey) as Api.User.SearchParams['sortField']) : null,
    sortOrder: active?.order === 'descend' ? 'desc' : active?.order === 'ascend' ? 'asc' : null
  });
  getDataByPage(1);
}

const visible = ref(false);
const editingData = ref<Api.User.Item | null>(null);
function handleOrgTag(row: Api.User.Item) {
  editingData.value = row;
  visible.value = true;
}

const auditVisible = ref(false);
const auditUser = ref<Api.User.Item | null>(null);
function handleAudit(row: Api.User.Item) { auditUser.value = row; auditVisible.value = true; }

const roleVisible = ref(false);
const roleUser = ref<Api.User.Item | null>(null);
const roleReason = ref('');
const currentPassword = ref('');
const promote = ref(false);
function handleRole(row: Api.User.Item) { roleUser.value = row; promote.value = row.role === 'ADMIN'; roleReason.value = ''; currentPassword.value = ''; roleVisible.value = true; }
async function submitRole() {
  if (!roleReason.value.trim() || !currentPassword.value) return false;
  const { error } = await request({ url: `/admin/organization-management/users/${roleUser.value?.userId}/super-role`, method: 'POST', data: { promote: promote.value, reason: roleReason.value.trim(), currentPassword: currentPassword.value } });
  if (!error) { roleVisible.value = false; window.$message?.success('角色已更新，目标用户需要重新登录'); await getData(); }
  return !error;
}

// async function setPrimaryOrgTag(userId: string, primaryOrg: string) {
//   loading.value = true;
//   const { error } = await request({ url: 'users/primary-org', method: 'PUT', data: { primaryOrg, userId } });
//   if (!error) {
//     window.$message?.success('操作成功');
//     await getData();
//   }
//   loading.value = false;
// }
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <Teleport defer to="#header-extra">
      <UserSearch v-model:model="searchParams" @reset="resetSearchParams" @search="getData" />
    </Teleport>
    <NCard title="用户列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :addable="false" :loading="loading" @refresh="getData" />
      </template>
      <NDataTable
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="1464"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="mobilePagination"
        class="sm:h-full"
        @update:sorter="handleSorterChange"
      />
    </NCard>
    <OrgTagSettingDialog v-model:visible="visible" :row-data="editingData!" @submitted="getData" />
    <UserAuditDrawer v-model:visible="auditVisible" :user="auditUser" />
    <NModal v-model:show="roleVisible" preset="dialog" :title="promote ? '提升为超级管理员' : '降级为管理员'" positive-text="确认变更" negative-text="取消" @positive-click="submitRole">
      <NAlert type="warning" :bordered="false" class="mb-12px">此操作将使 {{ roleUser?.displayName || roleUser?.username }}（{{ roleUser?.username }}）的所有登录会话立即失效。</NAlert>
      <NInput v-model:value="roleReason" type="textarea" maxlength="200" show-count placeholder="变更原因（必填）" class="mb-10px" />
      <NInput v-model:value="currentPassword" type="password" show-password-on="click" placeholder="输入你的当前密码" />
    </NModal>
  </div>
</template>

<style scoped></style>
