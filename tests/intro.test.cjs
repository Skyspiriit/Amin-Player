const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const script = read('app/src/main/assets/tv-navigation.js');
const activity = read('app/src/main/java/com/enigma/farsilandtv/MainActivity.kt');
const intro = read('app/src/main/java/com/enigma/farsilandtv/IntroView.kt');

test('the settings dialog offers both Persian startup switches and saves them through the app', () => {
    assert.match(script, /'انیمیشن آغاز برنامه'/);
    assert.match(script, /'صدای آغاز برنامه'/);
    assert.match(script, /postPlayback\(\{event: 'startup', animation: intro\.animation, sound: intro\.sound\}\)/);
    assert.match(activity, /event == "startup"/);
});

test('both switches default to on and the intro only runs on a fresh launch', () => {
    assert.match(activity, /getBoolean\(INTRO_ANIMATION, true\)/);
    assert.match(activity, /getBoolean\(INTRO_SOUND, true\)/);
    assert.match(activity, /savedInstanceState == null && playbackSettings\.getBoolean\(INTRO_ANIMATION, true\)\) startIntro\(\)/);
});

test('the impact uses the media stream at a moderate level and stops when the app is left', () => {
    assert.match(activity, /AudioAttributes\.USAGE_MEDIA/);
    assert.match(activity, /INTRO_VOLUME = 0\.7f/);
    assert.match(activity, /override fun onPause\(\) \{\s+resumed = false\s+introSound\?\.autoPause\(\)\s+endIntro\(immediate = true\)/);
});

test('the intro lasts about 2.5 s, honours reduced motion and the bundled assets exist', () => {
    const end = Number(intro.match(/const val END = (\d+)L/)[1]);
    assert.ok(end >= 2000 && end <= 3000, String(end));
    assert.match(activity, /ANIMATOR_DURATION_SCALE/);
    for (const file of ['res/raw/intro_forge.wav', 'res/drawable-nodpi/intro_mark.png', 'res/mipmap-anydpi-v26/ic_launcher.xml', 'res/drawable-xhdpi/tv_banner.png'])
        assert.ok(fs.existsSync(path.join(root, 'app/src/main', file)), file);
});
