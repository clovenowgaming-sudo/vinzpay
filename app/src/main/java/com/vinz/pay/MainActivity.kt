package com.vinz.pay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(this) } } }
    }
}

fun rp(n: Long) = "Rp" + "%,d".format(n).replace(',', '.')

@Composable
fun Col(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().statusBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content
)

@Composable
fun F(v: String, l: String, onC: (String) -> Unit, kb: KeyboardType = KeyboardType.Text, pw: Boolean = false) =
    OutlinedTextField(
        value = v, onValueChange = onC, label = { Text(l) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = kb),
        visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None
    )

@Composable
fun Back(nav: (String, String) -> Unit) = TextButton({ nav("home", "") }) { Text("← Kembali") }

@Composable
fun Msg(m: String, ok: Boolean = false) {
    if (m.isNotEmpty()) Text(m, color = if (ok) Color(0xFF2E7D32) else Color.Red)
}

fun saveSession(p: SharedPreferences, token: String) {
    Api.token = token
    p.edit().putString("token", token).apply()
}

@Composable
fun App(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences("pay", 0) }
    var screen by remember { mutableStateOf(if (prefs.getString("token", "").isNullOrEmpty()) "login" else "home") }
    var arg by remember { mutableStateOf("") }
    remember {
        Api.base = prefs.getString("server", "http://10.0.2.2:3000")!!
        Api.token = prefs.getString("token", "")!!
        0
    }
    val nav: (String, String) -> Unit = { s, a -> screen = s; arg = a }
    val logout = { prefs.edit().remove("token").apply(); Api.token = ""; screen = "login" }
    if (screen != "home" && screen != "login") BackHandler { screen = "home" }
    when (screen) {
        "login" -> Login(prefs, nav)
        "reg" -> Register(prefs, nav)
        "home" -> Home(nav, logout)
        "topup" -> TopUp(nav)
        "transfer" -> Transfer(arg, nav)
        "myqr" -> MyQr(nav)
        "scan" -> Scan(nav)
        "qris" -> Qris(arg, nav)
        "history" -> History(nav)
        "shop" -> Shop(nav)
        "orders" -> Orders(nav)
    }
}

@Composable
fun Login(prefs: SharedPreferences, nav: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(prefs.getString("server", "http://10.0.2.2:3000")!!) }
    var phone by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    fun setServer() { Api.base = server; prefs.edit().putString("server", server).apply() }
    Col {
        Text("VinzPay", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        F(server, "Server URL (http://IP:3000)", { server = it })
        F(phone, "Nomor HP", { phone = it }, KeyboardType.Phone)
        F(pin, "PIN (6 digit)", { pin = it }, KeyboardType.NumberPassword, true)
        Msg(msg)
        Button({
            scope.launch {
                try {
                    msg = ""; setServer()
                    val r = Api.call("POST", "/login", JSONObject().put("phone", phone).put("pin", pin))
                    saveSession(prefs, r.getString("token")); nav("home", "")
                } catch (e: Exception) { msg = e.message ?: "Error" }
            }
        }, Modifier.fillMaxWidth()) { Text("Masuk") }
        TextButton({ setServer(); nav("reg", "") }) { Text("Belum punya akun? Daftar") }
    }
}

@Composable
fun Register(prefs: SharedPreferences, nav: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(0) }
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    fun run(b: suspend () -> Unit) { scope.launch { try { msg = ""; b() } catch (e: Exception) { msg = e.message ?: "Error" } } }
    Col {
        TextButton({ nav("login", "") }) { Text("← Login") }
        Text("Daftar", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        when (stage) {
            0 -> {
                F(phone, "Nomor HP", { phone = it }, KeyboardType.Phone)
                Button({ run {
                    val r = Api.call("POST", "/register", JSONObject().put("phone", phone))
                    hint = r.optString("otp_dev"); stage = 1
                } }, Modifier.fillMaxWidth()) { Text("Kirim OTP") }
            }
            1 -> {
                Text("Masukkan kode OTP untuk $phone")
                if (hint.isNotEmpty()) Text("Kode OTP (mode dev): $hint", color = Color(0xFF1565C0))
                F(otp, "Kode OTP", { otp = it }, KeyboardType.Number)
                Button({ run {
                    val r = Api.call("POST", "/verify", JSONObject().put("phone", phone).put("otp", otp))
                    Api.token = r.getString("token"); stage = 2
                } }, Modifier.fillMaxWidth()) { Text("Verifikasi") }
            }
            else -> {
                Text("Nomor terverifikasi. Buat PIN 6 digit.")
                F(p1, "PIN baru", { p1 = it }, KeyboardType.NumberPassword, true)
                F(p2, "Ulangi PIN", { p2 = it }, KeyboardType.NumberPassword, true)
                Button({
                    if (p1 != p2) msg = "PIN tidak sama" else run {
                        val r = Api.call("POST", "/set-pin", JSONObject().put("pin", p1))
                        saveSession(prefs, r.getString("token")); nav("home", "")
                    }
                }, Modifier.fillMaxWidth()) { Text("Simpan PIN & Masuk") }
            }
        }
        Msg(msg)
    }
}

