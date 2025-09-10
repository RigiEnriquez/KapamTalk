package com.translator.kapamtalk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class PhraseLesson extends AppCompatActivity {
    private static final String TAG = "PhraseLesson";
    private static final String FIREBASE_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String COMPLETED_PHRASES_PREF = "completed_phrases";
    private static final String HAS_SHOWN_CONGRATULATIONS_PREF = "has_shown_phrases_congratulations";
    private static final String LAST_ITEM_COUNT_PREF = "last_phrase_item_count";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private FirebaseAuth auth;
    private DatabaseReference reference;
    private DatabaseReference phrasesReference;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private AlertDialog noInternetDialog;
    private AlertDialog activePhraseDialog;
    private AlertDialog resetProgressDialog;
    private List<PhraseItem> phraseItems;
    private Set<String> completedPhrases;
    private SharedPreferences preferences;
    private ValueEventListener progressListener;
    private ValueEventListener phrasesListener;
    private boolean hasShownCongratulations = false;
    private MediaPlayer mediaPlayer;
    private ProgressBar loadingProgressBar;
    private boolean phrasesLoaded = false;

    // Added to prevent duplicate processing
    private long lastFetchTimestamp = 0;
    private long lastProgressUpdateTimestamp = 0;
    private AtomicBoolean isUpdatingProgress = new AtomicBoolean(false);

    // Queue for pending completion updates
    private final List<String> pendingCompletionUpdates = Collections.synchronizedList(new ArrayList<>());
    private boolean isProcessingCompletionQueue = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setFullscreen();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.phrase);

        // Load congratulations state from preferences instead of resetting it
        initializeComponents();
        setupNetworkMonitoring();
    }

    private void initializeComponents() {
        auth = FirebaseAuth.getInstance();
        reference = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("users");
        phrasesReference = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("phrases");
        preferences = getSharedPreferences("LessonPreferences", MODE_PRIVATE);
        completedPhrases = new HashSet<>(preferences.getStringSet(COMPLETED_PHRASES_PREF, new HashSet<>()));
        hasShownCongratulations = preferences.getBoolean(HAS_SHOWN_CONGRATULATIONS_PREF, false);
        phraseItems = new ArrayList<>();

        // Setup loading indicator
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        if (loadingProgressBar != null) {
            loadingProgressBar.setVisibility(View.VISIBLE);
        } else {
            Log.e(TAG, "loadingProgressBar is null! Make sure it's in your layout.");
        }

        // Set up back button immediately regardless of data loading status
        setupBackButton();

        setupRealtimeSync();
        fetchPhraseItemsFromFirebase();
    }

    private void fetchPhraseItemsFromFirebase() {
        // Prevent duplicate fetches in short timespan
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTimestamp < 2000) {
            Log.d(TAG, "Ignoring rapid re-fetch request");
            return;
        }
        lastFetchTimestamp = currentTime;

        if (phrasesListener != null) {
            phrasesReference.removeEventListener(phrasesListener);
        }

        // Get the last known item count
        final int lastItemCount = preferences.getInt(LAST_ITEM_COUNT_PREF, 0);

        phrasesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int currentItemCount = (int)snapshot.getChildrenCount();
                Log.d(TAG, "Fetched phrases data: " + currentItemCount + " items (previously: " + lastItemCount + ")");

                phraseItems.clear();

                for (DataSnapshot phraseSnapshot : snapshot.getChildren()) {
                    try {
                        // Extract values from Firebase
                        String kapampangan = phraseSnapshot.child("kapampangan").getValue(String.class);
                        String english = phraseSnapshot.child("english").getValue(String.class);
                        String pronunciation = phraseSnapshot.child("pronunciation").getValue(String.class);
                        String usage = phraseSnapshot.child("usage").getValue(String.class);
                        String audioUrl = phraseSnapshot.child("audioUrl").getValue(String.class);
                        String referencelocator = phraseSnapshot.child("referencelocator").getValue(String.class);
                        Integer sortOrder = phraseSnapshot.child("sortOrder").getValue(Integer.class);

                        if (kapampangan == null || english == null) {
                            Log.w(TAG, "Skipping invalid phrase item - missing required fields");
                            continue; // Skip invalid entries
                        }

                        // Create PhraseItem with audioUrl
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

                        phraseItems.add(item);
                    } catch (Exception e) {
                        // Log error but continue processing other items
                        Log.e(TAG, "Error processing phrase item", e);
                    }
                }

                if (phraseItems.isEmpty()) {
                    // No phrases were loaded, show error
                    Log.w(TAG, "No phrases found in Firebase");
                    showErrorView("No phrases available. Please check your connection and try again.");
                } else {
                    // Sort by sortOrder if available
                    Collections.sort(phraseItems, (a, b) -> a.getSortOrder() - b.getSortOrder());

                    phrasesLoaded = true;

                    // Log all current items for debugging
                    Log.d(TAG, "ALL CURRENT ITEMS IN FIREBASE:");
                    for (PhraseItem item : phraseItems) {
                        Log.d(TAG, "Item: " + item.getKapampangan());
                    }

                    // Compare current count with previous count
                    boolean hasNewItems = currentItemCount > lastItemCount;

                    // Save the current count for next time
                    preferences.edit()
                            .putInt(LAST_ITEM_COUNT_PREF, currentItemCount)
                            .apply();

                    // If there are new items, we need to reset the completion check
                    if (hasNewItems) {
                        Log.d(TAG, "New items detected: Item count increased from " + lastItemCount + " to " + currentItemCount);

                        // Force UI refresh to properly show completion status for all items
                        runOnUiThread(() -> {
                            updateAllPhraseVisuals();
                        });

                        hasShownCongratulations = false;
                        preferences.edit()
                                .putBoolean(HAS_SHOWN_CONGRATULATIONS_PREF, false)
                                .apply();

                        // Force update of the lesson completion status in Firebase
                        if (auth.getCurrentUser() != null) {
                            String userId = auth.getCurrentUser().getUid();
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("isComplete", false);
                            updates.put("lastUpdated", System.currentTimeMillis());

                            reference.child(userId)
                                    .child("lessons")
                                    .child("phrases")
                                    .updateChildren(updates)
                                    .addOnSuccessListener(aVoid -> {
                                        Log.d(TAG, "Successfully reset lesson completion status due to new items");
                                        // Also reset the lesson3 progress flag
                                        reference.child(userId)
                                                .child("lessonsProgress")
                                                .child("lesson3")
                                                .setValue(false);
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "Failed to reset lesson completion status", e);
                                    });
                        }
                    }

                    setupUI();
                    restoreCompletedPhrasesFromFirebase();
                }

                // Hide loading indicator
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Show error - no fallback to local data
                Log.e(TAG, "Failed to load phrases from Firebase: " + error.getMessage());
                showErrorView("Error loading phrases: " + error.getMessage() +
                        "\nPlease check your connection and try again.");

                // Hide loading indicator
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }
        };

        phrasesReference.addValueEventListener(phrasesListener);
    }

    private void showErrorView(String message) {
        runOnUiThread(() -> {
            LinearLayout phrasesContainer = findViewById(R.id.phrasesContainer);
            if (phrasesContainer == null) return;

            // Clear any existing content
            phrasesContainer.removeAllViews();

            // Create and add error TextView
            TextView errorText = new TextView(this);
            errorText.setText(message);
            errorText.setTextColor(Color.RED);
            errorText.setTextSize(16);
            errorText.setPadding(32, 32, 32, 32);
            errorText.setGravity(android.view.Gravity.CENTER);

            // Create retry button
            Button retryButton = new Button(this);
            retryButton.setText("Retry");
            retryButton.setBackgroundResource(R.drawable.phrase_item);
            retryButton.setTextColor(getResources().getColor(R.color.nav_bar_color_default));

            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            buttonParams.setMargins(0, 16, 0, 0);
            buttonParams.gravity = android.view.Gravity.CENTER;
            retryButton.setLayoutParams(buttonParams);

            retryButton.setOnClickListener(v -> {
                // Show loading indicator again
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.VISIBLE);
                }

                // Clear the error view
                phrasesContainer.removeAllViews();

                // Refetch data
                fetchPhraseItemsFromFirebase();
            });

            // Add views to container
            phrasesContainer.addView(errorText);
            phrasesContainer.addView(retryButton);

            // Show internet status if we have that capability
            if (connectivityManager != null) {
                boolean isConnected = isInternetAvailable();
                TextView statusText = new TextView(this);
                statusText.setText("Internet connection: " + (isConnected ? "Available" : "Not available"));
                statusText.setTextColor(isConnected ? Color.GREEN : Color.RED);
                statusText.setTextSize(14);
                statusText.setPadding(32, 16, 32, 32);
                statusText.setGravity(android.view.Gravity.CENTER);
                phrasesContainer.addView(statusText);
            }
        });
    }

    private boolean isInternetAvailable() {
        if (connectivityManager == null) return false;

        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) return false;

        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void setupRealtimeSync() {
        if (auth.getCurrentUser() == null) return;

        String userId = auth.getCurrentUser().getUid();

        if (progressListener != null) {
            reference.child(userId).removeEventListener(progressListener);
        }

        progressListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!phrasesLoaded || phraseItems.isEmpty()) {
                    Log.d(TAG, "Skipping progress update as phrases not fully loaded");
                    return;
                }

                DataSnapshot phrasesSnapshot = snapshot.child("lessons").child("phrases");
                if (!phrasesSnapshot.exists()) return;

                DataSnapshot completedItemsSnapshot = phrasesSnapshot.child("completedItems");
                Set<String> previousCompleted = new HashSet<>(completedPhrases);
                completedPhrases.clear();

                if (completedItemsSnapshot.exists() && completedItemsSnapshot.getValue() != null) {
                    for (DataSnapshot item : completedItemsSnapshot.getChildren()) {
                        String kapampanganPhrase = item.getValue(String.class);
                        if (kapampanganPhrase != null) {
                            completedPhrases.add(kapampanganPhrase);
                        }
                    }
                }

                preferences.edit()
                        .putStringSet(COMPLETED_PHRASES_PREF, completedPhrases)
                        .apply();

                resetAllPhraseVisuals();
                updateAllPhraseVisuals();

                // Check if all CURRENT items are completed
                Set<String> currentItemsSet = new HashSet<>();
                for (PhraseItem item : phraseItems) {
                    currentItemsSet.add(item.getKapampangan());
                }

                // Only count items that exist in the current lesson
                int actualCompletedCount = 0;
                for (String completedItem : completedPhrases) {
                    if (currentItemsSet.contains(completedItem)) {
                        actualCompletedCount++;
                    }
                }

                // Log completion status
                Log.d(TAG, "Completion check: " + actualCompletedCount + "/" + currentItemsSet.size() +
                        " - hasShownCongratulations: " + hasShownCongratulations);

                // Only show congratulations if ALL current items are completed
                if (actualCompletedCount == currentItemsSet.size() &&
                        currentItemsSet.size() > 0 &&
                        !hasShownCongratulations) {
                    showToast("Congratulations! You've completed the Basic Phrases lesson!");
                    hasShownCongratulations = true;
                    preferences.edit()
                            .putBoolean(HAS_SHOWN_CONGRATULATIONS_PREF, true)
                            .apply();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showToast("Failed to sync with cloud");
            }
        };

        reference.child(userId).addValueEventListener(progressListener);
    }

    private void setupUI() {
        if (!phrasesLoaded) {
            Log.w(TAG, "Attempted to setup UI before phrases loaded");
            return; // Don't set up UI until phrases are loaded
        }

        setupResetButton();
        createPhraseItems();
        trackLessonProgress();
    }

    private void createPhraseItems() {
        LinearLayout phrasesContainer = findViewById(R.id.phrasesContainer);
        if (phrasesContainer == null) {
            Log.e(TAG, "phrasesContainer is null! Check your layout.");
            return;
        }

        // Clear any existing items from the container
        phrasesContainer.removeAllViews();

        // Log current completed items for debugging
        Log.d(TAG, "Creating phrase items. Currently completed items: " + completedPhrases.size());

        // For each phrase item, create a view and add it to the container
        for (PhraseItem item : phraseItems) {
            View phraseView = getLayoutInflater().inflate(R.layout.item_phrase, phrasesContainer, false);
            if (phraseView == null) {
                Log.e(TAG, "Failed to inflate phrase view");
                continue;
            }

            // Find the views within the phrase item layout
            TextView kapampanganText = phraseView.findViewById(R.id.phraseText);
            TextView englishText = phraseView.findViewById(R.id.englishText);
            ImageView checkmark = phraseView.findViewById(R.id.completionCheckmark);

            if (kapampanganText == null || englishText == null || checkmark == null) {
                Log.e(TAG, "Missing required views in phrase item layout");
                continue;
            }

            // Set the data in the views
            kapampanganText.setText(item.getKapampangan());
            englishText.setText(item.getEnglish());

            // Check completed status for this specific item
            boolean isCompleted = completedPhrases.contains(item.getKapampangan());
            Log.d(TAG, "Item: " + item.getKapampangan() + " completion status: " + isCompleted);

            // Set initial checkmark visibility based on completion status
            checkmark.setVisibility(isCompleted ? View.VISIBLE : View.GONE);

            // Set the background based on completion status
            phraseView.setBackgroundResource(
                    isCompleted ? R.drawable.completed_item_background : R.drawable.phrase_item
            );

            // Store the phrase item in the view's tag for later reference
            phraseView.setTag(item);

            // Set click listener
            phraseView.setOnClickListener(v -> {
                if (v == null || v.getTag() == null) return;
                PhraseItem clickedItem = (PhraseItem) v.getTag();
                showPhraseDialog(clickedItem);
            });

            // Add the view to the container
            phrasesContainer.addView(phraseView);
        }
    }

    private void setupBackButton() {
        ImageButton backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                finish();
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            });
        } else {
            Log.e(TAG, "Back button not found in layout!");
        }
    }

    private void setupResetButton() {
        Button resetButton = findViewById(R.id.resetButton);
        if (resetButton != null) {
            resetButton.setOnClickListener(v -> showResetDialog());
        }
    }

    private void trackLessonProgress() {
        if (auth.getCurrentUser() == null) return;

        String userId = auth.getCurrentUser().getUid();
        reference.child(userId)
                .child("lessons")
                .child("phrases")
                .child("started")
                .setValue(true)
                .addOnFailureListener(e -> showToast("Failed to track progress"));
    }

    private void updateLessonProgress() {
        if (auth.getCurrentUser() == null || phraseItems.isEmpty()) return;

        // Prevent concurrent updates
        if (!isUpdatingProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Progress update already in progress, skipping");
            return;
        }

        // Throttle updates to prevent rapid consecutive calls
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastProgressUpdateTimestamp < 1000) {
            Log.d(TAG, "Throttling rapid progress updates");
            isUpdatingProgress.set(false);

            // Schedule a delayed update to ensure data eventually syncs
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                lastProgressUpdateTimestamp = 0; // Reset timestamp to force update
                updateLessonProgress();
            }, 1500);
            return;
        }
        lastProgressUpdateTimestamp = currentTime;

        try {
            String userId = auth.getCurrentUser().getUid();

            // Only count completed items that exist in current phrases
            Set<String> currentItemsSet = new HashSet<>();
            for (PhraseItem item : phraseItems) {
                currentItemsSet.add(item.getKapampangan());
            }

            Set<String> validCompletedItems = new HashSet<>();
            for (String completed : completedPhrases) {
                if (currentItemsSet.contains(completed)) {
                    validCompletedItems.add(completed);
                }
            }

            float progressPercentage = phraseItems.isEmpty() ? 0 :
                    (float) validCompletedItems.size() / phraseItems.size() * 100;
            List<String> completedItemsList = new ArrayList<>(completedPhrases);

            Map<String, Object> phrasesProgress = new HashMap<>();
            phrasesProgress.put("completed", validCompletedItems.size());
            phrasesProgress.put("total", phraseItems.size());
            phrasesProgress.put("percentage", progressPercentage);

            // Only mark as complete if ALL current items are completed
            boolean isLessonComplete = (validCompletedItems.size() == phraseItems.size() && !phraseItems.isEmpty());
            phrasesProgress.put("isComplete", isLessonComplete);

            phrasesProgress.put("completedItems", completedItemsList);
            phrasesProgress.put("lastUpdated", System.currentTimeMillis());

            Log.d(TAG, "Updating progress: " + validCompletedItems.size() + "/" + phraseItems.size() +
                    " complete: " + isLessonComplete);

            reference.child(userId)
                    .child("lessons")
                    .child("phrases")
                    .updateChildren(phrasesProgress)
                    .addOnSuccessListener(aVoid -> {
                        preferences.edit()
                                .putStringSet(COMPLETED_PHRASES_PREF, completedPhrases)
                                .apply();

                        if (isLessonComplete) {
                            reference.child(userId)
                                    .child("lessonsProgress")
                                    .child("lesson3")
                                    .setValue(true);
                        }
                        isUpdatingProgress.set(false);
                    })
                    .addOnFailureListener(e -> {
                        showToast("Failed to sync progress");
                        preferences.edit()
                                .putStringSet(COMPLETED_PHRASES_PREF, completedPhrases)
                                .apply();
                        isUpdatingProgress.set(false);
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error updating lesson progress", e);
            isUpdatingProgress.set(false);
        }
    }

    private void restoreCompletedPhrasesFromFirebase() {
        if (auth.getCurrentUser() == null) return;

        String userId = auth.getCurrentUser().getUid();
        reference.child(userId)
                .child("lessons")
                .child("phrases")
                .child("completedItems")
                .get()
                .addOnSuccessListener(dataSnapshot -> {
                    if (!dataSnapshot.exists()) return;

                    Set<String> firebaseCompletedItems = new HashSet<>();
                    for (DataSnapshot item : dataSnapshot.getChildren()) {
                        String kapampanganPhrase = item.getValue(String.class);
                        if (kapampanganPhrase != null) {
                            firebaseCompletedItems.add(kapampanganPhrase);
                        }
                    }

                    completedPhrases = new HashSet<>(firebaseCompletedItems);
                    preferences.edit()
                            .putStringSet(COMPLETED_PHRASES_PREF, firebaseCompletedItems)
                            .apply();

                    updateAllPhraseVisuals();

                    // After restoring from Firebase, update to ensure consistency
                    // This is especially important when new items have been added

                    // Double check for any leftover completions that don't exist anymore
                    Set<String> validCompletedItems = new HashSet<>();
                    Set<String> currentItemKeys = new HashSet<>();

                    for (PhraseItem item : phraseItems) {
                        currentItemKeys.add(item.getKapampangan());
                    }

                    for (String completedItem : completedPhrases) {
                        if (currentItemKeys.contains(completedItem)) {
                            validCompletedItems.add(completedItem);
                        } else {
                            Log.d(TAG, "Removing invalid completed item: " + completedItem);
                        }
                    }

                    // Update the set to only include valid completions
                    completedPhrases = validCompletedItems;
                    preferences.edit()
                            .putStringSet(COMPLETED_PHRASES_PREF, completedPhrases)
                            .apply();

                    updateLessonProgress();
                })
                .addOnFailureListener(e -> {
                    Set<String> savedItems = preferences.getStringSet(COMPLETED_PHRASES_PREF, new HashSet<>());
                    completedPhrases = new HashSet<>(savedItems);
                    updateAllPhraseVisuals();
                    showToast("Failed to load progress from cloud");
                });
    }

    private void showResetDialog() {
        if (isFinishing() || isDestroyed()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogCustom);
        builder.setTitle("Reset Progress")
                .setMessage("Are you sure you want to reset all progress? This will reset progress for this account.")
                .setPositiveButton("Reset", null)
                .setNegativeButton("Cancel", null);

        resetProgressDialog = builder.create();
        resetProgressDialog.setOnShowListener(dialog -> setupResetDialogButtons());
        resetProgressDialog.show();
    }

    private void setupResetDialogButtons() {
        Button positiveButton = resetProgressDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negativeButton = resetProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        if (positiveButton == null || negativeButton == null) return;

        int buttonColor = getResources().getColor(R.color.nav_bar_color_default);
        positiveButton.setTextColor(buttonColor);
        negativeButton.setTextColor(buttonColor);

        positiveButton.setOnClickListener(v -> performReset());
        negativeButton.setOnClickListener(v -> resetProgressDialog.dismiss());
    }

    private void performReset() {
        String userId = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : null;
        if (userId != null) {
            resetFirebaseProgress(userId);
        } else {
            resetLocalProgress();
        }
    }

    private void resetFirebaseProgress(String userId) {
        Map<String, Object> lessonResetValues = createResetValues();
        Map<String, Object> updates = new HashMap<>();
        updates.put("/lessons/phrases", lessonResetValues);
        updates.put("/lessonsProgress/lesson3", false);

        reference.child(userId)
                .updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    resetLocalProgress();
                    showToast("Progress has been reset for this account");
                })
                .addOnFailureListener(e -> {
                    showToast("Failed to reset progress");
                    e.printStackTrace();
                });
    }

    private Map<String, Object> createResetValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("completed", 0);
        values.put("total", phraseItems.size());
        values.put("percentage", 0.0);
        values.put("isComplete", false);
        values.put("completedItems", null);
        values.put("started", false);
        values.put("lastUpdated", System.currentTimeMillis());
        return values;
    }

    private void resetLocalProgress() {
        completedPhrases.clear();
        preferences.edit()
                .remove(COMPLETED_PHRASES_PREF)
                .remove(HAS_SHOWN_CONGRATULATIONS_PREF)
                // Don't remove LAST_ITEM_COUNT_PREF - we still want to track item count
                .apply();
        hasShownCongratulations = false;  // Reset the congratulations flag
        resetAllPhraseVisuals();
        if (resetProgressDialog != null && resetProgressDialog.isShowing()) {
            resetProgressDialog.dismiss();
        }
    }

    private void updateAllPhraseVisuals() {
        runOnUiThread(() -> {
            LinearLayout phrasesContainer = findViewById(R.id.phrasesContainer);
            if (phrasesContainer == null) return;

            // Log completion status for debugging
            Log.d(TAG, "Updating visuals for all items. Completed items: " + completedPhrases.size());
            for (String completed : completedPhrases) {
                Log.d(TAG, "Completed item: " + completed);
            }

            // Loop through all child views in the container
            for (int i = 0; i < phrasesContainer.getChildCount(); i++) {
                View phraseView = phrasesContainer.getChildAt(i);
                if (phraseView == null) continue;

                Object tag = phraseView.getTag();
                if (tag == null || !(tag instanceof PhraseItem)) continue;

                PhraseItem item = (PhraseItem) tag;

                if (item != null) {
                    boolean isCompleted = completedPhrases.contains(item.getKapampangan());
                    Log.d(TAG, "Item: " + item.getKapampangan() + " is completed: " + isCompleted);

                    ImageView checkmark = phraseView.findViewById(R.id.completionCheckmark);
                    if (checkmark != null) {
                        checkmark.setVisibility(isCompleted ? View.VISIBLE : View.GONE);
                    }

                    phraseView.setBackgroundResource(
                            isCompleted ? R.drawable.completed_item_background : R.drawable.phrase_item
                    );
                }
            }
        });
    }

    private void resetAllPhraseVisuals() {
        runOnUiThread(() -> {
            LinearLayout phrasesContainer = findViewById(R.id.phrasesContainer);
            if (phrasesContainer == null) return;

            Log.d(TAG, "Resetting all phrase visuals");

            for (int i = 0; i < phrasesContainer.getChildCount(); i++) {
                View phraseView = phrasesContainer.getChildAt(i);
                if (phraseView == null) continue;

                Object tag = phraseView.getTag();
                if (tag == null || !(tag instanceof PhraseItem)) continue;

                PhraseItem item = (PhraseItem)tag;
                Log.d(TAG, "Resetting visual for item: " + item.getKapampangan());

                ImageView checkmark = phraseView.findViewById(R.id.completionCheckmark);
                if (checkmark != null) {
                    checkmark.setVisibility(View.GONE);
                }
                phraseView.setBackgroundResource(R.drawable.phrase_item);
                phraseView.invalidate();
            }
            phrasesContainer.invalidate();
        });
    }

    private void updatePhraseItemVisual(PhraseItem item) {
        if (item == null) return;

        runOnUiThread(() -> {
            LinearLayout phrasesContainer = findViewById(R.id.phrasesContainer);
            if (phrasesContainer == null) return;

            for (int i = 0; i < phrasesContainer.getChildCount(); i++) {
                View phraseView = phrasesContainer.getChildAt(i);
                if (phraseView == null) continue;

                Object tag = phraseView.getTag();
                if (tag == null || !(tag instanceof PhraseItem)) continue;

                PhraseItem viewItem = (PhraseItem) tag;

                if (viewItem != null && viewItem.getKapampangan().equals(item.getKapampangan())) {
                    ImageView checkmark = phraseView.findViewById(R.id.completionCheckmark);
                    if (checkmark != null) {
                        checkmark.setVisibility(View.VISIBLE);
                    }
                    phraseView.setBackgroundResource(R.drawable.completed_item_background);
                    break;
                }
            }
        });
    }

    private void markPhraseComplete(PhraseItem item) {
        if (item == null) return;

        String itemKey = item.getKapampangan();

        // Add to completion queue and process
        synchronized (pendingCompletionUpdates) {
            if (!completedPhrases.contains(itemKey) && !pendingCompletionUpdates.contains(itemKey)) {
                pendingCompletionUpdates.add(itemKey);
                Log.d(TAG, "Added to completion queue: " + itemKey);
            }
        }

        // Process completion queue asynchronously with a small delay to batch rapid clicks
        new Thread(() -> {
            processCompletionQueue();
        }).start();

        // Update UI immediately for better user experience
        completedPhrases.add(itemKey);
        updatePhraseItemVisual(item);
    }

    private void processCompletionQueue() {
        synchronized (pendingCompletionUpdates) {
            if (isProcessingCompletionQueue || pendingCompletionUpdates.isEmpty()) {
                return;
            }
            isProcessingCompletionQueue = true;
        }

        try {
            // Wait a moment to batch rapid clicks
            Thread.sleep(300);

            List<String> itemsToProcess;
            synchronized (pendingCompletionUpdates) {
                itemsToProcess = new ArrayList<>(pendingCompletionUpdates);
                pendingCompletionUpdates.clear();
            }

            Log.d(TAG, "Processing " + itemsToProcess.size() + " completion updates");

            // Update local storage first
            for (String item : itemsToProcess) {
                completedPhrases.add(item);
            }

            // Save to preferences
            preferences.edit()
                    .putStringSet(COMPLETED_PHRASES_PREF, completedPhrases)
                    .apply();

            // Update remote (Firebase) progress
            updateLessonProgress();

            // Check completion status
            runOnUiThread(() -> {
                if (phraseItems != null && !phraseItems.isEmpty()) {
                    boolean allCompleted = true;
                    for (PhraseItem currentItem : phraseItems) {
                        if (!completedPhrases.contains(currentItem.getKapampangan())) {
                            allCompleted = false;
                            Log.d(TAG, "Incomplete item: " + currentItem.getKapampangan());
                            break;
                        }
                    }

                    if (allCompleted && !hasShownCongratulations) {
                        Log.d(TAG, "ALL ITEMS COMPLETED - showing congratulations");
                        showToast("Congratulations! You've completed the Basic Phrases lesson!");
                        hasShownCongratulations = true;

                        // Save congratulations state to preferences
                        preferences.edit()
                                .putBoolean(HAS_SHOWN_CONGRATULATIONS_PREF, true)
                                .apply();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error processing completion queue", e);
        } finally {
            isProcessingCompletionQueue = false;
        }
    }

    private void showPhraseDialog(PhraseItem item) {
        if (item == null || isFinishing() || isDestroyed()) return;
        dismissActiveDialog();

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_phrase, null);
        if (dialogView == null) {
            Log.e(TAG, "Failed to inflate dialog_phrase layout");
            return;
        }

        setupDialogContent(dialogView, item);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);

        activePhraseDialog = builder.create();
        if (activePhraseDialog.getWindow() != null) {
            activePhraseDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        activePhraseDialog.show();
    }

    private void setupDialogContent(View dialogView, PhraseItem item) {
        if (dialogView == null || item == null) return;

        TextView wordTitle = dialogView.findViewById(R.id.wordTitle);
        TextView definition = dialogView.findViewById(R.id.definition);
        Button closeButton = dialogView.findViewById(R.id.closeButton);
        ImageButton speakerButton = dialogView.findViewById(R.id.speakerButton);

        if (wordTitle == null || definition == null || closeButton == null || speakerButton == null) {
            Log.e(TAG, "Missing views in dialog_phrase layout");
            return;
        }

        wordTitle.setText(item.getKapampangan());
        definition.setText(formatDefinitionText(item));

        // Set up speaker button click listener
        speakerButton.setOnClickListener(v -> playAudio(item.getAudioUrl()));

        closeButton.setOnClickListener(v -> {
            markPhraseComplete(item);
            if (activePhraseDialog != null && activePhraseDialog.isShowing()) {
                activePhraseDialog.dismiss();
            }
        });
    }

    private void playAudio(String audioUrl) {
        if (audioUrl == null || audioUrl.isEmpty()) {
            showToast("Audio not available");
            return;
        }

        // Release any existing MediaPlayer
        releaseMediaPlayer();

        // Find the speaker button in the current dialog
        ImageButton speakerButton = null;
        if (activePhraseDialog != null) {
            speakerButton = activePhraseDialog.findViewById(R.id.speakerButton);
        }

        // Store reference to the button for the onCompletion listener
        final ImageButton finalSpeakerButton = speakerButton;

        // Set button to green to indicate playing
        if (speakerButton != null) {
            speakerButton.setColorFilter(Color.parseColor("#4CAF50"));
        }

        try {
            // Create and prepare a new MediaPlayer with the URL
            mediaPlayer = new MediaPlayer();

            // Set proper audio attributes for streaming
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
            mediaPlayer.setAudioAttributes(attributes);

            // Set data source to the URL
            mediaPlayer.setDataSource(audioUrl);

            // Show loading indicator or message
            showToast("Loading audio...");

            // Prepare the player asynchronously
            mediaPlayer.setOnPreparedListener(mp -> {
                // Start playback when prepared
                mp.start();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                // Handle playback errors
                Log.e(TAG, "Media player error: " + what + ", " + extra);
                showToast("Error playing audio. Please try again.");
                if (finalSpeakerButton != null) {
                    finalSpeakerButton.clearColorFilter();
                }
                releaseMediaPlayer();
                return true;
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                // Reset button color when playback completes
                if (finalSpeakerButton != null) {
                    finalSpeakerButton.clearColorFilter();
                }
                releaseMediaPlayer();
            });

            // Begin preparing the player
            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "Error playing audio from URL: " + e.getMessage(), e);
            if (finalSpeakerButton != null) {
                finalSpeakerButton.clearColorFilter();
            }
            showToast("Error playing audio: " + e.getMessage());
            releaseMediaPlayer();
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            } finally {
                mediaPlayer = null;
            }
        }
    }

    private String formatDefinitionText(PhraseItem item) {
        if (item == null) return "";

        return String.format(
                "English: %s\n\nPronunciation: %s\n\nUsage: %s",
                item.getEnglish(),
                item.getPronunciation(),
                item.getUsage()
        );
    }

    private void showNoInternetDialog() {
        if (isFinishing() || isDestroyed() ||
                (noInternetDialog != null && noInternetDialog.isShowing())) {
            return;
        }

        View dialogView = getLayoutInflater().inflate(R.layout.customnointernetdialog, null);
        if (dialogView == null) {
            Log.e(TAG, "Failed to inflate customnointernetdialog layout");
            return;
        }

        noInternetDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        View okBtn = dialogView.findViewById(R.id.Okbtn);
        if (okBtn != null) {
            okBtn.setOnClickListener(v -> {
                goToLogin();
                if (noInternetDialog != null && noInternetDialog.isShowing()) {
                    noInternetDialog.dismiss();
                }
            });
        }

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

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error registering network callback", e);
        }
    }

    private void checkInternetConnection() {
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
        Intent intent = new Intent(PhraseLesson.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }

    private void dismissActiveDialog() {
        if (activePhraseDialog != null && activePhraseDialog.isShowing()) {
            try {
                activePhraseDialog.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing active phrase dialog", e);
            }
        }
    }

    private void dismissAllDialogs() {
        try {
            if (activePhraseDialog != null && activePhraseDialog.isShowing()) {
                activePhraseDialog.dismiss();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing active phrase dialog", e);
        }

        try {
            if (noInternetDialog != null && noInternetDialog.isShowing()) {
                noInternetDialog.dismiss();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing no internet dialog", e);
        }

        try {
            if (resetProgressDialog != null && resetProgressDialog.isShowing()) {
                resetProgressDialog.dismiss();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing reset progress dialog", e);
        }
    }

    private void showToast(String message) {
        if (isFinishing() || isDestroyed()) return;

        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onPause() {
        dismissAllDialogs();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phrasesLoaded) {
            restoreCompletedPhrasesFromFirebase();
        }
    }

    @Override
    protected void onDestroy() {
        releaseMediaPlayer();

        // Clean up Firebase listeners
        if (auth.getCurrentUser() != null && progressListener != null) {
            try {
                reference.child(auth.getCurrentUser().getUid()).removeEventListener(progressListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing progress listener", e);
            }
        }

        if (phrasesListener != null) {
            try {
                phrasesReference.removeEventListener(phrasesListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing phrases listener", e);
            }
        }

        // Clean up network callback
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback", e);
            }
        }

        dismissAllDialogs();
        super.onDestroy();
    }

    private void setFullscreen() {
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            window.setStatusBarColor(Color.TRANSPARENT);
        }
    }
}