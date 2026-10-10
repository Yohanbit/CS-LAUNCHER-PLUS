package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.mcAccountSpinner;
import net.kdt.pojavlaunch.PojavProfile;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tutorial.DragTutorialHost;
import net.kdt.pojavlaunch.ui.PremiumPlayButtonView;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.time.Instant;
import android.widget.ProgressBar;

import com.kdt.mcgui.ProgressLayout;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compact launcher home with an explicit instance selector and direct play action.
 * Saved profile order, account switching, and instance management keep their
 * existing behavior. No animated GL stage is created on this screen.
 */
public class LauncherHomeFragment extends Fragment implements DragTutorialHost {

    public static final String TAG = "LauncherHomeFragment";

    private List<String> mKeys = new ArrayList<>();
    private List<MinecraftProfile> mProfiles = new ArrayList<>();
    private String mPrimaryKey = null;
    private TextView mLaunchLabel;
    private PremiumPlayButtonView mLaunchButton;
    private View mLaunchSpecs;
    private TextView mSpecJavaText, mSpecRamText;
    private TextView mInstanceName, mInstanceMeta;
    private View mInstanceSelector, mEditButton;
    private androidx.appcompat.app.AlertDialog mInstancePicker;
    private net.kdt.pojavlaunch.multirt.MultiRTConfigDialog mRuntimePicker;
    private final androidx.activity.result.ActivityResultLauncher<Object> mRuntimeInstallLauncher =
            registerForActivityResult(new net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension("xz"), data -> {
                Context context = getContext();
                if (data != null && context != null) Tools.installRuntimeFromUri(context, data);
            });

    // Chrome
    private LinearLayout mAccountChip;
    private ImageView mAccountHead;
    private TextView mAccountName, mAccountType;
    private View mEmptyHint;

    // Library
    private RecyclerView mLibrary;
    private InstanceLibraryAdapter mAdapter;
    private ItemTouchHelper mTouchHelper;

    // ── Drag tutorial host (transient demo cards, never persisted) ──
    private static final String DEMO_KEY_STEVE = "\0tutorial.demo.steve";
    private static final String DEMO_KEY_ALEX  = "\0tutorial.demo.alex";
    @Nullable private DragTutorialHost.DemoPracticeListener mDemoPracticeListener;
    private boolean mPracticeMovedThisGesture = false;

