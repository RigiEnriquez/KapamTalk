package com.translator.kapamtalk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class Home extends AppCompatActivity {

    private TextView LogoutNow;
    private TextView usernameText;
    private ImageView profilePicture;
    private BottomNavigationView bottomNavigationView;
    private ActivityResultLauncher<String> imagePickerLauncher;

    // Expandable sections
    private LinearLayout lessonExpandableContent;
    private LinearLayout activitiesExpandableContent;
    private LinearLayout examExpandableContent;
    private ImageView lessonArrowIcon;
    private ImageView activitiesArrowIcon;
    private ImageView examArrowIcon;
    private boolean isLessonExpanded = false;
    private boolean isActivitiesExpanded = false;
    private boolean isExamExpanded = false;

    // Firebase
    private FirebaseAuth auth;
    private DatabaseReference reference;

    // Detect losing network
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    // Two custom dialogs
    private AlertDialog noInternetDialog;
    private AlertDialog confirmLogoutDialog;

    // HEAD request timeouts
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFullscreen();
        setContentView(R.layout.home);

        // Initialize Firebase
        auth = FirebaseAuth.getInstance();
        reference = FirebaseDatabase.getInstance(
                "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).getReference("users");

        // Initialize UI elements
        initializeUIElements();
        initializeExpandableSections();
        initializeProgressTracking();
        setupImagePicker();
        setupBottomNavigation();
        setupBackPressCallback();

        // Register network callback
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    private void initializeUIElements() {
        usernameText = findViewById(R.id.usernameText);
        LogoutNow = findViewById(R.id.Logout);
        profilePicture = findViewById(R.id.profile_user);

        LogoutNow.setOnClickListener(v -> showConfirmLogoutDialog());
        profilePicture.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));

        loadProfilePicture();
        loadUsername();
    }

    private void initializeExpandableSections() {
        // Initialize Lessons section
        CardView lessonsCard = findViewById(R.id.lessonsCard);
        lessonExpandableContent = findViewById(R.id.lessonExpandableContent);
        lessonArrowIcon = findViewById(R.id.lessonArrowIcon);
        lessonExpandableContent.setVisibility(View.GONE);
        lessonsCard.setOnClickListener(v -> toggleExpansion("lessons"));

        // Initialize Activities section
        CardView activitiesCard = findViewById(R.id.activitiesCard);
        activitiesExpandableContent = findViewById(R.id.activitiesExpandableContent);
        activitiesArrowIcon = findViewById(R.id.activitiesArrowIcon);
        activitiesExpandableContent.setVisibility(View.GONE);
        activitiesCard.setOnClickListener(v -> toggleExpansion("activities"));

        // Initialize Quiz section
        CardView examCard = findViewById(R.id.examCard);
        examExpandableContent = findViewById(R.id.examExpandableContent);
        examArrowIcon = findViewById(R.id.examArrowIcon);
        examExpandableContent.setVisibility(View.GONE);
        examCard.setOnClickListener(v -> toggleExpansion("exam"));

        // Add items to each section
        addLessonItems();
        addActivityItems();
        addExamItems();
    }

    private void toggleExpansion(String section) {
        switch (section) {
            case "lessons":
                isLessonExpanded = !isLessonExpanded;
                animateExpansion(lessonArrowIcon, lessonExpandableContent, isLessonExpanded);
                break;
            case "activities":
                isActivitiesExpanded = !isActivitiesExpanded;
                animateExpansion(activitiesArrowIcon, activitiesExpandableContent, isActivitiesExpanded);
                break;
            case "exam":
                isExamExpanded = !isExamExpanded;
                animateExpansion(examArrowIcon, examExpandableContent, isExamExpanded);
                break;
        }
    }

    private void animateExpansion(ImageView arrowIcon, LinearLayout content, boolean isExpanded) {
        // Animate the arrow
        float rotation = isExpanded ? 180f : 0f;
        arrowIcon.animate()
                .rotation(rotation)
                .setDuration(200)
                .start();

        // Show/hide content with animation
        if (isExpanded) {
            content.setVisibility(View.VISIBLE);
            content.setAlpha(0f);
            content.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        } else {
            content.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() ->
                            content.setVisibility(View.GONE))
                    .start();
        }
    }

    private void addLessonItems() {
        String[] lessonTitles = {
                "Lesson 1: Greetings",
                "Lesson 2: Numbers",
                "Lesson 3: Basic Phrases",
                "Lesson 4: Describe",
                "Lesson 5: Body Parts",
                "Lesson 6: Family Members",
                "Lesson 7: Plants/Animals/Inanimate Objects"
        };

        for (String title : lessonTitles) {
            addExpandableItem(lessonExpandableContent, title);
        }
    }

    private void addActivityItems() {
        String[] activityTitles = {
                "Activity 1: Greetings",
                "Activity 2: Numbers",
                "Activity 3: Basic Phrases",
                "Activity 4: Describe",
                "Activity 5: Body Parts",
                "Activity 6: Family Members",
                "Activity 7: Plants/Animals/Inanimate Objects"
        };

        for (String title : activityTitles) {
            addExpandableItem(activitiesExpandableContent, title);
        }
    }

    private void addExamItems() {
        String[] examTitles = {
                "Quiz 1: Basic Greetings",
                "Quiz 2: Numbers and Counting",
                "Quiz 3: Common Phrases",
                "Quiz 4: Describe",
                "Quiz 5: Body Parts",
                "Quiz 6: Family Members",
                "Quiz 7: Plants/Animals/Objects"
        };

        for (String title : examTitles) {
            addExpandableItem(examExpandableContent, title);
        }
    }

    private void addExpandableItem(LinearLayout container, String title) {
        CardView itemCard = new CardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 8, 0, 8);
        itemCard.setLayoutParams(cardParams);
        itemCard.setCardElevation(2f);
        itemCard.setRadius(getResources().getDimensionPixelSize(R.dimen.card_corner_radius));
        itemCard.setCardBackgroundColor(getResources().getColor(R.color.nav_bar_color_default));

        LinearLayout layout = new LinearLayout(this);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.setPadding(32, 36, 32, 36);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackground(getResources().getDrawable(R.drawable.ripple_effect));

        TextView itemText = new TextView(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        itemText.setLayoutParams(textParams);
        itemText.setText(title);
        itemText.setTextSize(18);
        itemText.setTextColor(getResources().getColor(android.R.color.white));
        itemText.setTypeface(ResourcesCompat.getFont(this, R.font.antic));

        layout.addView(itemText);
        itemCard.addView(layout);
        container.addView(itemCard);

        itemCard.setOnClickListener(v -> {
            if (title.equals("Lesson 1: Greetings")) {
                Intent intent = new Intent(this, GreetingLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 1: Greetings")) {
                Intent intent = new Intent(this, GreetingActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Lesson 2: Numbers")) {
                Intent intent = new Intent(this, NumbersLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 2: Numbers")) {
                Intent intent = new Intent(this, NumbersActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Lesson 3: Basic Phrases")) {
                Intent intent = new Intent(this, PhraseLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 3: Basic Phrases")) {
                Intent intent = new Intent(this, PhrasesActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Lesson 4: Describe")) {
                Intent intent = new Intent(this, DescriptiveLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 4: Describe")) {
                Intent intent = new Intent(this, DescriptiveActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Lesson 5: Body Parts")) {
                Intent intent = new Intent(this, BodyPartsLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 5: Body Parts")) {
                Intent intent = new Intent(this, BodyPartActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Lesson 6: Family Members")) {
                Intent intent = new Intent(this, FamilyMemberLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 6: Family Members")) {
                Intent intent = new Intent(this, FamilyMemberActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Lesson 7: Plants/Animals/Inanimate Objects")) {
                Intent intent = new Intent(this, PlantAnimalObjectLesson.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Activity 7: Plants/Animals/Inanimate Objects")) {
                Intent intent = new Intent(this, PlantsAnimalsObjectsActivity.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 1: Basic Greetings")) {
                Intent intent = new Intent(this, ExamOne.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 2: Numbers and Counting")) {
                Intent intent = new Intent(this, ExamTwo.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 3: Common Phrases")) {
                Intent intent = new Intent(this, ExamThree.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 4: Describe")) {
                Intent intent = new Intent(this, ExamFour.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 5: Body Parts")) {
                Intent intent = new Intent(this, ExamFive.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 6: Family Members")) {
                Intent intent = new Intent(this, ExamSix.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else if (title.equals("Quiz 7: Plants/Animals/Objects")) {
                Intent intent = new Intent(this, ExamSeven.class);
                startActivity(intent);
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            } else {
                Toast.makeText(this, "Opening " + title, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initializeProgressTracking() {
        if (auth.getCurrentUser() != null) {
            String userId = auth.getCurrentUser().getUid();

            // Set up progress views
            TextView lessonsCompletedText = findViewById(R.id.lessonsCompletedText);
            ProgressBar lessonsProgressBar = findViewById(R.id.lessonsProgressBar);
            TextView activitiesCompletedText = findViewById(R.id.activitiesCompletedText);
            ProgressBar activitiesProgressBar = findViewById(R.id.activitiesProgressBar);
            TextView examsCompletedText = findViewById(R.id.examsCompletedText);
            ProgressBar examsProgressBar = findViewById(R.id.examsProgressBar);

            // Listen for real-time updates to lessons progress
            reference.child(userId)
                    .child("lessonsProgress")
                    .addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            int completedLessons = 0;
                            int totalLessons = 7; // Total number of lessons

                            // Count completed lessons
                            if (snapshot.exists()) {
                                for (DataSnapshot lessonSnapshot : snapshot.getChildren()) {
                                    Boolean isComplete = lessonSnapshot.getValue(Boolean.class);
                                    if (isComplete != null && isComplete) {
                                        completedLessons++;
                                    }
                                }
                            }

                            // Update UI
                            int progressPercentage = (completedLessons * 100) / totalLessons;
                            lessonsCompletedText.setText(String.format("%d/%d", completedLessons, totalLessons));
                            lessonsProgressBar.setProgress(progressPercentage);

                            // Update lesson items visual state
                            updateLessonItemsState(snapshot);
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            showToast("Failed to load lessons progress");
                        }
                    });

            // Listen for real-time updates to activities progress
            reference.child(userId)
                    .child("activitiesProgress")
                    .addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            int completedActivities = 0;
                            int totalActivities = 7; // Total number of activities

                            // Count completed activities
                            if (snapshot.exists()) {
                                for (DataSnapshot activitySnapshot : snapshot.getChildren()) {
                                    Boolean isComplete = activitySnapshot.getValue(Boolean.class);
                                    if (isComplete != null && isComplete) {
                                        completedActivities++;
                                    }
                                }
                            }

                            // Update UI
                            int progressPercentage = (completedActivities * 100) / totalActivities;
                            activitiesCompletedText.setText(String.format("%d/%d", completedActivities, totalActivities));
                            activitiesProgressBar.setProgress(progressPercentage);

                            // Update activity items visual state
                            updateActivityItemsState(snapshot);
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            showToast("Failed to load activities progress");
                        }
                    });
            // Add listener for real-time updates to exams progress
            reference.child(userId)
                    .child("examsProgress")
                    .addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snapshot) {
                            int completedExams = 0;
                            int totalExams = 7; // Total number of exams

                            // Count completed exams
                            if (snapshot.exists()) {
                                for (DataSnapshot examSnapshot : snapshot.getChildren()) {
                                    Boolean isComplete = examSnapshot.getValue(Boolean.class);
                                    if (isComplete != null && isComplete) {
                                        completedExams++;
                                    }
                                }
                            }

                            // Update UI
                            int progressPercentage = (completedExams * 100) / totalExams;
                            examsCompletedText.setText(String.format("%d/%d", completedExams, totalExams));
                            examsProgressBar.setProgress(progressPercentage);

                            // Update exam items visual state
                            updateExamItemsState(snapshot);
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            showToast("Failed to load exams progress");
                        }
                    });
        }
    }

    private void updateLessonItemsState(DataSnapshot progressSnapshot) {
        LinearLayout lessonExpandableContent = findViewById(R.id.lessonExpandableContent);

        // Map lesson IDs to their respective positions
        Map<String, Integer> lessonPositions = new HashMap<>();
        lessonPositions.put("lesson1", 0); // Greetings
        lessonPositions.put("lesson2", 1); // Numbers
        lessonPositions.put("lesson3", 2); // Basic Phrases
        lessonPositions.put("lesson4", 3); // Descriptive Words
        lessonPositions.put("lesson5", 4); // Body Parts
        lessonPositions.put("lesson6", 5); // Family Members
        lessonPositions.put("lesson7", 6); // Plants/Animals/Inanimate Objects

        // First, reset all lessons to incomplete state
        for (int i = 0; i < lessonPositions.size(); i++) {
            View lessonView = lessonExpandableContent.getChildAt(i);
            if (lessonView instanceof CardView) {
                CardView card = (CardView) lessonView;
                // Reset to default background
                card.setCardBackgroundColor(getResources().getColor(R.color.nav_bar_color_default));

                // Find and hide the completion indicator
                ImageView checkmark = card.findViewById(R.id.completionCheckmark);
                if (checkmark != null) {
                    checkmark.setVisibility(View.GONE);
                }
            }
        }

        // Then update completed lessons
        for (DataSnapshot lessonSnapshot : progressSnapshot.getChildren()) {
            String lessonId = lessonSnapshot.getKey();
            Boolean isComplete = lessonSnapshot.getValue(Boolean.class);

            if (lessonId != null && isComplete != null && isComplete) {
                Integer position = lessonPositions.get(lessonId);
                if (position != null) {
                    View lessonView = lessonExpandableContent.getChildAt(position);
                    if (lessonView instanceof CardView) {
                        // Update the lesson item to show completion
                        CardView card = (CardView) lessonView;
                        card.setCardBackgroundColor(getResources().getColor(R.color.completed_lesson_bg));

                        // Find and update the completion indicator
                        ImageView checkmark = card.findViewById(R.id.completionCheckmark);
                        if (checkmark != null) {
                            checkmark.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        }
    }

    // Add new method to update activity items state
    private void updateActivityItemsState(DataSnapshot progressSnapshot) {
        LinearLayout activityExpandableContent = findViewById(R.id.activitiesExpandableContent);

        // Map activity IDs to their respective positions
        Map<String, Integer> activityPositions = new HashMap<>();
        activityPositions.put("activity1", 0); // Greetings
        activityPositions.put("activity2", 1); // Numbers
        activityPositions.put("activity3", 2); // Basic Phrases
        activityPositions.put("activity4", 3); // Descriptive Words
        activityPositions.put("activity5", 4); // Body Parts
        activityPositions.put("activity6", 5); // Family Members
        activityPositions.put("activity7", 6); // Plants/Animals/Inanimate Objects

        // First, reset all activities to incomplete state
        for (int i = 0; i < activityPositions.size(); i++) {
            View activityView = activityExpandableContent.getChildAt(i);
            if (activityView instanceof CardView) {
                CardView card = (CardView) activityView;
                card.setCardBackgroundColor(getResources().getColor(R.color.nav_bar_color_default));

                ImageView checkmark = card.findViewById(R.id.completionCheckmark);
                if (checkmark != null) {
                    checkmark.setVisibility(View.GONE);
                }
            }
        }

        // Then update completed activities
        for (DataSnapshot activitySnapshot : progressSnapshot.getChildren()) {
            String activityId = activitySnapshot.getKey();
            Boolean isComplete = activitySnapshot.getValue(Boolean.class);

            if (activityId != null && isComplete != null && isComplete) {
                Integer position = activityPositions.get(activityId);
                if (position != null) {
                    View activityView = activityExpandableContent.getChildAt(position);
                    if (activityView instanceof CardView) {
                        CardView card = (CardView) activityView;
                        card.setCardBackgroundColor(getResources().getColor(R.color.completed_lesson_bg));

                        ImageView checkmark = card.findViewById(R.id.completionCheckmark);
                        if (checkmark != null) {
                            checkmark.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        }
    }
    private void updateExamItemsState(DataSnapshot progressSnapshot) {
        LinearLayout examExpandableContent = findViewById(R.id.examExpandableContent);

        // Map exam IDs to their respective positions - Updated to include new quizzes
        Map<String, Integer> examPositions = new HashMap<>();
        examPositions.put("exam1", 0); // Basic Greetings
        examPositions.put("exam2", 1); // Numbers and Counting
        examPositions.put("exam3", 2); // Common Phrases
        examPositions.put("exam4", 3); // Describe
        examPositions.put("exam5", 4); // Body Parts
        examPositions.put("exam6", 5); // Family Members
        examPositions.put("exam7", 6); // Plants/Animals/Objects

        // First, reset all exams to incomplete state
        for (int i = 0; i < examPositions.size(); i++) {
            View examView = examExpandableContent.getChildAt(i);
            if (examView instanceof CardView) {
                CardView card = (CardView) examView;
                // Reset to default background
                card.setCardBackgroundColor(getResources().getColor(R.color.nav_bar_color_default));

                // Find and hide the completion indicator
                ImageView checkmark = card.findViewById(R.id.completionCheckmark);
                if (checkmark != null) {
                    checkmark.setVisibility(View.GONE);
                }
            }
        }

        // Then update completed exams
        for (DataSnapshot examSnapshot : progressSnapshot.getChildren()) {
            String examId = examSnapshot.getKey();
            Boolean isComplete = examSnapshot.getValue(Boolean.class);

            if (examId != null && isComplete != null && isComplete) {
                Integer position = examPositions.get(examId);
                if (position != null) {
                    View examView = examExpandableContent.getChildAt(position);
                    if (examView instanceof CardView) {
                        // Update the exam item to show completion
                        CardView card = (CardView) examView;
                        card.setCardBackgroundColor(getResources().getColor(R.color.completed_lesson_bg));

                        // Find and update the completion indicator
                        ImageView checkmark = card.findViewById(R.id.completionCheckmark);
                        if (checkmark != null) {
                            checkmark.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLessonState();
        refreshExamState();
    }

    // Add this method to manually refresh the lesson state
    public void refreshLessonState() {
        if (auth.getCurrentUser() != null) {
            String userId = auth.getCurrentUser().getUid();
            reference.child(userId)
                    .child("lessonsProgress")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        updateLessonItemsState(snapshot);
                    })
                    .addOnFailureListener(e ->
                            showToast("Failed to refresh lesson state")
                    );
        }
    }
    public void refreshExamState() {
        if (auth.getCurrentUser() != null) {
            String userId = auth.getCurrentUser().getUid();
            reference.child(userId)
                    .child("examsProgress")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        updateExamItemsState(snapshot);
                    })
                    .addOnFailureListener(e ->
                            showToast("Failed to refresh exam state")
                    );
        }
    }

    private void setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        try {
                            Bitmap bitmap = BitmapFactory.decodeStream(
                                    getContentResolver().openInputStream(uri)
                            );
                            Bitmap resizedBitmap = Bitmap.createScaledBitmap(
                                    bitmap, 200, 200, true
                            );
                            String base64Image = bitmapToBase64(resizedBitmap);
                            saveProfilePicture(base64Image);
                            profilePicture.setImageBitmap(resizedBitmap);
                        } catch (Exception e) {
                            showToast("Failed to load image");
                        }
                    }
                }
        );
    }

    private void setupBottomNavigation() {
        bottomNavigationView = findViewById(R.id.bottomNavigationView);
        bottomNavigationView.setSelectedItemId(R.id.nav_home);

        bottomNavigationView.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_home) {
                return true;
            } else if (item.getItemId() == R.id.nav_dictionary) {
                startActivity(new Intent(getApplicationContext(), Dictionary.class));
                finish();
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
                return true;
            } else if (item.getItemId() == R.id.nav_translator) {
                startActivity(new Intent(getApplicationContext(), Translator.class));
                finish();
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
                return true;
            }
            return false;
        });
    }

    private void setupBackPressCallback() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Intent intent = new Intent(Home.this, Home.class);
                startActivity(intent);
                finish();
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    // Existing helper methods
    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.encodeToString(imageBytes, Base64.DEFAULT);
    }

    private Bitmap base64ToBitmap(String base64String) {
        byte[] imageBytes = Base64.decode(base64String, Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private void saveProfilePicture(String base64Image) {
        if (auth.getCurrentUser() != null) {
            String userId = auth.getCurrentUser().getUid();
            reference.child(userId).child("profilePicture")
                    .setValue(base64Image)
                    .addOnFailureListener(e ->
                            showToast("Failed to save profile picture")
                    );
        }
    }

    private void loadProfilePicture() {
        if (auth.getCurrentUser() != null) {
            String userId = auth.getCurrentUser().getUid();
            reference.child(userId).child("profilePicture").get()
                    .addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            String base64Image = snapshot.getValue(String.class);
                            if (base64Image != null) {
                                Bitmap bitmap = base64ToBitmap(base64Image);
                                profilePicture.setImageBitmap(bitmap);
                            }
                        }
                    })
                    .addOnFailureListener(e ->
                            showToast("Failed to load profile picture")
                    );
        }
    }

    private void loadUsername() {
        if (auth.getCurrentUser() != null) {
            String userId = auth.getCurrentUser().getUid();
            reference.child(userId).child("username").get()
                    .addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            String username = snapshot.getValue(String.class);
                            usernameText.setText("Hello " + username + "!");
                        }
                    })
                    .addOnFailureListener(e -> {
                        usernameText.setText("Hello User!");
                        showToast("Failed to load username");
                    });
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        unregisterNetworkCallback();
        dismissNoInternetDialog();
        dismissConfirmLogoutDialog();
        super.onDestroy();
    }

    private void goToLogin() {
        Intent intent = new Intent(Home.this, Login.class);
        startActivity(intent);
        finish();
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
    }

    // Network-related methods
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
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    // Dialog-related methods
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

    private void showConfirmLogoutDialog() {
        if (confirmLogoutDialog != null && confirmLogoutDialog.isShowing()) {
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.customconfirmlogoutdialog, null);

        confirmLogoutDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        // Cancel => just dismiss
        View cancelBtn = dialogView.findViewById(R.id.Cancelbtn);
        cancelBtn.setOnClickListener(v -> {
            if (confirmLogoutDialog != null && confirmLogoutDialog.isShowing()) {
                confirmLogoutDialog.dismiss();
            }
        });

        // Ok => sign out => go to Login
        View okBtn = dialogView.findViewById(R.id.Okbtn2);
        okBtn.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            goToLogin();
            if (confirmLogoutDialog != null && confirmLogoutDialog.isShowing()) {
                confirmLogoutDialog.dismiss();
            }
        });

        confirmLogoutDialog.show();
        if (confirmLogoutDialog.getWindow() != null) {
            confirmLogoutDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private void dismissConfirmLogoutDialog() {
        if (confirmLogoutDialog != null && confirmLogoutDialog.isShowing()) {
            confirmLogoutDialog.dismiss();
        }
    }

    private void setFullscreen() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        window.setStatusBarColor(Color.TRANSPARENT);
    }
}