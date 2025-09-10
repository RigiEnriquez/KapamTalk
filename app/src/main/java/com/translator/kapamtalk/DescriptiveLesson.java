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

public class DescriptiveLesson extends AppCompatActivity {
    private static final String TAG = "DescriptiveLesson";
    private static final String FIREBASE_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final String COMPLETED_DESCRIPTIVES_PREF = "completed_descriptives";
    private static final String HAS_SHOWN_CONGRATULATIONS_PREF = "has_shown_descriptives_congratulations";
    private static final String LAST_ITEM_COUNT_PREF = "last_descriptive_item_count";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private FirebaseAuth auth;
    private DatabaseReference reference;
    private DatabaseReference descriptivesReference;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private AlertDialog noInternetDialog;
    private AlertDialog activeDescriptiveDialog;
    private AlertDialog resetProgressDialog;
    private List<DescriptiveItem> descriptiveItems;
    private Set<String> completedDescriptives;
    private SharedPreferences preferences;
    private ValueEventListener progressListener;
    private ValueEventListener descriptivesListener;
    private boolean hasShownCongratulations = false;
    private MediaPlayer mediaPlayer;
    private ProgressBar loadingProgressBar;
    private boolean descriptivesLoaded = false;

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
        setContentView(R.layout.descriptive);

