const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const path=require('node:path');const fs=require('node:fs');const net=require('node:net');
const origin='http://127.0.0.1:3111';const root=path.join(__dirname,'..');let child;
const uploaded=[];
before(async()=>{
 await new Promise((resolve,reject)=>{const check=net.createServer();check.once('error',reject);check.listen(3111,'127.0.0.1',()=>check.close(resolve));});
 child=spawn(process.execPath,['server.js'],{cwd:root,env:{...process.env,PORT:'3111'},windowsHide:true,stdio:'ignore'});
 let ready=false;for(let i=0;i<80;i++){try{if((await fetch(origin+'/v1/config')).ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
 assert.ok(ready,'Isolated profile server must start');
});
after(()=>{
 child?.kill();
 for(const filename of uploaded){const base=path.resolve(root,'uploads');const target=path.resolve(base,filename);assert.ok(target.startsWith(base+path.sep));if(fs.existsSync(target))fs.unlinkSync(target);}
});
async function login(email){const response=await fetch(origin+'/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password:'123456'})});assert.equal(response.status,200);return (await response.json()).data;}
const headers=account=>({Authorization:'Bearer '+account.accessToken});
test('owner sees own email but another profile never exposes it',async()=>{
 const a=await login('minhanh@momentbox.dev');const b=await login('lananh@momentbox.dev');
 assert.equal(a.user.email,'minhanh@momentbox.dev');
 const me=await (await fetch(origin+'/v1/me',{headers:headers(a)})).json();assert.equal(me.data.email,'minhanh@momentbox.dev');
 const other=await (await fetch(origin+'/v1/users/'+b.user.id,{headers:headers(a)})).json();assert.equal(Object.hasOwn(other.data,'email'),false);
});
test('profile video paging uses filter and explicit metadata',async()=>{
 const a=await login('minhanh@momentbox.dev');const response=await fetch(origin+`/v1/users/${a.user.id}/posts?type=VIDEO&page=1&pageSize=2`,{headers:headers(a)});
 assert.equal(response.status,200);const body=await response.json();assert.equal(body.success,true);assert.ok(body.data.every(post=>post.author.id===a.user.id && post.type==='VIDEO'));
 assert.equal(body.meta.page,1);assert.equal(body.meta.pageSize,2);assert.equal(typeof body.meta.total,'number');assert.equal(typeof body.meta.hasMore,'boolean');
});
test('profile patch returns actual values and rejects empty display name',async()=>{
 const a=await login('minhanh@momentbox.dev');const h={...headers(a),'Content-Type':'application/json'};
 let response=await fetch(origin+'/v1/me',{method:'PATCH',headers:h,body:JSON.stringify({displayName:'Public Profile Fixture',bio:'Public bio',location:'Test location'})});
 assert.equal(response.status,200);const body=await response.json();assert.equal(body.data.displayName,'Public Profile Fixture');assert.equal(body.data.bio,'Public bio');
 response=await fetch(origin+'/v1/me',{method:'PATCH',headers:h,body:JSON.stringify({displayName:'   '})});assert.equal(response.status,400);
 const me=await (await fetch(origin+'/v1/me',{headers:headers(a)})).json();assert.equal(me.data.displayName,'Public Profile Fixture');
});
test('named avatar multipart returns readable real fixture and owner profile',async()=>{
 const a=await login('minhanh@momentbox.dev');const form=new FormData();form.append('avatar',new Blob([fs.readFileSync(path.join(__dirname,'fixtures/public-avatar.jpg'))],{type:'image/jpeg'}),'public-avatar.jpg');
 const response=await fetch(origin+'/v1/me/avatar',{method:'POST',headers:headers(a),body:form});assert.equal(response.status,200);const body=await response.json();
 const url=new URL(body.data.avatarUrl);assert.equal(url.pathname.startsWith('/uploads/'),true);uploaded.push(path.basename(url.pathname));
 const image=await fetch(body.data.avatarUrl);assert.equal(image.status,200);assert.equal((await image.arrayBuffer()).byteLength,fs.statSync(path.join(__dirname,'fixtures/public-avatar.jpg')).size);
 assert.equal(body.data.email,'minhanh@momentbox.dev');assert.equal(body.data.id,a.user.id);
});
test('avatar mutation requires authentication before upload',async()=>{
 const response=await fetch(origin+'/v1/me/avatar',{method:'POST'});assert.equal(response.status,401);
});
