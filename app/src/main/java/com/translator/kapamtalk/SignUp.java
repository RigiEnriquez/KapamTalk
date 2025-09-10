package com.translator.kapamtalk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SignUp extends AppCompatActivity {

    // Constants
    private static final int WIFI_TIMEOUT_MS = 10000;
    private static final int CELLULAR_TIMEOUT_MS = 15000;
    private static final int DEFAULT_TIMEOUT_MS = 20000;
    private static final String DB_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final int DRAWABLE_RIGHT = 2;
    private static final int MIN_USERNAME_LENGTH = 6;

    private FirebaseAuth auth;
    private DatabaseReference reference;

    // UI
    private EditText SignUpUsername, SignUpEmail, SignUpPassword, SignUpConfirmPassword;
    private Button btnSignUp;
    private TextView textLoginNow;
    private InputMethodManager imm;

    // Dialogs
    private AlertDialog loadingDialog, noInternetDialog;
    private TextView loadingMessageText;

    // Network
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Executor networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Password visibility
    private boolean isPasswordVisible = false;
    private boolean isConfirmPasswordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFullscreen();
        setContentView(R.layout.signup);

        initializeComponents();
        setupPasswordVisibility();
        registerNetworkCallback();

        // On sign-up button click, check internet then attempt sign-up
        btnSignUp.setOnClickListener(view -> {
            hideKeyboard();
            checkInternetAndRun("Checking internet...", this::validateAndSignUp);
        });

        // If user taps "Login now," check internet first, then navigate
        textLoginNow.setOnClickListener(v -> {
            hideKeyboard();
            checkInternetAndRun("Checking internet...", () -> navigateTo(Login.class));
        });
    }

    private void initializeComponents() {
        // Initialize InputMethodManager
        imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        // Initialize Firebase
        auth = FirebaseAuth.getInstance();
        FirebaseDatabase database = FirebaseDatabase.getInstance(DB_URL);
        reference = database.getReference("users");

        // Initialize UI
        SignUpUsername = findViewById(R.id.SignUpUsername);
        SignUpEmail = findViewById(R.id.SignUpEmail);
        SignUpPassword = findViewById(R.id.SignUpPassword);
        SignUpConfirmPassword = findViewById(R.id.SignUpConfirmPassword);
        btnSignUp = findViewById(R.id.btnSignUp);
        textLoginNow = findViewById(R.id.LoginPage);
    }

    private void hideKeyboard() {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null && imm != null && currentFocus.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(),
                    InputMethodManager.HIDE_NOT_ALWAYS);
        }
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterNetworkCallback();
        dismissDialogs();
    }

    private void dismissDialogs() {
        dismissLoadingDialog();
        if (noInternetDialog != null && noInternetDialog.isShowing()) {
            noInternetDialog.dismiss();
        }
    }

    /**
     * Make the activity fullscreen with a transparent status bar.
     */
    private void setFullscreen() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    //region Password Visibility Methods

    /**
     * Set up password visibility toggle functionality for both password fields.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupPasswordVisibility() {
        // Initial state for both password fields
        updatePasswordVisibilityIcon(SignUpPassword, isPasswordVisible);
        updatePasswordVisibilityIcon(SignUpConfirmPassword, isConfirmPasswordVisible);

        // Prevent text selection menu
        SignUpPassword.setLongClickable(false);
        SignUpConfirmPassword.setLongClickable(false);

        // Set up touch listeners for both password fields
        setupPasswordTouchListener(SignUpPassword, true);
        setupPasswordTouchListener(SignUpConfirmPassword, false);

        // Add text watchers to update icons
        addPasswordTextWatcher(SignUpPassword, true);
        addPasswordTextWatcher(SignUpConfirmPassword, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPasswordTouchListener(EditText passwordField, boolean isMainPassword) {
        passwordField.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // Get drawable dimensions
                if (passwordField.getCompoundDrawables()[DRAWABLE_RIGHT] != null) {
                    // Calculate drawable width properly
                    int drawableWidth = passwordField.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width();
                    int touchAreaRight = passwordField.getWidth() - passwordField.getPaddingEnd() - drawableWidth - 20;

                    // Check if touch is within the icon area
                    if (event.getX() >= touchAreaRight && passwordField.getText().length() > 0) {
                        togglePasswordVisibility(passwordField, isMainPassword);
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private void addPasswordTextWatcher(EditText passwordField, boolean isMainPassword) {
        passwordField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updatePasswordVisibilityIcon(passwordField, isMainPassword ? isPasswordVisible : isConfirmPasswordVisible);
            }
        });
    }

    /**
     * Update the password field's visibility icon based on current state.
     */
    private void updatePasswordVisibilityIcon(EditText passwordField, boolean isVisible) {
        boolean hasText = passwordField.getText().length() > 0;

        int rightIcon = hasText ?
                (isVisible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off) :
                0;

        // Use lock icon for all password fields
        int leftIcon = R.drawable.lockicon;

        passwordField.setCompoundDrawablesWithIntrinsicBounds(leftIcon, 0, rightIcon, 0);
    }

    /**
     * Toggle password visibility and update icon for the specified password field.
     */
    private void togglePasswordVisibility(EditText passwordField, boolean isMainPassword) {
        // Toggle the state for the appropriate field
        if (isMainPassword) {
            isPasswordVisible = !isPasswordVisible;
        } else {
            isConfirmPasswordVisible = !isConfirmPasswordVisible;
        }

        // Save cursor position
        int selection = passwordField.getSelectionEnd();

        // Update input type while preserving font family
        int inputType = InputType.TYPE_CLASS_TEXT |
                ((isMainPassword ? isPasswordVisible : isConfirmPasswordVisible) ?
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                        InputType.TYPE_TEXT_VARIATION_PASSWORD);

        passwordField.setInputType(inputType);

        // Update icon
        updatePasswordVisibilityIcon(passwordField, isMainPassword ? isPasswordVisible : isConfirmPasswordVisible);

        // Restore cursor position
        if (selection >= 0) {
            int textLength = passwordField.getText().length();
            selection = Math.min(selection, textLength);
            try {
                passwordField.setSelection(selection);
            } catch (Exception e) {
                passwordField.setSelection(textLength);
            }
        }

        // Ensure keyboard stays visible
        passwordField.requestFocus();
    }
    //endregion

    //region Network Functions from Login class

    /**
     * Check for internet connection and run task if available.
     */
    private void checkInternetAndRun(String loadingMsg, Runnable onSuccess) {
        showLoadingDialog(loadingMsg, false);
        networkExecutor.execute(() -> {
            final boolean hasInternet = isInternetAvailable();
            mainHandler.post(() -> {
                dismissLoadingDialog();
                if (hasInternet) {
                    onSuccess.run();
                } else {
                    showSingleNoInternetDialog();
                }
            });
        });
    }

    /**
     * Check internet connection by hitting a known endpoint.
     */
    private boolean isInternetAvailable() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://clients3.google.com/generate_204").openConnection();
            conn.setRequestMethod("HEAD");
            int timeout = getAdaptiveTimeout();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            return (conn.getResponseCode() == 204);
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Get adaptive timeout based on network type.
     */
    private int getAdaptiveTimeout() {
        ConnectivityManager cm = ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return WIFI_TIMEOUT_MS;
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return CELLULAR_TIMEOUT_MS;
                    }
                }
            }
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Register network callback for connection status changes.
     */
    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (connectivityManager == null) return;

        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                // Double-check internet availability
                networkExecutor.execute(() -> {
                    boolean hasInternet = isInternetAvailable();
                    mainHandler.post(() -> {
                        if (!hasInternet && !isFinishing() && !isDestroyed()) {
                            showSingleNoInternetDialog();
                        }
                    });
                });
            }
        };

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    /**
     * Unregister network callback on activity destroy.
     */
    private void unregisterNetworkCallback() {
        ConnectivityManager connectivityManager = ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException e) {
                // Callback was not registered or already unregistered
            }
        }
    }

    /**
     * Show loading dialog with message.
     */
    private void showLoadingDialog(String message, boolean cancelable) {
        if (isFinishing() || isDestroyed()) return;

        if (loadingDialog != null && loadingDialog.isShowing()) {
            // If it's already showing, just update the message
            loadingMessageText.setText(message);
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_loading, null);
        loadingMessageText = view.findViewById(R.id.loadingMessage);
        loadingMessageText.setText(message);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(cancelable);

        loadingDialog = builder.create();
        if (loadingDialog.getWindow() != null) {
            loadingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (!isFinishing() && !isDestroyed()) {
            loadingDialog.show();
        }
    }

    /**
     * Dismiss loading dialog if showing.
     */
    private void dismissLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            try {
                loadingDialog.dismiss();
            } catch (IllegalArgumentException e) {
                // Window was already closed
            }
        }
    }

    /**
     * Show no internet dialog if not already showing.
     */
    private void showSingleNoInternetDialog() {
        if (isFinishing() || isDestroyed()) return;
        if (noInternetDialog != null && noInternetDialog.isShowing()) return;

        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;

            View dialogView = LayoutInflater.from(this).inflate(R.layout.customnointernetdialog, null);
            noInternetDialog = new AlertDialog.Builder(this)
                    .setView(dialogView)
                    .create();

            if (noInternetDialog.getWindow() != null) {
                noInternetDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            noInternetDialog.show();
            dialogView.findViewById(R.id.Okbtn).setOnClickListener(v -> noInternetDialog.dismiss());
        });
    }
    //endregion

    /**
     * Validate all fields, then check if the username is taken. This runs AFTER
     * we confirm that internet is available.
     */
    private void validateAndSignUp() {
        String username = SignUpUsername.getText().toString().trim();
        String email = SignUpEmail.getText().toString().trim();
        String password = SignUpPassword.getText().toString();
        String confirmPassword = SignUpConfirmPassword.getText().toString();

        // Reset all errors first
        SignUpUsername.setError(null);
        SignUpEmail.setError(null);
        SignUpPassword.setError(null);
        SignUpConfirmPassword.setError(null);

        if (!validateFields(username, email, password, confirmPassword)) {
            return;
        }

        // If all validations pass, check username in DB
        checkUsernameExists(username, email, password);
    }

    private boolean validateFields(String username, String email, String password, String confirmPassword) {
        boolean isValid = true;

        // Username validation
        if (username.isEmpty()) {
            SignUpUsername.setError("Username is required");
            SignUpUsername.requestFocus();
            isValid = false;
        } else if (username.contains(" ")) {
            SignUpUsername.setError("Spaces are not allowed in username");
            SignUpUsername.requestFocus();
            isValid = false;
        } else if (username.length() < MIN_USERNAME_LENGTH) {
            SignUpUsername.setError("Username must be at least " + MIN_USERNAME_LENGTH + " characters");
            SignUpUsername.requestFocus();
            isValid = false;
        }

        // Email validation
        if (email.isEmpty()) {
            SignUpEmail.setError("Email is required");
            if (isValid) {
                SignUpEmail.requestFocus();
                isValid = false;
            }
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            SignUpEmail.setError("Invalid email format");
            if (isValid) {
                SignUpEmail.requestFocus();
                isValid = false;
            }
        }

        // Enhanced password validation
        if (password.isEmpty()) {
            SignUpPassword.setError("Password is required");
            if (isValid) {
                SignUpPassword.requestFocus();
                isValid = false;
            }
        } else if (password.length() < 6) {
            SignUpPassword.setError("Password must be at least 6 characters");
            if (isValid) {
                SignUpPassword.requestFocus();
                isValid = false;
            }
        } else if (password.contains(" ")) {
            SignUpPassword.setError("Spaces are not allowed in password");
            if (isValid) {
                SignUpPassword.requestFocus();
                isValid = false;
            }
        } else if (!containsUpperCase(password)) {
            SignUpPassword.setError("Password must contain at least one uppercase letter");
            if (isValid) {
                SignUpPassword.requestFocus();
                isValid = false;
            }
        } else if (!containsDigit(password)) {
            SignUpPassword.setError("Password must contain at least one number");
            if (isValid) {
                SignUpPassword.requestFocus();
                isValid = false;
            }
        } else if (!containsSpecialChar(password)) {
            SignUpPassword.setError("Password must contain at least one special character");
            if (isValid) {
                SignUpPassword.requestFocus();
                isValid = false;
            }
        }

        // Confirm password validation
        if (confirmPassword.isEmpty()) {
            SignUpConfirmPassword.setError("Please confirm password");
            if (isValid) {
                SignUpConfirmPassword.requestFocus();
                isValid = false;
            }
        } else if (!password.equals(confirmPassword)) {
            SignUpConfirmPassword.setError("Passwords do not match");
            SignUpPassword.setError("Passwords do not match");
            if (isValid) {
                SignUpConfirmPassword.requestFocus();
                isValid = false;
            }
        }

        return isValid;
    }

    /**
     * Check if the password contains at least one uppercase letter.
     */
    private boolean containsUpperCase(String password) {
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the password contains at least one digit.
     */
    private boolean containsDigit(String password) {
        for (char c : password.toCharArray()) {
            if (Character.isDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the password contains at least one special character.
     */
    private boolean containsSpecialChar(String password) {
        String specialChars = "!@#$%^&*()_-+=<>?/[]{}|~";
        for (char c : password.toCharArray()) {
            if (specialChars.contains(String.valueOf(c))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the password contains at least one special character or digit.
     */
    private boolean containsSpecialCharOrDigit(String password) {
        for (char c : password.toCharArray()) {
            if (Character.isDigit(c) || !Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the username is already in use in the realtime DB.
     */
    private void checkUsernameExists(String username, String email, String password) {
        showLoadingDialog("Checking username...", false);
        reference.orderByChild("username").equalTo(username)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        dismissLoadingDialog();
                        if (snapshot.exists()) {
                            SignUpUsername.setError("Username already taken");
                            SignUpUsername.requestFocus();
                        } else {
                            // Proceed to create the user in FirebaseAuth
                            registerUser(username, email, password);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        dismissLoadingDialog();
                        SignUpUsername.setError("Database Error: " + error.getMessage());
                        SignUpUsername.requestFocus();
                    }
                });
    }

    /**
     * Create a user in FirebaseAuth, then store user info in Realtime Database.
     * Also sends verification email to the user's email address.
     */
    private void registerUser(String username, String email, String password) {
        showLoadingDialog("Creating account...", false);
        auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    dismissLoadingDialog();
                    if (!task.isSuccessful()) {
                        handleRegistrationError(task.getException());
                        return;
                    }

                    // Get the newly created FirebaseUser
                    FirebaseUser user = auth.getCurrentUser();
                    if (user == null) {
                        showToast("Error: Failed to get user information");
                        return;
                    }

                    // Send email verification
                    showLoadingDialog("Sending verification email...", false);
                    user.sendEmailVerification()
                            .addOnCompleteListener(verificationTask -> {
                                dismissLoadingDialog();
                                if (verificationTask.isSuccessful()) {
                                    showToast("Verification email sent. Please check your inbox.");
                                } else {
                                    showToast("Failed to send verification email: " +
                                            (verificationTask.getException() != null ?
                                                    verificationTask.getException().getMessage() :
                                                    "Unknown error"));
                                }

                                // Continue with saving user data regardless of email verification status
                                saveUserData(user.getUid(), username, email);
                            });
                });
    }

    /**
     * Save user data to Firebase Realtime Database
     */
    private void saveUserData(String userId, String username, String email) {
        showLoadingDialog("Saving user data...", false);
        DatabaseHelper newUser = new DatabaseHelper(username, email);

        reference.child(userId).setValue(newUser)
                .addOnCompleteListener(task -> {
                    dismissLoadingDialog();
                    if (task.isSuccessful()) {
                        showEmailVerificationDialog();
                    } else {
                        SignUpUsername.setError("Failed to save user data");
                        SignUpUsername.requestFocus();
                    }
                });
    }

    /**
     * Show a dialog informing the user about email verification
     * Uses a matching style to the forgot password dialog
     */
    private void showEmailVerificationDialog() {
        // Create a custom dialog
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_email_verification, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Set message for signup context
        TextView messageTextView = dialogView.findViewById(R.id.verification_dialog_message);
        messageTextView.setText("We've sent a verification link to your email address. Please verify your email before logging in.");

        // Get references to buttons
        Button okButton = dialogView.findViewById(R.id.btn_ok);
        Button loginButton = dialogView.findViewById(R.id.btn_resend_email);

        // Hide the second button and make OK button take full width
        loginButton.setVisibility(View.GONE);

        // Update layout params to make OK button take full width
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) okButton.getLayoutParams();
        params.weight = 2; // Double the weight to take full space
        params.setMargins(4, 0, 4, 0); // Reset margins
        okButton.setLayoutParams(params);

        // Set click listener for OK button to navigate to login
        okButton.setOnClickListener(v -> {
            dialog.dismiss();
            // Sign out the user to enforce verification
            auth.signOut();
            showToast("Sign Up Successful! Please check your email");
            navigateTo(Login.class);
        });

        dialog.show();
    }

    private void handleRegistrationError(Exception exception) {
        String error = (exception != null) ? exception.getMessage() : "Unknown error";
        // Try to determine which field caused the error
        if (error.toLowerCase().contains("email")) {
            SignUpEmail.setError(error);
            SignUpEmail.requestFocus();
        } else if (error.toLowerCase().contains("password")) {
            SignUpPassword.setError(error);
            SignUpPassword.requestFocus();
        } else {
            // If we can't determine the field, show on email as default
            SignUpEmail.setError(error);
            SignUpEmail.requestFocus();
        }
    }

    /**
     * Navigate to another activity with a fade transition.
     */
    private void navigateTo(Class<?> activityClass) {
        startActivity(new Intent(this, activityClass));
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        finish();
    }

    private void showToast(String message) {
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}