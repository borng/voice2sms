package com.voice2sms.sendlater.ui;

import android.content.Context;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.voice2sms.R;
import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import java.util.Date;
import java.util.TimeZone;

/**
 * The "Scheduled messages" card at the top of the launcher page (R35a, D47, OD37). A view only:
 * {@link ScheduledCardController} loads the {@link ScheduledCardModel} off the main thread, hands
 * it to {@link #show} and receives the taps through {@link Listener}.
 *
 * <p>Look (D55, mockup): rounded card surface, clock icon + title + On/Off pill, rows from
 * {@code sendlater_home_row} (time column | name + first line or Held tag + reason | chevron),
 * a full-width "See all N scheduled" button, and the empty state with a picture of the tab.
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
        holder.setDividerAllowedBelow(false);
        Context c = getContext();

        // Rounded surface: clip row ripples to the card's corners.
        View surface = holder.findViewById(R.id.sendlater_home_card_surface);
        if (surface != null) surface.setClipToOutline(true);

        // D55 header pill: On (accent fill) / Off (accent outline); opens the Send Later group.
        TextView pill = (TextView) holder.findViewById(R.id.sendlater_home_card_switch);
        pill.setText(enabled ? R.string.sendlater_home_card_pill_on : R.string.sendlater_home_card_pill_off);
        pill.setContentDescription(c.getString(enabled
                ? R.string.sendlater_home_card_pill_on_description
                : R.string.sendlater_home_card_pill_off_description));
        pill.setBackgroundResource(enabled ? R.drawable.sendlater_home_pill_bg
                : R.drawable.sendlater_home_pill_off_bg);
        pill.setTextColor(ContextCompat.getColor(c, enabled ? R.color.sendlater_on_accent_text
                : R.color.sendlater_accent_text));
        pill.setOnClickListener(v -> {
            if (listener != null) listener.onSwitchStateTapped();
        });

        LinearLayout rows = (LinearLayout) holder.findViewById(R.id.sendlater_home_card_rows);
        TextView status = (TextView) holder.findViewById(R.id.sendlater_home_card_status);
        View empty = holder.findViewById(R.id.sendlater_home_card_empty);
        TextView seeAll = (TextView) holder.findViewById(R.id.sendlater_home_card_see_all);
        View seeAllDivider = holder.findViewById(R.id.sendlater_home_card_see_all_divider);
        rows.removeAllViews();
        rows.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        seeAll.setVisibility(View.GONE);
        seeAllDivider.setVisibility(View.GONE);

        if (!loaded || failed) {
            status.setVisibility(View.VISIBLE);
            status.setText(!loaded ? R.string.sendlater_home_card_loading
                    : R.string.sendlater_home_card_load_failed);
            return;
        }
        if (model.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            ((TextView) holder.findViewById(R.id.sendlater_home_card_empty_title))
                    .setText(R.string.sendlater_home_card_empty);
            ((TextView) holder.findViewById(R.id.sendlater_home_card_empty_how))
                    .setText(R.string.sendlater_home_card_empty_how);
            ((TextView) holder.findViewById(R.id.sendlater_home_card_empty_scope))
                    .setText(R.string.sendlater_home_card_empty_scope);
            return;
        }

        rows.setVisibility(View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(c);
        long now = System.currentTimeMillis();
        for (ScheduledMessage m : model.getRows()) rows.addView(row(inf, rows, m, now));

        int n = model.getTotal();
        seeAllDivider.setVisibility(View.VISIBLE);
        seeAll.setVisibility(View.VISIBLE);
        seeAll.setText(c.getResources().getQuantityString(R.plurals.sendlater_home_see_all, n, n));
        seeAll.setOnClickListener(v -> {
            if (listener != null) listener.onSeeAllTapped();
        });
    }

    private View row(LayoutInflater inf, ViewGroup parent, final ScheduledMessage m, long now) {
        Context c = getContext();
        View v = inf.inflate(R.layout.sendlater_home_row, parent, false);
        long when = m.getScheduledFor();
        ((TextView) v.findViewById(R.id.sendlater_home_row_day)).setText(dayLabel(c, when, now));
        ((TextView) v.findViewById(R.id.sendlater_home_row_time)).setText(
                DateFormat.getTimeFormat(c).format(new Date(when)));
        // D39: a number without a name is shown formatted, as everywhere else.
        ((TextView) v.findViewById(R.id.sendlater_home_row_name)).setText(
                ChipLabel.recipient(m.getRecipientDisplayName(), m.getRecipientE164(),
                        c.getString(R.string.sendlater_upcoming_unknown_recipient)));
        TextView second = v.findViewById(R.id.sendlater_home_row_text);
        if (m.getStatus() == ScheduledMessage.Status.HELD) {
            // D55: amber Held tag next to the name; the hold reason is the second line.
            v.findViewById(R.id.sendlater_home_row_held).setVisibility(View.VISIBLE);
            second.setText(UpcomingActivity.reasonRes(m.getHoldReason()));
        } else {
            second.setText(UpcomingSections.firstLine(m.getText()));
        }
        v.setOnClickListener(x -> {
            if (listener != null) listener.onRowTapped(m);
        });
        return v;
    }

    /** Time column day label: Today, Tomorrow, a short weekday (within a week) or a short date. */
    private static String dayLabel(Context c, long when, long now) {
        switch (HomeRowTime.dayKind(when, now, TimeZone.getDefault())) {
            case TODAY:
                return c.getString(R.string.sendlater_home_row_today);
            case TOMORROW:
                return c.getString(R.string.sendlater_home_row_tomorrow);
            case WEEKDAY:
                return DateUtils.formatDateTime(c, when,
                        DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY);
            default:
                return DateUtils.formatDateTime(c, when,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        }
    }
}
