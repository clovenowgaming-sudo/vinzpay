const http=require('http'),fs=require('fs'),crypto=require('crypto');
const PORT=+process.env.PORT||3000, DB=process.env.DB||'db.json';
const SHOW_OTP=process.env.SHOW_OTP!=='0';        // 0 = jangan kirim OTP di response (sambungkan SMS gateway di sendOtp)
const XKEY=process.env.XENDIT_SECRET_KEY||'';
const TOPUP_MODE=XKEY?'xendit':'demo';             // tanpa key Xendit = demo (saldo langsung bertambah)
const XBASE=process.env.XENDIT_BASE||'https://api.xendit.co';
const DBASE=process.env.DIGIFLAZZ_BASE||'https://api.digiflazz.com/v1';
const PUBLIC_URL=process.env.PUBLIC_URL||'';
const CB_TOKEN=process.env.XENDIT_CALLBACK_TOKEN||'';
const DUSER=process.env.DIGIFLAZZ_USER||'',DKEY=process.env.DIGIFLAZZ_KEY||'',DDEV=process.env.DIGIFLAZZ_DEV!=='0';
const MARKUP=+process.env.MARKUP||0;               // untung per transaksi toko (Rp)
let db={users:{},tx:[],otps:{},sessions:{}};
try{db=JSON.parse(fs.readFileSync(DB,'utf8'))}catch{}
const save=()=>fs.writeFileSync(DB,JSON.stringify(db));
const norm=p=>{p=String(p||'').replace(/\D/g,'');if(p.startsWith('0'))p='62'+p.slice(1);return p};
const validPhone=p=>/^628\d{7,11}$/.test(p);
const hash=(pin,salt)=>crypto.scryptSync(String(pin),salt,32).toString('hex');
const err=(m,c=400)=>{const e=new Error(m);e.code=c;return e};
const send=(res,c,o)=>{res.writeHead(c,{'Content-Type':'application/json'});res.end(JSON.stringify(o))};
const readBody=req=>new Promise((ok,no)=>{let d='';req.on('data',c=>{d+=c;if(d.length>1e5)req.destroy()});req.on('end',()=>{try{ok(d?JSON.parse(d):{})}catch{no(err('JSON salah'))}})});
const auth=(req,scope='full')=>{const t=(req.headers.authorization||'').replace('Bearer ','');const s=db.sessions[t];if(!s||s.scope!==scope)throw err('unauthorized',401);return s.phone};
const newSession=(phone,scope)=>{const t=crypto.randomBytes(24).toString('hex');db.sessions[t]={phone,scope,ts:Date.now()};return t};
const checkPin=(u,pin)=>{
  if(u.lockUntil&&u.lockUntil>Date.now())throw err('PIN terkunci 5 menit, coba lagi nanti',429);
  const a=Buffer.from(hash(pin,u.salt)),b=Buffer.from(u.pin);
  if(a.length!==b.length||!crypto.timingSafeEqual(a,b)){u.fails=(u.fails||0)+1;if(u.fails>=5){u.lockUntil=Date.now()+300000;u.fails=0}save();throw err('PIN salah')}
  u.fails=0;
};
const amountOf=v=>{const a=Number(v);if(!Number.isInteger(a)||a<=0||a>50000000)throw err('Nominal tidak valid (1 - 50.000.000)');return a};
const addTx=t=>{db.tx.push({id:crypto.randomUUID(),ts:Date.now(),...t})};
async function sendOtp(phone,code){console.log(`[OTP] ${phone}: ${code}`);/* TODO: panggil SMS/WhatsApp gateway di sini */}

