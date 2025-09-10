package com.translator.kapamtalk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import com.android.volley.AuthFailureError;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.toolbox.HttpHeaderParser;
import org.json.JSONException;
import org.json.JSONObject;
import com.android.volley.DefaultRetryPolicy;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;

public class ExamThree extends AppCompatActivity {
    // Constants
    private static final String TAG = "ExamThree";
    private static final String FIREBASE_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String EXAM_ID = "exam3";
    private static final String LAST_EXAM_ITEM_COUNT_PREF = "last_exam3_item_count";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int TOTAL_QUESTIONS = 5;
    private static final int PASSING_SCORE = 70; // 70% to pass
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final String FLASK_BASE_URL = "https://coco-18-kapamtalk.hf.space";
    private static final String FLASK_EVALUATE_ENDPOINT = "/evaluate";
    private static final int MINIMUM_RECORDING_DURATION_MS = 1000; // 1 second minimum
    private static final long MINIMUM_FILE_SIZE_BYTES = 1000; // 1KB minimum

    // Firebase components
    private FirebaseAuth auth;
    private DatabaseReference userRef;
    private DatabaseReference examItemsReference;
    private ValueEventListener progressListener;
    private ValueEventListener realTimeListener;
    private ValueEventListener examItemsListener;

    // Network components
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    // UI components
    private TextView tvPhraseToPronounce;
    private TextView tvPhraseMeaning;
    private TextView tvRecordingInstructions;
    private TextView tvFeedback;
    private TextView questionNumber;
    private TextView progressText;
    private Button btnListen;
    private Button resetButton;
    private ImageButton backButton;
    private FloatingActionButton fabRecord;
    private ProgressBar progressIndicator;
    private ProgressBar loadingProgressBar;
    private ImageView completionCheckMark;

    // Audio components
    private MediaPlayer mediaPlayer;
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private long recordingStartTime = 0;

    // Dialog components - using WeakReference to prevent memory leaks
    private WeakReference<AlertDialog> noInternetDialogRef;
    private WeakReference<AlertDialog> resetConfirmDialogRef;
    private WeakReference<AlertDialog> completionDialogRef;
    private WeakReference<AlertDialog> failureDialogRef;
    private AlertDialog progressDialog;

    // Data and state
    private List<PhraseItem> allPhraseItems;  // All available phrase items
    private List<PhraseItem> phraseItems;     // Selected phrase items in the current order
    private int currentQuestionIndex = 0;
    private int correctAnswers = 0;
    private boolean[] questionAnswered = new boolean[TOTAL_QUESTIONS];
    private double[] questionScores = new double[TOTAL_QUESTIONS]; // Store scores for each question
    private boolean examCompleted = false;
    private Random random = new Random();
    private Handler autoAdvanceHandler = new Handler(Looper.getMainLooper());
    private boolean phrasesLoaded = false;

    // Added to prevent duplicate processing
    private long lastFetchTimestamp = 0;

