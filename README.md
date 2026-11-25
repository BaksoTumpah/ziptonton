# ZipTonton

Pemutar video Android yang memutar film **langsung dari dalam file ZIP** tanpa ekstrak.

- Video tidak masuk galeri (tetap tersimpan di dalam ZIP).
- ZIP dibuka **read-only**: file asli tidak pernah diubah.
- Byte video dikirim apa adanya ke ExoPlayer: kualitas sama persis.
- Menit terakhir tiap part disimpan otomatis (tiap 2 detik + saat keluar). Besok tinggal "Lanjutkan menonton".
- Part berurutan alami (Part 2 sebelum Part 10), lanjut otomatis ke part berikutnya.
- Mendukung ZIP64 (file > 4 GB). ZIP berpassword belum didukung.

## Tips
Buat ZIP dengan kompresi **Store / Tanpa kompresi** (ZArchiver: Level kompresi = "Tanpa kompresi";
7-Zip: Compression level = Store). Video memang tidak bisa dikompres lagi, jadi ukurannya sama, tapi
geser menit (seek) jadi instan. ZIP terkompresi biasa tetap bisa diputar, hanya geser menit lebih lambat.

## Build
Push ke `main` → GitHub Actions build APK release bertanda tangan → unduh dari tab **Releases** atau **Actions**.
