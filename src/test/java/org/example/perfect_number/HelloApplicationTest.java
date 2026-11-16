package org.example.perfect_number;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real FXML, JavaFX tasks and controls; requires a desktop JavaFX runtime. */
class HelloApplicationTest {
    private HelloApplication application;
    private Stage stage;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @BeforeEach
    void openApplication() throws Exception {
        onFx(() -> {
            application = new HelloApplication();
            stage = new Stage();
            stage.setOpacity(0); // The real window remains invisible during tests.
            application.start(stage);
            return null;
        });
    }

    @AfterEach
    void closeApplication() throws Exception {
        onFx(() -> {
            application.stop();
            stage.close();
            return null;
        });
        await(() -> workerCount() == 0);
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    void loadsTheWindowAndAssociatesInputLabels() throws Exception {
        onFx(() -> {
            assertEquals("Perfect Number", stage.getTitle());
            assertFalse(start().isDisabled());
            assertTrue(stop().isDisabled());
            assertFalse(error().isVisible());
            assertFalse(results().isEditable());
            var root = stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            for (String caption : List.of("Upper limit (num)", "Threads (cnt)", "Perfect numbers")) {
                Label label = root.lookupAll(".label").stream()
                        .filter(node -> node instanceof Label item && item.getText().equals(caption))
                        .map(Label.class::cast).findFirst().orElseThrow();
                assertNotNull(label.getLabelFor(), caption + " has no associated control");
            }
            return null;
        });
    }

    @Test
    void validatesInputsWithoutCreatingWorkers() throws Exception {
        onFx(() -> {
            for (String invalid : List.of("", "abc", "2.5", "0", "-1", "2147483648")) {
                number().setText(invalid);
                threads().setText("1");
                start().fire();
                assertTrue(error().isVisible(), invalid);
                assertTrue(error().getText().startsWith("Upper limit"));
                assertEquals(0, progress().getChildren().size());
                assertFalse(start().isDisabled());
            }
            for (String invalid : List.of("", "abc", "1.5", "0", "-1", "2147483648", "65", "29")) {
                number().setText("28");
                threads().setText(invalid);
                start().fire();
                assertTrue(error().isVisible(), invalid);
                assertTrue(error().getText().startsWith("Threads"));
                assertEquals(0, progress().getChildren().size());
                assertFalse(start().isDisabled());
            }
            assertEquals(0, workerCount());
            return null;
        });
    }

    @Test
    void showsCorrectResultsAndSupportsRepeatedRuns() throws Exception {
        runAndWait(" 28 ", " 4 ");
        onFx(() -> {
            assertEquals("6, 28", results().getText());
            assertEquals("Perfect: 2\nLess than perfect (deficient): 22\nMore than perfect (abundant): 4",
                    summary().getText());
            assertEquals(4, indicators().size());
            assertTrue(indicators().stream().allMatch(indicator -> indicator.getProgress() == 1));
            assertFalse(number().isDisabled());
            assertFalse(threads().isDisabled());
            assertTrue(stop().isDisabled());
            assertFalse(error().isVisible());
            stage.setWidth(600);
            stage.setHeight(650);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertTrue(summary().localToScene(summary().getBoundsInLocal()).getMaxY()
                    <= stage.getScene().getHeight(), "Summary exceeds minimum window bounds");
            return null;
        });
        runAndWait("1", "1");
        onFx(() -> {
            assertEquals("None", results().getText());
            assertEquals("Perfect: 0\nLess than perfect (deficient): 1\nMore than perfect (abundant): 0",
                    summary().getText());
            assertEquals(1, indicators().size());
            assertEquals(1, indicators().getFirst().getProgress());
            return null;
        });
        runAndWait("100", "64");
        onFx(() -> {
            assertEquals("6, 28", results().getText());
            assertEquals(64, indicators().size());
            assertTrue(indicators().stream().allMatch(indicator -> indicator.getProgress() == 1));
            return null;
        });
    }

    @Test
    void updatesProgressWithoutBlockingAndStopsOnWindowClose() throws Exception {
        onFx(() -> {
            number().setText("2147483647");
            threads().setText("3");
            start().fire();
            assertTrue(start().isDisabled());
            assertTrue(number().isDisabled());
            assertTrue(threads().isDisabled());
            assertEquals(3, indicators().size());
            return null;
        });
        await(() -> {
            try {
                return onFx(() -> indicators().stream()
                        .anyMatch(indicator -> indicator.getProgress() > 0 && indicator.getProgress() < 1));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        assertEquals(3, workerCount());
        onFx(() -> {
            assertEquals("", results().getText(), "Incomplete results are visible");
            Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });
        await(() -> workerCount() == 0);
    }

    @Test
    void stopInterruptsWorkersAndPreservesTheirLastProgress() throws Exception {
        onFx(() -> {
            number().setText("2147483647");
            threads().setText("3");
            start().fire();
            assertFalse(stop().isDisabled());
            return null;
        });
        await(() -> {
            try {
                return onFx(() -> indicators().stream().anyMatch(indicator -> indicator.getProgress() > 0));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        List<Double> stoppedProgress = onFx(() -> {
            stop().fire();
            assertEquals("Stopped.", ((Label) find("#statusLabel")).getText());
            assertEquals("No complete results.", summary().getText());
            assertEquals("", results().getText());
            assertFalse(error().isVisible());
            assertFalse(start().isDisabled());
            assertFalse(number().isDisabled());
            assertFalse(threads().isDisabled());
            assertTrue(stop().isDisabled());
            for (Node child : progress().getChildren()) {
                assertEquals("Stopped", ((Label) ((VBox) child).getChildren().getLast()).getText());
            }
            return indicators().stream().map(ProgressIndicator::getProgress).toList();
        });
        await(() -> workerCount() == 0);
        onFx(() -> {
            assertEquals(stoppedProgress, indicators().stream().map(ProgressIndicator::getProgress).toList());
            assertEquals("Stopped.", ((Label) find("#statusLabel")).getText());
            return null;
        });
        runAndWait("28", "4");
        onFx(() -> {
            assertEquals("6, 28", results().getText());
            stop().fire();
            assertEquals("6, 28", results().getText());
            assertTrue(((Label) find("#statusLabel")).getText().startsWith("Complete."));
            return null;
        });
    }

    @Test
    void immediateRestartIgnoresCallbacksFromTheStoppedRun() throws Exception {
        onFx(() -> {
            number().setText("2147483647");
            threads().setText("3");
            start().fire();
            stop().fire();
            number().setText("28");
            threads().setText("4");
            start().fire();
            assertTrue(start().isDisabled());
            assertFalse(stop().isDisabled());
            return null;
        });
        await(() -> {
            try {
                return onFx(() -> !start().isDisabled());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        await(() -> workerCount() == 0);
        onFx(() -> {
            assertEquals("6, 28", results().getText());
            assertEquals("Perfect: 2\nLess than perfect (deficient): 22\nMore than perfect (abundant): 4",
                    summary().getText());
            assertEquals(4, indicators().size());
            assertTrue(indicators().stream().allMatch(indicator -> indicator.getProgress() == 1));
            assertTrue(stop().isDisabled());
            assertFalse(error().isVisible());
            return null;
        });
    }

    private void runAndWait(String num, String cnt) throws Exception {
        onFx(() -> {
            number().setText(num);
            threads().setText(cnt);
            start().fire();
            assertFalse(error().isVisible(), error().getText());
            return null;
        });
        await(() -> {
            try {
                return onFx(() -> !start().isDisabled());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        onFx(() -> {
            assertTrue(((Label) find("#statusLabel")).getText().startsWith("Complete."));
            return null;
        });
    }

    private Node find(String selector) { return stage.getScene().lookup(selector); }
    private TextField number() { return (TextField) find("#numberField"); }
    private TextField threads() { return (TextField) find("#threadField"); }
    private Button start() { return (Button) find("#startButton"); }
    private Button stop() { return (Button) find("#stopButton"); }
    private Label error() { return (Label) find("#errorLabel"); }
    private Label summary() { return (Label) find("#summaryLabel"); }
    private TextArea results() { return (TextArea) find("#resultsArea"); }
    private TilePane progress() { return (TilePane) find("#progressPane"); }

    private List<ProgressIndicator> indicators() {
        return progress().getChildren().stream().map(VBox.class::cast)
                .map(tile -> (ProgressIndicator) tile.getChildren().get(1)).toList();
    }

    private static long workerCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("perfect-number-")).count();
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("Timed out waiting for JavaFX workers");
            }
            Thread.sleep(20);
        }
    }
}
