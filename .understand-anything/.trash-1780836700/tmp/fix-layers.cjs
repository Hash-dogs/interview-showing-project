const fs = require('fs');
const path = require('path');

const projectRoot = 'd:/coding_world/javaguide_project/interviewer_showing_type/interviewer-project-master';
const intermediateDir = path.join(projectRoot, '.understand-anything/intermediate');

const graph = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'assembled-graph.json'), 'utf8'));

const fileLevelTypes = new Set(['file', 'config', 'document', 'service', 'pipeline', 'table', 'schema', 'resource', 'endpoint']);
const fileNodes = graph.nodes.filter(n => fileLevelTypes.has(n.type));
console.log('File-level nodes: ' + fileNodes.length);

// Define layer matching rules (exclusive - first match wins)
const layerRules = [
  { id: 'layer:backend-application', name: '后端应用层', description: 'Spring Boot后端应用入口和核心业务逻辑，包含Controller、Service、Repository三层的Java代码',
    test: (p) => p.startsWith('app/src/main/java/interview/guide/modules/') },
  { id: 'layer:backend-entry', name: '后端入口', description: 'Spring Boot应用入口类',
    test: (p) => p === 'app/src/main/java/interview/guide/App.java' },
  { id: 'layer:common-infrastructure', name: '公共基础设施层', description: 'AI集成、异步任务、异常处理、统一响应、Redis缓存、文件存储等共享组件',
    test: (p) => p.startsWith('app/src/main/java/interview/guide/common/') || p.startsWith('app/src/main/java/interview/guide/infrastructure/') },
  { id: 'layer:test-files', name: '测试代码', description: '后端单元测试和集成测试',
    test: (p) => p.includes('src/test/') || (p.includes('Test') && p.endsWith('.java') && p.includes('test')) },
  { id: 'layer:frontend-application', name: '前端应用层', description: 'React + TypeScript前端应用代码',
    test: (p) => p.startsWith('frontend/src/') },
  { id: 'layer:frontend-config', name: '前端构建配置', description: '前端构建工具和配置文件',
    test: (p) => p.startsWith('frontend/') },
  { id: 'layer:ai-prompts', name: 'AI提示词与面试技能定义', description: 'AI面试方向的技能定义、提示词模板、面试题目库',
    test: (p) => p.startsWith('app/src/main/resources/prompts/') },
  { id: 'layer:infrastructure-deployment', name: '基础设施与部署', description: 'Docker、Docker Compose、Nginx、数据库初始化',
    test: (p) => p.includes('docker-compose') || p.startsWith('docker/') || p.endsWith('.dockerignore') || p.endsWith('Dockerfile') },
  { id: 'layer:app-configuration', name: '应用配置', description: 'Spring Boot配置、Gradle构建、日志配置、环境变量',
    test: (p) => p.startsWith('app/src/main/resources/') || p.endsWith('build.gradle') || p.endsWith('settings.gradle') || p.endsWith('libs.versions.toml') || p === '.env.example' },
  { id: 'layer:documentation', name: '文档', description: '项目README、AGENTS、CLAUDE等核心文档',
    test: (p) => ['README.md','AGENTS.md','CLAUDE.md','SETUP_API_KEYS.md','LICENSE'].includes(p) || p.startsWith('docs/') },
  { id: 'layer:knowledge-docs', name: '知识库文档', description: 'markdown/目录下的技术知识点文档',
    test: (p) => p.startsWith('markdown/') },
  { id: 'layer:app-resources', name: '其他应用资源', description: 'app/目录下的其他构建和资源文件',
    test: (p) => p.startsWith('app/') },
];

const assigned = new Map();
const layers = [];

for (const rule of layerRules) {
  layers.push({ id: rule.id, name: rule.name, description: rule.description, nodeIds: [] });
}

for (const node of fileNodes) {
  if (assigned.has(node.id)) continue;
  let matched = false;
  for (let i = 0; i < layerRules.length; i++) {
    if (layerRules[i].test(node.filePath)) {
      layers[i].nodeIds.push(node.id);
      assigned.set(node.id, layers[i].id);
      matched = true;
      break;
    }
  }
  if (!matched) {
    // Find or create "other" layer
    let otherIdx = layers.findIndex(l => l.id === 'layer:miscellaneous');
    if (otherIdx === -1) {
      layers.push({ id: 'layer:miscellaneous', name: '其他文件', description: '未分类到其他层的项目文件', nodeIds: [] });
      otherIdx = layers.length - 1;
    }
    layers[otherIdx].nodeIds.push(node.id);
    assigned.set(node.id, 'layer:miscellaneous');
  }
}

// Clean up empty layers
const cleanLayers = layers.filter(l => l.nodeIds.length > 0);

// Verify
const allIds = cleanLayers.flatMap(l => l.nodeIds);
const uniqueIds = new Set(allIds);
console.log('Assigned: ' + uniqueIds.size + '/' + fileNodes.length + ' file nodes');
console.log('Duplicates: ' + (allIds.length - uniqueIds.size));
console.log('Layers: ' + cleanLayers.length);
cleanLayers.forEach(l => console.log('  ' + l.id + ': ' + l.nodeIds.length + ' nodes'));

fs.writeFileSync(path.join(intermediateDir, 'layers.json'), JSON.stringify(cleanLayers, null, 2));
console.log('Layers written to layers.json');
