<script setup lang="ts">
import { fetchOrganizationGraph, fetchOrganizationGraphOptions } from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';
import KnowledgeGraphCanvas from '@/views/knowledge-base/modules/knowledge-graph-canvas.vue';
import GraphPromptTemplateCard from './modules/graph-prompt-template-card.vue';

defineOptions({ name: 'OrganizationGraph' });

const loading = ref(false);
const activeSection = ref<'graph' | 'templates'>('graph');
const organizations = ref<Api.KnowledgeGraph.OrganizationOption[]>([]);
const selectedOrg = ref<string | null>(null);
const keyword = ref('');
const selectedType = ref<string | null>(null);
const selectedFileIds = ref<number[]>([]);
const graph = ref<Api.KnowledgeGraph.OrganizationGraph | null>(null);
const selectedNode = ref<Api.KnowledgeGraph.GraphNode | null>(null);
const selectedEdge = ref<Api.KnowledgeGraph.OrganizationGraphEdge | null>(null);
const workspacePanel = ref<HTMLElement | null>(null);
const detailPanel = ref<HTMLElement | null>(null);
let nodeDetailScrollTop = 0;

const organizationOptions = computed(() =>
  organizations.value.map(item => ({
    label: `${item.name} · ${scopeTypeLabel(item.scopeType)}（${item.publishedDocumentCount} 份已发布）`,
    value: item.scopeId
  }))
);

function scopeTypeLabel(scopeType: Api.KnowledgeGraph.OrganizationOption['scopeType']) {
  if (scopeType === 'PUBLIC') return '公开图谱';
  if (scopeType === 'PRIVATE') return '私人图谱';
  return '组织内图谱';
}
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
    limit: 1000
  });
  if (!error) graph.value = data;
  loading.value = false;
}

async function refreshPage() {
  const previousScope = selectedOrg.value;
  await loadOrganizations();
  if (selectedOrg.value && selectedOrg.value === previousScope) await loadGraph();
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

async function showRelationDetail(edge: Api.KnowledgeGraph.OrganizationGraphEdge) {
  nodeDetailScrollTop = detailPanel.value?.scrollTop || 0;
  selectedEdge.value = edge;
  await nextTick();
  if (detailPanel.value) detailPanel.value.scrollTop = 0;
}

async function returnToNodeDetail() {
  selectedEdge.value = null;
  await nextTick();
  if (detailPanel.value) detailPanel.value.scrollTop = nodeDetailScrollTop;
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
  await refreshPage();
});
</script>

