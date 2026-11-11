package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Acceptance (home-redesign; D55, H2, R35a, R36a, R36b, D58): the launcher page
 * (SettingsActivity root) matches the approved mockup. Pure source/resource checks; the device
 * screenshots (light / dark / empty) are the orchestrator's.
 *
 * <p><b>Where things are looked up</b> (no new names are pinned beyond these):
 * <ul>
 *   <li>Card layout: {@code res/layout/sendlater_home_card.xml} (already pinned by
 *       DarkSettingsThemeTest).</li>
 *   <li>"Card sources": main Java files that reference {@code ScheduledCardModel} or
 *       {@code R.layout.sendlater_home_card}.</li>
 *   <li>"Row layouts": every other {@code R.layout.X} named in the card sources, plus layouts
 *       {@code <include>}d by the card layout (recursively).</li>
 *   <li>Strings: {@code res/values/sendlater_*.xml} (strings.xml is never edited).</li>
 *   <li>Colours: any {@code res/values/*.xml} / {@code res/values-night/*.xml}; {@code @color}
 *       references are resolved recursively.</li>
 *   <li>Icons are drawables whose resource name names the icon: clock (clock|schedule|alarm),
 *       chat bubble (chat|bubble|message|sms), person (person|account), sparkle
 *       (sparkle|spark|star|auto_awesome|gemini), bell (bell|notification), info (info), chevron
 *       (chevron|navigate_next|arrow_right|arrow_forward_ios).</li>
 *   <li>Group row icon: {@code android:icon}/{@code app:icon} on the group's PreferenceScreen in
 *       preferences.xml, or (fallback) a {@code setIcon(...R.drawable.X...)} in main Java with a
 *       matching name. Group chevron: the group's {@code widgetLayout}/{@code layout} attribute,
 *       or (fallback) main Java applying a chevron layout with
 *       {@code setWidgetLayoutResource}/{@code setLayoutResource}.</li>
 * </ul>
 */
public class HomeRedesignSourceTest {

    private static final Path MAIN_DIR = resolveMain();
    private static final Path JAVA = MAIN_DIR.resolve("java/com/voice2sms");
    private static final Path RES = MAIN_DIR.resolve("res");
    private static final Path LAYOUT = RES.resolve("layout");
    private static final Path DRAWABLE = RES.resolve("drawable");
    private static final Path CARD_LAYOUT = LAYOUT.resolve("sendlater_home_card.xml");
    private static final Path ROOT_PREFS = RES.resolve("xml/preferences.xml");

    private static final String CARD_BG_LIGHT = "E8F0FE";
    private static final String CARD_BG_DARK = "1F2633";
    private static final String HELD_BG_LIGHT = "FDE7C2";
    private static final String HELD_TEXT_LIGHT = "7A4300";
    private static final String HELD_BG_DARK = "5C3B00";
    private static final String HELD_TEXT_DARK = "FFD27A";

    static final String EMPTY_TITLE = "Nothing scheduled";
    static final String EMPTY_HOW = "In any one-to-one conversation, start typing. A blue clock tab "
            + "appears on the right edge. Tap it and pick a time.";
    static final String EMPTY_SCOPE = "Works in Google Voice and in voice2sms. Not in group conversations.";

    /** Colour names that existed before this node (base 3a09a5d). Anything else is "new". */
    private static final Set<String> BASELINE_COLOURS = new HashSet<>(Arrays.asList(
            "sendlater_a11y_banner_bg", "sendlater_a11y_banner_text", "sendlater_a11y_warn",
            "sendlater_accent", "sendlater_accent_soft", "sendlater_accent_text",
            "sendlater_battery_ok", "sendlater_battery_warn", "sendlater_on_accent",
            "sendlater_on_accent_text", "sendlater_scrim", "sendlater_section_header",
            "sendlater_setup_badge_bg", "sendlater_setup_badge_text", "sendlater_setup_card_bg",
            "sendlater_setup_code_bg", "sendlater_setup_code_text", "sendlater_setup_footer_text",
            "sendlater_setup_future_badge_bg", "sendlater_setup_future_bg",
            "sendlater_setup_future_text", "sendlater_setup_page_bg", "sendlater_setup_status_off",
            "sendlater_setup_status_ok", "sendlater_setup_text_body", "sendlater_setup_text_hint",
            "sendlater_setup_text_subtitle", "sendlater_setup_text_title",
            "sendlater_upcoming_banner_bg", "sendlater_upcoming_banner_text"));

    /** Group row key -> accepted icon-name words. */
    private static final Map<String, List<String>> GROUP_ICONS = new LinkedHashMap<>();

    static {
        GROUP_ICONS.put("group_send_later", Arrays.asList("clock", "schedule", "alarm"));
        GROUP_ICONS.put("group_default_sms", Arrays.asList("chat", "bubble", "message", "sms"));
        GROUP_ICONS.put("group_google_account", Arrays.asList("person", "account"));
        GROUP_ICONS.put("group_gemini", Arrays.asList("sparkle", "spark", "star", "auto_awesome", "gemini"));
        GROUP_ICONS.put("group_notifications", Arrays.asList("bell", "notification"));
        GROUP_ICONS.put("group_about", Arrays.asList("info"));
    }

    private static final List<String> CLOCK = Arrays.asList("clock", "schedule", "alarm");
    private static final List<String> CHEVRON =
            Arrays.asList("chevron", "navigate_next", "arrow_right", "arrow_forward_ios");

    private static final List<String> DRAWABLE_ATTRS = Arrays.asList(
            "android:src", "app:srcCompat", "android:background", "android:drawableStart",
            "android:drawableEnd", "android:drawableLeft", "android:drawableRight",
            "app:drawableStartCompat", "app:drawableEndCompat", "app:icon", "android:icon",
            "android:foreground", "android:drawable");

    // =================================================================== card surface

    @Test
    public void cardIsARoundedSurfaceInTheMockupColoursOrASurfaceContainerToken() throws Exception {
        Document card = parse(CARD_LAYOUT);
        boolean rounded = false;
        boolean coloured = false;
        List<String> seen = new ArrayList<>();
        for (Element e : elements(card)) {
            String radius = attr(e, "app:cardCornerRadius");
            if (radius != null && dp(radius) > 0) rounded = true;
            for (String a : Arrays.asList("android:background", "app:cardBackgroundColor",
                    "android:backgroundTint", "app:backgroundTint")) {
                String v = attr(e, a);
                if (v == null) continue;
                seen.add(a + "=" + v);
                if (isCardColour(v)) coloured = true;
                if (v.startsWith("@drawable/")) {
                    for (Document d : drawableTree(v.substring(10))) {
                        for (Element de : elements(d)) {
                            if (de.getTagName().equals("corners")) {
                                for (String ca : Arrays.asList("android:radius", "android:topLeftRadius",
                                        "android:topRightRadius", "android:bottomLeftRadius",
                                        "android:bottomRightRadius")) {
                                    String r = attr(de, ca);
                                    if (r != null && dp(r) > 0) rounded = true;
                                }
                            }
                            if (de.getTagName().equals("solid")) {
                                String c = attr(de, "android:color");
                                if (c != null && isCardColour(c)) coloured = true;
                            }
                        }
                    }
                }
            }
        }
        assertTrue("card has a rounded corner radius (shape <corners> or app:cardCornerRadius); saw "
                + seen, rounded);
        assertTrue("card surface is light #" + CARD_BG_LIGHT + " / dark #" + CARD_BG_DARK
                + " (same @color, values-night) or ?attr/colorSurfaceContainer*; saw " + seen, coloured);
    }

    @Test
    public void cardHeaderHasAClockIconTitleAndAnOnOffPill() throws Exception {
        String cardXml = read(CARD_LAYOUT);
        Set<String> drawables = drawableRefs(cardXml);
        drawables.addAll(javaRefs(cardSources(), "drawable"));
        assertTrue("card shows a clock icon; drawables " + drawables, anyNamed(drawables, CLOCK));

        Set<String> strings = stringRefs(cardXml);
        strings.addAll(javaRefs(cardSources(), "string"));
        Map<String, String> values = strings();
        assertTrue("card title 'Scheduled messages'", referencesValue(strings, values, "Scheduled messages"));
        assertTrue("pill label 'On' referenced by the card; refs " + strings, referencesValue(strings, values, "On"));
        assertTrue("pill label 'Off' referenced by the card; refs " + strings, referencesValue(strings, values, "Off"));
    }

    @Test
    public void pillIsAtLeast44dpAccentFilledAndOpensTheSendLaterGroup() throws Exception {
        Document card = parse(CARD_LAYOUT);
        Element pill = null;
        for (Element e : elements(card)) {
            if (!isTextLike(e)) continue;
            if (!fillsWithAccent(e)) continue;
            double h = Math.max(dpOrZero(attr(e, "android:minHeight")),
                    Math.max(dpOrZero(attr(e, "android:layout_height")), dpOrZero(attr(e, "android:minimumHeight"))));
            if (h >= 44) {
                pill = e;
                break;
            }
        }
        assertNotNull("card layout has a >=44dp button/text view with an accent fill "
                + "(background/backgroundTint -> a *accent* colour)", pill);

        String src = joined(cardSources());
        assertTrue("pill tap goes to the Send Later group (listener.onSwitchStateTapped)",
                src.contains("onSwitchStateTapped"));
        String settings = noComments(read(JAVA.resolve("SettingsActivity.java")));
        assertTrue("SettingsActivity opens group_send_later from the card",
                Pattern.compile("openGroup\\(\\s*KEY_GROUP_SEND_LATER\\s*\\)").matcher(settings).find());
    }

    // =================================================================== rows

    @Test
    public void rowsHaveATimeColumnBoldNameEllipsizedFirstLineAndChevron() throws Exception {
        List<Path> rows = rowLayouts();
        assertFalse("the card inflates or includes a row layout", rows.isEmpty());
        List<Element> texts = new ArrayList<>();
        Set<String> drawables = new LinkedHashSet<>();
        for (Path p : rows) {
            Document d = parse(p);
            for (Element e : elements(d)) if (isTextLike(e)) texts.add(e);
            drawables.addAll(drawableRefs(read(p)));
        }
        List<Element> bold = texts.stream().filter(HomeRedesignSourceTest::isBold).collect(Collectors.toList());
        assertTrue("time and name are bold (>=2 bold text views in " + names(rows) + ")", bold.size() >= 2);

        boolean ellipsized = false;
        for (Element e : texts) {
            boolean end = "end".equals(attr(e, "android:ellipsize"));
            boolean oneLine = "1".equals(attr(e, "android:maxLines")) || "true".equals(attr(e, "android:singleLine"))
                    || "1".equals(attr(e, "android:lines"));
            if (end && oneLine) ellipsized = true;
        }
        assertTrue("first line of text is one line, ellipsized at the end", ellipsized);

        double maxBold = 0;
        for (Element e : bold) maxBold = Math.max(maxBold, dpOrZero(attr(e, "android:textSize")));
        boolean smallDay = false;
        for (Element e : texts) {
            double s = dpOrZero(attr(e, "android:textSize"));
            if (!isBold(e) && s > 0 && s < maxBold) smallDay = true;
        }
        assertTrue("time column: a small (non-bold, smaller textSize) day label above the bold time", smallDay);

        assertTrue("each row ends in a chevron; drawables " + drawables, anyNamed(drawables, CHEVRON));
    }

    @Test
    public void heldRowsGetAnAmberHeldTagAndTheReasonAsSecondLine() throws Exception {
        String bg = colourNamed(HELD_BG_LIGHT, HELD_BG_DARK);
        String fg = colourNamed(HELD_TEXT_LIGHT, HELD_TEXT_DARK);
        assertNotNull("a colour that is #" + HELD_BG_LIGHT + " light / #" + HELD_BG_DARK + " night", bg);
        assertNotNull("a colour that is #" + HELD_TEXT_LIGHT + " light / #" + HELD_TEXT_DARK + " night", fg);

        StringBuilder used = new StringBuilder(joined(cardSources()));
        for (Path p : rowLayouts()) {
            String xml = read(p);
            used.append(xml);
            for (String d : drawableRefs(xml)) for (Path f : drawableFiles(d)) used.append(read(f));
        }
        String all = used.toString();
        assertTrue("held tag background colour used by the row or card", usesColour(all, bg));
        assertTrue("held tag text colour used by the row or card", usesColour(all, fg));

        Set<String> refs = javaRefs(cardSources(), "string");
        for (Path p : rowLayouts()) refs.addAll(stringRefs(read(p)));
        assertTrue("tag text 'Held'", referencesValue(refs, strings(), "Held"));
        String src = joined(cardSources());
        assertTrue("held reason line from UpcomingActivity.reasonRes(getHoldReason())",
                src.contains("reasonRes(") && src.contains("getHoldReason()"));
    }

    @Test
    public void seeAllIsAFullWidthSeeAllNScheduledButton() throws Exception {
        Map<String, String> plurals = pluralsOther();
        Pattern want = Pattern.compile("See all %(1\\$)?d scheduled( ?\u203A)?");
        Set<String> matching = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : plurals.entrySet()) {
            if (want.matcher(e.getValue()).matches()) matching.add(e.getKey());
        }
        assertFalse("a plurals 'other' = 'See all %d scheduled' (optionally ' \u203A'); have " + plurals,
                matching.isEmpty());
        Set<String> used = javaRefs(cardSources(), "plurals");
        used.retainAll(matching);
        assertFalse("card uses that plural", used.isEmpty());

        Element seeAll = null;
        for (Element e : elements(parse(CARD_LAYOUT))) {
            String id = attr(e, "android:id");
            if (id != null && id.toLowerCase(Locale.ROOT).contains("see_all")) seeAll = e;
        }
        assertNotNull("card layout has a *see_all* view", seeAll);
        String w = attr(seeAll, "android:layout_width");
        assertTrue("See all spans the card width: " + w,
                "match_parent".equals(w) || "fill_parent".equals(w) || "0dp".equals(w));
    }

    // =================================================================== empty state

    @Test
    public void emptyStateHasTitleHowToIllustrationAndScopeLine() throws Exception {
        Map<String, String> values = strings();
        String cardXml = read(CARD_LAYOUT);
        Set<String> refs = stringRefs(cardXml);
        refs.addAll(javaRefs(cardSources(), "string"));
        assertTrue("'" + EMPTY_TITLE + "'", referencesValue(refs, values, EMPTY_TITLE));
        assertTrue("how-to text", referencesValue(refs, values, EMPTY_HOW));
        assertTrue("scope line", referencesValue(refs, values, EMPTY_SCOPE));

        String stringsXml = read(RES.resolve("values/strings.xml"));
        assertFalse("strings.xml untouched", stringsXml.contains("one-to-one conversation"));

        boolean illustration = false;
        List<String> images = new ArrayList<>();
        for (Element e : elements(parse(CARD_LAYOUT))) {
            if (!e.getTagName().endsWith("ImageView")) continue;
            for (String a : Arrays.asList("android:src", "app:srcCompat")) {
                String v = attr(e, a);
                if (v == null || !v.startsWith("@drawable/")) continue;
                String name = v.substring(10);
                images.add(name);
                if (name.equals("sendlater_ic_clock")) continue;
                if (!(name.contains("tab") || name.contains("illustration") || name.contains("empty"))) continue;
                List<Path> files = drawableFiles(name);
                if (!files.isEmpty() && isVectorish(files.get(0))) illustration = true;
            }
        }
        assertTrue("card has an ImageView with a static vector illustration of the tab "
                + "(*tab*|*illustration*|*empty* drawable); images " + images, illustration);
    }

    // =================================================================== settings rows

    @Test
    public void settingsSectionLabelIsTealAndHoldsTheSixGroupRows() throws Exception {
        Document prefs = parse(ROOT_PREFS);
        Element root = prefs.getDocumentElement();
        Element category = null;
        for (Element c : children(root)) {
            if (!c.getTagName().endsWith("PreferenceCategory")) continue;
            Set<String> keys = new HashSet<>();
            for (Element d : elements(c)) {
                String k = attr(d, "android:key");
                if (k != null) keys.add(k);
            }
            if (keys.containsAll(GROUP_ICONS.keySet())) category = c;
        }
        assertNotNull("a top-level PreferenceCategory wraps all six group rows", category);

        String title = resolveString(attr(category, "android:title"));
        assertNotNull("section has a title", title);
        assertTrue("section title is Settings: " + title, title.equalsIgnoreCase("settings"));

        String layoutAttr = firstNonNull(attr(category, "android:layout"), attr(category, "app:layout"));
        assertNotNull("section label uses its own layout (teal label)", layoutAttr);
        assertTrue(layoutAttr.startsWith("@layout/"));
        String labelXml = read(LAYOUT.resolve(layoutAttr.substring(8) + ".xml"));
        assertTrue("label colour @color/sendlater_section_header",
                labelXml.contains("@color/sendlater_section_header"));
        assertTrue("label reads SETTINGS (upper case title or textAllCaps)",
                title.equals("SETTINGS") || labelXml.contains("android:textAllCaps=\"true\""));
    }

    @Test
    public void everyGroupRowHasItsMaterialVectorIconAndAChevron() throws Exception {
        Document prefs = parse(ROOT_PREFS);
        Map<String, Element> groups = groupElements(prefs);
        String java = joined(mainJava());
        Set<String> javaIcons = new HashSet<>();
        if (java.contains("setIcon(")) javaIcons.addAll(javaRefs(mainJava(), "drawable"));
        Set<String> chevronLayouts = chevronLayouts();
        boolean javaChevron = false;
        if (java.contains("setWidgetLayoutResource(") || java.contains("setLayoutResource(")) {
            Set<String> l = javaRefs(mainJava(), "layout");
            l.retainAll(chevronLayouts);
            javaChevron = !l.isEmpty();
        }

        for (Map.Entry<String, List<String>> g : GROUP_ICONS.entrySet()) {
            Element e = groups.get(g.getKey());
            assertNotNull("group row " + g.getKey(), e);
            String icon = firstNonNull(attr(e, "android:icon"), attr(e, "app:icon"));
            boolean ok = false;
            if (icon != null && icon.startsWith("@drawable/")) {
                String name = icon.substring(10);
                assertTrue(g.getKey() + " icon " + name + " is one of " + g.getValue(),
                        anyNamed(Arrays.asList(name), g.getValue()));
                List<Path> files = drawableFiles(name);
                assertFalse(g.getKey() + " icon file " + name, files.isEmpty());
                assertTrue(g.getKey() + " icon is a vector drawable", isVectorish(files.get(0)));
                ok = true;
            } else {
                ok = anyNamed(javaIcons, g.getValue());
            }
            assertTrue(g.getKey() + " has a " + g.getValue() + " icon", ok);

            String w = firstNonNull(attr(e, "android:widgetLayout"), attr(e, "app:widgetLayout"),
                    attr(e, "android:layout"), attr(e, "app:layout"));
            boolean chevron = w != null && w.startsWith("@layout/") && chevronLayouts.contains(w.substring(8));
            assertTrue(g.getKey() + " shows a chevron (widgetLayout/layout with a chevron drawable)",
                    chevron || javaChevron);
        }
    }

    @Test
    public void noTopLevelRowHasAnEmptyIconIndent() throws Exception {
        Element root = parse(ROOT_PREFS).getDocumentElement();
        for (Element c : children(root)) {
            if ("home_scheduled_card".equals(attr(c, "android:key"))) continue;
            boolean icon = attr(c, "android:icon") != null || attr(c, "app:icon") != null;
            boolean noSpace = "false".equals(attr(c, "app:iconSpaceReserved"))
                    || "false".equals(attr(c, "android:iconSpaceReserved"));
            boolean ownLayout = c.getTagName().endsWith("PreferenceCategory")
                    && (attr(c, "android:layout") != null || attr(c, "app:layout") != null);
            assertTrue("top-level " + c.getTagName() + " " + attr(c, "android:key")
                    + " has an icon or iconSpaceReserved=false", icon || noSpace || ownLayout);
        }
    }

    @Test
    public void sendLaterSummaryShowsExactAlarmAndBatteryState() throws Exception {
        Map<String, String> values = strings();
        Set<String> exactOn = new LinkedHashSet<>();
        Set<String> exactOff = new LinkedHashSet<>();
        Set<String> battery = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue().toLowerCase(Locale.ROOT);
            if (v.contains("exact alarms on")) exactOn.add(e.getKey());
            if (v.contains("exact alarms off")) exactOff.add(e.getKey());
            if (v.matches("(?s).*exact alarms %(\\d\\$)?s.*")) {
                exactOn.add(e.getKey());
                exactOff.add(e.getKey());
            }
            if (v.contains("battery")) battery.add(e.getKey());
        }
        assertFalse("a summary string says 'exact alarms on' (or 'exact alarms %s')", exactOn.isEmpty());
        assertFalse("a summary string says 'exact alarms off' (or 'exact alarms %s')", exactOff.isEmpty());

        boolean exactWired = false;
        boolean batteryWired = false;
        for (Path p : mainJava()) {
            String src = noComments(read(p));
            Set<String> refs = javaRefs(Arrays.asList(p), "string");
            if (src.contains("exactAlarmsAllowed(")) {
                Set<String> on = new HashSet<>(exactOn);
                on.retainAll(refs);
                Set<String> off = new HashSet<>(exactOff);
                off.retainAll(refs);
                if (!on.isEmpty() && !off.isEmpty()) exactWired = true;
            }
            if ((src.contains("BatteryHealth.") || src.contains("BatteryIndicator."))
                    && (src.contains("group_send_later") || src.contains("KEY_GROUP_SEND_LATER"))) {
                Set<String> b = new HashSet<>(battery);
                b.retainAll(refs);
                // the battery_no_screen toast string is not a summary
                b.removeIf(k -> k.contains("no_screen"));
                if (!b.isEmpty()) batteryWired = true;
            }
        }
        assertTrue("the Send Later group summary is built from exactAlarmsAllowed and the exact-alarm "
                + "strings", exactWired);
        assertTrue("the Send Later group summary names the battery state", batteryWired);
    }

    // =================================================================== colours / dark mode

    @Test
    public void everyNewColourHasANightValueAndADocumentedContrast() throws Exception {
        Map<String, String> day = colourFiles(RES.resolve("values"));
        Map<String, String> night = colourFiles(RES.resolve("values-night"));
        Set<String> fresh = new LinkedHashSet<>();
        for (String text : day.values()) fresh.addAll(colourNames(text));
        for (String text : night.values()) fresh.addAll(colourNames(text));
        fresh.removeAll(BASELINE_COLOURS);
        assertFalse("this node adds colours (card surface, Held tag)", fresh.isEmpty());

        Set<String> nightNames = new HashSet<>();
        for (String text : night.values()) nightNames.addAll(colourNames(text));
        Set<String> dayNames = new HashSet<>();
        for (String text : day.values()) dayNames.addAll(colourNames(text));
        for (String c : fresh) {
            assertTrue("values-night defines new colour " + c, nightNames.contains(c));
            assertTrue("values defines new colour " + c, dayNames.contains(c));
        }
        Pattern ratio = Pattern.compile("\\d+(\\.\\d+)?\\s*:\\s*1");
        for (Map<String, String> files : Arrays.asList(day, night)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                String text = f.getValue();
                Matcher m = Pattern.compile("<color\\s+name=\"([^\"]+)\"").matcher(text);
                while (m.find()) {
                    if (!fresh.contains(m.group(1))) continue;
                    String before = text.substring(0, m.start());
                    int open = before.lastIndexOf("<!--");
                    assertTrue(f.getKey() + ": a comment above " + m.group(1), open >= 0);
                    int close = before.indexOf("-->", open);
                    String comment = before.substring(open, close < 0 ? before.length() : close);
                    assertTrue(f.getKey() + ": contrast ratio (N.N:1) documented above " + m.group(1),
                            ratio.matcher(comment).find());
                }
            }
        }
    }

    @Test
    public void cardTextHeldTagAndPillMeet45To1InBothModes() throws Exception {
        String bg = colourNamed(HELD_BG_LIGHT, HELD_BG_DARK);
        String fg = colourNamed(HELD_TEXT_LIGHT, HELD_TEXT_DARK);
        assertNotNull("Held tag colours exist", bg);
        assertNotNull(fg);
        for (boolean dark : new boolean[]{false, true}) {
            assertTrue("Held tag 4.5:1 (" + (dark ? "dark" : "light") + ")",
                    contrast(resolve(fg, dark), resolve(bg, dark)) >= 4.5);
        }

        String cardBg = cardColourName();
        List<Path> layouts = new ArrayList<>();
        layouts.add(CARD_LAYOUT);
        layouts.addAll(rowLayouts());
        for (Path p : layouts) {
            for (Element e : elements(parse(p))) {
                String tc = attr(e, "android:textColor");
                if (tc == null || !tc.startsWith("@color/")) continue;
                String name = tc.substring(7);
                String ownBg = firstNonNull(attr(e, "android:background"), attr(e, "android:backgroundTint"),
                        attr(e, "app:backgroundTint"));
                for (boolean dark : new boolean[]{false, true}) {
                    String surface;
                    if (ownBg != null && ownBg.startsWith("@color/")) surface = resolve(ownBg.substring(7), dark);
                    else if (ownBg != null) continue; // drawable/attr background: checked elsewhere
                    else if (cardBg != null) surface = resolve(cardBg, dark);
                    else continue;
                    String text = resolve(name, dark);
                    assertNotNull("resolve " + name, text);
                    assertNotNull("resolve card surface", surface);
                    assertTrue(p.getFileName() + " " + name + " on its surface >= 4.5:1 ("
                            + (dark ? "dark" : "light") + "): " + contrast(text, surface),
                            contrast(text, surface) >= 4.5);
                }
            }
        }

        // Header clock icon: 3:1 (icon) on the card surface when both are colour resources.
        if (cardBg != null) {
            for (String icon : iconColoursInCardHeader()) {
                for (boolean dark : new boolean[]{false, true}) {
                    String c = resolve(icon, dark);
                    if (c == null) continue;
                    assertTrue("card clock icon " + icon + " >= 3:1 on the card (" + (dark ? "dark" : "light")
                            + "): " + contrast(c, resolve(cardBg, dark)), contrast(c, resolve(cardBg, dark)) >= 3.0);
                }
            }
        }
    }

    @Test
    public void newCardRowAndIconDrawablesHaveNoHardCodedColours() throws Exception {
        Set<String> names = new LinkedHashSet<>();
        List<Path> layouts = new ArrayList<>();
        layouts.add(CARD_LAYOUT);
        layouts.addAll(rowLayouts());
        for (String l : chevronLayouts()) layouts.add(LAYOUT.resolve(l + ".xml"));
        for (Path p : layouts) {
            String xml = read(p);
            assertFalse(p.getFileName() + " has a hard-coded colour",
                    Pattern.compile("=\"#[0-9A-Fa-f]{3,8}\"").matcher(xml).find());
            names.addAll(drawableRefs(xml));
        }
        for (Element e : groupElements(parse(ROOT_PREFS)).values()) {
            String icon = firstNonNull(attr(e, "android:icon"), attr(e, "app:icon"));
            if (icon != null && icon.startsWith("@drawable/")) names.add(icon.substring(10));
        }
        assertFalse("card, rows and group rows reference drawables (icons, chevron, illustration)",
                names.isEmpty());
        Pattern hex = Pattern.compile("android:(fillColor|strokeColor|color|tint)=\"#([0-9A-Fa-f]{3,8})\"");
        for (String n : names) {
            for (Path f : drawableFiles(n)) {
                String xml = read(f);
                boolean themedTint = Pattern.compile("android:tint=\"[?@]").matcher(xml).find();
                Matcher m = hex.matcher(xml);
                while (m.find()) {
                    String h = m.group(2);
                    boolean transparent = (h.length() == 8 && h.startsWith("00")) || (h.length() == 4 && h.startsWith("0"));
                    if (themedTint && !m.group(1).equals("tint")) continue;
                    assertTrue(f.getFileName() + " hard-codes #" + h + " (use a @color with a night value "
                            + "or a themed android:tint)", transparent);
                }
            }
        }
    }

    // =================================================================== helpers: sources

    private static Path resolveMain() {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main").toAbsolutePath();
    }

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String noComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    private static List<Path> mainJava() throws IOException {
        try (Stream<Path> s = Files.walk(JAVA)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static List<Path> cardSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path p : mainJava()) {
            String s = noComments(read(p));
            if (s.contains("ScheduledCardModel") || s.contains("R.layout.sendlater_home_card")) out.add(p);
        }
        assertFalse("card sources", out.isEmpty());
        return out;
    }

    private static String joined(List<Path> files) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : files) sb.append(noComments(read(p))).append('\n');
        return sb.toString();
    }

    private static Set<String> javaRefs(List<Path> files, String type) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Pattern p = Pattern.compile("\\bR\\." + type + "\\.([A-Za-z0-9_]+)");
        for (Path f : files) {
            Matcher m = p.matcher(noComments(read(f)));
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> stringRefs(String xml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("@string/([A-Za-z0-9_]+)").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Set<String> drawableRefs(String xml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("@drawable/([A-Za-z0-9_]+)").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static boolean anyNamed(Iterable<String> names, List<String> words) {
        for (String n : names) for (String w : words) if (n.toLowerCase(Locale.ROOT).contains(w)) return true;
        return false;
    }

    private static List<Path> rowLayouts() throws Exception {
        Set<String> names = new LinkedHashSet<>(javaRefs(cardSources(), "layout"));
        names.remove("sendlater_home_card");
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(includes(read(CARD_LAYOUT)));
        queue.addAll(names);
        List<Path> out = new ArrayList<>();
        while (!queue.isEmpty()) {
            String n = queue.remove(0);
            if (!seen.add(n) || n.equals("sendlater_home_card")) continue;
            Path p = LAYOUT.resolve(n + ".xml");
            if (!Files.exists(p)) continue;
            out.add(p);
            queue.addAll(includes(read(p)));
        }
        return out;
    }

    private static List<String> includes(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<include[^>]*layout=\"@layout/([A-Za-z0-9_]+)\"").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static List<String> names(List<Path> ps) {
        List<String> out = new ArrayList<>();
        for (Path p : ps) out.add(p.getFileName().toString());
        return out;
    }

    /** Layouts that reference a chevron drawable (directly or via include). */
    private static Set<String> chevronLayouts() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> s = Files.list(LAYOUT)) {
            for (Path p : s.collect(Collectors.toList())) {
                String xml = read(p);
                if (anyNamed(drawableRefs(xml), CHEVRON)) {
                    out.add(p.getFileName().toString().replace(".xml", ""));
                }
            }
        }
        return out;
    }

    // =================================================================== helpers: XML

    private static Document parse(Path p) throws Exception {
        assertTrue("missing " + p, Files.exists(p));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(p.toFile());
    }

    private static List<Element> elements(Node root) {
        List<Element> out = new ArrayList<>();
        NodeList all = root instanceof Document
                ? ((Document) root).getElementsByTagName("*")
                : ((Element) root).getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) out.add((Element) all.item(i));
        if (root instanceof Document) return out;
        return out;
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element) out.add((Element) nl.item(i));
        }
        return out;
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... vs) {
        for (T v : vs) if (v != null) return v;
        return null;
    }

    private static Map<String, Element> groupElements(Document prefs) {
        Map<String, Element> out = new LinkedHashMap<>();
        for (Element e : elements(prefs)) {
            String k = attr(e, "android:key");
            if (k != null && GROUP_ICONS.containsKey(k)) out.put(k, e);
        }
        return out;
    }

    private static boolean isTextLike(Element e) {
        String t = e.getTagName();
        return t.endsWith("TextView") || t.endsWith("Button") || t.endsWith("Chip");
    }

    private static boolean isBold(Element e) {
        String s = attr(e, "android:textStyle");
        String f = attr(e, "android:fontFamily");
        return (s != null && s.contains("bold")) || (f != null && f.contains("medium"));
    }

    private static double dp(String v) throws IOException {
        if (v.startsWith("@dimen/")) {
            String name = v.substring(7);
            for (Path p : valueFiles(RES.resolve("values"))) {
                Matcher m = Pattern.compile("<dimen\\s+name=\"" + Pattern.quote(name) + "\"\\s*>([^<]+)</dimen>")
                        .matcher(read(p));
                if (m.find()) return dp(m.group(1).trim());
            }
            return 0;
        }
        Matcher m = Pattern.compile("([0-9.]+)\\s*(dp|dip|sp|px)").matcher(v);
        return m.matches() ? Double.parseDouble(m.group(1)) : 0;
    }

    private static double dpOrZero(String v) throws IOException {
        return v == null ? 0 : dp(v);
    }

    // =================================================================== helpers: drawables

    private static List<Path> drawableFiles(String name) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(RES)) {
            for (Path dir : s.filter(d -> d.getFileName().toString().startsWith("drawable"))
                    .collect(Collectors.toList())) {
                Path f = dir.resolve(name + ".xml");
                if (Files.exists(f)) out.add(f);
            }
        }
        return out;
    }

    /** The drawable and every drawable it references, recursively. */
    private static List<Document> drawableTree(String name) throws Exception {
        List<Document> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> q = new ArrayList<>(Arrays.asList(name));
        while (!q.isEmpty()) {
            String n = q.remove(0);
            if (!seen.add(n)) continue;
            for (Path f : drawableFiles(n)) {
                out.add(parse(f));
                q.addAll(drawableRefs(read(f)));
            }
        }
        return out;
    }

    private static boolean isVectorish(Path f) throws Exception {
        Document d = parse(f);
        String root = d.getDocumentElement().getTagName();
        if (root.equals("vector")) return true;
        if (root.equals("layer-list") || root.equals("inset") || root.equals("selector")) {
            for (String r : drawableRefs(read(f))) {
                for (Path p : drawableFiles(r)) if (isVectorish(p)) return true;
            }
            return elements(d).stream().anyMatch(e -> e.getTagName().equals("vector"));
        }
        return false;
    }

    private static boolean fillsWithAccent(Element e) throws Exception {
        for (String a : Arrays.asList("android:background", "android:backgroundTint", "app:backgroundTint")) {
            String v = attr(e, a);
            if (v == null) continue;
            if (v.startsWith("@color/") && v.contains("accent")) return true;
            if (v.startsWith("@drawable/")) {
                for (Document d : drawableTree(v.substring(10))) {
                    for (Element de : elements(d)) {
                        NamedNodeMap attrs = de.getAttributes();
                        for (int i = 0; i < attrs.getLength(); i++) {
                            String val = attrs.item(i).getNodeValue();
                            if (val.startsWith("@color/") && val.contains("accent")) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static Set<String> iconColoursInCardHeader() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (Element e : elements(parse(CARD_LAYOUT))) {
            if (!e.getTagName().endsWith("ImageView")) continue;
            String src = firstNonNull(attr(e, "android:src"), attr(e, "app:srcCompat"));
            if (src == null || !src.startsWith("@drawable/")) continue;
            if (!anyNamed(Arrays.asList(src.substring(10)), CLOCK)) continue;
            String tint = firstNonNull(attr(e, "app:tint"), attr(e, "android:tint"));
            if (tint != null) {
                if (tint.startsWith("@color/")) out.add(tint.substring(7));
                continue;
            }
            for (Path f : drawableFiles(src.substring(10))) {
                String xml = read(f);
                Matcher t = Pattern.compile("android:tint=\"@color/([A-Za-z0-9_]+)\"").matcher(xml);
                if (t.find()) {
                    out.add(t.group(1));
                    continue;
                }
                Matcher m = Pattern.compile("android:(fillColor|strokeColor)=\"@color/([A-Za-z0-9_]+)\"").matcher(xml);
                while (m.find()) out.add(m.group(2));
            }
        }
        return out;
    }

    // =================================================================== helpers: values

    private static List<Path> valueFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
        }
    }

    private static Map<String, String> colourFiles(Path dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path p : valueFiles(dir)) {
            String t = read(p);
            if (t.contains("<color")) out.put(p.getFileName().toString(), t);
        }
        return out;
    }

    private static Set<String> colourNames(String xml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<color\\s+name=\"([^\"]+)\"").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Map<String, String> colourValues(boolean dark) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern p = Pattern.compile("<color\\s+name=\"([^\"]+)\"\\s*>\\s*([^<\\s]+)\\s*</color>");
        for (String t : colourFiles(RES.resolve("values")).values()) {
            Matcher m = p.matcher(t);
            while (m.find()) out.put(m.group(1), m.group(2));
        }
        if (dark) {
            for (String t : colourFiles(RES.resolve("values-night")).values()) {
                Matcher m = p.matcher(t);
                while (m.find()) out.put(m.group(1), m.group(2));
            }
        }
        return out;
    }

    /** RRGGBB of a colour resource in light or dark mode (references followed), or null. */
    private static String resolve(String name, boolean dark) throws IOException {
        Map<String, String> values = colourValues(dark);
        String v = values.get(name);
        for (int i = 0; v != null && v.startsWith("@color/") && i < 10; i++) v = values.get(v.substring(7));
        if (v == null || !v.startsWith("#")) return null;
        String h = v.substring(1).toUpperCase(Locale.ROOT);
        if (h.length() == 8) {
            if (!h.startsWith("FF")) return null;
            h = h.substring(2);
        }
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        return h.length() == 6 ? h : null;
    }

    /** A colour resource whose light value is {@code light} and night value is {@code dark}. */
    private static String colourNamed(String light, String dark) throws IOException {
        for (String n : colourValues(false).keySet()) {
            if (light.equals(resolve(n, false)) && dark.equals(resolve(n, true))) return n;
        }
        return null;
    }

    private static boolean isCardColour(String v) throws IOException {
        if (v.startsWith("?") && v.contains("SurfaceContainer")) return true;
        if (!v.startsWith("@color/")) return false;
        String n = v.substring(7);
        return CARD_BG_LIGHT.equals(resolve(n, false)) && CARD_BG_DARK.equals(resolve(n, true));
    }

    /** The @color the card surface uses, or null when it is a theme token. */
    private static String cardColourName() throws Exception {
        for (Element e : elements(parse(CARD_LAYOUT))) {
            for (String a : Arrays.asList("android:background", "app:cardBackgroundColor",
                    "android:backgroundTint", "app:backgroundTint")) {
                String v = attr(e, a);
                if (v == null) continue;
                if (v.startsWith("@color/") && isCardColour(v)) return v.substring(7);
                if (v.startsWith("@drawable/")) {
                    for (Document d : drawableTree(v.substring(10))) {
                        for (Element de : elements(d)) {
                            String c = de.getTagName().equals("solid") ? attr(de, "android:color") : null;
                            if (c != null && c.startsWith("@color/") && isCardColour(c)) return c.substring(7);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean usesColour(String text, String name) {
        return text.contains("@color/" + name + "\"") || text.contains("R.color." + name);
    }

    private static Map<String, String> strings() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern p = Pattern.compile("(?s)<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>");
        for (Path f : valueFiles(RES.resolve("values"))) {
            if (!f.getFileName().toString().startsWith("sendlater_")) continue;
            Matcher m = p.matcher(read(f));
            while (m.find()) out.put(m.group(1), unescape(m.group(2)));
        }
        return out;
    }

    private static Map<String, String> pluralsOther() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern p = Pattern.compile("(?s)<plurals\\s+name=\"([^\"]+)\"[^>]*>(.*?)</plurals>");
        Pattern other = Pattern.compile("(?s)<item\\s+quantity=\"other\"\\s*>(.*?)</item>");
        for (Path f : valueFiles(RES.resolve("values"))) {
            if (!f.getFileName().toString().startsWith("sendlater_")) continue;
            Matcher m = p.matcher(read(f));
            while (m.find()) {
                Matcher o = other.matcher(m.group(2));
                if (o.find()) out.put(m.group(1), unescape(o.group(1)));
            }
        }
        return out;
    }

    private static String unescape(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("\\n", "\n");
    }

    private static String resolveString(String v) throws IOException {
        if (v == null) return null;
        if (v.startsWith("@string/")) return strings().get(v.substring(8));
        return v;
    }

    private static boolean referencesValue(Set<String> refs, Map<String, String> values, String want) {
        for (String r : refs) if (want.equals(values.get(r))) return true;
        return false;
    }

    // =================================================================== contrast

    private static double lum(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(String a, String b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    public void contrastHelperMatchesKnownRatios() {
        // sanity for the helper: black on white is 21:1, the pinned Held pairs pass 4.5:1.
        assertEquals(21.0, contrast("000000", "FFFFFF"), 0.01);
        assertTrue(contrast(HELD_TEXT_LIGHT, HELD_BG_LIGHT) >= 4.5);
        assertTrue(contrast(HELD_TEXT_DARK, HELD_BG_DARK) >= 4.5);
        if (contrast("FFFFFF", "FFFFFF") > 1.01) fail("helper");
    }
}
