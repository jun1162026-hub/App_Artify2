# App_Artify2 - Nano Banana 2 Style Editor

Android Studio の既存プロジェクトへマージするための、最小構成の Java/XML 実装です。写真フォルダから画像を 1 枚選択し、画風を選ぶと、Gemini API の **Nano Banana 2** (`gemini-3.1-flash-image-preview`) に画像編集を依頼して結果を表示します。

## 前提

- Java のみでアプリコードを実装
- XML Views (`activity_main.xml`)
- Kotlin DSL (`build.gradle.kts`) の既存 Android Studio プロジェクトへマージ
- Minimum SDK: API 23 (Android 6.0 Marshmallow)
- ネットワーク接続と Google AI Studio の paid API key が必要
- API キーは画面で都度入力し、保存・ソース埋め込みを行わない
- 生成・編集画像には Google の SynthID watermark が含まれる

## 追加・置換するファイル

既存の Empty Views Activity プロジェクトを前提に、次のファイルだけをマージします。

```text
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/app_artify2/MainActivity.java
app/src/main/java/com/example/app_artify2/NanoBananaApiClient.java
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
```

このリポジトリでは package / namespace を `com.example.app_artify2` としています。Android Studio 側の namespace が異なる場合は、Java ファイルの `package` 宣言と格納ディレクトリ、manifest の activity 解決先をプロジェクト側に合わせてください。

`AndroidManifest.xml` は初期プロジェクトの launcher icon と theme が存在する前提の例です。プロジェクト側の icon / theme / backup 属性名が異なる場合は、それらを保持したまま `INTERNET` permission、`MainActivity`、Photo Picker 用 `service` をマージしてください。

## Gradle 確認

新しいネットワークライブラリは不要です。API 呼び出しは Android 標準の `HttpsURLConnection` と `org.json` で行います。

Photo Picker の Java API を利用するため、既存の `app/build.gradle.kts` に AndroidX Activity がない場合のみ追加してください。

```kotlin
dependencies {
    implementation("androidx.activity:activity:1.13.0")
}
```

既存プロジェクトの `minSdk` は次の値であることを確認してください。

```kotlin
android {
    defaultConfig {
        minSdk = 23
    }
}
```

## 使用方法

1. Google AI Studio で Nano Banana 2 を使用可能な paid API key を取得します。
2. アプリを起動し、API キーを入力します。
3. `写真を選択` から写真フォルダ内の画像を選びます。
4. 画風を選択し、`画風を変換` をタップします。
5. API から返った変換済み画像が画面下部に表示されます。

API キーはプロトタイプの実行中のみメモリに保持され、保存しません。配布アプリにする場合は、API キーをクライアントに渡さない認証・プロキシ設計へ移行してください。

## API 実装

- Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image-preview:generateContent`
- Header: `x-goog-api-key`
- Input: style instruction text + JPEG の base64 `inline_data`
- Output: response parts に含まれる base64 `inlineData` 画像

写真は通信量を抑えるため、送信前に最大辺 1280 px 以下の JPEG に縮小します。保存機能や撮影機能は追加せず、Photo Picker で既存写真を選ぶ最小プロトタイプに留めています。

## References

- [Nano Banana image generation - Gemini API](https://ai.google.dev/gemini-api/docs/image-generation)
- [Build with Nano Banana 2 - Google Blog, February 26, 2026](https://blog.google/innovation-and-ai/technology/developers-tools/build-with-nano-banana-2/)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
