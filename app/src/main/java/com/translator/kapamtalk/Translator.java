package com.translator.kapamtalk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class Translator extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final String TAG = "TranslatorActivity";

    private Spinner sourceLanguageSpinner, targetLanguageSpinner;
    private ImageButton speechButton, micButton, translateButton, speakerButton, copyButton;
    private RequestQueue requestQueue;
    private EditText sourceText;
    private BottomNavigationView bottomNavigationView;
    private AlertDialog progressDialog;
    private AlertDialog noInternetDialog;
    private ImageButton clearButton;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    // Network timeout constants
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    // Audio recording variables
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;

    // API endpoints
    private static final String FLASK_BASE_URL = "https://coco-18-kapamtalk.hf.space";
    private static final String FLASK_TTS_ENDPOINT = "/tts";
    private static final String FLASK_ASR_ENDPOINT = "/asr";
    private static final String FLASK_TRANSLATION_ENDPOINT = "/translate";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        setFullscreen();
        setContentView(R.layout.translator);

        requestQueue = Volley.newRequestQueue(this);

        // Initialize UI components
        setupSpinners();
        setupSpeechButton();
        setupMicButton();
        setupTranslateButton();
        setupTargetSpeechButton();
        setupClearButton();
        setupCopyButton();

        // Initialize the EditText
        sourceText = findViewById(R.id.sourceText);

        // Add a TextWatcher to clear translation when source text is empty
        sourceText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Not needed for this implementation
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // When text changes, check if it's empty
                if (s.length() == 0) {
                    // Clear the translated text field
                    TextView translatedTextView = findViewById(R.id.translatedText);
                    translatedTextView.setText("Translation will appear here...");

                    // Disable the copy button when source text is cleared
                    copyButton.setEnabled(false);
                    copyButton.setAlpha(0.5f);
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // Not needed for this implementation
            }
        });

        bottomNavigationView = findViewById(R.id.bottomNavigationView);
        bottomNavigationView.setSelectedItemId(R.id.nav_translator);

        // Bottom navigation handling
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                moveToHome();
                return true;
            } else if (itemId == R.id.nav_translator) {
                // Already here
                return true;
            } else if (itemId == R.id.nav_dictionary) {
                startActivity(new Intent(getApplicationContext(), Dictionary.class));
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
                finish();
                return true;
            }
            return false;
        });

        // Custom back press handling
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveToHome();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);

        // Set up network connectivity monitoring
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();

        // Create a folder for audio recordings
        File audioDir = new File(getExternalFilesDir(null), "audio_records");
        if (!audioDir.exists()) {
            audioDir.mkdirs();
        }
        audioFilePath = new File(audioDir, "recorded_audio.wav").getAbsolutePath();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof EditText) {
                Rect outRect = new Rect();
                v.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    hideKeyboard();
                    v.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void setupSpinners() {
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner);
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner);

        // Create array of languages
        final String[] languages = {"Kapampangan", "Tagalog", "English"};

        // Create and customize the adapter
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                languages
        ) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text = (TextView) view;
                text.setTextColor(Color.WHITE);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView text = (TextView) view;
                text.setTextColor(Color.WHITE);
                text.setPadding(20, 20, 20, 20);
                return view;
            }
        };

        // Specify the spinner layout
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // Apply adapter to spinners
        sourceLanguageSpinner.setAdapter(adapter);
        targetLanguageSpinner.setAdapter(adapter);

        // Default selection (optional - set different initial languages)
        sourceLanguageSpinner.setSelection(0); // Kapampangan
        targetLanguageSpinner.setSelection(1); // Tagalog

        // Add listeners to handle selection changes
        sourceLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Get current target selection
                int targetPosition = targetLanguageSpinner.getSelectedItemPosition();

                // If source and target are the same, change target
                if (position == targetPosition) {
                    // Select a different language for target
                    int newTargetPosition = (position + 1) % languages.length;
                    targetLanguageSpinner.setSelection(newTargetPosition);
                    Toast.makeText(Translator.this,
                            "Target language updated to avoid duplication",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });

        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Get current source selection
                int sourcePosition = sourceLanguageSpinner.getSelectedItemPosition();

                // If target and source are the same, change source
                if (position == sourcePosition) {
                    // Select a different language for source
                    int newSourcePosition = (position + 1) % languages.length;
                    sourceLanguageSpinner.setSelection(newSourcePosition);
                    Toast.makeText(Translator.this,
                            "Source language updated to avoid duplication",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }

    private void setupSpeechButton() {
        speechButton = findViewById(R.id.speechButton);

        speechButton.setOnClickListener(v -> {
            String selectedLanguage = sourceLanguageSpinner.getSelectedItem().toString().toLowerCase();
            String userInputText = sourceText.getText().toString();

            // Check if the input is empty
            if (userInputText.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show();
                return;
            }

            // Hide keyboard
            hideKeyboard();

            // Show progress dialog instead of Toast
            showProgressDialog("Generating speech...");

            sendTextToFlask(userInputText, selectedLanguage);
        });
    }

    private void setupMicButton() {
        micButton = findViewById(R.id.micButton);

        micButton.setOnClickListener(v -> {
            // Hide keyboard
            hideKeyboard();

            // Check for permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO_PERMISSION);
                return;
            }

            // Toggle recording state
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
    }

    private void setupTranslateButton() {
        translateButton = findViewById(R.id.translateButton);

        translateButton.setOnClickListener(v -> {
            String userInputText = sourceText.getText().toString();

            // Check if the input is empty
            if (userInputText.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show();
                return;
            }

            // Hide keyboard
            hideKeyboard();

            String sourceLanguage = sourceLanguageSpinner.getSelectedItem().toString().toLowerCase();
            String targetLanguage = targetLanguageSpinner.getSelectedItem().toString().toLowerCase();

            // Show progress dialog instead of Toast
            showProgressDialog("Translating...");

            // Send the text for translation
            sendTextForTranslation(userInputText, sourceLanguage, targetLanguage);
        });
    }

    private void setupTargetSpeechButton() {
        speakerButton = findViewById(R.id.speakerButton);

        speakerButton.setOnClickListener(v -> {
            // Hide keyboard
            hideKeyboard();

            String selectedLanguage = targetLanguageSpinner.getSelectedItem().toString().toLowerCase();
            TextView translatedTextView = findViewById(R.id.translatedText);
            String translatedText = translatedTextView.getText().toString();

            // Check if the translated text is empty or still shows the hint
            if (translatedText.isEmpty() || translatedText.equals("Translation will appear here...")) {
                Toast.makeText(this, "No translation available", Toast.LENGTH_SHORT).show();
                return;
            }

            // Show progress dialog instead of Toast
            showProgressDialog("Generating speech...");

            // Reuse the same TTS function but with the translated text and target language
            sendTextToFlask(translatedText, selectedLanguage);
        });
    }

    private void setupCopyButton() {
        copyButton = findViewById(R.id.copyButton);

        copyButton.setOnClickListener(v -> {
            // Hide keyboard
            hideKeyboard();

            TextView translatedTextView = findViewById(R.id.translatedText);
            String translatedText = translatedTextView.getText().toString();

            // Check if the translated text is empty or still shows the hint
            if (translatedText.isEmpty() || translatedText.equals("Translation will appear here...")) {
                Toast.makeText(this, "No translation available to copy", Toast.LENGTH_SHORT).show();
                return;
            }

            // Get the clipboard manager
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // Create a clip
            ClipData clip = ClipData.newPlainText("Translated Text", translatedText);
            // Set the clipboard's primary clip
            clipboard.setPrimaryClip(clip);

            // Notify user
            Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupClearButton() {
        clearButton = findViewById(R.id.clearButton);

        clearButton.setOnClickListener(v -> {
            // Clear the source text
            sourceText.setText("");

            // Clear the translated text
            TextView translatedTextView = findViewById(R.id.translatedText);
            translatedTextView.setText("Translation will appear here...");

            // Disable the copy button when translation is cleared
            copyButton.setEnabled(false);
            copyButton.setAlpha(0.5f);

            // Hide keyboard
            hideKeyboard();

            // Show brief confirmation
            Toast.makeText(this, "Text cleared", Toast.LENGTH_SHORT).show();
        });
    }

    private void startRecording() {
        // Change mic button appearance to indicate recording
        micButton.setImageResource(R.drawable.ic_mic_recording);
        micButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.recording_color));

        // Set the icon color to red
        micButton.setColorFilter(ContextCompat.getColor(this, R.color.recording_color));

        Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show();

        try {
            // Prepare MediaRecorder
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(16000);

            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start recording", e);
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
            resetMicButton();
        }
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;

                // Reset mic button appearance
                resetMicButton();

                // Show progress dialog instead of Toast
                showProgressDialog("Processing speech...");

                // Send the recording to the Flask API
                String selectedLanguage = sourceLanguageSpinner.getSelectedItem().toString().toLowerCase();
                sendAudioToFlask(audioFilePath, selectedLanguage);

            } catch (Exception e) {
                Log.e(TAG, "Failed to stop recording", e);
                Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show();
                resetMicButton();
            }
        }
    }

    private void resetMicButton() {
        micButton.setImageResource(R.drawable.ic_mic_normal);
        micButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.normal_color));

        // Reset the icon color
        micButton.clearColorFilter();

        isRecording = false;
    }

    private void sendAudioToFlask(String audioFilePath, String language) {
        String fullUrl = FLASK_BASE_URL + FLASK_ASR_ENDPOINT;

        // Create a VolleyMultipartRequest
        VolleyMultipartRequest multipartRequest = new VolleyMultipartRequest(
                Request.Method.POST,
                fullUrl,
                response -> {
                    try {
                        String responseData = new String(response.data);
                        JSONObject jsonResponse = new JSONObject(responseData);
                        String transcription = jsonResponse.getString("transcription");

                        // Set the transcribed text to the source text field
                        runOnUiThread(() -> {
                            sourceText.setText(transcription);
                            // Dismiss the progress dialog
                            dismissProgressDialog();
                        });

                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing response", e);
                        Toast.makeText(Translator.this, "Error processing transcription", Toast.LENGTH_SHORT).show();
                        dismissProgressDialog();
                    }
                },
                error -> {
                    Log.e(TAG, "ASR request failed: " + error.toString(), error);
                    Toast.makeText(Translator.this, "Error connecting to server", Toast.LENGTH_SHORT).show();
                    dismissProgressDialog();
                }
        ) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("language", language);
                return params;
            }

            @Override
            protected Map<String, DataPart> getByteData() {
                Map<String, DataPart> params = new HashMap<>();
                try {
                    File audioFile = new File(audioFilePath);
                    byte[] fileData = readFileToByteArray(audioFile);
                    params.put("audio", new DataPart("audio.m4a", fileData, "audio/wav"));
                } catch (IOException e) {
                    Log.e(TAG, "Error reading audio file", e);
                }
                return params;
            }
        };

        // Set a longer timeout for the request (30 seconds)
        multipartRequest.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        requestQueue.add(multipartRequest);
    }

    private void sendTextToFlask(String text, String language) {
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("text", text);
            jsonBody.put("language", language);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Set up the full URL
        String fullUrl = FLASK_BASE_URL + FLASK_TTS_ENDPOINT;

        JsonObjectRequest jsonRequest = new JsonObjectRequest(Request.Method.POST, fullUrl, jsonBody,
                response -> {
                    try {
                        String fileUrl = response.getString("file_url");
                        String fullAudioUrl = FLASK_BASE_URL + fileUrl;

                        // Download and play the audio
                        downloadAndPlayAudio(fullAudioUrl);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing response", e);
                        Toast.makeText(Translator.this, "Error processing server response", Toast.LENGTH_SHORT).show();
                    }
                    // Dismiss the progress dialog
                    dismissProgressDialog();
                },
                error -> {
                    Log.e(TAG, "Request failed: " + error.toString(), error);
                    Toast.makeText(Translator.this, "Error connecting to server", Toast.LENGTH_SHORT).show();
                    // Dismiss the progress dialog
                    dismissProgressDialog();
                }
        );

        // Set a longer timeout for the request (30 seconds)
        jsonRequest.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        requestQueue.add(jsonRequest);
    }

    private void sendTextForTranslation(String text, String sourceLanguage, String targetLanguage) {
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("text", text);
            jsonBody.put("source_language", sourceLanguage);
            jsonBody.put("target_language", targetLanguage);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Set up the full URL
        String fullUrl = FLASK_BASE_URL + FLASK_TRANSLATION_ENDPOINT;

        JsonObjectRequest jsonRequest = new JsonObjectRequest(Request.Method.POST, fullUrl, jsonBody,
                response -> {
                    try {
                        String translatedText = response.getString("translated_text");

                        // Update the UI with the translated text
                        runOnUiThread(() -> {
                            TextView translatedTextView = findViewById(R.id.translatedText);
                            translatedTextView.setText(translatedText);

                            // Enable the copy button when translation is successful
                            copyButton.setEnabled(true);
                            copyButton.setAlpha(1.0f);

                            // Dismiss the progress dialog
                            dismissProgressDialog();
                        });
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing translation response", e);
                        Toast.makeText(Translator.this, "Error processing translation response", Toast.LENGTH_SHORT).show();
                        dismissProgressDialog();
                    }
                },
                error -> {
                    Log.e(TAG, "Translation request failed: " + error.toString(), error);
                    Toast.makeText(Translator.this, "Error connecting to translation server", Toast.LENGTH_SHORT).show();
                    dismissProgressDialog();
                }
        );

        // Set a timeout for the request (30 seconds)
        jsonRequest.setRetryPolicy(new DefaultRetryPolicy(
                30000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        requestQueue.add(jsonRequest);
    }

    private void downloadAndPlayAudio(String audioUrl) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            InputStream input = null;
            FileOutputStream output = null;

            try {
                // Create a temporary file
                File tempFile = File.createTempFile("tts_audio", ".wav", getCacheDir());
                final File audioFile = tempFile;

                // Set up the connection
                URL url = new URL(audioUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage());
                    runOnUiThread(() -> Toast.makeText(Translator.this, "Error downloading audio", Toast.LENGTH_SHORT).show());
                    return;
                }

                // Download the file
                input = connection.getInputStream();
                output = new FileOutputStream(audioFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }

                output.flush();

                runOnUiThread(() -> playAudioFile(audioFile.getAbsolutePath()));

            } catch (IOException e) {
                Log.e(TAG, "Error downloading audio", e);
                runOnUiThread(() -> Toast.makeText(Translator.this, "Error downloading audio: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams", e);
                }
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void playAudioFile(String filePath) {
        MediaPlayer mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();

            // Add a completion listener to release resources when done
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                Log.d(TAG, "Audio playback completed");
            });

            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Error playing audio", e);
            Toast.makeText(this, "Error playing audio", Toast.LENGTH_SHORT).show();
            mediaPlayer.release();
        }
    }

    // Helper method to read file to byte array
    private byte[] readFileToByteArray(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;

        while ((read = fis.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }

        fis.close();
        return bos.toByteArray();
    }

    // Custom Volley request for multipart form data
    public class VolleyMultipartRequest extends Request<NetworkResponse> {
        private final Response.Listener<NetworkResponse> mListener;
        private final Map<String, String> mParams;
        private final Map<String, DataPart> mByteData;

        public VolleyMultipartRequest(int method, String url,
                                      Response.Listener<NetworkResponse> listener,
                                      Response.ErrorListener errorListener) {
            super(method, url, errorListener);
            this.mListener = listener;
            this.mParams = new HashMap<>();
            this.mByteData = new HashMap<>();
        }

        @Override
        protected Response<NetworkResponse> parseNetworkResponse(NetworkResponse response) {
            return Response.success(response, HttpHeaderParser.parseCacheHeaders(response));
        }

        @Override
        protected void deliverResponse(NetworkResponse response) {
            mListener.onResponse(response);
        }

        @Override
        public Map<String, String> getHeaders() throws AuthFailureError {
            return super.getHeaders();
        }

        protected Map<String, String> getParams() {
            return mParams;
        }

        protected Map<String, DataPart> getByteData() {
            return mByteData;
        }

        @Override
        public String getBodyContentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        @Override
        public byte[] getBody() throws AuthFailureError {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try {
                // Add string params
                Map<String, String> params = getParams();
                if (params != null && params.size() > 0) {
                    textParse(bos, params);
                }

                // Add data byte params
                Map<String, DataPart> data = getByteData();
                if (data != null && data.size() > 0) {
                    dataParse(bos, data);
                }

                // Close multipart form data
                bos.write(("--" + boundary + "--\r\n").getBytes());

                return bos.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        private final String boundary = "apiclient-" + System.currentTimeMillis();
        private final String lineEnd = "\r\n";
        private final String twoHyphens = "--";

        private void textParse(ByteArrayOutputStream bos, Map<String, String> params) throws IOException {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                bos.write((twoHyphens + boundary + lineEnd).getBytes());
                bos.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"" + lineEnd).getBytes());
                bos.write(lineEnd.getBytes());
                bos.write(entry.getValue().getBytes());
                bos.write(lineEnd.getBytes());
            }
        }

        private void dataParse(ByteArrayOutputStream bos, Map<String, DataPart> data) throws IOException {
            for (Map.Entry<String, DataPart> entry : data.entrySet()) {
                DataPart dp = entry.getValue();
                bos.write((twoHyphens + boundary + lineEnd).getBytes());
                bos.write(("Content-Disposition: form-data; name=\"" +
                        entry.getKey() + "\"; filename=\"" + dp.getFileName() + "\"" + lineEnd).getBytes());
                if (dp.getType() != null && !dp.getType().isEmpty()) {
                    bos.write(("Content-Type: " + dp.getType() + lineEnd).getBytes());
                }
                bos.write((lineEnd).getBytes());

                bos.write(dp.getContent());
                bos.write(lineEnd.getBytes());
            }
        }

        public class DataPart {
            private String fileName;
            private byte[] content;
            private String type;

            public DataPart(String name, byte[] data) {
                fileName = name;
                content = data;
            }

            public DataPart(String name, byte[] data, String mimeType) {
                fileName = name;
                content = data;
                type = mimeType;
            }

            public String getFileName() {
                return fileName;
            }

            public byte[] getContent() {
                return content;
            }

            public String getType() {
                return type;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, start recording
                Toast.makeText(this, "Recording permission granted", Toast.LENGTH_SHORT).show();
                startRecording();
            } else {
                // Permission denied
                Toast.makeText(this, "Recording permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Make sure to stop recording if the app is paused
        if (isRecording) {
            stopRecording();
        }
    }

    @Override
    protected void onDestroy() {
        // Dismiss the progress dialog
        dismissProgressDialog();

        // Dismiss the no internet dialog
        dismissNoInternetDialog();

        // Unregister network callback
        unregisterNetworkCallback();

        // Clean up recording resources
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
        super.onDestroy();
    }

    private void moveToHome() {
        Intent intent = new Intent(this, Home.class);
        startActivity(intent);
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        finish();
    }

    private void showProgressDialog(String message) {
        dismissProgressDialog(); // Dismiss any existing dialog

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.progress_dialog_translator, null);
        TextView messageText = dialogView.findViewById(R.id.dialog_message);
        messageText.setText(message);

        builder.setView(dialogView);
        builder.setCancelable(false);

        progressDialog = builder.create();
        if (progressDialog.getWindow() != null) {
            progressDialog.getWindow().setBackgroundDrawableResource(R.drawable.rounded_dialog_background);
        }

        progressDialog.show();
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void hideKeyboard() {
        View view = this.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void setFullscreen() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    // Network monitoring methods
    private void registerNetworkCallback() {
        if (connectivityManager == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                new Thread(() -> {
                    if (!isInternetStillAvailable()) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                showNoInternetDialog();
                            }
                        });
                    }
                }).start();
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }

    private boolean isInternetStillAvailable() {
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL("https://clients3.google.com/generate_204");
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            urlConnection.setReadTimeout(READ_TIMEOUT_MS);
            urlConnection.setRequestMethod("HEAD");
            return urlConnection.getResponseCode() == 204;
        } catch (IOException e) {
            return false;
        } finally {
            if (urlConnection != null)
                urlConnection.disconnect();
        }
    }

    private void showNoInternetDialog() {
        if (noInternetDialog != null && noInternetDialog.isShowing()) {
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.customnointernetdialog, null);

        noInternetDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        View okBtn = dialogView.findViewById(R.id.Okbtn);
        okBtn.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            goToLogin();
            if (noInternetDialog != null && noInternetDialog.isShowing()) {
                noInternetDialog.dismiss();
            }
        });

        noInternetDialog.show();
        if (noInternetDialog.getWindow() != null) {
            noInternetDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private void dismissNoInternetDialog() {
        if (noInternetDialog != null && noInternetDialog.isShowing()) {
            noInternetDialog.dismiss();
        }
    }

    private void goToLogin() {
        Intent intent = new Intent(Translator.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }
}