function tlv(s){const o={};let i=0;while(i+4<=s.length){const t=s.slice(i,i+2),l=+s.slice(i+2,i+4);if(isNaN(l)||i+4+l>s.length)throw err('QRIS tidak valid');o[t]=s.slice(i+4,i+4+l);i+=4+l}return o}
function crc16(s){let c=0xFFFF;for(let i=0;i<s.length;i++){c^=s.charCodeAt(i)<<8;for(let j=0;j<8;j++)c=c&0x8000?((c<<1)^0x1021)&0xFFFF:(c<<1)&0xFFFF}return c.toString(16).toUpperCase().padStart(4,'0')}
function parseQris(q){
  q=String(q||'').trim();
  if(!q.startsWith('000201')||q.length<30)throw err('Bukan QRIS');
  if(crc16(q.slice(0,-4))!==q.slice(-4).toUpperCase())throw err('QRIS tidak valid (CRC salah)');
  const t=tlv(q);if(t['58']&&t['58']!=='ID')throw err('Bukan QRIS Indonesia');
  return{merchant:t['59']||'Merchant',city:t['60']||'',amount:t['54']?Math.round(parseFloat(t['54'])):0,dynamic:t['01']==='12'};
}

db.topups=db.topups||{};db.orders=db.orders||[];
const md5=s=>crypto.createHash('md5').update(s).digest('hex');
const qs=(req,k)=>new URL(req.url,'http://x').searchParams.get(k)||'';
async function xendit(method,path,body){
  const r=await fetch(XBASE+path,{method,headers:{Authorization:'Basic '+Buffer.from(XKEY+':').toString('base64'),'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  const j=await r.json().catch(()=>({}));
  if(!r.ok)throw err('Xendit: '+(j.message||j.error_code||r.status),502);
  return j;
}
async function settleTopup(id){
  const t=db.topups[id];if(!t)throw err('Topup tidak ditemukan',404);
  if(t.status==='PAID')return t;
  let paid=false;
  try{
    const p=await xendit('GET','/qr_codes/payments?external_id='+encodeURIComponent(id));
    const list=Array.isArray(p)?p:(p.data||[]);
    paid=list.some(x=>x.status==='COMPLETED'&&Number(x.amount)>=t.amount);
  }catch(e){
    const q=await xendit('GET','/qr_codes/'+encodeURIComponent(id));  // fallback: QR dinamis INACTIVE = sudah dibayar
    paid=q.status==='INACTIVE';
  }
  if(paid&&t.status==='PENDING'){
    t.status='PAID';db.users[t.phone].balance+=t.amount;
    addTx({type:'topup',to:t.phone,amount:t.amount,desc:'Top up QRIS'});save();
  }
  return t;
}
async function dfz(path,body){
  if(!DUSER||!DKEY)throw err('Toko belum aktif: isi DIGIFLAZZ_USER & DIGIFLAZZ_KEY di server',503);
  const r=await fetch(DBASE+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:DUSER,...body})});
  return await r.json().catch(()=>({}));
}
let plCache={ts:0,items:[]};
async function prices(){
  if(Date.now()-plCache.ts<600000&&plCache.items.length)return plCache.items;
  const j=await dfz('/price-list',{cmd:'prepaid',sign:md5(DUSER+DKEY+'pricelist')});
  if(!Array.isArray(j.data))throw err('Digiflazz: '+((j.data&&j.data.message)||'gagal ambil daftar harga'),502);
  plCache={ts:Date.now(),items:j.data.filter(x=>x.buyer_product_status&&x.seller_product_status)};
  return plCache.items;
}
const sell=p=>Math.ceil(Number(p.price)+MARKUP);
function applyDfz(o,j){
  const d=(j&&j.data)||{};
  if(d.status==='Sukses'){o.status='SUCCESS';o.sn=d.sn||''}
  else if(d.status==='Gagal'&&o.status!=='FAILED'&&o.status!=='SUCCESS'){
    o.status='FAILED';db.users[o.phone].balance+=o.price;
    addTx({type:'refund',to:o.phone,amount:o.price,desc:'Refund: '+o.name});
  }
  save();
}
const dfzTx=o=>dfz('/transaction',{buyer_sku_code:o.sku,customer_no:o.target,ref_id:o.ref,sign:md5(DUSER+DKEY+o.ref),...(DDEV?{testing:true}:{})});

