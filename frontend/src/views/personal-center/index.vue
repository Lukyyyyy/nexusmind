<script setup lang="ts">
import { useCountDown } from '@sa/hooks';
import { REG_EMAIL } from '@/constants/reg';

const { userInfo } = storeToRefs(useAuthStore());

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const loading = ref(false);
const getOrgTags = async () => {
  loading.value = true;
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
  loading.value = false;
};

onMounted(() => {
  getOrgTags();
});

const visible = ref(false);
const currentTagId = ref('');
const showModal = (tagId: string) => {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
};
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};

const emailProfile = reactive({ email: '', verified: false, organizationEmailEnabled: true });
const emailVisible = ref(false);
const emailInput = ref('');
const emailCode = ref('');
const emailLoading = ref(false);
const emailCodeLoading = ref(false);
const { count: emailCount, start: startEmailCount, isCounting: isEmailCounting } = useCountDown(60);
const loadEmail = async () => {
  const { data, error } = await request<typeof emailProfile>({ url: '/email/profile' });
  if (!error) Object.assign(emailProfile, data);
};
const requestEmailVerification = async () => {
  const email = emailInput.value.trim().toLowerCase();
  if (!REG_EMAIL.test(email)) {
    window.$message?.error('请输入有效邮箱');
    return;
  }
  emailCodeLoading.value = true;
  const { error } = await request({ url: '/email/verification', method: 'POST', data: { email } });
  emailCodeLoading.value = false;
  if (!error) {
    emailInput.value = email;
    startEmailCount();
    window.$message?.success('验证码已发送');
  }
};
const confirmEmail = async () => {
  if (!/^\d{6}$/.test(emailCode.value)) {
    window.$message?.error('请输入 6 位验证码');
    return false;
  }
  emailLoading.value = true;
  const { error } = await request({
    url: '/email/verification/confirm', method: 'POST',
    data: { email: emailInput.value.trim().toLowerCase(), verificationCode: emailCode.value }
  });
  emailLoading.value = false;
  if (!error) {
    window.$message?.success('登录邮箱已更新');
    await loadEmail();
  }
  return !error;
};
const openEmail = () => {
  emailInput.value = '';
  emailCode.value = '';
  emailVisible.value = true;
};
const saveEmailPreference = async (value: boolean) => {
  emailProfile.organizationEmailEnabled = value;
  const { error } = await request({ url: '/email/preferences', method: 'PUT', data: { organizationEmailEnabled: value } });
  if (error) emailProfile.organizationEmailEnabled = !value;
};
const nicknameVisible = ref(false);
const nicknameInput = ref('');
const nicknameLoading = ref(false);
const openNickname = () => {
  nicknameInput.value = userInfo.value.displayName || userInfo.value.username;
  nicknameVisible.value = true;
};
const saveNickname = async () => {
  const displayName = nicknameInput.value.trim();
  if (!displayName || [...displayName].length > 32) {
    window.$message?.error('昵称需为 1-32 个字符');
    return false;
  }
  nicknameLoading.value = true;
  const { data, error } = await request<{ displayName: string }>({
    url: '/users/display-name', method: 'PUT', data: { displayName }
  });
  nicknameLoading.value = false;
  if (!error) userInfo.value.displayName = data.displayName;
  return !error;
};

onMounted(loadEmail);
</script>

