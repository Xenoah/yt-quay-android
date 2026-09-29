# 検証結果 — YT Quay 1.0.0-pre.1

確認日: 2026-09-30（日本時間）

| 検証 | 結果 |
| --- | --- |
| releaseビルド | ARM64 / ARMv7 / x86_64の3種類に成功 |
| ユニットテスト | 9件成功、失敗0 |
| Android Lint | エラー0、警告36件 |
| APK署名 | 3種類とも検証成功。旧ClipDock版と同じ証明書 |
| zip alignment | 3種類とも検証成功 |
| Manifest | アプリID io.xenoah.clipdock、表示名 YT Quay、versionCode 2、minSdk 29、targetSdk 35 |
| 同梱yt-dlp | 3種類のAPK内の実体がソース内の公式2026.08.19ファイルとSHA-256で一致 |
| Android実機での起動・保存・更新・復元 | 未確認 |

実機での通知、共有受信、MediaStoreへの保存、yt-dlp初期化・更新・ロールバックは追加確認が必要です。
Lint警告には同期SharedPreferences、Application Context保持、非推奨UI API、スタイルなどが含まれます。

## APK

| ファイル | サイズ（bytes） |
| --- | ---: |
| YTQuay-1.0.0-pre.1-arm64-v8a.apk | 61988149 |
| YTQuay-1.0.0-pre.1-armeabi-v7a.apk | 55335333 |
| YTQuay-1.0.0-pre.1-x86_64.apk | 64979927 |

SHA-256:

```
d08ab02c920e7ed6ea06124719670c3ffbbe032377256d9234000e6d82bc568e  YTQuay-1.0.0-pre.1-arm64-v8a.apk
0c0c62e0d7488037830ed584096076df4f3301abc9094c47edcb0d35bad8c161  YTQuay-1.0.0-pre.1-armeabi-v7a.apk
ff0a8e5a6a87f8d7b30a96ef28b53de755ce30065efd1e60bb4f13335566ae49  YTQuay-1.0.0-pre.1-x86_64.apk
```

## 補足

旧版で実行したLinux上のサンプル動画保存・MP3変換の記録は `download-command-results.json` に残しています。Android実機の検証結果ではありません。ユニットテスト結果は `unit-test-results.xml`、署名は `apk-signature.txt` を参照してください。