<template>
  <div class="organization-graph-page">
    <header class="page-heading">
      <div>
        <h1>组织知识图谱</h1>
        <p v-if="activeSection === 'graph'">在权限隔离下保留各文档事实与证据，并通过规范化实体扩展跨文档关系。</p>
        <p v-else>按文档类型维护领域抽取要求，统一控制进入组织图谱的知识质量。</p>
      </div>
      <NButton v-if="activeSection === 'graph'" secondary :loading="loading" @click="refreshPage">
        <template #icon><SvgIcon icon="mdi:refresh" /></template>
        刷新
      </NButton>
    </header>

    <NTabs v-model:value="activeSection" type="line" animated class="section-tabs">
      <NTab name="graph">图谱浏览</NTab>
      <NTab name="templates">抽取模板</NTab>
    </NTabs>

    <div v-if="activeSection === 'graph'" class="graph-section">

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
        <div>
          <small>关系</small><strong>{{ graph.stats.relationCount }}</strong>
          <em v-if="graph.stats.disputedRelationCount > 0">{{ graph.stats.disputedRelationCount }} 条存在不同陈述</em>
          <em v-if="graph.stats.crossDocumentRelationCount > 0" class="cross-document-stat">
            {{ graph.stats.crossDocumentRelationCount }} 条由多文档共同支持
          </em>
        </div>
      </div>
      <div class="stat-item">
        <span class="stat-icon document"><SvgIcon icon="solar:documents-line-duotone" /></span>
        <div><small>来源文档</small><strong>{{ graph.stats.documentCount }}</strong></div>
      </div>
      <div class="stat-context">
        当前范围：{{ graph.orgName }} · {{ scopeTypeLabel(graph.scopeType) }} · {{ graph.communities?.length || 0 }} 个知识社区
      </div>
    </div>

    <NSpin :show="loading" class="graph-spinner">
      <div v-if="graph" ref="workspacePanel" class="workspace-grid">
        <section class="canvas-panel">
          <KnowledgeGraphCanvas
            :nodes="graph.nodes"
            :edges="canvasEdges"
            :show-inspector="false"
            layout-mode="organization"
            :fullscreen-target="workspacePanel"
            @node-select="handleNodeSelect"
            @edge-select="handleEdgeSelect"
            @selection-clear="clearSelection"
          />
        </section>

        <aside
          ref="detailPanel"
          class="detail-panel"
          :class="{ 'detail-panel--empty': !selectedNode && !selectedEdge }"
        >
          <template v-if="selectedEdge">
            <div class="detail-heading">
              <NButton
                v-if="selectedNode"
                quaternary
                circle
                size="tiny"
                class="detail-back"
                aria-label="返回实体详情"
                title="返回实体详情"
                @click="returnToNodeDetail"
              >
                <template #icon><SvgIcon icon="mdi:chevron-left" /></template>
              </NButton>
              <div class="detail-kicker">关系详情</div>
            </div>
            <h2>{{ selectedEdge.predicate }}</h2>
            <NTag v-if="selectedEdge.crossDocument" size="small" type="success">跨文档共同事实</NTag>
            <NText depth="3">
              最高置信度 {{ Math.round(selectedEdge.confidence * 100) }}%
              · {{ selectedEdge.documentCount }} 份文档的 {{ selectedEdge.supportCount }} 条证据
            </NText>
            <NDivider />
            <div class="evidence-route">
              <div class="route-entity">
                <small>起点</small>
                <strong>{{ graph.nodes.find(node => node.id === selectedEdge?.source)?.name }}</strong>
              </div>
              <span class="route-arrow"><SvgIcon icon="mdi:arrow-right" /></span>
              <div class="route-entity">
                <small>终点</small>
                <strong>{{ graph.nodes.find(node => node.id === selectedEdge?.target)?.name }}</strong>
              </div>
            </div>
            <NAlert v-if="selectedEdge.disputed" type="warning" class="mt-14px">
              存在不同陈述：其他文档对同一实体和关系给出了不同对象，请结合来源和时间判断。
            </NAlert>
            <h3>来源证据</h3>
            <div class="evidence-list">
              <article v-for="evidence in selectedEdge.evidences" :key="evidence.claimId" class="evidence-card">
                <p class="evidence-text">{{ evidence.evidenceText }}</p>
                <div class="source-box">
                  <SvgIcon icon="solar:document-text-line-duotone" />
                  <div>
                    <strong :title="evidence.fileName">{{ evidence.fileName }}</strong>
                    <small>切片 {{ evidence.chunkId }} · 置信度 {{ Math.round(evidence.confidence * 100) }}%</small>
                  </div>
                </div>
              </article>
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
                @click="showRelationDetail(edge)"
              >
                <span>{{ edge.predicate }}</span>
                <small>{{ edge.crossDocument ? `${edge.documentCount} 份文档共同支持` : edge.fileName }}</small>
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

    <GraphPromptTemplateCard v-else />
  </div>
</template>