<template>
  <NSpin :show="loading">
    <div class="flex flex-col items-center gap-16px">
      <NCard class="min-h-400px min-w-600px w-50vw card-wrapper" :segmented="{ content: true, footer: 'soft' }">
        <template #header>
          <div class="flex items-center gap-4">
            <NAvatar size="large">
              <icon-solar:user-circle-linear class="text-icon-large" />
            </NAvatar>
            <div>
              <div class="flex-y-center gap-8px">
                {{ userInfo.displayName || userInfo.username }}
                <NButton text type="primary" size="tiny" @click="openNickname">修改昵称</NButton>
              </div>
              <div v-if="userInfo.displayName" class="text-12px text-gray-500">知枢ID：{{ userInfo.username }}</div>
            </div>
          </div>
        </template>
        <NScrollbar class="max-h-60vh">
          <div class="flex flex-wrap gap-4 p-4">
            <NCard
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              size="small"
              embedded
              hoverable
              class="w-[calc((100%-32px)/3)]"
              :segmented="{ content: true, footer: 'soft' }"
              @click="showModal(tag.tagId)"
            >
              <div class="flex items-center justify-between">
                <div>{{ tag.name }}</div>
                <NTag v-if="tag.tagId === tags.primaryOrg" type="primary" size="small">
                  主标签
                  <template #icon>
                    <icon-solar:verified-check-bold-duotone class="text-icon" />
                  </template>
                </NTag>
              </div>
              <template #footer>
                <NEllipsis :line-clamp="3">{{ tag.description }}</NEllipsis>
              </template>
            </NCard>
          </div>
        </NScrollbar>
      </NCard>

      <NCard class="min-w-600px w-50vw card-wrapper" title="邮箱与通知" :segmented="{ content: true }">
        <div class="flex items-center justify-between gap-20px p-4px">
          <div>
            <div class="flex-y-center gap-8px text-14px font-500">
              {{ emailProfile.verified ? emailProfile.email : '尚未绑定邮箱' }}
              <NTag v-if="emailProfile.verified" size="small" type="success" :bordered="false">已验证</NTag>
            </div>
            <div class="mt-6px text-12px text-gray-500">该邮箱用于登录，并接收组织申请与审批结果。</div>
          </div>
          <NButton type="primary" secondary @click="openEmail">更换邮箱</NButton>
        </div>
        <NDivider />
        <div class="flex items-center justify-between p-4px">
          <div><div class="text-14px font-500">组织审批邮件通知</div><div class="mt-4px text-12px text-gray-500">新申请、审批结果和管理员直接变更成员关系</div></div>
          <NSwitch :value="emailProfile.organizationEmailEnabled" :disabled="!emailProfile.verified" @update:value="saveEmailPreference" />
        </div>
      </NCard>

      <NModal
        v-model:show="visible"
        :loading="submitLoading"
        preset="dialog"
        title="设置主标签"
        content="确定将当前标签设置为主标签吗？"
        positive-text="确认"
        negative-text="取消"
        @positive-click="setPrimaryOrg"
        @negative-click="visible = false"
      />
      <NModal
        v-model:show="emailVisible"
        preset="dialog"
        :title="emailProfile.verified ? '更换邮箱' : '绑定邮箱'"
        positive-text="确认更换"
        negative-text="取消"
        :loading="emailLoading"
        @positive-click="confirmEmail"
      >
        <NSpace vertical class="w-full">
          <NInput v-model:value="emailInput" placeholder="请输入新邮箱" />
          <div class="flex gap-12px">
            <NInput v-model:value="emailCode" maxlength="6" placeholder="请输入验证码" />
            <NButton :disabled="isEmailCounting" :loading="emailCodeLoading" @click="requestEmailVerification">
              {{ isEmailCounting ? `${emailCount}秒后重发` : '发送验证码' }}
            </NButton>
          </div>
        </NSpace>
      </NModal>
      <NModal
        v-model:show="nicknameVisible"
        preset="dialog"
        title="修改昵称"
        positive-text="保存"
        negative-text="取消"
        :loading="nicknameLoading"
        @positive-click="saveNickname"
      >
        <NInput v-model:value="nicknameInput" maxlength="32" placeholder="请输入昵称" @keyup.enter="saveNickname" />
      </NModal>
    </div>
  </NSpin>
</template>

<style scoped lang="scss">
:deep(.n-card__content) {
  flex: none m !important;
  height: fit-content;
}
</style>
