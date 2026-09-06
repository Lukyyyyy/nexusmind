/** Document membership is presentation metadata; the input knowledge edges remain untouched. */
interface Entity {
  id: string;
  name: string;
}
interface Relation {
  source: string;
  target: string;
}
export interface DocumentPosition {
  x: number;
  y: number;
  angle: number;
  radius: number;
  depth: number;
  core: boolean;
  parent: string | null;
  label: string;
  width: number;
  height: number;
}

export function documentLabel(name: string, core: boolean, document = false) {
  const chars = Array.from(name);
  if (!core && !document) return chars.length > 12 ? `${chars.slice(0, 11).join('')}…` : name;
  const lines: string[] = [];
  const columns = document ? 20 : 14;
  for (let i = 0; i < chars.length; i += columns) lines.push(chars.slice(i, i + columns).join(''));
  return lines.join('\n');
}

export function layoutDocumentGraph(nodes: Entity[], edges: Relation[], documentName: string) {
  const byId = new Map(nodes.map(node => [node.id, node]));
  const adjacency = new Map(nodes.map(node => [node.id, new Set<string>()]));
  edges.forEach(({ source, target }) => {
    if (source === target || !byId.has(source) || !byId.has(target)) return;
    adjacency.get(source)!.add(target);
    adjacency.get(target)!.add(source);
  });
  const ranked = [...byId.keys()].sort((a, b) => adjacency.get(b)!.size - adjacency.get(a)!.size || a.localeCompare(b));
  const seen = new Set<string>();
  const components: string[][] = [];
  for (const seed of ranked) {
    if (seen.has(seed)) continue;
    const component = [seed];
    seen.add(seed);
    for (let i = 0; i < component.length; i++) {
      for (const neighbor of adjacency.get(component[i])!) {
        if (!seen.has(neighbor)) { seen.add(neighbor); component.push(neighbor); }
      }
    }
    components.push(component);
  }
  components.sort((a, b) => b.length - a.length || a[0].localeCompare(b[0]));
  const budget = Math.min(nodes.length, Math.max(4, Math.min(6, Math.round(Math.sqrt(nodes.length)))));
  // Cover the largest independent groups first, then favor uncovered neighborhoods.
  const cores = components.slice(0, budget).map(component => component[0]);
  const covered = new Set<string>();
  const cover = (id: string) => { covered.add(id); adjacency.get(id)!.forEach(n => covered.add(n)); };
  cores.forEach(cover);
  while (cores.length < budget) {
    const candidates = ranked.filter(id => !cores.includes(id));
    const score = (id: string) => [...adjacency.get(id)!].filter(n => !covered.has(n)).length * 3
      + (covered.has(id) ? 0 : 2) + adjacency.get(id)!.size / Math.max(1, nodes.length);
    candidates.sort((a, b) => score(b) - score(a) || a.localeCompare(b));
    cores.push(candidates[0]);
    cover(candidates[0]);
  }
  const coreSet = new Set(cores);
  // Extra disconnected groups get their own outer branch, never an invented entity relation.
  const roots = [...cores, ...components.filter(component => !component.some(id => coreSet.has(id))).map(c => c[0])];
  const parent = new Map<string, string | null>(roots.map(id => [id, null]));
  const depth = new Map(roots.map(id => [id, coreSet.has(id) ? 1 : 2]));
  const children = new Map(nodes.map(node => [node.id, [] as string[]]));
  const queue = [...roots];
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i];
    const neighbors = [...adjacency.get(id)!].sort((a, b) => a.localeCompare(b));
    for (const next of neighbors) {
      if (parent.has(next)) continue;
      parent.set(next, id);
      depth.set(next, depth.get(id)! + 1);
      children.get(id)!.push(next);
      queue.push(next);
    }
  }
  const weights = new Map<string, number>();
  for (const id of [...queue].reverse()) {
    weights.set(id, 1 + children.get(id)!.reduce((sum, child) => sum + weights.get(child)!, 0));
  }

  interface Sector { start: number; span: number }
  const sectors = new Map<string, Sector>();
  const angles = new Map<string, number>();
  const fullCircle = Math.PI * 2;
  const rootGap = roots.length > 1 ? Math.min(0.1, Math.PI / (roots.length * 5)) : 0;
  const availableRootSpan = Math.max(fullCircle * 0.72, fullCircle - rootGap * roots.length);
  const rootScores = roots.map(id => Math.max(1.4, Math.sqrt(weights.get(id)!)));
  const rootScoreTotal = rootScores.reduce((sum, score) => sum + score, 0);
  let rootCursor = -Math.PI / 2;
  const pending = roots.map((id, index) => {
    const span = availableRootSpan * rootScores[index] / rootScoreTotal;
    const sector = { id, start: rootCursor + rootGap / 2, span };
    rootCursor += span + rootGap;
    return sector;
  });
  for (let i = 0; i < pending.length; i++) {
    const { id, start, span } = pending[i];
    sectors.set(id, { start, span });
    angles.set(id, start + span / 2);
    const kids = [...children.get(id)!].sort((a, b) => weights.get(b)! - weights.get(a)! || a.localeCompare(b));
    if (kids.length === 0) continue;
    const childGap = kids.length > 1 ? Math.min(0.055, span / (kids.length * 6)) : 0;
    const usableSpan = Math.max(span * 0.78, span - childGap * (kids.length - 1));
    const scores = kids.map(child => Math.max(1, Math.sqrt(weights.get(child)!)));
    const total = scores.reduce((sum, score) => sum + score, 0);
    let cursor = start;
    kids.forEach((child, index) => {
      const childSpan = usableSpan * scores[index] / total;
      pending.push({ id: child, start: cursor, span: childSpan });
      cursor += childSpan + childGap;
    });
  }

  const positions = new Map<string, DocumentPosition>();
  const centerLabel = documentLabel(documentName, false, true);
  const centerHeight = 76 + 12 + centerLabel.split('\n').length * 18;
  const boxes: { x: number; y: number; width: number; height: number }[] = [
    { x: 0, y: (centerHeight - 76) / 2, width: 300, height: centerHeight }
  ];
  const intersects = (a: typeof boxes[number], b: typeof boxes[number]) =>
    Math.abs(a.x - b.x) < (a.width + b.width) / 2 + 24 && Math.abs(a.y - b.y) < (a.height + b.height) / 2 + 24;
  const radii = new Map<string, number>();
  const levels = [...new Set(depth.values())].sort((a, b) => a - b);
  for (const level of levels) {
    const ids = queue.filter(id => depth.get(id) === level)
      .sort((a, b) => angles.get(a)! - angles.get(b)! || a.localeCompare(b));
    const metrics = ids.map(id => {
      const label = documentLabel(byId.get(id)!.name, coreSet.has(id));
      const lines = label.split('\n');
      const width = Math.max(58, Math.max(...lines.map(line => Array.from(line).length)) * 13);
      const height = 58 + 12 + lines.length * 18;
      return { id, label, width, height };
    });
    metrics.forEach(({ id, label, width, height }) => {
      const parentId = parent.get(id)!;
      const baseRadius = Math.max(190 + (level - 1) * 160, parentId ? radii.get(parentId)! + 145 : 190);
      const baseAngle = angles.get(id)!;
      const sector = sectors.get(id)!;
      const nudgeStep = Math.min(0.045, sector.span / 12);
      const nudges = [0, 1, -1, 2, -2, 3, -3];
      let placedBox: typeof boxes[number] | null = null;
      let placedAngle = baseAngle;
      let placedRadius = baseRadius;
      for (let radialStep = 0; radialStep < 80 && !placedBox; radialStep++) {
        const radius = baseRadius + radialStep * 12;
        for (const nudge of nudges) {
          const margin = Math.min(0.02, sector.span / 10);
          const angle = Math.max(sector.start + margin,
            Math.min(sector.start + sector.span - margin, baseAngle + nudge * nudgeStep));
          const candidate = { x: radius * Math.cos(angle),
            y: radius * Math.sin(angle) + (height - 58) / 2, width, height };
          if (boxes.every(other => !intersects(candidate, other))) {
            placedBox = candidate;
            placedAngle = angle;
            placedRadius = radius;
            break;
          }
        }
      }
      if (!placedBox) {
        placedRadius = baseRadius + 80 * 12;
        placedBox = { x: placedRadius * Math.cos(baseAngle),
          y: placedRadius * Math.sin(baseAngle) + (height - 58) / 2, width, height };
      }
      angles.set(id, placedAngle);
      radii.set(id, placedRadius);
      positions.set(id, { x: placedBox.x, y: placedBox.y - (height - 58) / 2,
        angle: placedAngle, radius: placedRadius, depth: level, core: coreSet.has(id),
        parent: parentId, label, width, height });
      boxes.push(placedBox);
    });
  }
  return { positions, roots, cores, centerLabel };
}
