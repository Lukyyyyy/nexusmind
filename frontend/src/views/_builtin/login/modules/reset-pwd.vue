<script setup lang="ts">
import { useCountDown } from '@sa/hooks';
import { REG_EMAIL } from '@/constants/reg';
import { useRouterPush } from '@/hooks/common/router';

defineOptions({ name: 'ResetPwd' });

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const model = reactive({ email: '', verificationCode: '', password: '', confirmPassword: '' });
const rules = computed(() => {
  const { formRules, createConfirmPwdRule } = useFormRules();
  return {
    email: formRules.email,
    verificationCode: formRules.code,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});
const loading = ref(false);
const codeLoading = ref(false);
const { count, start, isCounting } = useCountDown(60);

async function sendCode() {
  const email = model.email.trim().toLowerCase();
  if (!REG_EMAIL.test(email)) {
    window.$message?.error('请输入有效邮箱');
    return;
  }
  codeLoading.value = true;
  const { error } = await fetchPasswordResetCode(email);
  codeLoading.value = false;
  if (!error) {
    model.email = email;
    start();
    window.$message?.success('验证码已发送');
  }
}

async function handleSubmit() {
  await validate();
  loading.value = true;
  const { error } = await fetchResetPassword(
    model.email.trim().toLowerCase(),
    model.verificationCode,
    model.password
  );
  loading.value = false;
  if (!error) {
    window.$message?.success('密码已重置，请重新登录');
    await toggleLoginModule('pwd-login');
  }
}
</script>

<template>
  <NForm
    ref="formRef"
    class="reset-form"
    :model="model"
    :rules="rules"
    size="large"
    :show-label="false"
    @keyup.enter="handleSubmit"
  >
    <NFormItem path="email">
      <NInput v-model:value="model.email" placeholder="请输入邮箱">
        <template #prefix><icon-ant-design:mail-outlined /></template>
      </NInput>
    </NFormItem>
    <NFormItem path="verificationCode">
      <div class="verification-row">
        <NInput v-model:value="model.verificationCode" maxlength="6" placeholder="请输入验证码">
          <template #prefix><icon-ant-design:safety-certificate-outlined /></template>
        </NInput>
        <NButton :disabled="isCounting" :loading="codeLoading" @click="sendCode">
          {{ isCounting ? `${count}秒后重发` : '发送验证码' }}
        </NButton>
      </div>
    </NFormItem>
    <NFormItem path="password">
      <NInput v-model:value="model.password" type="password" show-password-on="click" placeholder="请输入新密码">
        <template #prefix><icon-ant-design:key-outlined /></template>
      </NInput>
    </NFormItem>
    <NFormItem path="confirmPassword">
      <NInput
        v-model:value="model.confirmPassword"
        type="password"
        show-password-on="click"
        placeholder="请再次输入新密码"
      >
        <template #prefix><icon-ant-design:key-outlined /></template>
      </NInput>
    </NFormItem>
    <NButton type="primary" size="large" block :loading="loading" @click="handleSubmit">重置密码</NButton>
  </NForm>
</template>

<style scoped lang="scss">
.reset-form {
  :deep(.n-form-item) {
    margin-bottom: 2px;
  }

  :deep(.n-input) {
    height: 38px;
    font-size: 13px;
  }

  :deep(.n-button) {
    height: 40px;
    font-size: 15px;
  }
}

.verification-row {
  display: grid;
  grid-template-columns: 1fr 118px;
  gap: 12px;
  width: 100%;
}
</style>
