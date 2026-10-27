package me.aap.fermata.auto;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.ui.activity.MainActivity;

/**
 * Erdal Auto home screen, shown on the car display through Fermata Mirror.
 * All sizes follow a 960 x 960 design grid, scaled to the actual screen.
 */
@SuppressLint("ViewConstructor")
public class ErdalHomeView extends FrameLayout {
	public static final String EXTRA_OPEN = "erdal.open";
	private static final Locale TR = new Locale("tr", "TR");
	private static final String[][] APPS = {
			{"nav", "Navigasyon", "#1A73E8", "#FFFFFF"},
			{"wa", "WhatsApp", "#1E9E52", "#FFFFFF"},
			{"music", "Müzik", "#E8384F", "#FFFFFF"},
			{"radio", "Radyo", "#F28C28", "#1A1206"},
			{"tv", "TV", "#7B4DE0", "#FFFFFF"},
			{"yt", "YouTube", "#E02B2B", "#FFFFFF"},
			{"tiktok", "TikTok", "#121212", "#25F4EE"},
			{"film", "Netflix", "#1C1C1E", "#FF4B55"},
			{"play", "Google Play", "#01875F", "#FFFFFF"},
			{"mirror", "Telefon ekranı", "#00838F", "#FFFFFF"},
			{"web", "Tarayıcı", "#3D5AFE", "#FFFFFF"},
			{"m3u", "M3U Ekle", "#F5B400", "#241A00"},
			{"phone", "Telefon", "#2E7D32", "#FFFFFF"},
			{"set", "Ayarlar", "#5F6B7A", "#FFFFFF"},
	};
	private static final Set<String> VIDEO_APPS =
			new HashSet<>(java.util.Arrays.asList("yt", "tiktok", "film", "tv"));

	private final Activity act;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AudioManager audio;
	private final Typeface clockFont;
	private float s = 1f;
	private boolean dark;
	private int cText, cSub, cCard, cChip, cTrack, cBg;
	private boolean editing;
	private Set<String> hidden;
	private boolean moving;
	private float speedKmh;
	private ErdalData.Weather weather;
	private ErdalData.Market market;
	private final Set<String> btConnected = new HashSet<>();

	private TextView clock, date, speedText, batteryText, wxTemp, wxLabel, usdText, eurText,
			goldText, volText, brText, mediaState, btSummary;
	private ImageView wxIcon, playIcon, muteIcon;
	private View volFill, brFill, wxCard;
	private LocationListener locListener;
	private long lastWeather, lastMarket;

	private final Runnable ticker = new Runnable() {
		@Override
		public void run() {
			updateClock();
			updateBattery();
			updateMedia();
			updateVolume();
			long now = SystemClock.elapsedRealtime();
			if (now - lastWeather > 30 * 60_000L || lastWeather == 0) refreshWeather();
			if (now - lastMarket > 60 * 60_000L || lastMarket == 0) refreshMarket();
			handler.postDelayed(this, 10_000);
		}
	};

