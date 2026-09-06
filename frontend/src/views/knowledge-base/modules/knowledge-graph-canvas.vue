<script setup lang="ts">
import { CanvasEvent, EdgeEvent, Graph, NodeEvent } from '@antv/g6';
import type { GraphData, IElementEvent } from '@antv/g6';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { layoutDocumentGraph } from './document-graph-layout';

defineOptions({ name: 'KnowledgeGraphCanvas' });

const props = withDefaults(
  defineProps<{
    nodes: Api.KnowledgeGraph.GraphNode[];
    edges: Api.KnowledgeGraph.GraphEdge[];
    document?: { id: string; name: string };
    showInspector?: boolean;
    fullscreenTarget?: HTMLElement | null;
  }>(),
  { showInspector: true }
);
const emit = defineEmits<{
  nodeSelect: [node: Api.KnowledgeGraph.GraphNode];
  edgeSelect: [edge: Api.KnowledgeGraph.GraphEdge];
  selectionClear: [];
}>();

const themeStore = useThemeStore();
const containerRef = ref<HTMLElement | null>(null);
const frameRef = ref<HTMLElement | null>(null);
const keyword = ref('');
const selectedType = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);
const selectedNodeId = ref<string | null>(null);
const rendering = ref(false);
const isFullscreen = ref(false);
let graph: Graph | null = null;
let resizeFrame = 0;
const canvasResizeObserver = new ResizeObserver(() => {
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(() => {
    if (props.document && graph && !rendering.value) void fitView(false);
  });
});
watch(containerRef, element => {
  canvasResizeObserver.disconnect();
  if (element) canvasResizeObserver.observe(element);
});

const typeColors: Record<string, string> = {
  PERSON: '#8b6fb3',
  人员: '#5558c9',
  人物: '#5558c9',
  ORGANIZATION: '#5875a8',
  组织: '#245bdb',
  机构: '#245bdb',
  SYSTEM: '#438899',
  SERVICE: '#4d917d',
  METHOD: '#b97a58',
  MODEL: '#a96d86',
  DATASET: '#6f9565',
  数据: '#6f9565',
  EVENT: '#b96868',
  LOCATION: '#6d7eaa',
  地点: '#6d7eaa',
  CONCEPT: '#7669ad',
  TECHNOLOGY: '#647b8f',
  项目: '#2787bd',
  文档: '#2787bd',
  OTHER: '#7b8794'
};

const typeOptions = computed(() => {
  const types = [...new Set(props.nodes.map(node => node.type))].sort();
  return types.map(type => ({ label: type, value: type }));
});

const visibleData = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase();
  const documentMatched = Boolean(
    normalizedKeyword && props.document?.name.toLocaleLowerCase().includes(normalizedKeyword)
  );
  const directlyMatchedIds = new Set(
    props.nodes
      .filter(node => !selectedType.value || node.type === selectedType.value)
      .filter(
        node => documentMatched || !normalizedKeyword || node.name.toLocaleLowerCase().includes(normalizedKeyword)
      )
      .map(node => node.id)
  );
  const visibleIds = new Set(directlyMatchedIds);
  if (normalizedKeyword) {
    props.edges.forEach(edge => {
      if (directlyMatchedIds.has(edge.source) || directlyMatchedIds.has(edge.target)) {
        visibleIds.add(edge.source);
        visibleIds.add(edge.target);
      }
    });
  }

  const nodes = props.nodes.filter(node => visibleIds.has(node.id));
  const edges = props.edges.filter(edge => visibleIds.has(edge.source) && visibleIds.has(edge.target));
  return { nodes, edges };
});

const documentNode = computed(() => props.document ? {
  id: `__document:${props.document.id}`, name: props.document.name, type: '文档', degree: visibleData.value.nodes.length
} : null);

const selectedEdge = computed(() => props.edges.find(edge => edge.id === selectedEdgeId.value) || null);
const selectedNode = computed(() => [...props.nodes, ...(documentNode.value ? [documentNode.value] : [])].find(node => node.id === selectedNodeId.value) || null);

function edgeCurveOffset(edgeId: string) {
  const hash = Array.from(edgeId).reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 0);
  return (hash % 2 === 0 ? 1 : -1) * (28 + hash % 3 * 8);
}

