const fs = require('fs');
const path = require('path');

const projectRoot = 'd:/coding_world/javaguide_project/interviewer_showing_type/interviewer-project-master';
const intermediateDir = path.join(projectRoot, '.understand-anything/intermediate');

// Read assembled graph
const graph = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'assembled-graph.json'), 'utf8'));

// Read import map
const importMap = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'import-map-output.json'), 'utf8'));

const nodeIds = new Set(graph.nodes.map(n => n.id));
const existingEdges = new Set(
  graph.edges.map(e => `${e.source}|${e.target}|${e.type}`)
);

let addedEdges = 0;
let skippedEdges = 0;

// Add import edges from import map
for (const [sourcePath, targets] of Object.entries(importMap.importMap || {})) {
  if (!targets || targets.length === 0) continue;

  // Determine source node ID
  const sourceNode = graph.nodes.find(n => n.filePath === sourcePath);
  if (!sourceNode) {
    skippedEdges += targets.length;
    continue;
  }
  const sourceId = sourceNode.id;

  for (const targetPath of targets) {
    const targetNode = graph.nodes.find(n => n.filePath === targetPath);
    if (!targetNode) {
      skippedEdges++;
      continue;
    }
    const targetId = targetNode.id;

    const edgeKey = `${sourceId}|${targetId}|imports`;
    if (!existingEdges.has(edgeKey)) {
      graph.edges.push({
        source: sourceId,
        target: targetId,
        type: 'imports',
        weight: 0.7,
        metadata: { relationship: 'imports' }
      });
      existingEdges.add(edgeKey);
      addedEdges++;
    }
  }
}

console.log('Added ' + addedEdges + ' import edges');
console.log('Skipped ' + skippedEdges + ' unresolvable imports');
console.log('Total graph: ' + graph.nodes.length + ' nodes, ' + graph.edges.length + ' edges');

// Write updated graph
fs.writeFileSync(
  path.join(intermediateDir, 'assembled-graph.json'),
  JSON.stringify(graph, null, 2));
console.log('Updated graph written to assembled-graph.json');
