const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the actual injected functions without needing a network or a desktop browser.
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/tv-navigation.js'), 'utf8');
const context = {URL, location: new URL('https://farsiland.com/episodes/test-ep01/'),
    q: (selector, element) => element.form || null};
function injectedFunction(name) {
    const match = source.match(new RegExp('    function ' + name + '\\([^\\n]*\\) \\{[\\s\\S]*?\\n    \\}'));
    assert(match, 'Injected function exists: ' + name);
    return vm.runInNewContext('(' + match[0] + ')', context);
}
const knownUrl = context.knownUrl = injectedFunction('knownUrl');
const checkPlayerLink = injectedFunction('checkPlayerLink');

const playerHosts = ['farsiplex.com', 'www.farsiplex.com', 'farsiland.buzz', 'www.farsiland.buzz'];
const rejected = ['http://farsiplex.com/smart-play', 'https://farsiplex.com:8443/smart-play',
    'https://farsiplex.com.evil.example/smart-play', 'https://evilfarsiplex.com/smart-play',
    'https://player.farsiplex.com/smart-play', 'https://www.www.farsiplex.com/smart-play',
    'https://farsiplex.com./smart-play', 'https://farsiplex.com@evil.example/smart-play',
    'https://user:password@farsiplex.com/smart-play', 'https://example.com/smart-play',
    'javascript:void(0)', 'data:text/html,test'];

test('initialization and player detection accept both exact player domains', () => {
    const prefix = source.slice(0, source.indexOf("    var catalogue =")) + 'return {playerSite: playerSite};\n}';
    const init = vm.runInNewContext('(' + prefix + ')', context);
    context.document = {body: {}, readyState: 'complete'};
    context.window = {}; context.window.top = context.window;
    for (const host of playerHosts) {
        context.location = new URL('https://' + host + '/smart-play');
        assert.equal(init().playerSite, true, host);
    }
    context.location = new URL('https://farsiland.com/home');
    assert.equal(init().playerSite, false);
    for (const value of rejected.filter(x => !x.startsWith('javascript:') && !x.startsWith('data:'))) {
        context.location = new URL(value);
        assert.equal(init(), false, value);
    }
});

test('known links and POST forms accept exact player hosts without modifying the handoff', () => {
    for (const host of playerHosts) {
        const target = 'https://' + host + '/smart-play';
        assert.equal(knownUrl(target), true);
        assert.equal(checkPlayerLink({href: target}), true);
        const form = Object.freeze({action: target, method: 'post', payload: 'selected-video-fixture'});
        const watch = Object.freeze({href: '', form});
        assert.equal(checkPlayerLink(watch), true, host);
        assert.equal(form.payload, 'selected-video-fixture');
        assert.equal(form.method, 'post');
    }
    assert.equal(checkPlayerLink({href: 'https://farsiland.com/episodes/test-ep01/'}), false);
});

test('unrelated and look-alike player URLs cannot gain app controls or bridge access', () => {
    for (const value of rejected) {
        assert.equal(knownUrl(value), false, value);
        assert.equal(checkPlayerLink({href: value}), false, value);
        assert.equal(checkPlayerLink({form: {action: value}}), false, value);
    }
});

test('resume uses the player API and waits for a confirmed seek through startup resets', () => {
    const video = {duration: 1800, currentTime: 0, seeking: false};
    const seeks = [];
    let now = 10000;
    context.playback = {resumeAt: 120};
    context.sourceVideo = () => video;
    context.getPlayerApi = () => ({seek: position => seeks.push(position)});
    context.Date = {now: () => now};
    context.seekTo = injectedFunction('seekTo');
    const apply = injectedFunction('applyResumePosition');
    apply();
    assert.deepEqual(seeks, [120]);
    assert.equal(context.playback.resumeAt, 120, 'A queued API command is not a confirmed seek');
    now += 500; apply();
    assert.equal(seeks.length, 1, 'Do not flood a buffering player with seek commands');
    now += 1500; apply();
    assert.deepEqual(seeks, [120, 120], 'Retry if initialization left the content at zero');
    video.currentTime = 120; video.seeking = true; apply();
    assert.equal(context.playback.resumeAt, 120);
    video.seeking = false; apply();
    assert.equal(context.playback.resumeAt, 0, 'A completed seek releases normal progress reporting');
});

test('HTML5 fallback waits for metadata and still restores a saved position', () => {
    const video = {duration: NaN, currentTime: 0, seeking: false};
    context.playback = {resumeAt: 90};
    context.sourceVideo = () => video;
    context.getPlayerApi = () => null;
    context.seekTo = injectedFunction('seekTo');
    const apply = injectedFunction('applyResumePosition');
    apply();
    assert.equal(video.currentTime, 0);
    assert.equal(context.playback.resumeAt, 90);
    video.duration = 1800;
    apply();
    assert.equal(video.currentTime, 90);
    apply();
    assert.equal(context.playback.resumeAt, 0);
});
