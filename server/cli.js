const fs=require('fs'),path=require('path');
const PORT=process.env.PORT||3000;
let key=process.env.ADMIN_KEY||'';
if(!key){try{key=fs.readFileSync(path.join(__dirname,'admin.key'),'utf8').trim()}catch{}}
const rp=n=>'Rp'+Number(n).toLocaleString('id-ID');
async function api(m,p,b){
  const r=await fetch(`http://127.0.0.1:${PORT}${p}`,{method:m,headers:{'Content-Type':'application/json','x-admin-key':key},body:b?JSON.stringify(b):undefined});
  const j=await r.json().catch(()=>({}));if(!r.ok)throw new Error(j.error||('HTTP '+r.status));return j;
}
const [,,cmd,...a]=process.argv;
(async()=>{
 switch(cmd){
  case 'users':{const r=await api('GET','/admin/users');r.users.forEach(u=>console.log(u.phone.padEnd(15),rp(u.balance)));console.log(r.users.length+' user');break}
  case 'topup':{if(a.length<2)throw new Error('pakai: topup <nomor> <nominal>');const r=await api('POST','/admin/topup',{phone:a[0],amount:Number(a[1])});console.log('OK',r.phone,'saldo sekarang',rp(r.balance));break}
  case 'pending':{const r=await api('GET','/admin/pending');if(!r.items.length)console.log('Tidak ada permintaan top up');r.items.forEach(t=>console.log(t.id,t.phone,rp(t.amount),new Date(t.ts).toLocaleString('id-ID')));break}
  case 'approve':{const r=await api('POST','/admin/approve',{id:a[0]});console.log('OK',r.phone,'saldo',rp(r.balance));break}
  case 'reject':{await api('POST','/admin/reject',{id:a[0]});console.log('Ditolak');break}
  case 'reset-pin':{await api('POST','/admin/reset-pin',{phone:a[0],pin:a[1]});console.log('PIN direset');break}
  case 'orders':{const r=await api('GET','/admin/orders');r.orders.forEach(o=>console.log(o.status.padEnd(8),o.phone,o.name,o.target,rp(o.price),o.sn||''));break}
  case 'supplier':{console.log(await api('GET','/admin/supplier'));break}
  default:console.log(`VinzPay admin CLI
  ./cli.sh users                      daftar user + saldo
  ./cli.sh topup <nomor> <nominal>    tambah saldo langsung
  ./cli.sh pending                    permintaan top up dari app
  ./cli.sh approve <kode>             setujui permintaan
  ./cli.sh reject <kode>              tolak permintaan
  ./cli.sh reset-pin <nomor> <pin6>   reset PIN user
  ./cli.sh orders                     pesanan toko terakhir
  ./cli.sh supplier                   saldo deposit Digiflazz`);
 }
})().catch(e=>{console.error('Error:',e.message.includes('fetch failed')?'server belum jalan (./run-server.sh)':e.message);process.exit(1)});
