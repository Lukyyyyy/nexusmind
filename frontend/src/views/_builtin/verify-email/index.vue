<script setup lang="ts">
const route = useRoute();
const loading = ref(true);
const success = ref(false);
const message = ref('正在验证邮箱…');
onMounted(async () => {
  const token = String(route.query.token || '');
  if (!token) { loading.value = false; message.value = '验证链接缺少令牌'; return; }
  const { error } = await request({ url: '/email/verify', params: { token } });
  success.value = !error;
  message.value = error ? '验证链接无效或已过期' : '邮箱验证成功';
  loading.value = false;
});
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-[#f5f7fb] p-20px">
    <NCard class="max-w-440px w-full text-center" :bordered="false">
      <NSpin :show="loading">
        <div class="py-36px">
          <div class="mx-auto mb-18px grid h-54px w-54px place-items-center rounded-full" :class="success ? 'bg-green-50 text-green-600' : 'bg-blue-50 text-blue-600'">
            <SvgIcon :icon="success ? 'solar:check-circle-bold' : 'solar:letter-linear'" class="text-28px" />
          </div>
          <h1 class="m-0 text-20px font-600">{{ message }}</h1>
          <p class="mb-24px mt-8px text-13px text-gray-500">{{ success ? '现在可以接收知枢 NexusMind 的组织审批邮件。' : '请重新登录后发送新的验证邮件。' }}</p>
          <RouterLink to="/login"><NButton type="primary">返回登录</NButton></RouterLink>
        </div>
      </NSpin>
    </NCard>
  </div>
</template>