function graphData(): GraphData {
  const nodes = visibleData.value.nodes;
  const edges = visibleData.value.edges;
  if (documentNode.value) {
    const layout = layoutDocumentGraph(nodes, edges, documentNode.value.name);
    return {
      nodes: [
        { id: documentNode.value.id, data: { ...documentNode.value, documentRoot: true, displayLabel: layout.centerLabel },
          style: { x: 0, y: 0 } },
        ...nodes.map(node => {
          const position = layout.positions.get(node.id)!;
          return { id: node.id, data: { ...node, core: position.core, displayLabel: position.label },
            style: { x: position.x, y: position.y } };
        })
      ],
      edges: [
        ...edges.map(edge => {
          const sourcePosition = layout.positions.get(edge.source);
          const targetPosition = layout.positions.get(edge.target);
          const layoutTree = sourcePosition?.parent === edge.target || targetPosition?.parent === edge.source;
          return { id: edge.id, source: edge.source, target: edge.target,
            data: { ...edge, layoutTree, layoutCurveOffset: layoutTree ? 0 : edgeCurveOffset(edge.id) } };
        }),
        ...layout.roots.map(id => ({ id: `__document-link:${id}`, source: documentNode.value!.id,
          target: id, data: { documentLink: true, traversable: true,
            relationKind: 'DOCUMENT_PROVENANCE', predicate: '文档声明', navigationOnly: true } }))
      ]
    };
  }
  const adjacency = new Map(nodes.map(node => [node.id, new Set<string>()]));

  edges.forEach(edge => {
    adjacency.get(edge.source)?.add(edge.target);
    adjacency.get(edge.target)?.add(edge.source);
  });

  const unvisited = new Set(nodes.map(node => node.id));
  const components: string[][] = [];
  while (unvisited.size > 0) {
    const seed = unvisited.values().next().value as string;
    const component: string[] = [];
    const queue = [seed];
    unvisited.delete(seed);
    while (queue.length > 0) {
      const nodeId = queue.shift()!;
      component.push(nodeId);
      adjacency.get(nodeId)?.forEach(neighborId => {
        if (!unvisited.delete(neighborId)) return;
        queue.push(neighborId);
      });
    }
    components.push(component);
  }

  components.sort((left, right) => right.length - left.length);
  const anchorOf = (component: string[]) =>
    [...component].sort((left, right) => (adjacency.get(right)?.size || 0) - (adjacency.get(left)?.size || 0))[0];
  const primaryAnchor = components[0] ? anchorOf(components[0]) : null;
  const layoutEdges = primaryAnchor
    ? components.slice(1).map((component, index) => ({
        id: `__layout-${index}`,
        source: primaryAnchor,
        target: anchorOf(component),
        data: { layoutOnly: true }
      }))
    : [];

  return {
    nodes: nodes.map(node => ({
      id: node.id,
      data: { ...node }
    })),
    edges: [
      ...edges.map(edge => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      data: { ...edge }
      })),
      ...layoutEdges
    ]
  };
}

function nodeColor(type: string) {
  return typeColors[type] || typeColors.OTHER;
}

function nodeStyle(datum: { data?: Record<string, unknown> }) {
  const node = datum.data as unknown as Api.KnowledgeGraph.GraphNode;
  const color = nodeColor(node.type);
  return {
    size: datum.data?.documentRoot ? 76 : Math.min(58, 34 + Math.max(node.degree - 1, 0) * 4),
    fill: color,
    fillOpacity: themeStore.darkMode ? 0.86 : 0.9,
    stroke: themeStore.darkMode ? '#e2e8f0' : '#ffffff',
    lineWidth: 2,
    shadowColor: `${color}55`,
    shadowBlur: 12,
    labelText: String(datum.data?.displayLabel ?? node.name),
    labelPlacement: 'bottom' as const,
    labelOffsetY: 8,
    labelFill: themeStore.darkMode ? '#e5e7eb' : '#334155',
    labelFontSize: 12,
    labelFontWeight: datum.data?.core || datum.data?.documentRoot ? 600 : 500,
    labelWordWrap: !props.document,
    labelMaxWidth: datum.data?.documentRoot ? 300 : datum.data?.core ? 210 : 160,
    labelMaxLines: props.document ? String(datum.data?.displayLabel || node.name).split('\n').length : 1,
    labelLineHeight: 18
  };
}