    private RequestQueue requestQueue;
    // Background task executor
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();
    // Handler for main thread operations
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Swipe navigation components
    private GestureDetectorCompat gestureDetector;
    private View mainContentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setFullscreen();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.examthree);

        // Initialize Volley RequestQueue
        requestQueue = Volley.newRequestQueue(this);

        initializeComponents();
        requestMicrophonePermission();
        setupUI();
        setupSwipeNavigation();
        setupNetworkMonitoring();
        loadUserProgress();
    }

    private void initializeComponents() {
        auth = FirebaseAuth.getInstance();
        FirebaseDatabase database = FirebaseDatabase.getInstance(FIREBASE_URL);
        userRef = database.getReference("users");

        // Initialize the exam items reference
        examItemsReference = database.getReference("examItems").child(EXAM_ID);

        // Initialize phrase items - will be populated from Firebase
        allPhraseItems = new ArrayList<>();
        phraseItems = new ArrayList<>();

        // Initialize loading progress bar
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        if (loadingProgressBar != null) {
            loadingProgressBar.setVisibility(View.VISIBLE);
        } else {
            Log.w(TAG, "loadingProgressBar not found in layout");
        }

        // Initialize audio file path
        File audioDir = new File(getExternalFilesDir(null), "audio_records");
        if (!audioDir.exists()) {
            boolean dirCreated = audioDir.mkdirs();
            if (!dirCreated) {
                Log.w(TAG, "Failed to create audio directory");
            }
        }
        audioFilePath = new File(audioDir, "recorded_audio.m4a").getAbsolutePath();

        // Fetch exam items from Firebase
        fetchExamItemsFromFirebase();
    }

    private void fetchExamItemsFromFirebase() {
        // Prevent duplicate fetches in short timespan
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTimestamp < 2000) {
            Log.d(TAG, "Ignoring rapid re-fetch request");
            return;
        }
        lastFetchTimestamp = currentTime;

        // Remove any existing listener
        if (examItemsListener != null) {
            examItemsReference.removeEventListener(examItemsListener);
        }

        // Get the last known item count from preferences
        SharedPreferences preferences = getSharedPreferences("ExamPreferences", MODE_PRIVATE);
        final int lastItemCount = preferences.getInt(LAST_EXAM_ITEM_COUNT_PREF, 0);

        examItemsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int currentItemCount = (int)snapshot.getChildrenCount();
                Log.d(TAG, "Fetched exam items: " + currentItemCount + " items (previously: " + lastItemCount + ")");

                allPhraseItems.clear();

                for (DataSnapshot itemSnapshot : snapshot.getChildren()) {
                    try {
                        // Extract values from Firebase
                        String kapampangan = itemSnapshot.child("kapampangan").getValue(String.class);
                        String english = itemSnapshot.child("english").getValue(String.class);
                        String pronunciation = itemSnapshot.child("pronunciation").getValue(String.class);
                        String usage = itemSnapshot.child("usage").getValue(String.class);
                        String audioUrl = itemSnapshot.child("audioUrl").getValue(String.class);
                        String referencelocator = itemSnapshot.child("referencelocator").getValue(String.class);
                        Integer sortOrder = itemSnapshot.child("sortOrder").getValue(Integer.class);

                        if (kapampangan == null || english == null) {
                            Log.w(TAG, "Skipping invalid exam item - missing required fields");
                            continue; // Skip invalid entries
                        }

                        PhraseItem item = new PhraseItem(
                                kapampangan,
                                english,
                                pronunciation != null ? pronunciation : "",
                                usage != null ? usage : "",
                                audioUrl != null ? audioUrl : "",
                                referencelocator != null ? referencelocator : ""
                        );

                        // Add sort order if available
                        if (sortOrder != null) {
                            item.setSortOrder(sortOrder);
                        }

                        allPhraseItems.add(item);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing exam item", e);
                    }
                }

                if (allPhraseItems.isEmpty()) {
                    Log.w(TAG, "No exam items found in Firebase!");
                    showErrorView("No exam items available. Please add items to Firebase.");
                } else {
                    // Sort by sortOrder if available
                    Collections.sort(allPhraseItems, (a, b) -> a.getSortOrder() - b.getSortOrder());

                    // Check if the number of items has changed
                    boolean hasNewItems = currentItemCount != lastItemCount;

                    // Save the current count for next time
                    preferences.edit()
                            .putInt(LAST_EXAM_ITEM_COUNT_PREF, currentItemCount)
                            .apply();

                    // If number of items changed, we need to reset exam state
                    if (hasNewItems && lastItemCount > 0) {
                        Log.d(TAG, "Item count changed from " + lastItemCount + " to " + currentItemCount + ", resetting exam state");

                        // Reset the current exam in Firebase
                        if (auth.getCurrentUser() != null) {
                            String userId = auth.getCurrentUser().getUid();
                            // Reset exam progress
                            userRef.child(userId)
                                    .child("examProgress")
                                    .child(EXAM_ID)
                                    .removeValue();

                            // Reset exam completion status
                            userRef.child(userId)
                                    .child("examsProgress")
                                    .child(EXAM_ID)
                                    .setValue(false);

                            // Reset local state
                            currentQuestionIndex = 0;
                            correctAnswers = 0;
                            for (int i = 0; i < questionAnswered.length; i++) {
                                questionAnswered[i] = false;
                                questionScores[i] = 0;
                            }
                            examCompleted = false;

                            // Show a toast to inform the user
                            showToast("New exam items available! Your progress has been reset.");
                        }
                    }

                    // Proceed with shuffling and displaying items
                    shufflePhraseItems();
                    displayCurrentQuestion();
                    updateProgress();
                }

                phrasesLoaded = true;

                // Hide loading indicator
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to load exam items from Firebase: " + error.getMessage());
                showErrorView("Error loading exam items: " + error.getMessage() + "\nPlease check your connection and try again.");

                // Hide loading indicator
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }
        };

        examItemsReference.addValueEventListener(examItemsListener);
    }

    private void showErrorView(String message) {
        runOnUiThread(() -> {
            // Set error message in UI
            if (tvPhraseToPronounce != null) {
                tvPhraseToPronounce.setText("Error");
                tvPhraseToPronounce.setTextColor(Color.RED);
            }

            if (tvPhraseMeaning != null) {
                tvPhraseMeaning.setText(message);
            }

            // Disable recording
            if (fabRecord != null) {
                fabRecord.setEnabled(false);
                fabRecord.setAlpha(0.5f);
            }

            // Show toast with error
            showToast("Failed to load exam items. Try again later.");
        });
    }

    private void requestMicrophonePermission() {
        // Check if we already have the permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Request the permission - ONLY request permission, no other actions
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
        }
    }

    private void shufflePhraseItems() {
        // Check if we have enough items
        if (allPhraseItems.size() < TOTAL_QUESTIONS) {
            Log.w(TAG, "Not enough items for exam. Need " + TOTAL_QUESTIONS +
                    " but only have " + allPhraseItems.size());

            // Use all available items if we don't have enough
            phraseItems = new ArrayList<>(allPhraseItems);
            return;
        }

        // Create a new list for the shuffled items
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < allPhraseItems.size(); i++) {
            indices.add(i);
        }

        // Fisher-Yates shuffle algorithm for indices
        for (int i = indices.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = indices.get(i);
            indices.set(i, indices.get(j));
            indices.set(j, temp);
        }

        // Create the phraseItems list based on the shuffled indices
        phraseItems.clear();
        for (int i = 0; i < Math.min(TOTAL_QUESTIONS, indices.size()); i++) {
            phraseItems.add(allPhraseItems.get(indices.get(i)));
        }
    }

    private void setupUI() {
        // Initialize views
        tvPhraseToPronounce = findViewById(R.id.tvPhraseToPronounce);
        tvPhraseMeaning = findViewById(R.id.tvPhraseMeaning);
        tvRecordingInstructions = findViewById(R.id.tvRecordingInstructions);
        tvFeedback = findViewById(R.id.tvFeedback);
        questionNumber = findViewById(R.id.questionNumber);
        progressText = findViewById(R.id.progressText);
        btnListen = findViewById(R.id.btnListen);
        resetButton = findViewById(R.id.resetButton);
        backButton = findViewById(R.id.backButton);
        fabRecord = findViewById(R.id.fabRecord);
        progressIndicator = findViewById(R.id.progressIndicator);
        completionCheckMark = findViewById(R.id.completionCheckMark);

        if (completionCheckMark != null) {
            completionCheckMark.setVisibility(View.GONE);  // Initially hidden
        }

        // Hide listen button initially since exam is not completed
        btnListen.setVisibility(View.GONE);

        // Set up click listeners
        btnListen.setOnClickListener(v -> playPronunciation());
        resetButton.setOnClickListener(v -> showResetConfirmation());
        backButton.setOnClickListener(v -> {
            releaseMediaResources();
            finish();
            overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        });

        // Set up record button touch listener
        fabRecord.setOnTouchListener(this::handleRecordButtonTouch);

        // Initialize with empty state until questions are loaded
        tvPhraseToPronounce.setText("Loading...");
        tvPhraseMeaning.setText("Please wait while exam items are loaded from Firebase...");
    }

    private void setupSwipeNavigation() {
        // Find the main content view to attach the gesture detector
        mainContentView = findViewById(R.id.examContentContainer);

        // If mainContentView is null, try to find the root view
        if (mainContentView == null) {
            mainContentView = findViewById(android.R.id.content);
        }

        // Create gesture detector
        gestureDetector = new GestureDetectorCompat(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                boolean result = false;
                try {
                    float diffX = e2.getX() - e1.getX();
                    float diffY = e2.getY() - e1.getY();

                    // Check if it's a horizontal swipe
                    if (Math.abs(diffX) > Math.abs(diffY) &&
                            Math.abs(diffX) > SWIPE_THRESHOLD &&
                            Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {

                        if (diffX > 0) {
                            // Right swipe - go to previous question
                            navigateToPreviousQuestion();
                        } else {
                            // Left swipe - go to next question
                            navigateToNextQuestion();
                        }
                        result = true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing swipe gesture", e);
                }
                return result;
            }
        });

        // Set touch listener on the main content view
        if (mainContentView != null) {
            mainContentView.setOnTouchListener((v, event) -> {
                // Only process touch events if exam is completed (review mode)
                if (examCompleted) {
                    return gestureDetector.onTouchEvent(event);
                }
                return false;
            });
        }
    }

    private void navigateToNextQuestion() {
        // Only allow navigation if exam is completed (review mode)
        if (!examCompleted) return;

        if (currentQuestionIndex < phraseItems.size() - 1) {
            currentQuestionIndex++;
            displayCurrentQuestion();
            saveProgress(); // Save the current index to Firebase
        }
    }

    private void navigateToPreviousQuestion() {
        // Only allow navigation if exam is completed (review mode)
        if (!examCompleted) return;

        if (currentQuestionIndex > 0) {
            currentQuestionIndex--;
            displayCurrentQuestion();
            saveProgress(); // Save the current index to Firebase
        }
    }

    private void displayCurrentQuestion() {
        if (phraseItems == null || phraseItems.isEmpty() || currentQuestionIndex >= phraseItems.size()) {
            // Handle case when no questions are loaded yet or invalid index
            Log.e(TAG, "Cannot display question - invalid state");
            tvPhraseToPronounce.setText("Loading...");
            tvPhraseMeaning.setText("Please wait while exam items are loaded.");
            return;
        }

        PhraseItem currentItem = phraseItems.get(currentQuestionIndex);

        // CHANGE: Swap the display of English and Kapampangan based on exam completion
        if (examCompleted) {
            // REVIEW MODE: Show Kapampangan phrase as main text (the answer)
            tvPhraseToPronounce.setText(currentItem.getKapampangan());
            tvPhraseMeaning.setText("(" + currentItem.getEnglish() + ")");
        } else {
            // EXAM MODE: Show English phrase as main text (user needs to translate & pronounce)
            tvPhraseToPronounce.setText(currentItem.getEnglish());
            tvPhraseMeaning.setText("(Translate and pronounce in Kapampangan)");
        }

        // If exam is completed, show current question number from total
        if (examCompleted) {
            questionNumber.setText((currentQuestionIndex + 1) + "/" + Math.min(TOTAL_QUESTIONS, phraseItems.size()));

            // Disable recording when exam is completed
            fabRecord.setEnabled(false);
            fabRecord.setAlpha(0.5f); // Visual indicator that button is disabled
            tvRecordingInstructions.setText("Exam completed! Swipe left/right to review Kapampangan translations.");

            // Show the listen button when exam is completed
            btnListen.setVisibility(View.VISIBLE);
        } else {
            questionNumber.setText((currentQuestionIndex + 1) + "/" + Math.min(TOTAL_QUESTIONS, phraseItems.size()));

            // Enable recording when exam is not completed
            fabRecord.setEnabled(true);
            fabRecord.setAlpha(1.0f);
            tvRecordingInstructions.setText("Tap and hold to record your Kapampangan translation");

            // Hide the listen button when exam is not completed
            btnListen.setVisibility(View.GONE);
        }

        // Reset feedback
        tvFeedback.setVisibility(View.GONE);
    }

    @SuppressLint("SetTextI18n")
    private boolean handleRecordButtonTouch(View v, MotionEvent event) {
        // First check if exam is completed
        if (examCompleted) {
            // If exam is completed, show message and ignore touch
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                showToast("Exam completed! Reset progress to try again.");
            }
            return true;
        }

        // If exam is not completed, proceed with normal recording logic
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check for permission before starting recording
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    // Permission not granted, request it
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_RECORD_AUDIO_PERMISSION);
                    return true;
                }
                // Permission already granted, start recording
                startRecording();
                return true;
            case MotionEvent.ACTION_UP:
                if (isRecording) {
                    stopRecording();

                    // Calculate recording duration
                    long recordingDuration = System.currentTimeMillis() - recordingStartTime;

                    // Check if recording is too short
                    if (recordingDuration < MINIMUM_RECORDING_DURATION_MS) {
                        tvFeedback.setVisibility(View.VISIBLE);
                        tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        tvFeedback.setText("Recording too short! Hold longer to record your voice.");
                        return true;
                    }

                    // Only evaluate if recording was long enough
                    evaluatePronunciation();
                }
                return true;
        }
        return false;
    }

    private void playPronunciation() {
        if (phraseItems == null || phraseItems.isEmpty() || currentQuestionIndex >= phraseItems.size()) {
            Log.e(TAG, "Cannot play pronunciation - invalid state");
            return;
        }

        // Always release previous MediaPlayer to prevent leaks
        releaseMediaPlayer();

        try {
            // Get URL instead of resource ID
            String audioUrl = phraseItems.get(currentQuestionIndex).getAudioUrl();

            // Check if URL is empty or null
            if (audioUrl == null || audioUrl.isEmpty()) {
                Log.e(TAG, "No audio URL available for this phrase");
                showToast("Audio not available for this phrase");
                return;
            }

            // Create MediaPlayer with URL instead of resource ID
            mediaPlayer = new MediaPlayer();

            // Set audio attributes
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
            mediaPlayer.setAudioAttributes(attributes);

            // Store original background tint for restoration later
            final ColorStateList originalBackgroundTint = btnListen.getBackgroundTintList();

            // Change button background color when playing
            btnListen.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.nav_bar_color_default)));
            // Make sure text remains visible by setting it to white or contrasting color
            btnListen.setTextColor(Color.WHITE);

            // Show loading toast
            showToast("Loading audio...");

            // Set data source to the URL and prepare asynchronously
            mediaPlayer.setDataSource(audioUrl);
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Media player error: " + what + ", " + extra);
                showToast("Error playing audio. Please try again.");
                btnListen.setBackgroundTintList(originalBackgroundTint);
                btnListen.setTextColor(Color.BLACK);
                releaseMediaPlayer();
                return true;
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                // Vibrate slightly to indicate playback finished
                vibrate(50);

                // Restore original button state
                btnListen.setBackgroundTintList(originalBackgroundTint);
                btnListen.setTextColor(Color.BLACK); // Or your default text color
            });

            // Start preparing the player
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "Error playing pronunciation: " + e.getMessage(), e);
            showToast("Could not play audio. Please try again.");
        }
    }

    private void vibrate(int durationMs) {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception e) {
                Log.e(TAG, "Error during vibration", e);
            }
        }
    }

    private void startRecording() {
        if (isRecording) {
            return;
        }

        // Record the start time
        recordingStartTime = System.currentTimeMillis();

        // Vibrate to indicate recording started
        vibrate(100);

        // Visual feedback for recording
        fabRecord.setImageTintList(ColorStateList.valueOf(Color.RED));
        tvRecordingInstructions.setText("Recording... Release to stop");

        // Ensure previous recorder is released
        releaseMediaRecorder();

        // Initialize recorder
        mediaRecorder = new MediaRecorder();
        try {
            // Delete previous recording if exists
            File file = new File(audioFilePath);
            if (file.exists()) {
                boolean deleted = file.delete();
                if (!deleted) {
                    Log.w(TAG, "Could not delete previous recording file");
                }
            }

            // Ensure parent directory exists
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                boolean created = parent.mkdirs();
                if (!created) {
                    Log.w(TAG, "Could not create parent directories for recording");
                }
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(16000);
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start recording", e);
            showToast("Failed to start recording");
            releaseMediaRecorder();
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaRecorder in illegal state", e);
            showToast("Recording error. Please try again.");
            releaseMediaRecorder();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error when recording", e);
            showToast("Recording error. Please try again.");
            releaseMediaRecorder();
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }

        // Vibrate to indicate recording stopped
        vibrate(100);

        // Visual feedback for stopping
        fabRecord.setImageTintList(ColorStateList.valueOf(getResources().getColor(android.R.color.white)));
        tvRecordingInstructions.setText("Tap and hold to record your Kapampangan translation");

        try {
            mediaRecorder.stop();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to stop recording - IllegalStateException", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
        } finally {
            releaseMediaRecorder();
        }
    }

    private void showProgressDialog(String message) {
        dismissProgressDialog(); // Dismiss any existing dialog

        // Run on UI thread to prevent window leaks
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View dialogView = getLayoutInflater().inflate(R.layout.progress_dialog_exam, null);
            TextView messageText = dialogView.findViewById(R.id.dialog_message);
            messageText.setText(message);

            builder.setView(dialogView);
            builder.setCancelable(false);

            progressDialog = builder.create();
            if (progressDialog.getWindow() != null) {
                progressDialog.getWindow().setBackgroundDrawableResource(R.drawable.rounded_dialog_background);
            }

            progressDialog.show();
        });
    }

    private void dismissProgressDialog() {
        runOnUiThread(() -> {
            if (progressDialog != null) {
                try {
                    progressDialog.dismiss();
                } catch (IllegalArgumentException e) {
                    // Activity was likely destroyed, ignore
                    Log.e(TAG, "Error dismissing dialog", e);
                } finally {
                    progressDialog = null;
                }
            }
        });
    }

    private void sendAudioToFlask(String audioFilePath, String language) {
        String fullUrl = FLASK_BASE_URL + FLASK_EVALUATE_ENDPOINT;

        // Get the current phrase's reference locator
        final String referenceLocator;
        if (currentQuestionIndex < phraseItems.size()) {
            referenceLocator = phraseItems.get(currentQuestionIndex).getReferencelocator();
        } else {
            referenceLocator = "";
        }

        if (referenceLocator.isEmpty()) {
            Log.e(TAG, "No reference locator available for current question");
            tvFeedback.setVisibility(View.VISIBLE);
            tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvFeedback.setText("Error: Unable to evaluate pronunciation. Please try again later.");
            dismissProgressDialog();
            return;
        }

        // Check audio file before attempting to send
        File audioFile = new File(audioFilePath);
        if (!audioFile.exists() || audioFile.length() < MINIMUM_FILE_SIZE_BYTES) {
            Log.e(TAG, "Audio file does not exist or is too small: " + audioFilePath);
            tvFeedback.setVisibility(View.VISIBLE);
            tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvFeedback.setText("Recording too short! Please try again.");
            return;
        }

        // Show progress dialog with custom message
        showProgressDialog("Evaluating your translation...");

        VolleyMultipartRequest multipartRequest = new VolleyMultipartRequest(
                Request.Method.POST,
                fullUrl,
                response -> {
                    // Dismiss the dialog when response is received
                    dismissProgressDialog();

                    try {
                        String responseData = new String(response.data);
                        JSONObject jsonResponse = new JSONObject(responseData);

                        // Extract evaluation results
                        boolean isCorrect = jsonResponse.getBoolean("is_correct");
                        double score = jsonResponse.getDouble("score");
                        String feedback = jsonResponse.getString("feedback");

                        Log.i(TAG, "Pronunciation evaluation: Score=" + score +
                                ", Correct=" + isCorrect + ", Feedback=" + feedback);

                        // Process the evaluation result - now passing the score
                        processEvaluationResult(isCorrect, feedback, score);

                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing evaluation response", e);
                        tvFeedback.setVisibility(View.VISIBLE);
                        tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        tvFeedback.setText("Server response error. Please try again later.");
                        showToast("Error processing evaluation");
                    }
                },
                error -> {
                    // Dismiss the dialog on error too
                    dismissProgressDialog();

                    Log.e(TAG, "Evaluation request failed: " + error.toString(), error);
                    tvFeedback.setVisibility(View.VISIBLE);
                    tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));

                    // Check if it's a network connectivity issue
                    if (!isNetworkConnected()) {
                        tvFeedback.setText("Internet connection required. Please connect to the internet and try again.");
                        showToast("Internet connection required");
                    } else {
                        tvFeedback.setText("Server error. Please try again later.");
                        showToast("Error connecting to server");
                    }
                }
        ) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("language", language);
                params.put("reference_locator", referenceLocator);
                return params;
            }

            @Override
            protected Map<String, DataPart> getByteData() {
                Map<String, DataPart> params = new HashMap<>();
                try {
                    File audioFile = new File(audioFilePath);
                    if (!audioFile.exists() || !audioFile.canRead()) {
                        Log.e(TAG, "Audio file does not exist or cannot be read: " + audioFilePath);
                        return params;
                    }

                    byte[] fileData = readFileToByteArray(audioFile);
                    params.put("audio", new DataPart("audio.wav", fileData, "audio/wav"));
                } catch (IOException e) {
                    Log.e(TAG, "Error reading audio file", e);
                }
                return params;
            }

            @Override
            public String getBodyContentType() {
                return "multipart/form-data; boundary=" + boundary;
            }

            private final String boundary = "apiclient-" + System.currentTimeMillis();
            private final String lineEnd = "\r\n";
            private final String twoHyphens = "--";

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
                    bos.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes());

                    return bos.toByteArray();
                } catch (IOException e) {
                    Log.e(TAG, "Error creating request body", e);
                    return null;
                } finally {
                    try {
                        bos.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing output stream", e);
                    }
                }
            }

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
        };

        // Set a longer timeout for the request (3 minutes)
        multipartRequest.setRetryPolicy(new DefaultRetryPolicy(
                180000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        requestQueue.add(multipartRequest);
    }

    private void processEvaluationResult(boolean isCorrect, String feedback, double score) {
        // Ensure we're not working with a destroyed activity
        if (isFinishing() || isDestroyed()) {
            return;
        }

        // Remove any pending auto-advance
        autoAdvanceHandler.removeCallbacksAndMessages(null);

        runOnUiThread(() -> {
            tvFeedback.setVisibility(View.VISIBLE);

            // Store the score, but only if it's better than the previous score
            // This ensures we keep the user's best attempt
            if (score > questionScores[currentQuestionIndex]) {
                questionScores[currentQuestionIndex] = score;
            }

            if (isCorrect) {
                // Pronunciation is correct
                tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                tvFeedback.setText(feedback);

                if (!questionAnswered[currentQuestionIndex]) {
                    correctAnswers++;
                    questionAnswered[currentQuestionIndex] = true;
                    updateProgress();
                    saveProgress();
                }

                // Show dialog with score
                showPronunciationResultDialog(true, feedback, score);
            } else {
                // Pronunciation needs improvement
                tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                tvFeedback.setText(feedback);

                // Show dialog with score
                showPronunciationResultDialog(false, feedback, score);
            }
        });
    }

    private void evaluatePronunciation() {
        if (phraseItems == null || phraseItems.isEmpty()) {
            Log.e(TAG, "Cannot evaluate pronunciation - no phrase items");
            return;
        }

        // Show "Processing..." feedback
        tvFeedback.setVisibility(View.VISIBLE);
        tvFeedback.setTextColor(getResources().getColor(android.R.color.darker_gray));
        tvFeedback.setText("Processing...");

        // Cancel any pending auto advance
        autoAdvanceHandler.removeCallbacksAndMessages(null);

        // Check if audio file exists and has meaningful content before sending
        File audioFile = new File(audioFilePath);
        if (!audioFile.exists() || audioFile.length() < MINIMUM_FILE_SIZE_BYTES) {
            Log.e(TAG, "Audio file does not exist or is too small: " + audioFilePath);
            tvFeedback.setVisibility(View.VISIBLE);
            tvFeedback.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvFeedback.setText("Recording too short! Please try again.");
            return;
        }

        // Send the recording to the Flask API
        String selectedLanguage = "kapampangan"; // Since this is a Kapampangan exam
        sendAudioToFlask(audioFilePath, selectedLanguage);
    }

    // Enhanced version that includes the score in the dialog and shows correct translation
    private void showPronunciationResultDialog(boolean isCorrect, String feedback, double score) {
        if (isFinishing() || isDestroyed()) return;

        // Format the score with no decimal places
        String formattedScore = String.format("%.0f%%", score);

        // Get the previous best score for comparison
        double previousBestScore = questionScores[currentQuestionIndex];

        // Determine if this is a new best score (will be true for first attempt)
        boolean isNewBestScore = score > previousBestScore;

        // Format the best score message
        String bestScoreMessage;
        if (!isCorrect && !isNewBestScore && previousBestScore > 0) {
            // Only show previous best if current attempt isn't better
            bestScoreMessage = String.format("\n\nYour best score: %.0f%%", previousBestScore);
        } else {
            bestScoreMessage = "";
        }

        // Get the current item
        PhraseItem currentItem = phraseItems.get(currentQuestionIndex);

        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            if (isCorrect) {
                // Success dialog with score
                builder.setTitle("Good Translation & Pronunciation!");

                // Only show the correct answer if in review mode, otherwise just confirm success
                if (examCompleted) {
                    builder.setMessage("Correct: \"" + currentItem.getKapampangan() + "\"\n\n" +
                            feedback + "\n\nYour accuracy: " + formattedScore + bestScoreMessage);
                } else {
                    builder.setMessage(feedback + "\n\nYour accuracy: " + formattedScore + bestScoreMessage);
                }

                builder.setCancelable(false)
                        .setPositiveButton("Proceed", (dialog, which) -> {
                            // Move to the next question
                            moveToNextQuestion();
                        });
            } else {
                // Failure dialog with score
                builder.setTitle("Try Again");

                // During exam mode, don't show the correct answer
                if (examCompleted) {
                    // In review mode, show the correct answer and allow listening
                    builder.setMessage("The correct translation is: \"" + currentItem.getKapampangan() + "\"\n\n" +
                                    feedback + "\n\nYour accuracy: " + formattedScore + bestScoreMessage)
                            .setCancelable(false)
                            .setPositiveButton("Listen to Correct Version", (dialog, which) -> {
                                // Play the pronunciation
                                playPronunciation();
                            })
                            .setNegativeButton("Continue", (dialog, which) -> {
                                // Just close the dialog
                            });
                } else {
                    // In exam mode, don't show correct answer or offer listening option
                    builder.setMessage("Your translation needs improvement.\n\n" +
                                    feedback + "\n\nYour accuracy: " + formattedScore + bestScoreMessage)
                            .setCancelable(false)
                            .setPositiveButton("Try Again", (dialog, which) -> {
                                // Just close the dialog and let them try again
                            });
                }
            }

            AlertDialog dialog = builder.create();

            // Style the dialog buttons
            dialog.setOnShowListener(dialogInterface -> {
                Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

                int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
                positiveButton.setTextColor(buttonColor);
                if (negativeButton != null) {
                    negativeButton.setTextColor(buttonColor);
                }
            });

            dialog.show();
        });
    }

    private void moveToNextQuestion() {
        currentQuestionIndex++;

        // Check if we've reached the end
        if (currentQuestionIndex >= phraseItems.size() || currentQuestionIndex >= TOTAL_QUESTIONS) {
            // Complete the exam if average score meets the passing threshold
            if (calculateAverageScore() >= PASSING_SCORE) {
                completeExam();
                // Stay on last question (index TOTAL_QUESTIONS-1 or greetingItems.size()-1) instead of resetting to 0
                currentQuestionIndex = Math.min(TOTAL_QUESTIONS - 1, phraseItems.size() - 1);
            } else {
                showFailureDialog();
                // Reset to first question for review if failed
                currentQuestionIndex = 0;
            }
        }

        // Save the current index to Firebase to sync across devices
        saveProgress();

        // Add null check before displaying question
        if (phraseItems != null && !phraseItems.isEmpty()) {
            displayCurrentQuestion();
        } else {
            Log.e(TAG, "Unable to display question - phraseItems is null or empty");
            // Attempt to recover by reloading
            fetchExamItemsFromFirebase();
        }
    }

    // Calculate average score based on all answered questions
    private double calculateAverageScore() {
        double totalScore = 0;
        int answeredCount = 0;

        for (int i = 0; i < TOTAL_QUESTIONS && i < phraseItems.size(); i++) {
            if (questionAnswered[i]) {
                totalScore += questionScores[i];
                answeredCount++;
            }
        }

        // If no questions answered, return 0
        if (answeredCount == 0) {
            return 0;
        }

        return totalScore / answeredCount;
    }

    private byte[] readFileToByteArray(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("File not found: " + (file != null ? file.getAbsolutePath() : "null"));
        }

        FileInputStream fis = null;
        ByteArrayOutputStream bos = null;

        try {
            fis = new FileInputStream(file);
            bos = new ByteArrayOutputStream((int)file.length());
            byte[] buffer = new byte[8192]; // Larger buffer for efficiency
            int read;

            while ((read = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }

            return bos.toByteArray();
        } finally {
            // Close resources in finally block to ensure they're closed even if an exception occurs
            closeQuietly(fis);
            closeQuietly(bos);
        }
    }

    // Helper method to close streams quietly
    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing resource", e);
            }
        }
    }

    private void updateProgress() {
        int totalQuestions = Math.min(TOTAL_QUESTIONS, phraseItems.size());
        int answeredCount = 0;
        for (int i = 0; i < totalQuestions; i++) {
            if (questionAnswered[i]) answeredCount++;
        }

        int progress = (answeredCount * 100) / totalQuestions;
        progressIndicator.setProgress(progress);
        progressText.setText(answeredCount + "/" + totalQuestions + " Phrases Completed");

        // Show completion checkmark if exam is completed
        if (completionCheckMark != null) {
            completionCheckMark.setVisibility(examCompleted ? View.VISIBLE : View.GONE);
        }

        // Update the question number display based on completion status
        if (examCompleted && questionNumber != null) {
            questionNumber.setText((currentQuestionIndex + 1) + "/" + totalQuestions);

            // Disable recording when exam is completed
            fabRecord.setEnabled(false);
            fabRecord.setAlpha(0.5f);
            tvRecordingInstructions.setText("Exam completed! Swipe left/right to review Kapampangan translations.");

            // Show the listen button when exam is completed
            btnListen.setVisibility(View.VISIBLE);
        }
    }

    private void completeExam() {
        if (examCompleted) return;

        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();

            // Get the actual average score
            double averageScore = calculateAverageScore();

            // Only mark as completed if the average score meets the passing threshold
            boolean passed = averageScore >= PASSING_SCORE;

            if (passed) {
                // Mark exam as completed in Firebase
                userRef.child(userId)
                        .child("examsProgress")
                        .child(EXAM_ID)
                        .setValue(true)
                        .addOnSuccessListener(aVoid -> {
                            examCompleted = true;
                            updateProgress(); // Update to show completion checkmark
                            showCompletionDialog();
                        })
                        .addOnFailureListener(e -> showToast("Failed to update progress"));
            } else {
                // Exam not passed, show failure dialog
                showFailureDialog();
            }
        } else {
            showToast("You need to be logged in to save progress");
        }
    }

    private void saveProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        String userId = currentUser.getUid();

        // Create a batch update for efficiency
        Map<String, Object> updates = new HashMap<>();

        // Save progress for each question
        int totalQuestions = Math.min(TOTAL_QUESTIONS, phraseItems.size());
        for (int i = 0; i < totalQuestions; i++) {
            updates.put("/examProgress/" + EXAM_ID + "/question" + i, questionAnswered[i]);
            // Also save the score for this question
            updates.put("/examProgress/" + EXAM_ID + "/questionScore" + i, questionScores[i]);
        }

        // Save current question index to sync across devices
        updates.put("/examProgress/" + EXAM_ID + "/currentQuestionIndex", currentQuestionIndex);

        // Save exam metadata
        Map<String, Object> examData = new HashMap<>();
        examData.put("correctAnswers", correctAnswers);
        examData.put("totalQuestions", totalQuestions);
        examData.put("averageScore", calculateAverageScore());
        examData.put("lastAttempt", System.currentTimeMillis());
        updates.put("/exams/" + EXAM_ID + "/data", examData);

        userRef.child(userId)
                .updateChildren(updates)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to save progress", e);
                    showToast("Failed to save progress");
                });
    }

    private void loadUserProgress() {
        initializeAndSaveQuestionOrder();
    }

    private void initializeAndSaveQuestionOrder() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            // If not logged in, just use local ordering
            if (phrasesLoaded && !allPhraseItems.isEmpty()) {
                shufflePhraseItems();
                displayCurrentQuestion();
                updateProgress();
            }
            return;
        }

        String userId = currentUser.getUid();

        // Check if question order already exists
        userRef.child(userId)
                .child("exams")
                .child(EXAM_ID)
                .child("questionOrder")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<Integer> savedOrder = new ArrayList<>();

                        if (snapshot.exists() && snapshot.getChildrenCount() == TOTAL_QUESTIONS) {
                            // Order exists, load it
                            for (DataSnapshot indexSnapshot : snapshot.getChildren()) {
                                Integer index = indexSnapshot.getValue(Integer.class);
                                if (index != null) {
                                    savedOrder.add(index);
                                }
                            }
                        }

                        // Only proceed if we actually have items to display
                        if (allPhraseItems.isEmpty()) {
                            Log.d(TAG, "initializeAndSaveQuestionOrder: No items available yet");
                            return;
                        }

                        // If we have a valid saved order, use it
                        if (savedOrder.size() == TOTAL_QUESTIONS) {
                            phraseItems.clear();
                            for (Integer index : savedOrder) {
                                // Check index bounds to prevent crashes
                                if (index >= 0 && index < allPhraseItems.size()) {
                                    phraseItems.add(allPhraseItems.get(index));
                                }
                            }
                        } else {
                            // No valid order exists, create and save new one
                            shufflePhraseItems();
                            saveCurrentQuestionOrder(userId);
                        }

                        // Display the questions after loading/creating order
                        displayCurrentQuestion();

                        // After question order is established, check completion status
                        checkExamCompletion(userId);

                        // Set up real-time listener AFTER initial setup
                        setupRealtimeSync();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load/save question order", error.toException());
                        // Fallback to local shuffling if Firebase fails
                        if (!allPhraseItems.isEmpty()) {
                            shufflePhraseItems();
                            displayCurrentQuestion();
                            updateProgress();
                        }
                    }
                });
    }

    // Add network connectivity check helper method
    private boolean isNetworkConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
            return capabilities != null &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
    }

    private void saveCurrentQuestionOrder(String userId) {
        // Extract indices from current phraseItems based on position in allPhraseItems
        List<Integer> indices = new ArrayList<>();
        for (PhraseItem item : phraseItems) {
            int index = allPhraseItems.indexOf(item);
            if (index >= 0) {
                indices.add(index);
            }
        }

        // Ensure we have exactly TOTAL_QUESTIONS items (or all available if fewer)
        int size = Math.min(TOTAL_QUESTIONS, indices.size());
        if (size > 0) {
            while (indices.size() > size) {
                indices.remove(indices.size() - 1);
            }

            userRef.child(userId)
                    .child("exams")
                    .child(EXAM_ID)
                    .child("questionOrder")
                    .setValue(indices)
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Failed to save question order", e));
        }
    }

    private void checkExamCompletion(String userId) {
        // Check if exam is already completed
        userRef.child(userId)
                .child("examsProgress")
                .child(EXAM_ID)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists() && Boolean.TRUE.equals(snapshot.getValue(Boolean.class))) {
                            examCompleted = true;

                            // If exam is completed, mark all questions as answered
                            int totalQuestions = Math.min(TOTAL_QUESTIONS, phraseItems.size());
                            for (int i = 0; i < totalQuestions; i++) {
                                questionAnswered[i] = true;
                            }
                            correctAnswers = totalQuestions;

                            // Update UI state
                            updateExamCompletedUI();
                            updateProgress();
                        } else {
                            // Exam not completed yet, load any saved progress
                            loadExamProgress(userId);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to check exam completion status", error.toException());
                    }
                });

        // Track that the user has started this exam
        userRef.child(userId)
                .child("exams")
                .child(EXAM_ID)
                .child("started")
                .setValue(true)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to track exam start", e));
    }

    private void updateExamCompletedUI() {
        // Disable recording
        fabRecord.setEnabled(false);
        fabRecord.setAlpha(0.5f);
        tvRecordingInstructions.setText("Exam completed! Swipe left/right to review Kapampangan translations.");

        // Show the listen button when exam is completed
        btnListen.setVisibility(View.VISIBLE);
    }

    private void setupRealtimeSync() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        String userId = currentUser.getUid();

        // Remove any existing listener
        if (realTimeListener != null) {
            userRef.child(userId).removeEventListener(realTimeListener);
        }

        // Add real-time listener
        realTimeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Check if we have items to work with
                if (allPhraseItems.isEmpty()) {
                    return;
                }

                // Get exam completion status
                boolean isExamComplete = snapshot.child("examsProgress")
                        .child(EXAM_ID)
                        .exists() &&
                        Boolean.TRUE.equals(snapshot.child("examsProgress")
                                .child(EXAM_ID)
                                .getValue(Boolean.class));

                // Check for changes in question order
                DataSnapshot orderSnapshot = snapshot.child("exams")
                        .child(EXAM_ID)
                        .child("questionOrder");

                if (orderSnapshot.exists() && orderSnapshot.getChildrenCount() > 0) {
                    // Load the order from Firebase
                    List<Integer> savedOrder = new ArrayList<>();
                    for (DataSnapshot indexSnapshot : orderSnapshot.getChildren()) {
                        Integer index = indexSnapshot.getValue(Integer.class);
                        if (index != null) {
                            savedOrder.add(index);
                        }
                    }

                    // Check if we need to update the local order
                    if (!savedOrder.isEmpty()) {
                        boolean orderChanged = false;

                        // Compare current order with saved order
                        if (phraseItems.size() == savedOrder.size()) {
                            for (int i = 0; i < savedOrder.size(); i++) {
                                int expectedIndex = savedOrder.get(i);
                                if (expectedIndex >= 0 && expectedIndex < allPhraseItems.size()) {
                                    PhraseItem expectedItem = allPhraseItems.get(expectedIndex);
                                    if (i >= phraseItems.size() || !phraseItems.get(i).equals(expectedItem)) {
                                        orderChanged = true;
                                        break;
                                    }
                                }
                            }
                        } else {
                            orderChanged = true;
                        }

                        // Update local order if changed
                        if (orderChanged) {
                            phraseItems.clear();
                            for (Integer index : savedOrder) {
                                if (index >= 0 && index < allPhraseItems.size()) {
                                    phraseItems.add(allPhraseItems.get(index));
                                }
                            }
                            // Force refresh UI with new items
                            displayCurrentQuestion();
                        }
                    }
                }

                // Update exam completion status
                boolean statusChanged = examCompleted != isExamComplete;
                examCompleted = isExamComplete;

                // If exam is completed, enable all questions as answered
                if (examCompleted) {
                    int totalQuestions = Math.min(TOTAL_QUESTIONS, phraseItems.size());
                    for (int i = 0; i < totalQuestions; i++) {
                        questionAnswered[i] = true;
                    }
                    correctAnswers = totalQuestions;

                    // When exam is complete, show the current question
                    // Don't automatically go to the last question to allow for review

                    // Update UI for completed state
                    updateExamCompletedUI();
                    updateProgress();
                    displayCurrentQuestion(); // Add this to update the question display

                    // Show completion dialog only if status just changed
                    if (statusChanged) {
                        showCompletionDialog();
                    }
                } else {
                    // Enable recording
                    fabRecord.setEnabled(true);
                    fabRecord.setAlpha(1.0f);
                    tvRecordingInstructions.setText("Tap and hold to record your Kapampangan translation");

                    // Hide the listen button when exam is not completed
                    btnListen.setVisibility(View.GONE);

                    // Update progress for individual questions
                    if (snapshot.child("examProgress").child(EXAM_ID).exists()) {
                        // Reset progress counters
                        correctAnswers = 0;

                        // First reset all questions to unanswered
                        for (int i = 0; i < questionAnswered.length; i++) {
                            questionAnswered[i] = false;
                        }

                        // Update question answered status
                        int totalQuestions = Math.min(TOTAL_QUESTIONS, phraseItems.size());
                        for (int i = 0; i < totalQuestions; i++) {
                            DataSnapshot questionSnapshot = snapshot.child("examProgress")
                                    .child(EXAM_ID)
                                    .child("question" + i);

                            if (questionSnapshot.exists()) {
                                boolean answered = Boolean.TRUE.equals(questionSnapshot.getValue(Boolean.class));
                                questionAnswered[i] = answered;
                                if (answered) correctAnswers++;
                            }

                            // Load the scores for each question
                            DataSnapshot scoreSnapshot = snapshot.child("examProgress")
                                    .child(EXAM_ID)
                                    .child("questionScore" + i);

                            if (scoreSnapshot.exists()) {
                                Double savedScore = scoreSnapshot.getValue(Double.class);
                                if (savedScore != null) {
                                    questionScores[i] = savedScore;
                                }
                            }
                        }

                        // Sync current question index
                        DataSnapshot indexSnapshot = snapshot.child("examProgress")
                                .child(EXAM_ID)
                                .child("currentQuestionIndex");

                        if (indexSnapshot.exists()) {
                            Integer syncedIndex = indexSnapshot.getValue(Integer.class);
                            if (syncedIndex != null && syncedIndex != currentQuestionIndex) {
                                // Only update if different to avoid unnecessary UI refreshes
                                currentQuestionIndex = syncedIndex;
                                displayCurrentQuestion();
                            }
                        }

                        updateProgress();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Real-time sync failed", error.toException());
            }
        };

        userRef.child(userId).addValueEventListener(realTimeListener);
    }

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

    private void loadExamProgress(String userId) {
        userRef.child(userId)
                .child("examProgress")
                .child(EXAM_ID)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            // Reset progress
                            correctAnswers = 0;

                            // Load progress for each question
                            int totalQuestions = Math.min(TOTAL_QUESTIONS, phraseItems.size());
                            for (int i = 0; i < totalQuestions; i++) {
                                DataSnapshot questionSnapshot = snapshot.child("question" + i);
                                if (questionSnapshot.exists()) {
                                    boolean answered = Boolean.TRUE.equals(questionSnapshot.getValue(Boolean.class));
                                    questionAnswered[i] = answered;
                                    if (answered) correctAnswers++;
                                } else {
                                    questionAnswered[i] = false;
                                }

                                // Load the score for this question
                                DataSnapshot scoreSnapshot = snapshot.child("questionScore" + i);
                                if (scoreSnapshot.exists()) {
                                    Double savedScore = scoreSnapshot.getValue(Double.class);
                                    if (savedScore != null) {
                                        questionScores[i] = savedScore;
                                    }
                                } else {
                                    questionScores[i] = 0;
                                }
                            }

                            // Load current question index
                            DataSnapshot indexSnapshot = snapshot.child("currentQuestionIndex");
                            if (indexSnapshot.exists()) {
                                Integer savedIndex = indexSnapshot.getValue(Integer.class);
                                if (savedIndex != null) {
                                    currentQuestionIndex = savedIndex;
                                }
                            }

                            // If exam is completed, ensure we're showing the current question
                            if (examCompleted) {
                                // Don't reset to last question automatically to support swipe navigation
                            }

                            // Update UI after loading data
                            displayCurrentQuestion();
                            updateProgress();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load exam progress", error.toException());
                    }
                });
    }

    private void resetProgress() {
        // Cancel any pending auto advance
        autoAdvanceHandler.removeCallbacksAndMessages(null);

        // Reset local state
        currentQuestionIndex = 0;
        correctAnswers = 0;
        for (int i = 0; i < questionAnswered.length; i++) {
            questionAnswered[i] = false;
            questionScores[i] = 0; // Reset scores as well
        }
        examCompleted = false;

        // Re-enable recording
        fabRecord.setEnabled(true);
        fabRecord.setAlpha(1.0f);
        tvRecordingInstructions.setText("Tap and hold to record your Kapampangan translation");

        // Hide the listen button when exam is not completed
        btnListen.setVisibility(View.GONE);

        // Reset in Firebase if logged in
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();

            // Create new shuffled order
            shufflePhraseItems();

            // Save the new question order first to ensure it's available when the progress is reset
            List<Integer> indices = new ArrayList<>();
            for (PhraseItem item : phraseItems) {
                int index = allPhraseItems.indexOf(item);
                if (index >= 0) {
                    indices.add(index);
                }
            }

            // Create a batch update to ensure atomicity
            Map<String, Object> updates = new HashMap<>();

            // Clear all progress data
            updates.put("/examProgress/" + EXAM_ID, null);
            updates.put("/examsProgress/" + EXAM_ID, false);

            // Set the new question order in the same update
            updates.put("/exams/" + EXAM_ID + "/questionOrder", indices);

            // Reset current question index
            Map<String, Object> examProgress = new HashMap<>();
            examProgress.put("currentQuestionIndex", 0);
            updates.put("/examProgress/" + EXAM_ID, examProgress);

            userRef.child(userId)
                    .updateChildren(updates)
                    .addOnSuccessListener(aVoid -> {
                        // Update UI
                        displayCurrentQuestion();
                        // Always update progress to ensure progress bar is reset
                        updateProgress();
                        showToast("Progress reset successfully");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to reset progress", e);
                        showToast("Failed to reset progress");
                    });
        } else {
            // Local-only reset
            shufflePhraseItems();
            displayCurrentQuestion();
            updateProgress();
            showToast("Progress reset successfully");
        }
    }

    private void showResetConfirmation() {
        // Dismiss any existing dialog first
        dismissDialog(resetConfirmDialogRef);

        // Don't show if finishing or destroyed
        if (isFinishing() || isDestroyed()) {
            return;
        }

        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Reset Progress")
                    .setMessage("Are you sure you want to reset your progress? This will clear all your answers.")
                    .setPositiveButton("Yes", (dialog, which) -> resetProgress())
                    .setNegativeButton("No", null);

            AlertDialog resetConfirmDialog = builder.create();
            resetConfirmDialogRef = new WeakReference<>(resetConfirmDialog);

            resetConfirmDialog.setOnShowListener(dialog -> {
                Button positiveButton = resetConfirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button negativeButton = resetConfirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);

                int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
                positiveButton.setTextColor(buttonColor);
                if (negativeButton != null) {
                    negativeButton.setTextColor(buttonColor);
                }
            });

            resetConfirmDialog.show();
        });
    }

    private void showCompletionDialog() {
        if (isFinishing() || isDestroyed()) return;

        // Dismiss any existing dialog first
        dismissDialog(completionDialogRef);

        runOnUiThread(() -> {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_activity_completed_quiz, null);
            Button okButton = dialogView.findViewById(R.id.okButton);
            TextView scoreText = dialogView.findViewById(R.id.scoreText);
            TextView instructionsText = dialogView.findViewById(R.id.instructionsText);

            // Calculate the final score based on average accuracy
            int score = (int) Math.round(calculateAverageScore());
            scoreText.setText(String.format("Your score: %d%%", score));

            // Set swipe instructions if the view exists
            if (instructionsText != null) {
                instructionsText.setText("You can now review all the Kapampangan translations. Swipe left/right to navigate between questions and listen to correct pronunciations.");
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setView(dialogView)
                    .setCancelable(true);  // Allow cancellation by back button

            AlertDialog completionDialog = builder.create();
            completionDialogRef = new WeakReference<>(completionDialog);

            okButton.setOnClickListener(v -> {
                dismissDialog(completionDialogRef);
                // Don't finish the activity, so they can review all questions
                // finish();
                // overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            });

            if (completionDialog.getWindow() != null) {
                completionDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            completionDialog.show();
        });
    }

    private void showFailureDialog() {
        if (isFinishing() || isDestroyed()) return;

        // Dismiss any existing dialog first
        dismissDialog(failureDialogRef);

        // Calculate the final score based on average accuracy
        int score = (int) Math.round(calculateAverageScore());

        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Exam Not Completed")
                    .setMessage(String.format("You scored %d%%. You need %d%% to pass. Review and try again!",
                            score, PASSING_SCORE))
                    .setPositiveButton("OK", null);

            AlertDialog failureDialog = builder.create();
            failureDialogRef = new WeakReference<>(failureDialog);

            failureDialog.setOnShowListener(dialog -> {
                Button positiveButton = failureDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
                positiveButton.setTextColor(buttonColor);
            });

            failureDialog.show();
        });
    }

    private void setupNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                checkInternetConnection();
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void checkInternetConnection() {
        backgroundExecutor.execute(() -> {
            if (!isInternetStillAvailable()) {
                mainHandler.post(this::showNoInternetDialog);
            }
        });
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
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private void showNoInternetDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (noInternetDialogRef != null && noInternetDialogRef.get() != null && noInternetDialogRef.get().isShowing()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.customnointernetdialog, null);
        AlertDialog noInternetDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        noInternetDialogRef = new WeakReference<>(noInternetDialog);

        View okBtn = dialogView.findViewById(R.id.Okbtn);
        if (okBtn != null) {
            okBtn.setOnClickListener(v -> {
                goToLogin();
                dismissDialog(noInternetDialogRef);
            });
        }

        noInternetDialog.show();
        if (noInternetDialog.getWindow() != null) {
            noInternetDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private void goToLogin() {
        dismissAllDialogs();
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(ExamThree.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }

    private void dismissAllDialogs() {
        // Cancel any pending auto advance
        autoAdvanceHandler.removeCallbacksAndMessages(null);

        dismissProgressDialog();
        dismissDialog(completionDialogRef);
        dismissDialog(noInternetDialogRef);
        dismissDialog(resetConfirmDialogRef);
        dismissDialog(failureDialogRef);
    }

    private void dismissDialog(WeakReference<AlertDialog> dialogRef) {
        if (dialogRef != null) {
            AlertDialog dialog = dialogRef.get();
            if (dialog != null && dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Exception e) {
                    Log.e(TAG, "Error dismissing dialog", e);
                }
            }
        }
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media recorder", e);
            }
            mediaRecorder = null;
        }
        isRecording = false;
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media player", e);
            }
            mediaPlayer = null;
        }
    }

    private void releaseMediaResources() {
        releaseMediaRecorder();
        releaseMediaPlayer();
    }

    private void showToast(String message) {
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Cancel any pending auto advance to prevent memory leaks
        if (autoAdvanceHandler != null) {
            autoAdvanceHandler.removeCallbacksAndMessages(null);
        }

        // Release media resources
        releaseMediaResources();
    }

    @Override
    protected void onPause() {
        // Cancel any pending auto advance
        autoAdvanceHandler.removeCallbacksAndMessages(null);
        super.onPause();
        releaseMediaResources();
        dismissAllDialogs();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showToast("Recording permission granted");
            } else {
                // Permission denied
                showToast("Recording permission denied. Cannot record audio.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        // Cancel any pending auto advance
        autoAdvanceHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);

        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister network callback", e);
            }
        }

        if (auth.getCurrentUser() != null) {
            if (progressListener != null) {
                userRef.child(auth.getCurrentUser().getUid()).removeEventListener(progressListener);
            }
            if (realTimeListener != null) {
                userRef.child(auth.getCurrentUser().getUid()).removeEventListener(realTimeListener);
            }
        }

        // Clean up the exam items listener
        if (examItemsListener != null) {
            examItemsReference.removeEventListener(examItemsListener);
        }

        // Dismiss progress dialog
        dismissProgressDialog();

        releaseMediaResources();
        dismissAllDialogs();

        // Clean up volley request queue
        if (requestQueue != null) {
            requestQueue.cancelAll(TAG);
        }

        super.onDestroy();
    }

    private void setFullscreen() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setStatusBarColor(Color.TRANSPARENT);
    }
}