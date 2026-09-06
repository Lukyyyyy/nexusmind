import assert from 'node:assert/strict';
import { layoutOrganizationGraph } from '../src/views/knowledge-base/modules/organization-graph-layout';

const nodes = [
  ...Array.from({ length: 9 }, (_, index) => ({ id: `a${index}`, name: `胶囊网络实体${index}`,
    degree: 9 - index, importance: 1 - index * 0.05, componentId: 'component-a', communityId: 'community-a1' })),
  ...Array.from({ length: 6 }, (_, index) => ({ id: `b${index}`, name: `声音检测实体${index}`,
    degree: 6 - index, importance: 0.8 - index * 0.05, componentId: 'component-a', communityId: 'community-a2' })),
  ...Array.from({ length: 3 }, (_, index) => ({ id: `c${index}`, name: `独立分量实体${index}`,
    degree: 3 - index, importance: 0.5 - index * 0.05, componentId: 'component-b', communityId: 'community-b1' }))
];
const first = layoutOrganizationGraph(nodes);
const second = layoutOrganizationGraph([...nodes].reverse());
assert.equal(first.positions.size, nodes.length);
assert.deepEqual(first, second, '输入顺序不能造成布局跳动');
const coordinates = new Set([...first.positions.values()].map(position => `${position.x.toFixed(4)}:${position.y.toFixed(4)}`));
assert.equal(coordinates.size, nodes.length, '节点不能占用相同坐标');
for (const position of first.positions.values()) {
  assert.ok(Number.isFinite(position.x) && Number.isFinite(position.y));
  assert.ok(position.label.length > 0);
}
for (const communityId of new Set(nodes.map(node => node.communityId))) {
  const members = nodes.filter(node => node.communityId === communityId);
  assert.equal(members.filter(node => first.positions.get(node.id)?.communityCore).length, 1,
    '每个社区必须有且仅有一个视觉核心');
}
const center = (ids: string[]) => ids.reduce((sum, id) => ({
  x: sum.x + first.positions.get(id)!.x / ids.length,
  y: sum.y + first.positions.get(id)!.y / ids.length
}), { x: 0, y: 0 });
const componentA = center(nodes.filter(node => node.componentId === 'component-a').map(node => node.id));
const componentB = center(nodes.filter(node => node.componentId === 'component-b').map(node => node.id));
assert.ok(Math.hypot(componentA.x - componentB.x, componentA.y - componentB.y) > 200,
  '不同连通分量必须分区摆放');
console.log('PASS: 社区核心、分量装箱、确定性、有限坐标与唯一位置');
