<script setup lang="ts">
import { fetchOrganizationGraph, fetchOrganizationGraphOptions } from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';
import KnowledgeGraphCanvas from '@/views/knowledge-base/modules/knowledge-graph-canvas.vue';

defineOptions({ name: 'OrganizationGraph' });

const loading = ref(false);
const organizations = ref<Api.KnowledgeGraph.OrganizationOption[]>([]);
const selectedOrg = ref<string | null>(null);
const keyword = ref('');
const selectedType = ref<string | null>(null);
const selectedFileIds = ref<number[]>([]);
const graph = ref<Api.KnowledgeGraph.OrganizationGraph | null>(null);
const selectedNode = ref<Api.KnowledgeGraph.GraphNode | null>(null);
const selectedEdge = ref<Api.KnowledgeGraph.OrganizationGraphEdge | null>(null);

const organizationOptions = computed(() =>
  organizations.value.map(item => ({
    label: `${item.name}（${item.publishedDocumentCount} 份已发布）`,
    value: item.tagId
  }))
);
const typeOptions = computed(() =>
  (graph.value?.entityTypes || []).map(type => ({ label: type, value: type }))
);
const documentOptions = computed(() =>
  (graph.value?.documents || []).map(document => ({ label: document.fileName, value: document.id }))
);
const canvasEdges = computed<Api.KnowledgeGraph.GraphEdge[]>(() =>
  (graph.value?.edges || []).map(edge => ({ ...edge, status: 'PUBLISHED' }))
);
const selectedNodeEdges = computed(() => {
  if (!selectedNode.value || !graph.value) return [];
  return graph.value.edges.filter(
    edge => edge.source === selectedNode.value?.id || edge.target === selectedNode.value?.id
  );
});

async function loadOrganizations() {
  const { data, error } = await fetchOrganizationGraphOptions();
  if (error) return;
  organizations.value = data || [];
  if (!selectedOrg.value || !organizations.value.some(item => item.tagId === selectedOrg.value)) {
    selectedOrg.value = organizations.value[0]?.tagId || null;
  }
}

async function loadGraph() {
  if (!selectedOrg.value) {
    graph.value = null;
    return;
  }
  loading.value = true;
  selectedNode.value = null;
  selectedEdge.value = null;
  const { data, error } = await fetchOrganizationGraph(selectedOrg.value, {
    query: keyword.value.trim() || undefined,
    entityType: selectedType.value || undefined,
    fileIds: selectedFileIds.value.length > 0 ? selectedFileIds.value : undefined,
    limit: 500
  });
  if (!error) graph.value = data;
  loading.value = false;
}

function resetFilters() {
  keyword.value = '';
  selectedType.value = null;
  selectedFileIds.value = [];
  loadGraph();
}

function handleNodeSelect(node: Api.KnowledgeGraph.GraphNode) {
  selectedNode.value = node;
  selectedEdge.value = null;
}

function handleEdgeSelect(edge: Api.KnowledgeGraph.GraphEdge) {
  selectedEdge.value = graph.value?.edges.find(item => item.id === edge.id) || null;
  selectedNode.value = null;
}

function clearSelection() {
  selectedNode.value = null;
  selectedEdge.value = null;
}

watch(selectedOrg, async (value, previous) => {
  if (!value || value === previous) return;
  keyword.value = '';
  selectedType.value = null;
  selectedFileIds.value = [];
  await loadGraph();
});

onMounted(async () => {
  await loadOrganizations();
  if (selectedOrg.value) await loadGraph();
});
</script>

