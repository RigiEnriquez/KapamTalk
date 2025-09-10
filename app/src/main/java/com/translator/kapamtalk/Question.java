package com.translator.kapamtalk;

import java.util.List;

public class Question {
    public static final int TYPE_MULTIPLE_CHOICE = 1;
    public static final int TYPE_FILL_BLANK = 2;

    private String questionId;
    private String questionText;
    private int questionType;
    private List<String> options;  // For multiple choice
    private String correctAnswer;
    private String userAnswer;
    private boolean isAnswered;

    public Question() {
        // Required empty constructor for Firebase
    }

    public Question(String questionId, String questionText, int questionType,
                    List<String> options, String correctAnswer) {
        this.questionId = questionId;
        this.questionText = questionText;
        this.questionType = questionType;
        this.options = options;
        this.correctAnswer = correctAnswer;
        this.isAnswered = false;
    }

    // Getters and setters
    public String getQuestionId() { return questionId; }
    public String getQuestionText() { return questionText; }
    public int getQuestionType() { return questionType; }
    public List<String> getOptions() { return options; }
    public String getCorrectAnswer() { return correctAnswer; }
    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String userAnswer) {
        this.userAnswer = userAnswer;
        this.isAnswered = true;
    }
    public boolean isAnswered() {
        return userAnswer != null && !userAnswer.isEmpty();
    }
    public boolean isCorrect() {
        if (userAnswer == null) return false;

        if (questionType == TYPE_FILL_BLANK) {
            // Case-insensitive comparison for fill in the blank
            return userAnswer.trim().equalsIgnoreCase(correctAnswer.trim());
        } else {
            // For multiple choice, keep exact matching
            return userAnswer.equals(correctAnswer);
        }
    }
}