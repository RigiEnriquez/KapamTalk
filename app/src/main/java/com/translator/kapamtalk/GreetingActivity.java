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

public class GreetingActivity extends AppCompatActivity {
    // Constants
    private static final String FIREBASE_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String ACTIVITY_ID = "activity1";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int PASSING_SCORE = 70;
    private static final int QUESTIONS_PER_SESSION = 10; // Number of questions to show per session

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
    private List<Question> allQuestions; // All available questions
    private List<Question> activeQuestions; // Currently selected questions
    private int currentScore = 0;
    private boolean activityCompleted = false;
    private boolean feedbackVisible = false;
    private SharedPreferences preferences;

    // Background task executor
    private final Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setFullscreen();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.greeting_activity);

        initializeComponents();
        setupUI();
        setupNetworkMonitoring();
        loadUserProgress();
    }

    private void initializeComponents() {
        auth = FirebaseAuth.getInstance();
        FirebaseDatabase database = FirebaseDatabase.getInstance(FIREBASE_URL);
        userRef = database.getReference("users");
        preferences = getSharedPreferences("ActivityPreferences", MODE_PRIVATE);

        allQuestions = createQuestionsList();
        activeQuestions = new ArrayList<>();
    }

    private void selectRandomQuestions() {
        activeQuestions.clear();
        List<Question> shuffledQuestions = new ArrayList<>(allQuestions);
        Collections.shuffle(shuffledQuestions, new Random(System.currentTimeMillis()));

        // Select the first QUESTIONS_PER_SESSION questions or all if less
        int count = Math.min(QUESTIONS_PER_SESSION, shuffledQuestions.size());
        for (int i = 0; i < count; i++) {
            activeQuestions.add(shuffledQuestions.get(i));
        }
    }

    private void setupUI() {
        initializeViews();
        setupListeners();
        updateProgress();
    }

    private void initializeViews() {
        questionsRecyclerView = findViewById(R.id.questionsRecyclerView);
        progressIndicator = findViewById(R.id.progressIndicator);
        progressText = findViewById(R.id.progressText);
        submitButton = findViewById(R.id.submitButton);
        resetButton = findViewById(R.id.resetButton);
        backButton = findViewById(R.id.backButton);
        completionCheckMark = findViewById(R.id.completionCheckMark);

        questionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        questionAdapter = new QuestionAdapter(activeQuestions, false);
        questionAdapter.setProgressUpdateListener(this::updateProgress);
        questionsRecyclerView.setAdapter(questionAdapter);
    }

    private void setupListeners() {
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

    private List<Question> createQuestionsList() {
        List<Question> questionsList = new ArrayList<>();

        // Original questions
        questionsList.add(new Question(
                "q1",
                "How do you say 'I Love You' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Kaluguran da ka", "Mayap a bengi", "Mayap a yabak", "Malagu"),
                "Kaluguran da ka"
        ));

        questionsList.add(new Question(
                "q2",
                "Complete the greeting: 'Mayap a _____' (Good evening)",
                Question.TYPE_FILL_BLANK,
                null,
                "Bengi"
        ));

        questionsList.add(new Question(
                "q3",
                "How do you say 'Good afternoon' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Mayap a abak", "Mayap a gatpanapun", "Mayap a ugtu", "Siklod pu"),
                "Mayap a gatpanapun"
        ));

        questionsList.add(new Question(
                "q4",
                "What does 'Komusta na ka?' mean?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Good morning", "How are you?", "Thank you", "Good Day"),
                "How are you?"
        ));

        questionsList.add(new Question(
                "q5",
                "Complete the greeting: 'Dakal a _____' (Many thanks)",
                Question.TYPE_FILL_BLANK,
                null,
                "Salamat"
        ));

        questionsList.add(new Question(
                "q6",
                "What Kapampangan term do we use from sunrise to noon?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Mayap a gatpanapun", "Hanggan king salukuyan", "Ampanayan", "Mayap a abak"),
                "Mayap a abak"
        ));

        questionsList.add(new Question(
                "q7",
                "What does 'Malaus kayu/ko pu' mean?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Welcome", "How are you?", "What is your name?", "Where are you going?"),
                "Welcome"
        ));

        questionsList.add(new Question(
                "q8",
                "Complete the greeting: 'Mayap a ____' (Noon time greeting)",
                Question.TYPE_FILL_BLANK,
                null,
                "Ugtu"
        ));

        questionsList.add(new Question(
                "q9",
                "What is the equivalent term of 'Excuse me' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Mayap mu", "Masalese ku", "Manatul ku", "Panapaya mu ku"),
                "Panapaya mu ku"
        ));

        questionsList.add(new Question(
                "q10",
                "What does 'I miss you' translates into Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Panapaya mu ku", "Mayap a abak", "Dakal a Salamat", "Agaganaka da ka/Pagdulapan da ka"),
                "Agaganaka da ka/Pagdulapan da ka"
        ));

        questionsList.add(new Question(
                "q11",
                "It is used when politely asking for permission or apologizing. What is the Kapampangan term for it?",
                Question.TYPE_FILL_BLANK,
                null,
                "Panapaya mu ku"
        ));

        questionsList.add(new Question(
                "q12",
                "It is used in the afternoon, typically from 2PM until sunset.",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Panapaya mu ku", "Agaganaka da ka/Pagdulapan da ka", "Mayap a gatpanapun","Mayap mu"),
                "Mayap a gatpanapun"
        ));

        questionsList.add(new Question(
                "q13",
                "Used to express gratitude, similar to 'thank you very much'",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Mayap a gatpanapun", "Dakal a Salamat","Mayap", "Mayap a abak"),
                "Dakal a Salamat"
        ));

        questionsList.add(new Question(
                "q14",
                "Complete the phrase: '_____ kayu/ko pu' (Welcome)",
                Question.TYPE_FILL_BLANK,
                null,
                "Malaus"
        ));

        questionsList.add(new Question(
                "q15",
                "How do you welcome guest or visitors in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Malaus kayu/ko pu", "Ugtu", "Gatpanapun", "Dispensa"),
                "Malaus kayu/ko pu"
        ));

        questionsList.add(new Question(
                "q16",
                "It is used to express love or deep affection. What Kapampangan term is it?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Dakal a Salamat", "Malaus kayu/ko pu", "Kaluguran da ka", "Mayap a abak"),
                "Kaluguran da ka"
        ));

        questionsList.add(new Question(
                "q17",
                "It is used as a general greeting when meeting someone. What Kapampangan term is it?",
                Question.TYPE_FILL_BLANK,
                null,
                "Komusta na ka?"
        ));

        questionsList.add(new Question(
                "q18",
                "What Kapampangan term do we use to express that we miss someone?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Komusta na ka?", "Kaluguran da ka", "Agaganaka da ka/Pagdulapan da ka", "Panapaya mu ku"),
                "Agaganaka da ka/Pagdulapan da ka"
        ));

        questionsList.add(new Question(
                "q19",
                "It is used during midday, typically between 11 AM to 1 PM",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Mayap a abak", "Mayap a ugtu", "Komusta na ka?", "Kaluguran da ka"),
                "Mayap a ugtu"
        ));

        questionsList.add(new Question(
                "q20",
                "Complete the phrase: '_____ a gatpanapun' (Good afternoon)",
                Question.TYPE_FILL_BLANK,
                null,
                "Mayap"
        ));

        return questionsList;
    }

    private void setupRealtimeSync() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        String userId = currentUser.getUid();
        progressListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Get activity completion status
                boolean isActivityComplete = snapshot.child("activitiesProgress")
                        .child(ACTIVITY_ID)
                        .exists() &&
                        snapshot.child("activitiesProgress")
                                .child(ACTIVITY_ID)
                                .getValue(Boolean.class);

                // Store old values for comparison
                boolean oldActivityCompleted = activityCompleted;
                boolean oldFeedbackVisible = feedbackVisible;

                // Update activity completion status
                activityCompleted = isActivityComplete;

                // Get feedback visibility state from Firebase
                boolean newFeedbackVisible = false;
                if (snapshot.child("activities").child(ACTIVITY_ID).child("data").child("feedbackVisible").exists()) {
                    newFeedbackVisible = snapshot.child("activities")
                            .child(ACTIVITY_ID)
                            .child("data")
                            .child("feedbackVisible")
                            .getValue(Boolean.class);
                }

                // Check if feedback visibility has changed
                boolean feedbackChanged = feedbackVisible != newFeedbackVisible;
                feedbackVisible = newFeedbackVisible;

                // Check if activeQuestions have been updated from another device
                boolean questionsChanged = false;

                if (snapshot.child("activities").child(ACTIVITY_ID).child("activeQuestions").exists()) {
                    // Get the remote question IDs from Firebase
                    List<String> remoteQuestionIds = new ArrayList<>();
                    for (DataSnapshot questionSnapshot : snapshot.child("activities").child(ACTIVITY_ID).child("activeQuestions").getChildren()) {
                        String questionId = questionSnapshot.getValue(String.class);
                        if (questionId != null) {
                            remoteQuestionIds.add(questionId);
                        }
                    }

                    // Create list of current local question IDs
                    List<String> localQuestionIds = new ArrayList<>();
                    for (Question question : activeQuestions) {
                        localQuestionIds.add(question.getQuestionId());
                    }

                    // Check if questions have changed
                    if (remoteQuestionIds.size() != localQuestionIds.size() || !remoteQuestionIds.containsAll(localQuestionIds)) {
                        questionsChanged = true;

                        // Update local questions to match remote
                        List<Question> newActiveQuestions = new ArrayList<>();
                        for (String questionId : remoteQuestionIds) {
                            for (Question question : allQuestions) {
                                if (question.getQuestionId().equals(questionId)) {
                                    newActiveQuestions.add(question);
                                    break;
                                }
                            }
                        }

                        // If we couldn't find all questions, fill with random ones
                        if (newActiveQuestions.size() < QUESTIONS_PER_SESSION) {
                            List<Question> availableQuestions = new ArrayList<>(allQuestions);
                            availableQuestions.removeAll(newActiveQuestions);
                            Collections.shuffle(availableQuestions);

                            while (newActiveQuestions.size() < QUESTIONS_PER_SESSION && !availableQuestions.isEmpty()) {
                                newActiveQuestions.add(availableQuestions.remove(0));
                            }
                        }

                        // Update active questions
                        activeQuestions.clear();
                        activeQuestions.addAll(newActiveQuestions);
                    }
                }

                // Check if question progress exists
                boolean questionsExist = snapshot.child("questionProgress").child(ACTIVITY_ID).exists();

                // If question progress doesn't exist, clear all answers
                if (!questionsExist) {
                    clearAllUserAnswers();
                } else {
                    // Update questions from snapshot
                    updateQuestionsFromSnapshot(snapshot);
                }

                // If any state changes occurred, update the UI
                if (oldActivityCompleted != activityCompleted ||
                        oldFeedbackVisible != feedbackVisible ||
                        feedbackChanged ||
                        questionsChanged) {  // Added questionsChanged check
                    refreshQuestionAdapter();

                    // Optional: Show a toast if questions were refreshed from another device
                    if (questionsChanged) {
                        showToast("Questions have been updated");
                    }
                }

                // Update UI based on completion status
                updateUIBasedOnCompletionStatus(isActivityComplete);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showToast("Failed to sync with cloud");
            }
        };

        userRef.child(userId).addValueEventListener(progressListener);
    }

    private void clearAllUserAnswers() {
        for (Question question : activeQuestions) {
            question.setUserAnswer(null);
        }
        refreshQuestionAdapter();
        updateProgress();
    }

    private void refreshQuestionAdapter() {
        questionAdapter = new QuestionAdapter(activeQuestions, feedbackVisible);
        questionAdapter.setProgressUpdateListener(this::updateProgress);
        questionsRecyclerView.setAdapter(questionAdapter);
        updateProgress();

        // Add this line to update button state based on current completion and feedback status
        updateUIBasedOnCompletionStatus(activityCompleted);
    }

    private void updateQuestionsFromSnapshot(DataSnapshot snapshot) {
        // First, clear all user answers to handle removed answers
        for (Question question : activeQuestions) {
            question.setUserAnswer(null);
        }

        // Then, only set answers that still exist in the snapshot
        if (snapshot.child("questionProgress").child(ACTIVITY_ID).exists()) {
            for (DataSnapshot questionSnapshot : snapshot.child("questionProgress").child(ACTIVITY_ID).getChildren()) {
                String questionId = questionSnapshot.getKey();
                String userAnswer = questionSnapshot.getValue(String.class);

                // Only update if there's a valid answer
                if (userAnswer != null && !userAnswer.isEmpty()) {
                    // Update the corresponding question
                    for (Question question : activeQuestions) {
                        if (question.getQuestionId().equals(questionId)) {
                            question.setUserAnswer(userAnswer);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void updateUIBasedOnCompletionStatus(boolean isComplete) {
        submitButton.setText(isComplete ? "Review Answers" : "Submit Answers");

        if (isComplete) {
            // Always enable the button in "Review Answers" mode
            submitButton.setEnabled(true);
        } else {
            // In "Submit Answers" mode, disable if feedback is already visible
            submitButton.setEnabled(!feedbackVisible);
        }

        completionCheckMark.setVisibility(isComplete ? View.VISIBLE : View.GONE);
    }

    private void loadUserProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();

            // First check if this user already has active questions
            userRef.child(userId)
                    .child("activities")
                    .child(ACTIVITY_ID)
                    .child("activeQuestions")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            if (snapshot.exists()) {
                                // User already has active questions, load them
                                activeQuestions.clear();
                                List<String> activeQuestionIds = new ArrayList<>();

                                for (DataSnapshot questionIdSnapshot : snapshot.getChildren()) {
                                    String questionId = questionIdSnapshot.getValue(String.class);
                                    if (questionId != null) {
                                        activeQuestionIds.add(questionId);
                                    }
                                }

                                // Find the corresponding questions from allQuestions
                                for (String questionId : activeQuestionIds) {
                                    for (Question question : allQuestions) {
                                        if (question.getQuestionId().equals(questionId)) {
                                            activeQuestions.add(question);
                                            break;
                                        }
                                    }
                                }

                                // If we couldn't find all questions (e.g. if question list was updated)
                                // fill in with random questions to reach QUESTIONS_PER_SESSION
                                if (activeQuestions.size() < QUESTIONS_PER_SESSION) {
                                    List<Question> availableQuestions = new ArrayList<>(allQuestions);
                                    availableQuestions.removeAll(activeQuestions);
                                    Collections.shuffle(availableQuestions);

                                    while (activeQuestions.size() < QUESTIONS_PER_SESSION && !availableQuestions.isEmpty()) {
                                        activeQuestions.add(availableQuestions.remove(0));
                                    }

                                    // Save the updated list
                                    saveActiveQuestionIds();
                                }
                            } else {
                                // User doesn't have active questions yet, create new selection
                                selectRandomQuestions();
                                saveActiveQuestionIds();
                            }

                            // Update UI after loading questions
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

            // Track that the user has started this activity
            userRef.child(userId)
                    .child("activities")
                    .child(ACTIVITY_ID)
                    .child("started")
                    .setValue(true)
                    .addOnFailureListener(e -> showToast("Failed to track activity start"));
        } else {
            showToast("Please log in to save your progress");
        }
    }

    // Helper method to save current active question IDs to Firebase
    private void saveActiveQuestionIds() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        String userId = currentUser.getUid();
        List<String> activeQuestionIds = new ArrayList<>();

        for (Question question : activeQuestions) {
            activeQuestionIds.add(question.getQuestionId());
        }

        userRef.child(userId)
                .child("activities")
                .child(ACTIVITY_ID)
                .child("activeQuestions")
                .setValue(activeQuestionIds)
                .addOnFailureListener(e -> showToast("Failed to save question selection"));
    }

    private void checkAnswers() {
        int answeredQuestions = 0;
        int correctAnswers = 0;
        int totalQuestions = activeQuestions.size();

        for (Question question : activeQuestions) {
            if (question.isAnswered()) {
                answeredQuestions++;
                if (question.isCorrect()) {
                    correctAnswers++;
                }
            }
        }

        // Check if all questions are answered or if activity is completed (review mode)
        if (answeredQuestions < totalQuestions && !activityCompleted) {
            showToast("Please answer all questions before submitting");
            return;
        }

        currentScore = (correctAnswers * 100) / totalQuestions;

        // Show feedback with background colors
        questionAdapter.showFeedback();
        feedbackVisible = true;

        // The button state will be set by updateUIBasedOnCompletionStatus
        // which is called after completion status is updated

        saveProgress();

        // Show appropriate dialog based on completion status
        if (activityCompleted) {
            showReviewSummary(correctAnswers, totalQuestions);
        } else {
            checkAndUpdateProgress(currentScore);
        }
    }

    private void showReviewSummary(int correctAnswers, int totalQuestions) {
        int score = (correctAnswers * 100) / totalQuestions;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Review Summary")
                .setMessage(String.format("You got %d out of %d questions correct. Your score is %d%%.",
                        correctAnswers, totalQuestions, score))
                .setPositiveButton("OK", null);

        AlertDialog reviewDialog = builder.create();
        reviewDialog.setOnShowListener(dialog -> {
            Button positiveButton = reviewDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
            positiveButton.setTextColor(buttonColor);
        });

        // Clean up any existing dialog
        dismissDialog(failureDialog);
        failureDialog = reviewDialog;

        reviewDialog.show();
    }

    private void saveProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        String userId = currentUser.getUid();
        Map<String, Object> updates = new HashMap<>();

        // Save individual question answers
        for (Question question : activeQuestions) {
            if (question.isAnswered()) {
                updates.put("/questionProgress/" + ACTIVITY_ID + "/" + question.getQuestionId(),
                        question.getUserAnswer());
            }
        }

        // Save active question IDs for persistence
        List<String> activeQuestionIds = new ArrayList<>();
        for (Question question : activeQuestions) {
            activeQuestionIds.add(question.getQuestionId());
        }
        updates.put("/activities/" + ACTIVITY_ID + "/activeQuestions", activeQuestionIds);

        // Save activity metadata
        Map<String, Object> activityData = new HashMap<>();
        activityData.put("totalQuestions", activeQuestions.size());
        activityData.put("score", currentScore);
        activityData.put("lastAttempt", System.currentTimeMillis());
        activityData.put("feedbackVisible", feedbackVisible);
        updates.put("/activities/" + ACTIVITY_ID + "/data", activityData);

        userRef.child(userId)
                .updateChildren(updates)
                .addOnFailureListener(e -> showToast("Failed to save progress"));
    }

    private void resetProgress() {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();

            // Create a comprehensive update map that explicitly removes all data
            Map<String, Object> updates = new HashMap<>();

            // Clear question progress
            updates.put("/questionProgress/" + ACTIVITY_ID, null);

            // Set activity completion to false
            updates.put("/activitiesProgress/" + ACTIVITY_ID, false);

            // Select new random questions
            selectRandomQuestions();

            // Save new active question IDs
            List<String> activeQuestionIds = new ArrayList<>();
            for (Question question : activeQuestions) {
                activeQuestionIds.add(question.getQuestionId());
            }
            updates.put("/activities/" + ACTIVITY_ID + "/activeQuestions", activeQuestionIds);

            // Reset activity data
            Map<String, Object> activityData = new HashMap<>();
            activityData.put("score", 0);
            activityData.put("feedbackVisible", false);
            activityData.put("lastAttempt", System.currentTimeMillis());
            activityData.put("totalQuestions", QUESTIONS_PER_SESSION);
            updates.put("/activities/" + ACTIVITY_ID + "/data", activityData);

            // Apply all updates atomically
            userRef.child(userId)
                    .updateChildren(updates)
                    .addOnSuccessListener(aVoid -> {
                        // Refresh the questions adapter with new questions
                        refreshQuestionAdapter();
                        showToast("Progress reset successfully with new questions");
                    })
                    .addOnFailureListener(e -> showToast("Failed to reset progress"));
        } else {
            // Local reset only if not logged in
            resetLocalState();
        }
    }

    private void resetLocalState() {
        // Select new random questions
        selectRandomQuestions();

        // Reset local question data
        for (Question question : activeQuestions) {
            question.setUserAnswer(null);
        }

        // Update local state
        feedbackVisible = false;
        activityCompleted = false;

        // Create new adapter with feedback off
        refreshQuestionAdapter();

        // Reset progress indicator
        updateProgress();

        // Update UI based on completion
        updateUIBasedOnCompletionStatus(false);

        // Make sure submit button is enabled after reset
        submitButton.setEnabled(true);

        showToast("Progress reset successfully with new questions");
    }

    private void showResetConfirmation() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Reset Progress")
                .setMessage("Are you sure you want to reset your progress? This will clear all your answers and generate a new set of questions.")
                .setPositiveButton("Yes", (dialog, which) -> resetProgress())
                .setNegativeButton("No", null);

        resetConfirmDialog = builder.create();
        resetConfirmDialog.setOnShowListener(dialog -> {
            Button positiveButton = resetConfirmDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negativeButton = resetConfirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE);

            int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
            positiveButton.setTextColor(buttonColor);
            negativeButton.setTextColor(buttonColor);
        });

        resetConfirmDialog.show();
    }

    private void updateProgress() {
        int answeredQuestions = 0;

        // Explicitly check if userAnswer is not null and not empty
        for (Question question : activeQuestions) {
            if (question.getUserAnswer() != null && !question.getUserAnswer().isEmpty()) {
                answeredQuestions++;
            }
        }

        int progress = activeQuestions.size() > 0 ? (answeredQuestions * 100) / activeQuestions.size() : 0;
        progressIndicator.setProgress(progress);
        progressText.setText(answeredQuestions + "/" + activeQuestions.size() + " Questions Completed");
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
                        updateUIBasedOnCompletionStatus(true);
                        showCompletionDialog();
                    })
                    .addOnFailureListener(e -> showToast("Failed to update progress"));
        } else {
            // Only show failure dialog if not already completed
            if (!activityCompleted) {
                showFailureDialog();
            }
        }
    }

    private void showCompletionDialog() {
        if (isFinishing() || isDestroyed()) return;

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_activity_completed, null);
        Button okButton = dialogView.findViewById(R.id.okButton);
        TextView scoreText = dialogView.findViewById(R.id.scoreText);

        scoreText.setText(String.format("Your score: %d%%", currentScore));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView)
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

        // Dismiss any existing dialog first
        dismissDialog(failureDialog);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Activity Not Completed")
                .setMessage(String.format("You scored %d%%. You need %d%% to pass. Click Reset Progress to Try again!",
                        currentScore, PASSING_SCORE))
                .setPositiveButton("OK", null);

        failureDialog = builder.create();
        failureDialog.setOnShowListener(dialog -> {
            Button positiveButton = failureDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
            positiveButton.setTextColor(buttonColor);
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
                runOnUiThread(this::showNoInternetDialog);
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

    private void goToLogin() {
        dismissAllDialogs();
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(GreetingActivity.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }

    private void dismissAllDialogs() {
        dismissDialog(completionDialog);
        dismissDialog(noInternetDialog);
        dismissDialog(resetConfirmDialog);
        dismissDialog(failureDialog);
    }

    private void dismissDialog(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
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
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setStatusBarColor(Color.TRANSPARENT);
    }
}