<template>
  <div class="organization-graph-page">
    <header class="page-heading">
      <div>
        <h1>组织知识图谱</h1>
        <p>汇聚组织内已发布文档的实体关系，并保留每条关系的原文证据。</p>
      </div>
      <NButton secondary :loading="loading" @click="loadGraph">
        <template #icon><SvgIcon icon="mdi:refresh" /></template>
        刷新
      </NButton>
    </header>

    <div class="filter-rail">
      <NSelect
        v-model:value="selectedOrg"
        :options="organizationOptions"
        placeholder="选择组织"
        class="filter-control organization-select"
      />
      <NInput
        v-model:value="keyword"
        clearable
        placeholder="搜索实体或关系"
        class="filter-control search-input"
        @keyup.enter="loadGraph"
      >
        <template #prefix><SvgIcon icon="mdi:magnify" /></template>
      </NInput>
      <NSelect
        v-model:value="selectedType"
        clearable
        :options="typeOptions"
        placeholder="实体类型"
        class="filter-control"
      />
      <NSelect
        v-model:value="selectedFileIds"
        multiple
        clearable
        max-tag-count="responsive"
        :options="documentOptions"
        placeholder="来源文档"
        class="filter-control source-select"
      />
      <NButton type="primary" :loading="loading" @click="loadGraph">查询</NButton>
      <NButton quaternary @click="resetFilters">重置</NButton>
    </div>

    <NAlert v-if="graph && !graph.neo4jEnabled" type="warning" class="mb-14px">
      Neo4j 服务当前不可用，组织知识图谱暂时无法查询。
    </NAlert>
    <NAlert v-else-if="graph?.truncated" type="info" class="mb-14px">
      当前结果较多，仅展示前 500 条关系。可通过关键词、实体类型或来源文档缩小范围。
    </NAlert>

    <div v-if="graph" class="stat-strip">
      <div class="stat-item">
        <span class="stat-icon entity"><SvgIcon icon="solar:share-circle-line-duotone" /></span>
        <div><small>实体</small><strong>{{ graph.stats.entityCount }}</strong></div>
      </div>
      <div class="stat-item">
        <span class="stat-icon relation"><SvgIcon icon="solar:link-circle-line-duotone" /></span>
        <div><small>关系</small><strong>{{ graph.stats.relationCount }}</strong></div>
      </div>
      <div class="stat-item">
        <span class="stat-icon document"><SvgIcon icon="solar:documents-line-duotone" /></span>
        <div><small>来源文档</small><strong>{{ graph.stats.documentCount }}</strong></div>
      </div>
      <div class="stat-context">
        当前范围：{{ graph.orgName }}
      </div>
    </div>

    <NSpin :show="loading">
      <div v-if="graph" class="workspace-grid">
        <section class="canvas-panel">
          <KnowledgeGraphCanvas
            :nodes="graph.nodes"
            :edges="canvasEdges"
            :show-inspector="false"
            @node-select="handleNodeSelect"
            @edge-select="handleEdgeSelect"
            @selection-clear="clearSelection"
          />
        </section>

        <aside class="detail-panel">
          <template v-if="selectedEdge">
            <div class="detail-kicker">关系详情</div>
            <h2>{{ selectedEdge.predicate }}</h2>
            <NText depth="3">
              置信度 {{ Math.round(selectedEdge.confidence * 100) }}%
            </NText>
            <NDivider />
            <div class="evidence-route">
              <span>{{ graph.nodes.find(node => node.id === selectedEdge?.source)?.name }}</span>
              <SvgIcon icon="mdi:arrow-right" />
              <span>{{ graph.nodes.find(node => node.id === selectedEdge?.target)?.name }}</span>
            </div>
            <h3>关系证据</h3>
            <p class="evidence-text">{{ selectedEdge.evidenceText }}</p>
            <div class="source-box">
              <SvgIcon icon="solar:document-text-line-duotone" />
              <div>
                <strong>{{ selectedEdge.fileName }}</strong>
                <small>切片 {{ selectedEdge.evidenceChunkId }}</small>
              </div>
            </div>
          </template>

          <template v-else-if="selectedNode">
            <div class="detail-kicker">实体详情</div>
            <NTag size="small" type="info">{{ selectedNode.type }}</NTag>
            <h2>{{ selectedNode.name }}</h2>
            <NText depth="3">连接了 {{ selectedNode.degree }} 条关系</NText>
            <NDivider />
            <h3>连接关系</h3>
            <div class="relation-list">
              <button
                v-for="edge in selectedNodeEdges"
                :key="edge.id"
                type="button"
                class="relation-row"
                @click="selectedEdge = edge; selectedNode = null"
              >
                <span>{{ edge.predicate }}</span>
                <small>{{ edge.fileName }}</small>
              </button>
            </div>
          </template>

          <div v-else class="detail-empty">
            <SvgIcon icon="solar:cursor-square-line-duotone" />
            <h3>选择实体或关系</h3>
            <p>点击画布中的节点查看连接关系，点击连线查看文档证据。</p>
          </div>
        </aside>
      </div>
      <NEmpty v-else-if="organizations.length === 0" description="暂无可查看的组织文档图谱" class="empty-page" />
    </NSpin>
  </div>
</template>

