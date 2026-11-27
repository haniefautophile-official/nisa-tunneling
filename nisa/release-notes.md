# 2.4.36

- Watchdog SlowDNS: kalau auto ping gagal 3 kali berturut-turut, proses dnstt dimulai ulang otomatis
  dengan tipe record dan qname yang sama. Proses baru memakai port sumber UDP baru, jadi pemetaan NAT
  operator yang kedaluwarsa (tunnel macet setelah berjalan lama, log `noise handshake: timeout` dan
  `Ping gagal (tunnel tidak membalas)`) terlepas tanpa menyambung ulang manual. Jeda minimal 60 detik
  antar restart. Perlu Auto ping aktif (Pengaturan > Advanced Settings > Auto ping). Kejadiannya tercatat
  di tab Log: `SlowDNS: ping gagal berulang, dnstt dimulai ulang (port sumber baru)`.

# 2.4.35

- Migrasi sekali jalan: profil tersimpan yang memakai Type NULL (manual) dipindahkan ke Otomatis saat
  aplikasi dibuka, supaya ikut diuji oleh probe tipe record otomatis (2.4.34) alih-alih macet di satu
  tipe yang mungkin dipilih dulu karena Otomatis lama gagal. Profil dengan Type lain (TXT, AAAA, dll.)
  tidak diubah.

# 2.4.34

- SlowDNS Type "Otomatis" kini benar-benar otomatis (sebelumnya sama dengan TXT). Saat connect, aplikasi
  menguji semua tipe record (AAAA, TXT, NULL, CNAME, MX, NS, SRV, A, CAA) ke DNS di profil secara paralel
  (sekitar 2 detik), membuang tipe yang tidak dijawab resolver, lalu mencoba paling banyak 4 tipe
  berurutan sampai jabat tangan berhasil. AAAA (tipe server NISA) selalu dicoba pertama selama resolver
  tidak menjatuhkannya. Tipe yang berhasil diingat per jaringan.
- Jalannya Otomatis tercatat di tab Log dengan awalan `SlowDNS Otomatis:` (hasil probe, tipe yang dicoba,
  dan pesan kalau semua tipe gagal).
- Probe hanya berjalan untuk DNS mode UDP. Untuk DoH/DoT atau DNS kosong, probe dilewati dan tipe dicoba
  berurutan mulai dari AAAA.
- Type manual (bukan Otomatis) tidak berubah: tetap memakai tipe yang dipilih.
- Nama aplikasi berganti dari NISA TUNNELING menjadi NISA NEXUS (nama di launcher, judul layar utama, dan nama file APK).
  ID aplikasi tidak berubah, jadi update tetap menimpa versi lama dan profil/config tetap aman.

# 2.4.33

- SlowDNS mode UDP: opsi baru di kolom DNS setelah `|` (semua bawaan kosong, nilai di luar rentang
  di-clamp dan diberi peringatan di Log):

  | Opsi | Rentang | Keterangan |
  |---|---|---|
  | `qname` | 60..253 | nilai tetap `-max-qname-len`, tanpa turun otomatis (tidak di bawah lantai domain) |
  | `queue` | 128..4096 | `-queue-size` |
  | `window` | 32..2048 | `-kcp-window-size`; jika lebih besar dari queue, queue dinaikkan ke window x 2 (maks 4096) |
  | `openstream` | 5s..60s | `-open-stream-timeout` |
  | `idle` | 15s..120s | `-idle-timeout` |

  Contoh: `112.215.198.254|rps=40,workers=20,qname=200,window=512,idle=60s`.
  Tanpa opsi baru, argumen klien sama persis seperti 2.4.32.
- Binary libdnstt baru: KCP mode cepat (nodelay=1, interval 20ms, fast resend 2, ACK segera) dengan RTO minimum
  150ms (bukan 100ms bawaan atau 30ms bawaan mode nodelay). Bisa ditimpa lewat env `NISA_KCP_RXMINRTO` (100..500).
  Ini bawaan binary baru, bukan opsi di kolom DNS.

# 2.4.31

- SlowDNS mode UDP: kolom DNS sekarang boleh memuat opsi penyetelan setelah alamat resolver,
  dipisah `|`. Contoh: `112.215.198.254|rps=40,workers=20,timeout=1500ms`.
  Opsi: `rps` (batas query per detik), `workers` (jumlah worker UDP, bawaan 100),
  `timeout` (tunggu balasan per query, bawaan 2s), `shared=1` (satu socket bersama).
  Tanpa opsi, perilakunya sama seperti 2.4.30. Opsi yang dipakai tercatat di tab Log
  ("SlowDNS UDP: ..."), nilai yang salah diabaikan dan diberi peringatan di Log.

