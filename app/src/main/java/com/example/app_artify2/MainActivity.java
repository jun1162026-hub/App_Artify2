package com.example.app_artify2;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.activity.result.contract.ActivityResultContracts.TakePicture;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int MAX_PREVIEW_DIMENSION = 2048;
    private static final String API_KEY = "Your_API_Key";
    private static final String[] STYLE_PROMPTS = {
            "Transform the provided photograph into a delicate watercolor painting "
                    + "with soft washes, textured paper, and gentle pigment edges.",
            "Transform the provided photograph into traditional Japanese ukiyo-e art "
                    + "with clean ink outlines, flat color blocks, and woodblock print texture.",
            "Transform the provided photograph into an expressive oil painting "
                    + "with visible brush strokes, layered paint, and rich museum-style color.",
            "Transform the provided photograph into a hand-painted anime background scene "
                    + "with warm light, detailed scenery, and cinematic color.",
            "Transform the provided photograph into a painting inspired by Vincent van Gogh, "
                    + "with swirling directional brushwork, thick impasto, and vivid cobalt-blue "
                    + "and yellow contrasts.",
            "Transform the provided photograph into a painting inspired by Claude Monet, "
                    + "with softly broken color, luminous atmospheric light, and gently "
                    + "dissolved edges.",
            "Transform the provided photograph into a Cubist painting inspired by Pablo Picasso, "
                    + "with geometric facets, multiple viewpoints, strong outlines, and balanced "
                    + "ochre and blue planes.",
            "Transform the provided photograph into a decorative painting inspired by Gustav "
                    + "Klimt, with golden mosaic-like ornament, patterned surfaces, and elegant "
                    + "flat composition.",
            "Transform the provided photograph into a painting inspired by Johannes Vermeer, "
                    + "with quiet window light, soft realism, and deep ultramarine and warm "
                    + "yellow accents.",
            "Transform the provided photograph into an expressionist painting inspired by "
                    + "Edvard Munch, with flowing contours, intense color contrast, and a "
                    + "dramatic emotional atmosphere."
    };

    private ActivityResultLauncher<PickVisualMediaRequest> photoPicker;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ExecutorService executorService;
    private final NanoBananaApiClient apiClient = new NanoBananaApiClient();

    private Button selectPhotoButton;
    private Button takePhotoButton;
    private Button convertButton;
    private Button saveButton;
    private ImageView inputImageView;
    private ImageView outputImageView;
    private Spinner styleSpinner;
    private ProgressBar progressBar;
    private TextView statusText;

    private Bitmap inputBitmap;
    private Bitmap outputBitmap;
    private byte[] uploadInputBytes;
    private Uri pendingCameraUri;
    private File pendingCameraFile;
    private boolean processing;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        executorService = Executors.newSingleThreadExecutor();
        photoPicker = registerForActivityResult(new PickVisualMedia(), this::onPhotoSelected);
        cameraLauncher = registerForActivityResult(new TakePicture(), this::onPhotoTaken);
        storagePermissionLauncher = registerForActivityResult(
                new RequestPermission(),
                permissionGranted -> {
                    if (permissionGranted) {
                        saveGeneratedImage();
                    } else {
                        setStatus(R.string.error_storage_permission);
                    }
                }
        );

        selectPhotoButton.setOnClickListener(view -> selectPhoto());
        takePhotoButton.setOnClickListener(view -> takePhoto());
        convertButton.setOnClickListener(view -> convertImage());
        saveButton.setOnClickListener(view -> requestSaveImage());
        updateControls();
    }

    private void bindViews() {
        selectPhotoButton = findViewById(R.id.select_photo_button);
        takePhotoButton = findViewById(R.id.take_photo_button);
        convertButton = findViewById(R.id.convert_button);
        saveButton = findViewById(R.id.save_button);
        inputImageView = findViewById(R.id.input_image_view);
        outputImageView = findViewById(R.id.output_image_view);
        styleSpinner = findViewById(R.id.style_spinner);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
    }

    private void selectPhoto() {
        photoPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void onPhotoSelected(Uri uri) {
        if (uri == null) {
            setStatus(R.string.status_cancelled);
            return;
        }
        loadPhoto(uri, null);
    }

    private void takePhoto() {
        try {
            clearPendingCameraFile();
            File cameraDirectory = new File(getCacheDir(), "camera");
            if (!cameraDirectory.exists() && !cameraDirectory.mkdirs()) {
                throw new IOException("Unable to create camera directory.");
            }
            pendingCameraFile = File.createTempFile("artify_capture_", ".jpg", cameraDirectory);
            pendingCameraUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    pendingCameraFile
            );
            cameraLauncher.launch(pendingCameraUri);
        } catch (IOException | IllegalArgumentException exception) {
            clearPendingCameraFile();
            showFailure(getString(R.string.error_camera));
        }
    }

    private void onPhotoTaken(Boolean imageSaved) {
        if (Boolean.TRUE.equals(imageSaved) && pendingCameraUri != null) {
            Uri uri = pendingCameraUri;
            File temporaryFile = pendingCameraFile;
            pendingCameraUri = null;
            pendingCameraFile = null;
            loadPhoto(uri, temporaryFile);
            return;
        }
        clearPendingCameraFile();
        setStatus(R.string.status_camera_cancelled);
    }

    private void loadPhoto(Uri uri, File temporaryFile) {
        setProcessing(true);
        setStatus(R.string.status_loading);
        executorService.execute(() -> {
            try {
                Bitmap bitmap = loadSelectedBitmap(uri);
                runOnUiThread(() -> showInputImage(bitmap));
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> showFailure(getString(R.string.error_image)));
            } finally {
                if (temporaryFile != null) {
                    temporaryFile.delete();
                }
            }
        });
    }

    private void convertImage() {
        if (inputBitmap == null || processing) {
            return;
        }
        if (API_KEY.isEmpty() || "Your_API_Key".equals(API_KEY)) {
            setStatus(R.string.error_api_key);
            return;
        }

        int styleIndex = styleSpinner.getSelectedItemPosition();
        if (styleIndex < 0 || styleIndex >= STYLE_PROMPTS.length) {
            return;
        }
        String prompt = STYLE_PROMPTS[styleIndex]
                + " Preserve the subject, composition, and recognizable details of the "
                + "original photograph. Return one edited image without added text or borders.";

        setProcessing(true);
        setStatus(R.string.status_converting);
        Bitmap source = inputBitmap;
        byte[] encodedSource = uploadInputBytes;
        executorService.execute(() -> {
            try {
                byte[] preparedImage = encodedSource;
                if (preparedImage == null) {
                    preparedImage = apiClient.prepareImage(
                            source,
                            NanoBananaApiClient.MAX_UPLOAD_DIMENSION
                    );
                }
                Bitmap result = apiClient.transform(
                        API_KEY,
                        preparedImage,
                        prompt
                );
                byte[] cachedImage = preparedImage;
                runOnUiThread(() -> showOutputImage(result, cachedImage));
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? getString(R.string.error_image)
                        : exception.getMessage();
                runOnUiThread(() -> showFailure(
                        getString(R.string.error_request, message)
                ));
            }
        });
    }

    private void requestSaveImage() {
        if (outputBitmap == null || processing) {
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveGeneratedImage();
    }

    private void saveGeneratedImage() {
        if (outputBitmap == null || processing) {
            return;
        }
        Bitmap bitmapToSave = outputBitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (bitmapToSave == null) {
            showFailure(getString(R.string.error_save));
            return;
        }

        setProcessing(true);
        setStatus(R.string.status_saving);
        executorService.execute(() -> {
            try {
                writeGeneratedImage(bitmapToSave);
                runOnUiThread(() -> {
                    if (!destroyed) {
                        setProcessing(false);
                        setStatus(R.string.status_saved);
                    }
                });
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> showFailure(getString(R.string.error_save)));
            } finally {
                bitmapToSave.recycle();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void writeGeneratedImage(Bitmap bitmap) throws IOException {
        String fileName = "Artify_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Artify"
            );
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File directory = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Artify"
            );
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Unable to create output directory.");
            }
            values.put(MediaStore.Images.Media.DATA, new File(directory, fileName).getAbsolutePath());
        }

        Uri outputUri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
        );
        if (outputUri == null) {
            throw new IOException("Unable to create media entry.");
        }

        try {
            try (OutputStream outputStream = getContentResolver().openOutputStream(outputUri)) {
                if (outputStream == null
                        || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                    throw new IOException("Unable to write generated image.");
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, complete, null, null);
            }
        } catch (IOException | RuntimeException exception) {
            getContentResolver().delete(outputUri, null, null);
            throw exception;
        }
    }

    private Bitmap loadSelectedBitmap(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("Unable to open image.");
            }
            BitmapFactory.decodeStream(inputStream, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Invalid image.");
        }
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        int sampleSize = 1;
        while (largest / sampleSize > MAX_PREVIEW_DIMENSION) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("Unable to open image.");
            }
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            if (bitmap == null) {
                throw new IOException("Unable to decode image.");
            }
            return bitmap;
        }
    }

    private void showInputImage(Bitmap bitmap) {
        if (destroyed) {
            bitmap.recycle();
            return;
        }
        recycleInputBitmap();
        inputBitmap = bitmap;
        uploadInputBytes = null;
        inputImageView.setImageBitmap(bitmap);
        inputImageView.setVisibility(View.VISIBLE);
        recycleOutputBitmap();
        setProcessing(false);
        setStatus(R.string.status_selected);
    }

    private void showOutputImage(Bitmap bitmap, byte[] cachedInputBytes) {
        if (destroyed) {
            bitmap.recycle();
            return;
        }
        recycleOutputBitmap();
        outputBitmap = bitmap;
        uploadInputBytes = cachedInputBytes;
        outputImageView.setImageBitmap(bitmap);
        outputImageView.setVisibility(View.VISIBLE);
        setProcessing(false);
        setStatus(R.string.status_complete);
    }

    private void showFailure(String message) {
        if (!destroyed) {
            setProcessing(false);
            statusText.setText(message);
        }
    }

    private void setProcessing(boolean value) {
        processing = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        updateControls();
    }

    private void updateControls() {
        selectPhotoButton.setEnabled(!processing);
        takePhotoButton.setEnabled(!processing);
        styleSpinner.setEnabled(!processing);
        convertButton.setEnabled(!processing && inputBitmap != null);
        saveButton.setEnabled(!processing && outputBitmap != null);
    }

    private void setStatus(int stringId) {
        statusText.setText(stringId);
    }

    private void recycleInputBitmap() {
        if (inputBitmap != null) {
            inputBitmap.recycle();
            inputBitmap = null;
        }
    }

    private void recycleOutputBitmap() {
        outputImageView.setImageDrawable(null);
        outputImageView.setVisibility(View.GONE);
        if (outputBitmap != null) {
            outputBitmap.recycle();
            outputBitmap = null;
        }
    }

    private void clearPendingCameraFile() {
        pendingCameraUri = null;
        if (pendingCameraFile != null) {
            pendingCameraFile.delete();
            pendingCameraFile = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (executorService != null) {
            executorService.shutdownNow();
        }
        clearPendingCameraFile();
        recycleInputBitmap();
        recycleOutputBitmap();
        super.onDestroy();
    }
}
