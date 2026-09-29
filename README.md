# YT Quay

Kotlin製のAndroid向けyt-dlpフロントエンド。動画・音声を端末の `Download/YTQuay` に保存します。

初回プレリリース `v1.0.0-pre.1` のAPKは検証済みです。GitHub Releasesへの公開待ちです。[APK・リリース一覧](https://github.com/Xenoah/yt-quay-android/releases) · [検証記録](docs/VERIFICATION.md)

ビルド・ユニットテスト9件・Lint・APK署名を検証済みです。**Android実機での起動・保存・更新・復元は未確認**のため、動作確認用のプレリリースです。

以前のClipDock版と同じアプリID・署名を維持し、上書き更新できる構成です。設定と履歴を引き継ぎ、新しいファイルを `Download/YTQuay` に保存します。以前のファイルは `Download/ClipDock` に残ります。

## インストール・使用方法

1. `YTQuay-1.0.0-pre.1-arm64-v8a.apk` をAndroid端末へ保存して開き、インストールします。インストール元の許可を求められた場合は、そのアプリの設定で許可します。
2. URLを貼り付け、形式と画質を選んで「保存を開始」。他アプリの共有メニューから「YT Quay」にURLを渡すこともできます。
3. 「履歴」から保存したファイルを開く／共有できます。保存したファイルは通常のファイル管理アプリでも確認できます。
4. サイト側の変更で取得できなくなった場合は「更新設定」→「今すぐ更新する」。必要に応じてNightlyに切り替えます。

対応: Android 10 / API 29以降。通常のスマートフォンにはARM64版を選んでください。ARMv7版とx86_64版も用意しています。

## 主な機能

- 動画: 1080p、720p、最高画質。H.264/AACを優先し、ソースに応じてMP4/MKVへ結合。
- 音声: 元の音声形式を保存、またはMP3 192 kbpsへ変換。
- 通知付きフォアグラウンドサービスでダウンロード。ダウンロード中のキャンセル。
- AndroidのMediaStoreを利用。広範なストレージ権限は不要。
- 完了履歴100件、開く／共有、処理ログのコピー。
- 完成後に保存先へ転送できなかったファイルの回収。
- UIはKotlinとAndroid Viewsで実装。WebView、外部ダウンロードサーバー、広告、解析SDKなし。

## yt-dlp更新

yt-dlpのPython実行ファイルとAndroidアプリを分離しています。

| 項目 | 動作 |
| --- | --- |
| Stable | `yt-dlp/yt-dlp` の最新リリース |
| Nightly | `yt-dlp/yt-dlp-nightly-builds` の最新リリース |
| 手動更新 | 「今すぐ更新する」で確認・ダウンロード・差し替え |
| 自動更新 | 初期値OFF。ON時、アプリ起動時に24時間に1回まで確認・更新 |
| 整合性 | 同じ公式リリースの `SHA2-256SUMS` とSHA-256を照合 |
| 差し替え | アプリ専用領域へ保存しAtomicFileで差し替え。操作は直列化 |
| 起動確認 | 差し替え後に `--version` を実行。30秒以内に動作しなければ復元 |
| 復元 | 直前の版を保持。更新中断後の次回起動でも復元 |
| 前の版へ戻す | 設定ボタンで復元し、自動更新をOFFにする |

SHA-256照合は配信ファイルの破損確認です。別経路の署名検証は実装していません。更新元はHTTPSの公式GitHubリリースに固定し、任意URLからのコード投入機能はありません。

同梱yt-dlp: **2026.08.19**。同梱ファイルのSHA-256:

```
1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6
```

Python・FFmpeg・QuickJSのネイティブ部分はAPK内蔵のため、その更新や、将来のyt-dlpが要求するPythonバージョンの変更にはアプリの再ビルドが必要です。YouTubeのJS解析に必要な追加コンポーネントはyt-dlpの `--remote-components ejs:github` で取得します。

## 範囲・制約

- この版はURLを1件ずつ保存します。プレイリスト全件保存、ログイン・Cookie取り込み、DRM処理はありません。
- 「最高画質」の実際の解像度・形式は配信元に依存します。常にMP4へ再エンコードする設定ではありません。
- 音声変換・動画結合中は完了を待ちます。キャンセルやOS終了後の途中ファイルは自動再開しません。
- Android 15以降はバックグラウンドのdataSyncサービスにOSの実行時間制限があります。時間切れ時はサービスを停止します。
- 一時ファイルと保存先への転送時には、最終ファイルサイズのおよそ2倍の空き容量が必要です。
- 履歴はアプリ内データです。アプリをアンインストールすると履歴やyt-dlp更新内容は消えます。共有Download内へ保存済みのファイルは残ります。
- ダウンロード元の仕様・地域制限・認証・レート制限によって取得できないURLがあります。全サイトでの動作を保証するものではありません。

## ビルド

必要環境: JDK 17、Android SDK Platform 35 / Build Tools 35.0.0。Gradle Wrapper 8.11.1、AGP 8.9.1、Kotlin 2.1.20。

```sh
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Windows:

```bat
gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Android Studioでこのフォルダを開いても構いません。`local.properties` は利用環境のSDKパスを指定してください。

`signing.properties` がない場合、release出力は未署名です。配布版と同じ署名で更新する場合は、非公開の署名バックアップZIPをプロジェクト直下へ展開します。`versionCode` を増やしてビルドしてください。署名鍵・パスワードをGitHubへ登録しないでください（`.gitignore` に登録済み）。

署名を準備せず動かす場合:

```sh
./gradlew :app:assembleDebug
```

debug版の署名は配布release版と異なるため、release版へ上書きできません。release版のアンインストールは履歴・設定を消すので、上書き更新には同じ署名を使ってください。

ABI別APKの出力先: `app/build/outputs/apk/release/`。ARM64、ARMv7、x86_64をビルドします。

## ソース構成

| ファイル | 役割 |
| --- | --- |
| `MainActivity.kt` | 日本語UI・共有受信・履歴 |
| `DownloadService.kt` | フォアグラウンド処理・通知・キャンセル |
| `Engine.kt` | 初期化・公式更新・チェックサム・復元 |
| `MediaFiles.kt` | MediaStore保存・未保存ファイル回収 |
| `DownloadSpec.kt` | URL検証・形式選択・チェックサム解析 |
| `AppState.kt` | 状態通知・設定・履歴 |

アプリ本体: GPL-3.0-or-later。依存物は `THIRD_PARTY_NOTICES.md` 参照。検証結果は `docs/VERIFICATION.md` を参照してください。
