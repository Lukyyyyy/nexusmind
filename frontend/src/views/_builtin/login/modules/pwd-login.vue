<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg } from '@/utils/storage';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
  remember: boolean;
}

const rememberedLogin = localStg.get('rememberedLogin');

const model: FormModel = reactive({
  userName: rememberedLogin?.userName || '',
  password: rememberedLogin?.password || '',
  remember: rememberedLogin?.remember || false
});

const rules = computed<Partial<Record<keyof FormModel, App.Global.FormRule[]>>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});

async function handleSubmit() {
  await validate();
  await authStore.login(model.userName, model.password);

  if (!authStore.isLogin) {
    return;
  }

  if (model.remember) {
    localStg.set('rememberedLogin', {
      userName: model.userName,
      password: model.password,
      remember: true
    });
  } else {
    localStg.remove('rememberedLogin');
  }
}
</script>

<template>
  <NForm
    ref="formRef"
    class="pwd-login-form"
    :model="model"
    :rules="rules"
    size="large"
    :show-label="false"
    @keyup.enter="handleSubmit"
  >
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')">
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
    <div class="login-options">
      <NCheckbox v-model:checked="model.remember">记住我</NCheckbox>
    </div>
    <div class="login-actions">
      <NButton type="primary" size="large" block :loading="authStore.loginLoading" @click="handleSubmit">
        {{ $t('page.login.common.login') }}
      </NButton>
    </div>
  </NForm>
</template>

<style scoped lang="scss">
.pwd-login-form {
  :deep(.n-form-item) {
    margin-bottom: 2px;
  }

  :deep(.n-input) {
    height: 38px;
    font-size: 13px;
  }
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 18px;
  color: #64748b;
  font-size: 13px;
}

.login-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;

  :deep(.n-button) {
    height: 40px;
    font-size: 15px;
    letter-spacing: 0;
  }
}
</style>