        // Load congratulations state from preferences instead of resetting it
        initializeComponents();
        setupNetworkMonitoring();
    }

    private void initializeComponents() {
        auth = FirebaseAuth.getInstance();
        reference = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("users");
        descriptivesReference = FirebaseDatabase.getInstance(FIREBASE_URL).getReference("descriptives");
        preferences = getSharedPreferences("LessonPreferences", MODE_PRIVATE);
        completedDescriptives = new HashSet<>(preferences.getStringSet(COMPLETED_DESCRIPTIVES_PREF, new HashSet<>()));
        hasShownCongratulations = preferences.getBoolean(HAS_SHOWN_CONGRATULATIONS_PREF, false);
        descriptiveItems = new ArrayList<>();

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
        fetchDescriptiveItemsFromFirebase();
    }

    private void fetchDescriptiveItemsFromFirebase() {
        // Prevent duplicate fetches in short timespan
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTimestamp < 2000) {
            Log.d(TAG, "Ignoring rapid re-fetch request");
            return;
        }
        lastFetchTimestamp = currentTime;

        if (descriptivesListener != null) {
            descriptivesReference.removeEventListener(descriptivesListener);
        }

        // Get the last known item count
        final int lastItemCount = preferences.getInt(LAST_ITEM_COUNT_PREF, 0);

        descriptivesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int currentItemCount = (int)snapshot.getChildrenCount();
                Log.d(TAG, "Fetched descriptives data: " + currentItemCount + " items (previously: " + lastItemCount + ")");

                descriptiveItems.clear();

                for (DataSnapshot descriptiveSnapshot : snapshot.getChildren()) {
                    try {
                        // Extract values from Firebase
                        String kapampangan = descriptiveSnapshot.child("kapampangan").getValue(String.class);
                        String english = descriptiveSnapshot.child("english").getValue(String.class);
                        String pronunciation = descriptiveSnapshot.child("pronunciation").getValue(String.class);
                        String usage = descriptiveSnapshot.child("usage").getValue(String.class);
                        String category = descriptiveSnapshot.child("category").getValue(String.class);
                        String audioUrl = descriptiveSnapshot.child("audioUrl").getValue(String.class);
                        String referencelocator = descriptiveSnapshot.child("referencelocator").getValue(String.class);
                        String example = descriptiveSnapshot.child("example").getValue(String.class);
                        String translated = descriptiveSnapshot.child("translated").getValue(String.class);
                        Integer sortOrder = descriptiveSnapshot.child("sortOrder").getValue(Integer.class);

                        if (kapampangan == null || english == null) {
                            Log.w(TAG, "Skipping invalid descriptive item - missing required fields");
                            continue; // Skip invalid entries
                        }

                        // Create DescriptiveItem with audioUrl
                        DescriptiveItem item = new DescriptiveItem(
                                kapampangan,
                                english,
                                pronunciation != null ? pronunciation : "",
                                usage != null ? usage : "",
                                category != null ? category : "General",
                                audioUrl != null ? audioUrl : "",
                                referencelocator != null ? referencelocator : "",
                                example != null ? example : "",
                                translated != null ? translated : ""
                        );

                        // Add sort order if available
                        if (sortOrder != null) {
                            item.setSortOrder(sortOrder);
                        }

                        descriptiveItems.add(item);
                    } catch (Exception e) {
                        // Log error but continue processing other items
                        Log.e(TAG, "Error processing descriptive item", e);
                    }
                }

                if (descriptiveItems.isEmpty()) {
                    // No descriptives were loaded, show error
                    Log.w(TAG, "No descriptives found in Firebase");
                    showErrorView("No descriptive words available. Please check your connection and try again.");
                } else {
                    // Sort by sortOrder if available, then by category
                    Collections.sort(descriptiveItems, (a, b) -> {
                        // First sort by category
                        int categoryCompare = a.getCategory().compareTo(b.getCategory());
                        if (categoryCompare != 0) return categoryCompare;

                        // Then by sortOrder within category
                        return a.getSortOrder() - b.getSortOrder();
                    });

                    descriptivesLoaded = true;

                    // Log all current items for debugging
                    Log.d(TAG, "ALL CURRENT ITEMS IN FIREBASE:");
                    for (DescriptiveItem item : descriptiveItems) {
                        Log.d(TAG, "Item: " + item.getKapampangan() + " - Category: " + item.getCategory());
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
                            updateAllDescriptiveVisuals();
                        });

                        hasShownCongratulations = false;
                        preferences.edit()
                                .putBoolean(HAS_SHOWN_CONGRATULATIONS_PREF, false)
                                .apply();

                        // IMPORTANT FIX: Force update of the lesson completion status in Firebase
                        // This ensures the lesson is marked as incomplete when new items are added
                        if (auth.getCurrentUser() != null) {
                            String userId = auth.getCurrentUser().getUid();
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("isComplete", false);
                            updates.put("lastUpdated", System.currentTimeMillis());

                            reference.child(userId)
                                    .child("lessons")
                                    .child("descriptives")
                                    .updateChildren(updates)
                                    .addOnSuccessListener(aVoid -> {
                                        Log.d(TAG, "Successfully reset lesson completion status due to new items");
                                        // Also reset the lesson4 progress flag
                                        reference.child(userId)
                                                .child("lessonsProgress")
                                                .child("lesson4")
                                                .setValue(false);
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "Failed to reset lesson completion status", e);
                                    });
                        }
                    }

                    setupUI();
                    restoreCompletedDescriptivesFromFirebase();
                }

                // Hide loading indicator
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Show error - no fallback to local data
                Log.e(TAG, "Failed to load descriptives from Firebase: " + error.getMessage());
                showErrorView("Error loading descriptive words: " + error.getMessage() +
                        "\nPlease check your connection and try again.");

                // Hide loading indicator
                if (loadingProgressBar != null) {
                    loadingProgressBar.setVisibility(View.GONE);
                }
            }
        };

        descriptivesReference.addValueEventListener(descriptivesListener);
    }

    private void showErrorView(String message) {
        runOnUiThread(() -> {
            // Back button is already set up in initializeComponents()

            LinearLayout descriptiveContainer = findViewById(R.id.descriptiveContainer);
            if (descriptiveContainer == null) return;

            // Clear any existing content
            descriptiveContainer.removeAllViews();

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
            retryButton.setBackgroundResource(R.drawable.descriptive_item);
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
                descriptiveContainer.removeAllViews();

                // Refetch data
                fetchDescriptiveItemsFromFirebase();
            });

            // Add views to container
            descriptiveContainer.addView(errorText);
            descriptiveContainer.addView(retryButton);

            // Show internet status if we have that capability
            if (connectivityManager != null) {
                boolean isConnected = isInternetAvailable();
                TextView statusText = new TextView(this);
                statusText.setText("Internet connection: " + (isConnected ? "Available" : "Not available"));
                statusText.setTextColor(isConnected ? Color.GREEN : Color.RED);
                statusText.setTextSize(14);
                statusText.setPadding(32, 16, 32, 32);
                statusText.setGravity(android.view.Gravity.CENTER);
                descriptiveContainer.addView(statusText);
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
                if (!descriptivesLoaded || descriptiveItems.isEmpty()) {
                    Log.d(TAG, "Skipping progress update as descriptives not fully loaded");
                    return;
                }

                DataSnapshot descriptivesSnapshot = snapshot.child("lessons").child("descriptives");
                if (!descriptivesSnapshot.exists()) return;

                DataSnapshot completedItemsSnapshot = descriptivesSnapshot.child("completedItems");
                Set<String> previousCompleted = new HashSet<>(completedDescriptives);
                completedDescriptives.clear();

                if (completedItemsSnapshot.exists() && completedItemsSnapshot.getValue() != null) {
                    for (DataSnapshot item : completedItemsSnapshot.getChildren()) {
                        String kapampanganWord = item.getValue(String.class);
                        if (kapampanganWord != null) {
                            completedDescriptives.add(kapampanganWord);
                        }
                    }
                }

                preferences.edit()
                        .putStringSet(COMPLETED_DESCRIPTIVES_PREF, completedDescriptives)
                        .apply();

                resetAllDescriptiveVisuals();
                updateAllDescriptiveVisuals();

                // Check if all CURRENT items are completed
                Set<String> currentItemsSet = new HashSet<>();
                for (DescriptiveItem item : descriptiveItems) {
                    currentItemsSet.add(item.getKapampangan());
                }

                // Only count items that exist in the current lesson
                int actualCompletedCount = 0;
                for (String completedItem : completedDescriptives) {
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
                    showToast("Congratulations! You've completed the Descriptive Words lesson!");
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
        if (!descriptivesLoaded) {
            Log.w(TAG, "Attempted to setup UI before descriptives loaded");
            return; // Don't set up UI until descriptives are loaded
        }

        // Back button now set up in initializeComponents()

        setupResetButton();
        createDescriptiveItems();
        trackLessonProgress();
    }

    private void createDescriptiveItems() {
        LinearLayout descriptiveContainer = findViewById(R.id.descriptiveContainer);
        if (descriptiveContainer == null) {
            Log.e(TAG, "descriptiveContainer is null! Check your layout.");
            return;
        }

        // Clear any existing items from the container
        descriptiveContainer.removeAllViews();

        // Log current completed items for debugging
        Log.d(TAG, "Creating descriptive items. Currently completed items: " + completedDescriptives.size());

        // Group items by category
        String currentCategory = "";

        // For each descriptive item, create a view and add it to the container
        for (DescriptiveItem item : descriptiveItems) {
            // Add category header if it's a new category
            if (!currentCategory.equals(item.getCategory())) {
                currentCategory = item.getCategory();

                TextView categoryHeader = new TextView(this);
                categoryHeader.setText(currentCategory);
                categoryHeader.setTextSize(18);
                categoryHeader.setPadding(0, 24, 0, 8);
                categoryHeader.setTextColor(getResources().getColor(R.color.nav_bar_color_default));
                categoryHeader.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.aldrich));

                descriptiveContainer.addView(categoryHeader);
            }

            View descriptiveView = getLayoutInflater().inflate(R.layout.item_descriptive, descriptiveContainer, false);
            if (descriptiveView == null) {
                Log.e(TAG, "Failed to inflate descriptive view");
                continue;
            }

            // Find the views within the descriptive item layout
            TextView wordText = descriptiveView.findViewById(R.id.wordText);
            TextView englishText = descriptiveView.findViewById(R.id.englishText);
            ImageView checkmark = descriptiveView.findViewById(R.id.completionCheckmark);

            if (wordText == null || englishText == null || checkmark == null) {
                Log.e(TAG, "Missing required views in descriptive item layout");
                continue;
            }

            // Set the data in the views
            wordText.setText(item.getKapampangan());
            englishText.setText(item.getEnglish());

            // Check completed status for this specific item
            boolean isCompleted = completedDescriptives.contains(item.getKapampangan());
            Log.d(TAG, "Item: " + item.getKapampangan() + " completion status: " + isCompleted);

            // Set initial checkmark visibility based on completion status
            checkmark.setVisibility(isCompleted ? View.VISIBLE : View.GONE);

            // Set the background based on completion status
            descriptiveView.setBackgroundResource(
                    isCompleted ? R.drawable.completed_item_background : R.drawable.descriptive_item
            );

            // Store the descriptive item in the view's tag for later reference
            descriptiveView.setTag(item);

            // Set click listener
            descriptiveView.setOnClickListener(v -> {
                if (v == null || v.getTag() == null) return;
                DescriptiveItem clickedItem = (DescriptiveItem) v.getTag();
                showDescriptiveDialog(clickedItem);
            });

            // Add the view to the container
            descriptiveContainer.addView(descriptiveView);
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
                .child("descriptives")
                .child("started")
                .setValue(true)
                .addOnFailureListener(e -> showToast("Failed to track progress"));
    }

    private void updateLessonProgress() {
        if (auth.getCurrentUser() == null || descriptiveItems.isEmpty()) return;

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

            // Only count completed items that exist in current descriptives
            Set<String> currentItemsSet = new HashSet<>();
            for (DescriptiveItem item : descriptiveItems) {
                currentItemsSet.add(item.getKapampangan());
            }

            Set<String> validCompletedItems = new HashSet<>();
            for (String completed : completedDescriptives) {
                if (currentItemsSet.contains(completed)) {
                    validCompletedItems.add(completed);
                }
            }

            float progressPercentage = descriptiveItems.isEmpty() ? 0 :
                    (float) validCompletedItems.size() / descriptiveItems.size() * 100;
            List<String> completedItemsList = new ArrayList<>(completedDescriptives);

            Map<String, Object> descriptivesProgress = new HashMap<>();
            descriptivesProgress.put("completed", validCompletedItems.size());
            descriptivesProgress.put("total", descriptiveItems.size());
            descriptivesProgress.put("percentage", progressPercentage);

            // Only mark as complete if ALL current items are completed
            boolean isLessonComplete = (validCompletedItems.size() == descriptiveItems.size() && !descriptiveItems.isEmpty());
            descriptivesProgress.put("isComplete", isLessonComplete);

            descriptivesProgress.put("completedItems", completedItemsList);
            descriptivesProgress.put("lastUpdated", System.currentTimeMillis());

            Log.d(TAG, "Updating progress: " + validCompletedItems.size() + "/" + descriptiveItems.size() +
                    " complete: " + isLessonComplete);

            reference.child(userId)
                    .child("lessons")
                    .child("descriptives")
                    .updateChildren(descriptivesProgress)
                    .addOnSuccessListener(aVoid -> {
                        preferences.edit()
                                .putStringSet(COMPLETED_DESCRIPTIVES_PREF, completedDescriptives)
                                .apply();

                        if (isLessonComplete) {
                            reference.child(userId)
                                    .child("lessonsProgress")
                                    .child("lesson4")
                                    .setValue(true);
                        }
                        isUpdatingProgress.set(false);
                    })
                    .addOnFailureListener(e -> {
                        showToast("Failed to sync progress");
                        preferences.edit()
                                .putStringSet(COMPLETED_DESCRIPTIVES_PREF, completedDescriptives)
                                .apply();
                        isUpdatingProgress.set(false);
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error updating lesson progress", e);
            isUpdatingProgress.set(false);
        }
    }

    private void restoreCompletedDescriptivesFromFirebase() {
        if (auth.getCurrentUser() == null) return;

        String userId = auth.getCurrentUser().getUid();
        reference.child(userId)
                .child("lessons")
                .child("descriptives")
                .child("completedItems")
                .get()
                .addOnSuccessListener(dataSnapshot -> {
                    if (!dataSnapshot.exists()) return;

                    Set<String> firebaseCompletedItems = new HashSet<>();
                    for (DataSnapshot item : dataSnapshot.getChildren()) {
                        String kapampanganWord = item.getValue(String.class);
                        if (kapampanganWord != null) {
                            firebaseCompletedItems.add(kapampanganWord);
                        }
                    }

                    completedDescriptives = new HashSet<>(firebaseCompletedItems);
                    preferences.edit()
                            .putStringSet(COMPLETED_DESCRIPTIVES_PREF, firebaseCompletedItems)
                            .apply();

                    updateAllDescriptiveVisuals();

                    // After restoring from Firebase, update to ensure consistency
                    // This is especially important when new items have been added

                    // Double check for any leftover completions that don't exist anymore
                    Set<String> validCompletedItems = new HashSet<>();
                    Set<String> currentItemKeys = new HashSet<>();

                    for (DescriptiveItem item : descriptiveItems) {
                        currentItemKeys.add(item.getKapampangan());
                    }

                    for (String completedItem : completedDescriptives) {
                        if (currentItemKeys.contains(completedItem)) {
                            validCompletedItems.add(completedItem);
                        } else {
                            Log.d(TAG, "Removing invalid completed item: " + completedItem);
                        }
                    }

                    // Update the set to only include valid completions
                    completedDescriptives = validCompletedItems;
                    preferences.edit()
                            .putStringSet(COMPLETED_DESCRIPTIVES_PREF, completedDescriptives)
                            .apply();

                    updateLessonProgress();
                })
                .addOnFailureListener(e -> {
                    Set<String> savedItems = preferences.getStringSet(COMPLETED_DESCRIPTIVES_PREF, new HashSet<>());
                    completedDescriptives = new HashSet<>(savedItems);
                    updateAllDescriptiveVisuals();
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
        updates.put("/lessons/descriptives", lessonResetValues);
        updates.put("/lessonsProgress/lesson4", false);

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
        values.put("total", descriptiveItems.size());
        values.put("percentage", 0.0);
        values.put("isComplete", false);
        values.put("completedItems", null);
        values.put("started", false);
        values.put("lastUpdated", System.currentTimeMillis());
        return values;
    }

    private void resetLocalProgress() {
        completedDescriptives.clear();
        preferences.edit()
                .remove(COMPLETED_DESCRIPTIVES_PREF)
                .remove(HAS_SHOWN_CONGRATULATIONS_PREF)
                // Don't remove LAST_ITEM_COUNT_PREF - we still want to track item count
                .apply();
        hasShownCongratulations = false;  // Reset the congratulations flag
        resetAllDescriptiveVisuals();
        if (resetProgressDialog != null && resetProgressDialog.isShowing()) {
            resetProgressDialog.dismiss();
        }
    }

    private void updateAllDescriptiveVisuals() {
        runOnUiThread(() -> {
            LinearLayout descriptiveContainer = findViewById(R.id.descriptiveContainer);
            if (descriptiveContainer == null) return;

            // Log completion status for debugging
            Log.d(TAG, "Updating visuals for all items. Completed items: " + completedDescriptives.size());
            for (String completed : completedDescriptives) {
                Log.d(TAG, "Completed item: " + completed);
            }

            // Loop through all child views in the container
            for (int i = 0; i < descriptiveContainer.getChildCount(); i++) {
                View descriptiveView = descriptiveContainer.getChildAt(i);
                if (descriptiveView == null) continue;

                // Skip category headers which are TextViews
                if (descriptiveView instanceof TextView) continue;

                Object tag = descriptiveView.getTag();
                if (tag == null || !(tag instanceof DescriptiveItem)) continue;

                DescriptiveItem item = (DescriptiveItem) tag;

                if (item != null) {
                    boolean isCompleted = completedDescriptives.contains(item.getKapampangan());
                    Log.d(TAG, "Item: " + item.getKapampangan() + " is completed: " + isCompleted);

                    ImageView checkmark = descriptiveView.findViewById(R.id.completionCheckmark);
                    if (checkmark != null) {
                        checkmark.setVisibility(isCompleted ? View.VISIBLE : View.GONE);
                    }

                    descriptiveView.setBackgroundResource(
                            isCompleted ? R.drawable.completed_item_background : R.drawable.descriptive_item
                    );
                }
            }
        });
    }

    private void resetAllDescriptiveVisuals() {
        runOnUiThread(() -> {
            LinearLayout descriptiveContainer = findViewById(R.id.descriptiveContainer);
            if (descriptiveContainer == null) return;

            Log.d(TAG, "Resetting all descriptive visuals");

            for (int i = 0; i < descriptiveContainer.getChildCount(); i++) {
                View descriptiveView = descriptiveContainer.getChildAt(i);
                if (descriptiveView == null) continue;

                // Skip category headers which are TextViews
                if (descriptiveView instanceof TextView) continue;

                Object tag = descriptiveView.getTag();
                if (tag == null || !(tag instanceof DescriptiveItem)) continue;

                DescriptiveItem item = (DescriptiveItem)tag;
                Log.d(TAG, "Resetting visual for item: " + item.getKapampangan());

                ImageView checkmark = descriptiveView.findViewById(R.id.completionCheckmark);
                if (checkmark != null) {
                    checkmark.setVisibility(View.GONE);
                }
                descriptiveView.setBackgroundResource(R.drawable.descriptive_item);
                descriptiveView.invalidate();
            }
            descriptiveContainer.invalidate();
        });
    }

    private void updateDescriptiveItemVisual(DescriptiveItem item) {
        if (item == null) return;

        runOnUiThread(() -> {
            LinearLayout descriptiveContainer = findViewById(R.id.descriptiveContainer);
            if (descriptiveContainer == null) return;

            for (int i = 0; i < descriptiveContainer.getChildCount(); i++) {
                View descriptiveView = descriptiveContainer.getChildAt(i);
                if (descriptiveView == null) continue;

                // Skip category headers which are TextViews
                if (descriptiveView instanceof TextView) continue;

                Object tag = descriptiveView.getTag();
                if (tag == null || !(tag instanceof DescriptiveItem)) continue;

                DescriptiveItem viewItem = (DescriptiveItem) tag;

                if (viewItem != null && viewItem.getKapampangan().equals(item.getKapampangan())) {
                    ImageView checkmark = descriptiveView.findViewById(R.id.completionCheckmark);
                    if (checkmark != null) {
                        checkmark.setVisibility(View.VISIBLE);
                    }
                    descriptiveView.setBackgroundResource(R.drawable.completed_item_background);
                    break;
                }
            }
        });
    }

    private void markDescriptiveComplete(DescriptiveItem item) {
        if (item == null) return;

        String itemKey = item.getKapampangan();

        // Add to completion queue and process
        synchronized (pendingCompletionUpdates) {
            if (!completedDescriptives.contains(itemKey) && !pendingCompletionUpdates.contains(itemKey)) {
                pendingCompletionUpdates.add(itemKey);
                Log.d(TAG, "Added to completion queue: " + itemKey);
            }
        }

        // Process completion queue asynchronously with a small delay to batch rapid clicks
        new Thread(() -> {
            processCompletionQueue();
        }).start();

        // Update UI immediately for better user experience
        completedDescriptives.add(itemKey);
        updateDescriptiveItemVisual(item);
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
                completedDescriptives.add(item);
            }

            // Save to preferences
            preferences.edit()
                    .putStringSet(COMPLETED_DESCRIPTIVES_PREF, completedDescriptives)
                    .apply();

            // Update remote (Firebase) progress
            updateLessonProgress();

            // Check completion status
            runOnUiThread(() -> {
                if (descriptiveItems != null && !descriptiveItems.isEmpty()) {
                    boolean allCompleted = true;
                    for (DescriptiveItem currentItem : descriptiveItems) {
                        if (!completedDescriptives.contains(currentItem.getKapampangan())) {
                            allCompleted = false;
                            Log.d(TAG, "Incomplete item: " + currentItem.getKapampangan());
                            break;
                        }
                    }

                    if (allCompleted && !hasShownCongratulations) {
                        Log.d(TAG, "ALL ITEMS COMPLETED - showing congratulations");
                        showToast("Congratulations! You've completed the Descriptive Words lesson!");
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

    private void showDescriptiveDialog(DescriptiveItem item) {
        if (item == null || isFinishing() || isDestroyed()) return;
        dismissActiveDialog();

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_descriptive, null);
        if (dialogView == null) {
            Log.e(TAG, "Failed to inflate dialog_descriptive layout");
            return;
        }

        setupDialogContent(dialogView, item);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);

        activeDescriptiveDialog = builder.create();
        if (activeDescriptiveDialog.getWindow() != null) {
            activeDescriptiveDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        activeDescriptiveDialog.show();
    }

    private void setupDialogContent(View dialogView, DescriptiveItem item) {
        if (dialogView == null || item == null) return;

        TextView wordTitle = dialogView.findViewById(R.id.wordTitle);
        TextView categoryBadge = dialogView.findViewById(R.id.categoryBadge);
        TextView definition = dialogView.findViewById(R.id.definition);
        Button closeButton = dialogView.findViewById(R.id.closeButton);
        ImageButton speakerButton = dialogView.findViewById(R.id.speakerButton);

        if (wordTitle == null || categoryBadge == null || definition == null ||
                closeButton == null || speakerButton == null) {
            Log.e(TAG, "Missing views in dialog_descriptive layout");
            return;
        }

        wordTitle.setText(item.getKapampangan());
        categoryBadge.setText(item.getCategory());
        definition.setText(formatDefinitionText(item));

        // Set up speaker button click listener
        // Use audioUrl instead of audioResourceId
        speakerButton.setOnClickListener(v -> playAudio(item.getAudioUrl()));

        closeButton.setOnClickListener(v -> {
            markDescriptiveComplete(item);
            if (activeDescriptiveDialog != null && activeDescriptiveDialog.isShowing()) {
                activeDescriptiveDialog.dismiss();
            }
        });
    }

    // Changed to accept a URL string instead of a resource ID
    private void playAudio(String audioUrl) {
        if (audioUrl == null || audioUrl.isEmpty()) {
            showToast("Audio not available");
            return;
        }

        // Release any existing MediaPlayer
        releaseMediaPlayer();

        // Find the speaker button in the current dialog
        ImageButton speakerButton = null;
        if (activeDescriptiveDialog != null) {
            speakerButton = activeDescriptiveDialog.findViewById(R.id.speakerButton);
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

    private String formatDefinitionText(DescriptiveItem item) {
        if (item == null) return "";

        return String.format(
                "English: %s\n\nPronunciation: %s\n\nUsage: %s\n\nExample: %s\n\nTranslated: %s",
                item.getEnglish(),
                item.getPronunciation(),
                item.getUsage(),
                item.getExample(),
                item.getTranslated()
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
        Intent intent = new Intent(DescriptiveLesson.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }

    private void dismissActiveDialog() {
        if (activeDescriptiveDialog != null && activeDescriptiveDialog.isShowing()) {
            try {
                activeDescriptiveDialog.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing active descriptive dialog", e);
            }
        }
    }

    private void dismissAllDialogs() {
        try {
            if (activeDescriptiveDialog != null && activeDescriptiveDialog.isShowing()) {
                activeDescriptiveDialog.dismiss();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing active descriptive dialog", e);
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
        if (descriptivesLoaded) {
            restoreCompletedDescriptivesFromFirebase();
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

        if (descriptivesListener != null) {
            try {
                descriptivesReference.removeEventListener(descriptivesListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing descriptives listener", e);
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