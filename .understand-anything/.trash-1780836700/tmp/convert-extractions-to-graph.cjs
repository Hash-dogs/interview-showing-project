const fs = require('fs');
const path = require('path');

const projectRoot = 'd:/coding_world/javaguide_project/interviewer_showing_type/interviewer-project-master';
const intermediateDir = path.join(projectRoot, '.understand-anything/intermediate');

/**
 * Map file category and language to graph node type
 */
function getNodeType(fileCategory, language, fileRecord) {
  if (fileCategory === 'config') return 'config';
  if (fileCategory === 'docs') return 'document';
  if (fileCategory === 'infra') {
    if (language === 'dockerfile') return 'service';
    if (language === 'yaml' || language === 'sql') return 'service';
    return 'config';
  }
  if (fileCategory === 'markup') return 'file';
  if (fileCategory === 'data') return 'config';
  if (fileCategory === 'script') return 'file';
  return 'file';
}

/**
 * Estimate complexity from line count
 */
function estimateComplexity(lines) {
  if (lines < 30) return 'simple';
  if (lines < 100) return 'moderate';
  if (lines < 300) return 'complex';
  return 'very_complex';
}

/**
 * Generate tags from file info
 */
function generateTags(filePath, language, fileCategory) {
  const tags = [language || 'unknown'];
  if (fileCategory) tags.push(fileCategory);

  const ext = path.extname(filePath).replace('.', '');
  if (ext) tags.push(ext);

  // Path-based tags
  const parts = filePath.split('/');
  if (parts.length > 1) tags.push(parts[0]); // top-level dir

  return [...new Set(tags)];
}

/**
 * Generate a summary from the extract result
 */
function generateSummary(filePath, language, extractResult) {
  const fileName = path.basename(filePath);

  if (extractResult) {
    if (extractResult.classes && extractResult.classes.length > 0) {
      const classNames = extractResult.classes.map(c => c.name).join(', ');
      return `定义类: ${classNames}`;
    }
    if (extractResult.functions && extractResult.functions.length > 0) {
      const funcNames = extractResult.functions.slice(0, 5).map(f => f.name).join(', ');
      return `定义函数/方法: ${funcNames}`;
    }
    if (extractResult.services && extractResult.services.length > 0) {
      const svcNames = extractResult.services.map(s => s.name).join(', ');
      return `定义服务: ${svcNames}`;
    }
    if (extractResult.imports && extractResult.imports.length > 0) {
      return `文件包含 ${extractResult.imports.length} 个导入`;
    }
  }

  // Generic summary based on file type
  const summaries = {
    'markdown': 'Markdown 文档',
    'yaml': 'YAML 配置文件',
    'json': 'JSON 配置文件',
    'dockerfile': 'Docker 容器定义',
    'java': 'Java 源文件',
    'typescript': 'TypeScript 源文件',
    'javascript': 'JavaScript 源文件',
    'css': 'CSS 样式文件',
    'html': 'HTML 模板文件',
    'sql': 'SQL 数据库脚本',
    'xml': 'XML 配置文件',
    'gradle': 'Gradle 构建配置',
    'properties': 'Properties 配置文件',
    'toml': 'TOML 配置文件',
    'lua': 'Lua 脚本',
    'st': 'StringTemplate 模板文件',
    'conf': '配置文件',
    'batch': '批处理脚本',
  };

  return summaries[language] || `${fileName} 文件`;
}

// Read all extract output files
const extractFiles = fs.readdirSync(intermediateDir)
  .filter(f => f.startsWith('extract-output-batch-') && f.endsWith('.json'))
  .sort((a, b) => {
    const na = parseInt(a.match(/batch-(\d+)/)[1]);
    const nb = parseInt(b.match(/batch-(\d+)/)[1]);
    return na - nb;
  });

console.log('Found ' + extractFiles.length + ' extraction output files\n');

// Read batches.json for file metadata
const batches = JSON.parse(fs.readFileSync(
  path.join(intermediateDir, 'batches.json'), 'utf8'));

// Build file metadata map
const fileMeta = {};
for (const batch of batches.batches) {
  for (const f of batch.files) {
    fileMeta[f.path] = f;
  }
}

