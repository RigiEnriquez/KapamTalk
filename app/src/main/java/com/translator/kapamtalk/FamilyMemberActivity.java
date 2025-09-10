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

public class FamilyMemberActivity extends AppCompatActivity {

    // Constants
    private static final String FIREBASE_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String ACTIVITY_ID = "activity6"; // Activity 6 for family members
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
        setContentView(R.layout.family_members_activity);

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

        // Original questions for each term (1-13)
        questionsList.add(new Question("fm1", "What is the Kapampangan word for 'Father'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Ima", "Koya", "Tatang/Ibpa", "Bapa"), "Tatang/Ibpa"));

        questionsList.add(new Question("fm2", "Which family member is called 'Ima/Indu' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Father", "Mother", "Grandmother", "Aunt"), "Mother"));

        questionsList.add(new Question("fm3", "The Kapampangan term 'Ingkung' refers to which family member?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Grandfather", "Uncle", "Father-in-law", "Godfather"), "Grandfather"));

        questionsList.add(new Question("fm4", "What is the correct Kapampangan term for 'Sibling'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Kamag-anak", "Kapatad", "Bapa", "Anák"), "Kapatad"));

        questionsList.add(new Question("fm5", "Which of these is the Kapampangan word for an older brother?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Koya", "Wali", "Kapatad", "Pisan"), "Koya"));

        questionsList.add(new Question("fm6", "'Atsi' refers to which family member?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Niece", "Younger sister", "Older sister", "Aunt"), "Older sister"));

        questionsList.add(new Question("fm7", "What is the Kapampangan term for a younger sibling?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Koya", "Wali", "Pisan", "Atsi"), "Wali"));

        questionsList.add(new Question("fm8", "Which family member is called 'Bapa' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Brother-in-law", "Uncle", "Godfather", "Father-in-law"), "Uncle"));

        questionsList.add(new Question("fm9", "The Kapampangan term 'Apu' refers to which family member?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Aunt", "Mother-in-law", "Grandmother", "Godmother"), "Grandmother"));

        questionsList.add(new Question("fm10", "Which term refers to either a mother or father in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Kapatad", "Pengari", "Pisan", "Dara"), "Pengari"));

        questionsList.add(new Question("fm11", "In Kapampangan family terms, which word means 'Cousin'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Kapatad", "Pisan", "Wali", "Bapa"), "Pisan"));

        questionsList.add(new Question("fm12", "Which Kapampangan term specifically refers to a 'First Cousin'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Pisan", "Pisan alang pilatan", "Kapatad", "Pengari"), "Pisan alang pilatan"));

        questionsList.add(new Question("fm13", "The Kapampangan word for your parent's sister is:",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Apu", "Atsi", "Dara", "Ima"), "Dara"));

        questionsList.add(new Question("fm14", "Which pair of Kapampangan terms represents 'Mother' and 'Father'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Koya and Atsi", "Ima and Tatang", "Apu and Ingkung", "Dara and Bapa"), "Ima and Tatang"));

        questionsList.add(new Question("fm15", "Which Kapampangan terms would you use to address your grandparents?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Tatang and Ima", "Apu and Ingkung", "Dara and Bapa", "Koya and Atsi"), "Apu and Ingkung"));

        questionsList.add(new Question("fm16", "If you want to refer to all your brothers and sisters in Kapampangan, which term would you use?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Pisan", "Kapatad", "Pengari", "Wali"), "Kapatad"));

        questionsList.add(new Question("fm17", "In a Kapampangan family, who would call you 'Wali'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Your parents", "Your younger siblings", "Your older siblings", "Your cousins"), "Your older siblings"));

        questionsList.add(new Question("fm18", "Which of these best describes the relationship between 'Koya' and 'Atsi' in Kapampangan?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("They are both older siblings", "They are cousins", "They are husband and wife", "They are parent and child"), "They are both older siblings"));

        questionsList.add(new Question("fm19", "In Kapampangan, what is the relationship between 'Pisan' and 'Pisan alang pilatan'?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("They are different types of siblings", "They are different types of cousins", "They are different types of parents", "They are different types of grandparents"), "They are different types of cousins"));

        questionsList.add(new Question("fm20", "In a traditional Kapampangan family, which set of terms represents the oldest generation?",
                Question.TYPE_MULTIPLE_CHOICE,
                Arrays.asList("Tatang and Ima", "Koya and Atsi", "Apu and Ingkung", "Dara and Bapa"), "Apu and Ingkung"));

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
        Intent intent = new Intent(FamilyMemberActivity.this, Login.class);
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