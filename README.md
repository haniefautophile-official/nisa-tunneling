# NISA NEXUS

Aplikasi tunneling Android buatan **Hanief Autophile** untuk pelanggan [NisaVPN](https://nisavpn.my.id). Dibangun di atas [v2rayNG](https://github.com/2dust/v2rayNG) (core [Xray](https://github.com/XTLS/Xray-core)), dengan tambahan fitur dan tampilan NISA.

[![Release](https://img.shields.io/github/v/release/haniefautophile-official/nisa-tunneling?label=rilis)](https://github.com/haniefautophile-official/nisa-tunneling/releases/latest)
[![Telegram](https://img.shields.io/badge/Grup-Telegram-blue?logo=telegram)](https://t.me/NisaVPN)
[![WhatsApp](https://img.shields.io/badge/Grup-WhatsApp-brightgreen?logo=whatsapp)](https://chat.whatsapp.com/HHjZrX4BX8UAwWLleXGfrw)

---

## Unduh

Unduh APK terbaru di halaman rilis:

**https://github.com/haniefautophile-official/nisa-tunneling/releases/latest**

> [!TIP]
> Pilih APK sesuai HP. Hampir semua HP baru memakai **arm64-v8a**. HP lama 32-bit memakai **armeabi-v7a**.

**Cara update:** buka aplikasi, menu (tiga garis) > **Check for update**, unduh lalu pasang di atas aplikasi lama. Akun yang sudah ada tetap aman.

---

## Fitur

- Protokol V2Ray/Xray (VMess, VLESS, Trojan, dan lainnya) serta mode SSH dan SlowDNS.
- Profil TLS dengan SNI palsu langsung tersambung tanpa mengisi *Verify peer certificate by name*: sertifikat server dikenali otomatis tiap konek, seperti di NetMod dan HTTP Custom.
- Tab **Main** dan **Log** terpisah, dengan ping otomatis lewat tunnel dan status koneksi yang jelas.
- Ekspor dan impor akun dalam file **.nisa** (bisa terkunci). File `.nisa` bisa dibuka langsung dari Telegram atau file manager lewat **Buka dengan**.
- Penyimpanan config terenkripsi.
- Menu **Hubungi Admin** di dalam aplikasi.

---

## Hubungi Admin

| Kanal | Kontak |
|---|---|
| WhatsApp admin | [0895-2456-4686](https://wa.me/6289524564686) |
| Telegram admin | [@hanief_autophile](https://t.me/hanief_autophile) |
| Grup Telegram | [t.me/NisaVPN](https://t.me/NisaVPN) |
| Grup WhatsApp | [Gabung grup WhatsApp](https://chat.whatsapp.com/HHjZrX4BX8UAwWLleXGfrw) |
| Bot otomatis 24/7 | [t.me/NISA_VPNbot](https://t.me/NISA_VPNbot) |
| Website resmi | [nisavpn.my.id](https://nisavpn.my.id) |

Kalau ada kendala koneksi, kirim ke admin screenshot tab **Log** dan nama profil yang dipakai.

---

## Pengembangan

- Proyek Android ada di folder `V2rayNG/` (Kotlin, Gradle). Build otomatis lewat GitHub Actions (`.github/workflows/nisa-build.yml`) setiap push ke `master`, dan hasilnya terbit sebagai Release.
- Kustomisasi NISA ada di folder `nisa/` dan `sshsocks/`. Isi kontak di aplikasi diatur di `nisa/contact.properties`.
- `libv2ray.aar` dibuat dari [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).

---

## Lisensi dan kredit

Proyek ini turunan dari [v2rayNG](https://github.com/2dust/v2rayNG) oleh 2dust dan kontributor, dengan lisensi yang sama (lihat berkas `LICENSE`). Terima kasih kepada tim [Xray-core](https://github.com/XTLS/Xray-core).