for (const extractFile of extractFiles) {
  const match = extractFile.match(/batch-(\d+)/);
  const batchIdx = match[1];

  const extractData = JSON.parse(fs.readFileSync(
    path.join(intermediateDir, extractFile), 'utf8'));

  const nodes = [];
  const edges = [];

  for (const result of extractData.results || []) {
    const fpath = result.path;
    const meta = fileMeta[fpath] || {};
    const language = result.language || meta.language || 'unknown';
    const fileCategory = result.fileCategory || meta.fileCategory || 'code';
    const totalLines = result.totalLines || meta.sizeLines || 0;

    const nodeType = getNodeType(fileCategory, language, result);
    const fileId = nodeType + ':' + fpath;

    // Create file-level node
    const fileNode = {
      id: fileId,
      type: nodeType,
      name: path.basename(fpath),
      filePath: fpath,
      summary: generateSummary(fpath, language, result),
      tags: generateTags(fpath, language, fileCategory),
      complexity: estimateComplexity(totalLines)
    };
    nodes.push(fileNode);

    // Create function nodes for extracted functions
    if (result.functions) {
      for (const func of result.functions) {
        const funcId = 'function:' + fpath + ':' + func.name;
        nodes.push({
          id: funcId,
          type: 'function',
          name: func.name,
          filePath: fpath,
          summary: '函数 ' + func.name + (func.isAsync ? ' (异步)' : ''),
          tags: ['function', language],
          complexity: func.endLine && func.startLine ?
            estimateComplexity(func.endLine - func.startLine) : 'simple'
        });
        edges.push({
          source: fileId,
          target: funcId,
          type: 'contains',
          weight: 1.0,
          metadata: { relationship: 'file contains function' }
        });
      }
    }

    // Create class nodes for extracted classes
    if (result.classes) {
      for (const cls of result.classes) {
        const clsId = 'class:' + fpath + ':' + cls.name;
        nodes.push({
          id: clsId,
          type: 'class',
          name: cls.name,
          filePath: fpath,
          summary: '类 ' + cls.name,
          tags: ['class', language],
          complexity: cls.endLine && cls.startLine ?
            estimateComplexity(cls.endLine - cls.startLine) : 'simple'
        });
        edges.push({
          source: fileId,
          target: clsId,
          type: 'contains',
          weight: 1.0,
          metadata: { relationship: 'file contains class' }
        });

        // Handle inheritance
        if (cls.extends) {
          edges.push({
            source: clsId,
            target: cls.extends,
            type: 'inherits',
            weight: 0.9,
            metadata: { relationship: 'extends' }
          });
        }
        if (cls.implements && cls.implements.length > 0) {
          for (const iface of cls.implements) {
            edges.push({
              source: clsId,
              target: iface,
              type: 'implements',
              weight: 0.9,
              metadata: { relationship: 'implements' }
            });
          }
        }
      }
    }

    // Create service nodes for Dockerfile services
    if (result.services) {
      for (const svc of result.services) {
        if (svc.name && svc.name !== 'builder') {
          const svcId = 'service:' + fpath + ':' + svc.name;
          nodes.push({
            id: svcId,
            type: 'service',
            name: svc.name,
            filePath: fpath,
            summary: '服务 ' + svc.name + ' (镜像: ' + (svc.image || 'unknown') + ')',
            tags: ['service', 'docker'],
            complexity: 'simple'
          });
        }
      }
    }

    // Create import edges from resolved imports
    if (result.imports && result.imports.length > 0) {
      for (const imp of result.imports) {
        // Try to resolve the import to a project file path
        // For Java imports like 'interview.guide.common.Result'
        if (typeof imp === 'object' && imp.source) {
          edges.push({
            source: fileId,
            target: imp.source,
            type: 'imports',
            weight: 0.7,
            metadata: { relationship: 'imports' }
          });
        }
      }
    }
  }

  // Write batch output file
  const batchFile = path.join(intermediateDir, 'batch-' + batchIdx + '.json');
  fs.writeFileSync(batchFile, JSON.stringify({ nodes, edges }, null, 2));
  console.log('Batch ' + batchIdx + ': ' + nodes.length + ' nodes, ' + edges.length + ' edges -> ' + batchFile);
}

console.log('\nAll conversions complete.');
