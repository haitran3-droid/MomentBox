const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const { startServer } = require('./fixtures/start-server.cjs');
let origin, child;
before(async()=>{
 ({ origin, child } = await startServer());
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
