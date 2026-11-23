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
    assert.match(activity, /INTRO_VOLUME = 0\.7f/);
    assert.match(activity, /override fun onPause\(\) \{\s+resumed = false\s+endIntro\(immediate = true\)/);
    assert.match(intro, /setUsage\(AudioAttributes\.USAGE_MEDIA\)/);
});

test('the intro is the light 2D molten logo: no video, no heavy art, short', () => {
    assert.match(activity, /IntroView\(this, mark, strike, INTRO_VOLUME\)/);
    assert.equal(/ANIMATOR_DURATION_SCALE|forceIntroMotion/.test(activity), false);
    assert.equal(fs.existsSync(path.join(root, 'app/src/main/res/raw/intro_forge_video.mp4')), false);
    assert.equal(fs.existsSync(path.join(root, 'app/src/main/java/com/enigma/farsilandtv/IntroVideoView.kt')), false);
    for (const file of ['res/raw/intro_forge.wav', 'res/drawable-nodpi/intro_mark.png', 'res/mipmap-anydpi-v26/ic_launcher.xml', 'res/drawable-xhdpi/tv_banner.png'])
        assert.ok(fs.existsSync(path.join(root, 'app/src/main', file)), file);
    const wav = fs.statSync(path.join(root, 'app/src/main/res/raw/intro_forge.wav')).size;
    assert.ok(wav < 160 * 1024, String(wav));
    const num = name => Number(intro.match(new RegExp('const val ' + name + ' = ([0-9]+)L'))[1]);
    const total = num('FILL_FROM') + num('EXPECTED_LATENCY') + num('LEAD') + num('HOLD');
    assert.ok(total >= 1800 && total <= 2400, String(total));
    assert.match(intro, /"AMIN PLAYER"/);
});

test('sync: the strike starts the sound file, and the finish is set from the audio output timestamp', () => {
    const wav = fs.readFileSync(path.join(root, 'app/src/main/res/raw/intro_forge.wav'));
    const data = wav.indexOf('data') + 8;
    let first = -1;
    for (let i = data; i + 1 < wav.length; i += 2) { if (Math.abs(wav.readInt16LE(i)) > 3000) { first = (i - data) / 2; break; } }
    assert.ok(first >= 0 && first < 44100 * 0.005, 'strike onset within 5 ms: ' + first);
    assert.match(intro, /getTimestamp\(stamp\)/);
    assert.match(intro, /impact = firstSample \+ ms\(LEAD\) - ms\(DISPLAY_LEAD\)/);
    assert.match(intro, /AudioTrack\.MODE_STATIC/);
    // Skipping before the strike silences it.
    assert.match(intro, /if \(System\.nanoTime\(\) < impact\) release\(\)/);
});

test('any remote key still skips the intro', () => {
    assert.match(activity, /if \(intro != null\) \{\s+if \(event\.action == KeyEvent\.ACTION_UP\) endIntro\(\)\s+return true/);
});
