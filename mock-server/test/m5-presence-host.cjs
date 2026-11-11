// Test-only peer: socket thật với tài khoản không phải bot; token chỉ nằm trong RAM.
const {spawn}=require('node:child_process');const http=require('node:http');const path=require('node:path');
const child=spawn(process.execPath,['server.js'],{cwd:path.join(__dirname,'..'),env:{...process.env,PORT:'3107'},windowsHide:true,stdio:'ignore'});
let socket=null,peer=null;
async function bootstrap(){
 let ready=false;for(let i=0;i<100;i++){try{const r=await fetch('http://127.0.0.1:3107/v1/config');if(r.ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}
 if(!ready)throw Error('isolated server failed to start');
 const response=await fetch('http://127.0.0.1:3107/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'travel@momentbox.dev',password:'123456'})});
 if(!response.ok)throw Error('peer login status '+response.status);peer=(await response.json()).data;
}
async function connect(){
 if(socket && socket.readyState===WebSocket.OPEN)return;
 await new Promise((resolve,reject)=>{
  socket=new WebSocket('ws://127.0.0.1:3107/socket.io/?EIO=4&transport=websocket');
  const deadline=setTimeout(()=>reject(Error('peer handshake timeout')),8000);
  socket.addEventListener('error',()=>{clearTimeout(deadline);reject(Error('peer transport error'));});
  socket.addEventListener('message',event=>{
   const value=String(event.data);
   if(value.startsWith('0'))socket.send('40'+JSON.stringify({token:peer.accessToken}));
   else if(value==='2')socket.send('3');
   else if(value.startsWith('40')){clearTimeout(deadline);resolve();}
  });
 });
}
async function disconnect(){
 if(!socket)return;const previous=socket;socket=null;
 if(previous.readyState===WebSocket.CLOSED)return;
 await new Promise(resolve=>{previous.addEventListener('close',resolve,{once:true});previous.close();setTimeout(resolve,1000);});
}
bootstrap().then(()=>{
 const control=http.createServer(async(req,res)=>{
  try {
   if(req.url==='/connect')await connect();
   else if(req.url==='/disconnect')await disconnect();
   else if(req.url==='/stop'){await disconnect();res.end('stopped');child.kill();control.close();setTimeout(()=>process.exit(0),100);return;}
   res.setHeader('Content-Type','application/json');res.end(JSON.stringify({ready:true,peerId:peer.user.id}));
  } catch(error){res.statusCode=500;res.end(JSON.stringify({error:error.message}));}
 });
 control.listen(3108,'127.0.0.1',()=>console.log('M5 isolated presence peer ready on3108; mock server3107'));
}).catch(error=>{console.error(error.message);child.kill();process.exit(1);});
process.on('SIGINT',()=>{child.kill();process.exit(0);});
