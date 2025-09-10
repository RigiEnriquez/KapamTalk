package com.translator.kapamtalk;

public class GreetingItem {
    private final String kapampangan;
    private final String english;
    private final String pronunciation;
    private final String usage;
    private final String audioUrl; // Changed from int audioResourceId
    private final String referencelocator;
    private int sortOrder; // Added for ordering items from Firebase

    // Modified constructor
    public GreetingItem(String kapampangan, String english, String pronunciation,
                        String usage, String audioUrl, String referencelocator) {
        this.kapampangan = kapampangan;
        this.english = english;
        this.pronunciation = pronunciation;
        this.usage = usage;
        this.audioUrl = audioUrl; // Changed parameter type
        this.referencelocator = referencelocator;
        this.sortOrder = 999; // Default high value if not set
    }

    public String getKapampangan() {
        return kapampangan;
    }

    public String getEnglish() {
        return english;
    }

    public String getPronunciation() {
        return pronunciation;
    }

    public String getUsage() {
        return usage;
    }

    public String getAudioUrl() { // Changed from getAudioResourceId
        return audioUrl;
    }

    public String getReferencelocator() {
        return referencelocator;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}