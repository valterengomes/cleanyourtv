package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

/**
 * The "Recent apps" label above the first grid row when recent_row has put the most recently opened apps there
 * (docs/ui/launcher/SPEC.md section 3): 12sp caps in text2, drawn once with the row, no view of its own.
 */
final class RecentLabel extends RecyclerView.ItemDecoration {

    private final AppAdapter adapter;
    private final int columns;
    private final int room;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String text;

    RecentLabel(Context ctx, AppAdapter adapter, int columns, float density) {
        this.adapter = adapter;
        this.columns = columns;
        this.room = Math.round(AppAdapter.RECENT_LABEL_DP * density);
        this.text = ctx.getString(R.string.recent_apps).toUpperCase(Locale.getDefault());
        paint.setColor(ctx.getColor(R.color.lw_text2));
        paint.setTypeface(UiFont.get(ctx, R.string.font_medium));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, ctx.getResources().getDisplayMetrics()));
        paint.setLetterSpacing(0.08f);
    }

    private boolean inRecentRow(int position) {
        int start = adapter.gridStart();
        return adapter.recentCount() > 0 && position >= start && position < start + columns;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        if (inRecentRow(parent.getChildAdapterPosition(view))) outRect.top += room;
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (adapter.recentCount() == 0) return;
        int start = adapter.gridStart();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View item = parent.getChildAt(i);
            if (parent.getChildAdapterPosition(item) != start) continue;
            View card = item.findViewById(R.id.card);
            float x = item.getLeft() + (card != null ? card.getLeft() : 0);
            // The label sits in the room getItemOffsets added above the row, its baseline a little above the tiles.
            float y = item.getTop() - room * 0.35f;
            c.drawText(text, x, y, paint);
            return;
        }
    }
}
