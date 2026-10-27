package me.aap.fermata.auto;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple line icons drawn on a 24x24 grid (Erdal Auto).
 */
public class ErdalGlyph extends Drawable {
	private static final Pattern TOKEN = Pattern.compile("[MmLlHhVvCcQqZz]|-?\\d*\\.?\\d+(?:e-?\\d+)?");
	private final Path stroke = new Path();
	private final Path fill = new Path();
	private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path tmp = new Path();

	public ErdalGlyph(int color) {
		this(color, 1.9f);
	}

	public ErdalGlyph(int color, float strokeWidth) {
		strokePaint.setStyle(Paint.Style.STROKE);
		strokePaint.setStrokeWidth(strokeWidth);
		strokePaint.setStrokeCap(Paint.Cap.ROUND);
		strokePaint.setStrokeJoin(Paint.Join.ROUND);
		strokePaint.setColor(color);
		fillPaint.setStyle(Paint.Style.FILL);
		fillPaint.setColor(color);
	}

	public ErdalGlyph p(String svg) {
		parse(svg, stroke);
		return this;
	}

	public ErdalGlyph f(String svg) {
		parse(svg, fill);
		return this;
	}

	public ErdalGlyph circle(float cx, float cy, float r) {
		stroke.addCircle(cx, cy, r, Path.Direction.CW);
		return this;
	}

	public ErdalGlyph fcircle(float cx, float cy, float r) {
		fill.addCircle(cx, cy, r, Path.Direction.CW);
		return this;
	}

	public ErdalGlyph rect(float x, float y, float w, float h, float r) {
		stroke.addRoundRect(new RectF(x, y, x + w, y + h), r, r, Path.Direction.CW);
		return this;
	}

	public ErdalGlyph frect(float x, float y, float w, float h, float r) {
		fill.addRoundRect(new RectF(x, y, x + w, y + h), r, r, Path.Direction.CW);
		return this;
	}

	public void setColor(int color) {
		strokePaint.setColor(color);
		fillPaint.setColor(color);
		invalidateSelf();
	}

	@Override
	public void draw(@NonNull Canvas c) {
		Rect b = getBounds();
		float s = Math.min(b.width(), b.height()) / 24f;
		int save = c.save();
		c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
		c.scale(s, s);
		c.drawPath(fill, fillPaint);
		c.drawPath(stroke, strokePaint);
		c.restoreToCount(save);
	}

	@Override
	public void setAlpha(int alpha) {
		strokePaint.setAlpha(alpha);
		fillPaint.setAlpha(alpha);
	}

	@Override
	public void setColorFilter(ColorFilter cf) {
		strokePaint.setColorFilter(cf);
		fillPaint.setColorFilter(cf);
	}

