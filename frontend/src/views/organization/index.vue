<script setup lang="ts">
import dayjs from 'dayjs';
defineOptions({ name: 'Organization' });

const route = useRoute();
const activeTab = ref(String(route.query.tab || 'mine'));
const loading = ref(false);
const overview = ref<Api.Organization.Overview>({ mine: [], discover: [], discoverTotal: 0, primaryOrg: '' });
const requests = ref<Api.Organization.RequestPage>({ content: [], page: 1, number: 1, size: 20, totalElements: 0 });
const keyword = ref('');
const page = ref(1);

const loadOverview = async () => {
  loading.value = true;
  const { data, error } = await request<Api.Organization.Overview>({
    url: '/organizations',
    params: { keyword: keyword.value || undefined, page: page.value, size: 12 }
  });
  if (!error) overview.value = data;
  loading.value = false;
};

const loadRequests = async () => {
  const { data, error } = await request<Api.Organization.RequestPage>({ url: '/organizations/requests' });
  if (!error) requests.value = data;
};

watch(activeTab, tab => {
  if (tab === 'requests') loadRequests();
});
watch(page, loadOverview);
onMounted(() => {
  loadOverview();
  if (activeTab.value === 'requests') loadRequests();
});

const applyVisible = ref(false);
const selectedOrganization = ref<Api.Organization.Item | null>(null);
const reason = ref('');
const actionLoading = ref(false);

function openApply(item: Api.Organization.Item) {
  selectedOrganization.value = item;
  reason.value = '';
  applyVisible.value = true;
}

async function submitApply() {
  if (!reason.value.trim()) {
    window.$message?.warning('请填写申请理由');
    return false;
  }
  actionLoading.value = true;
  const { error } = await request({
    url: `/organizations/${selectedOrganization.value?.tagId}/requests`,
    method: 'POST',
    data: { reason: reason.value.trim() }
  });
  actionLoading.value = false;
  if (!error) {
    applyVisible.value = false;
    window.$message?.success('申请已提交');
    await loadOverview();
  }
  return !error;
}

function confirmExit(item: Api.Organization.Item) {
  window.$dialog?.warning({
    title: '退出组织',
    content: `退出「${item.path}」后将立即失去组织文档访问权限${item.primary ? '，主组织会切回私人空间' : ''}。确定继续吗？`,
    positiveText: '确认退出',
    negativeText: '取消',
    async onPositiveClick() {
      const { error } = await request({ url: `/organizations/${item.tagId}/membership`, method: 'DELETE' });
      if (!error) {
        window.$message?.success('已退出组织');
        await loadOverview();
      }
    }
  });
}

async function setPrimary(tagId: string) {
  const { error } = await request({ url: '/users/primary-org', method: 'PUT', data: { primaryOrg: tagId } });
  if (!error) {
    window.$message?.success('主组织已更新');
    await loadOverview();
  }
}

async function withdraw(item: Api.Organization.JoinRequest) {
  const { error } = await request({ url: `/organizations/requests/${item.id}/withdraw`, method: 'POST' });
  if (!error) {
    window.$message?.success('申请已撤回');
    await Promise.all([loadRequests(), loadOverview()]);
  }
}

const statusMeta: Record<Api.Organization.JoinRequest['status'], { label: string; type: 'default' | 'success' | 'error' | 'warning' }> = {
  PENDING: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已批准', type: 'success' },
  REJECTED: { label: '已拒绝', type: 'error' },
  WITHDRAWN: { label: '已撤回', type: 'default' },
  ARCHIVED: { label: '组织已归档', type: 'default' },
  REMOVED_BY_ADMIN: { label: '管理员移除', type: 'warning' }
};
</script>

