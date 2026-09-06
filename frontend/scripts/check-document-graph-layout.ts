import assert from 'node:assert/strict';
import { layoutDocumentGraph } from '../src/views/knowledge-base/modules/document-graph-layout';
const nodes = Array.from({ length: 37 }, (_, i) => ({ id: `n${String(i).padStart(2, '0')}`, name: `实体${i}的完整名称及测试说明` }));
const edge = (a: number, b: number) => ({ source: nodes[a].id, target: nodes[b].id });
const edges = [
  ...Array.from({ length: 24 }, (_, i) => edge(Math.floor(i / 4), i + 6)),
  edge(0, 1), edge(1, 2), edge(2, 3), edge(3, 4), edge(4, 5),
  edge(30, 31), edge(31, 32), edge(33, 34), edge(34, 35)
];
function verify(inputNodes = nodes, inputEdges = edges) {
  const before = JSON.stringify(inputEdges);
  const layout = layoutDocumentGraph(inputNodes, inputEdges, '基于卷积神经网络的车辆碰撞声识别方法.pdf');
  assert.equal(layout.positions.size, inputNodes.length);
  assert.equal(JSON.stringify(inputEdges), before, '布局不能改写真实关系');
  assert.ok(layout.cores.length <= 6);
  const values = [...layout.positions.values()];
  for (const [id, position] of layout.positions) {
    assert.ok(Number.isFinite(position.x) && Number.isFinite(position.y));
    if (position.parent) assert.ok(inputEdges.some(e =>
      (e.source === id && e.target === position.parent) || (e.target === id && e.source === position.parent)), '层级必须沿真实关系展开');
    if (position.core) assert.equal(position.label.replaceAll('\n', ''), inputNodes.find(n => n.id === id)!.name);
  }
  for (let i = 0; i < values.length; i++) {
    const a = values[i];
    for (const b of values.slice(0, i)) {
      const dy = Math.abs(a.y + (a.height - 58) / 2 - b.y - (b.height - 58) / 2);
      assert.ok(Math.abs(a.x - b.x) >= (a.width + b.width) / 2 + 23 || dy >= (a.height + b.height) / 2 + 23, '节点和标签不能重叠');
    }
  }
  return layout;
}
const layout = verify();
assert.ok([...layout.positions.values()].some(p => p.depth > 1));
assert.ok([...layout.positions.values()].some(p => p.x < 0));
assert.ok([...layout.positions.values()].some(p => p.x > 0));
assert.ok([...layout.positions.values()].some(p => p.y < 0));
assert.ok([...layout.positions.values()].some(p => p.y > 0));
assert.deepEqual(verify([...nodes].reverse(), [...edges].reverse()), layout, '输入顺序不能造成布局跳动');
verify(nodes, []);
verify(nodes.slice(0, 1), []);
verify([], []);
verify(nodes, nodes.slice(1).map((_, i) => edge(0, i + 1)));
verify(nodes, nodes.slice(1).map((_, i) => edge(i, i + 1)));
console.log('PASS: 多层展开、覆盖四周、真实关系、独立实体、星形/链形、标签避让、确定性');
