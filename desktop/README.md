# NadaTube Desktop (Windows / macOS / Linux)

Pemutar audio YouTube tanpa iklan untuk desktop, dibangun dengan
[Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
di atas logika yang sama dengan aplikasi Android (`:core`).

Versi desktop saat ini: **1.2.0** (Android tetap 1.1.0 — penomoran terpisah).

## Yang sudah jalan

- Cari lagu/artis/album/playlist (YouTube Music / InnerTube, klien VISIONOS dkk.)
- Putar AAC itag 140 (128 kbps) — diunduh penuh ke cache, lalu didekode
- Campuran/radio otomatis dari lagu yang sedang diputar
- Riwayat, lagu disukai, unduhan offline (.m4a + tag)
- Lirik tersinkron (lrclib.net)
- Lewati segmen SponsorBlock
- Sleep timer (15/30/60 menit, akhir lagu)
- Tema Terang / Gelap / Ikuti Sistem (palet sama dengan Android)
- Kualitas stream Terbaik / Hemat data

## Arsitektur

```
repo/
  core/     logika murni bersama Android+desktop (extractor, model, playback,
            lyrics, sponsorblock, download, theme) — tanpa dependensi Android
  app/      aplikasi Android (Media3, Room, Coil)
  desktop/  aplikasi Compose Multiplatform (JVM)
    src/main/kotlin/com/nadatube/desktop/
      Main.kt            entry point + mode --smoke / --smoke-decode
      audio/
        Fmp4Demuxer.kt   demuxer fMP4 (fragmented + progresif) untuk AAC YouTube
        AacDecoder.kt    dekoder AAC berbasis JAAD (pure Java, tanpa native)
        AudioSink.kt     tujuan PCM: JavaSound / palsu (test) / null (smoke)
        DesktopPlayer.kt mesin putar: cache, antrean, SponsorBlock, sleep timer
      data/
        DesktopStore.kt  pengaturan + riwayat + unduhan (JSON di direktori config)
        DesktopServices.kt  merakit OkHttp + InnerTube + resolver + player
      ui/
        DesktopTheme.kt  token warna (disalin dari palet Android)
        DesktopApp.kt    layar Beranda / Cari / Pustaka / Pengaturan + player bar
```

## Kenapa AAC saja (bukan Opus)

JavaSound tidak bisa memutar AAC maupun Opus. AAC didekode dengan
[JAAD](https://github.com/timothysomething/jaad) (`com.tianscar.javasound:jaad:0.9.4`,
pure Java). Untuk Opus tidak ada dekoder pure-Java yang tepercaya di Maven Central
(Concentus hanya lewat JitPack), jadi desktop memilih stream AAC itag 140 dan
menolak Opus dengan pesan yang jelas. Menambah Opus berarti membundel library
native (libopus) per OS — dicatat sebagai pekerjaan lanjutan, bukan v1.2.0.

## Build

```sh
# Test (Linux/macOS/Windows — butuh JDK 17)
./gradlew :desktop:test

# Jalankan dari source (butuh perangkat audio)
./gradlew :desktop:run

# Windows: installer .msi + .exe (HANYA bisa di runner Windows — jpackage
# membuat installer untuk OS tempat ia berjalan; di Linux tidak bisa membuat .msi)
./gradlew :desktop:packageMsi :desktop:packageExe :desktop:createDistributable

# macOS: ./gradlew :desktop:packageDmg
# Linux:  ./gradlew :desktop:createDistributable   (portable, tanpa installer)
```

Hasil Windows: `desktop/build/compose/binaries/main/msi/*.msi`,
`desktop/build/compose/binaries/main/exe/*.exe`,
portable: `desktop/build/compose/binaries/main/app/`.

Rilis: dorong tag `desktop-v*` (mis. `desktop-v1.2.0`) — workflow
`.github/workflows/desktop.yml` akan menguji, membangun installer di
`windows-latest`, dan melampirkan .msi/.exe ke rilis GitHub. APK Android
tetap memakai tag `v*` dan workflow `android.yml`.

## Smoke test tanpa audio

```sh
# Buka jendela, keluar sendiri setelah 5 detik (dipakai di VM tanpa audio)
./gradlew :desktop:run --args="--smoke"

# Dekode satu file .m4a lewat jalur player sungguhan (tanpa jendela/audio)
./gradlew :desktop:run --args="--smoke-decode /path/ke/lagu.m4a"
```

## Test

`desktop/src/test/kotlin/com/nadatube/desktop/`:

- `Fmp4DemuxerTest` — fixture nada 440 Hz (ffmpeg): fMP4 terfragmentasi
  88 frame, ~2023 ms; MP4 progresif lewat tabel stbl.
- `AacDecoderTest` — JAAD mendekode frame menjadi PCM; frekuensi dominan
  dicek 440 Hz dengan algoritma Goertzel.
- `DesktopPlayerTest` — end-to-end melawan MockWebServer (cermin test yang
  dikerjakan Hoplite): AAC itag 140 diunduh, 88 frame, ~2023 ms, riwayat
  berisi videoId, file cache `aaaaaaaaaaa_140.m4a` ada, visitorData `VD1`
  terkirim di body request player.
- `DesktopStoreTest` — pengaturan/riwayat/suka bertahan setelah reload.

Jalankan: `./gradlew :desktop:test`.

## Catatan VM (pengembangan di VM ini)

Dua quirk sandbox yang sempat menghentikan build/test — keduanya sudah
terbukti dan solusinya terdokumentasi di sini, bukan ditebak:

1. **Gradle daemon "rusak" — ternyata bukan daemon.** Egress proxy sel ini
   mengintersep TCP keluar dari soket **dual-stack (IPv6)** JVM, termasuk
   loopback ke daemon Gradle dan ke MockWebServer. Soket IPv4 murni tidak
   tersentuh (terbukti: klien Java gagal, klien Python berhasil, dan Java
   dengan `-Djava.net.preferIPv4Stack=true` berhasil). Solusi: jalankan
   Gradle/JVM dengan `-Djava.net.preferIPv4Stack=true`
   (mis. `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"`).
2. **TLS ke `dl.google.com` di-MITM proxy** dengan CA `Hatch Sandbox Egress CA`
   (sementara `plugins.gradle.org`/`repo.maven.apache.org` lolos end-to-end).
   JDK tidak memercayai CA itu, jadi resolusi AGP gagal. Solusi: impor CA
   sistem ke trust store JDK yang dipakai Gradle:
   `keytool -import -trustcacerts -cacerts -alias hatch-egress-ca
   -file /etc/ssl/certs/hatch-egress-ca.pem`.
   Unduhan dependensi Gradle lewat proxy diatur oleh init script lokal
   `~/.gradle/init.d/proxy.init.gradle.kts` (membaca `HTTPS_PROXY` dari env
   saat runtime; tidak ada kredensial yang ditulis di file mana pun).
3. **Marker plugin AGP di portal**: `plugins.gradle.org` me-redirect marker
   `com.android.application` (dan semua marker plugin Google) ke Maven Central
   (303), padahal marker itu tidak ada di Central (404). Solusi di
   `settings.gradle.kts`: `resolutionStrategy.eachPlugin` memetakan
   `com.android.application` langsung ke modul
   `com.android.tools.build:gradle:<versi>` di repo Google — versi dan artefak
   sama, perilaku build tidak berubah.

## Batasan v1.2.0

- Dekode AAC saja; Opus ditolak dengan pesan jelas.
- Tidak ada equalizer (butuh DSP; preset Android tidak dibawa ke desktop).
- Tidak ada keystore rilis — installer ditandatangani debug/unsigned.
- Thumbnail dimuat lewat OkHttp + ImageIO (bukan Coil).
