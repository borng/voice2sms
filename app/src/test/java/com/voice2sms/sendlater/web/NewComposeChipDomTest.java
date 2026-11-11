package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Acceptance (newcompose-selector, D57 / H3; R10a, D46, OD36): the WebView edge tab on GV's
 * new-message page. Once a recipient chip exists, GV changes the To input's placeholder to
 * "Add recipients" (a11y: input mat-input-0 hint "Add recipients", chip mat-mdc-chip-0 with its
 * number + a Remove button). The page must still find that single chip, so the tab can appear.
 *
 * <p>JS-level: inject.js is evaluated by node in a vm context with a small fake DOM
 * ({@code src/test/resources/js/newcompose-dom-harness.js}); each test builds a GV-shaped page
 * and evaluates {@code _v2sSlNewComposeChip()} ({count, e164}) and {@code _v2sSlPageE164(st)}
 * (the number the tab binds to, null = no tab). There is no JS engine on the JVM; node is found on
 * PATH, under ~/.nvm, or at /usr/bin/node and /usr/local/bin/node (env V2S_NODE overrides).
 * Numbers are fictional (555-01xx).
 */
public class NewComposeChipDomTest {

    private static final Path INJECT = Paths.get("src/main/assets/inject.js");
    private static final Path HARNESS = Paths.get("src/test/resources/js/newcompose-dom-harness.js");

    private static final String CHIP = "+12025550123";
    private static final String OTHER_DIGITS = "2025550124";

    private static final String PH_BEFORE = "Type a name or phone number";
    private static final String PH_AFTER = "Add recipients";

    // ------------------------------------------------------------------ fixtures (GV-shaped)

    /** One mat-chip-row as Angular Material renders it: text label + trailing Remove button. */
    private static String chip(String id, String label, String removeButtonContent) {
        return "<mat-chip-row id=\"" + id + "\" class=\"mat-mdc-chip mat-mdc-chip-row mdc-evolution-chip"
                + " mdc-evolution-chip--with-trailing-action\" role=\"row\">"
                + "<span role=\"gridcell\" class=\"mdc-evolution-chip__cell mdc-evolution-chip__cell--primary\">"
                + "<span class=\"mdc-evolution-chip__action mat-mdc-chip-action mdc-evolution-chip__action--primary\""
                + " role=\"button\" tabindex=\"-1\">"
                + "<span class=\"mdc-evolution-chip__text-label mat-mdc-chip-action-label\">" + label + "</span>"
                + "</span></span>"
                + "<span role=\"gridcell\" class=\"mdc-evolution-chip__cell mdc-evolution-chip__cell--trailing\">"
                + "<button class=\"mat-mdc-chip-remove mat-mdc-chip-trailing-icon mdc-evolution-chip__action--trailing\""
                + " aria-label=\"Remove\">" + removeButtonContent + "</button>"
                + "</span></mat-chip-row>";
    }

    private static String chip(String id, String label) {
        return chip(id, label, "<mat-icon>cancel</mat-icon>");
    }

    /** The To field: chips + (optionally) the recipient input, inside mat-chip-grid. */
    private static String toField(String inputTag, String... chips) {
        StringBuilder b = new StringBuilder();
        b.append("<gv-recipient-picker><mat-form-field class=\"mat-mdc-form-field\">")
                .append("<div class=\"mat-mdc-text-field-wrapper\">")
                .append("<mat-chip-grid class=\"mat-mdc-chip-grid mdc-evolution-chip-set\" role=\"grid\">")
                .append("<div class=\"mdc-evolution-chip-set__chips\" role=\"presentation\">");
        for (String c : chips) b.append(c);
        b.append("</div>");
        if (inputTag != null) b.append(inputTag);
        b.append("</mat-chip-grid></div></mat-form-field></gv-recipient-picker>");
        return b.toString();
    }

    private static String input(String placeholder) {
        return "<input id=\"mat-input-0\" class=\"mat-mdc-chip-input mat-mdc-input-element\" type=\"text\""
                + (placeholder == null ? "" : " placeholder=\"" + placeholder + "\"") + ">";
    }

