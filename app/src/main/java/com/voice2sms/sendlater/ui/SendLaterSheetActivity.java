package com.voice2sms.sendlater.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.voice2sms.R;
import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.engine.GvSession;
import com.voice2sms.sendlater.engine.PendingSummaryNotifier;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.engine.TapResult;
import com.voice2sms.sendlater.store.PersistException;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Send Later scheduling sheet (R4, R9, R12-R16, R21, I1, I3). A translucent activity styled
 * as a bottom sheet with its own Material 3 theme ({@code Theme.SendLater.Sheet}, F2).
 *
 * <ul>
 *   <li>{@link SendLaterIntents#ACTION_CAPTURE}: a new message from a capture surface. Schedule
 *       persists it (transactional, verified), registers its alarms, broadcasts
 *       {@link SendLaterIntents#ACTION_SCHEDULED} (the caller clears its draft and shows Undo) and
 *       closes. A failed persist shows an inline error and changes nothing else (R21).</li>
 *   <li>{@link SendLaterIntents#ACTION_EDIT}: an existing message; Update reschedules it, Cancel
 *       message cancels it through the engine (R16).</li>
 * </ul>
 * The sheet never sends and never performs the R7 live-screen check (the capturing surface does
 * that on ACTION_SCHEDULED). Message text, names and numbers are never logged.
 */
public class SendLaterSheetActivity extends AppCompatActivity {

    private static final String TAG = "SendLaterSheet";
    private static final int REQ_NOTIFICATIONS = 4101;
    /** D52 / R12b: the model's unsaved choices, kept only in the instance-state Bundle. */
    private static final String KEY_SHEET_STATE = "sendlater.sheet.state";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Context app;

    // R12 views, top to bottom.
    private TextView title;
    private View card;
    private TextView cardRecipient;
    private TextView cardDraft;
    private View recipientSection;
    private RadioGroup recipientChoices;
    private TextInputLayout numberEntryLayout;
    private EditText numberEntry;
    /** D49 / R12a: edit mode's editable message text (capture shows the quoted cardDraft). */
    private View draftEditLayout;
    private EditText draftEdit;
    private TextView sessionHint;
    private RadioGroup presets;
    private Button pickDateTime;
    private CheckBox holdCheckbox;
    private Spinner ifLate;
    private ArrayAdapter<String> ifLateAdapter;
    private final List<String> ifLateLabels = new ArrayList<>();
    private List<LatenessPolicy> ifLateOptions = new ArrayList<>();
    private TextView notifHint;
    private TextView error;
    private Button scheduleButton;
    private Button cancelButton;

    private SheetViewModelLogic logic;
    private boolean binding;
    private boolean busy;
    private boolean draftExpanded;
    private boolean editable = true;

    // Capture payload (never logged).
    private ScheduledMessage.Source source = ScheduledMessage.Source.NATIVE_GV;
    private String displayName;
    private String draft;
    private String threadItemId;
    private Watermark watermark;
    private boolean hasWebSession;
    private long editId = -1L;
    /** The re-created activity's saved state, until it is applied over the rebuilt model. */
    private Bundle restoredState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = getApplicationContext();
        restoredState = savedInstanceState;
        setContentView(R.layout.sendlater_sheet);
        bindViews();

        hasWebSession = GvSession.hasWebSession(this);
        sessionHint.setVisibility(hasWebSession ? View.GONE : View.VISIBLE);
        refreshNotificationHint();

        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;
        if (SendLaterIntents.ACTION_CAPTURE.equals(action)) {
            // UI M2: a second capture while one sheet is open closes at once (no second record).
            if (savedInstanceState != null) {
                CaptureSheetGuard.takeOver(this);
            } else if (!CaptureSheetGuard.tryAcquire(this)) {
                finish();
                return;
            }
            setupCapture(intent);
        } else if (SendLaterIntents.ACTION_EDIT.equals(action)) {
            setupEdit(intent.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1L));
        } else {
            finish();
        }
    }

    /**
     * D52 / OD43 / R12b: a light/dark switch or other re-creation keeps the unsaved choices
     * (rotation and fold do not re-create: see configChanges). Bundle only: never the DB or logs.
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (logic != null) {
            outState.putBundle(KEY_SHEET_STATE, toBundle(logic.saveState()));
        } else if (editable && restoredState != null) {
            // Re-created again while the edit row is still loading: keep what was saved before.
            Bundle pending = restoredState.getBundle(KEY_SHEET_STATE);
            if (pending != null) outState.putBundle(KEY_SHEET_STATE, pending);
        }
    }

    private static Bundle toBundle(Map<String, Object> state) {
        Bundle b = new Bundle();
        for (Map.Entry<String, Object> e : state.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Long) b.putLong(e.getKey(), (Long) v);
            else if (v instanceof Integer) b.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Boolean) b.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof String) b.putString(e.getKey(), (String) v);
        }
        return b;
    }

    /** Applies the saved choices over the freshly built model, once; nothing saved: no change. */
    @SuppressWarnings("deprecation")
    private void restoreUnsavedEdits() {
        Bundle saved = restoredState != null ? restoredState.getBundle(KEY_SHEET_STATE) : null;
        restoredState = null;
        if (saved == null || logic == null) return;
        Map<String, Object> state = new HashMap<>();
        for (String k : saved.keySet()) state.put(k, saved.get(k));
        logic.restoreState(state);
    }

    @Override
    protected void onDestroy() {
        CaptureSheetGuard.release(this);
        io.shutdown(); // a persist already running still completes (and broadcasts) on its own.
        super.onDestroy();
    }

    private void bindViews() {
        title = findViewById(R.id.title);
        card = findViewById(R.id.card);
        cardRecipient = findViewById(R.id.card_recipient);
        cardDraft = findViewById(R.id.card_draft);
        recipientSection = findViewById(R.id.recipient_section);
        recipientChoices = findViewById(R.id.recipient_choices);
        numberEntryLayout = findViewById(R.id.number_entry_layout);
        numberEntry = findViewById(R.id.number_entry);
        draftEditLayout = findViewById(R.id.draft_edit_layout);
        draftEdit = findViewById(R.id.draft_edit);
        sessionHint = findViewById(R.id.session_hint);
        presets = findViewById(R.id.presets);
        pickDateTime = findViewById(R.id.pick_datetime);
        holdCheckbox = findViewById(R.id.hold_checkbox);
        ifLate = findViewById(R.id.if_late);
        notifHint = findViewById(R.id.notif_hint);
        error = findViewById(R.id.error);
        scheduleButton = findViewById(R.id.schedule_button);
        cancelButton = findViewById(R.id.cancel_button);

        title.setContentDescription(getString(R.string.sendlater_sheet_title));
        findViewById(R.id.scrim).setOnClickListener(v -> onSecondary(true));
        card.setOnClickListener(v -> toggleDraft());
        pickDateTime.setOnClickListener(v -> openDatePicker());
        holdCheckbox.setOnCheckedChangeListener((b, checked) -> {
            if (!binding && logic != null) logic.setHoldIfReplied(checked);
        });
        presets.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding || logic == null || checkedId == View.NO_ID) return;
            View row = group.findViewById(checkedId);
            if (row != null && row.getTag() instanceof Integer) {
                logic.selectPreset((Integer) row.getTag());
                clearError();
                render();
            }
        });
        recipientChoices.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding || logic == null || checkedId == View.NO_ID) return;
            View row = group.findViewById(checkedId);
            if (row != null && row.getTag() instanceof String) {
                logic.chooseCandidate((String) row.getTag());
                render();
            }
        });
        numberEntry.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (binding || logic == null) return;
                boolean ok = logic.enterNumber(s.toString());
                // OD22(8): an invalid manual number says so inline (empty field: no message yet).
                showNumberError(!ok && s.toString().trim().length() > 0);
                render();
            }
        });
        draftEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (binding || logic == null || !logic.isTextEditable()) return;
                logic.setText(s.toString());
                // Empty or whitespace-only text cannot be saved: the existing empty-draft message.
                if (isDraftEmpty()) showError(R.string.sendlater_sheet_empty_draft);
                else clearError();
                render();
            }
        });
        notifHint.setOnClickListener(v -> requestNotificationPermission());
        scheduleButton.setOnClickListener(v -> onPrimary());
        cancelButton.setOnClickListener(v -> onSecondary(false));
    }

    // ================================================================== capture (R4, R9, R15)

    private void setupCapture(Intent in) {
        ScheduledMessage.Source parsed = parseSource(in.getStringExtra(SendLaterIntents.EXTRA_SOURCE));
        if (parsed == null) {
            // OD22(8): a capture must say where it came from; nothing is shown or saved otherwise.
            Toast.makeText(app, R.string.sendlater_sheet_bad_source, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        source = parsed;
        displayName = in.getStringExtra(SendLaterIntents.EXTRA_DISPLAY_NAME);
        draft = in.getStringExtra(SendLaterIntents.EXTRA_DRAFT);
        threadItemId = in.getStringExtra(SendLaterIntents.EXTRA_THREAD_ITEM_ID);
        watermark = readWatermark(in);
        String givenNumber = in.getStringExtra(SendLaterIntents.EXTRA_E164);
        String[] candidates = in.getStringArrayExtra(SendLaterIntents.EXTRA_CANDIDATES);

        // deliver sec-M1: directory-only candidates are confirmed by the user, even a single one.
        boolean unconfirmed = in.getBooleanExtra(SendLaterIntents.EXTRA_RECIPIENT_UNCONFIRMED, false);
        logic = SheetViewModelLogic.forCapture(givenNumber, candidates, unconfirmed,
                SendLaterSettings.holdDefault(this), System.currentTimeMillis(),
                TimeZone.getDefault(), SendLaterSettings.slotMinutes(this));
        restoreUnsavedEdits(); // D52: re-creation keeps the chosen number, time, hold, If late
        bindLogic();
        if (isDraftEmpty()) showError(R.string.sendlater_sheet_empty_draft);
    }

    /** The capture's EXTRA_SOURCE, or null when missing or unknown (the capture is rejected). */
    static ScheduledMessage.Source parseSource(String s) {
        if (s != null) {
            for (ScheduledMessage.Source v : ScheduledMessage.Source.values()) {
                if (v.name().equals(s)) return v;
            }
        }
        return null;
    }

    private static Watermark readWatermark(Intent in) {
        Watermark.Quality q = Watermark.Quality.DEVICE_ONLY;
        String qs = in.getStringExtra(SendLaterIntents.EXTRA_WATERMARK_QUALITY);
        if (qs != null) {
            for (Watermark.Quality v : Watermark.Quality.values()) if (v.name().equals(qs)) q = v;
        }
        String newestId = in.getStringExtra(SendLaterIntents.EXTRA_WATERMARK_NEWEST_ID);
        Long newestTs = in.hasExtra(SendLaterIntents.EXTRA_WATERMARK_NEWEST_TS)
                ? in.getLongExtra(SendLaterIntents.EXTRA_WATERMARK_NEWEST_TS, 0L) : null;
        String bubbleHash = in.getStringExtra(SendLaterIntents.EXTRA_WATERMARK_BUBBLE_HASH);
        long capturedAt = in.getLongExtra(SendLaterIntents.EXTRA_WATERMARK_CAPTURED_AT, 0L);
        if (capturedAt <= 0) capturedAt = System.currentTimeMillis();
        // A claimed quality the payload cannot back is downgraded (CapturePayload.qualityOf rule).
        if (q == Watermark.Quality.EXACT && (newestId == null || newestTs == null)) {
            q = newestTs != null ? Watermark.Quality.TIMESTAMP : Watermark.Quality.DEVICE_ONLY;
        } else if (q == Watermark.Quality.TIMESTAMP && newestTs == null) {
            q = Watermark.Quality.DEVICE_ONLY;
        }
        return new Watermark(q, newestId, newestTs, bubbleHash, capturedAt, null);
    }

    /** Capture: the quoted draft; edit mode (D49): the editable text as it is now. */
    private boolean isDraftEmpty() {
        String t = logic != null && logic.isTextEditable() ? logic.getText() : draft;
        return !SheetViewModelLogic.hasContent(t);
    }

    // ================================================================== edit mode (R16)

    private void setupEdit(final long id) {
        editId = id;
        setBusy(true);
        cancelButton.setText(R.string.sendlater_sheet_cancel_message);
        io.execute(() -> {
            ScheduledMessage m = null;
            boolean canEdit = false;
            try {
                // Re-checked here: notification-launched edits skip the action receiver's check.
                canEdit = id > 0 && SendLaterEngine.isEditable(app, id);
                if (canEdit) m = SendLaterRepository.get(app).get(id);
            } catch (RuntimeException e) {
                Log.w(TAG, "edit load failed: " + e.getClass().getSimpleName());
            }
            final ScheduledMessage loaded = m;
            final boolean ok = canEdit && loaded != null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (!ok) {
                    showNotEditable();
                    return;
                }
                source = loaded.getSource();
                displayName = loaded.getRecipientDisplayName();
                draft = loaded.getText();
                logic = SheetViewModelLogic.forEdit(loaded, System.currentTimeMillis(),
                        TimeZone.getDefault(), SendLaterSettings.slotMinutes(this));
                // D52: only a row that passed the re-check above gets the unsaved edits back.
                restoreUnsavedEdits();
                bindLogic();
                if (isDraftEmpty()) showError(R.string.sendlater_sheet_empty_draft);
            });
        });
    }

    private void showNotEditable() {
        editable = false;
        logic = null;
        draftEdit.setEnabled(false);
        presets.setVisibility(View.GONE);
        pickDateTime.setVisibility(View.GONE);
        holdCheckbox.setVisibility(View.GONE);
        ((View) ifLate.getParent()).setVisibility(View.GONE);
        scheduleButton.setVisibility(View.GONE);
        cancelButton.setText(R.string.sendlater_sheet_cancel);
        showError(R.string.sendlater_sheet_not_editable);
    }

    // ================================================================== binding + render

    /** One-time population of the rows from the logic; then {@link #render()}. */
    private void bindLogic() {
        binding = true;
        try {
            LayoutInflater inf = getLayoutInflater();

            presets.removeAllViews();
            List<PresetSlots.Preset> rows = logic.getPresets();
            for (int i = 0; i < rows.size(); i++) {
                RadioButton rb = (RadioButton) inf.inflate(R.layout.sendlater_preset_row, presets, false);
                rb.setId(View.generateViewId());
                rb.setTag(i);
                rb.setText(rows.get(i).getLabel());
                presets.addView(rb);
            }

            recipientChoices.removeAllViews();
            boolean choice = logic.showsCandidateChoice();
            boolean entry = logic.showsNumberEntry();
            recipientSection.setVisibility(choice || entry ? View.VISIBLE : View.GONE);
            recipientChoices.setVisibility(choice ? View.VISIBLE : View.GONE);
            numberEntryLayout.setVisibility(entry ? View.VISIBLE : View.GONE);
            if (choice) {
                for (String number : logic.getCandidates()) {
                    RadioButton rb = (RadioButton) inf.inflate(R.layout.sendlater_choice_row, recipientChoices, false);
                    rb.setId(View.generateViewId());
                    rb.setTag(number);
                    rb.setText(ChipLabel.formatNumber(number));
                    recipientChoices.addView(rb);
                }
            }

            holdCheckbox.setChecked(logic.isHoldIfReplied());

            final List<LatenessPolicy> options = logic.getIfLateOptions();
            ifLateOptions = options;
            ifLateLabels.clear();
            for (LatenessPolicy p : options) ifLateLabels.add(ifLateLabel(p));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, ifLateLabels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            ifLateAdapter = adapter;
            ifLate.setAdapter(adapter);
            ifLate.setSelection(Math.max(0, options.indexOf(logic.getLatenessPolicy())), false);
            ifLate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (!binding && logic != null && pos >= 0 && pos < options.size()) {
                        logic.setLatenessPolicy(options.get(pos));
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            if (logic.isTextEditable()) {
                // D49 / R12a / OD40: edit mode prefills the stored text, exactly, and lets it change.
                cardDraft.setVisibility(View.GONE);
                draftEditLayout.setVisibility(View.VISIBLE);
                draftEdit.setText(logic.getText());
            } else {
                String quoted = draft == null ? "" : getString(R.string.sendlater_sheet_draft_quoted, draft);
                cardDraft.setText(quoted);
                cardDraft.setVisibility(View.VISIBLE);
                draftEditLayout.setVisibility(View.GONE);
            }

            cancelButton.setText(logic.isEditMode()
                    ? R.string.sendlater_sheet_cancel_message : R.string.sendlater_sheet_cancel);
        } finally {
            binding = false;
        }
        render();
    }

    /** The row label; "Automatic" names its budget for this message at the selected time (OD31 PQ5). */
    private String ifLateLabel(LatenessPolicy p) {
        switch (p) {
            case WITHIN_30_MIN: return getString(R.string.sendlater_sheet_if_late_30);
            case NEVER_LATE: return getString(R.string.sendlater_sheet_if_late_never);
            case UP_TO_24H: return getString(R.string.sendlater_sheet_if_late_anyway);
            case DEFAULT:
            default:
                return getString(R.string.sendlater_sheet_if_late_default_budget,
                        SheetViewModelLogic.budgetPhrase(
                                logic.automaticBudgetMs(System.currentTimeMillis())));
        }
    }

    /** Re-labels "Automatic" when the selected time moves its budget (render). */
    private void refreshIfLateLabels() {
        if (ifLateAdapter == null) return;
        int i = ifLateOptions.indexOf(LatenessPolicy.DEFAULT);
        if (i < 0 || i >= ifLateLabels.size()) return;
        String label = ifLateLabel(LatenessPolicy.DEFAULT);
        if (!label.equals(ifLateLabels.get(i))) {
            ifLateLabels.set(i, label);
            ifLateAdapter.notifyDataSetChanged();
        }
    }

    /** Reflects the logic's state: card, selected row, button text and enabled state. */
    private void render() {
        if (logic == null) return;
        binding = true;
        try {
            // D39: numbers are shown formatted; logic keeps the E.164 itself.
            String number = logic.getE164() != null ? ChipLabel.formatNumber(logic.getE164()) : null;
            boolean hasName = displayName != null && !displayName.trim().isEmpty();
            if (hasName && number != null) {
                cardRecipient.setText(getString(R.string.sendlater_sheet_card_name_number, displayName, number));
            } else if (number != null) {
                cardRecipient.setText(number);
            } else if (hasName) {
                cardRecipient.setText(getString(R.string.sendlater_sheet_card_name_number, displayName,
                        getString(R.string.sendlater_sheet_card_unknown)));
            } else {
                cardRecipient.setText(R.string.sendlater_sheet_card_unknown);
            }

            if (logic.showsCandidateChoice() && logic.getE164() != null) {
                for (int i = 0; i < recipientChoices.getChildCount(); i++) {
                    View row = recipientChoices.getChildAt(i);
                    if (logic.getE164().equals(row.getTag())) recipientChoices.check(row.getId());
                }
            }

            int sel = logic.getSelectedPresetIndex();
            if (sel >= 0 && sel < presets.getChildCount()) {
                presets.check(presets.getChildAt(sel).getId());
                pickDateTime.setText(R.string.sendlater_sheet_pick_datetime);
            } else {
                presets.clearCheck();
                pickDateTime.setText(getString(R.string.sendlater_sheet_picked, logic.getSelectedTimeLabel()));
            }

            if (logic.isEditMode()) {
                scheduleButton.setText(R.string.sendlater_sheet_update);
            } else {
                scheduleButton.setText(getString(R.string.sendlater_sheet_schedule_for,
                        logic.getSelectedTimeLabel()));
            }
            scheduleButton.setEnabled(!busy && editable && logic.isScheduleEnabled() && !isDraftEmpty());
            cancelButton.setEnabled(!busy);
            refreshIfLateLabels();
        } finally {
            binding = false;
        }
    }

    private void toggleDraft() {
        draftExpanded = !draftExpanded;
        cardDraft.setMaxLines(draftExpanded ? Integer.MAX_VALUE : 3);
    }

    private void setBusy(boolean b) {
        busy = b;
        scheduleButton.setEnabled(!b && editable && logic != null && logic.isScheduleEnabled() && !isDraftEmpty());
        cancelButton.setEnabled(!b);
        // edit-text review L2: no keystrokes while the captured text is being saved.
        if (draftEdit != null) draftEdit.setEnabled(!b && editable);
    }

    private void showError(int res) {
        error.setText(res);
        error.setVisibility(View.VISIBLE);
    }

    private void clearError() {
        error.setVisibility(View.GONE);
    }

    /** Inline "Enter a full phone number" under the manual number field (OD22(8)). */
    private void showNumberError(boolean show) {
        numberEntryLayout.setError(show ? getString(R.string.sendlater_sheet_number_invalid) : null);
    }

    // ================================================================== pickers (R14)

    private void openDatePicker() {
        if (logic == null || busy) return;
        final TimeZone utc = TimeZone.getTimeZone("UTC");
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(logic.getSelectedTimeMs());
        Calendar sel = Calendar.getInstance(utc);
        sel.clear();
        sel.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));

        MaterialDatePicker<Long> dp = MaterialDatePicker.Builder.datePicker()
                .setSelection(sel.getTimeInMillis())
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointForward.now()).build())
                .build();
        dp.addOnPositiveButtonClickListener(dayUtc -> {
            if (dayUtc == null || isFinishing()) return;
            Calendar d = Calendar.getInstance(utc);
            d.setTimeInMillis(dayUtc);
            openTimePicker(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH));
        });
        dp.show(getSupportFragmentManager(), "sendlater_date");
    }

    private void openTimePicker(final int year, final int month, final int day) {
        Calendar cur = Calendar.getInstance();
        cur.setTimeInMillis(logic.getSelectedTimeMs());
        final MaterialTimePicker tp = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(cur.get(Calendar.HOUR_OF_DAY))
                .setMinute(cur.get(Calendar.MINUTE))
                .build();
        tp.addOnPositiveButtonClickListener(v -> {
            if (logic == null || isFinishing()) return;
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(year, month, day, tp.getHour(), tp.getMinute(), 0);
            if (logic.pickDateTime(c.getTimeInMillis(), System.currentTimeMillis())) {
                clearError();
            } else {
                showError(R.string.sendlater_sheet_time_past);
            }
            render();
        });
        tp.show(getSupportFragmentManager(), "sendlater_time");
    }

    // ================================================================== notifications hint

    private boolean notificationsDenied() {
        return Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED;
    }

    private void refreshNotificationHint() {
        notifHint.setVisibility(notificationsDenied() ? View.VISIBLE : View.GONE);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this,
                    new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) refreshNotificationHint();
    }

    // ================================================================== buttons

    private void onPrimary() {
        if (busy || logic == null || !editable) return;
        if (logic.isEditMode()) onUpdate();
        else onSchedule();
    }

    private void onSecondary(boolean fromScrim) {
        if (busy) return;
        if (!fromScrim && logic != null && logic.isEditMode() && editId > 0) {
            cancelMessage(editId);
            return;
        }
        finish(); // capture Cancel leaves everything untouched (R15)
    }

    /**
     * Edit mode "Cancel message" (UI M4): the engine cancel (alarms, notification, state
     * broadcast) runs off the main thread and the sheet closes only when it took. A row that
     * changed meanwhile (already sending, sent, cancelled) says so inline and stays open.
     */
    private void cancelMessage(final long id) {
        setBusy(true);
        io.execute(() -> {
            TapResult r;
            try {
                r = SendLaterEngine.cancel(app, id);
            } catch (RuntimeException e) {
                Log.w(TAG, "cancel failed: " + e.getClass().getSimpleName());
                r = TapResult.NOT_PENDING;
            }
            final TapResult result = r;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == TapResult.DONE) {
                    finish();
                    return;
                }
                showNotEditable();
                error.setText(TapResult.feedback(result, true));
                error.setVisibility(View.VISIBLE);
                setBusy(false);
            });
        });
    }

    /** Capture: persist -> verify -> alarms -> ACTION_SCHEDULED -> close (R21, I1, I3). */
    private void onSchedule() {
        if (!logic.isScheduleEnabled() || isDraftEmpty()) return;
        if (!logic.isSelectedTimeInFuture(System.currentTimeMillis())) {
            showError(R.string.sendlater_sheet_time_past);
            return;
        }
        final String number = logic.getE164();
        if (number == null) return;
        final ScheduledMessage msg = new ScheduledMessage.Builder()
                .createdAt(System.currentTimeMillis())
                .scheduledFor(logic.getSelectedTimeMs())
                .text(draft)
                .recipientE164(number)
                .recipientDisplayName(displayName)
                .threadItemId(threadItemId)
                .source(source)
                // R9 / D23: without a web session it is saved at R3 (the engine reminds).
                .rung(hasWebSession ? Rung.R1 : Rung.R3)
                // OD22(4): the engine evaluates it at R1 if a session exists at due time.
                .rungReason(hasWebSession ? null : RemindReason.NO_SESSION)
                .holdIfReplied(logic.isHoldIfReplied())
                .latenessPolicy(logic.getLatenessPolicy())
                .status(ScheduledMessage.Status.SCHEDULED)
                .watermark(watermark)
                .build();
        final String label = logic.getSelectedTimeLabel();
        clearError();
        setBusy(true);
        io.execute(() -> persistArmAndAnnounce(msg, label));
    }

    /** Worker thread. Nothing outside the store is touched unless the persist is verified. */
    private void persistArmAndAnnounce(ScheduledMessage msg, String label) {
        final long id;
        try {
            id = SendLaterRepository.get(app).schedule(msg, msg.getWatermark());
        } catch (PersistException e) {
            Log.w(TAG, "schedule not persisted: " + e.getClass().getSimpleName());
            runOnUiThread(this::onPersistFailed);
            return;
        }
        if (id <= 0) {
            runOnUiThread(this::onPersistFailed);
            return;
        }
        ScheduledMessage stored = msg.toBuilder().id(id).build();
        if (isExistingCapture(app, id, msg)) {
            // UI M2: the repository returned the record an identical capture saved seconds ago.
            // That sheet armed it and announced it; this one just closes.
            runOnUiThread(() -> {
                if (!isFinishing()) finish();
            });
            return;
        }
        // Deliver M2: armed under the engine lock from the stored row (never throws; a failure is
        // picked up by the next process start / permission-change re-registration).
        SendLaterEngine.registerAlarms(app, stored);
        Intent done = new Intent(SendLaterIntents.ACTION_SCHEDULED)
                .setPackage(app.getPackageName())
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id)
                .putExtra(SendLaterIntents.EXTRA_SOURCE, stored.getSource().name())
                .putExtra(SendLaterIntents.EXTRA_E164, stored.getRecipientE164())
                .putExtra(SendLaterIntents.EXTRA_DRAFT, stored.getText())
                .putExtra(SendLaterIntents.EXTRA_SCHEDULED_FOR, stored.getScheduledFor())
                .putExtra(SendLaterIntents.EXTRA_LABEL, label);
        app.sendBroadcast(done);
        // R45: the new message joins the "N scheduled" notification.
        PendingSummaryNotifier.refresh(app);
        runOnUiThread(() -> {
            if (!isFinishing()) finish();
        });
    }

    /**
     * Worker thread. True when {@code id} is an earlier record (UI M2 dedupe): its createdAt is not
     * this capture's. A failed read counts as new, so the normal arm-and-announce runs.
     */
    private static boolean isExistingCapture(Context app, long id, ScheduledMessage mine) {
        try {
            ScheduledMessage row = SendLaterRepository.get(app).get(id);
            return row != null && row.getCreatedAt() != mine.getCreatedAt();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** UI thread. Inline error; the sheet stays open and nothing else is touched (R21). */
    private void onPersistFailed() {
        if (isFinishing() || isDestroyed()) return;
        setBusy(false);
        showError(R.string.sendlater_sheet_persist_failed);
        render();
    }

    /**
     * Edit: one engine call under its lock (deliver M2) reschedules, clears the reminded mark,
     * dismisses the stale notification, re-arms the alarms and broadcasts the state change (R16).
     */
    private void onUpdate() {
        final long id = editId;
        if (id <= 0 || !logic.isScheduleEnabled()) return;
        final long now = System.currentTimeMillis();
        if (!logic.isSelectedTimeInFuture(now)) {
            showError(R.string.sendlater_sheet_time_past);
            return;
        }
        final long when = logic.getSelectedTimeMs();
        final boolean hold = logic.isHoldIfReplied();
        final LatenessPolicy policy = logic.getLatenessPolicy();
        if (isDraftEmpty()) {
            showError(R.string.sendlater_sheet_empty_draft);
            return;
        }
        // D49 / R12a: an edited text goes with the time, hold and If-late in one guarded write;
        // an untouched one is left as stored.
        final String text = logic.isTextChanged() ? logic.getText() : null;
        clearError();
        setBusy(true);
        io.execute(() -> {
            try {
                if (text != null) {
                    updateArmAndAnnounce(id, when, hold, policy, SheetViewModelLogic.editBaseline(now), text);
                } else {
                    updateArmAndAnnounce(id, when, hold, policy, SheetViewModelLogic.editBaseline(now));
                }
            } catch (RuntimeException e) {
                // OD22(8): a DB or notification error shows the persist error; the sheet stays open.
                Log.w(TAG, "update failed: " + e.getClass().getSimpleName());
                runOnUiThread(this::onPersistFailed);
            }
        });
    }

    /**
     * Worker thread. Throws on a store or notification failure (caught by {@link #onUpdate}).
     * {@code baseline} is the fresh reply baseline every time edit takes (OD31 P2, whole-branch M2).
     * A row that changed meanwhile (already sending, sent, cancelled) is refused by the engine and
     * the sheet says so inline, like Cancel message. {@code text} (D49 / R12a): the edited text,
     * stored in the same guarded write; null keeps the stored text.
     */
    private void updateArmAndAnnounce(long id, long when, boolean hold, LatenessPolicy policy,
                                      Watermark baseline, String text) {
        final TapResult result = text != null
                ? SendLaterEngine.reschedule(app, id, when, hold, policy, baseline, text)
                : SendLaterEngine.reschedule(app, id, when, hold, policy, baseline);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (result == TapResult.DONE) {
                finish();
                return;
            }
            setBusy(false);
            showNotEditable();
            String why = TapResult.feedback(result, false);
            if (why != null) {
                error.setText(why);
                error.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Time, hold and If-late only: the stored text is kept (the user did not change it). */
    private void updateArmAndAnnounce(long id, long when, boolean hold, LatenessPolicy policy,
                                      Watermark baseline) {
        updateArmAndAnnounce(id, when, hold, policy, baseline, (String) null);
    }
}
