package com.example.app_artify2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int MAX_PREVIEW_DIMENSION = 2048;
    private static final String[] STYLE_PROMPTS = {
            "Transform the provided photograph into a delicate watercolor painting "
                    + "with soft washes, textured paper, and gentle pigment edges.",
            "Transform the provided photograph into traditional Japanese ukiyo-e art "
                    + "with clean ink outlines, flat color blocks, and woodblock print texture.",
            "Transform the provided photograph into an expressive oil painting "
                    + "with visible brush strokes, layered paint, and rich museum-style color.",
            "Transform the provided photograph into a hand-painted anime background scene "
                    + "with warm light, detailed scenery, and cinematic color."
    };

    private ActivityResultLauncher<PickVisualMediaRequest> photoPicker;
    private ExecutorService executorService;
    private final NanoBananaApiClient apiClient = new NanoBananaApiClient();

    private EditText apiKeyInput;
    private Button selectPhotoButton;
    private Button convertButton;
    private ImageView inputImageView;
    private ImageView outputImageView;
    private Spinner styleSpinner;
    private ProgressBar progressBar;
    private TextView statusText;

    private Bitmap inputBitmap;
    private Bitmap outputBitmap;
    private boolean processing;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        executorService = Executors.newSingleThreadExecutor();
        photoPicker = registerForActivityResult(new PickVisualMedia(), this::onPhotoSelected);

        selectPhotoButton.setOnClickListener(view -> selectPhoto());
        convertButton.setOnClickListener(view -> convertImage());
        updateControls();
    }

    private void bindViews() {
        apiKeyInput = findViewById(R.id.api_key_input);
        selectPhotoButton = findViewById(R.id.select_photo_button);
        convertButton = findViewById(R.id.convert_button);
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

        setProcessing(true);
        setStatus(R.string.status_loading);
        executorService.execute(() -> {
            try {
                Bitmap bitmap = loadSelectedBitmap(uri);
                runOnUiThread(() -> showInputImage(bitmap));
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> showFailure(getString(R.string.error_image)));
            }
        });
    }

    private void convertImage() {
        if (inputBitmap == null || processing) {
            return;
        }
        String apiKey = apiKeyInput.getText().toString().trim();
        if (apiKey.isEmpty()) {
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
        executorService.execute(() -> {
            try {
                Bitmap result = apiClient.transform(apiKey, source, prompt);
                runOnUiThread(() -> showOutputImage(result));
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
        inputImageView.setImageBitmap(bitmap);
        inputImageView.setVisibility(View.VISIBLE);
        recycleOutputBitmap();
        setProcessing(false);
        setStatus(R.string.status_selected);
    }

    private void showOutputImage(Bitmap bitmap) {
        if (destroyed) {
            bitmap.recycle();
            return;
        }
        recycleOutputBitmap();
        outputBitmap = bitmap;
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
        apiKeyInput.setEnabled(!processing);
        styleSpinner.setEnabled(!processing);
        convertButton.setEnabled(!processing && inputBitmap != null);
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

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (executorService != null) {
            executorService.shutdownNow();
        }
        recycleInputBitmap();
        recycleOutputBitmap();
        super.onDestroy();
    }
}
