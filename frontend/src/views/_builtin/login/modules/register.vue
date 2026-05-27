<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  username: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  username: '',
  password: '',
  confirmPassword: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, createConfirmPwdRule } = useFormRules();

  return {
    username: formRules.userName,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});

const loading = ref(false);
async function handleSubmit() {
  await validate();
  loading.value = true;
  const { error } = await fetchRegister(model.username, model.password);
  if (!error) {
    window.$message?.success('注册成功');
    toggleLoginModule('pwd-login');
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
    <NFormItem path="username">
      <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')">
        <template #prefix>
          <icon-ant-design:user-outlined />
        </template>
      </NInput>
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
</style>