<style scoped lang="scss">
.organization-graph-page {
  min-height: 100%;
  background: #fff;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.page-heading h1 {
  margin: 0;
  color: #172033;
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.page-heading p {
  margin: 5px 0 0;
  color: #697386;
  font-size: 13px;
}

.filter-rail {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 0;
  background: #fff;
}

.filter-control { width: 168px; }
.organization-select { width: 250px; }
.search-input { width: min(300px, 28vw); }
.source-select { min-width: 210px; flex: 1; }

.stat-strip {
  display: flex;
  min-height: 78px;
  align-items: center;
  margin-bottom: 14px;
  border: 1px solid #e3e8ef;
  border-radius: 5px;
  background: #fff;
}

.stat-item {
  display: flex;
  min-width: 190px;
  align-items: center;
  gap: 12px;
  padding: 0 26px;
  border-right: 1px solid #edf0f4;
}

.stat-item small, .source-box small { display: block; color: #788397; font-size: 12px; }
.stat-item strong { display: block; margin-top: 2px; color: #172033; font-size: 23px; font-weight: 650; }
.stat-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 50%; font-size: 21px; }
.stat-icon.entity { color: #245bdb; background: #eef3ff; }
.stat-icon.relation { color: #5558c9; background: #ececfc; }
.stat-icon.document { color: #277eb6; background: #e8f3fa; }
.stat-context { margin-left: auto; padding: 0 24px; color: #697386; font-size: 13px; }

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
}

.canvas-panel, .detail-panel {
  min-width: 0;
  border: 1px solid #e3e8ef;
  border-radius: 5px;
  background: #fff;
}

.canvas-panel { overflow: hidden; }
.canvas-panel :deep(.graph-frame) { border: 0; border-radius: 0; box-shadow: none; }
.canvas-panel :deep(.graph-canvas), .canvas-panel :deep(.graph-empty) { height: clamp(420px, calc(100vh - 450px), 640px); }

.detail-panel {
  min-height: 520px;
  padding: 20px;
}

.detail-kicker { margin-bottom: 12px; color: #768196; font-size: 12px; font-weight: 600; }
.detail-panel h2 { margin: 8px 0 4px; color: #182235; font-size: 20px; }
.detail-panel h3 { margin: 18px 0 9px; color: #263247; font-size: 14px; }
.evidence-route { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 8px; color: #354158; font-size: 13px; }
.evidence-route span:last-child { text-align: right; }
.evidence-text { margin: 0; padding: 12px; border-left: 3px solid #245bdb; background: #f5f7ff; color: #47546a; font-size: 13px; line-height: 1.7; }
.source-box { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding: 11px; border: 1px solid #e7ebf0; border-radius: 4px; color: #2680b8; }
.source-box strong { display: block; overflow: hidden; color: #354158; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }

.relation-list { display: flex; flex-direction: column; gap: 7px; }
.relation-row { width: 100%; padding: 10px; border: 1px solid #e8ecf1; border-radius: 4px; background: #fff; text-align: left; cursor: pointer; }
.relation-row:hover { border-color: #9cb6ee; background: #f4f7ff; }
.relation-row span, .relation-row small { display: block; }
.relation-row span { color: #334056; font-size: 13px; }
.relation-row small { margin-top: 3px; overflow: hidden; color: #8490a3; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.detail-empty { display: flex; height: 100%; min-height: 460px; align-items: center; justify-content: center; flex-direction: column; color: #9aa4b4; text-align: center; }
.detail-empty > :first-child { font-size: 38px; }
.detail-empty h3 { margin: 14px 0 5px; }
.detail-empty p { max-width: 230px; margin: 0; font-size: 12px; line-height: 1.7; }
.empty-page { min-height: 440px; }

:global(html.dark) .organization-graph-page { background: #10151f; }
:global(html.dark) .page-heading h1, :global(html.dark) .stat-item strong,
:global(html.dark) .detail-panel h2, :global(html.dark) .detail-panel h3 { color: #edf2f7; }
:global(html.dark) .filter-rail, :global(html.dark) .stat-strip,
:global(html.dark) .canvas-panel, :global(html.dark) .detail-panel { border-color: #2d3748; background: #171e2a; }
:global(html.dark) .stat-item { border-color: #2d3748; }
:global(html.dark) .relation-row { border-color: #303b4b; background: #1b2431; }

@media (max-width: 1100px) {
  .filter-rail { flex-wrap: wrap; }
  .source-select { flex: 1 1 280px; }
  .workspace-grid { grid-template-columns: minmax(0, 1fr); }
  .detail-panel { min-height: auto; }
  .detail-empty { min-height: 180px; }
}

@media (max-width: 700px) {
  .filter-control, .organization-select, .search-input, .source-select { width: 100%; flex: 1 1 100%; }
  .stat-strip { align-items: stretch; flex-wrap: wrap; }
  .stat-item { min-width: 33.333%; flex: 1; padding: 14px; }
  .stat-context { width: 100%; margin: 0; padding: 10px 14px; }
  .canvas-panel :deep(.graph-canvas), .canvas-panel :deep(.graph-empty) { height: 500px; }
}
</style>
