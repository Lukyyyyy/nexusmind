<script setup lang="ts">
import { useCountDown } from '@sa/hooks';
import { REG_EMAIL } from '@/constants/reg';
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const { formRef, validate } = useNaiveForm();
const authStore = useAuthStore();

interface FormModel {
  email: string;
  verificationCode: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  email: '',
  verificationCode: '',
  password: '',
  confirmPassword: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
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
  const { error } = await fetchRegistrationCode(email);
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
  const email = model.email.trim().toLowerCase();
  const { error } = await fetchRegister(email, model.verificationCode, model.password);
  if (!error) {
    window.$message?.success('注册成功');
    await authStore.login(email, model.password);
  }
  loading.value = false;
}
</script>

<template>
  <NForm
    ref="formRef"
    class="register-form"
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
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="confirmPassword">
      <NInput
        v-model:value="model.confirmPassword"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <div class="register-actions">
      <NButton type="primary" size="large" block :loading="loading" @click="handleSubmit">
        {{ $t('page.login.common.register') }}
      </NButton>
    </div>
  </NForm>
</template>

<style scoped lang="scss">
.register-form {
  :deep(.n-form-item) {
    margin-bottom: 2px;
  }

  :deep(.n-input) {
    height: 38px;
    font-size: 13px;
  }
}

.register-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 8px;

  :deep(.n-button) {
    height: 40px;
    font-size: 15px;
    letter-spacing: 0;
  }
}

.verification-row {
  display: grid;
  grid-template-columns: 1fr 118px;
  gap: 12px;
  width: 100%;
}
</style>
