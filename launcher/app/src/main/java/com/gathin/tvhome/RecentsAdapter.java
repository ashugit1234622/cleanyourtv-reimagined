package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Horizontal tvOS-style glass card carousel adapter for running apps and per-app RAM display.
 */
final class RecentsAdapter extends RecyclerView.Adapter<RecentsAdapter.Holder> {

    static final class Item {
        final RecentsLogic.RunningApp app;
        final ComponentName component;
        final boolean isLeanback;
        final Drawable banner;
        final Drawable icon;
        final int[] backdrop;

        Item(RecentsLogic.RunningApp app, ComponentName component, boolean isLeanback,
             Drawable banner, Drawable icon, int[] backdrop) {
            this.app = app;
            this.component = component;
            this.isLeanback = isLeanback;
            this.banner = banner;
            this.icon = icon;
            this.backdrop = backdrop;
        }
    }

    interface Listener {
        void onItemClicked(int position, View sourceArtView, Item item);
        void onCardFocused(int position);
        void onMoveDownToActions(int position);
    }

    private final Context ctx;
    private final Listener listener;
    private final boolean animations;
    private final boolean reducedMotion;
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Long> stableIds = new HashMap<>();
    private long nextId = 1L;

    RecentsAdapter(Context ctx, Listener listener, boolean animations, boolean reducedMotion) {
        this.ctx = ctx;
        this.listener = listener;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        setHasStableIds(true);
    }

    void setItems(List<Item> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
            for (Item it : newItems) {
                if (!stableIds.containsKey(it.app.packageName)) {
                    stableIds.put(it.app.packageName, nextId++);
                }
            }
        }
        notifyDataSetChanged();
    }

    Item itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    List<Item> allItems() {
        return new ArrayList<>(items);
    }

    void removeAt(int position) {
        if (position < 0 || position >= items.size()) return;
        items.remove(position);
        notifyItemRemoved(position);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= items.size()) return RecyclerView.NO_ID;
        Long id = stableIds.get(items.get(position).app.packageName);
        return id != null ? id : RecyclerView.NO_ID;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(ctx).inflate(R.layout.item_recent_app, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Item item = items.get(position);
        h.name.setText(item.app.label);
        h.ramBadge.setText(ctx.getString(R.string.recents_ram_label,
                RecentsLogic.formatRamCompact(item.app.rssKb)));

        if (item.banner != null) {
            h.banner.setImageDrawable(item.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            if (item.banner.getOpacity() == PixelFormat.OPAQUE) {
                h.cardArt.setBackground(null);
            } else {
                h.cardArt.setBackgroundResource(R.drawable.tile_bg);
            }
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(item.icon);
            h.icon.setVisibility(View.VISIBLE);
            if (item.backdrop != null) {
                h.cardArt.setBackground(new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, item.backdrop));
            } else {
                h.cardArt.setBackgroundResource(R.drawable.tile_bg);
            }
        }

        h.itemView.setAlpha(1f);
        h.itemView.setTranslationY(0f);
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        applyFocusVisuals(h, h.card.isFocused(), false);

        h.card.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onItemClicked(pos, h.cardArt, items.get(pos));
            }
        });

        h.card.setOnFocusChangeListener((v, focused) -> {
            applyFocusVisuals(h, focused, animations && !reducedMotion);
            if (focused) {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onCardFocused(pos);
                }
            }
        });

        h.card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int pos = h.getBindingAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onMoveDownToActions(pos);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos == items.size() - 1) {
                return true;
            }
            return false;
        });
    }

    @Override
    public void onViewRecycled(@NonNull Holder h) {
        super.onViewRecycled(h);
        h.itemView.animate().cancel();
        h.itemView.setAlpha(1f);
        h.itemView.setTranslationY(0f);
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        h.card.animate().cancel();
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
    }

    private void applyFocusVisuals(Holder h, boolean focused, boolean animate) {
        h.focusRing.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        float density = ctx.getResources().getDisplayMetrics().density;
        float targetScale = focused ? (animations ? 1.07f : 1f) : 1f;
        float targetZ = focused ? 10f * density : 0f;
        h.card.setTranslationZ(targetZ);
        h.name.setAlpha(focused ? 1f : 0.78f);
        h.ramBadge.setAlpha(focused ? 1f : 0.85f);

        h.card.animate().cancel();
        if (animate) {
            h.card.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(focused ? 170L : 140L)
                    .setInterpolator(focused ? new OvershootInterpolator(1.35f) : new DecelerateInterpolator(1.5f))
                    .start();
        } else {
            h.card.setScaleX(targetScale);
            h.card.setScaleY(targetScale);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final FrameLayout cardArt;
        final ImageView banner;
        final ImageView icon;
        final View focusRing;
        final TextView name;
        final TextView ramBadge;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.recentCard);
            cardArt = itemView.findViewById(R.id.recentCardArt);
            banner = itemView.findViewById(R.id.recentBanner);
            icon = itemView.findViewById(R.id.recentIcon);
            focusRing = itemView.findViewById(R.id.recentFocusRing);
            name = itemView.findViewById(R.id.recentName);
            ramBadge = itemView.findViewById(R.id.recentRamBadge);

            final float radiusPx = 18f * itemView.getResources().getDisplayMetrics().density;
            ViewOutlineProvider rounded = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    if (view.getWidth() > 0 && view.getHeight() > 0) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                    }
                }
            };
            cardArt.setOutlineProvider(rounded);
            cardArt.setClipToOutline(true);
            card.setOutlineProvider(rounded);
            card.setClipToOutline(false);
        }
    }
}

