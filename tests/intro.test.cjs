const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const script = read('app/src/main/assets/tv-navigation.js');
const activity = read('app/src/main/java/com/enigma/farsilandtv/MainActivity.kt');
const intro = read('app/src/main/java/com/enigma/farsilandtv/IntroVideoView.kt');

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

test('the intro is the pre-rendered film, always plays and the bundled assets exist', () => {
    assert.match(activity, /IntroVideoView\(this, onSound = \{/);
    assert.equal(/ANIMATOR_DURATION_SCALE|forceIntroMotion/.test(activity), false);
    for (const file of ['res/raw/intro_forge.wav', 'res/raw/intro_forge_video.mp4', 'res/drawable-nodpi/intro_mark.png', 'res/mipmap-anydpi-v26/ic_launcher.xml', 'res/drawable-xhdpi/tv_banner.png'])
        assert.ok(fs.existsSync(path.join(root, 'app/src/main', file)), file);
    const film = fs.statSync(path.join(root, 'app/src/main/res/raw/intro_forge_video.mp4')).size;
    assert.ok(film < 6 * 1024 * 1024, String(film));
    assert.match(intro, /setVolume\(0f, 0f\)/);   // the film is silent; the strike sound follows the sound switch
});

test('Golden Forge: the strike in the sound file lands on the strike in the film', () => {
    const wav = fs.readFileSync(path.join(root, 'app/src/main/res/raw/intro_forge.wav'));
    const rate = wav.readUInt32LE(24);
    const data = wav.indexOf('data') + 8;
    let loud = 0, at = 0;
    for (let i = data; i + 1 < wav.length; i += 2) { const v = Math.abs(wav.readInt16LE(i)); if (v > loud) { loud = v; at = (i - data) / 2; } }
    assert.ok(Math.abs(at / rate - 0.40) < 0.015, String(at / rate));
    assert.match(intro, /const val IMPACT_MS = 1180/);
    assert.match(intro, /const val SOUND_AT_MS = IMPACT_MS - SOUND_LEAD_MS/);
    // The sound is started from the player's own position, not a separate clock.
    assert.match(intro, /p\.currentPosition/);
});

test('Golden Forge: any remote key still skips the intro', () => {
    assert.match(activity, /if \(intro != null\) \{\s+if \(event\.action == KeyEvent\.ACTION_UP\) endIntro\(\)\s+return true/);
});
