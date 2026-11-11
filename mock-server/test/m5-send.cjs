const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');const path=require('node:path');
const {io}=require('socket.io-client');
const origin='http://127.0.0.1:3107';let child,a,b,socket,room;
const request=async(route,token,body)=>{const r=await fetch(origin+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});return {status:r.status,body:await r.json()}};
before(async()=>{
 child=spawn(process.execPath,['server.js'],{cwd:path.join(__dirname,'..'),env:{...process.env,PORT:'3107'},windowsHide:true,stdio:'ignore'});
 let ready=false;for(let i=0;i<80;i++){try{if((await fetch(origin+'/v1/config')).ok){ready=true;break}}catch{}await new Promise(r=>setTimeout(r,100))}assert.ok(ready);
 a=(await request('/v1/auth/login',null,{email:'minhanh@momentbox.dev',password:'123456'})).body.data;
 b=(await request('/v1/auth/login',null,{email:'travel@momentbox.dev',password:'123456'})).body.data;
 room=(await request('/v1/conversations',a.accessToken,{userId:b.user.id})).body.data.id;
 socket=io(origin,{auth:{token:a.accessToken},transports:['websocket'],reconnection:false});
 await new Promise((resolve,reject)=>{socket.once('connect',resolve);socket.once('connect_error',reject)});
});
after(()=>{socket?.disconnect();child?.kill()});
const emit=body=>new Promise(resolve=>socket.emit('message:send',body,resolve));
test('REST send requires auth, room membership, valid client ID and media shape',async()=>{
 const payload={clientId:'5b2cb9b9-d296-41ba-a034-410ff57a8c01',type:'text',content:'hello'};
 assert.equal((await request('/v1/conversations/'+room+'/messages',null,payload)).status,401);
 const outsider=(await request('/v1/auth/login',null,{email:'lananh@momentbox.dev',password:'123456'})).body.data;
 assert.equal((await request('/v1/conversations/'+room+'/messages',outsider.accessToken,payload)).status,403);
 assert.equal((await request('/v1/conversations/'+room+'/messages',a.accessToken,{...payload,clientId:''})).status,400);
 assert.equal((await request('/v1/conversations/'+room+'/messages',a.accessToken,{...payload,type:'image',mediaUrl:null})).status,400);
});
test('Socket and REST share one logical message and server ID across retries',async()=>{
 const payload={clientId:'5b2cb9b9-d296-41ba-a034-410ff57a8c02',conversationId:room,type:'text',content:'idempotent text'};
 const first=await emit(payload);assert.equal(first.ok,true);
 const second=await request('/v1/conversations/'+room+'/messages',a.accessToken,payload);assert.equal(second.status,200);assert.equal(second.body.data.id,first.message.id);
 const third=await emit(payload);assert.equal(third.message.id,first.message.id);
 const history=await request('/v1/conversations/'+room+'/messages?limit=100',a.accessToken);
 assert.equal(history.body.data.filter(m=>m.clientId===payload.clientId).length,1);
});
test('slow persists before delayed ack, REST retry reconciles it; fail never persists',async()=>{
 const payload={clientId:'5b2cb9b9-d296-41ba-a034-410ff57a8c03',conversationId:room,type:'text',content:'#slow'};
 const start=Date.now();const slow=emit(payload);
 await new Promise(r=>setTimeout(r,100));
 const retry=await request('/v1/conversations/'+room+'/messages',a.accessToken,{...payload,content:'same operation'});assert.equal(retry.status,200);
 const late=await slow;assert.ok(Date.now()-start>=7900);assert.equal(late.message.id,retry.body.data.id);
 const failed=await emit({...payload,clientId:'5b2cb9b9-d296-41ba-a034-410ff57a8c04',content:'#fail'});assert.equal(failed.ok,false);
 const history=await request('/v1/conversations/'+room+'/messages?limit=100',a.accessToken);
 assert.equal(history.body.data.filter(m=>m.clientId===payload.clientId).length,1);
 assert.ok(!history.body.data.some(m=>m.clientId.endsWith('8c04')));
});
