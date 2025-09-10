package com.translator.kapamtalk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.CheckBox;
import android.widget.EditText;
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

public class Login extends AppCompatActivity {
    // Constants
    private static final int WIFI_TIMEOUT_MS = 10000;
    private static final int CELLULAR_TIMEOUT_MS = 15000;
    private static final int DEFAULT_TIMEOUT_MS = 20000;
    private static final String PREFS_NAME = "KapamTalkPrefs";
    private static final String DB_URL = "https://kapamtalk-3b1f0-default-rtdb.asia-southeast1.firebasedatabase.app";
    private static final int DRAWABLE_RIGHT = 2;
    private static final int CURSOR_DELAY_MS = 100;

    // UI Elements
    private EditText loginUsername, loginPassword;
    private CheckBox checkRememberMe;

    // Firebase
    private FirebaseAuth auth;
    private DatabaseReference usersRef;

    // Preferences
    private SharedPreferences sharedPreferences;

    // Dialogs
    private AlertDialog loadingDialog;
    private AlertDialog noInternetDialog;
    private TextView loadingMessageText;

    // Network
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Executor networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private InputMethodManager imm;
    private boolean isPasswordVisible = false;

    // Track last resend time to prevent abuse
    private long lastResendTime = 0;
    private static final long RESEND_COOLDOWN_MS = 86400000; // 24 hours cooldown

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFullscreen();
        setContentView(R.layout.login);

        imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        initFirebase();
        initUiElements();
        setupClickListeners();
        setupPasswordVisibility();
        loadSavedCredentials();
        registerNetworkCallback();

