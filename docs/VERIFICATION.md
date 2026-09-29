# 検証結果 — ClipDock 1.0.0

確認日: 2026-09-29

| 検証 | 結果 |
| --- | --- |
| Kotlin/Android releaseビルド | 成功。ARM64 / ARMv7 / x86_64 APKを生成 |
| Kotlinユニットテスト | 9件成功、失敗0、スキップ0 |
| Android Lint | エラー0。同期SharedPreferences、Application Context保持、スタイル等の警告あり |
| APK署名 | apksigner verify 成功。RSA 3072-bit / APK Signature Scheme v2 |
| APK zip alignment | 成功 |
| Manifest | minSdk 29 / targetSdk 35 / versionCode 1。release版 |
| 同梱yt-dlp | APK内の実体が公式2026.08.19配布ファイルとSHA-256で一致 |
| ネイティブ実行環境 | ARM64 APK内にPython、FFmpeg、ffprobe、QuickJSと各ライブラリを確認 |
| 更新API | アプリと同じHTTPヘッダーで公式最新リリースAPIへ接続、SHA2-256SUMS資産を確認 |
| 保存コマンド | ローカルサンプル動画に対し、動画保存とMP3 192K変換をLinux上の同じyt-dlp版で実行して成功 |
| 完成ファイル検出 | after_move:filepathの出力から実ファイルを検出できることを確認 |
| Android上の起動・実行 | 未確認。この環境のソフトウェア仮想端末で起動テストを完了できなかった |
| 実機ダウンロード・アプリ内更新 | 未確認 |

Android上の起動、MediaStore転送、通知、共有受信、yt-dlp更新・ロールバックの一連の操作は、端末上での追加確認が必要です。ビルド済みであることと、実機上で全機能が動作することは別の検証段階です。

## 納品APK

ファイル: ClipDock-1.0.0-arm64-v8a.apk

サイズ: 61,987,757 bytes

SHA-256:

```
2f0825348460c2cf592304fecf2801c75532cfdc0317f6d755c91318d43f08fb
```

## テスト範囲

- Android共有テキストからURLを抽出
- file URL、オプション文字列、ユーザー情報付きURLを拒否
- 日本語の括弧・句読点を処理
- 主形式とフォールバック形式の両方に画質上限を適用
- 最高画質では上限なし
- MP3の変換指定・ビットレート
- yt-dlp.exe等と区別した正確なチェックサム抽出
- 不正なチェックサム／対象違いを拒否

ユニットテスト結果XMLと署名検証出力を同じdocsフォルダへ添付しています。