@Composable
fun Home(nav: (String, String) -> Unit, logout: () -> Unit) {
    var bal by remember { mutableStateOf(0L) }
    var phone by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val r = Api.call("GET", "/me"); bal = r.getLong("balance"); phone = r.getString("phone")
        } catch (e: Exception) { if (e.message == "unauthorized") logout() else msg = e.message ?: "Error" }
    }
    Col {
        Text("VinzPay", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Saldo", fontSize = 14.sp)
                Text(rp(bal), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(phone, fontSize = 14.sp)
            }
        }
        Msg(msg)
        for ((label, s) in listOf("Top Up" to "topup", "Transfer ke Nomor" to "transfer", "Toko (Game, Pulsa, dll)" to "shop", "Scan QR VinzPay" to "scan",
            "QR Saya" to "myqr", "Pesanan" to "orders", "Riwayat" to "history")) {
            Button({ nav(s, "") }, Modifier.fillMaxWidth()) { Text(label) }
        }
        OutlinedButton(logout, Modifier.fillMaxWidth()) { Text("Keluar") }
    }
}

fun qrBitmap(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600)
    val b = Bitmap.createBitmap(600, 600, Bitmap.Config.RGB_565)
    for (x in 0 until 600) for (y in 0 until 600)
        b.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return b.asImageBitmap()
}

@Composable
fun TopUp(nav: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var amt by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var sandbox by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        while (id.isNotEmpty() && !paid) {
            delay(3000)
            try {
                val r = Api.call("GET", "/topup/status?id=$id")
                if (r.getBoolean("paid")) { paid = true; ok = true; msg = "Pembayaran diterima! Saldo: " + rp(r.getLong("balance")) }
            } catch (e: Exception) { }
        }
    }
    Col {
        Back(nav)
        Text("Top Up Saldo", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (qr.isEmpty()) {
            F(amt, "Nominal (Rp)", { amt = it.filter(Char::isDigit) }, KeyboardType.Number)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in listOf("20000", "50000", "100000")) OutlinedButton({ amt = v }) { Text(rp(v.toLong())) }
            }
            Button({
                scope.launch {
                    try {
                        msg = ""
                        val r = Api.call("POST", "/topup/qris", JSONObject().put("amount", amt.toLongOrNull() ?: 0))
                        if (r.optBoolean("paid")) { paid = true; ok = true; msg = "Berhasil. Saldo: " + rp(r.getLong("balance")); qr = "-" }
                        else { qr = r.getString("qr_string"); id = r.getString("id"); sandbox = r.optBoolean("sandbox") }
                    } catch (e: Exception) { ok = false; msg = e.message ?: "Error" }
                }
            }, Modifier.fillMaxWidth()) { Text("Buat QRIS Top Up") }
        } else if (!paid) {
            Text("Scan QRIS ini dengan e-wallet / m-banking. Nominal " + rp(amt.toLongOrNull() ?: 0))
            Image(remember(qr) { qrBitmap(qr) }, "QRIS", Modifier.size(280.dp))
            Text("Menunggu pembayaran...")
            if (sandbox) OutlinedButton({
                scope.launch { try { Api.call("POST", "/topup/simulate", JSONObject().put("id", id)) } catch (e: Exception) { msg = e.message ?: "Error" } }
            }) { Text("Simulasi bayar (sandbox)") }
            OutlinedButton({ qr = ""; id = "" }) { Text("Batal") }
        } else {
            Button({ nav("home", "") }, Modifier.fillMaxWidth()) { Text("Selesai") }
        }
        Msg(msg, ok)
    }
}

