Pembaruan 2.4.22:
- Tab LOG kini selalu menampilkan tipe record dan alamat DNS yang dipakai SlowDNS (tanpa perlu menyalakan Log detail), supaya mudah mencocokkan dengan server.
- Peringatan dari klien SlowDNS (misalnya tipe record dipaksa kembali ke TXT) kini ikut tampil di tab LOG.
- Jika SlowDNS tidak tersambung (noise handshake: timeout), pastikan Type di profil sama dengan tipe record di server.

Cara update: menu (tiga garis) > Check for update > unduh, lalu pasang di atas aplikasi lama. Akun tetap aman.

Pembaruan 2.4.21:
- SlowDNS kini tersedia di semua jenis akun: SSH, Trojan, VMess, dan VLESS, dengan form yang sama (centang SlowDNS, Type, NS Domain, Public Key, DNS).
- Pilihan Type record diperluas: Otomatis (TXT), TXT, NULL, CNAME, MX, NS, SRV, A, AAAA, dan CAA. Jika satu tipe diblokir operator, coba tipe lain.
- Type record kini ikut tersimpan saat akun dibagikan lewat link dan QR.
- Jika NS Domain atau Public Key kosong saat SlowDNS aktif, muncul peringatan dan profil tidak disimpan.

Cara update: menu (tiga garis) > Check for update > unduh, lalu pasang di atas aplikasi lama. Akun tetap aman.

Pembaruan 2.4.20:
- SlowDNS: waktu tunggu login SSH diperpanjang menjadi 120 detik, karena jabat tangan SSH lewat DNS lambat dan sebelumnya bisa terputus di tengah jalan. Setelah menekan sambung, tunggu sampai muncul "Tersambung".

Cara update: menu (tiga garis) > Check for update > unduh, lalu pasang di atas aplikasi lama. Akun tetap aman.

Pembaruan 2.4.19:
- SlowDNS: kolom DNS yang dikosongkan kini memakai DNS operator (UDP 53), bukan DoH Cloudflare, supaya bisa jalan tanpa kuota di XL dan Axis. Mohon dicoba dan laporkan hasilnya.
- Ingin tetap memakai DoH? Isi kolom DNS dengan https://1.1.1.1/dns-query.

