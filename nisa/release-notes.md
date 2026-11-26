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
