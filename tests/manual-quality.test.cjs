const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/tv-navigation.js'), 'utf8');
const extract = name => source.match(new RegExp('    function ' + name + '\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}'))[0];
const quality = vm.runInNewContext('(' + '() => {' + extract('closestQuality') + extract('startingQuality') +
    'return {closestQuality, startingQuality}; })()');

test('playback starts at the lowest quality the site offers', () => {
    assert.equal(quality.startingQuality([480, 720, 1080]), 480);
    assert.equal(quality.startingQuality([720, 1080]), 720);
    assert.equal(quality.startingQuality([360, 480, 720, 1080]), 360);
});

test("the viewer's own pick is used while she stays in the player", () => {
    assert.equal(quality.startingQuality([480, 720, 1080], 720), 720);
    assert.equal(quality.startingQuality([480, 720, 1080], 1080), 1080);
    // A pick the next episode does not offer falls back to the closest lower quality.
    assert.equal(quality.startingQuality([480, 720], 1080), 720);
    assert.equal(quality.startingQuality([480, 720, 1080], 600), 480);
});

test('quality never changes by itself', () => {
    for (const removed of ['watchBuffering', 'automaticQuality', 'adaptivePlayer', 'updateConnection',
        'quality-preference', 'connectionPolicy', "automatic: ", "buffering: "]) {
        assert.equal(source.includes(removed), false, removed + ' must not exist');
    }
    // askForQuality is defined once and called only from the quality menu.
    assert.equal(source.split('askForQuality(').length - 1, 2);
    const picker = extract('openQualityPicker');
    assert.match(picker, /askForQuality\(sourceVideo\(\), resolution\)/);
});

test('the quality menu has no Auto option and marks the quality that is playing', () => {
    const picker = extract('openQualityPicker');
    assert.equal(picker.includes('خودکار'), false);
    assert.match(picker, /aria-pressed', String\(playback\.quality === resolution\)/);
    assert.match(picker, /if \(resolution === playback\.quality\) return;/);
});