Pembaruan 2.4.18:
- Perbaikan SlowDNS lewat DoH (https://) dan DoT (tls://): nama host DNS kini di-resolve di dalam aplikasi dan sertifikat CA Android dipakai, jadi tidak lagi gagal dengan error "unknown authority" atau "lookup ... connection refused".
- Kolom DNS yang dikosongkan kini memakai DoH Cloudflare lewat alamat IP (1.1.1.1).
- Di jaringan yang memblokir UDP 53 langsung ke server, coba isi kolom DNS dengan alamat DoH, misalnya http://cp.cloudflare.com/generate_204.

Pembaruan 2.4.17:
- SlowDNS kini tersedia untuk akun SSH. Di form SSH centang SlowDNS, lalu isi Type, NS Domain, Public Key, dan DNS.
- Pilihan Type record: Otomatis, TXT, NULL, CNAME, NS, MX, SRV. "Otomatis" memakai TXT.
- Kolom DNS menerima host:port untuk UDP langsung, atau alamat https:// untuk DoH.
- Pengaturan SlowDNS ikut tersimpan, terbagikan lewat link dan file .nisa, serta terimpor kembali.
- Tab LOG menampilkan status tunnel SlowDNS (session ready, stream ready) sebelum baris SSH.

Pembaruan 2.4.16:
- Perbaikan impor akun dari file .nisa, link, atau Bagikan: akun baru kini DITAMBAHKAN ke daftar, tidak lagi menimpa dan menghapus profil yang sudah ada.

Pembaruan 2.4.15:
- Akun yang dibuka dari file .nisa, link, atau Bagikan kini langsung muncul di daftar profil, tanpa perlu menutup dan membuka ulang aplikasi.

Pembaruan 2.4.14:
- File akun .nisa kini bisa dibuka langsung: dari Telegram atau file manager pilih "Buka dengan" lalu NISA TUNNELING, atau Bagikan file ke aplikasi. Akun otomatis diimpor dan layar utama terbuka.

Pembaruan 2.4.13:
- Perbaikan pin sertifikat otomatis (2.4.12 tidak aktif karena start core jalan di main thread). Sertifikat server kini diambil di thread terpisah sebelum core start, jadi profil TLS dengan SNI palsu tersambung tanpa mengisi nama verifikasi. Tab LOG menampilkan baris "Sertifikat server di-pin otomatis" atau "Pin sertifikat otomatis gagal diambil".

Pembaruan 2.4.12:
- Profil TLS dengan SNI palsu kini langsung tersambung seperti di NetMod dan HTTP Custom: sertifikat yang disajikan server di-pin otomatis tiap connect, jadi nama sertifikat tidak lagi diperiksa dan tidak perlu mengisi "Verify peer certificate by name". Profil yang sudah mengisi pin atau nama verifikasi tidak berubah.

Pembaruan 2.4.11:
- Perbaikan TLS dengan SNI palsu: sertifikat server kini dianggap sah juga jika cocok dengan Host WebSocket/HTTPUpgrade, jadi profil server IP atau bug host tidak lagi ditolak core ("certificate is valid for X, not ...").

Pembaruan 2.4.10:
- Log detail kini juga menampilkan penyebab kegagalan dari core (level info) di tab LOG, untuk mencari sebab V2Ray/Trojan tidak tersambung. Nyalakan di Pengaturan > Log detail.

Pembaruan 2.4.9:
- Log "Tersambung" tidak lagi menyesatkan: sekarang baru muncul setelah ping lewat tunnel berhasil. Kalau server terhubung tapi internet tidak lewat, log menulis "Terhubung ke server, tapi internet tidak lewat tunnel".

Pembaruan 2.4.8:
- Pembaruan perbaikan stabilitas koneksi.
Pembaruan 2.4.7:
- V2ray dan Trojan (TLS + WebSocket/HTTPUpgrade) yang fingerprint-nya kosong kini memakai ClientHello kecil, tidak lagi macet di jaringan yang tidak bisa memproses paket TLS besar (tes koneksi: "context deadline exceeded").
- Cek server di tab LOG: kalau handshake TLS bawaan HP macet, otomatis dicoba ulang dengan TLS 1.2 dan hasilnya dicatat.

Pembaruan 2.4.6:
- Jika tes koneksi gagal, pesan error sekarang menyertakan penyebab asli dari log core (misalnya kegagalan sertifikat TLS), bukan hanya "closed pipe".

Pembaruan 2.4.5:
- SSH lewat proxy + payload di port 80 (GET lalu Upgrade) sekarang bisa tersambung.
- Trojan dan V2ray di port 80 tanpa TLS bisa disimpan dan dipakai.
- Banner server di tab LOG tampil rapi, tanpa kode HTML.
- Ping bawaan www.google.com (204) seperti HTTP Custom; gstatic tidak dipakai lagi.
- Log saat konek lebih singkat: cukup "Tersambung". Rincian ada di Pengaturan > Log detail.
- DNS tidak lagi gagal saat SSH mencoba tersambung ulang.

Fitur sebelumnya:
Perbaikan ekspor/impor akun:
- Ikon share: pilih Via Link atau Via File (.nisa), lalu Standar (tanpa kunci) atau Terkunci.
- Terkunci: tetap bisa diimpor tanpa password, tapi isi akun (username, password, payload, dll) tidak bisa dilihat, diedit, atau dibagikan ulang.
- Link ssh:// lengkap (payload, TLS/SNI, proxy, SlowDNS); hasil impor sama persis dengan akun asli.
- Tujuan ping bisa dipilih (google, dns.google, Cloudflare, atau URL sendiri).
- Backup lama NISA1: tetap bisa diimpor.
- SSH: placeholder [ua] dan [method] di payload didukung. Trojan tanpa TLS bisa disimpan.
