const fs = require('fs');
const path = require('path');

const projectRoot = 'd:/coding_world/javaguide_project/interviewer_showing_type/interviewer-project-master';
const intermediateDir = path.join(projectRoot, '.understand-anything/intermediate');

// Read assembled graph
const assembled = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'assembled-graph.json'), 'utf8'));

// Read layers
const layers = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'layers.json'), 'utf8'));

// Read tour
const tour = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'tour.json'), 'utf8'));

// Build the final knowledge graph
const knowledgeGraph = {
  version: '1.0.0',
  project: {
    name: 'interview-guide (AI智能面试辅助平台)',
    languages: [
      'Java 21', 'TypeScript', 'JavaScript', 'CSS', 'HTML',
      'YAML', 'Markdown', 'Gradle', 'Dockerfile', 'SQL',
      'XML', 'Lua', 'TOML', 'Properties', 'Batch'
    ],
    frameworks: [
      'Spring Boot 4.0', 'Spring AI 2.0', 'React 18',
      'Vite', 'TailwindCSS 4', 'PostgreSQL/pgvector',
      'Redis (Redisson)', 'Docker Compose'
    ],
    description: '基于 Spring Boot 4.0 + Java 21 + Spring AI + PostgreSQL/pgvector + Redis 构建的全栈 AI 面试辅助系统，集成简历智能分析、AI 模拟面试与 RAG 知识库检索三大核心能力。',
    analyzedAt: new Date().toISOString(),
    gitCommitHash: '37932dedcda72e329e7cc2d778896380160f8e41'
  },
  nodes: assembled.nodes,
  edges: assembled.edges,
  layers: layers,
  tour: tour
};

// Normalize layers: ensure all have the four required fields
for (const layer of knowledgeGraph.layers) {
  if (!layer.id) layer.id = 'layer:unnamed';
  if (!layer.name) layer.name = layer.id.replace('layer:', '');
  if (!layer.description) layer.description = layer.name;
  if (!layer.nodeIds) layer.nodeIds = [];
}

// Normalize tour: ensure required fields
for (const step of knowledgeGraph.tour) {
  if (step.order === undefined) step.order = 1;
  if (!step.title) step.title = 'Untitled Step';
  if (!step.description) step.description = '';
  if (!step.nodeIds) step.nodeIds = [];
}

// Write assembled graph for validation
fs.writeFileSync(
  path.join(intermediateDir, 'assembled-graph.json'),
  JSON.stringify(knowledgeGraph, null, 2)
);

console.log('Final assembled graph written');
console.log('Nodes: ' + knowledgeGraph.nodes.length);
console.log('Edges: ' + knowledgeGraph.edges.length);
console.log('Layers: ' + knowledgeGraph.layers.length);
console.log('Tour steps: ' + knowledgeGraph.tour.length);
