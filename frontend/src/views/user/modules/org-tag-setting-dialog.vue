<script setup lang="ts">
import type { FormRules } from 'naive-ui';

defineOptions({
  name: 'OrgTagSettingDialog'
});

const props = defineProps<{
  rowData: Api.User.Item;
}>();

const emit = defineEmits<{ submitted: [] }>();

const visible = defineModel<boolean>('visible', { default: false });
const authStore = useAuthStore();
const loading = ref(false);
const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

type Model = {
  orgTags: string[];
  reason: string;
  currentPassword: string;
};

const model = ref<Model>(createDefaultModel());

function createDefaultModel(): Model {
  return {
    orgTags: [],
    reason: '',
    currentPassword: ''
  };
}

const rules = ref<FormRules>({
  orgTags: defaultRequiredRule,
  reason: defaultRequiredRule
});

const privateOrgTag = ref<string[]>([]);
async function handleUpdateModelWhenEdit() {
  model.value = createDefaultModel();
  model.value.orgTags = props.rowData.orgTags.map(tag => tag.tagId!);
  // 备份默认的私人组织标签，防止被误删
  privateOrgTag.value = props.rowData.orgTags.filter(tag => tag.tagId!.startsWith('PRIVATE_')).map(tag => tag.tagId!);
}

function close() {
  visible.value = false;
}

async function handleSubmit() {
  await validate();
  loading.value = true;
  model.value.orgTags = Array.from(new Set([...model.value.orgTags, ...privateOrgTag.value]));
  const res = await request({
    method: 'PUT',
    url: `/admin/organization-management/users/${props.rowData.userId}/memberships`,
    data: model.value
  });
  if (!res.error) {
    window.$message?.success('操作成功');
    close();
    emit('submitted');
  }
  loading.value = false;
}

watch(visible, () => {
  if (visible.value) {
    handleUpdateModelWhenEdit();
    restoreValidation();
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="组织成员设置"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem label="用户">
        <NInput :value="`${rowData.displayName || rowData.username}（${rowData.username}）`" readonly />
      </NFormItem>
      <NFormItem label="所属组织" path="orgTags">
        <OrgTagCascader v-model:value="model.orgTags" multiple exclude-private :exclude-admin="!authStore.isSuperAdmin" />
      </NFormItem>
      <NFormItem label="变更原因" path="reason">
        <NInput v-model:value="model.reason" type="textarea" maxlength="200" show-count placeholder="请输入本次变更原因" />
      </NFormItem>
      <NFormItem v-if="rowData.role !== 'USER' || model.orgTags.includes('admin')" label="当前密码" path="currentPassword">
        <NInput v-model:value="model.currentPassword" type="password" show-password-on="click" placeholder="管理员权限变更需要验证身份" />
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" @click="handleSubmit">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
