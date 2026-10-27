package me.aap.fermata.auto;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Erdal Auto home screen settings and shared state.
 */
public final class ErdalSettings {
	private static final String NAME = "erdal_home";
	private static final List<Runnable> dimListeners = new ArrayList<>();
	private static final List<Runnable> homeListeners = new ArrayList<>();
	private static int brightness = -1;
	private static boolean homeVisible;

	private ErdalSettings() {}

	private static SharedPreferences prefs(Context ctx) {
		return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
	}

	public static boolean isDark(Context ctx) {
		var p = prefs(ctx);
		if (p.getBoolean("autoNight", true)) {
			int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
			if (h >= 19 || h < 7) return true;
		}
		return p.getBoolean("dark", true);
	}

	public static void setDark(Context ctx, boolean dark) {
		prefs(ctx).edit().putBoolean("dark", dark).apply();
	}

	public static boolean isAutoNight(Context ctx) {
		return prefs(ctx).getBoolean("autoNight", true);
	}

	public static void setAutoNight(Context ctx, boolean v) {
		prefs(ctx).edit().putBoolean("autoNight", v).apply();
	}

	public static String getWallpaper(Context ctx) {
		return prefs(ctx).getString("wp", "mavi");
	}

	public static void setWallpaper(Context ctx, String wp) {
		prefs(ctx).edit().putString("wp", wp).apply();
	}

	public static String getWallpaperUri(Context ctx) {
		return prefs(ctx).getString("wpUri", null);
	}

	public static void setWallpaperUri(Context ctx, String uri) {
		prefs(ctx).edit().putString("wpUri", uri).putString("wp", "foto").apply();
	}

	public static Set<String> getHidden(Context ctx) {
		return new HashSet<>(prefs(ctx).getStringSet("hidden", new HashSet<>()));
	}

	public static void setHidden(Context ctx, Set<String> hidden) {
		prefs(ctx).edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
	}

	public static String getAddress(Context ctx, String key) {
		return prefs(ctx).getString("addr_" + key, "");
	}

	public static void setAddress(Context ctx, String key, String v) {
		prefs(ctx).edit().putString("addr_" + key, v).apply();
	}

	public static boolean isYoutubeApp(Context ctx) {
		return prefs(ctx).getBoolean("ytApp", false);
	}

	public static void setYoutubeApp(Context ctx, boolean v) {
		prefs(ctx).edit().putBoolean("ytApp", v).apply();
	}

	public static boolean isVideoWarning(Context ctx) {
		return prefs(ctx).getBoolean("videoWarn", true);
	}

	public static void setVideoWarning(Context ctx, boolean v) {
		prefs(ctx).edit().putBoolean("videoWarn", v).apply();
	}

	public static String getCache(Context ctx, String key) {
		return prefs(ctx).getString("cache_" + key, null);
	}

	public static void setCache(Context ctx, String key, String v) {
		prefs(ctx).edit().putString("cache_" + key, v).apply();
	}

	/**
	 * Car screen brightness, 20..100 percent. Applied as a dim layer over the mirrored image.
	 */
	public static int getBrightness(Context ctx) {
		if (brightness < 0) brightness = prefs(ctx).getInt("brightness", 100);
		return brightness;
	}

	public static void setBrightness(Context ctx, int v) {
		brightness = Math.max(20, Math.min(100, v));
		prefs(ctx).edit().putInt("brightness", brightness).apply();
		for (var l : new ArrayList<>(dimListeners)) l.run();
	}

	public static float getDimAlpha(Context ctx) {
		return (100 - getBrightness(ctx)) / 100f * 0.7f;
	}

	public static void addDimListener(Runnable r) {
		dimListeners.add(r);
	}

	public static void removeDimListener(Runnable r) {
		dimListeners.remove(r);
	}

	public static boolean isHomeVisible() {
		return homeVisible;
	}

	static void setHomeVisible(boolean v) {
		homeVisible = v;
		for (var l : new ArrayList<>(homeListeners)) l.run();
	}

	public static void addHomeListener(Runnable r) {
		homeListeners.add(r);
	}

	public static void removeHomeListener(Runnable r) {
		homeListeners.remove(r);
	}
}
