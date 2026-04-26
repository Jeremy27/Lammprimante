package fr.courel.lammprimante;

import fr.courel.lammprimante.config.PreferencesManager;
import fr.courel.lammprimante.service.LogService;
import fr.courel.lammprimante.service.UpdateService;
import fr.courel.lammprimante.view.MainWindow;
import fr.courel.lammui.fx.component.LammButtonFx;
import fr.courel.lammui.fx.component.LammChromeFx;
import fr.courel.lammui.fx.theme.LammThemeFx;
import javafx.application.Application;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.prefs.Preferences;

public class App extends Application {

    private static final double DEFAULT_WIDTH = 800;
    private static final double DEFAULT_HEIGHT = 700;
    private static final String PREF_ACCENT = "accent";
    private static final String ACCENT_STEEL = "steel";
    private static final String ACCENT_EMERALD = "emerald";
    private static final String ACCENT_AMBER = "amber";
    private static final Preferences APP_PREFS = Preferences.userNodeForPackage(App.class);

    private Stage stage;
    private Scene scene;

    public static String getVersion() {
        try (var is = App.class.getResourceAsStream("/version.txt")) {
            return is != null ? new String(is.readAllBytes()).trim() : "inconnue";
        } catch (Exception e) {
            return "inconnue";
        }
    }

