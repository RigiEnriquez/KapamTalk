package com.translator.kapamtalk;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Dashboard extends AppCompatActivity {

    // Constants
    private static final int WIFI_TIMEOUT_MS = 5000;
    private static final int CELLULAR_TIMEOUT_MS = 7000;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    // UI Elements
    private Button continueButton;
    private AlertDialog loadingDialog;
    private TextView loadingMessageText;

    // For background tasks
    private final Executor networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFullscreen();
        setContentView(R.layout.dashboard);

        initializeViews();
        setupListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissLoadingDialog();
    }

    /**
     * Initialize UI components
     */
    private void initializeViews() {
        continueButton = findViewById(R.id.continueButton);
    }

    /**
     * Set up click listeners
     */
    private void setupListeners() {
        continueButton.setOnClickListener(v -> checkInternetAndNavigate());
    }

    /**
     * Check internet connection and navigate accordingly
     */
    private void checkInternetAndNavigate() {
        showLoadingDialog("Checking internet connection...");

        networkExecutor.execute(() -> {
            boolean hasInternet = isInternetAvailable();
            mainHandler.post(() -> {
                dismissLoadingDialog();
                navigateToNextScreen(hasInternet);
            });
        });
    }

    /**
     * Navigate to the appropriate screen based on internet availability
     */
    private void navigateToNextScreen(boolean hasInternet) {
        Intent intent;

        if (hasInternet) {
            intent = new Intent(Dashboard.this, Login.class);
        } else {
            Toast.makeText(
                    this,
                    "No internet connection detected. Redirecting to Dictionary.",
                    Toast.LENGTH_SHORT
            ).show();
            intent = new Intent(Dashboard.this, Dictionary.class);
        }

        startActivity(intent);
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        finish();
    }

    /**
     * Make the status bar transparent for a fullscreen layout.
     */
    private void setFullscreen() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    /**
     * Checks for actual internet access by hitting a known endpoint.
     */
    private boolean isInternetAvailable() {
        HttpURLConnection urlConnection = null;
        int timeout = getAdaptiveTimeout();
        try {
            URL url = new URL("https://clients3.google.com/generate_204");
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("HEAD");
            urlConnection.setConnectTimeout(timeout);
            urlConnection.setReadTimeout(timeout);
            urlConnection.connect();
            return (urlConnection.getResponseCode() == 204);
        } catch (IOException e) {
            return false;
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    /**
     * Dynamically select a timeout based on whether Wi-Fi or cellular is in use.
     */
    private int getAdaptiveTimeout() {
        ConnectivityManager cm = ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) return DEFAULT_TIMEOUT_MS;

        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) return DEFAULT_TIMEOUT_MS;

        NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) return DEFAULT_TIMEOUT_MS;

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WIFI_TIMEOUT_MS;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return CELLULAR_TIMEOUT_MS;
        }

        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * Shows a loading dialog with transparent background and rounded corners.
     * Uses the same implementation as in Login class for consistency.
     */
    private void showLoadingDialog(String message) {
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
                .setCancelable(false);

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
}