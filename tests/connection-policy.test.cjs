const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/connection-policy.js'),'utf8');
const factory=vm.runInNewContext('('+source+')');
const policy=factory();
const playing={playing:true,seeking:false,waiting:false,actual:480,buffer:30,manual:null};

test('startup is conservative and uses the lowest available fallback',()=>{
 assert.equal(policy.initialQuality([480,720,1080]),480);
 assert.equal(policy.initialQuality([720,1080]),720);
 assert.equal(policy.initialQuality([360]),360);
 assert.equal(policy.initialQuality([2160,480,720,480]),480);
});
test('upgrades wait for stable playback and buffer, advancing one step at a time',()=>{
 const s=policy.session([480,720,1080,2160]);
 assert.equal(s.observe({...playing,now:0}),480);
 assert.equal(s.observe({...playing,now:89999}),480);
 assert.equal(s.observe({...playing,now:90000,buffer:4}),480);
 assert.equal(s.observe({...playing,now:90001}),720);
 assert.equal(s.observe({...playing,now:180001}),720,'Do not raise the cap again while 480p is still playing');
 assert.equal(s.observe({...playing,actual:720,now:180002}),1080);
 assert.equal(s.observe({...playing,actual:1080,now:300000}),1080);
});
test('stalls lower the ceiling and prevent rapid up/down oscillation',()=>{
 const s=policy.session([360,480,720,1080]);
 s.observe({...playing,now:0});s.observe({...playing,now:90000});
 assert.equal(s.observe({...playing,actual:720,now:100000,waiting:true,buffer:0}),480);
 assert.equal(s.observe({...playing,now:100001}),480);
 assert.equal(s.observe({...playing,now:279999}),480);
 assert.equal(s.observe({...playing,now:280000}),720);
});
test('manual selection and pause do not count as automatic upgrade readiness',()=>{
 const s=policy.session([480,720,1080]);
 assert.equal(s.observe({...playing,now:0,manual:1080}),1080);
 assert.equal(s.observe({...playing,now:100000,manual:1080}),1080);
 const a=policy.session([480,720]);
 a.observe({...playing,now:0});a.observe({...playing,now:80000,playing:false});
 assert.equal(a.observe({...playing,now:200000}),480);
 assert.equal(a.observe({...playing,now:289999}),480);
 assert.equal(a.observe({...playing,now:290000}),720);
});
test('only buffered media ahead of the current playhead counts',()=>{
 const video={currentTime:10,buffered:{length:2,start:i=>[0,30][i],end:i=>[5,100][i]}};
 assert.equal(policy.bufferedAhead(video),0);
 video.currentTime=3;assert.equal(policy.bufferedAhead(video),2);
 video.currentTime=35;assert.equal(policy.bufferedAhead(video),65);
});

const navigation=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/tv-navigation.js'),'utf8');
const adaptiveSource=navigation.match(/    function adaptivePlayer\(video\) \{[\s\S]*?\n    \}/)[0];
test('startup waiting is not a playback stall and quick pause/seek events reach the policy',()=>{
 const observe=[];let now=1000;
 const playback={started:false,preferredQuality:null};
 const engine={id:{},heights:[480,720],apply(){}};
 const fakePolicy={session:()=>({observe:s=>{observe.push(s);return 480;}}),bufferedAhead:()=>30};
 const update=vm.runInNewContext('('+navigation.match(/    function updateConnection\(video, event\) \{[\s\S]*?\n    \}/)[0]+')',{
  playback,Date:{now:()=>now},adaptivePlayer:()=>engine,connectionPolicy:fakePolicy,
  closestQuality:()=>480,postPlayback(){},document:{hidden:false}
 });
 const video={videoHeight:480,readyState:0,paused:false,seeking:false};
 update(video,'waiting');assert.equal(observe.at(-1).waiting,false);
 playback.started=true;video.readyState=3;now=2000;update(video,'playing');
 now=2100;video.paused=true;update(video,'pause');assert.equal(observe.at(-1).playing,false);
 now=2200;video.paused=false;video.seeking=true;update(video,'seeking');assert.equal(observe.at(-1).seeking,true);
 now=2300;video.seeking=false;update(video,'waiting');assert.equal(observe.at(-1).waiting,true);
});
test('HLS upgrades affect future loading without flushing or replacing media',()=>{
 const video={};let cap,load;
 const hls={media:video,levels:[{height:480},{height:720},{height:1080}]};
 Object.defineProperty(hls,'currentLevel',{set(){throw Error('A playing buffer must not be flushed');}});
 Object.defineProperty(hls,'autoLevelCapping',{set(v){cap=v;}});
 Object.defineProperty(hls,'loadLevel',{set(v){load=v;}});
 const find=vm.runInNewContext('('+adaptiveSource+')',{window:{hls},connectionPolicy:policy});
 find(video).apply(720,false);
 assert.equal(cap,1);assert.equal(load,-1);
 find(video).apply(1080,true);assert.equal(cap,2);assert.equal(load,2);
});
test('Video.js MP4 does not gain speculative adaptive behavior',()=>{
 const video={};
 const player={el:()=>({contains:v=>v===video}),currentType:()=> 'video/mp4',tech:()=>{throw Error('Do not treat MP4 as adaptive');}};
 const videojs=function(){};videojs.getPlayers=()=>({main:player});
 const find=vm.runInNewContext('('+adaptiveSource+')',{window:{videojs},connectionPolicy:policy});
 assert.equal(find(video),null);
});
test('Video.js automatic upgrades cap rendition selection without flushing the buffer',()=>{
 const video={};let enables=0,fast=0;
 const playlist=h=>({attributes:{RESOLUTION:{height:h},BANDWIDTH:h*1000}});
 const playlists=[playlist(480),playlist(720),playlist(1080)];
 const reps=[480,720,1080].map(height=>({height,enabled:function(){if(arguments.length)enables++;return true;}}));
 let abrChoice=playlists[2];
 const vhs={representations:()=>reps,playlists:{main:{playlists}},playlistController_:{fastQualityChange_:()=>fast++}};
 vhs.selectPlaylist=function(){return abrChoice;};
 const player={el:()=>({contains:v=>v===video}),currentType:()=> 'application/x-mpegURL',tech:()=>({vhs})};
 const videojs=function(){};videojs.getPlayers=()=>({main:player});
 const find=vm.runInNewContext('('+adaptiveSource+')',{window:{videojs},connectionPolicy:policy,Date});
 const engine=find(video);
 engine.apply(480,false,false);
 assert.equal(vhs.selectPlaylist().attributes.RESOLUTION.height,480,'ABR choice above the cap is limited');
 abrChoice=playlists[0];assert.equal(vhs.selectPlaylist(),playlists[0],'ABR choices within the cap are kept');
 abrChoice=playlists[2];engine.apply(720,false,false);
 assert.equal(vhs.selectPlaylist().attributes.RESOLUTION.height,720,'A raised cap applies at the next selection');
 assert.equal(fast,0,'Automatic upgrades never reset the buffer');assert.equal(enables,0,'Renditions are never toggled');
 engine.apply(1080,true,true);
 assert.equal(fast,1,'A viewer-selected quality switches immediately');
 abrChoice=playlists[0];assert.equal(vhs.selectPlaylist().attributes.RESOLUTION.height,1080,'Manual quality is held');
});
