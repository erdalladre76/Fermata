package me.aap.fermata.auto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Live weather (Open-Meteo), exchange rates (ExchangeRate-API open access) and gold (xaus.com).
 */
public final class ErdalData {
	private static final ExecutorService exec = Executors.newSingleThreadExecutor();
	private static final Handler main = new Handler(Looper.getMainLooper());

	private ErdalData() {}

	public static final class Weather {
		public final double temp;
		public final int code;
		public final String place;

		Weather(double temp, int code, String place) {
			this.temp = temp;
			this.code = code;
			this.place = place;
		}

		/**
		 * sun, partly, cloud, rain, snow, storm, fog
		 */
		public String kind() {
			if (code == 0 || code == 1) return "sun";
			if (code == 2) return "partly";
			if (code == 3) return "cloud";
			if (code == 45 || code == 48) return "fog";
			if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
			if (code >= 95) return "storm";
			if (code >= 51) return "rain";
			return "cloud";
		}

		public String label() {
			return switch (kind()) {
				case "sun" -> "Güneşli";
				case "partly" -> "Parçalı bulutlu";
				case "cloud" -> "Bulutlu";
				case "fog" -> "Sisli";
				case "snow" -> "Karlı";
				case "storm" -> "Fırtınalı";
				default -> "Yağmurlu";
			};
		}
	}

	public static final class Market {
		public final double usd;
		public final double eur;
		public final double gramGold;

		Market(double usd, double eur, double gramGold) {
			this.usd = usd;
			this.eur = eur;
			this.gramGold = gramGold;
		}
	}

	public static void loadWeather(Context ctx, double lat, double lon, String place,
																 Consumer<Weather> cb) {
		var app = ctx.getApplicationContext();
		exec.execute(() -> {
			try {
				var url = String.format(Locale.US,
						"https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code",
						lat, lon);
				var cur = new JSONObject(get(url)).getJSONObject("current");
				var name = place;
				if (name == null) {
					try {
						var list = new android.location.Geocoder(app, new Locale("tr", "TR"))
								.getFromLocation(lat, lon, 1);
						if (list != null && !list.isEmpty()) {
							var a = list.get(0);
							name = a.getSubAdminArea() != null ? a.getSubAdminArea() :
									a.getLocality() != null ? a.getLocality() : a.getAdminArea();
						}
					} catch (Throwable ignore) {
					}
				}
				if (name == null) name = "";
				var w = new Weather(cur.getDouble("temperature_2m"), cur.getInt("weather_code"), name);
				ErdalSettings.setCache(app, "weather", w.temp + ";" + w.code + ";" + w.place);
				main.post(() -> cb.accept(w));
			} catch (Throwable ex) {
				var c = ErdalSettings.getCache(app, "weather");
				if (c != null) {
					try {
						var p = c.split(";", 3);
						var w = new Weather(Double.parseDouble(p[0]), Integer.parseInt(p[1]), p[2]);
						main.post(() -> cb.accept(w));
					} catch (Throwable ignore) {
					}
				}
			}
		});
	}

	public static void loadMarket(Context ctx, Consumer<Market> cb) {
		var app = ctx.getApplicationContext();
		exec.execute(() -> {
			double usd = 0, eur = 0, gold = 0;
			try {
				var rates = new JSONObject(get("https://open.er-api.com/v6/latest/USD"))
						.getJSONObject("rates");
				usd = rates.getDouble("TRY");
				eur = usd / rates.getDouble("EUR");
			} catch (Throwable ignore) {
			}
			try {
				var g = new JSONObject(get("https://xaus.com/api/v1/spot"));
				double perGramUsd = g.optDouble("per_gram_usd", 0);
				if (perGramUsd <= 0) perGramUsd = g.optDouble("spot_usd_oz", 0) / 31.1035;
				gold = perGramUsd * usd;
			} catch (Throwable ignore) {
			}
			if (usd > 0) {
				ErdalSettings.setCache(app, "market", usd + ";" + eur + ";" + gold);
			} else {
				var c = ErdalSettings.getCache(app, "market");
				if (c != null) {
					try {
						var p = c.split(";");
						usd = Double.parseDouble(p[0]);
						eur = Double.parseDouble(p[1]);
						gold = Double.parseDouble(p[2]);
					} catch (Throwable ignore) {
					}
				}
			}
			var m = new Market(usd, eur, gold);
			main.post(() -> cb.accept(m));
		});
	}

	private static String get(String url) throws Exception {
		var c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000);
		c.setReadTimeout(8000);
		c.setRequestProperty("User-Agent", "ErdalAuto/1.0");
		try (InputStream in = c.getInputStream()) {
			var out = new ByteArrayOutputStream();
			var buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
			return out.toString("UTF-8");
		} finally {
			c.disconnect();
		}
	}
}