    // ── Compact download progress pill ──
    private View mDlPill;
    private TextView mDlTitle, mDlPercent, mDlDetail;
    private ProgressBar mDlBar;
    private boolean mDlListening = false;
    public LauncherHomeFragment() {
        super(R.layout.fragment_launcher_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mLaunchButton = view.findViewById(R.id.lh_launch_button);
        mLaunchLabel = view.findViewById(R.id.lh_launch_label);
        mLaunchSpecs = view.findViewById(R.id.lh_launch_specs);
        mSpecJavaText = view.findViewById(R.id.lh_spec_java_text);
        mSpecRamText = view.findViewById(R.id.lh_spec_ram_text);
        mInstanceName = view.findViewById(R.id.lh_instance_name);
        mInstanceMeta = view.findViewById(R.id.lh_instance_meta);
        mInstanceSelector = view.findViewById(R.id.lh_instance_selector);
        mInstanceSelector.setOnClickListener(v -> showInstancePicker());
        view.findViewById(R.id.lh_new_instance).setOnClickListener(v -> CreationTypeDialog.show(this));
        view.findViewById(R.id.lh_tool_skins).setOnClickListener(v ->
                navigateTo(SkinLibraryOverviewFragment.class, SkinLibraryOverviewFragment.TAG, null));
        view.findViewById(R.id.lh_tool_controls).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        view.findViewById(R.id.lh_tool_runtime).setOnClickListener(v -> {
            if (mRuntimePicker == null) {
                mRuntimePicker = new net.kdt.pojavlaunch.multirt.MultiRTConfigDialog();
                mRuntimePicker.prepare(requireActivity(), mRuntimeInstallLauncher);
            }
            mRuntimePicker.show();
        });
        view.findViewById(R.id.lh_tool_cursor).setOnClickListener(v ->
                navigateTo(CursorCustomizationFragment.class, CursorCustomizationFragment.TAG, null));
        view.findViewById(R.id.lh_tool_settings).setOnClickListener(v ->
                navigateTo(net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment.class,
                        net.kdt.pojavlaunch.LauncherActivity.SETTING_FRAGMENT_TAG, null));
        styleInstancePanel(view.findViewById(R.id.lh_instance_panel));
        mAccountChip = view.findViewById(R.id.lh_account_chip);
        mAccountHead = view.findViewById(R.id.lh_account_head);
        mAccountName = view.findViewById(R.id.lh_account_name);
        mAccountType = view.findViewById(R.id.lh_account_type);
        mEmptyHint = view.findViewById(R.id.lh_empty);
        mLibrary = view.findViewById(R.id.lh_library);

        mDlPill = view.findViewById(R.id.lh_dl_pill);
        mDlTitle = view.findViewById(R.id.lh_dl_title);
        mDlPercent = view.findViewById(R.id.lh_dl_percent);
        mDlDetail = view.findViewById(R.id.lh_dl_detail);
        mDlBar = view.findViewById(R.id.lh_dl_bar);

        setupLibrary();

        // PENCIL → edit page of the SELECTED profile (same flow as ⋮ Edit Instance).
        View editBtn = mEditButton = view.findViewById(R.id.lh_edit_btn);
        if (editBtn != null) {
            editBtn.setOnClickListener(v -> {
                if (mPrimaryKey == null) return;
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, mPrimaryKey).apply();
                navigateTo(ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
            });
        }

        // Play / create — the one dominant action.
        mLaunchButton.setOnClickListener(v -> {
            if (mLaunchButton.isStopMode()) {
                mLaunchButton.cancelPendingLaunch();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                return;
            }
            if (mLaunchButton.isLaunching()) return;
            if (mPrimaryKey == null) {
                CreationTypeDialog.show(this);
                return;
            }
            mLaunchButton.beginLaunch();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            launch(mPrimaryKey);
        });

        mAccountChip.setOnClickListener(v -> switchAccount());

        // Pad interactive content out of real cutouts in either orientation.
        final View content = view.findViewById(R.id.lh_content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int l = 0, t = 0, r = 0, b = 0;
            androidx.core.view.DisplayCutoutCompat cut = insets.getDisplayCutout();
            if (cut != null) {
                l = cut.getSafeInsetLeft();
                t = cut.getSafeInsetTop();
                r = cut.getSafeInsetRight();
                b = cut.getSafeInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        // The activity consumes insets at its root, so the listener above may
        // never fire under edge-to-edge. Apply the real cutout padding directly
        // from the root window insets as well, so the account chip / controls stay
        // clear of a notch in fullscreen landscape regardless of dispatch.
        content.post(() -> applyCutoutPaddingDirect(content));

        // Real account switches (spinner pick / add / remove) refresh Home instantly.
        ExtraCore.addExtraListener(ExtraConstants.ACCOUNT_CHANGED, mAccountChangedListener);

        // Expose the drag-tutorial host before the tutorial may start.
        DragTutorialHost.Registry.register(this);

    }

    /**
     * Pads the interactive layer out of the real display cutout using the root
     * window insets directly — independent of the inset-dispatch chain (the
     * activity consumes insets at its root). Safe in either orientation.
     */
    private void applyCutoutPaddingDirect(@NonNull View content) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            android.view.WindowInsets wi = content.getRootWindowInsets();
            if (wi == null) return;
            android.view.DisplayCutout cut = wi.getDisplayCutout();
            if (cut == null) { content.setPadding(0, 0, 0, 0); return; }
            content.setPadding(cut.getSafeInsetLeft(), cut.getSafeInsetTop(),
                    cut.getSafeInsetRight(), cut.getSafeInsetBottom());
        } catch (Throwable ignored) {}
    }

    /**
     * Phase 11 (item 1): profiles written behind Home's back (a modloader
     * installer that just finished, a rename applied by InstallerHandoff) show
     * up without leaving and re-entering the page. Registered while resumed only.
     */
    private final Runnable mProfilesWrittenListener = () -> {
        if (!isAdded() || getView() == null) return;
        // From memory only: LauncherProfiles.load() itself fires this listener,
        // so calling loadData() (which loads) from here would loop forever.
        rebindProfilesFromMemory();
    };

    private final ExtraListener<String> mAccountChangedListener = (key, value) -> {
        if (!isAdded() || getView() == null) return false;
        bindAccount();
        try {
            net.kdt.pojavlaunch.value.MinecraftAccount acc =
                    PojavProfile.getCurrentProfileContent(requireContext(), null);
            if (acc != null && acc.username != null)
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Account switched", acc.username);
        } catch (Throwable ignored) {}
        return false;
    };


    private void styleInstancePanel(View panel) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFF1B2027);
        background.setCornerRadius(16f * getResources().getDisplayMetrics().density);
        background.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), 0xFF303943);
        panel.setBackground(background);
    }

    /** Explicit selector; selection uses the same persisted order as card taps. */
    private void showInstancePicker() {
        if (mKeys.isEmpty()) {
            CreationTypeDialog.show(this);
            return;
        }
        if (mInstancePicker != null && mInstancePicker.isShowing()) return;
        final List<String> keys = new ArrayList<>(mKeys);
        final List<MinecraftProfile> profiles = new ArrayList<>(mProfiles);
        String[] labels = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            MinecraftProfile profile = profiles.get(i);
            labels[i] = profile.name + " — " + displayVersion(profile.lastVersionId);
        }
        mInstancePicker = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Choose instance")
                .setSingleChoiceItems(labels, Math.max(0, keys.indexOf(mPrimaryKey)), (dialog, which) -> {
                    promoteToPrimary(keys.get(which), profiles.get(which));
                    dialog.dismiss();
                })
                .setPositiveButton("New instance", (dialog, which) -> CreationTypeDialog.show(this))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mInstancePicker.show();
    }

    private final ProgressListener mDlListener = new ProgressListener() {
        @Override public void onProgressStarted() {
            if (getActivity() != null) getActivity().runOnUiThread(() -> showDlPill(true));
        }
        @Override public void onProgressUpdated(int progress, int resid, Object... va) {
            if (getActivity() == null) return;
            final String detail = extractDlDetail(va);
            final String title = safeResString(resid);
            getActivity().runOnUiThread(() -> {
                showDlPill(true);
                if (mDlBar != null && progress >= 0) animateBarTo(Math.min(100, progress));
                if (mDlPercent != null) mDlPercent.setText((progress >= 0 ? progress : 0) + "%");
                if (mDlTitle != null && title != null) mDlTitle.setText(title);
                if (mDlDetail != null) {
                    mDlDetail.setText(detail == null ? "" : detail);
                    mDlDetail.setVisibility(detail == null || detail.isEmpty()
                            ? View.GONE : View.VISIBLE);
                }
            });
        }
        @Override public void onProgressEnded() {
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (ProgressKeeper.getTaskCount() <= 0) showDlPill(false);
            });
        }
    };

    private String safeResString(int resid) {
        try { return resid != 0 && getContext() != null ? getString(resid) : "Downloading"; }
        catch (Throwable t) { return "Downloading"; }
    }

    /** Pull "x.x MB / y.y MB" from the downloader's variadic payload when present. */
    private String extractDlDetail(Object... va) {
        try {
            if (va != null && va.length >= 3
                    && va[1] instanceof Number && va[2] instanceof Number) {
                double cur = ((Number) va[1]).doubleValue();
                double total = ((Number) va[2]).doubleValue();
                if (total > 0) return String.format(Locale.US, "%.1f MB / %.1f MB", cur, total);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Honest progress only: no decorative polling, shimmer or pulse. */
    private void animateBarTo(int target) {
        if (mDlBar != null) mDlBar.setProgress(target);
    }

    private void showDlPill(boolean show) {
        if (getView() == null || mDlPill == null) return;
        mDlPill.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void registerDlListeners() {
        if (mDlListening) return;
        mDlListening = true;
        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, mDlListener);
        ProgressKeeper.addListener(ProgressLayout.UNPACK_RUNTIME, mDlListener);
        ProgressKeeper.addListener(ProgressLayout.INSTALL_MODPACK, mDlListener);
        if (ProgressKeeper.getTaskCount() <= 0) showDlPill(false);
    }

    private void unregisterDlListeners() {
        if (!mDlListening) return;
        mDlListening = false;
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mDlListener);
        ProgressKeeper.removeListener(ProgressLayout.UNPACK_RUNTIME, mDlListener);
        ProgressKeeper.removeListener(ProgressLayout.INSTALL_MODPACK, mDlListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        LauncherProfiles.addUpdateListener(mProfilesWrittenListener);
        loadData();
        bindAccount();
        registerDlListeners();
    }

    @Override
    public void onPause() {
        LauncherProfiles.removeUpdateListener(mProfilesWrittenListener);
        unregisterDlListeners();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        DragTutorialHost.Registry.unregister(this);
        mDemoPracticeListener = null;
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.ACCOUNT_CHANGED, mAccountChangedListener);
        if (mMenu != null) { mMenu.dismiss(); mMenu = null; }
        if (mInstancePicker != null) { mInstancePicker.dismiss(); mInstancePicker = null; }
        unregisterDlListeners();
        if (mTouchHelper != null) mTouchHelper.attachToRecyclerView(null);
        if (mLibrary != null) mLibrary.setAdapter(null);
        mLibrary = null;
        mAdapter = null;
        mDlPill = null;
        mDlTitle = mDlPercent = mDlDetail = null;
        mDlBar = null;
        mAccountHead = null;
        mAccountChip = null;
        mAccountName = mAccountType = null;
        mLaunchButton = null;
        mLaunchLabel = null;
        mInstanceSelector = mEditButton = mLaunchSpecs = null;
        mInstanceName = mInstanceMeta = mSpecJavaText = mSpecRamText = null;
        mEmptyHint = null;
        mTouchHelper = null;
        mRuntimePicker = null;
        super.onDestroyView();
    }

    private void loadData() {
        LauncherProfiles.loadAsync(() -> {
            if (!isAdded() || getView() == null) return;
            // Installer-created profiles (Forge/NeoForge/OptiFine) get the
            // user's chosen name + icon applied as soon as we see them.
            try {
                net.kdt.pojavlaunch.profiles.PendingProfileRename.apply(requireContext());
            } catch (Throwable ignored) {}
            rebindProfilesFromMemory();
        });
    }

    /** Rebuild selection + library from the already-loaded profile model. */
    private void rebindProfilesFromMemory() {
        List<String> keys = new ArrayList<>();
        List<MinecraftProfile> profiles = new ArrayList<>();
        if (LauncherProfiles.mainProfileJson != null) {
            for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.getOrderedEntries()) {
                String key = e.getKey();
                MinecraftProfile p = e.getValue();
                if (key == null || key.isEmpty()) continue;
                if (p == null || p.name == null || p.name.trim().isEmpty()) continue;
                keys.add(key);
                profiles.add(p);
            }
        }
        Tools.runOnUiThread(() -> {
            if (!isAdded() || getView() == null) return;
            mKeys = keys;
            mProfiles = profiles;
            bindStage();
            bindLibrary();
        });
    }

    // Explicit selection is separate from ordering/favorite groups.

    private void bindStage() {
        if (mKeys.isEmpty()) {
            mPrimaryKey = null;
            mLaunchLabel.setText("Create instance");
            mLaunchButton.setContentDescription("Create instance");
            mInstanceName.setText("Create your first instance");
            mInstanceMeta.setText("Choose a Minecraft version to get started");
            mInstanceSelector.setContentDescription("Create your first instance");
            mLaunchSpecs.setVisibility(View.GONE);
            mEditButton.setVisibility(View.GONE);
            return;
        }
        String selected = LauncherPreferences.DEFAULT_PREF.getString(
                LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        int selectedIndex = mKeys.indexOf(selected);
        if (selectedIndex < 0) selectedIndex = 0;
        mPrimaryKey = mKeys.get(selectedIndex);
        MinecraftProfile p = mProfiles.get(selectedIndex);
        mLaunchLabel.setText("Play");
        mLaunchButton.setContentDescription("Play " + p.name);
        mInstanceName.setText(p.name);
        String version = displayVersion(p.lastVersionId);
        String loader = loaderName(p.lastVersionId);
        mInstanceMeta.setText(version + (loader == null ? "" : " · " + loader));
        mInstanceSelector.setContentDescription("Selected instance: " + p.name + ". Choose instance");
        mEditButton.setVisibility(View.VISIBLE);
        mLaunchSpecs.setVisibility(View.VISIBLE);
        mSpecJavaText.setText(resolveJavaVersion(p));
        mSpecRamText.setText(resolveAllocatedRam());
    }

    // ── Library carousel ────────────────────────────────────────────────────

    private void bindLibrary() {
        boolean empty = mKeys.isEmpty();
        mEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            mLibrary.setVisibility(View.GONE);
            return;
        }
        mLibrary.setVisibility(View.VISIBLE);
        if (mAdapter != null) mAdapter.updateData(mKeys, mProfiles);
    }

    private void setupLibrary() {
        LinearLayoutManager lm = new LinearLayoutManager(getContext(),
                LinearLayoutManager.HORIZONTAL, false);
        mLibrary.setLayoutManager(lm);
        new LinearSnapHelper().attachToRecyclerView(mLibrary);

        mAdapter = new InstanceLibraryAdapter(mKeys, mProfiles, new InstanceLibraryAdapter.Listener() {
            @Override public void onPromote(String key, MinecraftProfile profile) {
                promoteToPrimary(key, profile);
            }
            @Override public void onPlay(String key, MinecraftProfile profile) {
                mLaunchButton.beginLaunch();
                launch(key);
            }
            @Override public void onMore(@NonNull View anchor, String key, MinecraftProfile profile) {
                showInstanceMenu(anchor, key, profile);
            }
            @Override public void onCreateInstance() {
                CreationTypeDialog.show(LauncherHomeFragment.this);
            }
            @Override public void onOrderChanged(List<String> orderedKeys) {
                LauncherProfiles.applyProfileOrder(orderedKeys);
                loadData();   // primary (first) may have changed → re-theme stage
            }
        }) {
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                // The library's default accent marks index zero. Here it must
                // match the explicit selection even when favorites lead order.
                View accent = holder.itemView.findViewById(R.id.lib_card_primary);
                if (accent != null) accent.setVisibility(
                        position == indexOfKey(mPrimaryKey) ? View.VISIBLE : View.GONE);
            }
        };
        mAdapter.setDragStartListener(holder -> {
            if (mTouchHelper != null) mTouchHelper.startDrag(holder);
        });
        mLibrary.setAdapter(mAdapter);

        mTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            /**
             * Press and hold a card, then drag it to the front: the whole card is
             * grabbable, not just the small dotted handle. The handle keeps working
             * (its own listener still calls startDrag), and the "+ create" tile is
             * excluded, so its tap is untouched.
             */
            @Override public boolean isLongPressDragEnabled() { return true; }
            @Override public boolean isItemViewSwipeEnabled() { return false; }
            /** Tutorial practice signal: a REAL long-press matured into drag state. */
            @Override public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    mPracticeMovedThisGesture = false;
                    if (mDemoPracticeListener != null) mDemoPracticeListener.onPracticeDragStart();
                }
            }
            @Override public int getMovementFlags(@NonNull RecyclerView rv,
                                                  @NonNull RecyclerView.ViewHolder vh) {
                if (vh instanceof InstanceLibraryAdapter.CreateHolder) return 0;
                return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }
            @Override public boolean onMove(@NonNull RecyclerView rv,
                                            @NonNull RecyclerView.ViewHolder from,
                                            @NonNull RecyclerView.ViewHolder to) {
                if (mAdapter == null || to instanceof InstanceLibraryAdapter.CreateHolder) return false;
                int f = from.getBindingAdapterPosition(), t = to.getBindingAdapterPosition();
                boolean moved = mAdapter.moveItem(f, t);
                if (moved) {
                    from.itemView.setElevation(10f * getResources().getDisplayMetrics().density);
                    from.itemView.setScaleX(1.05f);
                    from.itemView.setScaleY(1.05f);
                    // Tutorial practice signal: while holding, the card truly
                    // crossed another slot (a real reorder, not a wiggle).
                    if (f != t) {
                        mPracticeMovedThisGesture = true;
                        if (mDemoPracticeListener != null) mDemoPracticeListener.onPracticeDragMoved();
                    }
                }
                return moved;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
            @Override public void clearView(@NonNull RecyclerView rv,
                                            @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                vh.itemView.setElevation(0f);
                if (mAdapter != null) mAdapter.dispatchOrderChanged();
                // Tutorial practice completes ONLY on release after a real reorder.
                if (mPracticeMovedThisGesture) {
                    mPracticeMovedThisGesture = false;
                    if (mDemoPracticeListener != null) mDemoPracticeListener.onPracticeComplete();
                }
            }
        });
        mTouchHelper.attachToRecyclerView(mLibrary);
    }

    // ── DragTutorialHost implementation ─────────────────────────────────────

    private static MinecraftProfile buildDemoProfile(String displayName) {
        MinecraftProfile p = new MinecraftProfile();
        p.name = displayName;
        p.type = "custom";
        p.lastVersionId = MinecraftProfile.DEFAULT_VERSION;
        p.icon = "default";
        // No banner fetch for demo cards (transient + offline friendly).
        p.background = null;
        p.favorite = false;
        return p;
    }

    @Override
    public boolean beginDragDemo() {
        if (!isAdded() || getView() == null || mAdapter == null || mLibrary == null) return false;
        int realCount = mAdapter.getRealCount();
        if (realCount <= 0) {
            // No real instances yet: seed TWO demo cards so "the front" exists
            // and a physical reorder is possible during practice.
            mAdapter.insertDemoItem(DEMO_KEY_ALEX, buildDemoProfile("Alex"), 0);
            mAdapter.insertDemoItem(DEMO_KEY_STEVE, buildDemoProfile("Steve"), 1);
        } else {
            // Real primary owns slot 0; Steve sits beside it, ready to be
            // dragged to the front.
            MinecraftProfile steve = buildDemoProfile("Steve");
            if (realCount == 1 && mProfiles != null && !mProfiles.isEmpty()
                    && mProfiles.get(0).favorite) {
                // Keep the demo inside the same favorite group so holding and
                // dragging Steve past the only real card stays a legal move.
                steve.favorite = true;
            }
            mAdapter.insertDemoItem(DEMO_KEY_STEVE, steve, 1);
        }
        if (mEmptyHint != null) mEmptyHint.setVisibility(View.GONE);
        mLibrary.setVisibility(View.VISIBLE);
        mLibrary.stopScroll();
        mLibrary.scrollToPosition(0);
        return true;
    }

    @Override
    public boolean hasRealProfiles() {
        return getRealProfileCount() > 0;
    }

    @Override
    public int getRealProfileCount() {
        if (mAdapter != null) return mAdapter.getRealCount();
        return mKeys != null ? mKeys.size() : 0;
    }

    @Nullable
    private RecyclerView.ViewHolder holderForKey(String key) {
        if (mAdapter == null || mLibrary == null) return null;
        int pos = mAdapter.indexOfKey(key);
        if (pos < 0) return null;
        return mLibrary.findViewHolderForAdapterPosition(pos);
    }

    @Nullable
    private static Rect viewRectOnScreen(@Nullable View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    @Override
    @Nullable
    public View getDemoCardView() {
        RecyclerView.ViewHolder vh = holderForKey(DEMO_KEY_STEVE);
        return vh != null ? vh.itemView : null;
    }

    @Override
    @Nullable
    public Rect getDemoCardScreenRect() {
        return viewRectOnScreen(getDemoCardView());
    }

    @Override
    @Nullable
    public Rect getFrontSlotScreenRect() {
        if (mAdapter == null || mLibrary == null) return null;
        RecyclerView.ViewHolder vh0 = mLibrary.findViewHolderForAdapterPosition(0);
        Rect r = vh0 != null ? viewRectOnScreen(vh0.itemView) : null;
        if (r != null) return r;
        return viewRectOnScreen(mLibrary);
    }

    @Override
    public void resetDemoCardTransform() {
        View card = getDemoCardView();
        if (card == null) return;
        card.animate().cancel();
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setElevation(0f);
    }

    @Override
    public void removeDemoCardsAfterDemo(boolean keepSteveForPractice) {
        if (mAdapter == null) return;
        resetDemoCardTransform();
        mAdapter.removeDemoItem(DEMO_KEY_ALEX);
        if (!keepSteveForPractice) {
            mAdapter.removeDemoItem(DEMO_KEY_STEVE);
        }
    }

    @Override
    public void setDemoPracticeListener(@Nullable DragTutorialHost.DemoPracticeListener listener) {
        mDemoPracticeListener = listener;
    }

    @Override
    public void endDragTutorial() {
        mDemoPracticeListener = null;
        mPracticeMovedThisGesture = false;
        if (mAdapter != null) {
            resetDemoCardTransform();
            mAdapter.removeDemoItems();
        }
        // Restore the truthful library state (empty hint / binding) from storage.
        View v = getView();
        if (isAdded() && v != null) {
            v.post(() -> { if (isAdded()) loadData(); });
        }
    }

    /** Select the instance and retain the existing card-order/favorite behavior. */
    private void promoteToPrimary(String key, MinecraftProfile profile) {
        // Tutorial demo cards can never be promoted or persisted.
        if (mAdapter != null && mAdapter.isDemoKey(key)) return;
        if (key.equals(mPrimaryKey)) return;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
        List<String> order = new ArrayList<>(mKeys);
        order.remove(key);
        if (profile.favorite) {
            order.add(0, key);
        } else {
            int favCount = 0;
            for (MinecraftProfile p : mProfiles) if (p.favorite) favCount++;
            order.add(Math.min(favCount, order.size()), key);
        }
        LauncherProfiles.applyProfileOrder(order);
        loadData();
    }

    // ── Contextual instance menu (anchored, clamped to screen) ──────────────

    private android.widget.PopupWindow mMenu = null;

    private void showInstanceMenu(@NonNull View anchor, String key, MinecraftProfile profile) {
        Context ctx = getContext();
        if (ctx == null) return;
        float dp = getResources().getDisplayMetrics().density;

        // Opening another card's menu → close the previous one cleanly first.
        if (mMenu != null) {
            android.widget.PopupWindow old = mMenu;
            mMenu = null;
            old.dismiss();
        }

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_lh_menu);
        panel.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));

        boolean isPrimary = key.equals(mPrimaryKey);
        if (!isPrimary) {
            panel.addView(menuRow(ctx, dp, R.drawable.ic_layers, "Set as Primary",
                    () -> promoteToPrimary(key, profile)));
        }
        panel.addView(menuRow(ctx, dp, R.drawable.ic_play_arrow, "Play",
                () -> { mLaunchButton.beginLaunch(); launch(key); }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_edit_profile, "Edit Instance",
                () -> {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
                    navigateTo(ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
                }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_search, "Download Resources",
                () -> {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
                    Bundle args = new Bundle(1);
                    args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, key);
                    navigateTo(ModsSearchFragment.class, ModsSearchFragment.TAG, args);
                }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_shortcut_add, "Create Shortcut",
                () -> {
                    Bundle args = new Bundle(1);
                    args.putString(net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.ARG_PROFILE_KEY, key);
                    navigateTo(net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.class,
                            net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.TAG, args);
                }));
        panel.addView(menuRow(ctx, dp,
                profile.favorite ? R.drawable.ic_csp_star_filled : R.drawable.ic_csp_star_outline,
                profile.favorite ? "Remove from Favorites" : "Add to Favorites",
                () -> {
                    LauncherProfiles.setFavorite(key, !profile.favorite);
                    loadData();
                }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_menu_delete_forever, "Delete",
                () -> confirmDeleteInstance(key, profile)));

        // Measure with a real width spec so wrap_content + clamping both work.
        panel.measure(
                View.MeasureSpec.makeMeasureSpec((int) (232 * dp), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.max(panel.getMeasuredWidth(), (int) (196 * dp));
        int h = panel.getMeasuredHeight();

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(panel, w,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(14 * dp);
        popup.setClippingEnabled(true);
        popup.setOnDismissListener(() -> {
            if (mMenu == popup) mMenu = null;
            // The ⋮ relaxes back to rest when the menu closes.
            anchor.animate().cancel();
            anchor.animate().rotation(0f).scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                    .start();
        });
        mMenu = popup;

        // ── Positioning: anchored to THIS card's ⋮ button, always fully on-screen.
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        android.graphics.Rect screen = new android.graphics.Rect();
        requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(screen);
        int margin = (int) (6 * dp);

        // Prefer opening toward the screen center, then clamp to the safe area.
        int x = loc[0] + anchor.getWidth() - w + (int) (8 * dp);   // left of the ⋮
        if (x + w / 2f > screen.centerX()) {                        // near right edge → flip leftward
            x = loc[0] - w - (int) (8 * dp);
        }
        x = Math.max(screen.left + margin, Math.min(x, screen.right - margin - w));

        boolean below = loc[1] + anchor.getHeight() + h + (int) (10 * dp) <= screen.bottom;
        int y = below ? loc[1] + anchor.getHeight() + (int) (4 * dp)
                      : loc[1] - h - (int) (4 * dp);                // near bottom → open upward

        // The ⋮ springs open, so it reads as a real control and not a glyph.
        anchor.animate().cancel();
        anchor.setPivotX(anchor.getWidth() / 2f);
        anchor.setPivotY(anchor.getHeight() / 2f);
        anchor.animate().rotation(90f).scaleX(1.18f).scaleY(1.18f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY,
                x, Math.max(screen.top + margin, y));

        // ── Opening animation: fade + scale + drift from the anchor corner.
        boolean fromRight = (loc[0] + anchor.getWidth() / 2f) > screen.centerX();
        panel.setPivotX(fromRight ? w * 0.9f : w * 0.1f);
        panel.setPivotY(below ? 0f : h);
        panel.setAlpha(0f);
        panel.setScaleX(0.88f);
        panel.setScaleY(0.88f);
        panel.setTranslationY(below ? 10 * dp : -10 * dp);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Eased close, then run the picked action (menus never just vanish). */
    private void closeMenuAnimated(@Nullable Runnable after) {
        if (mMenu == null) {
            if (after != null) after.run();
            return;
        }
        final android.widget.PopupWindow popup = mMenu;
        mMenu = null;
        View content = popup.getContentView();
        content.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(130)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    popup.dismiss();
                    if (after != null) after.run();
                })
                .start();
    }

    // ── Delete instance (confirm → real delete → instant refresh) ───────────

    /** Premium confirmation: dark card, fade + scale + rise, dim behind. */
    private void confirmDeleteInstance(String key, MinecraftProfile profile) {
        android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(22*dp), (int)(20*dp), (int)(22*dp), (int)(16*dp));
        card.setBackgroundResource(R.drawable.bg_vp_card);

        TextView title = new TextView(requireContext());
        title.setText("Delete Instance?");
        title.setTextColor(0xFFF2F3F5);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView message = new TextView(requireContext());
        String name = profile != null && profile.name != null ? profile.name : key;
        message.setText("Are you sure you want to delete \"" + name + "\"?\nThis action cannot be undone.");
        message.setTextColor(0xFF9CA3AF);
        message.setTextSize(13);
        message.setLineSpacing((int)(2*dp), 1f);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        TextView cancel = pillButton("CANCEL", 0xFFC4B5FD, dp);
        TextView delete = pillButton("DELETE", 0xFFFF6B81, dp);
        buttons.addView(cancel);
        buttons.addView(delete);

        card.addView(title);
        card.addView(message);
        android.widget.LinearLayout.LayoutParams mp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = (int)(14*dp);
        card.addView(buttons, mp);

        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.55f);
        }
        dialog.show();

        // Entrance: fade + scale 92→100% + rise.
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.setTranslationY(16f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                .start();

        cancel.setOnClickListener(v -> {
            UiMotion.pressFeedback(v);
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(130)
                    .withEndAction(dialog::dismiss)
                    .start();
        });
        delete.setOnClickListener(v -> {
            UiMotion.pressFeedback(v);
            dialog.dismiss();
            deleteInstance(key);
        });
    }

    private TextView pillButton(String label, int color, float dp) {
        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(12.5f);
        tv.setLetterSpacing(0.1f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding((int)(16*dp), (int)(10*dp), (int)(16*dp), (int)(10*dp));
        tv.setBackgroundResource(R.drawable.bg_cs_ghost_button);
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int)(8*dp);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** Deletes the profile, reselects primary, and refreshes Home immediately. */
    private void deleteInstance(String key) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                LauncherProfiles.load();
                LauncherProfiles.mainProfileJson.profiles.remove(key);
                LauncherProfiles.write();
                try {
                    net.kdt.pojavlaunch.shortcuts.ProfileShortcutHelper
                            .removeShortcutsForProfile(requireContext(), key);
                } catch (Exception ignored) {}
                // If the deleted instance was current, fall back to the first one.
                String current = LauncherPreferences.DEFAULT_PREF.getString(
                        LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                String next = null;
                for (Map.Entry<String, MinecraftProfile> e
                        : LauncherProfiles.getOrderedEntries()) {
                    if (e.getKey() != null && !e.getKey().isEmpty()
                            && e.getValue() != null && e.getValue().name != null) {
                        next = e.getKey();
                        break;
                    }
                }
                if (key.equals(current)) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, next == null ? "" : next)
                            .apply();
                }
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER,
                        next != null ? next : ProfileEditorFragment.DELETED_PROFILE);
            } catch (Throwable ignored) {}

            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                net.kdt.pojavlaunch.utils.CsPopup.show(requireContext(), "Instance deleted");
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Instance deleted", "List updated");
                loadData();
            });
        });
    }

    private View menuRow(Context ctx, float dp, int iconRes, String label, Runnable action) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackgroundResource(R.drawable.bg_lh_sheet_row);
        int padH = (int) (12 * dp), padV = (int) (11 * dp);
        l.setPadding(padH + (int)(2*dp), padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (4 * dp);
        l.setLayoutParams(lp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFC9B8FF);
        int size = (int) (18 * dp);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
        ilp.rightMargin = (int) (12 * dp);
        icon.setLayoutParams(ilp);
        l.addView(icon);

        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextColor(0xFFEDEAF6);
        text.setTextSize(13.5f);
        l.addView(text);

        l.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            // Eased close → then run the real action once the menu is gone.
            closeMenuAnimated(action);
        });
        return l;
    }

    // ── Account ─────────────────────────────────────────────────────────────

    private void bindAccount() {
        Activity act = getActivity();
        if (act == null) return;
        View v = act.findViewById(R.id.account_spinner);
        if (!(v instanceof mcAccountSpinner)) return;
        MinecraftAccount account = ((mcAccountSpinner) v).getSelectedAccount();
        String label;
        String type;
        if (account == null || account.username == null || account.username.trim().isEmpty()) {
            label = "Add account";
            type = "NEW";
        } else {
            label = account.username;
            type = account.isMicrosoft ? "MICROSOFT"
                    : account.isElyByAccount() ? "ELY.BY" : "LOCAL";
        }
        mAccountName.setText(label);
        mAccountType.setText(type);
        mAccountChip.setContentDescription("Account: " + label + ". Switch or add account");
        loadAccountHead(account);
    }

    /** 3D Minecraft head avatar (existing SkinHead3DRenderer, cached + invalidated on skin change). */
    private void loadAccountHead(MinecraftAccount account) {
        if (mAccountHead == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            Bitmap head = net.kdt.pojavlaunch.ui.SkinHead3DRenderer.resolve(getResources(), account);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || mAccountHead == null) return;
                mAccountHead.setImageTintList(null);
                if (head != null) mAccountHead.setImageBitmap(head);
                else mAccountHead.setImageResource(R.drawable.ic_profile_player);
            });
        });
    }

    /** Opens the existing account dialog (add / switch / delete). */
    private void switchAccount() {
        Activity act = getActivity();
        if (act == null) return;
        View v = act.findViewById(R.id.account_spinner);
        if (v instanceof mcAccountSpinner) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ((mcAccountSpinner) v).performClick();
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private void launch(String key) {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private void navigateTo(Class<? extends Fragment> cls, String tag, @Nullable Bundle args) {
        Tools.swapFragment(requireActivity(), cls, tag, args);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Real allocated RAM from the launcher configuration, e.g. "4 GB RAM". */
    private static String formatRam(int mb) {
        if (mb <= 0) return "RAM —";
        if (mb >= 1024) {
            float gb = mb / 1024f;
            return (gb == Math.floor(gb) ? String.valueOf((int) gb)
                    : String.format(java.util.Locale.US, "%.1f", gb)) + " GB RAM";
        }
        return mb + " MB RAM";
    }

    private static String displayVersion(String raw) {
        if (raw == null || raw.isEmpty()) return "—";
        return raw;
    }

    private static String loaderName(String versionId) {
        if (versionId == null) return null;
        String v = versionId.toLowerCase(Locale.ROOT);
        if (v.contains("neoforge")) return "NeoForge";
        if (v.contains("forge")) return "Forge";
        if (v.contains("fabric")) return "Fabric";
        if (v.contains("quilt")) return "Quilt";
        if (v.contains("optifine")) return "OptiFine";
        return null;
    }

    private String resolveJavaVersion(MinecraftProfile p) {
        if (p == null) return "Java 21";
        if (p.javaDir != null && !p.javaDir.isEmpty()) {
            String runtimeName = Tools.getRuntimeName(p.javaDir);
            if (runtimeName != null && !runtimeName.isEmpty()) {
                try {
                    Runtime rt = MultiRTUtils.read(runtimeName);
                    if (rt != null && rt.javaVersion > 0) {
                        return "Java " + rt.javaVersion;
                    }
                } catch (Throwable ignored) {}
                if (runtimeName.contains("21")) return "Java 21";
                if (runtimeName.contains("17")) return "Java 17";
                if (runtimeName.contains("8")) return "Java 8";
                return runtimeName;
            }
        }
        try {
            String defRt = LauncherPreferences.PREF_DEFAULT_RUNTIME;
            if (defRt != null && !defRt.isEmpty()) {
                Runtime rt = MultiRTUtils.read(defRt);
                if (rt != null && rt.javaVersion > 0) {
                    return "Java " + rt.javaVersion;
                }
            }
        } catch (Throwable ignored) {}
        String ver = p.lastVersionId;
        if (ver != null) {
            String v = ver.toLowerCase(Locale.ROOT);
            if (v.contains("1.20.5") || v.contains("1.20.6") || v.contains("1.21")) return "Java 21";
            if (v.contains("1.17") || v.contains("1.18") || v.contains("1.19") || v.contains("1.20")) return "Java 17";
            if (v.contains("1.7") || v.contains("1.8") || v.contains("1.12") || v.contains("1.16")) return "Java 8";
        }
        return "Java 21";
    }

    private String resolveAllocatedRam() {
        int ram = LauncherPreferences.PREF_RAM_ALLOCATION;
        if (ram <= 0 && getContext() != null) {
            ram = LauncherPreferences.DEFAULT_PREF.getInt("allocation", 1024);
        }
        return (ram > 0 ? ram : 1024) + " MB RAM";
    }

    private static String javaName(MinecraftProfile p) {
        if (p != null && p.javaDir != null) {
            if (p.javaDir.contains("21")) return "Java 21";
            if (p.javaDir.contains("17")) return "Java 17";
        }
        return null;
    }

    private static int countMods(MinecraftProfile p) {
        try {
            File gameDir = p.resolveGameDir();
            if (gameDir != null) {
                File modsDir = new File(gameDir, "mods");
                if (modsDir.exists() && modsDir.isDirectory()) {
                    File[] files = modsDir.listFiles(f -> f.isFile() &&
                            (f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                                    || f.getName().toLowerCase(Locale.ROOT).endsWith(".jar.disabled")));
                    return files != null ? files.length : 0;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static long parseLastUsed(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try { return Instant.parse(iso).toEpochMilli(); }
        catch (Throwable t) { return -1; }
    }
}
