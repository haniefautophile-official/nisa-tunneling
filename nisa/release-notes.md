Pembaruan 2.4.7:
- V2ray dan Trojan (TLS + WebSocket/HTTPUpgrade) yang fingerprint-nya kosong kini memakai ClientHello kecil, tidak lagi macet di jaringan yang tidak bisa memproses paket TLS besar (tes koneksi: "context deadline exceeded").

Pembaruan 2.4.6:
- Jika tes koneksi gagal, pesan error sekarang menyertakan penyebab asli dari log core (misalnya kegagalan sertifikat TLS), bukan hanya "closed pipe".

Cara update: menu (tiga garis) > Check for update > unduh, lalu pasang di atas aplikasi lama. Akun tetap aman.

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
