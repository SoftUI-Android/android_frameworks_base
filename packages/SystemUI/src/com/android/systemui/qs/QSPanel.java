/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;

import java.util.ArrayList;

/** View that represents the quick settings tile panel. **/
public class QSPanel extends ViewGroup {
    private static final float TILE_ASPECT = 1.4f;
    private static final float LARGE_TILE_FACTOR = 1.1f;

    private final Context mContext;
    private final ArrayList<TileRecord> mRecords = new ArrayList<TileRecord>();
    private final FrameLayout mDetail;
    private final CircularClipper mClipper;
    private final H mHandler = new H();

    private int mColumns;
    private int mCellWidth;
    private int mCellHeight;
    private int mLargeCellWidth;
    private int mLargeCellHeight;

    private TileRecord mDetailRecord;

    public QSPanel(Context context) {
        this(context, null);
    }

    public QSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

        mDetail = new FrameLayout(mContext);
        mDetail.setVisibility(GONE);
        mDetail.setClickable(true);
        addView(mDetail);
        mClipper = new CircularClipper(mDetail);
        updateResources();
    }

    public void updateResources() {
        final int columns = Math.max(1,
                mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        if (mColumns != columns) {
            mColumns = columns;
            postInvalidate();
        }
    }

    public void setExpanded(boolean expanded) {
        if (!expanded) {
            showDetail(false /*show*/, mDetailRecord);
        }
        for (TileRecord r : mRecords) {
            r.tile.setShown(expanded);
        }
    }

    private void showDetail(boolean show, TileRecord r) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0, r).sendToTarget();
    }

    private void setTileVisibility(View v, boolean visible) {
        mHandler.obtainMessage(H.SET_TILE_VISIBILITY, visible ? 1 : 0, 0, v).sendToTarget();
    }

    private void handleSetTileVisibility(View v, boolean visible) {
        v.setVisibility(visible ? VISIBLE : GONE);
    }

    public void addTile(final QSTile<?> tile) {
        final TileRecord r = new TileRecord();
        r.tile = tile;
        r.tileView = tile.createTileView(mContext);
        r.tileView.setVisibility(View.GONE);
        r.tile.setCallback(new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                setTileVisibility(r.tileView, state.visible);
                r.tileView.onStateChanged(state);
            }
            @Override
            public void onShowDetail(boolean show) {
                QSPanel.this.showDetail(show, r);
            }
        });
        final View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.tile.click();
            }
        };
        final View.OnClickListener clickSecondary = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.tile.secondaryClick();
            }
        };
        r.tileView.init(click, clickSecondary);
        mRecords.add(r);

        addView(r.tileView);
    }

    private void handleShowDetail(TileRecord r, boolean show) {
        AnimatorListener listener = null;
        if (show) {
            if (mDetailRecord != null) return;
            final View detail = r.tile.createDetailView(mContext, mDetail);
            if (detail == null) return;
            mDetailRecord = r;
            mDetail.removeAllViews();
            mDetail.bringToFront();
            mDetail.addView(detail);
        } else {
            if (mDetailRecord == null) return;
            listener = mTeardownDetailWhenDone;
        }
        int x = r.tileView.getLeft() + r.tileView.getWidth() / 2;
        int y = r.tileView.getTop() + r.tileView.getHeight() / 2;
        mClipper.animateCircularClip(x, y, show, listener);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        mCellWidth = width / mColumns;
        mCellHeight = (int)(mCellWidth / TILE_ASPECT);
        mLargeCellWidth = (int)(mCellWidth * LARGE_TILE_FACTOR);
        mLargeCellHeight = (int)(mCellHeight * LARGE_TILE_FACTOR);
        int r = 0;
        int c = 0;
        int rows = 0;
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            record.row = r;
            record.col = c;
            rows = r + 1;
            c++;
            if (c == mColumns /*end of normal column*/ || r == 0 && c == 2 /*end of 1st column*/) {
                c = 0;
                r++;
            }
        }

        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            record.tileView.setDual(record.row == 0);
            final int cw = record.row == 0 ? mLargeCellWidth : mCellWidth;
            final int ch = record.row == 0 ? mLargeCellHeight : mCellHeight;
            record.tileView.measure(exactly(cw), exactly(ch));
        }
        final int actualHeight = rows == 0 ? 0 : getRowTop(rows);
        mDetail.measure(exactly(width), exactly(actualHeight));
        setMeasuredDimension(width, actualHeight);
    }

    private static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = mCellWidth * mColumns;
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            final int cols = getColumnCount(record.row);
            final int cw = record.row == 0 ? mLargeCellWidth : mCellWidth;
            final int extra = (w - cw * cols) / (cols + 1);
            final int left = record.col * cw + (record.col + 1) * extra;
            final int top = getRowTop(record.row);
            record.tileView.layout(left, top,
                    left + record.tileView.getMeasuredWidth(),
                    top + record.tileView.getMeasuredHeight());
        }
        mDetail.layout(0, 0, mDetail.getMeasuredWidth(), mDetail.getMeasuredHeight());
    }

    private int getRowTop(int row) {
        if (row <= 0) return 0;
        return mLargeCellHeight + (row - 1) * mCellHeight;
    }

    private int getColumnCount(int row) {
        int cols = 0;
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            if (record.row == row) cols++;
        }
        return cols;
    }

    private class H extends Handler {
        private static final int SHOW_DETAIL = 1;
        private static final int SET_TILE_VISIBILITY = 2;
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SHOW_DETAIL) {
                handleShowDetail((TileRecord)msg.obj, msg.arg1 != 0);
            } else if (msg.what == SET_TILE_VISIBILITY) {
                handleSetTileVisibility((View)msg.obj, msg.arg1 != 0);
            }
        }
    }

    private static final class TileRecord {
        QSTile<?> tile;
        QSTileView tileView;
        int row;
        int col;
    }

    private final AnimatorListenerAdapter mTeardownDetailWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animation) {
            mDetail.removeAllViews();
            mDetailRecord = null;
        };
    };
}