	@Override
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}

	private void parse(String svg, Path out) {
		List<String> t = new ArrayList<>();
		Matcher m = TOKEN.matcher(svg);
		while (m.find()) t.add(m.group());
		tmp.reset();
		float x = 0, y = 0, sx = 0, sy = 0;
		char cmd = 'M';
		int i = 0;
		while (i < t.size()) {
			String tok = t.get(i);
			if (Character.isLetter(tok.charAt(0))) {
				cmd = tok.charAt(0);
				i++;
				if (cmd == 'Z' || cmd == 'z') {
					tmp.close();
					x = sx;
					y = sy;
					continue;
				}
			}
			switch (cmd) {
				case 'M', 'm' -> {
					float nx = num(t, i++), ny = num(t, i++);
					if (cmd == 'm') {
						nx += x;
						ny += y;
					}
					tmp.moveTo(nx, ny);
					x = sx = nx;
					y = sy = ny;
					cmd = (cmd == 'm') ? 'l' : 'L';
				}
				case 'L', 'l' -> {
					float nx = num(t, i++), ny = num(t, i++);
					if (cmd == 'l') {
						nx += x;
						ny += y;
					}
					tmp.lineTo(nx, ny);
					x = nx;
					y = ny;
				}
				case 'H', 'h' -> {
					float nx = num(t, i++);
					if (cmd == 'h') nx += x;
					tmp.lineTo(nx, y);
					x = nx;
				}
				case 'V', 'v' -> {
					float ny = num(t, i++);
					if (cmd == 'v') ny += y;
					tmp.lineTo(x, ny);
					y = ny;
				}
				case 'C', 'c' -> {
					float x1 = num(t, i++), y1 = num(t, i++), x2 = num(t, i++), y2 = num(t, i++);
					float nx = num(t, i++), ny = num(t, i++);
					if (cmd == 'c') {
						x1 += x;
						y1 += y;
						x2 += x;
						y2 += y;
						nx += x;
						ny += y;
					}
					tmp.cubicTo(x1, y1, x2, y2, nx, ny);
					x = nx;
					y = ny;
				}
				case 'Q', 'q' -> {
					float x1 = num(t, i++), y1 = num(t, i++);
					float nx = num(t, i++), ny = num(t, i++);
					if (cmd == 'q') {
						x1 += x;
						y1 += y;
						nx += x;
						ny += y;
					}
					tmp.quadTo(x1, y1, nx, ny);
					x = nx;
					y = ny;
				}
				default -> i++;
			}
		}
		out.addPath(tmp);
	}

	private static float num(List<String> t, int i) {
		return (i < t.size()) ? Float.parseFloat(t.get(i)) : 0f;
	}

	private static final String SPEAKER = "M11 5 6 9H3v6h3l5 4z";

	public static ErdalGlyph of(String id, int color) {
		ErdalGlyph g = new ErdalGlyph(color);
		switch (id) {
			case "nav" -> g.p("M3 11 21 3l-8 18-2-8z");
			case "wa" -> g.circle(12.5f, 11.5f, 8.5f).p("M6.5 17.8 3.5 21l4.4-1.4")
					.p("M9 8.5c0 3.5 3 6.5 6.5 6.5l1-1.5-2-1-1 1c-1-.5-2-1.5-2.5-2.5l1-1-1-2z");
			case "music" -> g.p("M9 18V5l11-2v13").circle(6, 18, 3).circle(17, 16, 3);
			case "radio" -> g.rect(3, 8, 18, 12, 2.5f).p("M7 8l10-4.5").circle(15.5f, 14, 2.5f)
					.p("M6.5 12.5h4M6.5 15.5h4");
			case "tv" -> g.rect(3, 4.5f, 18, 12.5f, 2.5f).p("M8 21h8M12 17v4");
			case "yt" -> g.rect(2.5f, 5, 19, 14, 4.5f).f("M10 9.3v5.4l4.7-2.7z");
			case "tiktok" -> g.p("M14 3v11.5").circle(10.5f, 14.5f, 3.5f).p("M14 3c.4 2.6 2.4 4.4 5 4.6");
			case "film" -> g.rect(3, 4, 18, 16, 2.5f)
					.p("M7.5 4v16M16.5 4v16M3 9h4.5M3 15h4.5M16.5 9H21M16.5 15H21");
			case "play" -> g.p("M5 8h14l-1.2 11.2c-.1 1-1 1.8-2 1.8H8.2c-1 0-1.9-.8-2-1.8z")
					.p("M9 8V6c0-1.7 1.3-3 3-3c1.7 0 3 1.3 3 3v2").f("M10.5 11.8v5.2l4.3-2.6z");
			case "mirror" -> g.rect(2.5f, 7, 7, 13, 1.8f).rect(12, 3.5f, 9.5f, 8, 1.8f)
					.p("M9.5 13.5H13c1.9 0 3.5-1.6 3.5-3.5v-.5").p("M14.8 11.3l1.7-1.8 1.8 1.8");
			case "web" -> g.circle(12, 12, 9).p("M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18");
			case "m3u" -> g.p("M8 6h12M8 12h7M8 18h5M19 15v6M16 18h6").fcircle(4, 6, 1).fcircle(4, 12, 1)
					.fcircle(4, 18, 1);
			case "phone" -> g.p("M21 16.5v3c0 1.2-1 2.1-2.2 2C10 20.6 3.4 14 2.5 5.2 2.4 4 3.3 3 4.5 3h3c1 0 1.9.7 2 1.7.1 1 .4 1.9.7 2.8.3.7.1 1.6-.5 2.1l-1.3 1.3c1.2 2 2.8 3.5 4.7 4.7l1.3-1.3c.6-.6 1.4-.8 2.1-.5.9.3 1.8.6 2.8.7 1 .1 1.7 1 1.7 2z");
			case "set" -> g.circle(12, 12, 3.2f)
					.p("M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8");
			case "home" -> g.p("M3 11 12 3l9 8").p("M5 9.5V21h14V9.5").p("M10 21v-6h4v6");
			case "back" -> g.p("M15 5l-7 7 7 7");
			case "bt" -> g.p("M7 7l10 10-5 5V2l5 5L7 17");
			case "volUp" -> g.p(SPEAKER).p("M15.5 8.5c1.9 1.9 1.9 5.1 0 7M19 5c3.9 3.9 3.9 10.1 0 14");
			case "volDown" -> g.p(SPEAKER).p("M16 12h5");
			case "mute" -> g.p(SPEAKER).p("M16 9l5 6M21 9l-5 6");
			case "sun" -> g.circle(12, 12, 4)
					.p("M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4");
			case "sunLow" -> g.circle(12, 12, 4).p("M12 4v1M12 19v1M4 12h1M19 12h1");
			case "edit" -> g.p("M12 20h9").p("M16.5 3.5c.8-.8 2.2-.8 3 0c.8.8.8 2.2 0 3L7 19l-4 1 1-4z");
			case "check" -> g.p("M20 6 9 17l-5-5");
			case "theme" -> g.circle(12, 12, 9).f("M12 3c5 0 9 4 9 9c0 5-4 9-9 9z");
			case "image" -> g.rect(3, 3, 18, 18, 3).circle(9, 9, 2).p("M21 15l-5-5L5 21");
			case "house" -> g.p("M3 11 12 3l9 8").p("M5 9.5V21h14V9.5");
			case "work" -> g.rect(3, 7, 18, 13, 2).p("M8 7V5c0-1.1.9-2 2-2h4c1.1 0 2 .9 2 2v2");
			case "fuel" -> g.p("M4 21V5c0-1.1.9-2 2-2h6c1.1 0 2 .9 2 2v16M3 21h12M7 8h4")
					.p("M14 10h2c1.1 0 2 .9 2 2v4c0 .8.7 1.5 1.5 1.5c.8 0 1.5-.7 1.5-1.5V8l-3-3");
			case "parking" -> g.rect(3, 3, 18, 18, 4).p("M9 17V7h4c1.7 0 3 1.3 3 3c0 1.7-1.3 3-3 3H9");
			case "mic" -> g.rect(9, 2, 6, 12, 3).p("M5 11c0 3.9 3.1 7 7 7c3.9 0 7-3.1 7-7M12 18v4");
			case "car" -> g.p("M5 17h14l-1.5-6.5C17.3 9.6 16.5 9 15.6 9H8.4c-.9 0-1.7.6-1.9 1.5z").circle(8, 17, 2)
					.circle(16, 17, 2);
			case "plus" -> g.p("M12 5v14M5 12h14");
			case "minus" -> g.p("M5 12h14");
			case "playFill" -> g.f("M8 5v14l11-7z");
			case "pauseFill" -> g.frect(6, 5, 4, 14, 1.2f).frect(14, 5, 4, 14, 1.2f);
			case "nextFill" -> g.f("M6 5v14l10-7z").frect(16.6f, 5, 2.4f, 14, 1);
			case "battery" -> g.rect(2, 7, 18, 10, 2).p("M22 11v2").frect(4.5f, 9.5f, 10, 5, 1);
			default -> g.circle(12, 12, 8);
		}
		return g;
	}
}