<style scoped lang="scss">
.organization-graph-page {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.section-tabs { margin: -4px 0 16px; }

.graph-section {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}

.graph-spinner {
  min-height: 0;
  flex: 1;
}

.graph-spinner :deep(.n-spin-content) {
  height: 100%;
  min-height: 0;
}

.page-heading h1 {
  margin: 0;
  color: #172033;
  font-size: 16px;
  font-weight: 650;
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
.stat-item em { display: block; color: #d97706; font-size: 11px; font-style: normal; }
.stat-item em.cross-document-stat { color: #0f8c72; }
.stat-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 50%; font-size: 21px; }
.stat-icon.entity { color: #245bdb; background: #eef3ff; }
.stat-icon.relation { color: #5558c9; background: #ececfc; }
.stat-icon.document { color: #277eb6; background: #e8f3fa; }
.stat-context { margin-left: auto; padding: 0 24px; color: #697386; font-size: 13px; }

.workspace-grid {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-columns: minmax(0, 1fr) clamp(360px, 24vw, 420px);
  gap: 14px;
  align-items: stretch;
}

.workspace-grid:fullscreen {
  display: block;
  padding: 20px;
  background: #f8fafc;
}

.workspace-grid:fullscreen .canvas-panel { height: 100%; }
.workspace-grid:fullscreen .detail-panel {
  position: absolute;
  z-index: 2;
  top: 112px;
  right: 32px;
  width: min(380px, calc(100vw - 64px));
  height: auto;
  max-height: calc(100vh - 144px);
  border-color: rgb(148 163 184 / 24%);
  box-shadow: 0 12px 32px rgb(15 23 42 / 16%);
}
.workspace-grid:fullscreen .detail-panel--empty { display: none; }

.canvas-panel, .detail-panel {
  min-width: 0;
  border: 1px solid #e3e8ef;
  border-radius: 5px;
  background: #fff;
}

.canvas-panel { height: 100%; overflow: hidden; }
.canvas-panel :deep(.graph-frame) { display: flex; flex-direction: column; border: 0; border-radius: 0; box-shadow: none; }
.canvas-panel :deep(.graph-content) { min-height: 0; flex: 1; }
.canvas-panel :deep(.graph-frame),
.canvas-panel :deep(.graph-canvas),
.canvas-panel :deep(.graph-empty) { height: 100%; min-height: 0; }

.detail-panel {
  height: 100%;
  min-height: 0;
  padding: 22px;
  overflow-x: hidden;
  overflow-y: auto;
}

.detail-heading { display: flex; align-items: center; gap: 4px; margin: -2px 0 12px; }
.detail-heading .detail-kicker { margin: 0; }
.detail-back { margin-left: -6px; color: #687386; }
.detail-kicker { margin-bottom: 12px; color: #768196; font-size: 12px; font-weight: 600; }
.detail-panel h2 { margin: 8px 0 4px; color: #182235; font-size: 20px; }
.detail-panel h3 { margin: 18px 0 9px; color: #263247; font-size: 14px; }
.evidence-route { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); align-items: stretch; gap: 10px; }
.route-entity { min-width: 0; padding: 10px 12px; border: 1px solid #e7ebf0; border-radius: 5px; background: #fafbfd; }
.route-entity small { display: block; margin-bottom: 4px; color: #8a95a7; font-size: 11px; }
.route-entity strong { display: block; color: #354158; font-size: 13px; font-weight: 500; line-height: 1.5; overflow-wrap: anywhere; }
.route-arrow { display: grid; place-items: center; color: #718096; }
.evidence-text { margin: 0; padding: 12px; border-left: 3px solid #245bdb; background: #f5f7ff; color: #47546a; font-size: 13px; line-height: 1.7; }
.evidence-list { display: flex; flex-direction: column; gap: 12px; }
.evidence-card { min-width: 0; }
.source-box { display: flex; align-items: center; gap: 10px; margin-top: 12px; padding: 11px; border: 1px solid #e7ebf0; border-radius: 4px; color: #2680b8; }
.source-box > :first-child { flex: 0 0 auto; }
.source-box > div { min-width: 0; flex: 1; }
.source-box strong { display: block; max-width: 100%; overflow: hidden; color: #354158; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }

.relation-list { display: flex; flex-direction: column; gap: 7px; }
.relation-row { width: 100%; padding: 10px; border: 1px solid #e8ecf1; border-radius: 4px; background: #fff; text-align: left; cursor: pointer; }
.relation-row:hover { border-color: #9cb6ee; background: #f4f7ff; }
.relation-row span, .relation-row small { display: block; }
.relation-row span { color: #334056; font-size: 13px; }
.relation-row small { margin-top: 3px; overflow: hidden; color: #8490a3; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.detail-empty { display: flex; height: 100%; min-height: 0; align-items: center; justify-content: center; flex-direction: column; color: #9aa4b4; text-align: center; }
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
:global(html.dark) .route-entity { border-color: #303b4b; background: #1b2431; }
:global(html.dark) .route-entity strong { color: #dce3ec; }
:global(html.dark) .workspace-grid:fullscreen { background: #111827; }

@media (max-width: 1100px) {
  .organization-graph-page { height: auto; min-height: 100%; overflow: visible; }
  .filter-rail { flex-wrap: wrap; }
  .source-select { flex: 1 1 280px; }
  .graph-section, .graph-spinner { flex: none; }
  .graph-spinner :deep(.n-spin-content) { height: auto; }
  .workspace-grid { height: auto; grid-template-columns: minmax(0, 1fr); }
  .canvas-panel { height: 500px; }
  .detail-panel { height: auto; min-height: auto; overflow-y: visible; }
  .detail-empty { min-height: 180px; }
}

@media (max-width: 700px) {
  .filter-control, .organization-select, .search-input, .source-select { width: 100%; flex: 1 1 100%; }
  .stat-strip { align-items: stretch; flex-wrap: wrap; }
  .stat-item { min-width: 33.333%; flex: 1; padding: 14px; }
  .stat-context { width: 100%; margin: 0; padding: 10px 14px; }
  .canvas-panel { height: 500px; }
}
</style>
