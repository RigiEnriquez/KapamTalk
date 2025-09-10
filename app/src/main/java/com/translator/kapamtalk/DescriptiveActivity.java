package com.translator.kapamtalk;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DescriptiveActivity extends AppCompatActivity {

    // Constants
    private static final String FIREBASE_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String ACTIVITY_ID = "activity4"; // Changed to activity4 for describe
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int PASSING_SCORE = 70;
    private static final int QUESTIONS_PER_SESSION = 10;

    // Firebase and network components
    private FirebaseAuth auth;
    private DatabaseReference userRef;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ValueEventListener progressListener;

    // UI components
    private RecyclerView questionsRecyclerView;
    private QuestionAdapter questionAdapter;
    private ProgressBar progressIndicator;
    private TextView progressText;
    private Button submitButton;
    private Button resetButton;
    private ImageButton backButton;
    private ImageView completionCheckMark;

    // Dialogs
    private AlertDialog noInternetDialog;
    private AlertDialog failureDialog;
    private AlertDialog completionDialog;
    private AlertDialog resetConfirmDialog;

    // Data and state
    private List<Question> allQuestions;
    private List<Question> activeQuestions;
    private int currentScore = 0;
    private boolean activityCompleted = false;
    private boolean feedbackVisible = false;
    private SharedPreferences preferences;

    // Background executor for network tasks
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setFullscreen();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.descriptive_activity); // You'll need to create this layout

        initializeComponents();
        setupUI();
        setupNetworkMonitoring();
        loadUserProgress();
    }

    //==========================================================================
    // Initialization Methods
    //==========================================================================
    private void initializeComponents() {
        auth = FirebaseAuth.getInstance();
        FirebaseDatabase database = FirebaseDatabase.getInstance(FIREBASE_URL);
        userRef = database.getReference("users");
        preferences = getSharedPreferences("ActivityPreferences", MODE_PRIVATE);

        allQuestions = createQuestionsList();
        activeQuestions = new ArrayList<>();
    }

    private void setupUI() {
        initViews();
        setListeners();
        updateProgressIndicator();
    }

    private void initViews() {
        questionsRecyclerView = findViewById(R.id.questionsRecyclerView);
        progressIndicator = findViewById(R.id.progressIndicator);
        progressText = findViewById(R.id.progressText);
        submitButton = findViewById(R.id.submitButton);
        resetButton = findViewById(R.id.resetButton);
        backButton = findViewById(R.id.backButton);
        completionCheckMark = findViewById(R.id.completionCheckMark);

        questionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        // Initialize adapter with feedback off
        questionAdapter = new QuestionAdapter(activeQuestions, false);
        questionAdapter.setProgressUpdateListener(this::updateProgressIndicator);
        questionsRecyclerView.setAdapter(questionAdapter);
    }

    private void setListeners() {
        submitButton.setOnClickListener(v -> {
            hideKeyboard();
            checkAnswers();
        });
        resetButton.setOnClickListener(v -> {
            hideKeyboard();
            showResetConfirmation();
        });
        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
            overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        });
    }

    //==========================================================================
    // Question List Creation and Selection
    //==========================================================================
    private List<Question> createQuestionsList() {
        List<Question> questionsList = new ArrayList<>();

        questionsList.add(new Question("d1", "What does 'Maputi' mean?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Black", "White", "Dark", "Bright"), "White"));

        questionsList.add(new Question("d2", "Which word describes something that is 'Far'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Malawut", "Malapit", "Makaba", "Matas"), "Malawut"));

        questionsList.add(new Question("d3", "When would you use the word 'Manyaman'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("To describe rainy weather", "To describe something dirty", "To describe delicious food", "To describe cold temperature"), "To describe delicious food"));

        questionsList.add(new Question("d4", "If the sky is cloudy, which word would be most appropriate?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Masala", "Mauran", "Malulam", "Madalumdum"), "Malulam"));

        questionsList.add(new Question("d5", "What does 'Makuyad' mean?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Long", "Short", "High", "Low"), "Short"));

        questionsList.add(new Question("d6", "Which word would you use to describe a small object?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Maragul", "Makaba", "Malati", "Makuyad"), "Malati"));

        questionsList.add(new Question("d7", "'Marinat' refers to something that is:",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Slippery", "Dirty", "Beautiful", "Sour"), "Dirty"));

        questionsList.add(new Question("d8", "Which word means 'Hot' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Marimla", "Malisangan", "Mapali", "Mauran"), "Mapali"));

        questionsList.add(new Question("d9", "What category does 'Masanting' belong to?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Quality and Condition", "Colors", "Size and Distance", "Temperature and Weather"), "Quality and Condition"));

        questionsList.add(new Question("d10", "Which word would you use to describe something that is 'Low'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Mababa", "Makuyad", "Matas", "Malati"), "Mababa"));

        questionsList.add(new Question("d11", "The Kapampangan word for 'Black' is ______.",
                Question.TYPE_FILL_BLANK, null, "Matuling"));

        questionsList.add(new Question("d12", "If you want to describe extremely bright light, you would use the word ______.",
                Question.TYPE_FILL_BLANK, null, "Masala"));

        questionsList.add(new Question("d13", "______ is the Kapampangan word for 'Slippery'.",
                Question.TYPE_FILL_BLANK, null, "Matalusad"));

        questionsList.add(new Question("d14", "______ is used to describe humid or sultry weather.",
                Question.TYPE_FILL_BLANK, null, "Malisangan"));

        questionsList.add(new Question("d15", "To describe a beautiful woman in Kapampangan, you would use the word ______.",
                Question.TYPE_FILL_BLANK, null, "Malagu"));

        questionsList.add(new Question("d16", "The Kapampangan word ______ means 'Good'.",
                Question.TYPE_FILL_BLANK, null, "Mayap"));

        questionsList.add(new Question("d17", "If something is nearby, you would describe it as ______.",
                Question.TYPE_FILL_BLANK, null, "Malapit"));

        questionsList.add(new Question("d18", "______ is the Kapampangan word that means 'Sour'.",
                Question.TYPE_FILL_BLANK, null, "Maslam"));

        questionsList.add(new Question("d19", "To describe something that is tall or high, you would use the word ______.",
                Question.TYPE_FILL_BLANK, null, "Matas"));

        questionsList.add(new Question("d20", "Rainy weather in Kapampangan is described as ______.",
                Question.TYPE_FILL_BLANK, null, "Mauran"));

        return questionsList;
    }

    private void selectRandomQuestions() {
        activeQuestions.clear();
        List<Question> shuffled = new ArrayList<>(allQuestions);
        Collections.shuffle(shuffled, new Random(System.currentTimeMillis()));
        int count = Math.min(QUESTIONS_PER_SESSION, shuffled.size());
        for (int i = 0; i < count; i++) {
            activeQuestions.add(shuffled.get(i));
        }
    }

    //==========================================================================
    // Firebase Realtime Sync and User Progress
    //==========================================================================
    private void loadUserProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            showToast("Please log in to save your progress");
            return;
        }
        final String userId = currentUser.getUid();
        userRef.child(userId)
                .child("activities")
                .child(ACTIVITY_ID)
                .child("activeQuestions")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            activeQuestions.clear();
                            List<String> activeQuestionIds = new ArrayList<>();
                            for (DataSnapshot qidSnap : snapshot.getChildren()) {
                                String qid = qidSnap.getValue(String.class);
                                if (qid != null) {
                                    activeQuestionIds.add(qid);
                                }
                            }
                            // Map remote questions to local ones
                            for (String qid : activeQuestionIds) {
                                for (Question q : allQuestions) {
                                    if (q.getQuestionId().equals(qid)) {
                                        activeQuestions.add(q);
                                        break;
                                    }
                                }
                            }
                            // Fill in additional questions if needed
                            if (activeQuestions.size() < QUESTIONS_PER_SESSION) {
                                List<Question> available = new ArrayList<>(allQuestions);
                                available.removeAll(activeQuestions);
                                Collections.shuffle(available);
                                while (activeQuestions.size() < QUESTIONS_PER_SESSION && !available.isEmpty()) {
                                    activeQuestions.add(available.remove(0));
                                }
                                saveActiveQuestionIds();
                            }
                        } else {
                            // No existing questions; select new set
                            selectRandomQuestions();
                            saveActiveQuestionIds();
                        }
                        refreshQuestionAdapter();
                        setupRealtimeSync();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        showToast("Failed to load questions");
                        selectRandomQuestions();
                        refreshQuestionAdapter();
                        setupRealtimeSync();
                    }
                });

        // Track activity start
        userRef.child(userId)
                .child("activities")
                .child(ACTIVITY_ID)
                .child("started")
                .setValue(true)
                .addOnFailureListener(e -> showToast("Failed to track activity start"));
    }

    private void setupRealtimeSync() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;
        final String userId = currentUser.getUid();

        progressListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Update activity completion
                boolean isComplete = snapshot.child("activitiesProgress")
                        .child(ACTIVITY_ID)
                        .exists() &&
                        snapshot.child("activitiesProgress")
                                .child(ACTIVITY_ID)
                                .getValue(Boolean.class);
                boolean oldComplete = activityCompleted;
                activityCompleted = isComplete;

                // Update feedback visibility
                boolean newFeedback = false;
                if (snapshot.child("activities").child(ACTIVITY_ID).child("data").child("feedbackVisible").exists()) {
                    newFeedback = snapshot.child("activities").child(ACTIVITY_ID).child("data").child("feedbackVisible").getValue(Boolean.class);
                }
                boolean feedbackChanged = (feedbackVisible != newFeedback);
                feedbackVisible = newFeedback;

                // Check for remote question changes
                boolean questionsChanged = false;
                if (snapshot.child("activities").child(ACTIVITY_ID).child("activeQuestions").exists()) {
                    List<String> remoteQuestionIds = new ArrayList<>();
                    for (DataSnapshot qSnap : snapshot.child("activities").child(ACTIVITY_ID).child("activeQuestions").getChildren()) {
                        String qid = qSnap.getValue(String.class);
                        if (qid != null) remoteQuestionIds.add(qid);
                    }
                    List<String> localQuestionIds = new ArrayList<>();
                    for (Question q : activeQuestions) {
                        localQuestionIds.add(q.getQuestionId());
                    }
                    if (remoteQuestionIds.size() != localQuestionIds.size() || !remoteQuestionIds.containsAll(localQuestionIds)) {
                        questionsChanged = true;
                        ArrayList<Question> newActive = new ArrayList<>();
                        for (String qid : remoteQuestionIds) {
                            for (Question q : allQuestions) {
                                if (q.getQuestionId().equals(qid)) {
                                    newActive.add(q);
                                    break;
                                }
                            }
                        }
                        if (newActive.size() < QUESTIONS_PER_SESSION) {
                            List<Question> available = new ArrayList<>(allQuestions);
                            available.removeAll(newActive);
                            Collections.shuffle(available);
                            while (newActive.size() < QUESTIONS_PER_SESSION && !available.isEmpty()) {
                                newActive.add(available.remove(0));
                            }
                        }
                        activeQuestions.clear();
                        activeQuestions.addAll(newActive);
                    }
                }

                // Update question answers from snapshot
                updateQuestionsFromSnapshot(snapshot);

                // Refresh adapter if any state changes occurred
                if (oldComplete != activityCompleted || feedbackChanged || questionsChanged) {
                    refreshQuestionAdapter();
                    if (questionsChanged) {
                        showToast("Questions have been updated");
                    }
                }
                updateUIBasedOnCompletion(isComplete);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showToast("Failed to sync with cloud");
            }
        };

        userRef.child(userId).addValueEventListener(progressListener);
    }

    private void updateQuestionsFromSnapshot(DataSnapshot snapshot) {
        // Reset answers then update with existing progress
        for (Question q : activeQuestions) {
            q.setUserAnswer(null);
        }
        if (snapshot.child("questionProgress").child(ACTIVITY_ID).exists()) {
            for (DataSnapshot qSnap : snapshot.child("questionProgress").child(ACTIVITY_ID).getChildren()) {
                String qid = qSnap.getKey();
                String answer = qSnap.getValue(String.class);
                if (answer != null && !answer.isEmpty()) {
                    for (Question q : activeQuestions) {
                        if (q.getQuestionId().equals(qid)) {
                            q.setUserAnswer(answer);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void saveActiveQuestionIds() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;
        String userId = currentUser.getUid();
        List<String> activeIds = new ArrayList<>();
        for (Question q : activeQuestions) {
            activeIds.add(q.getQuestionId());
        }
        userRef.child(userId)
                .child("activities")
                .child(ACTIVITY_ID)
                .child("activeQuestions")
                .setValue(activeIds)
                .addOnFailureListener(e -> showToast("Failed to save question selection"));
    }

    //==========================================================================
    // Answer Checking and Progress Updates
    //==========================================================================
    private void checkAnswers() {
        hideKeyboard();
        int answeredCount = 0, correctCount = 0;
        int total = activeQuestions.size();
        for (Question q : activeQuestions) {
            if (q.isAnswered()) {
                answeredCount++;
                if (q.isCorrect()) {
                    correctCount++;
                }
            }
        }
        if (answeredCount < total && !activityCompleted) {
            showToast("Please answer all questions before submitting");
            return;
        }
        currentScore = (correctCount * 100) / total;
        questionAdapter.showFeedback();
        feedbackVisible = true;
        saveProgress();

        if (activityCompleted) {
            showReviewSummary(correctCount, total);
        } else {
            checkAndUpdateProgress(currentScore);
        }
    }

    private void saveProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;
        String userId = currentUser.getUid();
        Map<String, Object> updates = new HashMap<>();

        // Save each answered question
        for (Question q : activeQuestions) {
            if (q.isAnswered()) {
                updates.put("/questionProgress/" + ACTIVITY_ID + "/" + q.getQuestionId(), q.getUserAnswer());
            }
        }
        // Save active questions
        List<String> activeIds = new ArrayList<>();
        for (Question q : activeQuestions) {
            activeIds.add(q.getQuestionId());
        }
        updates.put("/activities/" + ACTIVITY_ID + "/activeQuestions", activeIds);

        // Save activity metadata
        Map<String, Object> activityData = new HashMap<>();
        activityData.put("totalQuestions", totalQuestions());
        activityData.put("score", currentScore);
        activityData.put("lastAttempt", System.currentTimeMillis());
        activityData.put("feedbackVisible", feedbackVisible);
        updates.put("/activities/" + ACTIVITY_ID + "/data", activityData);

        userRef.child(userId)
                .updateChildren(updates)
                .addOnFailureListener(e -> showToast("Failed to save progress"));
    }

    private void checkAndUpdateProgress(int score) {
        currentScore = score;
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentScore >= PASSING_SCORE && currentUser != null) {
            String userId = currentUser.getUid();
            userRef.child(userId)
                    .child("activitiesProgress")
                    .child(ACTIVITY_ID)
                    .setValue(true)
                    .addOnSuccessListener(aVoid -> {
                        activityCompleted = true;
                        updateUIBasedOnCompletion(true);
                        showCompletionDialog();
                    })
                    .addOnFailureListener(e -> showToast("Failed to update progress"));
        } else {
            if (!activityCompleted) {
                showFailureDialog();
            }
        }
    }

    private int totalQuestions() {
        return activeQuestions.size();
    }

    private void refreshQuestionAdapter() {
        questionAdapter = new QuestionAdapter(activeQuestions, feedbackVisible);
        questionAdapter.setProgressUpdateListener(this::updateProgressIndicator);
        questionsRecyclerView.setAdapter(questionAdapter);
        updateProgressIndicator();
        updateUIBasedOnCompletion(activityCompleted);
    }

    private void updateProgressIndicator() {
        int answered = 0;
        for (Question q : activeQuestions) {
            if (q.getUserAnswer() != null && !q.getUserAnswer().isEmpty()) {
                answered++;
            }
        }
        int progress = (activeQuestions.size() > 0) ? (answered * 100 / activeQuestions.size()) : 0;
        progressIndicator.setProgress(progress);
        progressText.setText(answered + "/" + activeQuestions.size() + " Questions Completed");
    }

    private void updateUIBasedOnCompletion(boolean isComplete) {
        submitButton.setText(isComplete ? "Review Answers" : "Submit Answers");
        submitButton.setEnabled(isComplete || !feedbackVisible);
        completionCheckMark.setVisibility(isComplete ? View.VISIBLE : View.GONE);
    }

    private void showReviewSummary(int correct, int total) {
        int score = (correct * 100) / total;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Review Summary")
                .setMessage(String.format("You got %d out of %d questions correct. Your score is %d%%.", correct, total, score))
                .setPositiveButton("OK", null);
        failureDialog = builder.create();
        failureDialog.setOnShowListener(dialog -> {
            Button positiveButton = failureDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setTextColor(getResources().getColor(R.color.nav_bar_color_default));
        });
        failureDialog.show();
    }

    //==========================================================================
    // Reset Progress
    //==========================================================================
    private void showResetConfirmation() {
        hideKeyboard();
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Reset Progress")
                .setMessage("Are you sure you want to reset your progress? This will clear all your answers and generate a new set of questions.")
                .setPositiveButton("Yes", (dialog, which) -> resetProgress())
                .setNegativeButton("No", null);
        resetConfirmDialog = builder.create();
        resetConfirmDialog.setOnShowListener(dialog -> {
            Button pos = resetConfirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button neg = resetConfirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            int color = getResources().getColor(R.color.nav_bar_color_default);
            pos.setTextColor(color);
            neg.setTextColor(color);
        });
        resetConfirmDialog.show();
    }

    private void resetProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            final String userId = currentUser.getUid();
            Map<String, Object> updates = new HashMap<>();
            updates.put("/questionProgress/" + ACTIVITY_ID, null);
            updates.put("/activitiesProgress/" + ACTIVITY_ID, false);
            selectRandomQuestions();
            List<String> activeIds = new ArrayList<>();
            for (Question q : activeQuestions) {
                activeIds.add(q.getQuestionId());
            }
            updates.put("/activities/" + ACTIVITY_ID + "/activeQuestions", activeIds);
            Map<String, Object> activityData = new HashMap<>();
            activityData.put("score", 0);
            activityData.put("feedbackVisible", false);
            activityData.put("lastAttempt", System.currentTimeMillis());
            activityData.put("totalQuestions", QUESTIONS_PER_SESSION);
            updates.put("/activities/" + ACTIVITY_ID + "/data", activityData);
            userRef.child(userId)
                    .updateChildren(updates)
                    .addOnSuccessListener(aVoid -> {
                        refreshQuestionAdapter();
                        showToast("Progress reset successfully with new questions");
                    })
                    .addOnFailureListener(e -> showToast("Failed to reset progress"));
        } else {
            // Reset local state if user not logged in
            resetLocalState();
        }
    }

    private void resetLocalState() {
        selectRandomQuestions();
        for (Question q : activeQuestions) {
            q.setUserAnswer(null);
        }
        feedbackVisible = false;
        activityCompleted = false;
        refreshQuestionAdapter();
        updateProgressIndicator();
        updateUIBasedOnCompletion(false);
        submitButton.setEnabled(true);
        showToast("Progress reset successfully with new questions");
    }

    //==========================================================================
    // Dialogs: Completion, Failure, and No-Internet
    //==========================================================================
    private void showCompletionDialog() {
        if (isFinishing() || isDestroyed()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_activity_completed, null);
        Button okButton = dialogView.findViewById(R.id.okButton);
        TextView scoreText = dialogView.findViewById(R.id.scoreText);
        scoreText.setText(String.format("Your score: %d%%", currentScore));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false);
        completionDialog = builder.create();

        okButton.setOnClickListener(v -> {
            completionDialog.dismiss();
            finish();
            overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        });
        if (completionDialog.getWindow() != null) {
            completionDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        completionDialog.show();
    }

    private void showFailureDialog() {
        if (isFinishing() || isDestroyed()) return;
        dismissDialog(failureDialog);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Activity Not Completed")
                .setMessage(String.format("You scored %d%%. You need %d%% to pass. Click Reset Progress to Try again!", currentScore, PASSING_SCORE))
                .setPositiveButton("OK", null);
        failureDialog = builder.create();
        failureDialog.setOnShowListener(dialog -> {
            Button pos = failureDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setTextColor(getResources().getColor(R.color.nav_bar_color_default));
        });
        failureDialog.show();
    }

    private void showNoInternetDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (noInternetDialog != null && noInternetDialog.isShowing()) return;
        View dialogView = getLayoutInflater().inflate(R.layout.customnointernetdialog, null);
        noInternetDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        View okBtn = dialogView.findViewById(R.id.Okbtn);
        okBtn.setOnClickListener(v -> {
            goToLogin();
            dismissDialog(noInternetDialog);
        });
        noInternetDialog.show();
        if (noInternetDialog.getWindow() != null) {
            noInternetDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private void dismissDialog(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void dismissAllDialogs() {
        dismissDialog(completionDialog);
        dismissDialog(noInternetDialog);
        dismissDialog(resetConfirmDialog);
        dismissDialog(failureDialog);
    }

    //==========================================================================
    // Network Monitoring
    //==========================================================================
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
            if (!isInternetAvailable()) {
                runOnUiThread(this::showNoInternetDialog);
            }
        });
    }

    private boolean isInternetAvailable() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://clients3.google.com/generate_204");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("HEAD");
            return (connection.getResponseCode() == 204);
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void goToLogin() {
        dismissAllDialogs();
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(DescriptiveActivity.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }

    //==========================================================================
    // Utility Methods and Lifecycle Overrides
    //==========================================================================
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View current = getCurrentFocus();
            if (current instanceof EditText) {
                Rect outRect = new Rect();
                current.getGlobalVisibleRect(outRect);
                if (!outRect.contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    hideKeyboard();
                    current.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideKeyboard() {
        View current = getCurrentFocus();
        if (current != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
        }
    }

    private void setFullscreen() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    private void showToast(String message) {
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        dismissAllDialogs();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (auth.getCurrentUser() != null) {
            loadUserProgress();
        }
    }

    @Override
    protected void onDestroy() {
        if (auth.getCurrentUser() != null && progressListener != null) {
            userRef.child(auth.getCurrentUser().getUid()).removeEventListener(progressListener);
        }
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        dismissAllDialogs();
        super.onDestroy();
    }
}