interface OrganizationNode {
  id: string;
  name: string;
  degree: number;
  componentId?: string;
  communityId?: string;
  importance?: number;
}

export interface OrganizationPosition {
  x: number;
  y: number;
  label: string;
  communityCore: boolean;
}

interface Box<T> {
  item: T;
  width: number;
  height: number;
  x: number;
  y: number;
}

const LABEL_GAP = 170;
const GROUP_GAP = 110;

function displayLabel(name: string) {
  const chars = Array.from(name);
  return chars.length > 14 ? `${chars.slice(0, 13).join('')}…` : name;
}

function packRows<T>(items: { item: T; width: number; height: number }[], targetRatio = 1.6) {
  if (items.length === 0) return [] as Box<T>[];
  const totalArea = items.reduce((sum, box) => sum + (box.width + GROUP_GAP) * (box.height + GROUP_GAP), 0);
  const targetWidth = Math.max(items[0].width, Math.sqrt(totalArea * targetRatio));
  const rows: { boxes: Box<T>[]; width: number; height: number }[] = [];
  let row = { boxes: [] as Box<T>[], width: 0, height: 0 };
  for (const item of items) {
    const nextWidth = row.boxes.length === 0 ? item.width : row.width + GROUP_GAP + item.width;
    if (row.boxes.length > 0 && nextWidth > targetWidth) {
      rows.push(row);
      row = { boxes: [], width: 0, height: 0 };
    }
    const x = row.boxes.length === 0 ? 0 : row.width + GROUP_GAP;
    row.boxes.push({ ...item, x, y: 0 });
    row.width = x + item.width;
    row.height = Math.max(row.height, item.height);
  }
  if (row.boxes.length > 0) rows.push(row);
  const packed: Box<T>[] = [];
  let y = 0;
  for (const current of rows) {
    const rowOffset = (targetWidth - current.width) / 2;
    current.boxes.forEach(box => packed.push({ ...box, x: box.x + rowOffset, y: y + (current.height - box.height) / 2 }));
    y += current.height + GROUP_GAP;
  }
  const minX = Math.min(...packed.map(box => box.x));
  const maxX = Math.max(...packed.map(box => box.x + box.width));
  const minY = Math.min(...packed.map(box => box.y));
  const maxY = Math.max(...packed.map(box => box.y + box.height));
  const centerX = (minX + maxX) / 2;
  const centerY = (minY + maxY) / 2;
  return packed.map(box => ({ ...box, x: box.x + box.width / 2 - centerX, y: box.y + box.height / 2 - centerY }));
}

export function layoutOrganizationGraph(nodes: OrganizationNode[]) {
  const positions = new Map<string, OrganizationPosition>();
  const communities = new Map<string, OrganizationNode[]>();
  for (const node of nodes) {
    const communityId = node.communityId || `community:${node.id}`;
    const members = communities.get(communityId) || [];
    members.push(node);
    communities.set(communityId, members);
  }

  const communityLayouts = [...communities.entries()].map(([id, members]) => {
    members.sort((a, b) => (b.importance || 0) - (a.importance || 0) || b.degree - a.degree || a.id.localeCompare(b.id));
    const local = new Map<string, OrganizationPosition>();
    const core = members[0];
    local.set(core.id, { x: 0, y: 0, label: displayLabel(core.name), communityCore: true });
    let cursor = 1;
    let ring = 1;
    while (cursor < members.length) {
      const radius = 105 + (ring - 1) * 92;
      const capacity = Math.max(4, Math.floor(Math.PI * 2 * radius / LABEL_GAP));
      const count = Math.min(capacity, members.length - cursor);
      for (let index = 0; index < count; index++) {
        const angle = -Math.PI / 2 + index * Math.PI * 2 / count + (ring % 2 === 0 ? Math.PI / count : 0);
        const member = members[cursor++];
        local.set(member.id, {
          x: radius * Math.cos(angle),
          y: radius * Math.sin(angle),
          label: displayLabel(member.name),
          communityCore: false
        });
      }
      ring++;
    }
    const extent = members.length === 1 ? 82 : 105 + Math.max(0, ring - 2) * 92 + 88;
    return { id, componentId: members[0].componentId || `component:${id}`, members, local,
      width: extent * 2, height: extent * 2 };
  }).sort((a, b) => b.members.length - a.members.length || a.id.localeCompare(b.id));

  const byComponent = new Map<string, typeof communityLayouts>();
  for (const community of communityLayouts) {
    const values = byComponent.get(community.componentId) || [];
    values.push(community);
    byComponent.set(community.componentId, values);
  }
  const componentLayouts = [...byComponent.entries()].map(([id, values]) => {
    const boxes = packRows(values.map(item => ({ item, width: item.width, height: item.height })), 1.35);
    const minX = Math.min(...boxes.map(box => box.x - box.width / 2));
    const maxX = Math.max(...boxes.map(box => box.x + box.width / 2));
    const minY = Math.min(...boxes.map(box => box.y - box.height / 2));
    const maxY = Math.max(...boxes.map(box => box.y + box.height / 2));
    return { id, boxes, nodeCount: values.reduce((sum, value) => sum + value.members.length, 0),
      width: maxX - minX + GROUP_GAP, height: maxY - minY + GROUP_GAP };
  }).sort((a, b) => b.nodeCount - a.nodeCount || a.id.localeCompare(b.id));

  const componentBoxes = packRows(componentLayouts.map(item => ({ item, width: item.width, height: item.height })));
  for (const componentBox of componentBoxes) {
    const component = componentBox.item;
    for (const communityBox of component.boxes) {
      const community = communityBox.item;
      for (const node of community.members) {
        const local = community.local.get(node.id)!;
        positions.set(node.id, {
          ...local,
          x: componentBox.x + communityBox.x + local.x,
          y: componentBox.y + communityBox.y + local.y
        });
      }
    }
  }
  return { positions };
}
