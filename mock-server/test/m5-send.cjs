const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const { startServer } = require('./fixtures/start-server.cjs');
const {io}=require('socket.io-client');
let origin, child, a, b, socket, room;
const request=async(route,token,body)=>{const r=await fetch(origin+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});return {status:r.status,body:await r.json()}};
before(async()=>{
 ({ origin, child } = await startServer());
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

test('reading a group message preserves unread state for other recipients', async () => {
 const login = async email => (await request('/v1/auth/login', null, {email, password:'123456'})).body.data;
 const sender = await login('lananh@momentbox.dev');
 const other = await login('trang@momentbox.dev');
 const roomFor = async user => (await request('/v1/conversations', user.accessToken)).body.data.find(c => c.type === 'group');
 const group = await roomFor(other);
 const history = '/v1/conversations/' + group.id + '/messages';
 const writer = io(origin, {auth:{token:sender.accessToken}, transports:['websocket'], reconnection:false});
 try {
  await new Promise((resolve,reject) => {writer.once('connect', resolve); writer.once('connect_error', reject);});
  const sent = await request(history, sender.accessToken, {clientId:'5b2cb9b9-d296-41ba-a034-410ff57a8c05', content:'group message'});
  assert.equal(sent.status, 200);
  const id = sent.body.data.id;
  const received = new Promise((resolve,reject) => {
   const timeout = setTimeout(() => reject(new Error('Missing group read receipt')), 2000);
   writer.on('message:status', event => {
    if (event.messageId === id && event.userId === a.user.id && event.status === 'seen') {
     clearTimeout(timeout);
     resolve();
    }
   });
  });
  socket.emit('message:seen', {conversationId:group.id, messageId:id});
  await received;
  const statusFor = async user => (await request(history, user.accessToken)).body.data.find(m => m.id === id).status;
  assert.equal(await statusFor(a), 'seen');
  assert.equal(await statusFor(sender), 'seen');
  assert.notEqual(await statusFor(other), 'seen');
  assert.ok((await roomFor(other)).unreadCount > group.unreadCount);
  const seenRoute = '/v1/conversations/' + group.id + '/seen';
  const outsider = await login('travel@momentbox.dev');
  assert.equal((await request(seenRoute, outsider.accessToken, {messageId:id})).status, 404);
  assert.equal((await request(seenRoute, other.accessToken, {messageId:id})).status, 200);
  assert.equal(await statusFor(other), 'seen');
 } finally {
  writer.disconnect();
 }
});
