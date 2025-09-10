package com.translator.kapamtalk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Dictionary extends AppCompatActivity {

    // UI Components
    private BottomNavigationView bottomNavigationView;
    private EditText searchBar;
    private RecyclerView recyclerView;
    private DictionaryAdapter adapter;
    private TextView englishToggle;
    private TextView kapampanganToggle;

    // Dialogs
    private AlertDialog loginDialog;
    private AlertDialog searchingDialog;
    private AlertDialog dictionaryEntryDialog;

    // Network related
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean wasOffline = false;
    private boolean isTrulyOnline = false;

    // Search related
    private static final long SEARCH_DELAY_MS = 300;
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private List<DictionaryEntry> allEntries = new ArrayList<>();
    private boolean isEnglishSelected = false;

    // Constants
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();
        setContentView(R.layout.dictionary);

        initializeViews();
        setupRecyclerView();
        setupSearchBar();
        setupLanguageToggles();
        setupBottomNavigation();
        setupBackPressHandler();
        setupNetworkMonitoring();

        // Set UI to offline by default until connectivity is confirmed
        setOfflineState();
        wasOffline = true;
        isTrulyOnline = false;

        // Then check connectivity in background
        new Thread(() -> {
            boolean hasInternet = hasActualInternetAccess();
            runOnUiThread(() -> {
                if (hasInternet) {
                    wasOffline = false;
                    isTrulyOnline = true;
                    setOnlineState();
                }
            });
        }).start();

        // Set up touch listener to hide keyboard when touching outside search bar
        findViewById(android.R.id.content).setOnTouchListener((v, event) -> {
            hideKeyboard();
            return false;
        });

        // Load initial dictionary data
        loadDictionaryData();
    }

    private void setupWindow() {
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        setFullscreen();
    }

    private void initializeViews() {
        bottomNavigationView = findViewById(R.id.bottomNavigationView);
        searchBar = findViewById(R.id.searchBar);
        recyclerView = findViewById(R.id.recyclerView);
        englishToggle = findViewById(R.id.englishToggle);
        kapampanganToggle = findViewById(R.id.kapampanganToggle);
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DictionaryAdapter(new ArrayList<>());
        adapter.setOnItemClickListener(this::showDictionaryEntryDialog);
        recyclerView.setAdapter(adapter);

        // Add scroll listener to hide keyboard when scrolling
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    searchBar.clearFocus();
                    hideKeyboard();
                }
            }
        });
    }

    private void setupSearchBar() {
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                final String query = s.toString().trim().toLowerCase();

                if (query.isEmpty()) {
                    adapter.setEntries(new ArrayList<>());
                    return;
                }

                searchRunnable = () -> performRealTimeSearch(query);
                searchHandler.postDelayed(searchRunnable, SEARCH_DELAY_MS);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        searchBar.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = searchBar.getText().toString().trim();
                if (query.isEmpty()) {
                    showEmptySearchMessage();
                } else {
                    performRealTimeSearch(query);
                }
                searchBar.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });

        searchBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard();
            }
        });
    }

    private void setupLanguageToggles() {
        englishToggle.setOnClickListener(v -> {
            englishToggle.setBackground(ContextCompat.getDrawable(this, R.drawable.toggle_selected_background));
            kapampanganToggle.setBackground(null);
            searchBar.setHint("Search for a phrase:");
            updateDictionaryLanguage(true);
            Toast.makeText(Dictionary.this, "English Dictionary Selected!", Toast.LENGTH_SHORT).show();
        });

        kapampanganToggle.setOnClickListener(v -> {
            kapampanganToggle.setBackground(ContextCompat.getDrawable(this, R.drawable.toggle_selected_background));
            englishToggle.setBackground(null);
            searchBar.setHint("Manintun kang parirala:");
            updateDictionaryLanguage(false);
            Toast.makeText(Dictionary.this, "Kapampangan Dictionary Selected!", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupBottomNavigation() {
        bottomNavigationView.setSelectedItemId(R.id.nav_dictionary);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                goToHome();
                return true;
            } else if (id == R.id.nav_translator) {
                if (!isTrulyOnline) {
                    Toast.makeText(Dictionary.this,
                            "Translator requires internet connection",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                startActivity(new Intent(Dictionary.this, Translator.class));
                overridePendingTransition(R.anim.fadein, R.anim.fadeout);
                finish();
                return true;
            } else if (id == R.id.nav_dictionary) {
                return true;
            }
            return false;
        });
    }

    private void setupBackPressHandler() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isTrulyOnline) {
                    goToHome();
                } else {
                    finishAffinity();
                    System.exit(0);
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void setupNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    private void showEmptySearchMessage() {
        String message = isEnglishSelected ?
                "Please enter a word to search." :
                "Pakisulat ya ing salitang panintunan.";
        Toast.makeText(Dictionary.this, message, Toast.LENGTH_SHORT).show();
    }

    private void updateDictionaryLanguage(boolean isEnglish) {
        isEnglishSelected = isEnglish;
        adapter.setEntries(new ArrayList<>());
        searchBar.setText("");
        loadDictionaryData();
    }

    private void loadDictionaryData() {
        new Thread(() -> {
            allEntries = DictionaryUtils.loadDictionaryData(Dictionary.this, isEnglishSelected);
            if (allEntries == null) {
                allEntries = new ArrayList<>();
            }
        }).start();
    }

    private void performRealTimeSearch(String query) {
        if (allEntries.isEmpty()) {
            runOnUiThread(() -> {
                String message = isEnglishSelected
                        ? "Dictionary data not available."
                        : "E ya magamit ing talausuk kapampangan.";
                Toast.makeText(Dictionary.this, message, Toast.LENGTH_SHORT).show();
            });
            return;
        }

        List<DictionaryEntry> results = new ArrayList<>();
        Map<DictionaryEntry, Integer> relevanceMap = new HashMap<>();

        for (DictionaryEntry entry : allEntries) {
            String word = entry.getWord().toLowerCase();
            String meaning = entry.getMeaning().toLowerCase();

            int relevance = calculateRelevance(word, meaning, query);

            if (relevance > 0) {
                relevanceMap.put(entry, relevance);
                results.add(entry);
            }
        }

        Collections.sort(results, (a, b) -> relevanceMap.get(b).compareTo(relevanceMap.get(a)));

        final List<DictionaryEntry> finalResults = results;
        runOnUiThread(() -> {
            if (finalResults.isEmpty()) {
                String message = isEnglishSelected
                        ? "No results found."
                        : "Alang meakit a salita.";
                Toast.makeText(Dictionary.this, message, Toast.LENGTH_SHORT).show();
            }
            adapter.setEntries(finalResults);
        });
    }

    private int calculateRelevance(String word, String meaning, String query) {
        int relevance = 0;

        if (word.equals(query)) {
            relevance += 100;
        } else if (word.startsWith(query)) {
            relevance += 75;
        } else if (word.contains(query)) {
            relevance += 50;
        } else if (meaning.contains(query)) {
            relevance += 25;
        }

        return relevance;
    }

    private void showDictionaryEntryDialog(DictionaryEntry entry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dictionary_entry, null);

        TextView wordTitle = dialogView.findViewById(R.id.wordTitle);
        TextView definition = dialogView.findViewById(R.id.definition);
        Button closeButton = dialogView.findViewById(R.id.closeButton);

        wordTitle.setText(entry.getWord());
        String fullDefinition = "Meaning: " + entry.getMeaning() + "\n\n" +
                "Pronunciation: " + entry.getPronunciation();
        definition.setText(fullDefinition);

        dictionaryEntryDialog = builder.setView(dialogView).create();

        if (dictionaryEntryDialog.getWindow() != null) {
            dictionaryEntryDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        closeButton.setOnClickListener(v -> dictionaryEntryDialog.dismiss());
        dictionaryEntryDialog.show();
    }

    private void hideKeyboard() {
        // Get the input method manager
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // Get current focus
        View focusedView = getCurrentFocus();

        if (focusedView != null) {
            // Clear focus and hide keyboard
            focusedView.clearFocus();
            imm.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        } else {
            // If no view is focused, try to find the root view
            View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.clearFocus();
                imm.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
            }
        }
    }

    private void setFullscreen() {
        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        window.setStatusBarColor(Color.TRANSPARENT);
    }

    private boolean hasActualInternetAccess() {
        return hasNetworkTransportAvailable() && checkHttpConnectivity();
    }

    private boolean hasNetworkTransportAvailable() {
        if (connectivityManager == null) return false;
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(active);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    private boolean checkHttpConnectivity() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://clients3.google.com/generate_204");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            return (responseCode == 204);
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                new Thread(() -> {
                    boolean online = checkHttpConnectivity();
                    runOnUiThread(() -> {
                        if (!isActivityValid()) return;
                        handleNetworkChange(online);
                    });
                }).start();
            }

            @Override
            public void onLost(@NonNull Network network) {
                new Thread(() -> {
                    if (!isInternetStillAvailable()) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && isUserLoggedIn()) {
                                dismissDictionaryEntryDialog();
                                Toast.makeText(Dictionary.this,
                                        "Connectivity lost. You have been logged out automatically.",
                                        Toast.LENGTH_SHORT).show();
                                FirebaseAuth.getInstance().signOut();
                                relaunchCurrentActivity();
                            }
                        });
                    }
                }).start();
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void handleNetworkChange(boolean nowOnline) {
        if (nowOnline) {
            isTrulyOnline = true;
            if (wasOffline) {
                wasOffline = false;
                setOnlineState();
                if (!isUserLoggedIn()) {
                    if (isActivityValid()) {
                        showLoginDialog();
                    }
                }
            }
        } else {
            isTrulyOnline = false;
            wasOffline = true;
            setOfflineState();
        }
    }

    private boolean isInternetStillAvailable() {
        return checkHttpConnectivity();
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }

    private void setOfflineState() {
        bottomNavigationView.getMenu().findItem(R.id.nav_home).setEnabled(false);
        bottomNavigationView.getMenu().findItem(R.id.nav_translator).setEnabled(false);
        bottomNavigationView.setBackgroundColor(
                ContextCompat.getColor(this, R.color.nav_bar_color_offline)
        );
    }

    private void setOnlineState() {
        bottomNavigationView.getMenu().findItem(R.id.nav_home).setEnabled(true);
        bottomNavigationView.getMenu().findItem(R.id.nav_translator).setEnabled(true);
        bottomNavigationView.setBackgroundColor(
                ContextCompat.getColor(this, R.color.nav_bar_color_default)
        );
    }

    private void showLoginDialog() {
        if (!isActivityValid()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Login Required")
                .setMessage("You are now connected to the internet. Please login to continue.")
                .setCancelable(false)
                .setPositiveButton("Login", (dialog, which) -> {
                    dialog.dismiss();
                    if (isActivityValid()) {
                        startActivity(new Intent(Dictionary.this, Login.class));
                        finish();
                    }
                });

        loginDialog = builder.create();
        if (!isActivityValid()) return;

        // Show the dialog first, then apply the rounded corners
        loginDialog.show();

        // Apply rounded corners to the dialog
        if (loginDialog.getWindow() != null) {
            loginDialog.getWindow().setBackgroundDrawableResource(R.drawable.rounded_dialog_background);
        }

        loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
    }

    private void showSearchingDialog(String message) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_searching, null);
        ((TextView) view.findViewById(R.id.loadingMessage)).setText(message);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(view);
        builder.setCancelable(false);
        searchingDialog = builder.create();
        if (!isFinishing() && !isDestroyed()) {
            searchingDialog.show();
        }
    }

    private void dismissSearchingDialog() {
        if (searchingDialog != null && searchingDialog.isShowing()) {
            searchingDialog.dismiss();
            searchingDialog = null;
        }
    }

    private void dismissDictionaryEntryDialog() {
        if (dictionaryEntryDialog != null && dictionaryEntryDialog.isShowing()) {
            dictionaryEntryDialog.dismiss();
            dictionaryEntryDialog = null;
        }
    }

    private void dismissDialog(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void goToHome() {
        if (!isTrulyOnline) return;
        startActivity(new Intent(Dictionary.this, Home.class));
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        finish();
    }

    private void relaunchCurrentActivity() {
        Intent intent = new Intent(Dictionary.this, Dictionary.class);
        startActivity(intent);
        overridePendingTransition(R.anim.fadein, R.anim.fadeout);
        finish();
    }

    private boolean isActivityValid() {
        return !isFinishing() && !isDestroyed();
    }

    private boolean isUserLoggedIn() {
        return FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    @Override
    protected void onDestroy() {
        unregisterNetworkCallback();
        dismissDialog(loginDialog);
        dismissSearchingDialog();
        dismissDictionaryEntryDialog();
        if (searchHandler != null && searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }
        super.onDestroy();
    }
}