	public ErdalHomeView(Activity act) {
		super(act);
		this.act = act;
		audio = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
		Typeface tf;
		try {
			tf = Typeface.createFromAsset(act.getAssets(), "fonts/orbitron.ttf");
		} catch (Throwable ex) {
			tf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
		}
		clockFont = tf;
		hidden = ErdalSettings.getHidden(act);
		setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		handler.post(ticker);
		startLocation();
		loadBluetoothState();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		handler.removeCallbacksAndMessages(null);
		stopLocation();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
			s = Math.min(w, h) / 960f;
			post(this::build);
		}
	}

	public void refresh() {
		hidden = ErdalSettings.getHidden(act);
		if (getWidth() > 0) build();
	}

	public boolean onBack() {
		if (editing) {
			editing = false;
			build();
			return true;
		}
		return false;
	}

	public void onPermissionsChanged() {
		startLocation();
		loadBluetoothState();
		refreshWeather();
	}

	// ---------------------------------------------------------------- build

	private void build() {
		removeAllViews();
		dark = ErdalSettings.isDark(act);
		if (dark) {
			cBg = 0xFF0B1020;
			cText = 0xFFF3F5FA;
			cSub = 0xFFA9B3C6;
			cCard = 0xC7161C2C;
			cChip = 0x1AFFFFFF;
			cTrack = 0x29FFFFFF;
		} else {
			cBg = 0xFFEAF0FA;
			cText = 0xFF121722;
			cSub = 0xFF4E5869;
			cCard = 0xDBFFFFFF;
			cChip = 0x12121722;
			cTrack = 0x1F121722;
		}

		addView(createBackground(), new LayoutParams(MATCH_PARENT, MATCH_PARENT));

		var col = new LinearLayout(act);
		col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(px(108), px(20), px(108), px(24));
		addView(col, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

		col.addView(createStatusRow(), new LinearLayout.LayoutParams(MATCH_PARENT, px(44)));

		var home = new LinearLayout(act);
		home.setOrientation(LinearLayout.VERTICAL);
		home.setGravity(Gravity.CENTER_HORIZONTAL);
		col.addView(home, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

		clock = text("", 60, cText, true);
		clock.setTypeface(clockFont, Typeface.BOLD);
		clock.setLetterSpacing(0.06f);
		clock.setIncludeFontPadding(false);
		home.addView(clock, lp(WRAP_CONTENT, WRAP_CONTENT, 4, 0));
		date = text("", 20, cSub, false);
		home.addView(date, lp(WRAP_CONTENT, WRAP_CONTENT, 2, 0));
		updateClock();

		home.addView(createInfoRow(), lp(WRAP_CONTENT, px(76), 14, 0));
		var credit = text("Hava: Open-Meteo · Kur: ExchangeRate-API · Altın: xaus.com", 11, cSub, false);
		home.addView(credit, lp(WRAP_CONTENT, WRAP_CONTENT, 4, 0));

		if (editing) {
			var hint = text("Gizlemek istediğin simgeye dokun, bitince Kaydet'e bas.", 18, cSub, false);
			hint.setGravity(Gravity.CENTER);
			home.addView(hint, lp(MATCH_PARENT, px(56), 12, 0));
		} else {
			home.addView(createQuickRow(), lp(WRAP_CONTENT, px(56), 12, 0));
		}

		home.addView(createGrid(), lp(MATCH_PARENT, WRAP_CONTENT, 12, 0));
		home.addView(new View(act), new LinearLayout.LayoutParams(1, 0, 1f));
		home.addView(createControlRow(), lp(WRAP_CONTENT, px(64), 8, 0));

		col.addView(createBottomBar(), lp(MATCH_PARENT, px(88), 12, 0));

		addView(createVolumeBar(), sideParams(Gravity.START));
		addView(createBrightnessBar(), sideParams(Gravity.END));

		updateWeatherViews();
		updateMarketViews();
		updateBattery();
		updateMedia();
		updateVolume();
		updateBrightness();
		updateSpeed();
	}

	private View createBackground() {
		var uri = ErdalSettings.getWallpaperUri(act);
		if ("foto".equals(ErdalSettings.getWallpaper(act)) && uri != null) {
			var f = new FrameLayout(act);
			var img = new ImageView(act);
			img.setScaleType(ImageView.ScaleType.CENTER_CROP);
			try {
				img.setImageURI(Uri.parse(uri));
			} catch (Throwable ex) {
				return new WallpaperView(act, "mavi");
			}
			f.addView(img, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
			var scrim = new View(act);
			scrim.setBackgroundColor(dark ? 0x80000000 : 0x40FFFFFF);
			f.addView(scrim, new LayoutParams(MATCH_PARENT, MATCH_PARENT));
			return f;
		}
		return new WallpaperView(act, ErdalSettings.getWallpaper(act));
	}

	private View createStatusRow() {
		var row = new LinearLayout(act);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);

		var left = new LinearLayout(act);
		left.setGravity(Gravity.CENTER_VERTICAL);
		left.addView(icon("bt", cSub, 20), new LinearLayout.LayoutParams(px(20), px(20)));
		left.addView(text("  Araca bağlı", 16, cSub, false));
		row.addView(left, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

		var chip = new LinearLayout(act);
		chip.setGravity(Gravity.CENTER_VERTICAL);
		chip.setPadding(px(18), 0, px(18), 0);
		chip.setBackground(round(cChip, 999));
		chip.addView(icon("car", cText, 20), new LinearLayout.LayoutParams(px(20), px(20)));
		speedText = text("", 16, cText, true);
		chip.addView(speedText, lpH(WRAP_CONTENT, WRAP_CONTENT, 8));
		row.addView(chip, new LinearLayout.LayoutParams(WRAP_CONTENT, px(44)));

		var right = new LinearLayout(act);
		right.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
		batteryText = text("", 16, cSub, false);
		right.addView(batteryText);
		right.addView(icon("battery", cSub, 26), lpH(px(26), px(26), 8));
		row.addView(right, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		return row;
	}

	private View createInfoRow() {
		var row = new LinearLayout(act);
		row.setOrientation(LinearLayout.HORIZONTAL);

		var wx = new LinearLayout(act);
		wx.setGravity(Gravity.CENTER_VERTICAL);
		wx.setPadding(px(16), 0, px(16), 0);
		wxIcon = new ImageView(act);
		wx.addView(wxIcon, new LinearLayout.LayoutParams(px(54), px(54)));
		var wxt = new LinearLayout(act);
		wxt.setOrientation(LinearLayout.VERTICAL);
		wxTemp = text("--°", 26, Color.WHITE, true);
		wxLabel = text("Hava durumu", 14, 0xE6FFFFFF, false);
		wxt.addView(wxTemp);
		wxt.addView(wxLabel);
		wx.addView(wxt, lpH(WRAP_CONTENT, WRAP_CONTENT, 12));
		wx.setOnClickListener(v -> refreshWeather());
		wxCard = wx;
		row.addView(wx, new LinearLayout.LayoutParams(px(280), MATCH_PARENT));

		var fx = card(210);
		fx.addView(badge("₺", 0xFF1E8E3E), new LinearLayout.LayoutParams(px(36), px(36)));
		var fxt = new LinearLayout(act);
		fxt.setOrientation(LinearLayout.VERTICAL);
		usdText = text("Dolar ₺ --", 15, cText, false);
		eurText = text("Euro ₺ --", 15, cText, false);
		fxt.addView(usdText);
		fxt.addView(eurText);
		fx.addView(fxt, lpH(WRAP_CONTENT, WRAP_CONTENT, 12));
		fx.setOnClickListener(v -> refreshMarket());
		row.addView(fx, lpH(px(210), MATCH_PARENT, 12));

		var gd = card(210);
		var gb = new ImageView(act);
		gb.setBackground(round(0xFFC79100, 12));
		gb.setImageDrawable(ErdalGlyph.of("work", Color.WHITE));
		gb.setPadding(px(8), px(8), px(8), px(8));
		gd.addView(gb, new LinearLayout.LayoutParams(px(36), px(36)));
		var gdt = new LinearLayout(act);
		gdt.setOrientation(LinearLayout.VERTICAL);
		gdt.addView(text("Gram altın", 14, cSub, false));
		goldText = text("₺ --", 17, cText, true);
		gdt.addView(goldText);
		gd.addView(gdt, lpH(WRAP_CONTENT, WRAP_CONTENT, 12));
		gd.setOnClickListener(v -> refreshMarket());
		row.addView(gd, lpH(px(210), MATCH_PARENT, 12));
		return row;
	}

	private View createQuickRow() {
		var row = new LinearLayout(act);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.addView(pill("house", 0xFF1A73E8, "Eve git", v -> navigateTo("ev")));
		row.addView(pill("work", 0xFF7B4DE0, "İşe git", v -> navigateTo("is")), lpH(WRAP_CONTENT, MATCH_PARENT, 10));
		row.addView(pill("fuel", 0xFFF28C28, "Yakıt", v -> search("akaryakıt istasyonu")), lpH(WRAP_CONTENT, MATCH_PARENT, 10));
		row.addView(pill("parking", 0xFF00838F, "Otopark", v -> search("otopark")), lpH(WRAP_CONTENT, MATCH_PARENT, 10));
		var mic = pill("mic", Color.WHITE, "Sesli komut", v -> voiceCommand());
		mic.setBackground(ripple(round(0xFF1A73E8, 999)));
		((TextView) mic.getChildAt(1)).setTextColor(Color.WHITE);
		row.addView(mic, lpH(WRAP_CONTENT, MATCH_PARENT, 10));
		return row;
	}

	private View createGrid() {
		var grid = new LinearLayout(act);
		grid.setOrientation(LinearLayout.VERTICAL);
		List<String[]> visible = new ArrayList<>();
		for (var a : APPS) if (editing || !hidden.contains(a[0])) visible.add(a);
		LinearLayout row = null;
		for (int i = 0; i < visible.size(); i++) {
			if (i % 5 == 0) {
				row = new LinearLayout(act);
				row.setOrientation(LinearLayout.HORIZONTAL);
				grid.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, px(120)));
			}
			row.addView(appTile(visible.get(i)), new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
		}
		if (row != null) {
			for (int i = visible.size() % 5; (i != 0) && (i < 5); i++) {
				row.addView(new View(act), new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
			}
		}
		return grid;
	}

	private View appTile(String[] a) {
		var id = a[0];
		var tile = new LinearLayout(act);
		tile.setOrientation(LinearLayout.VERTICAL);
		tile.setGravity(Gravity.CENTER);
		var box = new FrameLayout(act);
		var bg = new ImageView(act);
		var shape = round(Color.parseColor(a[2]), 26);
		bg.setBackground(shape);
		bg.setImageDrawable(ErdalGlyph.of(id, Color.parseColor(a[3])));
		bg.setPadding(px(20), px(20), px(20), px(20));
		bg.setElevation(px(4));
		box.addView(bg, new LayoutParams(px(80), px(80), Gravity.CENTER));
		boolean isHidden = hidden.contains(id);
		if (editing) {
			var b = new ImageView(act);
			b.setBackground(round(isHidden ? 0xFF1E8E3E : 0xFFD93025, 999));
			b.setImageDrawable(ErdalGlyph.of(isHidden ? "plus" : "minus", Color.WHITE));
			b.setPadding(px(6), px(6), px(6), px(6));
			b.setElevation(px(6));
			box.addView(b, new LayoutParams(px(32), px(32), Gravity.TOP | Gravity.END));
			if (isHidden) tile.setAlpha(0.4f);
		}
		tile.addView(box, new LinearLayout.LayoutParams(px(92), px(88)));
		var label = text(a[1], 16, cText, false);
		label.setSingleLine(true);
		tile.addView(label, lp(WRAP_CONTENT, WRAP_CONTENT, 2, 0));
		tile.setBackground(ripple(null));
		tile.setContentDescription(a[1]);
		tile.setOnClickListener(v -> {
			if (editing) {
				if (!hidden.remove(id)) hidden.add(id);
				build();
			} else {
				open(id);
			}
		});
		return tile;
	}

	private View createControlRow() {
		var row = new LinearLayout(act);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.addView(control("theme", dark ? "Koyu" : "Açık", cCard, cText, v -> {
			ErdalSettings.setAutoNight(act, false);
			ErdalSettings.setDark(act, !dark);
			build();
		}));
		row.addView(control("image", "Arka plan", cCard, cText, v -> wallpaperDialog()),
				lpH(WRAP_CONTENT, MATCH_PARENT, 12));
		row.addView(editing ? control("check", "Kaydet", 0xFF1E8E3E, Color.WHITE, v -> {
			editing = false;
			ErdalSettings.setHidden(act, hidden);
			Toast.makeText(act, "Ana ekran kaydedildi", Toast.LENGTH_SHORT).show();
			build();
		}) : control("edit", "Düzenle", cCard, cText, v -> {
			editing = true;
			build();
		}), lpH(WRAP_CONTENT, MATCH_PARENT, 12));
		return row;
	}

	private View createBottomBar() {
		var bar = new LinearLayout(act);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);

		var media = card(0);
		media.setPadding(px(10), 0, px(10), 0);
		var mi = new ImageView(act);
		mi.setBackground(round(0xFFF28C28, 18));
		mi.setImageDrawable(ErdalGlyph.of("music", 0xFF1A1206));
		mi.setPadding(px(14), px(14), px(14), px(14));
		media.addView(mi, new LinearLayout.LayoutParams(px(56), px(56)));
		var mt = new LinearLayout(act);
		mt.setOrientation(LinearLayout.VERTICAL);
		mt.addView(text("Medya", 13, cSub, false));
		mediaState = text("", 18, cText, true);
		mediaState.setSingleLine(true);
		mt.addView(mediaState);
		media.addView(mt, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
		playIcon = roundIcon("playFill", 0xFF1A73E8, Color.WHITE, 56, 16);
		playIcon.setOnClickListener(v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
		media.addView(playIcon, lpH(px(56), px(56), 8));
		var next = roundIcon("nextFill", Color.TRANSPARENT, cText, 48, 12);
		next.setContentDescription("Sonraki");
		next.setOnClickListener(v -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
		media.addView(next, lpH(px(48), px(48), 4));
		bar.addView(media, new LinearLayout.LayoutParams(0, px(80), 1f));

		var home = roundIcon("home", 0xFF1A73E8, Color.WHITE, 88, 22);
		home.setContentDescription("Ana ekran");
		home.setElevation(px(8));
		home.setOnClickListener(v -> {
			if (!onBack()) build();
		});
		var hlp = new LinearLayout.LayoutParams(px(88), px(88));
		hlp.leftMargin = hlp.rightMargin = px(16);
		bar.addView(home, hlp);

		var bt = card(0);
		bt.setPadding(px(16), 0, px(16), 0);
		var bi = new ImageView(act);
		bi.setBackground(round(0xFF3D5AFE, 16));
		bi.setImageDrawable(ErdalGlyph.of("bt", Color.WHITE));
		bi.setPadding(px(13), px(13), px(13), px(13));
		bt.addView(bi, new LinearLayout.LayoutParams(px(52), px(52)));
		var btt = new LinearLayout(act);
		btt.setOrientation(LinearLayout.VERTICAL);
		btt.addView(text("Bluetooth", 18, cText, true));
		btSummary = text("", 14, cSub, false);
		btt.addView(btSummary);
		bt.addView(btt, lpH(0, WRAP_CONTENT, 12));
		((LinearLayout.LayoutParams) btt.getLayoutParams()).weight = 1;
		bt.setOnClickListener(v -> bluetoothDialog());
		bar.addView(bt, new LinearLayout.LayoutParams(0, px(80), 1f));
		updateBluetoothSummary();
		return bar;
	}

	private View createVolumeBar() {
		var bar = sideBar();
		var up = roundIcon("volUp", cChip, cText, 56, 15);
		up.setContentDescription("Sesi aç");
		up.setOnClickListener(v -> changeVolume(1));
		bar.addView(up, sideItem(56));
		var track = new FrameLayout(act);
		track.setBackground(round(cTrack, 999));
		volFill = new View(act);
		volFill.setBackground(round(0xFF1A73E8, 999));
		track.addView(volFill, new LayoutParams(MATCH_PARENT, 0, Gravity.BOTTOM));
		var tlp = new LinearLayout.LayoutParams(px(22), 0, 1f);
		tlp.topMargin = tlp.bottomMargin = px(8);
		bar.addView(track, tlp);
		volText = text("", 16, cText, true);
		bar.addView(volText);
		var down = roundIcon("volDown", cChip, cText, 56, 15);
		down.setContentDescription("Sesi kıs");
		down.setOnClickListener(v -> changeVolume(-1));
		bar.addView(down, sideItem(56));
		muteIcon = roundIcon("mute", 0xFFD93025, Color.WHITE, 56, 15);
		muteIcon.setContentDescription("Sesi kapat");
		muteIcon.setOnClickListener(v -> toggleMute());
		bar.addView(muteIcon, sideItem(56));
		bar.addView(text("Ses", 13, cSub, false), lp(WRAP_CONTENT, WRAP_CONTENT, 6, 0));
		return bar;
	}

	private View createBrightnessBar() {
		var bar = sideBar();
		var up = roundIcon("sun", cChip, cText, 56, 14);
		up.setContentDescription("Ekranı aydınlat");
		up.setOnClickListener(v -> {
			ErdalSettings.setBrightness(act, ErdalSettings.getBrightness(act) + 10);
			updateBrightness();
		});
		bar.addView(up, sideItem(56));
		var track = new FrameLayout(act);
		track.setBackground(round(cTrack, 999));
		brFill = new View(act);
		brFill.setBackground(round(0xFFF5B400, 999));
		track.addView(brFill, new LayoutParams(MATCH_PARENT, 0, Gravity.BOTTOM));
		var tlp = new LinearLayout.LayoutParams(px(22), 0, 1f);
		tlp.topMargin = tlp.bottomMargin = px(8);
		bar.addView(track, tlp);
		brText = text("", 16, cText, true);
		bar.addView(brText);
		var down = roundIcon("sunLow", cChip, cText, 56, 16);
		down.setContentDescription("Ekranı karart");
		down.setOnClickListener(v -> {
			ErdalSettings.setBrightness(act, ErdalSettings.getBrightness(act) - 10);
			updateBrightness();
		});
		bar.addView(down, sideItem(56));
		bar.addView(text("Parlaklık", 13, cSub, false), lp(WRAP_CONTENT, WRAP_CONTENT, 6, 0));
		return bar;
	}

	// ---------------------------------------------------------------- updates

	private void updateClock() {
		if (clock == null) return;
		var now = new Date();
		clock.setText(new SimpleDateFormat("HH:mm", TR).format(now));
		var d = new SimpleDateFormat("EEEE, d MMMM yyyy", TR).format(now);
		date.setText(d.substring(0, 1).toUpperCase(TR) + d.substring(1));
	}

	private void updateBattery() {
		if (batteryText == null) return;
		try {
			var bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
			int lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
			batteryText.setText(lvl > 0 ? "Telefon %" + lvl : "Telefon");
		} catch (Throwable ex) {
			batteryText.setText("Telefon");
		}
	}

	private void updateMedia() {
		if (mediaState == null) return;
		boolean playing = audio.isMusicActive();
		mediaState.setText(playing ? "Çalıyor" : "Duraklatıldı");
		playIcon.setImageDrawable(ErdalGlyph.of(playing ? "pauseFill" : "playFill", Color.WHITE));
		playIcon.setContentDescription(playing ? "Duraklat" : "Çal");
	}

	private void updateVolume() {
		if (volFill == null) return;
		int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
		int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
		boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
		int pct = muted ? 0 : Math.round(cur * 100f / max);
		volText.setText(muted ? "Kapalı" : "%" + pct);
		setFill(volFill, pct);
		muteIcon.setBackground(ripple(round(muted ? 0xFF1E8E3E : 0xFFD93025, 999)));
		muteIcon.setImageDrawable(ErdalGlyph.of(muted ? "volUp" : "mute", Color.WHITE));
		muteIcon.setContentDescription(muted ? "Sesi aç" : "Sesi kapat");
	}

	private void updateBrightness() {
		if (brFill == null) return;
		int b = ErdalSettings.getBrightness(act);
		brText.setText("%" + b);
		setFill(brFill, b);
	}

	private void setFill(View fill, int pct) {
		var parent = (View) fill.getParent();
		parent.post(() -> {
			var lp = fill.getLayoutParams();
			lp.height = Math.round(parent.getHeight() * pct / 100f);
			fill.setLayoutParams(lp);
		});
	}

	private void updateSpeed() {
		if (speedText == null) return;
		speedText.setText(moving ? String.format(TR, "Hareket hâlinde · %.0f km/s", speedKmh) : "Park hâlinde");
	}

	private void updateWeatherViews() {
		if (wxCard == null) return;
		String kind = (weather == null) ? "partly" : weather.kind();
		int bg = switch (kind) {
			case "sun" -> 0xFFF29D0B;
			case "rain", "storm" -> 0xFF41566E;
			case "snow" -> 0xFF6C8AAE;
			case "fog", "cloud" -> 0xFF5B6B80;
			default -> 0xFF3B82C4;
		};
		wxCard.setBackground(ripple(round(bg, 22)));
		wxIcon.setImageDrawable(new WeatherIcon(kind));
		if (weather != null) {
			wxTemp.setText(String.format(TR, "%.0f°", weather.temp));
			var place = (weather.place == null || weather.place.isEmpty()) ? "" : weather.place + " · ";
			wxLabel.setText(place + weather.label());
		} else {
			wxTemp.setText("--°");
			wxLabel.setText(hasLocationPermission() ? "Yükleniyor…" : "Konum izni gerekli");
		}
	}

	private void updateMarketViews() {
		if (usdText == null || market == null) return;
		if (market.usd > 0) {
			usdText.setText(String.format(TR, "Dolar ₺ %.2f", market.usd));
			eurText.setText(String.format(TR, "Euro ₺ %.2f", market.eur));
		}
		if (market.gramGold > 0) goldText.setText(String.format(TR, "₺ %,.0f", market.gramGold));
	}

	private void updateBluetoothSummary() {
		if (btSummary == null) return;
		var a = btAdapter();
		if (a == null) btSummary.setText("Yok");
		else if (!a.isEnabled()) btSummary.setText("Kapalı");
		else if (!btConnected.isEmpty()) btSummary.setText(btConnected.size() + " cihaz bağlı");
		else btSummary.setText("Açık");
	}

	// ---------------------------------------------------------------- data

	private void refreshWeather() {
		lastWeather = SystemClock.elapsedRealtime();
		var loc = lastLocation();
		if (loc == null) {
			updateWeatherViews();
			return;
		}
		ErdalData.loadWeather(act, loc.getLatitude(), loc.getLongitude(), null, w -> {
			weather = w;
			updateWeatherViews();
		});
	}

	private void refreshMarket() {
		lastMarket = SystemClock.elapsedRealtime();
		ErdalData.loadMarket(act, m -> {
			market = m;
			updateMarketViews();
		});
	}

	private boolean hasLocationPermission() {
		return act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
				PackageManager.PERMISSION_GRANTED ||
				act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
						PackageManager.PERMISSION_GRANTED;
	}

	@SuppressLint("MissingPermission")
	private Location lastLocation() {
		if (!hasLocationPermission()) return null;
		try {
			var lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
			Location best = null;
			for (var p : lm.getProviders(true)) {
				var l = lm.getLastKnownLocation(p);
				if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
			}
			return best;
		} catch (Throwable ex) {
			return null;
		}
	}

	@SuppressLint("MissingPermission")
	private void startLocation() {
		if (locListener != null || !hasLocationPermission()) return;
		try {
			var lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
			locListener = new LocationListener() {
				@Override
				public void onLocationChanged(@NonNull Location l) {
					speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
					boolean m = speedKmh > 9f;
					if (m != moving || m) {
						moving = m;
						updateSpeed();
					}
					if (weather == null) refreshWeather();
				}

				@Override
				public void onStatusChanged(String provider, int status, Bundle extras) {}

				@Override
				public void onProviderEnabled(@NonNull String provider) {}

				@Override
				public void onProviderDisabled(@NonNull String provider) {}
			};
			if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
				lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locListener,
						Looper.getMainLooper());
			} else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
				lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, locListener,
						Looper.getMainLooper());
			}
		} catch (Throwable ex) {
			locListener = null;
		}
	}

	private void stopLocation() {
		if (locListener == null) return;
		try {
			var lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
			lm.removeUpdates(locListener);
		} catch (Throwable ignore) {
		}
		locListener = null;
	}

	private BluetoothAdapter btAdapter() {
		try {
			var bm = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
			return (bm == null) ? null : bm.getAdapter();
		} catch (Throwable ex) {
			return null;
		}
	}

	private boolean hasBtPermission() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
				act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
						PackageManager.PERMISSION_GRANTED;
	}

	@SuppressLint("MissingPermission")
	private void loadBluetoothState() {
		var a = btAdapter();
		if (a == null || !hasBtPermission()) return;
		try {
			for (int profile : new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET}) {
				a.getProfileProxy(act, new BluetoothProfile.ServiceListener() {
					@Override
					public void onServiceConnected(int p, BluetoothProfile proxy) {
						try {
							for (BluetoothDevice d : proxy.getConnectedDevices()) btConnected.add(d.getAddress());
						} catch (Throwable ignore) {
						}
						a.closeProfileProxy(p, proxy);
						handler.post(ErdalHomeView.this::updateBluetoothSummary);
					}

					@Override
					public void onServiceDisconnected(int p) {}
				}, profile);
			}
		} catch (Throwable ignore) {
		}
	}

	// ---------------------------------------------------------------- actions

	private void open(String id) {
		if (moving && VIDEO_APPS.contains(id) && ErdalSettings.isVideoWarning(act)) {
			new AlertDialog.Builder(act)
					.setTitle("Araç hareket hâlinde")
					.setMessage("Video yolcular için. Sürücü yola baksın.")
					.setNegativeButton("Vazgeç", null)
					.setPositiveButton("Anladım, aç", (d, w) -> doOpen(id))
					.show();
			return;
		}
		doOpen(id);
	}

	private void doOpen(String id) {
		switch (id) {
			case "nav" -> {
				if (!launchFirst("com.google.android.apps.maps", "ru.yandex.yandexnavi", "com.waze",
						"ru.yandex.yandexmaps")) start(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")));
			}
			case "wa" -> launchOrStore("com.whatsapp", "com.whatsapp.w4b");
			case "music" -> openFermata("music");
			case "radio", "tv", "m3u" -> openFermata("tv");
			case "yt" -> {
				if (ErdalSettings.isYoutubeApp(act)) launchOrStore("com.google.android.youtube");
				else openFermata("youtube");
			}
			case "tiktok" -> launchOrStore("com.zhiliaoapp.musically", "com.ss.android.ugc.trill");
			case "film" -> launchOrStore("com.netflix.mediaclient");
			case "play" -> launchOrStore("com.android.vending");
			case "mirror" -> {
				var i = new Intent(Intent.ACTION_MAIN);
				i.addCategory(Intent.CATEGORY_HOME);
				start(i);
			}
			case "web" -> openFermata("web");
			case "phone" -> start(new Intent(Intent.ACTION_DIAL));
			case "set" -> settingsDialog();
			default -> {
			}
		}
	}

	private void openFermata(String section) {
		var i = new Intent(act, MainActivity.class);
		i.setAction(EXTRA_OPEN);
		i.putExtra(EXTRA_OPEN, section);
		start(i);
	}

	private boolean launchFirst(String... pkgs) {
		var pm = act.getPackageManager();
		for (var p : pkgs) {
			var i = pm.getLaunchIntentForPackage(p);
			if (i != null) {
				start(i);
				return true;
			}
		}
		return false;
	}

	private void launchOrStore(String... pkgs) {
		if (launchFirst(pkgs)) return;
		Toast.makeText(act, "Uygulama yüklü değil, Google Play açılıyor", Toast.LENGTH_LONG).show();
		start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkgs[0])));
	}

	private void start(Intent i) {
		try {
			i.addFlags(FLAG_ACTIVITY_NEW_TASK);
			i.putExtra(LauncherActivity.INTENT_EXTRA_MODE, FermataApplication.get().getMirroringMode());
			act.startActivity(i);
			MirrorDisplay.disableAccelRotation();
		} catch (ActivityNotFoundException ex) {
			Toast.makeText(act, "Açılamadı", Toast.LENGTH_SHORT).show();
		} catch (Throwable ex) {
			Toast.makeText(act, String.valueOf(ex.getLocalizedMessage()), Toast.LENGTH_LONG).show();
		}
	}

	private void navigateTo(String key) {
		var addr = ErdalSettings.getAddress(act, key);
		if (addr.isEmpty()) {
			addressDialog(key, true);
			return;
		}
		var maps = act.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
		if (maps != null) {
			var i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(addr)));
			i.setPackage("com.google.android.apps.maps");
			start(i);
		} else {
			start(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(addr))));
		}
	}

	private void search(String q) {
		start(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))));
	}

	private void voiceCommand() {
		try {
			var i = new Intent(Intent.ACTION_VOICE_COMMAND);
			i.addFlags(FLAG_ACTIVITY_NEW_TASK);
			act.startActivity(i);
		} catch (Throwable ex) {
			start(new Intent("android.intent.action.ASSIST"));
		}
	}

	private void mediaKey(int code) {
		long t = SystemClock.uptimeMillis();
		audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
		audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
		handler.postDelayed(this::updateMedia, 600);
	}

	private void changeVolume(int dir) {
		int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		int step = Math.max(1, Math.round(max / 10f));
		int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
		try {
			if (audio.isStreamMute(AudioManager.STREAM_MUSIC) && dir > 0) {
				audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
			}
			audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, cur + dir * step)), 0);
		} catch (Throwable ignore) {
		}
		updateVolume();
	}

	private void toggleMute() {
		try {
			audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
		} catch (Throwable ignore) {
		}
		handler.postDelayed(this::updateVolume, 150);
	}

	// ---------------------------------------------------------------- dialogs

	private void wallpaperDialog() {
		String[] keys = {"mavi", "gunbatimi", "orman", "sade", "foto"};
		String[] names = {"Mavi", "Gün batımı", "Orman", "Sade", "Galeriden fotoğraf seç…"};
		new AlertDialog.Builder(act).setTitle("Arka plan").setItems(names, (d, w) -> {
			if (w == 4) {
				if (act instanceof LauncherActivity la) la.pickWallpaper();
			} else {
				ErdalSettings.setWallpaper(act, keys[w]);
				build();
			}
		}).setNegativeButton("Kapat", null).show();
	}

	@SuppressLint("MissingPermission")
	private void bluetoothDialog() {
		var a = btAdapter();
		List<String> items = new ArrayList<>();
		if (a != null && hasBtPermission()) {
			try {
				for (BluetoothDevice d : a.getBondedDevices()) {
					var name = d.getName() == null ? d.getAddress() : d.getName();
					items.add(name + (btConnected.contains(d.getAddress()) ? "  ·  bağlı" : "  ·  eşleşmiş"));
				}
			} catch (Throwable ignore) {
			}
		}
		if (items.isEmpty()) items.add(hasBtPermission() ? "Eşleşmiş cihaz yok" : "Bluetooth izni gerekli");
		new AlertDialog.Builder(act).setTitle("Bluetooth cihazları")
				.setItems(items.toArray(new String[0]), (d, w) -> start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
				.setPositiveButton("Yeni cihaz ekle / ses çıkışı", (d, w) -> start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
				.setNegativeButton("Kapat", null).show();
	}

	private void settingsDialog() {
		var autoNight = ErdalSettings.isAutoNight(act);
		var ytApp = ErdalSettings.isYoutubeApp(act);
		var warn = ErdalSettings.isVideoWarning(act);
		String[] items = {
				"Ev adresi: " + emptyDash(ErdalSettings.getAddress(act, "ev")),
				"İş adresi: " + emptyDash(ErdalSettings.getAddress(act, "is")),
				"Gece otomatik koyu tema: " + (autoNight ? "Açık" : "Kapalı"),
				"YouTube: " + (ytApp ? "YouTube uygulaması" : "Reklamsız (Fermata)"),
				"Hareket hâlinde video uyarısı: " + (warn ? "Açık" : "Kapalı"),
				"Gizlenen simgeleri geri getir",
				"Fermata ayarları",
		};
		new AlertDialog.Builder(act).setTitle("Erdal Auto ayarları").setItems(items, (d, w) -> {
			switch (w) {
				case 0 -> addressDialog("ev", false);
				case 1 -> addressDialog("is", false);
				case 2 -> {
					ErdalSettings.setAutoNight(act, !autoNight);
					build();
					settingsDialog();
				}
				case 3 -> {
					ErdalSettings.setYoutubeApp(act, !ytApp);
					settingsDialog();
				}
				case 4 -> {
					ErdalSettings.setVideoWarning(act, !warn);
					settingsDialog();
				}
				case 5 -> {
					hidden.clear();
					ErdalSettings.setHidden(act, hidden);
					build();
				}
				case 6 -> openFermata("settings");
				default -> {
				}
			}
		}).setNegativeButton("Kapat", null).show();
	}

	private void addressDialog(String key, boolean thenNavigate) {
		var input = new EditText(act);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
		input.setText(ErdalSettings.getAddress(act, key));
		input.setHint("Adres veya yer adı");
		var box = new FrameLayout(act);
		box.setPadding(px(24), px(8), px(24), 0);
		box.addView(input);
		new AlertDialog.Builder(act).setTitle("ev".equals(key) ? "Ev adresi" : "İş adresi").setView(box)
				.setNegativeButton("Vazgeç", null)
				.setPositiveButton("Kaydet", (d, w) -> {
					ErdalSettings.setAddress(act, key, input.getText().toString().trim());
					if (thenNavigate && !ErdalSettings.getAddress(act, key).isEmpty()) navigateTo(key);
				}).show();
	}

	private static String emptyDash(String s) {
		return s.isEmpty() ? "—" : s;
	}

	// ---------------------------------------------------------------- view helpers

	private int px(float v) {
		return Math.round(v * s);
	}

	private TextView text(String t, float size, int color, boolean bold) {
		var tv = new TextView(act);
		tv.setText(t);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
		tv.setTextColor(color);
		if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
		return tv;
	}

	private GradientDrawable round(int color, float radius) {
		var g = new GradientDrawable();
		g.setColor(color);
		g.setCornerRadius(radius >= 999 ? 9999 : radius * s);
		return g;
	}

	private Drawable ripple(Drawable content) {
		var mask = round(Color.WHITE, 24);
		return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), content,
				content == null ? mask : null);
	}

	private ImageView icon(String id, int color, float size) {
		var iv = new ImageView(act);
		iv.setImageDrawable(ErdalGlyph.of(id, color));
		return iv;
	}

	private ImageView roundIcon(String id, int bg, int fg, float size, float pad) {
		var iv = new ImageView(act);
		iv.setBackground(ripple(round(bg, 999)));
		iv.setImageDrawable(ErdalGlyph.of(id, fg));
		iv.setPadding(px(pad), px(pad), px(pad), px(pad));
		return iv;
	}

	private LinearLayout card(float width) {
		var c = new LinearLayout(act);
		c.setOrientation(LinearLayout.HORIZONTAL);
		c.setGravity(Gravity.CENTER_VERTICAL);
		c.setPadding(px(16), 0, px(16), 0);
		c.setBackground(ripple(round(cCard, 22)));
		return c;
	}

	private TextView badge(String t, int color) {
		var tv = text(t, 18, Color.WHITE, true);
		tv.setGravity(Gravity.CENTER);
		tv.setBackground(round(color, 12));
		return tv;
	}

	private LinearLayout pill(String iconId, int iconColor, String label, OnClickListener l) {
		var p = new LinearLayout(act);
		p.setOrientation(LinearLayout.HORIZONTAL);
		p.setGravity(Gravity.CENTER_VERTICAL);
		p.setPadding(px(16), 0, px(18), 0);
		p.setBackground(ripple(round(cCard, 999)));
		p.addView(icon(iconId, iconColor, 22), new LinearLayout.LayoutParams(px(22), px(22)));
		p.addView(text(label, 17, cText, true), lpH(WRAP_CONTENT, WRAP_CONTENT, 8));
		p.setOnClickListener(l);
		return p;
	}

	private LinearLayout control(String iconId, String label, int bg, int fg, OnClickListener l) {
		var c = new LinearLayout(act);
		c.setOrientation(LinearLayout.VERTICAL);
		c.setGravity(Gravity.CENTER);
		c.setPadding(px(18), px(6), px(18), px(6));
		c.setBackground(ripple(round(bg, 24)));
		c.addView(icon(iconId, fg, 22), new LinearLayout.LayoutParams(px(22), px(22)));
		c.addView(text(label, 15, fg, true), lp(WRAP_CONTENT, WRAP_CONTENT, 2, 0));
		c.setOnClickListener(l);
		return c;
	}

	private LinearLayout sideBar() {
		var bar = new LinearLayout(act);
		bar.setOrientation(LinearLayout.VERTICAL);
		bar.setGravity(Gravity.CENTER_HORIZONTAL);
		bar.setPadding(0, px(10), 0, px(10));
		bar.setBackground(round(cCard, 38));
		bar.setElevation(px(6));
		return bar;
	}

	private LinearLayout.LayoutParams sideItem(float size) {
		var lp = new LinearLayout.LayoutParams(px(size), px(size));
		lp.topMargin = px(6);
		return lp;
	}

	private LayoutParams sideParams(int gravity) {
		var lp = new LayoutParams(px(76), px(600), gravity | Gravity.TOP);
		lp.topMargin = px(150);
		if (gravity == Gravity.START) lp.leftMargin = px(18);
		else lp.rightMargin = px(18);
		return lp;
	}

	private LinearLayout.LayoutParams lp(int w, int h, float top, float bottom) {
		var lp = new LinearLayout.LayoutParams(w, h);
		lp.topMargin = px(top);
		lp.bottomMargin = px(bottom);
		return lp;
	}

	private LinearLayout.LayoutParams lpH(int w, int h, float left) {
		var lp = new LinearLayout.LayoutParams(w, h);
		lp.leftMargin = px(left);
		return lp;
	}

	// ---------------------------------------------------------------- drawing

	private final class WallpaperView extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final int[] colors;

		WallpaperView(Context ctx, String key) {
			super(ctx);
			colors = switch (key) {
				case "gunbatimi" -> new int[]{0x99FF7043, 0x73AB47BC, 0x61FFCA28};
				case "orman" -> new int[]{0x992E7D32, 0x73009688, 0x4DCDDC39};
				case "sade" -> new int[]{0x40788296, 0x2E788296, 0x1F788296};
				default -> dark ? new int[]{0x8C4285F4, 0x61EA4335, 0x5234A853} :
						new int[]{0x664285F4, 0x66FBBC05, 0x38EA4335};
			};
		}

		@Override
		protected void onDraw(@NonNull Canvas c) {
			c.drawColor(cBg);
			int w = getWidth(), h = getHeight();
			blob(c, w * 0.12f, h * 0.10f, Math.max(w, h) * 0.55f, colors[0]);
			blob(c, w * 0.92f, h * 0.78f, Math.max(w, h) * 0.50f, colors[1]);
			blob(c, w * 0.50f, h * 0.58f, Math.max(w, h) * 0.38f, colors[2]);
		}

		private void blob(Canvas c, float x, float y, float r, int color) {
			paint.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
			c.drawCircle(x, y, r, paint);
		}
	}

	private static final class WeatherIcon extends Drawable {
		private final String kind;
		private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

		WeatherIcon(String kind) {
			this.kind = kind;
		}

		@Override
		public void draw(@NonNull Canvas c) {
			var b = getBounds();
			float k = Math.min(b.width(), b.height()) / 48f;
			int save = c.save();
			c.translate(b.left, b.top);
			c.scale(k, k);
			switch (kind) {
				case "sun" -> sun(c, 24, 24, 9, true);
				case "partly" -> {
					sun(c, 18, 17, 7, true);
					cloud(c, 0xFFFFFFFF, 4, 6);
				}
				case "rain", "storm" -> {
					cloud(c, 0xFFE3ECF7, 0, 0);
					line(c, 0xFF4FC3F7, 16, 33, 14, 39);
					line(c, 0xFF4FC3F7, 24, 33, 22, 39);
					line(c, 0xFF4FC3F7, 32, 33, 30, 39);
					if (kind.equals("storm")) {
						p.setStyle(Paint.Style.FILL);
						p.setColor(0xFFFFD54F);
						var bolt = new Path();
						bolt.moveTo(25, 28);
						bolt.lineTo(20, 37);
						bolt.lineTo(24, 37);
						bolt.lineTo(22, 45);
						bolt.lineTo(29, 34);
						bolt.lineTo(25, 34);
						bolt.close();
						c.drawPath(bolt, p);
					}
				}
				case "snow" -> {
					cloud(c, 0xFFFFFFFF, 0, 0);
					p.setStyle(Paint.Style.FILL);
					p.setColor(0xFFFFFFFF);
					c.drawCircle(16, 36, 2, p);
					c.drawCircle(24, 39, 2, p);
					c.drawCircle(32, 36, 2, p);
				}
				case "fog" -> {
					cloud(c, 0xFFE3ECF7, 0, -4);
					line(c, 0xFFFFFFFF, 10, 34, 38, 34);
					line(c, 0xFFFFFFFF, 14, 40, 34, 40);
				}
				default -> cloud(c, 0xFFFFFFFF, 0, 2);
			}
			c.restoreToCount(save);
		}

		private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
			p.setStyle(Paint.Style.FILL);
			p.setColor(0xFFFFD54F);
			c.drawCircle(cx, cy, r, p);
			if (!rays) return;
			p.setStyle(Paint.Style.STROKE);
			p.setStrokeWidth(2.6f);
			p.setStrokeCap(Paint.Cap.ROUND);
			for (int i = 0; i < 8; i++) {
				double a = Math.PI / 4 * i;
				float x1 = (float) (cx + Math.cos(a) * (r + 3)), y1 = (float) (cy + Math.sin(a) * (r + 3));
				float x2 = (float) (cx + Math.cos(a) * (r + 7)), y2 = (float) (cy + Math.sin(a) * (r + 7));
				c.drawLine(x1, y1, x2, y2, p);
			}
		}

		private void cloud(Canvas c, int color, float dx, float dy) {
			p.setStyle(Paint.Style.FILL);
			p.setColor(color);
			c.drawCircle(19 + dx, 24 + dy, 7, p);
			c.drawCircle(27 + dx, 19 + dy, 9, p);
			c.drawCircle(34 + dx, 24 + dy, 6.5f, p);
			c.drawRoundRect(12 + dx, 22 + dy, 40 + dx, 31 + dy, 4.5f, 4.5f, p);
		}

		private void line(Canvas c, int color, float x1, float y1, float x2, float y2) {
			p.setStyle(Paint.Style.STROKE);
			p.setStrokeWidth(3);
			p.setStrokeCap(Paint.Cap.ROUND);
			p.setColor(color);
			c.drawLine(x1, y1, x2, y2, p);
		}

		@Override
		public void setAlpha(int alpha) {}

		@Override
		public void setColorFilter(android.graphics.ColorFilter cf) {}

		@Override
		public int getOpacity() {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
	}
}