<template>
  <div class="organization-page">
    <NCard :bordered="false" size="small" content-style="padding: 0;" class="card-wrapper organization-shell">
      <template #header>
        <div class="page-heading">
          <div class="page-heading__icon"><SvgIcon icon="solar:buildings-3-linear" /></div>
          <div>
            <h1>组织关系</h1>
            <p>查看组织归属、发现可加入的团队并跟踪申请进度</p>
          </div>
        </div>
      </template>
      <NTabs v-model:value="activeTab" type="line" animated class="page-tabs">
        <NTabPane name="mine" tab="我的组织">
          <NSpin :show="loading">
            <div class="section-head">
              <div>
                <strong>直接加入的组织</strong>
                <p>你当前拥有成员身份的组织</p>
              </div>
              <span class="section-count">共 {{ overview.mine.length }} 个</span>
            </div>
            <div v-if="overview.mine.length" class="organization-grid">
              <article v-for="item in overview.mine" :key="item.tagId" class="organization-card">
                <div class="organization-card__main">
                  <div class="org-mark" :class="{ system: item.system }">
                    <SvgIcon :icon="item.system ? 'solar:lock-keyhole-minimalistic-linear' : 'solar:buildings-2-linear'" />
                  </div>
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-8px">
                      <span class="truncate text-15px font-600">{{ item.path }}</span>
                      <NTag v-if="item.primary" type="primary" size="small" :bordered="false">主组织</NTag>
                      <NTag v-if="item.archived" size="small" :bordered="false">已归档</NTag>
                      <NTag v-if="item.system" size="small" :bordered="false">系统组织</NTag>
                    </div>
                    <p>{{ item.description || '暂无描述' }}</p>
                  </div>
                </div>
                <div class="organization-card__actions">
                  <span>{{ item.primary ? '当前默认组织' : item.system ? '系统成员关系' : '已加入组织' }}</span>
                  <div class="row-actions">
                    <NButton
                      v-if="!item.primary && !item.system && !item.archived"
                      size="small"
                      quaternary
                      type="primary"
                      @click="setPrimary(item.tagId)"
                    >
                      设为主组织
                    </NButton>
                    <NButton v-if="!item.system" size="small" secondary type="error" @click="confirmExit(item)">退出组织</NButton>
                  </div>
                </div>
              </article>
            </div>
            <NEmpty v-else description="暂无组织" class="empty-panel" />
          </NSpin>
        </NTabPane>

        <NTabPane name="discover" tab="发现组织">
          <div class="section-head discover-head">
            <div>
              <strong>发现可加入的组织</strong>
              <p>搜索开放申请的团队，提交后由管理员审批</p>
            </div>
            <div class="search-toolbar">
              <NInput
                v-model:value="keyword"
                clearable
                placeholder="搜索组织名称或层级路径"
                @keyup.enter="page = 1; loadOverview()"
                @clear="page = 1; loadOverview()"
              >
                <template #prefix><SvgIcon icon="solar:magnifer-linear" /></template>
              </NInput>
              <NButton type="primary" @click="page = 1; loadOverview()">搜索</NButton>
            </div>
          </div>
          <NSpin :show="loading">
            <div v-if="overview.discover.length" class="organization-grid">
              <article v-for="item in overview.discover" :key="item.tagId" class="organization-card">
                <div class="organization-card__main">
                  <div class="org-mark"><SvgIcon icon="solar:buildings-2-linear" /></div>
                  <div class="min-w-0 flex-1">
                    <div class="truncate text-15px font-600">{{ item.path }}</div>
                    <p>{{ item.description || '暂无描述' }}</p>
                  </div>
                </div>
                <div class="organization-card__actions">
                  <span>开放申请</span>
                  <NButton v-if="item.membership === 'AVAILABLE'" type="primary" secondary size="small" @click="openApply(item)">申请加入</NButton>
                  <NTag v-else-if="item.membership === 'PENDING'" type="warning" :bordered="false">待审批</NTag>
                  <NTag v-else-if="item.membership === 'INHERITED'" :bordered="false">已通过子组织获得权限</NTag>
                  <NTag v-else type="success" :bordered="false">已加入</NTag>
                </div>
              </article>
            </div>
            <NEmpty v-else description="没有匹配的可申请组织" class="empty-panel" />
            <div v-if="overview.discoverTotal > 12" class="mt-18px flex justify-end">
              <NPagination v-model:page="page" :page-size="12" :item-count="overview.discoverTotal" />
            </div>
          </NSpin>
        </NTabPane>

        <NTabPane name="requests" tab="申请记录">
          <div class="section-head">
            <div>
              <strong>入组申请记录</strong>
              <p>查看申请状态与管理员处理结果</p>
            </div>
            <span class="section-count">共 {{ requests.totalElements }} 条</span>
          </div>
          <div v-if="requests.content.length" class="request-list">
            <div v-for="item in requests.content" :key="item.id" class="organization-row request-row">
              <div class="org-mark"><SvgIcon icon="solar:document-text-linear" /></div>
              <div class="min-w-0 flex-1">
                <div class="flex-y-center gap-8px">
                  <span class="truncate text-15px font-500">{{ item.organization }}</span>
                  <NTag :type="statusMeta[item.status].type" size="small" :bordered="false">{{ statusMeta[item.status].label }}</NTag>
                </div>
                <div class="mt-5px text-12px text-gray-500">申请理由：{{ item.reason }}</div>
                <div v-if="item.decisionReason" class="mt-3px text-12px text-gray-500">处理说明：{{ item.decisionReason }}</div>
              </div>
              <div class="request-meta">
                <span>{{ dayjs(item.createdAt).format('YYYY-MM-DD HH:mm') }}</span>
                <NButton v-if="item.status === 'PENDING'" size="small" secondary @click="withdraw(item)">撤回</NButton>
              </div>
            </div>
          </div>
          <NEmpty v-else description="暂无申请记录" class="empty-panel" />
        </NTabPane>
      </NTabs>
    </NCard>

    <NModal
      v-model:show="applyVisible"
      preset="dialog"
      title="申请加入组织"
      positive-text="提交申请"
      negative-text="取消"
      :loading="actionLoading"
      :positive-button-props="{ disabled: !reason.trim() }"
      @positive-click="submitApply"
    >
      <div class="mb-12px text-13px text-gray-500">{{ selectedOrganization?.path }}</div>
      <NInput v-model:value="reason" type="textarea" maxlength="200" show-count :autosize="{ minRows: 4, maxRows: 6 }" placeholder="请填写申请理由" />
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.organization-page { min-height: 100%; }
.page-heading { display: flex; align-items: center; gap: 12px; }
.page-heading__icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 6px; background: var(--nexus-primary-soft); color: var(--nexus-primary); font-size: 20px; }
.page-heading h1 { margin: 0; color: var(--nexus-text); font-size: 17px; font-weight: 650; line-height: 24px; }
.page-heading p { margin: 3px 0 0; color: var(--nexus-text-secondary); font-size: 12px; font-weight: 400; }
.page-tabs :deep(.n-tabs-nav) { padding: 0 18px; border-bottom: 1px solid var(--nexus-border); }
.page-tabs :deep(.n-tab-pane) { padding: 18px; }
.section-head { display: flex; align-items: flex-end; justify-content: space-between; padding: 0 2px 14px; }
.section-head strong { color: var(--nexus-text); font-size: 14px; font-weight: 600; }
.section-head p { margin: 4px 0 0; color: var(--nexus-text-secondary); font-size: 12px; }
.section-count { color: var(--nexus-text-secondary); font-size: 12px; font-weight: 400; }
.discover-head { align-items: center; gap: 24px; }
.search-toolbar { display: flex; width: min(520px, 50%); gap: 10px; }
.search-toolbar :deep(.n-input) { flex: 1; }
.organization-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.organization-card { display: flex; min-height: 148px; flex-direction: column; justify-content: space-between; overflow: hidden; border: 1px solid var(--nexus-border); border-radius: 6px; background: rgb(var(--container-bg-color)); box-shadow: var(--nexus-shadow-sm); transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease; }
.organization-card:hover { border-color: rgb(36 91 219 / 30%); box-shadow: 0 8px 20px rgb(15 23 42 / 7%); transform: translateY(-1px); }
.organization-card__main { display: flex; align-items: flex-start; gap: 13px; padding: 18px; }
.organization-card__main p { display: -webkit-box; overflow: hidden; margin: 6px 0 0; color: var(--nexus-text-secondary); font-size: 12px; line-height: 18px; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.organization-card__actions { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 14px 9px 18px; border-top: 1px solid var(--nexus-border); background: var(--nexus-fill); }
.organization-card__actions > span { color: var(--nexus-text-secondary); font-size: 11px; }
.request-list { overflow: hidden; border: 1px solid var(--nexus-border); border-radius: 6px; background: rgb(var(--container-bg-color)); }
.organization-row { display: flex; min-height: 76px; align-items: center; gap: 14px; padding: 14px 18px; border-bottom: 1px solid var(--nexus-border); transition: background-color 160ms ease; }
.organization-row:last-child { border-bottom: 0; }
.organization-row:hover { background: var(--nexus-primary-soft); }
.org-mark { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 5px; background: var(--nexus-primary-soft); color: var(--nexus-primary); font-size: 18px; }
.org-mark.system { background: var(--nexus-fill); color: var(--nexus-text-secondary); }
.row-actions { display: flex; align-items: center; gap: 6px; }
.request-meta { display: flex; min-width: 150px; flex-direction: column; align-items: flex-end; gap: 8px; color: var(--nexus-text-secondary); font-size: 12px; }
.empty-panel { padding: 56px 16px; border: 1px dashed var(--nexus-border); border-radius: 6px; background: var(--nexus-fill); }
@media (max-width: 960px) {
  .organization-grid { grid-template-columns: 1fr; }
  .discover-head { align-items: flex-start; flex-direction: column; }
  .search-toolbar { width: 100%; }
}
@media (max-width: 640px) {
  .page-heading__icon { display: none; }
  .page-tabs :deep(.n-tabs-nav) { padding: 0 14px; }
  .page-tabs :deep(.n-tab-pane) { padding: 14px; }
  .organization-row { align-items: flex-start; flex-wrap: wrap; padding: 14px; }
  .organization-card__main { padding: 14px; }
  .organization-card__actions { align-items: flex-start; flex-direction: column; padding: 10px 14px; }
  .row-actions, .request-meta { width: 100%; justify-content: flex-end; flex-direction: row; }
}
</style>
