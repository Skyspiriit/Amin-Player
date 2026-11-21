const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const policy=require('../docs/drive-import/drive-policy.js');
const meta={name:'یادداشت سفر',size:'12',mimeType:'application/octet-stream',capabilities:{canDownload:true}};
test('the phone bridge accepts exact private HTTPS origins and rejects public/look-alike origins',()=>{
 for(const origin of ['https://192.168.1.152:9000','https://10.0.0.4:12345','https://172.16.3.4:54321','https://127.0.0.1:4000'])assert(policy.localOrigin(origin));
 for(const origin of ['http://192.168.1.152:9000','https://192.168.1.152.evil.example:9000','https://example.com:9000','https://172.32.0.1:9000','https://192.168.1.152:9000/path','https://user@192.168.1.152:9000','https://192.168.1.152','https://0x7f000001:9000'])assert(!policy.localOrigin(origin),origin);
});
test('the receiver binds files to the exact popup, origin, nonce and live session',()=>{
 const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/vpn-import.html'),'utf8');
 const source=html.match(/function acceptDriveMessage\(event,state,now\)\{[\s\S]*?\n\}/)[0];
 const accept=vm.runInNewContext('('+source+')',{PICKER_ORIGIN:'https://skyspiriit.github.io'});
 const popup={},state={popup,nonce:'fixture-session',deadline:1000},event={origin:'https://skyspiriit.github.io',source:popup,data:{session:'fixture-session'}};
 assert(accept(event,state,100));
 assert(!accept({...event,origin:'https://skyspiriit.github.io.evil.example'},state,100));
 assert(!accept({...event,source:{}},state,100));assert(!accept({...event,data:{session:'old'}},state,100));assert(!accept(event,state,1000));assert(!accept(event,null,100));
});
test('arbitrary names and binary MIME labels are allowed; size and actual availability determine download eligibility',()=>{
 assert.equal(policy.metadata(meta).name,'یادداشت سفر');
 assert.equal(policy.metadata({...meta,name:'anything without a suffix'}).size,12);
 for(const change of [{size:'0'},{size:String(policy.MAX_BYTES+1)},{trashed:true},{capabilities:{canDownload:false}},{mimeType:'application/vnd.google-apps.folder'},{mimeType:'application/vnd.google-apps.document'}])assert.throws(()=>policy.metadata({...meta,...change}));
});
test('only the explicitly picked ID is downloaded; there is no folder listing or parent/name restriction',async()=>{
 const calls=[];const value=Buffer.from('fixture text');
 const file=await policy.download('fixture-token','picked_file-123',async(url,options)=>{
  calls.push({url,options});return calls.length===1?Response.json({...meta,size:String(value.length)}):new Response(value);
 });
 assert.equal(file.name,'یادداشت سفر');assert.equal(Buffer.from(file.bytes).toString(),'fixture text');assert.equal(calls.length,2);
 for(const call of calls){assert(call.url.startsWith('https://www.googleapis.com/drive/v3/files/picked_file-123?'));assert.equal(call.options.headers.Authorization,'Bearer fixture-token');assert.equal(call.options.redirect,'error');assert.equal(call.options.credentials,'omit');assert(!call.url.includes('fixture-token'));}
 let requested=false;await assert.rejects(policy.download('fixture-token','../other',async()=>{requested=true;}));assert(!requested);
});
test('expired, denied, revoked and unavailable Drive access return bounded error categories',async()=>{
 for(const [status,code]of [[401,'EXPIRED'],[403,'DENIED'],[404,'UNAVAILABLE'],[410,'UNAVAILABLE'],[429,'NETWORK']])await assert.rejects(policy.download('fixture','picked',async()=>new Response('',{status})),error=>error.code===code);
 await assert.rejects(policy.download('fixture','picked',async()=>{throw Error('untrusted response details');}),error=>error.code==='NETWORK'&&!error.message.includes('details'));
});
test('a file enlarged after selection is stopped at the byte limit and staged chunks are cleared',async()=>{
 let calls=0,cancelled=false;const first=new Uint8Array(policy.MAX_BYTES).fill(65),extra=new Uint8Array(1).fill(66);
 const media=new Response(new ReadableStream({start(controller){controller.enqueue(first);controller.enqueue(extra);},cancel(){cancelled=true;}}));
 await assert.rejects(policy.download('fixture','picked',async()=>++calls===1?Response.json({...meta,size:'100'}):media),error=>error.code==='SIZE');
 assert(cancelled);assert(first.every(v=>v===0));assert(extra.every(v=>v===0));
});
test('the picker opens on the account\'s own Drive with every folder, then shared files and shared drives',async()=>{
 // Runs the real phone page script with a fake Google loader; the first tab must not be the shared-drives view,
 // which is empty for ordinary accounts (found in the live Google test of 1.31).
 const views=[],features=[];
 class DocsView{constructor(id){this.o={id}}setSelectFolderEnabled(v){this.o.selectFolder=v;return this}setIncludeFolders(v){this.o.folders=v;return this}
  setParent(v){this.o.parent=v;return this}setOwnedByMe(v){this.o.ownedByMe=v;return this}setEnableDrives(v){this.o.drives=v;return this}}
 const chain={};for(const name of ['setLocale','setOAuthToken','setDeveloperKey','setAppId','setOrigin','setTitle','setCallback','setSize'])chain[name]=function(){return this};
 class PickerBuilder{addView(v){views.push(v.o);return this}enableFeature(f){features.push(f);return this}build(){return {setVisible(){},dispose(){}}}}
 Object.assign(PickerBuilder.prototype,chain);
 const choose={disabled:true,addEventListener(type,fn){this.click=fn}},status={textContent:''},listeners={};
 const context={AminDrivePolicy:policy,AminGoogle:{clientId:'1-a.apps.googleusercontent.com',pickerKey:'key',projectNumber:'1'},
  location:{origin:'https://skyspiriit.github.io'},innerWidth:400,innerHeight:700,setInterval:()=>0,clearInterval(){},setTimeout:()=>0,clearTimeout(){},
  opener:{postMessage(){},closed:false},addEventListener(type,fn){listeners[type]=fn},
  document:{getElementById:id=>({choose,status,cancel:{addEventListener(){}}})[id],createElement:()=>({}),head:{appendChild(s){setImmediate(()=>s.onload())}}},
  gapi:{load(name,o){setImmediate(o.callback)}},
  google:{picker:{DocsView,PickerBuilder,ViewId:{DOCS:'docs'},Feature:{SUPPORT_DRIVES:'drives'},Action:{PICKED:'picked',CANCEL:'cancel'}},
   accounts:{oauth2:{hasGrantedAllScopes:()=>true,initTokenClient:o=>({requestAccessToken:()=>o.callback({access_token:'fixture',expires_in:3600})})}}}};
 context.window=context;
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../docs/drive-import/drive-picker.js'),'utf8'),context);
 for(let i=0;i<5;i++)await new Promise(r=>setImmediate(r));
 listeners.message({source:context.opener,origin:'https://192.168.1.152:43471',data:{type:'amin-drive-init',session:'a'.repeat(32),expiresIn:300}});
 assert.equal(choose.disabled,false);
 choose.click();
 assert.equal(views.length,3);
 assert.deepEqual(views[0],{id:'docs',selectFolder:false,folders:true,parent:'root'});
 assert.equal(views[0].drives,undefined);
 assert.deepEqual(views[1],{id:'docs',selectFolder:false,ownedByMe:false});
 assert.equal(views[2].drives,true);
 assert.deepEqual(features,['drives']);
});
