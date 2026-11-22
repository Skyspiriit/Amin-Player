const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const script = fs.readFileSync(path.join(root, 'app/src/main/assets/tv-navigation.js'), 'utf8');
const activity = fs.readFileSync(path.join(root, 'app/src/main/java/com/enigma/farsilandtv/MainActivity.kt'), 'utf8');

test('the player offers All Episodes next to the episode buttons, only when the series is known', () => {
    assert.match(script, /button\('☰ همه قسمت‌ها'/);
    assert.match(script, /\[nextEpisode, play, episodeList, previousEpisode, full, quality\]/);
    assert.match(script, /playUi\.episodes\.hidden = !\(playback && playback\.allEpisodes\)/);
    assert.match(activity, /put\("allEpisodes", item\?\.series\?\.url\?\.let\(::isSeriesPage\) == true/);
});

test('All Episodes saves the position, then opens the series list at the playing episode', () => {
    assert.match(script, /reportProgress\(true\);\s+postPlayback\(\{event: 'all-episodes'\}\)/);
    assert.match(activity, /"all-episodes" -> if \(resumed && hasWindowFocus\(\)\)/);
    assert.match(activity, /#fl-episodes=" \+ Uri\.encode\(item\.url\)/);
    assert.match(script, /\/\^#fl-episodes=\(\.\+\)\$\/\.exec\(location\.hash\)/);
});
