# VinzPay
Dompet digital: daftar nomor + OTP, PIN 6 digit, top up, transfer ke nomor terdaftar, QR VinzPay, scan QRIS (validasi CRC).

## 1. Server (Termux)
    pkg install nodejs -y
    ./run-server.sh
OTP tampil di terminal server dan di layar app (mode dev). Set `SHOW_OTP=0` + sambungkan SMS gateway di `sendOtp()` untuk produksi.
Di HP yang sama isi Server URL `http://127.0.0.1:3000`; dari HP lain pakai IP server.

## 2. Build APK via GitHub
    gh auth login
    ./push.sh vinzpay
Download APK dari Actions > artifact `VinzPay-debug-apk`.

## 3. Gateway (top up & toko)
Salin `server/.env.example` jadi `server/.env`, isi key, lalu `./run-server.sh`.
- **Top up QRIS** lewat Xendit: app menampilkan QRIS dinamis, saldo bertambah otomatis setelah dibayar (status dicek ke Xendit, jadi tidak butuh URL publik). Di mode test ada tombol "Simulasi bayar".
- **Toko** (top up game, pulsa, data, e-money, PLN) lewat Digiflazz: saldo app dipotong, pesanan dikirim ke Digiflazz, gagal = saldo otomatis refund.
- Tanpa key: mode demo (saldo bertambah langsung, toko nonaktif).

## Catatan
- Key mode test/development gratis. Untuk uang asli, Xendit dan Digiflazz meminta verifikasi akun (KTP/usaha) dan Digiflazz perlu deposit saldo.
- Bayar QRIS merchant lain (scan QRIS toko) dinonaktifkan saat gateway aktif, karena butuh izin PJP. Gunakan Toko.
- Menyimpan saldo orang lain secara komersial butuh izin e-money dari BI/OJK. Untuk pakai pribadi/teman dekat aman.