function edgeStyle(datum: { data?: Record<string, unknown> }) {
  if (datum.data?.documentLink) {
    return { stroke: '#2787bd', opacity: 0.4, lineWidth: 1.5, endArrow: true, endArrowSize: 6, label: false };
  }
  if (datum.data?.layoutOnly) {
    return {
      strokeOpacity: 0,
      opacity: 0,
      endArrow: false,
      label: false
    };
  }
  const edge = datum.data as unknown as Api.KnowledgeGraph.GraphEdge;
  let stroke = themeStore.darkMode ? '#718096' : '#a5b1c1';
  if (edge.crossDocument) stroke = '#0f8c72';
  if (edge.disputed) stroke = '#d97706';
  const supplementary = datum.data?.layoutTree === false;
  let lineWidth = edge.crossDocument ? 2 : supplementary ? 1 : 1.5;
  if (selectedEdgeId.value === edge.id) lineWidth = 3;
  return {
    stroke,
    lineWidth,
    opacity: supplementary ? 0.42 : 0.82,
    curveOffset: Number(datum.data?.layoutCurveOffset || 0),
    lineDash: edge.disputed ? [6, 4] : undefined,
    endArrow: true,
    endArrowSize: 8,
    labelText: supplementary ? undefined : edge.predicate,
    labelFill: themeStore.darkMode ? '#cbd5e1' : '#475569',
    labelFontSize: 11,
    labelBackground: true,
    labelBackgroundFill: themeStore.darkMode ? '#1f2937' : '#ffffff',
    labelBackgroundFillOpacity: 0.9,
    labelPadding: [2, 5],
    labelRadius: 4
  };
}

async function createGraph() {
  if (!containerRef.value || visibleData.value.nodes.length === 0) return;
  graph?.destroy();
  graph = new Graph({
    container: containerRef.value,
    autoResize: true,
    padding: 64,
    autoFit: props.document ? undefined : { type: 'view', options: { when: 'always', direction: 'both' } },
    animation: false,
    data: graphData(),
    layout: documentNode.value ? undefined : {
      type: 'd3-force',
      animation: false,
      link: { distance: 108, strength: 0.8 },
      manyBody: { strength: -250 },
      center: { strength: 0.55 },
      collide: { radius: 40 },
      iterations: 280
    },
    node: {
      type: 'circle',
      style: nodeStyle,
      state: {
        selected: {
          halo: true,
          haloStroke: '#6366f1',
          haloLineWidth: 8,
          haloStrokeOpacity: 0.22
        }
      }
    },
    edge: {
      type: datum => datum.data?.layoutTree === false ? 'quadratic' : 'line',
      style: edgeStyle,
      state: {
        selected: datum => ({
          stroke: '#4f46e5',
          lineWidth: 3,
          opacity: 1,
          labelText: String(datum.data?.predicate || '')
        })
      }
    },
    plugins: [{
      type: 'tooltip',
      enable: (_event: IElementEvent, items: { data?: Record<string, unknown> }[]) => Boolean(items[0]?.data?.name),
      getContent: async (_event: IElementEvent, items: { data?: Record<string, unknown> }[]) => {
        const content = window.document.createElement('div');
        content.textContent = String(items[0]?.data?.name || '');
        content.style.cssText = 'max-width: 320px; white-space: normal; overflow-wrap: anywhere';
        return content;
      }
    }],
    behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', 'hover-activate']
  });

  graph.on(EdgeEvent.CLICK, (event: IElementEvent) => selectEdge(String(event.target.id)));
  graph.on(NodeEvent.CLICK, (event: IElementEvent) => selectNode(String(event.target.id)));
  graph.on(CanvasEvent.CLICK, clearSelection);
  rendering.value = true;
  try {
    await graph.render();
    await fitView();
  } finally {
    rendering.value = false;
  }
}

async function refreshGraph() {
  if (visibleData.value.nodes.length === 0) {
    graph?.destroy();
    graph = null;
    return;
  }
  await createGraph();
}

