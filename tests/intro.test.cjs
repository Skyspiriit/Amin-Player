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

test('the intro lasts about 2.5 s, always animates and the bundled assets exist', () => {
    const end = Number(intro.match(/const val END = (\d+)L/)[1]);
    assert.ok(end >= 2000 && end <= 3000, String(end));
    assert.match(activity, /IntroView\(this, mark, reducedMotion = false/);
    assert.equal(/ANIMATOR_DURATION_SCALE|forceIntroMotion/.test(activity), false);
    for (const file of ['res/raw/intro_forge.wav', 'res/drawable-nodpi/intro_mark.png', 'res/mipmap-anydpi-v26/ic_launcher.xml', 'res/drawable-xhdpi/tv_banner.png'])
        assert.ok(fs.existsSync(path.join(root, 'app/src/main', file)), file);
});

test('Golden Forge (1.35): anvil, hammer and metal art exist and stay small', () => {
    let total = 0;
    for (const name of ['forge_anvil', 'forge_hammer', 'forge_ingot']) {
        const file = path.join(root, 'app/src/main/res/drawable-nodpi', name + '.webp');
        assert.ok(fs.existsSync(file), name);
        total += fs.statSync(file).size;
    }
    assert.ok(total < 120 * 1024, String(total));
    for (const id of ['R.drawable.forge_anvil', 'R.drawable.forge_hammer', 'R.drawable.forge_ingot', 'R.drawable.intro_mark'])
        assert.ok(intro.includes(id) || activity.includes(id), id);
    assert.match(intro, /private const val WORD = "AMIN PLAYER"/);
});

test('Golden Forge: the strike in the sound file lands on the hammer impact', () => {
    const wav = fs.readFileSync(path.join(root, 'app/src/main/res/raw/intro_forge.wav'));
    const rate = wav.readUInt32LE(24);
    const data = wav.indexOf('data') + 8;
    let loud = 0, at = 0;
    for (let i = data; i + 1 < wav.length; i += 2) { const v = Math.abs(wav.readInt16LE(i)); if (v > loud) { loud = v; at = (i - data) / 2; } }
    const strike = at / rate;
    assert.ok(Math.abs(strike - 0.40) < 0.015, String(strike));
    assert.ok(wav.length < 260 * 1024, String(wav.length));
    const impact = Number(intro.match(/const val IMPACT = (\d+)L/)[1]);
    const lead = Number(intro.match(/const val SOUND_LEAD = (\d+)L/)[1]);
    assert.match(intro, /const val SOUND_AT = IMPACT - SOUND_LEAD/);
    assert.ok(lead >= 400 && lead <= 460, String(lead));
    assert.ok(impact - lead > 300, 'the sound must not start before the scene has faded in');
});

test('Golden Forge: any remote key still skips the intro', () => {
    assert.match(activity, /if \(intro != null\) \{\s+if \(event\.action == KeyEvent\.ACTION_UP\) endIntro\(\)\s+return true/);
});