    /** GV's new-message page around the To field: side nav, search box, message box with a draft. */
    private static String page(String toField, String extra) {
        return "<html><body><gv-side-nav></gv-side-nav>"
                + "<header><input id=\"gv-search\" type=\"text\" placeholder=\"Search Google Voice\"></header>"
                + (extra == null ? "" : extra)
                + "<gv-new-message>" + toField
                + "<gv-message-entry><textarea placeholder=\"Type a message\">draft</textarea></gv-message-entry>"
                + "</gv-new-message></body></html>";
    }

    // ------------------------------------------------------------------ harness

    private static File node() {
        List<File> c = new ArrayList<>();
        String env = System.getenv("V2S_NODE");
        if (env != null && !env.isEmpty()) c.add(new File(env));
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) c.add(new File(d, "node"));
        File nvm = new File(System.getProperty("user.home"), ".nvm/versions/node");
        File[] vs = nvm.listFiles();
        if (vs != null) {
            Arrays.sort(vs);
            for (int i = vs.length - 1; i >= 0; i--) c.add(new File(vs[i], "bin/node"));
        }
        c.add(new File("/usr/bin/node"));
        c.add(new File("/usr/local/bin/node"));
        for (File f : c) if (f.isFile() && f.canExecute()) return f;
        fail("node not found (PATH, ~/.nvm, /usr/bin, /usr/local/bin; or set V2S_NODE): "
                + "this JS-level test evaluates inject.js with node");
        return null;
    }

    private static String evaluateInPage(String html, String search, String expr) throws Exception {
        assertTrue("missing " + INJECT.toAbsolutePath(), Files.isRegularFile(INJECT));
        assertTrue("missing " + HARNESS.toAbsolutePath(), Files.isRegularFile(HARNESS));
        JSONObject tc = new JSONObject();
        tc.put("html", html);
        tc.put("search", search == null ? "" : search);
        tc.put("expr", expr);
        Path f = Files.createTempFile("newcompose-case", ".json");
        try {
            Files.write(f, tc.toString().getBytes(StandardCharsets.UTF_8));
            ProcessBuilder pb = new ProcessBuilder(node().getAbsolutePath(),
                    HARNESS.toAbsolutePath().toString(), INJECT.toAbsolutePath().toString(), f.toString());
            Process p = pb.start();
            p.getOutputStream().close();
            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("node harness timed out");
            }
            assertEquals("node harness failed: " + err, 0, p.exitValue());
            return out;
        } finally {
            Files.deleteIfExists(f);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** [_v2sSlNewComposeChip(), _v2sSlPageE164({ncE164: CHIP})] on a new-message page (no itemId). */
    private static JSONArray chipAndTab(String html) throws Exception {
        String out = evaluateInPage(html, "",
                "[_v2sSlNewComposeChip(), _v2sSlPageE164({ncE164: '" + CHIP + "'})]");
        return new JSONArray(out);
    }

    private static void assertOneChip(String why, JSONArray r) throws Exception {
        JSONObject nc = r.getJSONObject(0);
        assertEquals(why + ": chip count", 1, nc.getInt("count"));
        assertEquals(why + ": the single chip's E.164", CHIP, nc.isNull("e164") ? null : nc.getString("e164"));
        assertEquals(why + ": the tab binds to the confirmed chip's E.164", CHIP,
                r.isNull(1) ? null : r.getString(1));
    }

    // ------------------------------------------------------------------ acceptance

    /** H3: after the chip exists GV's input placeholder is "Add recipients"; the chip is still found. */
    @Test
    public void chipFound_whenTheInputPlaceholderIsAddRecipients() throws Exception {
        assertOneChip("placeholder 'Add recipients'",
                chipAndTab(page(toField(input(PH_AFTER), chip("mat-mdc-chip-0", "2025550123")), null)));
    }

    /** H3: the chip grid is located directly, not only through a matching recipient input. */
    @Test
    public void chipFound_withNoMatchingInput_inputHasNoPlaceholder() throws Exception {
        assertOneChip("To input with no placeholder",
                chipAndTab(page(toField(input(null), chip("mat-mdc-chip-0", "2025550123")), null)));
    }

    @Test
    public void chipFound_withNoRecipientInputAtAll() throws Exception {
        assertOneChip("no recipient input in the To field",
                chipAndTab(page(toField(null, chip("mat-mdc-chip-0", "2025550123")), null)));
    }

    /** H3: chip textContent holds the number twice (label + Remove button text): one E.164. */
    @Test
    public void chipTextHoldingTheNumberTwice_yieldsExactlyThatE164() throws Exception {
        String c = chip("mat-mdc-chip-0", "2025550123",
                "<span class=\"cdk-visually-hidden\">Remove 2025550123</span><mat-icon>cancel</mat-icon>");
        assertOneChip("digits label + Remove text", chipAndTab(page(toField(input(PH_AFTER), c), null)));
    }

    @Test
    public void chipTextHoldingTheFormattedNumberTwice_yieldsExactlyThatE164() throws Exception {
        String c = chip("mat-mdc-chip-0", "(202) 555-0123",
                "<span class=\"cdk-visually-hidden\">Remove (202) 555-0123</span><mat-icon>cancel</mat-icon>");
        assertOneChip("formatted label + Remove text", chipAndTab(page(toField(input(PH_AFTER), c), null)));
    }

    /** H3 dedupe alone: the original placeholder, chip text holding the number twice. */
    @Test
    public void chipTextHoldingTheNumberTwice_withTheOriginalPlaceholder_yieldsExactlyThatE164() throws Exception {
        String c = chip("mat-mdc-chip-0", "2025550123",
                "<span class=\"cdk-visually-hidden\">Remove 2025550123</span><mat-icon>cancel</mat-icon>");
        assertOneChip("number twice, placeholder 'Type a name or phone number'",
                chipAndTab(page(toField(input(PH_BEFORE), c), null)));
    }

    /** R10a: exactly one recipient chip; two chips: no tab. */
    @Test
    public void twoChips_noTab() throws Exception {
        JSONArray r = chipAndTab(page(toField(input(PH_AFTER),
                chip("mat-mdc-chip-0", "2025550123"), chip("mat-mdc-chip-1", OTHER_DIGITS)), null));
        JSONObject nc = r.getJSONObject(0);
        assertEquals("two recipient chips are counted", 2, nc.getInt("count"));
        assertTrue("no single-chip number with two chips", nc.isNull("e164"));
        assertTrue("two chips: no tab (_v2sSlPageE164 null)", r.isNull(1));
    }

    /** R10a: only recipient chips count; a chip elsewhere on the page is not a recipient chip. */
    @Test
    public void chipOutsideTheToField_isNotARecipientChip() throws Exception {
        String filter = "<mat-chip-listbox class=\"mdc-evolution-chip-set\" role=\"listbox\">"
                + "<mat-chip-option class=\"mat-mdc-chip mat-mdc-chip-option mdc-evolution-chip\" role=\"option\">"
                + "<span class=\"mdc-evolution-chip__text-label\">Unread</span></mat-chip-option></mat-chip-listbox>";
        assertOneChip("other chip set on the page",
                chipAndTab(page(toField(input(PH_AFTER), chip("mat-mdc-chip-0", "2025550123")), filter)));
    }

    // ------------------------------------------------------------------ guards (stay green)

    /** The pre-H3 shape (placeholder still "Type a name or phone number") keeps working. */
    @Test
    public void chipFound_withTheOriginalPlaceholder() throws Exception {
        assertOneChip("placeholder 'Type a name or phone number'",
                chipAndTab(page(toField(input(PH_BEFORE), chip("mat-mdc-chip-0", "2025550123")), null)));
    }

    /** No chip yet: count 0, no tab. */
    @Test
    public void noChip_noTab() throws Exception {
        JSONArray r = chipAndTab(page(toField(input(PH_BEFORE)), null));
        assertEquals(0, r.getJSONObject(0).getInt("count"));
        assertTrue(r.getJSONObject(0).isNull("e164"));
        assertTrue(r.isNull(1));
    }

    /** A 1:1 thread page stays R10's: the thread's number, whatever chips say. */
    @Test
    public void threadPage_bindsToTheThreadNumber() throws Exception {
        String out = evaluateInPage(page(toField(input(PH_AFTER)), null), "?itemId=t.%2B12025550199",
                "_v2sSlPageE164({ncE164: '" + CHIP + "'})");
        assertEquals("\"+12025550199\"", out);
    }

    /**
     * Scheduled 'new' mode (_v2sScheduledNewMessage) types the recipient into the input that
     * V2S_SCHEDULED_RECIPIENT_SELECTOR picks first: on the fresh new-message page that is still the
     * To input, never the search box or the message box.
     */
    @Test
    public void scheduledRecipientSelector_stillPicksTheToInputOnAFreshNewMessagePage() throws Exception {
        String out = evaluateInPage(page(toField(input(PH_BEFORE)), null), "",
                "(function(){var e=document.querySelector(V2S_SCHEDULED_RECIPIENT_SELECTOR);"
                        + "return e ? e.id : null;})()");
        assertNotNull(out);
        assertEquals("\"mat-input-0\"", out);
    }

    @Test
    public void chipFieldE164_stillReadsOneNumber() throws Exception {
        String out = evaluateInPage(page("", null), "",
                "[_v2sSlChipFieldE164('2025550123'), _v2sSlChipFieldE164('(202) 555-0123'),"
                        + " _v2sSlChipFieldE164('+1 202 555 0123'), _v2sSlChipFieldE164('Alex')]");
        JSONArray r = new JSONArray(out);
        assertEquals(CHIP, r.getString(0));
        assertEquals(CHIP, r.getString(1));
        assertEquals(CHIP, r.getString(2));
        assertNull(r.isNull(3) ? null : r.get(3));
    }

    /**
     * LIVE (Pixel, rc7, 2026-11-11): GV's real chip label holds the number twice: the visible
     * aria-hidden {@code .chip-name} "(202) 555-0123" (wrapped in LRE/PDF marks) and a
     * {@code .cdk-visually-hidden} spelled-out copy "2 0 2 5 5 5 0 1 2 3" for screen readers.
     * Reading the whole label gives 20 digits, so no number and no tab.
     */
    @Test
    public void realGvChipWithVisuallyHiddenDigitsYieldsItsNumber() throws Exception {
        String realChip = "<mat-chip-row id=\"mat-mdc-chip-0\" class=\"mat-mdc-chip mat-mdc-chip-row"
                + " mdc-evolution-chip chip-row\" role=\"row\" aria-haspopup=\"menu\">"
                + "<span class=\"mat-mdc-chip-focus-overlay\"></span>"
                + "<span role=\"gridcell\" class=\"mat-mdc-chip-action mdc-evolution-chip__action"
                + " mdc-evolution-chip__cell mdc-evolution-chip__cell--primary\">"
                + "<span class=\"mdc-evolution-chip__text-label mat-mdc-chip-action-label\">"
                + "<div aria-hidden=\"true\" class=\"chip-name\"> \u202a(202) 555-0123\u202c </div>"
                + "<div class=\"cdk-visually-hidden\">2 0 2 5 5 5 0 1 2 3</div>"
                + "<span aria-hidden=\"true\" class=\"mat-mdc-chip-primary-focus-indicator\"></span>"
                + "</span><span class=\"mat-mdc-chip-touch-target\"></span></span>"
                + "<span role=\"gridcell\" class=\"mdc-evolution-chip__cell mdc-evolution-chip__cell--trailing\">"
                + "<button role=\"button\" class=\"mat-mdc-chip-remove mat-mdc-chip-trailing-icon\""
                + " type=\"button\" aria-label=\"Remove 2 0 2 5 5 5 0 1 2 3?\">"
                + "<mat-icon aria-hidden=\"true\">cancel</mat-icon></button></span></mat-chip-row>";
        String html = page(toField(input(PH_AFTER), realChip), null);
        assertOneChip("real GV chip (chip-name + visually-hidden digits)", chipAndTab(html));
        // The scheduled Send gate reads the same chip: one digit run must be the number itself.
        assertEquals("scheduled Send gate digit runs", "2025550123,,",
                evaluateInPage(html, "", "_v2sSlChipDigits(document.querySelector('mat-chip-row'))").trim()
                        .replaceAll("^\"|\"$", ""));
    }
}
