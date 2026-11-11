const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const path=require('node:path');
const origin='http://127.0.0.1:3107';let child;
before(async()=>{
 child=spawn(process.execPath,['server.js'],{cwd:path.join(__dirname,'..'),env:{...process.env,PORT:'3107'},windowsHide:true,stdio:'ignore'});
 let ready=false;
 for(let i=0;i<80;i++){try{const r=await fetch(origin+'/v1/config');if(r.ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
 assert.ok(ready,'Isolated mock server must start');
});
after(()=>child?.kill());
async function login(email){const r=await fetch(origin+'/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password:'123456'})});assert.equal(r.status,200);return (await r.json()).data;}
test('following endpoint requires authentication',async()=>{
 const r=await fetch(origin+'/v1/me/following');assert.equal(r.status,401);
});
test('following reflects real follow and unfollow operations for the authenticated owner',async()=>{
 const a=await login('minhanh@momentbox.dev');const b=await login('travel@momentbox.dev');
 const headers={Authorization:'Bearer '+a.accessToken};
 const follow=await fetch(origin+'/v1/users/'+b.user.id+'/follow',{method:'POST',headers});assert.equal(follow.status,200);
 let r=await fetch(origin+'/v1/me/following',{headers});assert.equal(r.status,200);let body=await r.json();assert.equal(body.success,true);
 assert.ok(body.data.some(u=>u.id===b.user.id));assert.ok(body.data.every(u=>u.id!==a.user.id && u.isFollowing===true));
 const unfollow=await fetch(origin+'/v1/users/'+b.user.id+'/follow',{method:'DELETE',headers});assert.equal(unfollow.status,200);
 r=await fetch(origin+'/v1/me/following',{headers});body=await r.json();assert.ok(!body.data.some(u=>u.id===b.user.id));
});
