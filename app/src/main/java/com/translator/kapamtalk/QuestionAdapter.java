package com.translator.kapamtalk;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class QuestionAdapter extends RecyclerView.Adapter<QuestionAdapter.QuestionViewHolder> {
    private List<Question> questions;
    private boolean feedbackVisible = false;
    private ProgressUpdateListener progressUpdateListener;

    // Interface for progress update callbacks
    public interface ProgressUpdateListener {
        void onAnswerUpdated();
    }

    // Constructor that takes in feedbackVisible parameter
    public QuestionAdapter(List<Question> questions, boolean feedbackVisible) {
        this.questions = questions;
        this.feedbackVisible = feedbackVisible;
    }

    // Constructor with progress update listener
    public QuestionAdapter(List<Question> questions, boolean feedbackVisible, ProgressUpdateListener listener) {
        this.questions = questions;
        this.feedbackVisible = feedbackVisible;
        this.progressUpdateListener = listener;
    }

    // Setter for the progress update listener
    public void setProgressUpdateListener(ProgressUpdateListener listener) {
        this.progressUpdateListener = listener;
    }

    // Helper method to notify progress updates
    private void notifyProgressUpdated() {
        if (progressUpdateListener != null) {
            progressUpdateListener.onAnswerUpdated();
        }
    }

    @Override
    public int getItemViewType(int position) {
        return questions.get(position).getQuestionType();
    }

    @Override
    public QuestionViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view;

        if (viewType == Question.TYPE_MULTIPLE_CHOICE) {
            view = inflater.inflate(R.layout.item_multiple_choice, parent, false);
        } else {
            view = inflater.inflate(R.layout.item_fill_blank, parent, false);
        }

        return new QuestionViewHolder(view, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull QuestionViewHolder holder, int position) {
        Question question = questions.get(position);

        // Bind question text
        holder.questionText.setText(question.getQuestionText());

        // Handle based on question type
        if (question.getQuestionType() == Question.TYPE_MULTIPLE_CHOICE) {
            bindMultipleChoiceQuestion(holder, question);
        } else {
            bindFillBlankQuestion(holder, question);
        }

        // Show feedback if enabled and question is answered
        if (feedbackVisible && question.isAnswered()) {
            showQuestionFeedback(holder, question);
        } else {
            hideQuestionFeedback(holder);
        }

        // Disable interaction if feedback is visible
        if (feedbackVisible) {
            disableInteraction(holder);
        } else {
            enableInteraction(holder);
        }
    }

    private void bindMultipleChoiceQuestion(QuestionViewHolder holder, Question question) {
        // Clear any existing options
        holder.optionsGroup.removeAllViews();

        // Add radio buttons for each option
        for (String option : question.getOptions()) {
            RadioButton rb = new RadioButton(holder.itemView.getContext());
            rb.setText(option);
            rb.setPadding(0, 8, 0, 8);

            // Set button tint
            rb.setButtonTintList(ColorStateList.valueOf(
                    holder.itemView.getContext().getResources().getColor(R.color.nav_bar_color_default)));

            // Ensure text color is visible
            rb.setTextColor(Color.BLACK);

            rb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    question.setUserAnswer(option);
                    notifyProgressUpdated();
                }
            });

            holder.optionsGroup.addView(rb);
        }

        // Restore previous answer if exists
        if (question.isAnswered()) {
            for (int i = 0; i < holder.optionsGroup.getChildCount(); i++) {
                RadioButton rb = (RadioButton) holder.optionsGroup.getChildAt(i);
                if (rb.getText().toString().equals(question.getUserAnswer())) {
                    rb.setChecked(true);
                    break;
                }
            }
        }
    }

    private void bindFillBlankQuestion(QuestionViewHolder holder, Question question) {
        // Remove previous text watcher to avoid duplicates
        if (holder.textWatcher != null) {
            holder.answerInput.removeTextChangedListener(holder.textWatcher);
        }

        // Set clear hint and styling
        holder.answerInput.setHint("Type your answer here:");
        holder.answerInput.setHintTextColor(
                holder.itemView.getContext().getResources().getColor(R.color.nav_bar_color_default));
        holder.answerInput.setTextColor(
                holder.itemView.getContext().getResources().getColor(R.color.nav_bar_color_default));
        holder.answerInput.setTextCursorDrawable(R.drawable.custom_cursor);
        holder.answerInput.setElevation(4f);

        // Set current value
        holder.answerInput.setText(question.getUserAnswer());

        // Add new text watcher
        holder.textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                boolean wasAnswered = question.isAnswered();
                question.setUserAnswer(s.toString());

                boolean isNowAnswered = question.isAnswered();
                if (wasAnswered != isNowAnswered) {
                    notifyProgressUpdated();
                }
            }
        };

        holder.answerInput.addTextChangedListener(holder.textWatcher);

        // Add focus change listener
        holder.answerInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && question.isAnswered()) {
                notifyProgressUpdated();
            }
        });
    }

    private void showQuestionFeedback(QuestionViewHolder holder, Question question) {
        holder.feedbackContainer.setVisibility(View.VISIBLE);

        if (question.isCorrect()) {
            holder.feedbackIcon.setImageResource(R.drawable.ic_correct);
            holder.feedbackText.setText("Correct!");
            holder.feedbackText.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.correct_green));
            holder.itemView.setBackgroundColor(holder.itemView.getContext().getResources().getColor(R.color.light_green_bg));
        } else {
            holder.feedbackIcon.setImageResource(R.drawable.ic_incorrect);
            holder.feedbackText.setText("Incorrect. The correct answer is: " + question.getCorrectAnswer());
            holder.feedbackText.setTextColor(holder.itemView.getContext().getResources().getColor(R.color.incorrect_red));
            holder.itemView.setBackgroundColor(holder.itemView.getContext().getResources().getColor(R.color.light_red_bg));
        }
    }

    private void hideQuestionFeedback(QuestionViewHolder holder) {
        holder.feedbackContainer.setVisibility(View.GONE);
        holder.itemView.setBackgroundResource(R.drawable.card_background);
    }

    private void disableInteraction(QuestionViewHolder holder) {
        // For multiple choice questions
        if (holder.optionsGroup != null) {
            for (int i = 0; i < holder.optionsGroup.getChildCount(); i++) {
                View child = holder.optionsGroup.getChildAt(i);
                child.setEnabled(false);
                child.setClickable(false);
            }
        }

        // For fill in the blank questions
        if (holder.answerInput != null) {
            holder.answerInput.setEnabled(false);
            holder.answerInput.setFocusable(false);
            holder.answerInput.setFocusableInTouchMode(false);
        }
    }

    private void enableInteraction(QuestionViewHolder holder) {
        // For multiple choice questions
        if (holder.optionsGroup != null) {
            for (int i = 0; i < holder.optionsGroup.getChildCount(); i++) {
                View child = holder.optionsGroup.getChildAt(i);
                child.setEnabled(true);
                child.setClickable(true);
            }
        }

        // For fill in the blank questions
        if (holder.answerInput != null) {
            holder.answerInput.setEnabled(true);
            holder.answerInput.setFocusable(true);
            holder.answerInput.setFocusableInTouchMode(true);
        }
    }

    @Override
    public int getItemCount() {
        return questions.size();
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public boolean isFeedbackVisible() {
        return feedbackVisible;
    }

    public void showFeedback() {
        this.feedbackVisible = true;
        notifyDataSetChanged();
    }

    public static class QuestionViewHolder extends RecyclerView.ViewHolder {
        TextView questionText;
        LinearLayout feedbackContainer;
        ImageView feedbackIcon;
        TextView feedbackText;

        // Multiple choice specific views
        RadioGroup optionsGroup;

        // Fill in blank specific views
        EditText answerInput;
        TextWatcher textWatcher;

        public QuestionViewHolder(View itemView, int viewType) {
            super(itemView);

            // Common elements
            questionText = itemView.findViewById(R.id.questionText);
            feedbackContainer = itemView.findViewById(R.id.feedbackContainer);
            feedbackIcon = itemView.findViewById(R.id.feedbackIcon);
            feedbackText = itemView.findViewById(R.id.feedbackText);

            // Question type specific elements
            if (viewType == Question.TYPE_MULTIPLE_CHOICE) {
                optionsGroup = itemView.findViewById(R.id.optionsGroup);
            } else {
                answerInput = itemView.findViewById(R.id.answerInput);
            }
        }
    }
}