@Composable
fun Transfer(prefill: String, nav: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(prefill) }
    var amt by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    Col {
        Back(nav)
        Text("Transfer", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        F(to, "Nomor tujuan (terdaftar)", { to = it }, KeyboardType.Phone)
        F(amt, "Nominal (Rp)", { amt = it.filter(Char::isDigit) }, KeyboardType.Number)
        F(note, "Catatan (opsional)", { note = it })
        F(pin, "PIN", { pin = it }, KeyboardType.NumberPassword, true)
        Button({
            scope.launch {
                try {
                    val r = Api.call("POST", "/transfer", JSONObject().put("to", to)
                        .put("amount", amt.toLongOrNull() ?: 0).put("note", note).put("pin", pin))
                    ok = true; msg = "Transfer berhasil. Saldo: " + rp(r.getLong("balance")); pin = ""
                } catch (e: Exception) { ok = false; msg = e.message ?: "Error" }
            }
        }, Modifier.fillMaxWidth()) { Text("Kirim") }
        Msg(msg, ok)
    }
}

@Composable
fun MyQr(nav: (String, String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { phone = Api.call("GET", "/me").getString("phone") } catch (e: Exception) { msg = e.message ?: "Error" }
    }
    Col {
        Back(nav)
        Text("QR Saya", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (phone.isNotEmpty()) {
            val bmp = remember(phone) {
                val m = QRCodeWriter().encode("VINZPAY|$phone", BarcodeFormat.QR_CODE, 600, 600)
                val b = Bitmap.createBitmap(600, 600, Bitmap.Config.RGB_565)
                for (x in 0 until 600) for (y in 0 until 600)
                    b.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                b.asImageBitmap()
            }
            Image(bmp, "QR", Modifier.size(280.dp))
            Text(phone)
            Text("Minta teman scan QR ini untuk menerima transfer.")
        }
        Msg(msg)
    }
}

@Composable
fun Scan(nav: (String, String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r ->
        val t = r.contents
        when {
            t == null -> nav("home", "")
            t.startsWith("VINZPAY|") -> nav("transfer", t.removePrefix("VINZPAY|"))
            t.startsWith("000201") -> nav("qris", t)
            else -> nav("home", "")
        }
    }
    LaunchedEffect(Unit) {
        launcher.launch(ScanOptions().setPrompt("Arahkan ke QR VinzPay atau QRIS").setBeepEnabled(false))
    }
    Col { Text("Membuka kamera...") }
}

@Composable
fun Qris(payload: String, nav: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var merchant by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var fixed by remember { mutableStateOf(0L) }
    var amt by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val r = Api.call("POST", "/qris/parse", JSONObject().put("qris", payload))
            merchant = r.getString("merchant"); city = r.optString("city"); fixed = r.optLong("amount")
        } catch (e: Exception) { msg = e.message ?: "Error" }
    }
    Col {
        Back(nav)
        Text("Bayar QRIS", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (merchant.isNotEmpty()) {
            Text(merchant, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(city)
            if (fixed > 0) Text("Nominal: " + rp(fixed), fontSize = 22.sp)
            else F(amt, "Nominal (Rp)", { amt = it.filter(Char::isDigit) }, KeyboardType.Number)
            F(pin, "PIN", { pin = it }, KeyboardType.NumberPassword, true)
            Button({
                scope.launch {
                    try {
                        val r = Api.call("POST", "/qris/pay", JSONObject().put("qris", payload)
                            .put("amount", amt.toLongOrNull() ?: 0).put("pin", pin))
                        ok = true; msg = "Pembayaran tercatat. Saldo: " + rp(r.getLong("balance")); pin = ""
                    } catch (e: Exception) { ok = false; msg = e.message ?: "Error" }
                }
            }, Modifier.fillMaxWidth()) { Text("Bayar") }
        }
        Msg(msg, ok)
    }
}

@Composable
fun History(nav: (String, String) -> Unit) {
    var items by remember { mutableStateOf(listOf<JSONObject>()) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val a = Api.call("GET", "/history").getJSONArray("items")
            items = (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) { msg = e.message ?: "Error" }
    }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id")) }
    Col {
        Back(nav)
        Text("Riwayat", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Msg(msg)
        if (items.isEmpty() && msg.isEmpty()) Text("Belum ada transaksi.")
        for (t in items) {
            val out = t.getString("dir") == "out"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text((if (out) "- " else "+ ") + rp(t.getLong("amount")),
                        fontWeight = FontWeight.Bold, color = if (out) Color(0xFFC62828) else Color(0xFF2E7D32))
                    Text(t.optString("desc"))
                    Text(fmt.format(Date(t.getLong("ts"))), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun Shop(nav: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var cat by remember { mutableStateOf("Games") }
    var brands by remember { mutableStateOf(listOf<String>()) }
    var brand by remember { mutableStateOf("") }
    var products by remember { mutableStateOf(listOf<JSONObject>()) }
    var sel by remember { mutableStateOf<JSONObject?>(null) }
    var target by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    fun enc(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
    LaunchedEffect(cat) {
        brand = ""; products = emptyList(); sel = null; brands = emptyList(); msg = ""
        try {
            val a = Api.call("GET", "/shop/brands?cat=" + enc(cat)).getJSONArray("brands")
            brands = (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { ok = false; msg = e.message ?: "Error" }
    }
    LaunchedEffect(brand) {
        if (brand.isNotEmpty()) {
            products = emptyList(); sel = null
            try {
                val a = Api.call("GET", "/shop/products?cat=" + enc(cat) + "&brand=" + enc(brand)).getJSONArray("products")
                products = (0 until a.length()).map { a.getJSONObject(it) }
            } catch (e: Exception) { ok = false; msg = e.message ?: "Error" }
        }
    }
    Col {
        Back(nav)
        Text("Toko", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf("Games", "Pulsa", "Data", "E-Money", "PLN", "Voucher")) {
                if (c == cat) Button({ cat = c }) { Text(c) } else OutlinedButton({ cat = c }) { Text(c) }
            }
        }
        val p = sel
        if (p == null) {
            if (brand.isEmpty()) {
                if (brands.isEmpty() && msg.isEmpty()) Text("Memuat...")
                for (b in brands) OutlinedButton({ brand = b }, Modifier.fillMaxWidth()) { Text(b) }
            } else {
                TextButton({ brand = "" }) { Text("← Pilih brand lain") }
                for (x in products) OutlinedButton({ sel = x; msg = "" }, Modifier.fillMaxWidth()) {
                    Text(x.getString("name") + " - " + rp(x.getLong("price")))
                }
            }
        } else {
            Text(p.getString("name"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(rp(p.getLong("price")), fontSize = 22.sp)
            F(target, "ID game / nomor tujuan (MLBB: ID+Zone digabung)", { target = it })
            F(pin, "PIN", { pin = it }, KeyboardType.NumberPassword, true)
            Button({
                scope.launch {
                    try {
                        msg = ""
                        val r = Api.call("POST", "/shop/buy", JSONObject().put("sku", p.getString("sku")).put("target", target).put("pin", pin))
                        val st = r.getString("status")
                        ok = st != "FAILED"
                        msg = when (st) {
                            "SUCCESS" -> "Berhasil! " + r.optString("sn") + " Saldo: " + rp(r.getLong("balance"))
                            "PENDING" -> "Sedang diproses. Cek menu Pesanan."
                            else -> "Gagal, saldo dikembalikan."
                        }
                        pin = ""
                    } catch (e: Exception) { ok = false; msg = e.message ?: "Error" }
                }
            }, Modifier.fillMaxWidth()) { Text("Beli") }
            TextButton({ sel = null }) { Text("Batal") }
        }
        Msg(msg, ok)
    }
}

@Composable
fun Orders(nav: (String, String) -> Unit) {
    var items by remember { mutableStateOf(listOf<JSONObject>()) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val a = Api.call("GET", "/shop/orders").getJSONArray("orders")
            items = (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) { msg = e.message ?: "Error" }
    }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id")) }
    Col {
        Back(nav)
        Text("Pesanan", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Msg(msg)
        if (items.isEmpty() && msg.isEmpty()) Text("Belum ada pesanan.")
        for (o in items) {
            val st = o.getString("status")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(o.getString("name"), fontWeight = FontWeight.Bold)
                    Text("Tujuan: " + o.getString("target") + " - " + rp(o.getLong("price")))
                    Text(st, color = when (st) { "SUCCESS" -> Color(0xFF2E7D32); "FAILED" -> Color(0xFFC62828); else -> Color(0xFFEF6C00) })
                    if (o.optString("sn").isNotEmpty()) Text("SN: " + o.getString("sn"))
                    Text(fmt.format(Date(o.getLong("ts"))), fontSize = 12.sp)
                }
            }
        }
    }
}
