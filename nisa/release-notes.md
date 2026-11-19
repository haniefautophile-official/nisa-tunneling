Perbaikan ekspor/impor akun:
- Ikon share: pilih Via Link atau Via File (.nisa), lalu Standar (tanpa kunci) atau Terkunci.
- Terkunci: tetap bisa diimpor tanpa password, tapi isi akun (username, password, payload, dll) tidak bisa dilihat, diedit, atau dibagikan ulang.
- Link ssh:// lengkap (payload, TLS/SNI, proxy, SlowDNS); hasil impor sama persis dengan akun asli.
- Tujuan ping bisa dipilih (google, dns.google, Cloudflare, atau URL sendiri).
- Backup lama NISA1: tetap bisa diimpor.
- Log lebih singkat: saat konek cukup "Tersambung" dan ping; rincian ada di Pengaturan > Log detail.
- Ping bergaya HTTP Custom ("Ping 204 OK (55ms)"), bawaan www.google.com, tujuan bisa dipilih.
- SSH: placeholder [ua] dan [method] di payload didukung. Trojan tanpa TLS bisa disimpan.
- SSH payload dua request (GET lalu Upgrade) di port 80 tidak lagi ditolak karena balasan 301; Trojan/V2ray port 80 tanpa TLS bisa disimpan.
