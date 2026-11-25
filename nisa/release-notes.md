# 2.4.25

- SlowDNS: klien dnstt (libdnstt.so) sekarang dijalankan dalam mode wire native VayDNS
  (`-clientid-size 1`), bukan `-dnstt-compat`. Overhead per query turun dari 13 byte
  jadi 2 byte, jadi nama query minimum ikut lebih pendek.
- PENTING: ini mengubah format data di jaringan. Server (dnstt-server-multi /
  vaydns-server) WAJIB diganti ke `-clientid-size 1` dan TANPA `-dnstt-compat` di
  server yang sama, kalau tidak jabat tangan gagal total (bukan sekadar lambat).
  Jangan deploy build ini ke user sebelum server test lolos.
- Batas `-max-qname-len` otomatis (dipakai saat jabat tangan timeout berulang)
  disesuaikan ke lantai mode native: domain + 45, bukan domain + 64 seperti di
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
