package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.store.SendLaterRepository;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acceptance (edit-text; D49, OD40, R12a, R12, OD31): source-level checks that the edited text
 * reaches the store in the same guarded UPDATE as the time, hold and If-late (no separate text
 * write that could race the IN_FLIGHT claim), that the Room schema does not change, that the
 * Android store seam forwards the text, and that the sheet's Update hands the edited text to the
 * engine while capture mode keeps its quoted, read-only draft. Paths are relative to the app
 * module (Gradle runs unit tests there).
 */
public class EditTextSourceTest {

    private static final Path SL = Paths.get("src/main/java/com/voice2sms/sendlater");
    private static final Path DAO = SL.resolve("store/SendLaterDao.java");
    private static final Path DB = SL.resolve("store/SendLaterDatabase.java");
    private static final Path REPO = SL.resolve("store/SendLaterRepository.java");
    private static final Path ANDROID_DEPS = SL.resolve("engine/AndroidEngineDeps.java");
    private static final Path ACTIVITY = SL.resolve("ui/SendLaterSheetActivity.java");
    private static final Path LAYOUT = Paths.get("src/main/res/layout/sendlater_sheet.xml");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** Removes comments (keeps string literals: the DAO's SQL lives in them). */
    private static String noComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /** Removes comments and string literals. */
    private static String code(String s) {
        return noComments(s).replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    /** Each @Query's SQL, string-literal pieces concatenated. */
    private static List<String> queries(String dao) {
        List<String> out = new ArrayList<>();
        Matcher q = Pattern.compile("@Query\\s*\\((.*?)\\)\\s*\\n", Pattern.DOTALL).matcher(dao);
        while (q.find()) {
            StringBuilder sql = new StringBuilder();
            Matcher lit = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(q.group(1));
            while (lit.find()) sql.append(lit.group(1));
            out.add(sql.toString().replaceAll("\\s+", " "));
        }
        return out;
    }

    /** Top-level argument count of the first call matching {@code callRegex} (which ends at '('). */
    private static int argCount(String src, String callRegex) {
        Matcher m = Pattern.compile(callRegex).matcher(src);
        if (!m.find()) return -1;
        int depth = 1;
        int commas = 0;
        boolean any = false;
        for (int i = m.end(); i < src.length() && depth > 0; i++) {
            char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 1) commas++;
            if (depth > 0 && !Character.isWhitespace(c)) any = true;
        }
        return any ? commas + 1 : 0;
    }

    // ------------------------------------------------------------------ store: one guarded UPDATE

    @Test public void rescheduleUpdateSetsTheTextInTheSameStatusGuardedStatement() throws IOException {
        List<String> resched = new ArrayList<>();
        for (String sql : queries(noComments(read(DAO)))) {
            String s = sql.toLowerCase();
            if (s.startsWith("update scheduled_message") && s.contains("scheduledfor = :")
                    && s.contains("holdifreplied = :")) {
                resched.add(s);
            }
        }
        String s = null;
        for (String r : resched) {
            if (Pattern.compile("[ ,]text = :\\w+").matcher(r).find()) s = r;
        }
        assertTrue("a reschedule UPDATE sets the text with the time and hold: " + resched, s != null);
        assertTrue("same SCHEDULED/HELD guard: " + s, s.contains("where id = :id and status in (:"));
        assertTrue("same statement stores If-late: " + s, s.contains("latenesspolicy = :"));
    }

    @Test public void noOtherStatementWritesTheMessageText() throws IOException {
        for (String sql : queries(noComments(read(DAO)))) {
            String s = sql.toLowerCase();
            if (!s.startsWith("update scheduled_message")) continue;
            if (!Pattern.compile("[ ,]text = :\\w+").matcher(s).find()) continue;
            assertTrue("a text write outside the reschedule UPDATE could race the IN_FLIGHT claim: " + s,
                    s.contains("scheduledfor = :") && s.contains("status in (:"));
        }
    }

    @Test public void roomSchemaVersionIsUnchanged() throws IOException {
        String db = code(read(DB));
        assertTrue("no Room schema change for edit-text (text column exists): version stays 2",
                Pattern.compile("version\\s*=\\s*2\\b").matcher(db).find());
    }

    @Test public void repositoryRescheduleTakesTheText() {
        Method m;
        try {
            m = SendLaterRepository.class.getMethod("reschedule", long.class, long.class, boolean.class,
                    LatenessPolicy.class, Watermark.class, String.class);
        } catch (NoSuchMethodException e) {
            fail("missing SendLaterRepository.reschedule(long, long, boolean, LatenessPolicy, Watermark, String text)");
            return;
        }
        assertEquals(boolean.class, m.getReturnType());
        assertTrue(Modifier.isPublic(m.getModifiers()));
    }

    @Test public void androidStoreSeamForwardsTheText() throws IOException {
        String deps = code(read(ANDROID_DEPS));
        Matcher m = Pattern.compile("public\\s+boolean\\s+reschedule\\s*\\(([^)]*)\\)\\s*\\{([^}]*)\\}")
                .matcher(deps);
        boolean found = false;
        while (m.find()) {
            String params = m.group(1);
            if (!params.contains("String")) continue;
            found = true;
            String textParam = params.substring(params.lastIndexOf("String") + 6).trim();
            String body = m.group(2);
            assertTrue("forwards " + textParam + " to the repository: " + body,
                    Pattern.compile("reschedule\\s*\\([^;]*\\b" + Pattern.quote(textParam) + "\\b").matcher(body).find());
        }
        assertTrue("AndroidEngineDeps overrides EngineStore.reschedule(..., String text)", found);
    }

    // ------------------------------------------------------------------ sheet

    @Test public void sheetUpdatePassesTheEditedTextToTheEngine() throws IOException {
        String a = code(read(ACTIVITY));
        assertEquals("Update calls SendLaterEngine.reschedule(context, id, when, hold, policy, baseline, text)",
                7, argCount(a, "SendLaterEngine\\s*\\.\\s*reschedule\\s*\\("));
        assertTrue("the edit field feeds the model's editable text",
                Pattern.compile("logic\\s*\\.\\s*setText\\s*\\(").matcher(a).find());
        assertTrue("Update reads the edited text from the model",
                Pattern.compile("logic\\s*\\.\\s*getText\\s*\\(\\s*\\)").matcher(a).find());
        assertTrue("edit mode only: the activity asks the model whether the text is editable",
                Pattern.compile("\\.\\s*isTextEditable\\s*\\(\\s*\\)").matcher(a).find());
    }

    @Test public void editModeTextIsAMultiLineEditText() throws IOException {
        String both = read(ACTIVITY) + read(LAYOUT);
        assertTrue("an EditText holds the editable text", both.contains("EditText"));
        assertTrue("multi-line input",
                both.contains("textMultiLine") || both.contains("TYPE_TEXT_FLAG_MULTI_LINE"));
    }

    @Test public void captureModeKeepsTheQuotedReadOnlyDraft() throws IOException {
        String a = read(ACTIVITY);
        assertTrue("capture mode still shows the quoted draft",
                a.contains("R.string.sendlater_sheet_draft_quoted"));
        assertTrue("empty text still shows the existing empty-draft message",
                a.contains("R.string.sendlater_sheet_empty_draft"));
    }

    @Test public void sheetNeverLogsTheEditedText() throws IOException {
        String a = code(read(ACTIVITY));
        Matcher m = Pattern.compile("\\bLog\\.[vdiwe]\\s*\\(([^;]*)\\)\\s*;").matcher(a);
        while (m.find()) {
            String args = m.group(1).toLowerCase();
            for (String bad : new String[] {"gettext(", "text", "draft", "edit"}) {
                // tag and exception class names are fine; any text-bearing variable is not
                String stripped = args.replaceAll("\\btag\\b", "")
                        .replaceAll("\\w+\\s*\\.\\s*getclass\\s*\\(\\s*\\)\\s*\\.\\s*getsimplename\\s*\\(\\s*\\)", "");
                assertFalse("Log call may carry message text (" + bad + "): " + m.group(),
                        stripped.contains(bad));
            }
        }
    }
}