        // Resolve username focus issue - don't focus on any field automatically
        // If credentials are loaded, username will already be filled
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterNetworkCallback();
        dismissDialogs();
    }

    /**
     * Dismiss all dialogs to prevent window leaks
     */
    private void dismissDialogs() {
        dismissLoadingDialog();
        if (noInternetDialog != null && noInternetDialog.isShowing()) {
            noInternetDialog.dismiss();
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

    //region Initialization Methods

    /**
     * Initialize Firebase services.
     */
    private void initFirebase() {
        auth = FirebaseAuth.getInstance();
        usersRef = FirebaseDatabase.getInstance(DB_URL).getReference("users");
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    /**
     * Initialize UI elements without setting initial focus.
     */
    private void initUiElements() {
        loginUsername = findViewById(R.id.LoginUsername);
        loginPassword = findViewById(R.id.LoginPassword);
        checkRememberMe = findViewById(R.id.checkRememberMe);

        // Don't request focus automatically - fixes the issue with auto-focusing
    }

    /**
     * Set up click listeners for buttons and text views.
     */
    private void setupClickListeners() {
        findViewById(R.id.btnLogin).setOnClickListener(view -> attemptLogin());
        findViewById(R.id.SignUpPage2).setOnClickListener(view -> {
            hideKeyboard();
            checkInternetAndRun("Checking internet...", this::goToSignUp);
        });
        findViewById(R.id.textForgotPassword).setOnClickListener(view -> {
            hideKeyboard();
            checkInternetAndRun("Checking internet...", this::showForgotPasswordDialog);
        });
    }

    /**
     * Sets the activity to fullscreen mode.
     */
    private void setFullscreen() {
        Window window = getWindow();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            window.setStatusBarColor(Color.TRANSPARENT);
        }
    }
    //endregion

    //region UI Interaction Methods

    /**
     * Hides the keyboard - optimized version.
     */
    private void hideKeyboard() {
        View currentFocus = getCurrentFocus();
        if (currentFocus != null && imm != null && currentFocus.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(currentFocus.getWindowToken(),
                    InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    /**
     * Shows a toast message.
     */
    private void showToast(String message) {
        if (!isFinishing() && !isDestroyed()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Navigate to another activity.
     */
    private void navigateTo(Class<?> activity) {
        Intent intent = new Intent(this, activity);
        startActivity(intent);
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        finish();
    }

    /**
     * Navigate to the sign-up page.
     */
    private void goToSignUp() {
        navigateTo(SignUp.class);
    }
    //endregion

    //region Password Visibility Methods

    /**
     * Set up password visibility toggle functionality.
     * Fixed issues with duplicate indicators and properly handles icon state.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupPasswordVisibility() {
        // Set initial state - only if text is present
        updatePasswordVisibilityIcon();

        // Add this line to prevent text selection menu
        loginPassword.setLongClickable(false);

        loginPassword.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // Get drawable dimensions
                if (loginPassword.getCompoundDrawables()[DRAWABLE_RIGHT] != null) {
                    // Calculate drawable width properly
                    int drawableWidth = loginPassword.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width();
                    int touchAreaRight = loginPassword.getWidth() - loginPassword.getPaddingEnd() - drawableWidth - 20;

                    // Check if touch is within the icon area
                    if (event.getX() >= touchAreaRight && loginPassword.getText().length() > 0) {
                        togglePasswordVisibility();
                        return true;
                    }
                }
            }
            return false;
        });

        // Add text watcher to update icon
        loginPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updatePasswordVisibilityIcon();
            }
        });
    }

    /**
     * Update the password field's visibility icon based on current state.
     * Extracted to avoid duplicate code and ensure consistent state.
     */
    private void updatePasswordVisibilityIcon() {
        boolean hasText = loginPassword.getText().length() > 0;

        int rightIcon = hasText ?
                (isPasswordVisible ? R.drawable.ic_visibility : R.drawable.ic_visibility_off) :
                0;

        loginPassword.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.lockicon, 0, rightIcon, 0);
    }

    /**
     * Toggle password visibility and update icon.
     */
    private void togglePasswordVisibility() {
        // Toggle the state
        isPasswordVisible = !isPasswordVisible;

        // Save cursor position
        int selection = loginPassword.getSelectionEnd();

        // Update input type while preserving font family
        int inputType = InputType.TYPE_CLASS_TEXT |
                (isPasswordVisible ?
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                        InputType.TYPE_TEXT_VARIATION_PASSWORD);

        loginPassword.setInputType(inputType);

        // Update icon
        updatePasswordVisibilityIcon();

        // Restore cursor position - improved approach
        if (selection >= 0) {
            // Ensure we don't exceed text length
            int textLength = loginPassword.getText().length();
            selection = Math.min(selection, textLength);

            try {
                loginPassword.setSelection(selection);
            } catch (Exception e) {
                // Fallback if selection fails
                loginPassword.setSelection(textLength);
            }
        }

        // Ensure keyboard stays visible
        loginPassword.requestFocus();
    }
    //endregion

    //region Login Process

    /**
     * Validate and attempt login if internet is available.
     */
    private void attemptLogin() {
        hideKeyboard();
        if (!validateField(loginUsername, "Username cannot be empty!") ||
                !validateField(loginPassword, "Password cannot be empty!")) {
            return;
        }
        checkInternetAndRun("Checking internet...", this::loginUser);
    }

    /**
     * Validate a field is not empty.
     */
    private boolean validateField(EditText field, String errorMsg) {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) {
            field.setError(errorMsg);
            field.requestFocus();
            return false;
        }
        return true;
    }

    /**
     * Look up user by username and authenticate.
     */
    private void loginUser() {
        showLoadingDialog("Logging in...", false);
        final String username = loginUsername.getText().toString().trim();
        final String password = loginPassword.getText().toString().trim();

        usersRef.orderByChild("username").equalTo(username)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        dismissLoadingDialog();
                        if (!snapshot.exists()) {
                            showToast("Username not found!");
                            loginUsername.requestFocus();
                            return;
                        }

                        // Extract email from the first matching user
                        DataSnapshot userSnapshot = snapshot.getChildren().iterator().next();
                        String email = userSnapshot.child("email").getValue(String.class);

                        if (email != null) {
                            authenticateUser(email, password);
                        } else {
                            showToast("User data error. Please contact support.");
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        dismissLoadingDialog();
                        showToast("Database Error: " + error.getMessage());
                    }
                });
    }

    /**
     * Authenticate with Firebase using email and password.
     * Now checks for email verification after successful authentication.
     */
    private void authenticateUser(String email, String password) {
        showLoadingDialog("Authenticating...", false);
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            dismissLoadingDialog();
            if (task.isSuccessful()) {
                // Check email verification
                FirebaseUser user = auth.getCurrentUser();
                checkUserVerification(user);
            } else {
                showToast("Invalid password!");
                loginPassword.requestFocus();
            }
        });
    }

    /**
     * Check if the user's email is verified
     */
    private void checkUserVerification(FirebaseUser user) {
        if (user != null) {
            if (user.isEmailVerified()) {
                // User is verified, proceed with login
                saveCredentials();
                navigateTo(Home.class);
            } else {
                // User is not verified, show dialog
                showVerificationDialog(user);
                // Sign out the user to enforce verification
                auth.signOut();
            }
        }
    }

    /**
     * Show a custom dialog that allows resending the verification email
     * Uses a matching style to the forgot password dialog
     */
    private void showVerificationDialog(FirebaseUser user) {
        // Create a custom dialog
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_email_verification, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Get references to buttons
        Button okButton = dialogView.findViewById(R.id.btn_ok);
        Button resendButton = dialogView.findViewById(R.id.btn_resend_email);

        // Set click listeners
        okButton.setOnClickListener(v -> {
            dialog.dismiss();
        });

        resendButton.setOnClickListener(v -> {
            resendVerificationEmail(user, dialog);
        });

        dialog.show();
    }

    /**
     * Resend verification email with rate limiting
     */
    private void resendVerificationEmail(FirebaseUser user, AlertDialog parentDialog) {
        long currentTime = System.currentTimeMillis();

        // Check if we're within the cooldown period
        if (currentTime - lastResendTime < RESEND_COOLDOWN_MS) {
            long remainingHours = (RESEND_COOLDOWN_MS - (currentTime - lastResendTime)) / 3600000;
            long remainingMinutes = ((RESEND_COOLDOWN_MS - (currentTime - lastResendTime)) % 3600000) / 60000;
            showToast("Please wait " + remainingHours + " hours and " + remainingMinutes +
                    " minutes before requesting another verification email");
            return;
        }

        showLoadingDialog("Resending verification email...", false);
        user.sendEmailVerification()
                .addOnCompleteListener(task -> {
                    dismissLoadingDialog();
                    if (task.isSuccessful()) {
                        lastResendTime = System.currentTimeMillis(); // Update last sent time
                        showToast("Verification email sent. Please check your inbox.");

                        // Close the verification dialog after successful resend
                        if (parentDialog != null && parentDialog.isShowing()) {
                            parentDialog.dismiss();
                        }
                    } else {
                        String errorMessage = task.getException() != null ?
                                task.getException().getMessage() : "Unknown error";

                        // Check for rate limiting or blocking errors
                        if (errorMessage.toLowerCase().contains("blocked") ||
                                errorMessage.toLowerCase().contains("too many attempts") ||
                                errorMessage.toLowerCase().contains("unusual activity")) {
                            showToast("Too many requests. Please try again later or check your spam folder for the verification email.");
                        } else {
                            showToast("Failed to send verification email: " + errorMessage);
                        }
                    }
                });
    }
    //endregion

    //region Remember Me Functionality

    /**
     * Save credentials if "Remember Me" is checked.
     */
    private void saveCredentials() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (checkRememberMe.isChecked()) {
            editor.putString("SavedUsername", loginUsername.getText().toString().trim());
            editor.putString("SavedPassword", loginPassword.getText().toString().trim());
            editor.putBoolean("RememberMe", true);
        } else {
            editor.clear();
        }
        editor.apply();
    }

    /**
     * Load saved credentials if "Remember Me" was checked.
     */
    private void loadSavedCredentials() {
        if (sharedPreferences.getBoolean("RememberMe", false)) {
            loginUsername.setText(sharedPreferences.getString("SavedUsername", ""));
            loginPassword.setText(sharedPreferences.getString("SavedPassword", ""));
            checkRememberMe.setChecked(true);
        }
    }
    //endregion

    //region Password Reset

    /**
     * Show dialog for forgotten password.
     */
    private void showForgotPasswordDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialogforgotpassword, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialog.show();

        EditText inputEmail = dialogView.findViewById(R.id.inputEmail);

        dialogView.findViewById(R.id.Resetbtn).setOnClickListener(v -> {
            String email = inputEmail.getText().toString().trim();
            if (email.isEmpty()) {
                inputEmail.setError("Please enter your registered email");
                inputEmail.requestFocus();
                return;
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                inputEmail.setError("Invalid email format!");
                inputEmail.requestFocus();
                return;
            }
            dialog.dismiss();
            checkInternetAndRun("Verifying email...", () -> checkEmailExists(email));
        });

        dialogView.findViewById(R.id.Cancelbtn).setOnClickListener(v -> dialog.dismiss());
    }

    /**
     * Check if email exists in the database.
     */
    private void checkEmailExists(String email) {
        showLoadingDialog("Verifying email...", false);
        usersRef.orderByChild("email").equalTo(email)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        dismissLoadingDialog();
                        if (snapshot.exists()) {
                            sendPasswordResetEmail(email);
                        } else {
                            showToast("This email is not registered.");
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        dismissLoadingDialog();
                        showToast("Database Error: " + error.getMessage());
                    }
                });
    }

    /**
     * Send password reset email via Firebase Auth.
     */
    private void sendPasswordResetEmail(String email) {
        showLoadingDialog("Requesting password reset...", false);
        auth.sendPasswordResetEmail(email).addOnCompleteListener(task -> {
            dismissLoadingDialog();
            if (task.isSuccessful()) {
                showToast("Password reset link sent to your email.");
            } else {
                showToast("Error: " + (task.getException() != null
                        ? task.getException().getMessage()
                        : "Unknown error"));
            }
        });
    }
    //endregion

    //region Internet Connectivity

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
    //endregion

    //region Dialog Management

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
}