async function fitView(animated = true) {
  if (graph && documentNode.value) {
    const [width, height] = graph.getSize();
    if (!width || !height) return;
    let extentX = 180;
    let extentY = 160;
    for (const node of graph.getNodeData()) {
      const label = String(node.data?.displayLabel || node.data?.name || '');
      const lines = label.split('\n');
      extentX = Math.max(extentX, Math.abs(Number(node.style?.x || 0)) + 160);
      extentY = Math.max(extentY, Math.abs(Number(node.style?.y || 0)) + 50 + lines.length * 18);
    }
    // Fit symmetric bounds around the document, not the off-center bounds of unequal branches.
    await graph.zoomTo(Math.max(0.01, Math.min(1, (width - 48) / (2 * extentX), (height - 48) / (2 * extentY))), false);
    await graph.focusElement(documentNode.value.id, animated ? { duration: 300 } : false);
    return;
  }
  await graph?.fitView(
    { when: 'always', direction: 'both' },
    animated ? { duration: 300 } : undefined
  );
}

async function relayout() {
  rendering.value = true;
  try {
    if (props.document) {
      await clearSelection();
      graph?.setData(graphData());
      await graph?.render();
    } else {
      await graph?.layout();
    }
    await fitView();
  } finally {
    rendering.value = false;
  }
}

async function selectEdge(edgeId: string) {
  if (!props.edges.some(edge => edge.id === edgeId)) return;
  await resetElementStates();
  selectedEdgeId.value = edgeId;
  selectedNodeId.value = null;
  await graph?.setElementState(edgeId, ['selected'], true);
  const edge = props.edges.find(item => item.id === edgeId);
  if (edge) emit('edgeSelect', edge);
}

async function selectNode(nodeId: string) {
  await resetElementStates();
  selectedNodeId.value = nodeId;
  selectedEdgeId.value = null;
  await graph?.setElementState(nodeId, ['selected'], true);
  const node = props.nodes.find(item => item.id === nodeId);
  if (node) emit('nodeSelect', node);
}

async function resetElementStates() {
  if (!graph) return;
  const states = Object.fromEntries([
    ...visibleData.value.nodes.map(node => [node.id, []]),
    ...(documentNode.value ? [[documentNode.value.id, []]] : []),
    ...visibleData.value.edges.map(edge => [edge.id, []])
  ]);
  await graph.setElementState(states, false);
}

async function clearSelection() {
  await resetElementStates();
  selectedEdgeId.value = null;
  selectedNodeId.value = null;
  emit('selectionClear');
}

async function toggleFullscreen() {
  if (!frameRef.value) return;
  if (document.fullscreenElement) await document.exitFullscreen();
  else await (props.fullscreenTarget || frameRef.value).requestFullscreen();
}

function syncFullscreenState() {
  isFullscreen.value = document.fullscreenElement === (props.fullscreenTarget || frameRef.value);
  requestAnimationFrame(() => {
    requestAnimationFrame(async () => {
      graph?.resize();
      await fitView(false);
    });
  });
}

watch(
  () => [props.nodes, props.edges, props.document, keyword.value, selectedType.value, themeStore.darkMode],
  () => {
    selectedEdgeId.value = null;
    selectedNodeId.value = null;
    refreshGraph();
  },
  { deep: true, flush: 'post' }
);

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullscreenState);
  refreshGraph();
});
onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState);
  canvasResizeObserver.disconnect();
  cancelAnimationFrame(resizeFrame);
  graph?.destroy();
});
</script>

