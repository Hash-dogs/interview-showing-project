const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const projectRoot = 'd:/coding_world/javaguide_project/interviewer_showing_type/interviewer-project-master';
const skillDir = 'C:/Users/14207/.claude/plugins/cache/understand-anything/understand-anything/2.7.6/skills/understand';

const intermediateDir = path.join(projectRoot, '.understand-anything/intermediate');

// Get all extract input files
const inputFiles = fs.readdirSync(intermediateDir)
  .filter(f => f.startsWith('extract-input-batch-') && f.endsWith('.json'))
  .sort((a, b) => {
    const na = parseInt(a.match(/batch-(\d+)/)[1]);
    const nb = parseInt(b.match(/batch-(\d+)/)[1]);
    return na - nb;
  });

console.log('Found ' + inputFiles.length + ' batch input files to process\n');

let totalFiles = 0;
let totalSkipped = 0;

for (const inputFile of inputFiles) {
  const match = inputFile.match(/batch-(\d+)/);
  const batchIdx = match[1];
  const outputFile = 'extract-output-batch-' + batchIdx + '.json';
  const inputPath = path.join(intermediateDir, inputFile);
  const outputPath = path.join(intermediateDir, outputFile);

  console.log('Processing batch ' + batchIdx + '...');

  try {
    const result = execSync(
      'node "' + skillDir + '/extract-structure.mjs" "' + inputPath + '" "' + outputPath + '"',
      { cwd: projectRoot, timeout: 60000, encoding: 'utf8' }
    );

    // Read output to get stats
    const output = JSON.parse(fs.readFileSync(outputPath, 'utf8'));
    totalFiles += output.filesAnalyzed || 0;
    if (output.filesSkipped && output.filesSkipped.length > 0) {
      totalSkipped += output.filesSkipped.length;
    }
    console.log('  Done: ' + (output.filesAnalyzed || 0) + ' files analyzed');
  } catch (err) {
    console.error('  Error processing batch ' + batchIdx + ': ' + err.message.substring(0, 100));
  }
}

console.log('\n=== Summary ===');
console.log('Total batches processed: ' + inputFiles.length);
console.log('Total files analyzed: ' + totalFiles);
console.log('Total files skipped: ' + totalSkipped);
