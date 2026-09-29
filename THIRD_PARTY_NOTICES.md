# Third-party notices

YT Quay application code is licensed under GPL-3.0-or-later. See LICENSE.

## Bundled and linked components

| Component | Version | License / source |
| --- | --- | --- |
| youtubedl-android library / ffmpeg / common | 0.18.1 | GPL-3.0; https://github.com/yausername/youtubedl-android/tree/0.18.1 |
| yt-dlp standalone zipimport executable | 2026.08.19 | Unlicense; https://github.com/yt-dlp/yt-dlp/tree/2026.08.19 |
| yt-dlp bundled third-party Python packages | As shipped in the official release | https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/THIRD_PARTY_LICENSES.txt |
| Python Android runtime | As packaged by youtubedl-android 0.18.1 | PSF and component licenses; https://www.python.org/psf/license/ |
| FFmpeg Android runtime and codecs | As packaged by youtubedl-android 0.18.1 | FFmpeg LGPL/GPL build-dependent licenses; https://ffmpeg.org/legal.html |
| QuickJS Android runtime | As packaged by youtubedl-android 0.18.1 | MIT; https://bellard.org/quickjs/ |
| AndroidX (core, appcompat and transitive components) | Resolved by Gradle; see build files | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library | Resolved by Kotlin Gradle plugin | Apache-2.0; https://github.com/JetBrains/kotlin |
| Jackson | Transitive dependency of youtubedl-android | Apache-2.0; https://github.com/FasterXML/jackson |
| Apache Commons IO | Transitive dependency of youtubedl-android | Apache-2.0; https://commons.apache.org/proper/commons-io/ |

The yt-dlp archive in `app/src/main/res/raw/ytdlp` is the unmodified official release file and includes Python source. It is SHA-256 checked during creation of this delivery. The Android native runtime is consumed as published Maven Central artifacts; YT Quay does not recompile or modify it. Upstream repository includes the Android runtime integration and build-related sources. See the upstream projects for component source and original copyright notices.

Release artifacts:

- https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19
- https://github.com/yausername/youtubedl-android/releases/tag/0.18.1
- https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/

Runtime update channels:

- https://github.com/yt-dlp/yt-dlp/releases
- https://github.com/yt-dlp/yt-dlp-nightly-builds/releases

When yt-dlp updates at runtime, its component versions and accompanying licenses may change. The application does not change the licenses of downloaded components.
