package com.example.app_artify2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

public final class NanoBananaApiClient {
    public static final int MAX_UPLOAD_DIMENSION = 1024;

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + "gemini-3.1-flash-image-preview:generateContent";
    private static final int JPEG_QUALITY = 90;

    public Bitmap transform(
            String apiKey,
            byte[] sourceImageBytes,
            String prompt
    )
            throws IOException, JSONException {
        JSONObject request = buildRequest(sourceImageBytes, prompt);
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);

        HttpsURLConnection connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(180_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("x-goog-api-key", apiKey);
        connection.setFixedLengthStreamingMode(payload.length);

        try {
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readBody(
                    statusCode >= HttpURLConnection.HTTP_BAD_REQUEST
                            ? connection.getErrorStream()
                            : connection.getInputStream()
            );
            if (statusCode < HttpURLConnection.HTTP_OK
                    || statusCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException(readErrorMessage(responseBody, statusCode));
            }
            return parseImage(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    public byte[] prepareImage(Bitmap sourceImage, int maxUploadDimension) throws IOException {
        Bitmap uploadBitmap = resizeForUpload(sourceImage, maxUploadDimension);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (!uploadBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)) {
                throw new IOException("画像の送信準備に失敗しました。");
            }
            return outputStream.toByteArray();
        } finally {
            if (uploadBitmap != sourceImage) {
                uploadBitmap.recycle();
            }
        }
    }

    private JSONObject buildRequest(byte[] sourceImageBytes, String prompt)
            throws JSONException {
        String imageData = Base64.encodeToString(sourceImageBytes, Base64.NO_WRAP);

        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", prompt))
                .put(new JSONObject().put(
                        "inline_data",
                        new JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", imageData)
                ));

        JSONArray contents = new JSONArray()
                .put(new JSONObject().put("parts", parts));

        JSONObject generationConfig = new JSONObject()
                .put("responseModalities", new JSONArray().put("IMAGE"));

        return new JSONObject()
                .put("contents", contents)
                .put("generationConfig", generationConfig);
    }

    private Bitmap resizeForUpload(Bitmap sourceImage, int maxUploadDimension) {
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        int largestDimension = Math.max(width, height);
        if (largestDimension <= maxUploadDimension) {
            return sourceImage;
        }

        float scale = maxUploadDimension / (float) largestDimension;
        return Bitmap.createScaledBitmap(
                sourceImage,
                Math.round(width * scale),
                Math.round(height * scale),
                true
        );
    }

    private Bitmap parseImage(String responseBody) throws IOException, JSONException {
        JSONObject response = new JSONObject(responseBody);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("画像が生成されませんでした。");
        }

        JSONArray parts = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts");
        for (int index = 0; index < parts.length(); index++) {
            JSONObject part = parts.getJSONObject(index);
            JSONObject inlineData = part.optJSONObject("inlineData");
            if (inlineData == null) {
                inlineData = part.optJSONObject("inline_data");
            }
            if (inlineData != null && inlineData.has("data")) {
                byte[] bytes = Base64.decode(inlineData.getString("data"), Base64.DEFAULT);
                Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (result != null) {
                    return result;
                }
            }
        }
        throw new IOException("API 応答に変換画像が含まれていません。");
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private String readErrorMessage(String responseBody, int statusCode) {
        try {
            JSONObject error = new JSONObject(responseBody).optJSONObject("error");
            if (error != null && error.optString("message").length() > 0) {
                return error.optString("message");
            }
        } catch (JSONException ignored) {
            // Fall through to an HTTP status message when error JSON is unavailable.
        }
        return "API request failed (HTTP " + statusCode + ").";
    }
}
