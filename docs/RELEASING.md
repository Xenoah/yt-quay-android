# プレリリースの作成

1. `versionCode` を増やし、`versionName` を `1.0.0-pre.2` のように変更する。
2. 非公開の署名鍵と `signing.properties` を用意し、`./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease` を実行する。
3. APKの署名・アプリID・バージョンとzip alignmentを確認し、3種類のAPKを `YTQuay-{version}-{abi}.apk` に改名する。
4. APKのSHA-256を `SHA256SUMS` に記録する。検証記録と `docs/releases/{tag}.md` を更新してmainにpushする。
5. そのコミットにタグを付け、GitHubのReleasesから「Pre-release」として公開する。3種類のAPKと `SHA256SUMS` を添付し、本文には手順4のリリースノートを使う。

GitHub CLIを使う場合は、認証済みの自身の環境で次のように公開できます。

```sh
gh release create "$RELEASE_TAG" /path/to/assets/*.apk /path/to/assets/SHA256SUMS \
  --repo Xenoah/yt-quay-android --verify-tag --prerelease \
  --title "YT Quay $RELEASE_TAG" --notes-file "docs/releases/$RELEASE_TAG.md"
```

配布APKの上書き更新には同じ署名鍵が必要です。署名鍵・パスワードはリポジトリやリリースに添付しないでください。GitHub Actionsはpush・タグ・PRでビルド、ユニットテスト、Lintを実行します。
