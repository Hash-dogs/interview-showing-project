const fs = require('fs');
const path = require('path');

const projectRoot = 'd:/coding_world/javaguide_project/interviewer_showing_type/interviewer-project-master';
const skillDir = 'C:/Users/14207/.claude/plugins/cache/understand-anything/understand-anything/2.7.6/skills/understand';

const batches = JSON.parse(fs.readFileSync(
  path.join(projectRoot, '.understand-anything/intermediate/batches.json'), 'utf8'));

for (const batch of batches.batches) {
  const idx = batch.batchIndex;
  const input = {
    projectRoot: projectRoot,
    batchFiles: batch.files,
    batchImportData: batch.batchImportData
  };
  const inputPath = path.join(projectRoot, '.understand-anything/intermediate/extract-input-batch-' + idx + '.json');
  fs.writeFileSync(inputPath, JSON.stringify(input, null, 2));
  console.log('Created input for batch ' + idx + ' (' + batch.files.length + ' files)');
}
console.log('\nTotal: ' + batches.batches.length + ' batch inputs created');
