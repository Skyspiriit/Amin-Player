const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const script = fs.readFileSync(path.join(root, 'app/src/main/assets/tv-navigation.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'app/src/main/assets/tv.css'), 'utf8');
const kotlin = fs.readFileSync(path.join(root, 'app/src/main/java/com/enigma/farsilandtv/AppThemes.kt'), 'utf8');

const jsIds = [...script.match(/var themes = \[([\s\S]*?)\];/)[1].matchAll(/id: '([a-z]+)'/g)].map(m => m[1]);
const jsLegacy = Object.fromEntries([...script.match(/var legacyThemes = \{([\s\S]*?)\};/)[1].matchAll(/([a-z]+): '([a-z]+)'/g)].map(m => [m[1], m[2]]));
const ktIds = [...kotlin.match(/val ids = listOf\(([^)]*)\)/)[1].matchAll(/"([a-z]+)"/g)].map(m => m[1]);
const ktLegacy = Object.fromEntries([...kotlin.match(/private val legacy = mapOf\(([\s\S]*?)\)\r?\n/)[1].matchAll(/"([a-z]+)" to "([a-z]+)"/g)].map(m => [m[1], m[2]]));

test('three themes, gold cinema first (the default for fresh installs), same list in the page and the app', () => {
    assert.deepEqual(jsIds, ['cinema', 'turquoise', 'pomegranate']);
    assert.deepEqual(ktIds, jsIds);
    assert.match(script, /name: 'سینمای طلایی'/);
    assert.match(script, /name: 'شب فیروزه‌ای'/);
    assert.match(script, /name: 'انار مخملی'/);
});

test('every older saved theme maps to the same new theme in the page and in the app', () => {
    assert.deepEqual(jsLegacy, ktLegacy);
    assert.equal(Object.keys(jsLegacy).length, 13);
    for (const target of Object.values(jsLegacy)) assert.ok(jsIds.includes(target), target);
});

test('the stylesheet defines exactly the three themes, each with the full set of colours', () => {
    const defined = [...css.matchAll(/\[data-fl-theme="([a-z]+)"\]/g)].map(m => m[1]);
    assert.deepEqual([...new Set(defined)].sort(), [...jsIds].sort());
    for (const id of ['turquoise', 'pomegranate']) {
        const block = css.match(new RegExp('\\[data-fl-theme="' + id + '"\\] \\{([\\s\\S]*?)\\n\\}'))[1];
        for (const token of ['--fl-bg:', '--fl-bg-a90:', '--fl-panel:', '--fl-raised:', '--fl-raised-hi:', '--fl-text:', '--fl-muted:',
            '--fl-accent:', '--fl-focus:', '--fl-on-focus:', '--fl-glow:', '--fl-light:']) assert.ok(block.includes(token), id + ' ' + token);
    }
    assert.equal(/--fl-art\b/.test(css), false, 'old fantasy art layers are gone');
});

test('motion respects the reduced-motion setting', () => {
    assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
});

test('recommendations rotate every 12 seconds and page in groups of four', () => {
    assert.match(script, /var REC_INTERVAL = 12000;/);
    assert.match(script, /size: 4/);
    // Rotation only runs while focus is in the top menu, never inside the row, a dialog or a hidden app.
    const tick = script.match(/function recTick\(\) \{[\s\S]*?\n    \}/)[0];
    for (const guard of ['recs.row.contains(current)', 'toolbar.contains(current)', 'document.hidden', 'overlay()']) assert.ok(tick.includes(guard), guard);
});
