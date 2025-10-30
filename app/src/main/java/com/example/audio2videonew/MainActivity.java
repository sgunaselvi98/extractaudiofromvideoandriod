package com.example.audio2videonew;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PICK_VIDEO = 100;
    private static final int REQUEST_CODE_PERMISSIONS = 101;

    private Button btnSelectVideo;
    private Button btnExtractAudio;
    private TextView tvStatus;
    private TextView tvVideoName;
    private TextView tvOutputPath;
    private ProgressBar progressBar;

    private Uri selectedVideoUri;
    private String outputAudioPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        requestPermissions();
        setupListeners();
    }

    private void initializeViews() {
        btnSelectVideo = findViewById(R.id.btnSelectVideo);
        btnExtractAudio = findViewById(R.id.btnExtractAudio);
        tvStatus = findViewById(R.id.tvStatus);
        tvVideoName = findViewById(R.id.tvVideoName);
        tvOutputPath = findViewById(R.id.tvOutputPath);
        progressBar = findViewById(R.id.progressBar);

        btnExtractAudio.setEnabled(false);
        progressBar.setVisibility(View.GONE);
    }

    private void setupListeners() {
        btnSelectVideo.setOnClickListener(v -> selectVideo());
        btnExtractAudio.setOnClickListener(v -> extractAudio());
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permissions required for this app", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void selectVideo() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Select Video"), REQUEST_CODE_PICK_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            selectedVideoUri = data.getData();
            if (selectedVideoUri != null) {
                String selectedVideoName = getFileName(selectedVideoUri);
                tvVideoName.setText("Selected: " + selectedVideoName);
                tvStatus.setText("Video selected. Ready to extract audio.");
                btnExtractAudio.setEnabled(true);
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void extractAudio() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }

        btnExtractAudio.setEnabled(false);
        btnSelectVideo.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Extracting audio...");

        new Thread(() -> {
            try {
                // Create output file
                File outputDir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), "ExtractedAudio");
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }

                String timestamp = String.valueOf(System.currentTimeMillis());
                File outputFile = new File(outputDir, "audio_" + timestamp + ".m4a");
                outputAudioPath = outputFile.getAbsolutePath();

                // Get video path from URI
                String videoPath = getRealPathFromUri(selectedVideoUri);

                if (videoPath != null) {
                    extractAudioFromVideo(videoPath, outputAudioPath);

                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        tvStatus.setText("Audio extracted successfully!");
                        tvOutputPath.setText("Saved at: " + outputAudioPath);
                        tvOutputPath.setVisibility(View.VISIBLE);
                        btnSelectVideo.setEnabled(true);
                        Toast.makeText(MainActivity.this, "Audio saved!", Toast.LENGTH_LONG).show();
                    });
                } else {
                    throw new IOException("Could not access video file");
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Error: " + e.getMessage());
                    btnExtractAudio.setEnabled(true);
                    btnSelectVideo.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Extraction failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
                e.printStackTrace();
            }
        }).start();
    }

    private String getRealPathFromUri(Uri uri) {
        try {
            // For content:// URIs, copy to temporary file first
            File tempFile = new File(getCacheDir(), "temp_video.mp4");
            FileInputStream inputStream = (FileInputStream) getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(tempFile);

            FileChannel inChannel = inputStream.getChannel();
            FileChannel outChannel = outputStream.getChannel();
            inChannel.transferTo(0, inChannel.size(), outChannel);

            inputStream.close();
            outputStream.close();

            return tempFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void extractAudioFromVideo(String videoPath, String outputPath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(videoPath);

        int audioTrackIndex = -1;
        MediaFormat audioFormat = null;

        // Find audio track
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrackIndex = i;
                audioFormat = format;
                break;
            }
        }

        if (audioTrackIndex < 0) {
            extractor.release();
            throw new IOException("No audio track found in video");
        }

        extractor.selectTrack(audioTrackIndex);

        // Create muxer for output
        MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int trackIndex = muxer.addTrack(audioFormat);
        muxer.start();

        // Extract and write audio data
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            bufferInfo.size = extractor.readSampleData(buffer, 0);
            if (bufferInfo.size < 0) {
                break;
            }

            bufferInfo.presentationTimeUs = extractor.getSampleTime();
            bufferInfo.offset = 0;

            // Convert MediaExtractor flags to MediaCodec flags
            int sampleFlags = extractor.getSampleFlags();
            bufferInfo.flags = 0;
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                bufferInfo.flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                bufferInfo.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
            }

            muxer.writeSampleData(trackIndex, buffer, bufferInfo);
            extractor.advance();
        }

        // Cleanup
        muxer.stop();
        muxer.release();
        extractor.release();
    }
}