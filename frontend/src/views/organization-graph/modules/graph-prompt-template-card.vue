<script setup lang="tsx">
import type { DataTableColumns } from 'naive-ui';
import { NButton, NPopconfirm, NSpace, NTag } from 'naive-ui';
import {
  createGraphPromptTemplate,
  deleteGraphPromptTemplate,
  fetchGraphPromptTemplates,
  updateGraphPromptTemplate
} from '@/service/api';

const loading = ref(false);
const saving = ref(false);
const visible = ref(false);
const editingId = ref<number | null>(null);
const templates = ref<Api.GraphPromptTemplate.Item[]>([]);
const form = ref<Api.GraphPromptTemplate.Request>(emptyForm());
const canCreate = computed(() => templates.value.some(item => item.editable));

function emptyForm(): Api.GraphPromptTemplate.Request {
  return { name: '', documentType: 'TECHNICAL', description: '', instructions: '', enabled: true, defaultTemplate: false };
}

const columns: DataTableColumns<Api.GraphPromptTemplate.Item> = [
  { key: 'name', title: '模板', minWidth: 150 },
  { key: 'documentType', title: '文档类型', width: 110 },
  { key: 'description', title: '适用场景', minWidth: 220, ellipsis: { tooltip: true } },
  {
    key: 'status', title: '状态', width: 145,
    render: row => <NSpace size={6}>
      <NTag type={row.enabled ? 'success' : 'warning'}>{row.enabled ? '启用' : '停用'}</NTag>
      {row.defaultTemplate ? <NTag type="primary">默认</NTag> : null}
    </NSpace>
  },
  {
    key: 'operate', title: '操作', width: 150,
    render: row => row.editable ? <NSpace size={8}>
      <NButton size="small" type="primary" ghost onClick={() => edit(row)}>编辑</NButton>
      {!row.defaultTemplate ? <NPopconfirm onPositiveClick={() => remove(row.id)}>
        {{ trigger: () => <NButton size="small" type="error" ghost>删除</NButton>, default: () => '确认删除该模板吗？' }}
      </NPopconfirm> : null}
    </NSpace> : null
  }
];

async function load() {
  loading.value = true;
  const { data, error } = await fetchGraphPromptTemplates();
  if (!error) templates.value = data || [];
  loading.value = false;
}
function create() { editingId.value = null; form.value = emptyForm(); visible.value = true; }
function edit(row: Api.GraphPromptTemplate.Item) {
  editingId.value = row.id;
  form.value = { name: row.name, documentType: row.documentType, description: row.description,
    instructions: row.instructions, enabled: row.enabled, defaultTemplate: row.defaultTemplate };
  visible.value = true;
}
async function save() {
  if (!form.value.name.trim() || !form.value.instructions.trim()) {
    window.$message?.warning('请填写模板名称和抽取要求'); return;
  }
  saving.value = true;
  const result = editingId.value
    ? await updateGraphPromptTemplate(editingId.value, form.value)
    : await createGraphPromptTemplate(form.value);
  if (!result.error) { window.$message?.success('抽取模板已保存'); visible.value = false; await load(); }
  saving.value = false;
}
async function remove(id: number) {
  const { error } = await deleteGraphPromptTemplate(id);
  if (!error) { window.$message?.success('抽取模板已删除'); await load(); }
}
onMounted(load);
</script>

<template>
  <NCard title="图谱抽取模板" :bordered="true" size="small" class="template-card">
    <template #header-extra>
      <NButton v-if="canCreate" type="primary" size="small" @click="create">新增模板</NButton>
    </template>
    <NAlert type="info" class="mb-12px">
      模板用于告诉 AI 这类文档应重点提取哪些知识、忽略哪些内容。实体命名、原文证据和质量过滤等基础规则由系统自动处理，无需在模板中重复配置。
    </NAlert>
    <NDataTable :columns="columns" :data="templates" :loading="loading" :row-key="row => row.id" :scroll-x="800" size="small" />
  </NCard>

  <NModal v-model:show="visible" preset="card" :title="editingId ? '编辑抽取模板' : '新增抽取模板'" class="max-w-760px">
    <NForm label-placement="top">
      <div class="grid grid-cols-1 gap-x-16px md:grid-cols-2">
        <NFormItem label="模板名称" required><NInput v-model:value="form.name" placeholder="例如：研发技术方案" /></NFormItem>
        <NFormItem label="文档类型">
          <NSelect v-model:value="form.documentType" :options="[
            { label: '通用技术文档', value: 'TECHNICAL' }, { label: '学术论文', value: 'ACADEMIC' },
            { label: '业务制度', value: 'POLICY' }, { label: '其他', value: 'OTHER' }
          ]" />
        </NFormItem>
      </div>
      <NFormItem label="适用场景"><NInput v-model:value="form.description" placeholder="简要说明用户何时选择该模板" /></NFormItem>
      <NFormItem label="领域抽取要求" required>
        <NInput v-model:value="form.instructions" type="textarea" :autosize="{ minRows: 7, maxRows: 15 }"
          placeholder="说明优先抽取哪些领域事实，以及应忽略哪些内容" />
      </NFormItem>
      <NSpace>
        <NCheckbox v-model:checked="form.enabled">启用</NCheckbox>
        <NCheckbox v-model:checked="form.defaultTemplate">设为默认模板</NCheckbox>
      </NSpace>
    </NForm>
    <template #footer><div class="flex justify-end gap-12px">
      <NButton @click="visible = false">取消</NButton><NButton type="primary" :loading="saving" @click="save">保存</NButton>
    </div></template>
  </NModal>
</template>

<style scoped>
.template-card { margin-top: 2px; }
</style>