# 2.4.30

- Notifikasi update otomatis: saat aplikasi dibuka, versi terbaru dicek di latar belakang dan
  dialog "Versi baru ditemukan" langsung muncul jika ada update, tanpa harus buka menu
  Cek Update. Jika offline atau GitHub tidak terjangkau, tidak ada pesan apa pun.

# 2.4.29

- SlowDNS (mode UDP): batas tunggu balasan per query dinaikkan dari 500 ms ke 2 detik
  (`-udp-timeout 2s`) supaya balasan yang agak telat dari resolver operator tidak dibuang.
  Jalur DoH dan DoT tidak berubah.
- Cek update di dalam aplikasi diperbaiki: pembanding versi tidak lagi gagal pada tag
  yang memuat huruf, dan pemilihan file APK mencoba semua ABI perangkat sebelum menyerah.

# 2.4.28

- SlowDNS: klien kembali ke format dnstt klasik (`-dnstt-compat`) supaya cocok dengan
  server produksi (dnstt-server di port 5300). Build 2.4.26 dan 2.4.27 memakai format
  native (`-clientid-size 2`) yang tidak dikenali server itu, sehingga jabat tangan gagal.
  Pengguna 2.4.27 cukup update ke versi ini.
- Port lokal klien tetap 10899 dan DoH bawaan tetap `https://1.1.1.1/dns-query`
  (perbaikan dari 2.4.27).
- Mode native tetap tersedia di kode (`USE_DNSTT_COMPAT = false`) untuk nanti, setelah
  server diganti ke `-clientid-size 2`.

# 2.4.27

- Perbaikan SlowDNS: port lokal klien dnstt dipindah dari 10808 ke 10899. Sebelumnya
  bentrok dengan port SOCKS Xray (10808), sehingga core gagal start dengan error
  "address already in use".
- Perbaikan SlowDNS: DoH bawaan sekarang `https://1.1.1.1/dns-query` (bukan
  `cloudflare-dns.com`). Klien Go di Android tidak punya resolver sistem, jadi lookup
  nama DoH gagal ke `[::1]:53`. Kalau di profil field DoH berisi URL berhostname, ganti ke
  URL berbasis IP.

# 2.4.26 (sebelumnya)

- SlowDNS: klien dnstt (libdnstt.so) sekarang dijalankan dalam mode wire native VayDNS
  (`-clientid-size 2`, sama dengan bawaan vaydns-server), bukan `-dnstt-compat`. Overhead per query turun dari 13 byte
  jadi 2 byte, jadi nama query minimum ikut lebih pendek.
  Catatan: build sebelumnya masih mengirim format compat; mode native baru aktif di build ini.
- PENTING: ini mengubah format data di jaringan. Server (dnstt-server-multi /
  vaydns-server) WAJIB diganti ke `-clientid-size 2` dan TANPA `-dnstt-compat` di
  server yang sama, kalau tidak jabat tangan gagal total (bukan sekadar lambat).
  Jangan deploy build ini ke user sebelum server test lolos.
- Batas `-max-qname-len` otomatis (dipakai saat jabat tangan timeout berulang)
  disesuaikan ke lantai mode native: domain + 45 + clientid (65 untuk dns.nisavpn.biz.id), bukan domain + 64 seperti di
  mode compat lama.
- Perubahan ini bagian dari investigasi kenapa tunnel gagal konek di resolver
  operator (XL) yang membuang query DNS dengan nama di atas ~49 karakter.
  Berdasarkan source `net2share/vaydns`, mode native menghemat ~15-18 karakter per
  query dibanding compat, tapi dengan domain sepanjang `dns.nisavpn.biz.id` (18
  karakter) nama query minimum diperkirakan masih sekitar 59-64 karakter -- di
  atas 49. Ini perlu divalidasi dengan capture VPS yang sebenarnya, bukan dianggap
  pasti berhasil. Kalau masih di atas 49 setelah tes, langkah berikutnya adalah
  memendekkan domain tunnel (bukan patch app lagi), karena domain yang 18 karakter
  itu sendiri yang makan sebagian besar jatah 49 karakter.
