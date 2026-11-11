package com.voice2sms.sendlater.ui;

import android.content.Context;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.voice2sms.R;
import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * The "Scheduled messages" card at the top of the launcher page (R35a, D47, OD37). A view only:
 * {@link ScheduledCardController} loads the {@link ScheduledCardModel} off the main thread, hands
 * it to {@link #show} and receives the taps through {@link Listener}.
 */
public class ScheduledCardPreference extends Preference {

    /** Taps on the card. */
    public interface Listener {
        void onRowTapped(ScheduledMessage m);

        void onSeeAllTapped();

        void onSwitchStateTapped();
    }

    private ScheduledCardModel model;
    private boolean loaded;
    private boolean failed;
    private boolean enabled = true;
    private Listener listener;

    public ScheduledCardPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ScheduledCardPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.sendlater_home_card);
        setSelectable(false);
        setPersistent(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** New card content; {@code model == null} = the load failed. Main thread. */
    public void show(ScheduledCardModel model, boolean sendLaterOn) {
        this.model = model;
        this.failed = model == null;
        this.loaded = true;
        this.enabled = sendLaterOn;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        Context c = getContext();

        TextView state = (TextView) holder.findViewById(R.id.sendlater_home_card_switch);
        state.setText(enabled ? R.string.sendlater_home_card_switch_on
                : R.string.sendlater_home_card_switch_off);
        state.setOnClickListener(v -> {
            if (listener != null) listener.onSwitchStateTapped();
        });

        LinearLayout rows = (LinearLayout) holder.findViewById(R.id.sendlater_home_card_rows);
        TextView empty = (TextView) holder.findViewById(R.id.sendlater_home_card_empty);
        Button seeAll = (Button) holder.findViewById(R.id.sendlater_home_card_see_all);
        rows.removeAllViews();

        if (!loaded || failed || model.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(!loaded ? R.string.sendlater_home_card_loading
                    : failed ? R.string.sendlater_home_card_load_failed
                    : R.string.sendlater_home_card_empty);
            seeAll.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);

        LayoutInflater inf = LayoutInflater.from(c);
        for (ScheduledMessage m : model.getRows()) rows.addView(row(inf, rows, m));

        int n = model.getTotal();
        seeAll.setVisibility(View.VISIBLE);
        seeAll.setText(c.getResources().getQuantityString(R.plurals.sendlater_home_see_all, n, n));
        seeAll.setOnClickListener(v -> {
            if (listener != null) listener.onSeeAllTapped();
        });
    }

    private View row(LayoutInflater inf, ViewGroup parent, final ScheduledMessage m) {
        Context c = getContext();
        View v = inf.inflate(R.layout.sendlater_upcoming_row, parent, false);
        v.findViewById(R.id.sendlater_upcoming_row_actions).setVisibility(View.GONE);
        ((TextView) v.findViewById(R.id.sendlater_upcoming_row_time)).setText(
                DateUtils.formatDateTime(c, m.getScheduledFor(), DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY
                        | DateUtils.FORMAT_ABBREV_ALL));
        // D39: a number without a name is shown formatted, as everywhere else.
        ((TextView) v.findViewById(R.id.sendlater_upcoming_row_name)).setText(
                ChipLabel.recipient(m.getRecipientDisplayName(), m.getRecipientE164(),
                        c.getString(R.string.sendlater_upcoming_unknown_recipient)));
        ((TextView) v.findViewById(R.id.sendlater_upcoming_row_text))
                .setText(UpcomingSections.firstLine(m.getText()));
        if (m.getStatus() == ScheduledMessage.Status.HELD) {
            TextView status = v.findViewById(R.id.sendlater_upcoming_row_status);
            status.setText(c.getString(R.string.sendlater_home_card_held) + " · "
                    + c.getString(UpcomingActivity.reasonRes(m.getHoldReason())));
            status.setVisibility(View.VISIBLE);
        }
        TypedValue bg = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true)) {
            v.setBackgroundResource(bg.resourceId);
        }
        v.setClickable(true);
        v.setOnClickListener(x -> {
            if (listener != null) listener.onRowTapped(m);
        });
        return v;
    }
}
