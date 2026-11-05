package com.voice2sms.sendlater.voice.overlay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.voice2sms.sendlater.voice.logic.VoiceScreenSignature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The live native Voice screen through the service's active window (R2, R8). Every read takes a
 * fresh root and looks nodes up with findAccessibilityNodeInfosByViewId for the {@link VoiceOverlayIds}
 * only; every node is recycled before the call returns. Nothing is walked beyond the direct rows
 * of the bubble list. Safe on any thread (accessibility node calls are binder IPC).
 */
final class ScreenNodes implements VoiceNodeAccessor {

    /** One debounced reading of the screen for the tab/chip decision. */
    static final class Reading {
        final VoiceScreenSignature.Level level;
        final String title;
        final String draft;
        /** Bounds of input_message_container in screen coordinates, or null. */
        final Rect anchor;
        /** False when the bubble list can still scroll towards newer rows. */
        final boolean atNewest;

        Reading(VoiceScreenSignature.Level level, String title, String draft, Rect anchor, boolean atNewest) {
            this.level = level;
            this.title = title;
            this.draft = draft;
            this.anchor = anchor;
            this.atNewest = atNewest;
        }
    }

    private final AccessibilityService service;

    ScreenNodes(AccessibilityService service) {
        this.service = service;
    }

    /** The Voice root of the active window, or null (the caller recycles it). */
    private AccessibilityNodeInfo voiceRoot() {
        AccessibilityNodeInfo root;
        try {
            root = service.getRootInActiveWindow();
        } catch (RuntimeException e) {
            return null;
        }
        if (root == null) return null;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !VoiceScreenSignature.VOICE_PACKAGE.contentEquals(pkg)) {
            root.recycle();
            return null;
        }
        return root;
    }

    /** First node with an allowlisted id, or null; the other matches are recycled. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String id) {
        if (!VoiceOverlayIds.isAllowed(id)) throw new IllegalArgumentException("not an R8 id");
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        if (found == null || found.isEmpty()) return null;
        AccessibilityNodeInfo first = found.get(0);
        for (int i = 1; i < found.size(); i++) {
            AccessibilityNodeInfo n = found.get(i);
            if (n != null) n.recycle();
        }
        return first;
    }

    /**
     * Texts of every {@code chip} node (LIVE-F1); all nodes recycled. Only called when there is no
     * {@code toolbar_title}, so ordinary thread screens pay no extra lookup.
     */
    private static List<String> chipTexts(AccessibilityNodeInfo root) {
        if (!VoiceOverlayIds.isAllowed(VoiceOverlayIds.CHIP)) throw new IllegalArgumentException("not an R8 id");
        List<String> out = new ArrayList<>();
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(VoiceOverlayIds.CHIP);
        if (found == null) return out;
        for (AccessibilityNodeInfo n : found) {
            if (n == null) continue;
            try {
                out.add(textOf(n));
            } finally {
                n.recycle();
            }
        }
        return out;
    }

    /** The 1:1 title: toolbar_title text, else the single chip's text, else null (LIVE-F1, OD13). */
    private static String titleText(AccessibilityNodeInfo title, List<String> chips) {
        return VoiceScreenSignature.title(title != null ? textOf(title) : null, chips);
    }

    private static void recycle(AccessibilityNodeInfo n) {
        if (n != null) n.recycle();
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence t = n.getText();
        return t != null ? t.toString() : "";
    }

    /** Composer text without the hint ("" when the field shows its hint text). */
    private static String draftOf(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.isShowingHintText()) return "";
        return textOf(n);
    }

    /** The tab/chip reading, or null when Voice is not the active window. */
    Reading read() {
        AccessibilityNodeInfo root = voiceRoot();
        if (root == null) return null;
        AccessibilityNodeInfo title = null;
        AccessibilityNodeInfo field = null;
        AccessibilityNodeInfo send = null;
        AccessibilityNodeInfo container = null;
        AccessibilityNodeInfo list = null;
        try {
            title = find(root, VoiceOverlayIds.TOOLBAR_TITLE);
            field = find(root, VoiceOverlayIds.SEND_TEXT);
            send = find(root, VoiceOverlayIds.SEND_BUTTON);
            List<String> chips = title == null && field != null && send != null
                    ? chipTexts(root) : Collections.<String>emptyList();
            VoiceScreenSignature.Level level = VoiceScreenSignature.classify(title != null, chips.size(),
                    field != null, send != null, VoiceScreenSignature.VOICE_PACKAGE);
            if (level != VoiceScreenSignature.Level.HIGH) {
                return new Reading(level, null, null, null, false);
            }
            Rect anchor = null;
            container = find(root, VoiceOverlayIds.INPUT_CONTAINER);
            if (container != null) {
                anchor = new Rect();
                container.getBoundsInScreen(anchor);
                if (anchor.isEmpty()) anchor = null;
            }
            boolean atNewest = true;
            list = find(root, VoiceOverlayIds.BUBBLE_LIST);
            if (list != null) {
                atNewest = !list.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
            return new Reading(level, titleText(title, chips), draftOf(field), anchor, atNewest);
        } catch (RuntimeException e) {
            return null;
        } finally {
            recycle(title);
            recycle(field);
            recycle(send);
            recycle(container);
            recycle(list);
            root.recycle();
        }
    }

    @Override public String readTitle() {
        AccessibilityNodeInfo root = voiceRoot();
        if (root == null) return null;
        AccessibilityNodeInfo n = null;
        try {
            n = find(root, VoiceOverlayIds.TOOLBAR_TITLE);
            return titleText(n, n == null ? chipTexts(root) : Collections.<String>emptyList());
        } catch (RuntimeException e) {
            return null;
        } finally {
            recycle(n);
            root.recycle();
        }
    }

    @Override public String readDraft() {
        AccessibilityNodeInfo root = voiceRoot();
        if (root == null) return null;
        AccessibilityNodeInfo n = null;
        try {
            n = find(root, VoiceOverlayIds.SEND_TEXT);
            return draftOf(n);
        } catch (RuntimeException e) {
            return null;
        } finally {
            recycle(n);
            root.recycle();
        }
    }

    @Override public List<String> readBubbleDescriptions() {
        AccessibilityNodeInfo root = voiceRoot();
        if (root == null) return Collections.emptyList();
        AccessibilityNodeInfo list = null;
        List<String> out = new ArrayList<>();
        try {
            list = find(root, VoiceOverlayIds.BUBBLE_LIST);
            if (list == null) return out;
            int n = list.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo row = list.getChild(i);
                if (row == null) continue;
                CharSequence d = row.getContentDescription();
                if (d != null) out.add(d.toString());
                row.recycle();
            }
            return out;
        } catch (RuntimeException e) {
            return out;
        } finally {
            recycle(list);
            root.recycle();
        }
    }

    @Override public boolean setDraft(String text) {
        AccessibilityNodeInfo root = voiceRoot();
        if (root == null) return false;
        AccessibilityNodeInfo n = null;
        try {
            n = find(root, VoiceOverlayIds.SEND_TEXT);
            if (n == null) return false;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (RuntimeException e) {
            return false;
        } finally {
            recycle(n);
            root.recycle();
        }
    }
}
