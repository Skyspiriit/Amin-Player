const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/tv-navigation.js'), 'utf8');
const extract = name => source.match(new RegExp('    function ' + name + '\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}'))[0];
const context = vm.createContext({URL, catalogue: 'https://farsiland.com', location: {href: 'https://farsiland.com/home'}});
vm.runInContext(['knownUrl', 'recommendationKey', 'recommendationGroups', 'latinDigits', 'labelledEpisodeNumbers',
    'persianNumber', 'playbackCaption'].map(extract).join('\n'), context);
const clean = value => JSON.parse(JSON.stringify(value));
const day = 86400000, now = Date.UTC(2026, 10, 25, 18);
const item = (slug, type='movies') => ({url: `https://farsiland.com/${type}/${slug}/`, title: slug});

test('publication, modification, release dates and first observation never make an old link fresh', () => {
    const old = {...item('old'), datePublished: now, dateModified: now, uploadDate: now, firstSeen: now, year: 2026};
    const groups = clean(context.recommendationGroups([old], null, now));
    assert.deepEqual(groups, {recent: [], week: [], suggestions: [old]});
    assert.match(source, /function verifiedLinkAdditions\(\) \{ return null; \}/);
});

test('verified UTC windows are disjoint, newest first, and include the exact boundaries', () => {
    const pool = ['week', 'old', '48h', 'new', '7days', 'future'].map(s => item(s));
    const dates = Object.fromEntries(pool.map((p,i) => [p.url, [now-3*day,now-7*day-1,now-2*day,now-1000,now-7*day,now+1][i]]));
    const groups = clean(context.recommendationGroups(pool, dates, now));
    assert.deepEqual(groups.recent.map(x=>x.title), ['new', '48h']);
    assert.deepEqual(groups.week.map(x=>x.title), ['week', '7days']);
    assert.deepEqual(groups.suggestions.map(x=>x.title), ['old', 'future']);
});

test('one canonical link appears in only one group; series landing pages are not dated playable links', () => {
    const a = item('same'), series = item('series', 'tvshows');
    const groups = clean(context.recommendationGroups([a, {...a,url:'https://www.farsiland.com/movies/same?ref=home'}, series,
        {url:'https://farsiland.com.evil.test/movies/other/',title:'bad'}], {[a.url]:now,[series.url]:now}, now));
    assert.deepEqual(groups.recent, [a]);
    assert.deepEqual(groups.suggestions, [series]);
    assert.equal(groups.week.length, 0);
});

test('empty date groups create no DOM or request work', () => {
    const empty = vm.createContext({make(){throw Error('must not create empty heading')}});
    vm.runInContext(extract('mountDatedRecommendations'), empty);
    empty.mountDatedRecommendations([], 'تازه‌های هفته', 'fixture', null);
});

test('episode numbers come from explicit labels in Persian, Arabic or English', () => {
    for (const label of ['SE02 EP14', 'Season 2 Episode 14', 'S02E14', 'فصل ۲، قسمت ۱۴', 'فصل ٢ قسمت ١٤'])
        assert.deepEqual(clean(context.labelledEpisodeNumbers(label)), {season:2,episode:14}, label);
    assert.deepEqual(clean(context.labelledEpisodeNumbers('Roshan Episode 12')), {season:null,episode:12});
    assert.deepEqual(clean(context.labelledEpisodeNumbers('فیلم ۲۰۲۶ با کیفیت ۱۰۸۰')), {season:null,episode:null});
});

test('caption supports long mixed titles and omits unknown season/year instead of inventing values', () => {
    assert.deepEqual(clean(context.playbackCaption({kind:'episode',title:'  سریال بلند Persian English  ',episode:14})),
        {title:'سریال بلند Persian English',detail:'قسمت ۱۴',episodeTitle:''});
    assert.equal(context.playbackCaption({kind:'episode',title:''}), null);
    assert.equal(context.playbackCaption({kind:'player',title:'Smart Play'}), null);
    assert.equal(context.playbackCaption({kind:'movie',title:'سقف',year:2026}).detail, '۲۰۲۶');
    assert.equal(context.playbackCaption({kind:'movie',title:'سقف'}).detail, '');
});

test('overlay hides after five seconds, can be revealed again, and never changes remote focus', () => {
    const classes = new Set(), attrs = {};
    let callback, delay, cleared=0;
    const focus = {id:'fl-play'};
    const clock = vm.createContext({
        playback: {titleInfo:{kind:'episode',title:'نام سریال',season:2,episode:14,episodeTitle:'یک روز تازه'}},
        playUi: {info:{classList:{add:x=>classes.add(x),remove:x=>classes.delete(x)},setAttribute:(k,v)=>attrs[k]=v},
            infoTitle:{},infoDetail:{},infoEpisode:{}},
        current:focus, playbackTitleTimer:0, overlay:()=>null,
        window:{setTimeout:(fn,ms)=>{callback=fn;delay=ms;return 1},clearTimeout:()=>cleared++}
    });
    vm.runInContext(['persianNumber','playbackCaption','hidePlaybackTitle','revealPlaybackTitle'].map(extract).join('\n'),clock);
    clock.revealPlaybackTitle();
    assert.equal(delay,5000);
    assert.equal(clock.playUi.infoDetail.textContent,'فصل ۲ · قسمت ۱۴');
    assert.equal(classes.has('fl-title-visible'),true);
    callback();
    assert.equal(attrs['aria-hidden'],'true');
    clock.revealPlaybackTitle();
    assert.equal(classes.has('fl-title-visible'),true);
    assert.equal(clock.current,focus);
    clock.overlay=()=>({});
    clock.revealPlaybackTitle();
    assert.equal(classes.has('fl-title-visible'),false);
    assert.ok(cleared>=3);
});
