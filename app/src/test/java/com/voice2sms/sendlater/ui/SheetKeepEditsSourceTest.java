package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acceptance (sheet-keep-edits; D52, OD43, R12b, R12a): source-level checks on the sheet activity.
 * <ul>
 *   <li>onSaveInstanceState puts the model's {@code saveState()} into the Bundle (never the DB,
 *       never logs, never preferences);</li>
 *   <li>re-creation restores it ({@code restoreState(...)}) in capture mode after the model is
 *       rebuilt from the intent, and in edit mode only after the row passed the
 *       {@code SendLaterEngine.isEditable} re-check (a stale row shows the not-editable message
 *       and is not resurrected);</li>
 *   <li>the sheet's configChanges stay exactly orientation|screenSize|screenLayout|
 *       smallestScreenSize|keyboardHidden, so rotation and fold do not re-create it (uiMode is not
 *       handled, so a light/dark switch re-creates and relies on the saved state).</li>
 * </ul>
 * Paths are relative to the app module (Gradle runs unit tests there).
 */
public class SheetKeepEditsSourceTest {

    private static final Path ACTIVITY =
            Paths.get("src/main/java/com/voice2sms/sendlater/ui/SendLaterSheetActivity.java");
    private static final Path LOGIC =
            Paths.get("src/main/java/com/voice2sms/sendlater/ui/SheetViewModelLogic.java");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** Removes comments and string literals. */
    private static String code(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    /** Body (between the outer braces) of the first method declaration named {@code name}, or null. */
    private static String body(String src, String name) {
        Matcher m = Pattern.compile("(?:void|boolean|[A-Z]\\w*)\\s+" + Pattern.quote(name)
                + "\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{").matcher(src);
        if (!m.find()) return null;
        int depth = 1;
        int i = m.end();
        for (; i < src.length() && depth > 0; i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return src.substring(m.end(), i - 1);
    }

    /** Names of methods whose body calls {@code restoreState(} (the restore helper, if any). */
    private static Set<String> restoringMethods(String src) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("(?:private|protected|public|static|final|\\s)+"
                + "(?:void|boolean|[A-Z]\\w*)\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{").matcher(src);
        while (m.find()) {
            String b = body(src.substring(m.start()), m.group(1));
            if (b != null && Pattern.compile("\\brestoreState\\s*\\(").matcher(b).find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** Index of the first call, in {@code b}, of restoreState or of a method that calls it; -1 if none. */
    private static int firstRestore(String b, Set<String> helpers) {
        int best = -1;
        List<String> names = new ArrayList<>(helpers);
        names.add("restoreState");
        for (String n : names) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(n) + "\\s*\\(").matcher(b);
            if (m.find() && (best < 0 || m.start() < best)) best = m.start();
        }
        return best;
    }

    // ------------------------------------------------------------------ save

    @Test public void onSaveInstanceStatePutsTheModelStateIntoTheBundle() throws IOException {
        String a = code(read(ACTIVITY));
        String b = body(a, "onSaveInstanceState");
        assertTrue("SendLaterSheetActivity overrides onSaveInstanceState(Bundle)", b != null);
        assertTrue("calls super.onSaveInstanceState",
                Pattern.compile("super\\s*\\.\\s*onSaveInstanceState\\s*\\(").matcher(b).find());
        assertTrue("saves the model's in-progress state: " + b,
                Pattern.compile("\\.\\s*saveState\\s*\\(\\s*\\)").matcher(b).find());
        assertTrue("writes into the Bundle", Pattern.compile("\\.\\s*put\\w*\\s*\\(").matcher(b).find()
                || Pattern.compile("\\w+\\s*\\(\\s*\\w+\\s*,").matcher(b).find());
    }

    @Test public void unsavedStateGoesOnlyIntoTheBundle() throws IOException {
        String a = code(read(ACTIVITY));
        String b = body(a, "onSaveInstanceState");
        assertTrue("onSaveInstanceState exists", b != null);
        for (String bad : new String[] {"Repository", "SendLaterEngine", "Dao", "SharedPreferences",
                "getSharedPreferences", "SendLaterSettings", "Log."}) {
            assertFalse("unsaved sheet state never reaches the DB, preferences or logs (" + bad + ")",
                    b.contains(bad));
        }
    }

    // ------------------------------------------------------------------ restore

    @Test public void captureModeRestoresAfterRebuildingTheModelFromTheIntent() throws IOException {
        String a = code(read(ACTIVITY));
        Set<String> helpers = restoringMethods(a);
        String cap = body(a, "setupCapture");
        String create = body(a, "onCreate");
        assertTrue("setupCapture and onCreate exist", cap != null && create != null);
        int inCapture = firstRestore(cap, helpers);
        int built = cap.indexOf("forCapture(");
        boolean restoredInCapture = inCapture >= 0 && built >= 0 && inCapture > built;
        // Or: onCreate restores after setupCapture(...) built the model.
        int setup = create.indexOf("setupCapture(");
        int inCreate = firstRestore(create.substring(Math.max(setup, 0)), helpers);
        boolean restoredAfterSetup = setup >= 0 && inCreate >= 0;
        assertTrue("capture mode restores the saved state over the model built from the intent",
                restoredInCapture || restoredAfterSetup);
        assertTrue("onCreate hands the saved state to the setup path",
                Pattern.compile("setupCapture\\s*\\([^;]*savedInstanceState").matcher(create).find()
                        || Pattern.compile("\\w+\\s*=\\s*savedInstanceState").matcher(a).find()
                        || restoredAfterSetup);
    }

    @Test public void editModeRestoresOnlyAfterTheEditableRecheck() throws IOException {
        String a = code(read(ACTIVITY));
        Set<String> helpers = restoringMethods(a);
        String edit = body(a, "setupEdit");
        assertTrue("setupEdit exists", edit != null);
        int check = edit.indexOf("SendLaterEngine.isEditable(");
        int refused = edit.indexOf("showNotEditable(");
        int built = edit.indexOf("forEdit(");
        int restored = firstRestore(edit, helpers);
        assertTrue("edit re-creation still re-checks SendLaterEngine.isEditable", check >= 0);
        assertTrue("a stale row still shows the not-editable message", refused > check);
        assertTrue("edit mode restores the saved edits in setupEdit (directly or via a helper): "
                + helpers, restored >= 0);
        assertTrue("restore happens after the not-editable refusal branch, never instead of it",
                restored > refused);
        assertTrue("restore is applied over the model rebuilt from the reloaded row", restored > built);
    }

    @Test public void editRecreationDoesNotSkipTheEditSetup() throws IOException {
        String a = code(read(ACTIVITY));
        String create = body(a, "onCreate");
        assertTrue(create != null);
        Matcher m = Pattern.compile("ACTION_EDIT\\s*\\.\\s*equals\\s*\\(\\s*action\\s*\\)\\s*\\)\\s*\\{([^}]*)\\}")
                .matcher(create);
        assertTrue("onCreate has an ACTION_EDIT branch", m.find());
        assertTrue("ACTION_EDIT always goes through setupEdit (re-check + reload), also on re-creation",
                m.group(1).contains("setupEdit("));
        Set<String> helpers = restoringMethods(a);
        helpers.remove("setupEdit");
        helpers.remove("setupCapture");
        assertEquals("the edit branch does not restore on its own, outside setupEdit's re-check",
                -1, firstRestore(m.group(1), helpers));
    }

    // ------------------------------------------------------------------ no text in logs

    @Test public void modelHasNoLogging() throws IOException {
        String l = code(read(LOGIC));
        assertFalse("SheetViewModelLogic stays pure JVM, no Log", l.contains("android.util.Log"));
        assertFalse(Pattern.compile("\\bLog\\s*\\.\\s*[vdiwe]\\s*\\(").matcher(l).find());
        assertFalse(l.contains("System.out") || l.contains("System.err"));
    }

    @Test public void sheetLogsCarryNoSavedState() throws IOException {
        String a = code(read(ACTIVITY));
        Matcher m = Pattern.compile("\\bLog\\.[vdiwe]\\s*\\(([^;]*)\\)\\s*;").matcher(a);
        while (m.find()) {
            String args = m.group(1).toLowerCase()
                    .replaceAll("\\btag\\b", "")
                    .replaceAll("\\w+\\s*\\.\\s*getclass\\s*\\(\\s*\\)\\s*\\.\\s*getsimplename\\s*\\(\\s*\\)", "");
            for (String bad : new String[] {"savedinstancestate", "outstate", "bundle", "savestate",
                    "saved", "gettext(", "text", "draft"}) {
                assertFalse("Log call may carry unsaved sheet state (" + bad + "): " + m.group(),
                        args.contains(bad));
            }
        }
    }

    // ------------------------------------------------------------------ manifest

    @Test public void sheetConfigChangesPinRotationAndFoldWithoutRecreation() throws IOException {
        String mf = read(MANIFEST);
        Matcher act = Pattern.compile("<activity\\b[^>]*SendLaterSheetActivity[^>]*>", Pattern.DOTALL)
                .matcher(mf);
        assertTrue("manifest declares SendLaterSheetActivity", act.find());
        Matcher cc = Pattern.compile("android:configChanges\\s*=\\s*\"([^\"]*)\"").matcher(act.group());
        assertTrue("the sheet declares android:configChanges", cc.find());
        Set<String> got = new HashSet<>(Arrays.asList(cc.group(1).trim().split("\\s*\\|\\s*")));
        for (String need : new String[] {"orientation", "screenSize", "screenLayout", "smallestScreenSize"}) {
            assertTrue("rotation/fold handled without re-creation: " + need + " in " + got, got.contains(need));
        }
        assertEquals("configChanges kept exactly as is (uiMode re-creates for dark mode)",
                new HashSet<>(Arrays.asList("orientation", "screenSize", "screenLayout",
                        "smallestScreenSize", "keyboardHidden")), got);
    }
}