const routes={
 'POST /register':async(req,b)=>{
   const phone=norm(b.phone);if(!validPhone(phone))throw err('Nomor HP tidak valid (contoh 0812xxxxxxx)');
   if(db.users[phone])throw err('Nomor sudah terdaftar, silakan login');
   const last=db.otps[phone];if(last&&Date.now()-last.sent<30000)throw err('Tunggu 30 detik sebelum minta OTP lagi',429);
   const code=String(crypto.randomInt(100000,1000000));
   db.otps[phone]={code,exp:Date.now()+300000,tries:0,sent:Date.now()};save();await sendOtp(phone,code);
   return{ok:true,phone,...(SHOW_OTP?{otp_dev:code}:{})};
 },
 'POST /verify':async(req,b)=>{
   const phone=norm(b.phone),o=db.otps[phone];
   if(!o||o.exp<Date.now())throw err('OTP kadaluarsa, minta ulang');
   if(++o.tries>5){delete db.otps[phone];save();throw err('Terlalu banyak percobaan')}
   if(String(b.otp)!==o.code){save();throw err('OTP salah')}
   delete db.otps[phone];const token=newSession(phone,'setup');save();return{token};
 },
 'POST /set-pin':async(req,b)=>{
   const phone=auth(req,'setup');
   if(!/^\d{6}$/.test(String(b.pin)))throw err('PIN harus 6 digit angka');
   if(db.users[phone])throw err('Nomor sudah terdaftar');
   const salt=crypto.randomBytes(16).toString('hex');
   db.users[phone]={phone,balance:0,salt,pin:hash(b.pin,salt),created:Date.now()};
   const t=(req.headers.authorization||'').replace('Bearer ','');delete db.sessions[t];
   const token=newSession(phone,'full');save();return{token,phone};
 },
 'POST /login':async(req,b)=>{
   const phone=norm(b.phone),u=db.users[phone];if(!u)throw err('Nomor belum terdaftar');
   checkPin(u,b.pin);const token=newSession(phone,'full');save();return{token,phone};
 },
 'GET /me':async(req)=>{const u=db.users[auth(req)];return{phone:u.phone,balance:u.balance}},
 'POST /topup/qris':async(req,b)=>{
   const u=db.users[auth(req)];const a=amountOf(b.amount);
   if(TOPUP_MODE==='demo'){u.balance+=a;addTx({type:'topup',to:u.phone,amount:a,desc:'Top up (demo)'});save();return{paid:true,balance:u.balance}}
   if(a<1500)throw err('Minimal top up Rp1.500');
   const id='tp-'+crypto.randomBytes(8).toString('hex');
   const q=await xendit('POST','/qr_codes',{external_id:id,type:'DYNAMIC',amount:a,callback_url:PUBLIC_URL?PUBLIC_URL+'/webhook/xendit':'https://example.com/noop'});
   db.topups[id]={phone:u.phone,amount:a,status:'PENDING',ts:Date.now(),qr_id:q.id};save();
   return{id,qr_string:q.qr_string,amount:a,sandbox:XKEY.startsWith('xnd_development')};
 },
 'GET /topup/status':async(req)=>{
   const p=auth(req),id=qs(req,'id'),t=db.topups[id];
   if(!t||t.phone!==p)throw err('Topup tidak ditemukan',404);
   const r=await settleTopup(id);return{paid:r.status==='PAID',balance:db.users[p].balance};
 },
 'POST /topup/simulate':async(req,b)=>{
   const p=auth(req);if(!XKEY.startsWith('xnd_development'))throw err('Hanya untuk sandbox');
   const t=db.topups[b.id];if(!t||t.phone!==p)throw err('Topup tidak ditemukan',404);
   await xendit('POST','/qr_codes/'+t.qr_id+'/payments/simulate',{amount:t.amount});return{ok:true};
 },
 'POST /webhook/xendit':async(req,b)=>{
   if(CB_TOKEN&&req.headers['x-callback-token']!==CB_TOKEN)throw err('forbidden',403);
   const id=b.external_id||(b.qr_code&&b.qr_code.external_id)||(b.data&&b.data.qr_code&&b.data.qr_code.external_id);
   if(id&&db.topups[id])await settleTopup(id).catch(()=>{});   // status selalu diverifikasi ulang ke Xendit
   return{ok:true};
 },
 'GET /shop/brands':async(req)=>{
   auth(req);const cat=qs(req,'cat')||'Games';
   return{brands:[...new Set((await prices()).filter(x=>x.category===cat).map(x=>x.brand))].sort()};
 },
 'GET /shop/products':async(req)=>{
   auth(req);const cat=qs(req,'cat'),brand=qs(req,'brand');
   return{products:(await prices()).filter(x=>x.category===cat&&x.brand===brand)
     .map(x=>({sku:x.buyer_sku_code,name:x.product_name,price:sell(x)})).sort((a,b)=>a.price-b.price)};
 },
 'POST /shop/buy':async(req,b)=>{
   const u=db.users[auth(req)];checkPin(u,b.pin);
   const target=String(b.target||'').trim();
   if(!/^[\w\-.()@]{3,40}$/.test(target))throw err('ID / nomor tujuan tidak valid');
   const p=(await prices()).find(x=>x.buyer_sku_code===b.sku);if(!p)throw err('Produk tidak tersedia');
   const price=sell(p);if(u.balance<price)throw err('Saldo tidak cukup');
   const o={ref:'ord-'+crypto.randomBytes(8).toString('hex'),phone:u.phone,sku:p.buyer_sku_code,name:p.product_name,target,price,status:'PENDING',sn:'',ts:Date.now()};
   u.balance-=price;db.orders.push(o);addTx({type:'shop',from:u.phone,to:'SHOP',amount:price,desc:p.product_name+' > '+target});save();
   try{applyDfz(o,await dfzTx(o))}catch(e){/* tetap PENDING, dicek ulang di /shop/orders */}
   return{status:o.status,sn:o.sn,balance:u.balance};
 },
 'GET /shop/orders':async(req)=>{
   const p=auth(req);const mine=db.orders.filter(o=>o.phone===p).slice(-30);
   for(const o of mine.filter(o=>o.status==='PENDING').slice(-10)){try{applyDfz(o,await dfzTx(o))}catch(e){}}
   return{orders:mine.reverse().map(o=>({name:o.name,target:o.target,price:o.price,status:o.status,sn:o.sn,ts:o.ts}))};
 },
 'POST /transfer':async(req,b)=>{
   const u=db.users[auth(req)];checkPin(u,b.pin);
   const to=norm(b.to),r=db.users[to];if(!r)throw err('Nomor tujuan belum terdaftar di aplikasi');
   if(to===u.phone)throw err('Tidak bisa transfer ke diri sendiri');
   const a=amountOf(b.amount);if(u.balance<a)throw err('Saldo tidak cukup');
   u.balance-=a;r.balance+=a;addTx({type:'transfer',from:u.phone,to,amount:a,desc:String(b.note||'').slice(0,60)});save();return{balance:u.balance};
 },
 'POST /qris/parse':async(req,b)=>{auth(req);return parseQris(b.qris)},
 'POST /qris/pay':async(req,b)=>{
   const u=db.users[auth(req)];
   if(TOPUP_MODE!=='demo')throw err('Bayar QRIS merchant belum didukung (butuh izin PJP). Gunakan menu Toko untuk beli game/pulsa.');
   checkPin(u,b.pin);
   const q=parseQris(b.qris),a=q.amount||amountOf(b.amount);
   if(u.balance<a)throw err('Saldo tidak cukup');
   u.balance-=a;addTx({type:'qris',from:u.phone,to:'QRIS:'+q.merchant,amount:a,desc:'QRIS '+q.merchant+(q.city?' - '+q.city:'')+' (demo, belum settlement)'});save();return{balance:u.balance};
 },
 'GET /history':async(req)=>{
   const p=auth(req);
   return{items:db.tx.filter(t=>t.from===p||t.to===p).slice(-50).reverse().map(t=>({dir:t.from===p?'out':'in',amount:t.amount,desc:(t.type==='transfer'?(t.from===p?'Ke ':'Dari ')+(t.from===p?t.to:t.from)+(t.desc?' - '+t.desc:''):t.desc),ts:t.ts}))};
 },
};
http.createServer(async(req,res)=>{
  try{const h=routes[req.method+' '+req.url.split('?')[0]];if(!h)throw err('Not found',404);
    send(res,200,await h(req,req.method==='POST'?await readBody(req):{}));
  }catch(e){send(res,e.code||500,{error:e.message})}
}).listen(PORT,'0.0.0.0',()=>console.log('VinzPay server on :'+PORT));
