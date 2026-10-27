package me.aap.fermata.auto;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.car.app.SurfaceContainer;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.utils.log.Log;

/**
 * Erdal Auto on the car screen, without screen sharing:
 * the home screen is drawn directly by the car activity. Fermata sections (music, TV, radio,
 * YouTube, browser) open in the same car activity. Only other phone apps (WhatsApp, Netflix…)
 * need mirroring, which is shown in an embedded mirror layer.
 */
final class ErdalCarShell implements ErdalHomeView.Host {
	private final MainCarActivity activity;
	private final FrameLayout root;
	private final View fermataView;
	private final ErdalHomeView home;
	private final LinearLayout nav;
	private final View dim;
	private final Runnable dimListener;
	private FrameLayout mirrorLayer;
	private MirrorDisplay md;
	private SurfaceContainer sc;
	private boolean mirrorMode;

	private ErdalCarShell(MainCarActivity activity, FrameLayout root, View fermataView) {
		this.activity = activity;
		this.root = root;
		this.fermataView = fermataView;
		Context ctx = activity;

		home = new ErdalHomeView(ctx, this);
		root.addView(home, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

		var dm = ctx.getResources().getDisplayMetrics();
		int btn = Math.max(Math.min(dm.widthPixels, dm.heightPixels) / 11, 64);
		nav = new LinearLayout(ctx);
		nav.setOrientation(LinearLayout.HORIZONTAL);
		nav.addView(button(ctx, "back", 0xE6222A38, btn, v -> back()),
				new LinearLayout.LayoutParams(btn, btn));
		var hlp = new LinearLayout.LayoutParams(btn, btn);
		hlp.leftMargin = btn / 3;
		nav.addView(button(ctx, "home", 0xFF1A73E8, btn, v -> showHome()), hlp);
		var nlp = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
		nlp.bottomMargin = btn / 4;
		root.addView(nav, nlp);

		dim = new View(ctx);
		dim.setBackgroundColor(Color.BLACK);
		dim.setAlpha(ErdalSettings.getDimAlpha(ctx));
		root.addView(dim, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		dimListener = () -> dim.post(() -> dim.setAlpha(ErdalSettings.getDimAlpha(ctx)));
		ErdalSettings.addDimListener(dimListener);

		showHome();
	}

	/**
	 * Wraps the Fermata car UI so that the home screen and the mirror layer can be shown on top.
	 */
	static ErdalCarShell attach(MainCarActivity a) {
		View main = a.findViewById(R.id.main_activity);
		if (main == null) return null;
		if (main.getParent() instanceof FrameLayout f && f.getTag() == ErdalCarShell.class) {
			return null; // Already attached
		}
		var parent = (ViewGroup) main.getParent();
		if (parent == null) return null;
		int idx = parent.indexOfChild(main);
		var lp = main.getLayoutParams();
		parent.removeView(main);
		var root = new FrameLayout(a);
		root.setTag(ErdalCarShell.class);
		root.addView(main, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		parent.addView(root, idx, lp);
		return new ErdalCarShell(a, root, main);
	}

	void release() {
		ErdalSettings.removeDimListener(dimListener);
		releaseMirror();
		if (md != null) {
			md.release();
			md = null;
		}
	}

	boolean onBackKey() {
		if (home.getVisibility() == View.VISIBLE) return home.onBack();
		if (mirrorMode) {
			MirrorActivity.onBackButtonClick();
			return true;
		}
		return false;
	}

	void showHome() {
		releaseMirror();
		mirrorMode = false;
		if (mirrorLayer != null) mirrorLayer.setVisibility(View.GONE);
		home.setVisibility(View.VISIBLE);
		home.bringToFront();
		nav.setVisibility(View.GONE);
		dim.bringToFront();
		home.refresh();
	}

	private void back() {
		if (mirrorMode) {
			MirrorActivity.onBackButtonClick();
		} else {
			activity.getActivityDelegate().onSuccess(d -> {
				if (!d.isRootPage()) d.onBackPressed();
				else showHome();
			});
		}
	}

	// ---------------------------------------------------------------- Host

	@Override
	public boolean isCar() {
		return true;
	}

	@Override
	public void openSection(String section) {
		int fid;
		if ("tv".equals(section)) fid = R.id.tv_fragment;
		else if ("youtube".equals(section)) fid = R.id.youtube_fragment;
		else if ("web".equals(section)) fid = R.id.web_browser_fragment;
		else if ("settings".equals(section)) fid = R.id.settings_fragment;
		else fid = R.id.folders_fragment;
		home.setVisibility(View.GONE);
		if (mirrorLayer != null) mirrorLayer.setVisibility(View.GONE);
		releaseMirror();
		mirrorMode = false;
		nav.setVisibility(View.VISIBLE);
		nav.bringToFront();
		dim.bringToFront();
		activity.getActivityDelegate().onSuccess(d -> {
			try {
				if (d.showFragment(fid) == null) d.showFragment(R.id.folders_fragment);
			} catch (Throwable err) {
				Log.e(err, "Failed to open section ", section);
				d.showFragment(R.id.folders_fragment);
			}
		});
	}

	@Override
	public void openExternal(Intent intent) {
		try {
			ErdalHomeView.startOnPhone(intent);
		} catch (Throwable err) {
			Log.e(err, "Failed to start ", intent);
		}
		showMirror();
	}

	@Override
	public void pickWallpaper() {
		// Photos can only be picked on the phone.
		try {
			var i = new Intent(FermataApplication.get(), LauncherActivity.class);
			i.putExtra(LauncherActivity.EXTRA_PICK_WALLPAPER, true);
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			FermataApplication.get().startActivity(i);
		} catch (Throwable err) {
			Log.e(err, "Failed to open wallpaper picker");
		}
	}

	// ---------------------------------------------------------------- mirror layer

	@SuppressLint("ClickableViewAccessibility")
	private void showMirror() {
		Context ctx = activity;
		if (md == null) md = MirrorDisplay.get();
		if (mirrorLayer == null) {
			mirrorLayer = new FrameLayout(ctx) {
				@Override
				public boolean onTouchEvent(MotionEvent e) {
					if ((md != null) && (sc != null)) md.setSurface(sc);
					return (md != null) && md.motionEvent(e);
				}
			};
			mirrorLayer.setBackgroundColor(Color.BLACK);
			var sv = new SurfaceView(ctx);
			sv.getHolder().addCallback(new SurfaceHolder.Callback() {
				@Override
				public void surfaceCreated(@NonNull SurfaceHolder h) {
					var r = h.getSurfaceFrame();
					setSurface(new SurfaceContainer(h.getSurface(), r.width(), r.height(),
							ctx.getResources().getDisplayMetrics().densityDpi));
				}

				@Override
				public void surfaceChanged(@NonNull SurfaceHolder h, int format, int w, int hh) {
					setSurface(new SurfaceContainer(h.getSurface(), w, hh,
							ctx.getResources().getDisplayMetrics().densityDpi));
				}

				@Override
				public void surfaceDestroyed(@NonNull SurfaceHolder h) {
					releaseMirror();
					sc = null;
				}
			});
			mirrorLayer.addView(sv, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
			root.addView(mirrorLayer, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
		}
		mirrorMode = true;
		home.setVisibility(View.GONE);
		mirrorLayer.setVisibility(View.VISIBLE);
		mirrorLayer.bringToFront();
		nav.setVisibility(View.VISIBLE);
		nav.bringToFront();
		dim.bringToFront();
	}

	private void setSurface(SurfaceContainer c) {
		sc = c;
		if (mirrorMode && (md != null)) md.setSurface(c);
	}

	private void releaseMirror() {
		if ((md != null) && (sc != null)) {
			try {
				md.releaseSurface(sc);
			} catch (Throwable err) {
				Log.d(err);
			}
		}
	}

	private static ImageView button(Context ctx, String icon, int bg, int size,
																	View.OnClickListener l) {
		var b = new ImageView(ctx);
		var shape = new GradientDrawable();
		shape.setShape(GradientDrawable.OVAL);
		shape.setColor(bg);
		b.setBackground(shape);
		b.setImageDrawable(ErdalGlyph.of(icon, Color.WHITE));
		int pad = size / 4;
		b.setPadding(pad, pad, pad, pad);
		b.setElevation(size / 10f);
		b.setOnClickListener(l);
		return b;
	}
}