<template>
  <div ref="frameRef" class="graph-frame">
    <div class="graph-toolbar">
      <div class="min-w-0 flex flex-1 flex-wrap items-center gap-8px">
        <NInput v-model:value="keyword" clearable size="small" placeholder="搜索实体或文档" class="max-w-full" style="width: 210px">
          <template #prefix>
            <SvgIcon icon="mdi:magnify" />
          </template>
        </NInput>
        <NSelect
          v-model:value="selectedType"
          clearable
          size="small"
          placeholder="全部类型"
          :options="typeOptions"
          class="max-w-full"
          style="width: 150px"
        />
        <NText depth="3" class="text-12px">
          {{ document ? '1 篇文档 · ' : '' }}{{ visibleData.nodes.length }} 个实体 · {{ visibleData.edges.length }} 条关系
        </NText>
      </div>
      <NSpace :size="8">
        <NButton size="small" secondary @click="fitView()">适应画布</NButton>
        <NButton size="small" secondary :loading="rendering" @click="relayout">重新布局</NButton>
        <NButton size="small" secondary @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</NButton>
      </NSpace>
    </div>

    <div class="graph-legend">
      <span v-for="option in typeOptions" :key="option.value" class="graph-legend__item">
        <i :style="{ backgroundColor: nodeColor(option.value) }" />
        {{ option.label }}
      </span>
    </div>

    <div v-if="visibleData.nodes.length === 0" class="graph-empty flex items-center justify-center">
      <NEmpty description="没有符合条件的图谱关系" />
    </div>
    <div v-else class="graph-content">
      <div ref="containerRef" class="graph-canvas" />
      <aside v-if="showInspector && (selectedEdge || selectedNode)" class="graph-inspector">
        <template v-if="selectedEdge">
          <NTag size="small" type="info">关系</NTag>
          <h3>{{ selectedEdge.predicate }}</h3>
          <NText depth="3" class="text-12px">
            置信度 {{ Math.round(selectedEdge.confidence * 100) }}% · 切片 {{ selectedEdge.evidenceChunkId }}
          </NText>
          <NDivider class="my-12px!" />
          <NText class="leading-22px">{{ selectedEdge.evidenceText }}</NText>
        </template>
        <template v-else-if="selectedNode">
          <NTag size="small" :color="{ color: `${nodeColor(selectedNode.type)}18`, textColor: nodeColor(selectedNode.type) }">
            {{ selectedNode.type }}
          </NTag>
          <h3>{{ selectedNode.name }}</h3>
          <NText v-if="selectedNode.id === documentNode?.id" depth="3">文档节点已入图，可通过标题检索；蓝色连线折叠表示 Document → Claim → Entity 溯源链路</NText>
          <NText v-else depth="3">连接了 {{ selectedNode.degree }} 条关系</NText>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.graph-frame {
  position: relative;
  overflow: hidden;
  border: 1px solid #dfe5ed;
  border-radius: 5px;
  background: #fff;
  box-shadow: none;
}

.graph-frame:fullscreen {
  padding: 20px;
  background: #f8fafc;
}

.graph-frame:fullscreen .graph-canvas {
  height: calc(100vh - 126px);
}

.graph-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid #e4e9f0;
  background: #fff;
}

.graph-legend {
  display: flex;
  min-height: 36px;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  padding: 8px 14px;
  color: #64748b;
  font-size: 11px;
  border-bottom: 1px solid #edf0f4;
  background: #fff;
}

.graph-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.graph-legend__item i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.graph-content {
  position: relative;
}

.graph-canvas {
  height: clamp(320px, calc(100vh - 390px), 600px);
}

.graph-empty {
  height: clamp(320px, calc(100vh - 390px), 600px);
}

.graph-inspector {
  position: absolute;
  top: 12px;
  right: 12px;
  width: min(300px, calc(100% - 24px));
  max-height: calc(100% - 24px);
  overflow: auto;
  padding: 16px;
  border: 1px solid rgb(148 163 184 / 24%);
  border-radius: 5px;
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 8px 24px rgb(15 23 42 / 10%);
}

.graph-inspector h3 {
  margin: 10px 0 6px;
  color: #1e293b;
  font-size: 17px;
}

:global(html.dark) .graph-toolbar,
:global(html.dark) .graph-inspector {
  background: rgb(17 24 39 / 92%);
}

:global(html.dark) .graph-frame {
  border-color: #303b4d;
  background:
    radial-gradient(circle at 14% 4%, rgb(118 105 173 / 13%), transparent 34%),
    radial-gradient(circle at 88% 92%, rgb(67 136 153 / 10%), transparent 30%),
    #111827;
  box-shadow: none;
}

:global(html.dark) .graph-frame:fullscreen {
  background: #111827;
}

:global(html.dark) .graph-toolbar {
  border-color: #2b3546;
}

:global(html.dark) .graph-legend {
  color: #9ca9ba;
  background: rgb(17 24 39 / 72%);
}

:global(html.dark) .graph-inspector h3 {
  color: #f1f5f9;
}

@media (max-width: 640px) {
  .graph-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .graph-canvas {
    height: clamp(340px, calc(100vh - 360px), 520px);
  }

  .graph-empty {
    height: clamp(340px, calc(100vh - 360px), 520px);
  }
}
</style>
