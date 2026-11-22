const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const script = read('app/src/main/assets/tv-navigation.js');
const css = read('app/src/main/assets/tv.css');
const activity = read('app/src/main/java/com/enigma/farsilandtv/MainActivity.kt');
const source = 'app/src/main/java/com/enigma/farsilandtv/';

test('the home menu has two separate VPN buttons: power and settings', () => {
    assert.match(script, /vpnPower = withIcon\(button\('', function \(\) \{[\s\S]*?postPlayback\(\{event: 'vpn-power'\}\);\s*\}\), 'power', 'VPN قطع'\)/);
    assert.match(script, /vpnSettings = withIcon\(button\('', function \(\) \{ postPlayback\(\{event: 'vpn'\}\); \}\), 'vpnSettings', 'تنظیمات VPN'\)/);
    // The power button never opens settings and the settings button never connects.
    const power = /vpnPower = withIcon\(button\('', function \(\) \{([\s\S]*?)\}\), 'power'/.exec(script)[1];
    assert.doesNotMatch(power, /event: 'vpn'\}/);
    assert.match(activity, /"vpn" -> if \(resumed\) openVpnSettings\(\)/);
    assert.match(activity, /"vpn-power" -> if \(resumed\) vpnPower\(\)/);
    assert.doesNotMatch(activity, /CONNECT_REQUEST/);
});

test('the power button keeps its meaning in every theme: red, amber, green, with words', () => {
    assert.match(css, /\.fl-vpn-power,\s*html\[data-fl-tv\] \.fl-toolbar \.fl-vpn-power\[data-fl-focus\] \{\s*--fl-vpn-a: #e0503f;/);
    assert.match(css, /\.fl-vpn-power\[data-fl-vpn="connecting"\],\s*html\[data-fl-tv\] \.fl-toolbar \.fl-vpn-power\[data-fl-vpn="stopping"\] \{\s*--fl-vpn-a: #f6c052;/);
    assert.match(css, /\.fl-vpn-power\[data-fl-vpn="on"\] \{\s*--fl-vpn-a: #36c879;/);
    assert.match(script, /var words = \{ON: 'VPN وصل', CONNECTING: 'در حال اتصال', STOPPING: 'در حال قطع'\}/);
    assert.match(script, /if \(vpnPower\.dataset\.flVpn === 'stopping'\) return;/);
});

test('only review builds can be inspected; VPN code never logs', () => {
    assert.match(activity, /if \(resources\.getBoolean\(R\.bool\.amin_allow_screenshots\)\) WebView\.setWebContentsDebuggingEnabled\(true\)/);
    for (const file of ['AminVpnService.kt', 'VpnProfiles.kt', 'VpnSecrets.kt', 'VpnSetupActivity.kt', 'PlatformVpn.kt',
        'LocalVpnImportActivity.kt', 'VpnEngine.kt', 'VpnState.kt']) {
        const text = read(source + file);
        assert.doesNotMatch(text, /\bLog\.[vdiwe]\(|printStackTrace|println\(/, file);
    }
});

test('free methods are never built into the app', () => {
    for (const file of fs.readdirSync(path.join(root, source))) {
        assert.doesNotMatch(read(source + file), /vless:\/\/[0-9a-f-]{8,}|ss:\/\/[A-Za-z0-9]/, file);
    }
});
