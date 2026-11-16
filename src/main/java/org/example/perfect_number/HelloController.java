package org.example.perfect_number;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class HelloController {
    @FXML private TextField numberField;
    @FXML private TextField threadField;
    @FXML private Button startButton;
    @FXML private Label errorLabel;
    @FXML private Label statusLabel;
    @FXML private Label summaryLabel;
    @FXML private TextArea resultsArea;
    @FXML private TilePane progressPane;

    private final List<Task<PerfectNumbers.Result>> tasks = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final List<PerfectNumbers.Result> results = new ArrayList<>();
    private long runId;
    private boolean running;

    @FXML
    private void onStart() {
        if (running) {
            return;
        }
        clearError();
        int num;
        int cnt;
        try {
            num = positiveInteger(numberField, "Upper limit");
            cnt = positiveInteger(threadField, "Threads");
            if (cnt > PerfectNumbers.MAX_THREADS || cnt > num) {
                threadField.requestFocus();
                throw new IllegalArgumentException("Threads outside valid range (1 to "
                        + Math.min(num, PerfectNumbers.MAX_THREADS) + ").");
            }
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }

        long currentRun = ++runId;
        tasks.clear();
        workers.clear();
        results.clear();
        progressPane.getChildren().clear();
        resultsArea.clear();
        summaryLabel.setText("Calculating...");
        setRunning(true);
        statusLabel.setText("Running: 0 of " + cnt + " threads finished.");

        List<PerfectNumbers.Range> ranges = PerfectNumbers.partition(num, cnt);
        for (int i = 0; i < ranges.size(); i++) {
            PerfectNumbers.Range range = ranges.get(i);
            ProgressIndicator indicator = new ProgressIndicator(0);
            indicator.setPrefSize(64, 64);
            indicator.setAccessibleText("Thread " + (i + 1) + " progress");
            Label state = new Label("Waiting");
            Label assignment = new Label(range.start() + " to " + range.end());
            assignment.setWrapText(true);
            assignment.setMaxWidth(150);
            VBox tile = new VBox(6, new Label("Thread " + (i + 1)), indicator,
                    assignment, new Label(range.size() + " numbers"), state);
            tile.setAlignment(Pos.CENTER);
            tile.setPrefWidth(150);
            progressPane.getChildren().add(tile);

            Task<PerfectNumbers.Result> task = new Task<>() {
                @Override
                protected PerfectNumbers.Result call() throws InterruptedException {
                    updateProgress(0, range.size());
                    long[] lastUpdate = {System.nanoTime()};
                    return PerfectNumbers.calculate(range, completed -> {
                        long now = System.nanoTime();
                        if (completed == range.size() || now - lastUpdate[0] >= 50_000_000L) {
                            updateProgress(completed, range.size());
                            lastUpdate[0] = now;
                        }
                    });
                }
            };
            task.progressProperty().addListener((observable, oldValue, newValue) -> {
                if (currentRun == runId && running) {
                    indicator.setProgress(Math.max(0, newValue.doubleValue()));
                }
            });
            task.setOnRunning(event -> {
                if (currentRun == runId) {
                    state.setText("Running");
                }
            });
            task.setOnSucceeded(event -> {
                if (currentRun != runId) {
                    return;
                }
                indicator.setProgress(1);
                state.setText("Done");
                results.add(task.getValue());
                statusLabel.setText("Running: " + results.size() + " of " + cnt + " threads finished.");
                if (results.size() == cnt) {
                    finish(num);
                }
            });
            task.setOnFailed(event -> {
                if (currentRun == runId) {
                    state.setText("Failed");
                    fail(task.getException());
                }
            });
            tasks.add(task);
            Thread worker = new Thread(task, "perfect-number-" + currentRun + "-" + (i + 1));
            worker.setDaemon(true);
            workers.add(worker);
        }

        try {
            for (Thread worker : workers) {
                worker.start();
            }
        } catch (RuntimeException | OutOfMemoryError exception) {
            fail(exception);
        }
    }

    private int positiveInteger(TextField field, String name) {
        try {
            int value = Integer.parseInt(field.getText().trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Empty, fractional and oversized values share the same validation error.
        }
        field.requestFocus();
        throw new IllegalArgumentException(name + ": invalid whole number (1 to 2,147,483,647).");
    }

    private void finish(int num) {
        List<Integer> perfect = new ArrayList<>();
        long deficient = 0;
        long abundant = 0;
        for (PerfectNumbers.Result result : results) {
            perfect.addAll(result.perfectNumbers());
            deficient += result.deficientCount();
            abundant += result.abundantCount();
        }
        perfect.sort(Comparator.naturalOrder());
        resultsArea.setText(perfect.isEmpty() ? "None" : perfect.stream()
                .map(String::valueOf).collect(Collectors.joining(", ")));
        summaryLabel.setText("Perfect: " + perfect.size() + "\nLess than perfect (deficient): "
                + deficient + "\nMore than perfect (abundant): " + abundant);
        statusLabel.setText("Complete. Checked all " + num + " numbers from 1 to " + num + ".");
        tasks.clear();
        workers.clear();
        setRunning(false);
    }

    private void fail(Throwable exception) {
        stopWorkers();
        // Completed tiles retain their status; interrupted workers are marked as stopped.
        for (var child : progressPane.getChildren()) {
            VBox tile = (VBox) child;
            Label state = (Label) tile.getChildren().getLast();
            if (!state.getText().equals("Done") && !state.getText().equals("Failed")) {
                state.setText("Stopped");
            }
        }
        resultsArea.clear();
        summaryLabel.setText("No complete results.");
        statusLabel.setText("Calculation failed.");
        String detail = exception == null ? "Unknown error" : exception.getMessage();
        showError("Unable to finish the calculation: "
                + (detail == null ? exception.getClass().getSimpleName() : detail));
        setRunning(false);
    }

    /** Called on the JavaFX thread when the window closes. Never waits on the UI thread. */
    public void shutdown() {
        stopWorkers();
    }

    private void stopWorkers() {
        ++runId;
        for (Task<?> task : tasks) {
            task.cancel(true);
        }
        for (Thread worker : workers) {
            worker.interrupt();
        }
        tasks.clear();
        workers.clear();
        running = false;
    }

    private void setRunning(boolean value) {
        running = value;
        numberField.setDisable(value);
        threadField.setDisable(value);
        startButton.setDisable(value);
    }

    private void clearError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }
}