    public static void main(String[] args) {
        LogService.info("Démarrage de Lammprimante v" + getVersion());
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        primaryStage.initStyle(StageStyle.UNDECORATED);
        loadIcon();

        var chrome = new LammChromeFx("primante v" + getVersion());
        chrome.attachTo(primaryStage);

        var mainWindow = new MainWindow(this);
        VBox.setVgrow(mainWindow, Priority.ALWAYS);
        chrome.getChildren().add(mainWindow);

        scene = new Scene(chrome, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        LammThemeFx.install(scene);
        applySavedPreferences();

        chrome.addAction(buildSettingsButton());

        applySavedBounds();
        primaryStage.setTitle("Lammprimante");
        primaryStage.setScene(scene);
        primaryStage.show();

        primaryStage.setOnCloseRequest(e -> {
            mainWindow.shutdown();
            persistBounds();
        });

        UpdateService.checkForUpdatesAsync(primaryStage);
    }

    public Stage stage() {
        return stage;
    }

    public Scene scene() {
        return scene;
    }

    public void styleDialog(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        pane.setGraphic(null);
        pane.getStylesheets().setAll(scene.getStylesheets());
        var sceneRoot = scene.getRoot();
        for (var cls : sceneRoot.getStyleClass()) {
            if ("dark".equals(cls) || cls.startsWith("accent-")) {
                if (!pane.getStyleClass().contains(cls)) {
                    pane.getStyleClass().add(cls);
                }
            }
        }
    }

    public void styleScene(Scene other) {
        other.getStylesheets().setAll(scene.getStylesheets());
        var sceneRoot = scene.getRoot();
        for (var cls : sceneRoot.getStyleClass()) {
            if ("dark".equals(cls) || cls.startsWith("accent-")) {
                if (!other.getRoot().getStyleClass().contains(cls)) {
                    other.getRoot().getStyleClass().add(cls);
                }
            }
        }
    }

    private void loadIcon() {
        try (var in = getClass().getResourceAsStream("/logo.jpg")) {
            if (in != null) stage.getIcons().add(new Image(in));
        } catch (Exception ignored) {
        }
    }

    private void applySavedPreferences() {
        boolean dark = "dark".equalsIgnoreCase(PreferencesManager.getTheme());
        LammThemeFx.setMode(scene, dark ? LammThemeFx.Mode.DARK : LammThemeFx.Mode.LIGHT);
        applyAccent(APP_PREFS.get(PREF_ACCENT, ACCENT_STEEL));
    }

    private void applyAccent(String accent) {
        var root = scene.getRoot();
        root.getStyleClass().removeIf(c -> c.startsWith("accent-"));
        if (accent != null && !ACCENT_STEEL.equals(accent)) {
            root.getStyleClass().add("accent-" + accent);
        }
        APP_PREFS.put(PREF_ACCENT, accent == null ? ACCENT_STEEL : accent);
    }

    private void applySavedBounds() {
        var screen = Screen.getPrimary().getVisualBounds();
        double maxW = Math.max(700, screen.getWidth() - 80);
        double maxH = Math.max(600, screen.getHeight() - 120);

        double w = Math.min(Math.max(PreferencesManager.getWindowWidth(), 700), maxW);
        double h = Math.min(Math.max(PreferencesManager.getWindowHeight(), 600), maxH);
        stage.setWidth(w);
        stage.setHeight(h);

        int x = PreferencesManager.getWindowX();
        int y = PreferencesManager.getWindowY();
        boolean onScreen = x >= 0 && y >= 0 && x + w <= screen.getWidth() && y + h <= screen.getHeight();
        if (onScreen) {
            stage.setX(x);
            stage.setY(y);
        } else {
            stage.setX(screen.getMinX() + (screen.getWidth() - w) / 2);
            stage.setY(screen.getMinY() + (screen.getHeight() - h) / 2);
        }

        if (PreferencesManager.getWindowMaximized()) {
            stage.setMaximized(true);
        }
    }

    private void persistBounds() {
        if (!stage.isMaximized()) {
            PreferencesManager.setWindowBounds(
                (int) stage.getX(), (int) stage.getY(),
                (int) stage.getWidth(), (int) stage.getHeight());
        }
        PreferencesManager.setWindowMaximized(stage.isMaximized());
    }

    private void resetWindow() {
        stage.setMaximized(false);
        stage.setWidth(DEFAULT_WIDTH);
        stage.setHeight(DEFAULT_HEIGHT);
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMinX() + (screen.getWidth() - DEFAULT_WIDTH) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - DEFAULT_HEIGHT) / 2);
    }

    private void showAbout() {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("À propos");
        alert.setHeaderText("Lammprimante");
        alert.setContentText(
            "Version " + getVersion()
            + "\nImpression PDF / images / ZIP par lots"
            + "\n\n© 2026 Jeremy Courel");
        alert.initOwner(stage);
        styleDialog(alert);
        alert.showAndWait();
    }

    private Button buildSettingsButton() {
        var lightItem = new RadioMenuItem("Mode clair");
        var darkItem = new RadioMenuItem("Mode sombre");
        var themeGroup = new ToggleGroup();
        lightItem.setToggleGroup(themeGroup);
        darkItem.setToggleGroup(themeGroup);
        lightItem.setOnAction(e -> {
            LammThemeFx.setMode(scene, LammThemeFx.Mode.LIGHT);
            PreferencesManager.setTheme("light");
        });
        darkItem.setOnAction(e -> {
            LammThemeFx.setMode(scene, LammThemeFx.Mode.DARK);
            PreferencesManager.setTheme("dark");
        });

        var accentSteel = new RadioMenuItem("Bleu acier");
        var accentEmerald = new RadioMenuItem("Émeraude");
        var accentAmber = new RadioMenuItem("Ambre");
        var accentGroup = new ToggleGroup();
        accentSteel.setToggleGroup(accentGroup);
        accentEmerald.setToggleGroup(accentGroup);
        accentAmber.setToggleGroup(accentGroup);
        accentSteel.setOnAction(e -> applyAccent(ACCENT_STEEL));
        accentEmerald.setOnAction(e -> applyAccent(ACCENT_EMERALD));
        accentAmber.setOnAction(e -> applyAccent(ACCENT_AMBER));
        var accentMenu = new Menu("Accent");
        accentMenu.getItems().addAll(accentSteel, accentEmerald, accentAmber);

        var resetItem = new MenuItem("Réinitialiser la fenêtre");
        resetItem.setOnAction(e -> resetWindow());

        var aboutItem = new MenuItem("À propos…");
        aboutItem.setOnAction(e -> showAbout());

        var menu = new ContextMenu(
            lightItem, darkItem,
            new SeparatorMenuItem(),
            accentMenu,
            new SeparatorMenuItem(),
            resetItem,
            aboutItem
        );

        var btn = new Button();
        btn.getStyleClass().add("lamm-chrome-button");
        btn.setFocusTraversable(false);
        btn.setGraphic(LammChromeFx.settingsIcon());
        btn.setOnAction(e -> {
            boolean dark = LammThemeFx.isDark();
            lightItem.setSelected(!dark);
            darkItem.setSelected(dark);
            String accent = APP_PREFS.get(PREF_ACCENT, ACCENT_STEEL);
            accentSteel.setSelected(ACCENT_STEEL.equals(accent));
            accentEmerald.setSelected(ACCENT_EMERALD.equals(accent));
            accentAmber.setSelected(ACCENT_AMBER.equals(accent));
            menu.show(btn, Side.BOTTOM, 0, 4);
        });
        return btn;
    }
}
