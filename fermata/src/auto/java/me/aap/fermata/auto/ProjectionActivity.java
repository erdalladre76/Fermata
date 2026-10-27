package me.aap.fermata.auto;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
import static me.aap.utils.async.Completed.completedVoid;
import static me.aap.utils.function.ResultConsumer.Cancel.isCancellation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.utils.async.FutureSupplier;
import me.aap.utils.async.Promise;
import me.aap.utils.log.Log;
import me.aap.utils.ui.activity.ActivityBase;
import me.aap.utils.ui.activity.ActivityDelegate;
import me.aap.utils.ui.activity.AppActivity;

public class ProjectionActivity extends ActivityBase {
	private static Promise<Intent> promise;
	private static boolean starting;

	static FutureSupplier<Intent> start() {
		if (promise != null) promise.cancel();
		// Erdal Auto: a previous, abandoned permission request must not block a new one.
		starting = false;
		var p = promise = new Promise<>();
		p.thenRun(() -> {
			if (promise != p) return;
			promise = null;
			starting = false;
		});
		var app = FermataApplication.get();
		try {
			var intent = new Intent(app, ProjectionActivity.class);
			intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			app.startActivity(intent);
		} catch (Exception err) {
			Log.e(err, "Failed to start ProjectionActivity");
		}
		// Erdal Auto: newer Android versions block activity starts from the background.
		// A notification lets the user open the permission screen with one tap.
		showNotification(app);
		p.thenRun(() -> cancelNotification(app));
		return p;
	}

	private static final String NOTIF_CHANNEL = "erdal_projection";
	private static final int NOTIF_ID = 73021;

	/**
	 * Called from a foreground screen (the Erdal home screen on the phone):
	 * if the car is waiting for the screen-share permission, ask for it now.
	 */
	static void resumePending(Context ctx) {
		if ((promise == null) || starting || promise.isDone()) return;
		try {
			var intent = new Intent(ctx, ProjectionActivity.class);
			intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(intent);
		} catch (Exception err) {
			Log.e(err, "Failed to start ProjectionActivity");
		}
	}

	private static void showNotification(Context ctx) {
		try {
			var nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
			if (nm == null) return;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				var ch = new NotificationChannel(NOTIF_CHANNEL, "Araç ekranı izni",
						NotificationManager.IMPORTANCE_HIGH);
				nm.createNotificationChannel(ch);
			}
			var intent = new Intent(ctx, ProjectionActivity.class);
			intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			var pi = PendingIntent.getActivity(ctx, 0, intent,
					PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
			Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
					new Notification.Builder(ctx, NOTIF_CHANNEL) : new Notification.Builder(ctx);
			b.setSmallIcon(R.drawable.notification)
					.setContentTitle("Araç ekranı için dokunun")
					.setContentText("Erdal Auto ekranı araca aktarmak için izin istiyor")
					.setContentIntent(pi)
					.setAutoCancel(true)
					.setCategory(Notification.CATEGORY_CALL)
					.setPriority(Notification.PRIORITY_MAX);
			nm.notify(NOTIF_ID, b.build());
		} catch (Throwable err) {
			Log.e(err, "Failed to show projection notification");
		}
	}

	private static void cancelNotification(Context ctx) {
		try {
			var nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
			if (nm != null) nm.cancel(NOTIF_ID);
		} catch (Throwable ignore) {
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (promise != null) promise.cancel();
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (starting) return;
		var p = promise;
		if (p == null) {
			finish();
			return;
		}
		starting = true;
		checkOverlayPermission().thenIgnoreResult(this::checkWriteSettingsPermission)
				.thenIgnoreResult(this::checkAccessibilityPermission)
				.thenIgnoreResult(this::requestRootPermission)
				.thenIgnoreResult(this::requestScreenCapturePermission).onCompletion((i, err) -> {
					AccessibilityEventDispatcherService.autoClickOnButton(null);
					if (promise != p) return;
					starting = false;
					finish();
					if (err != null) {
						if (!isCancellation(err)) Log.i(err, "Screen capture request failed");
					} else if (i == null) {
						Log.i("Screen capture request rejected");
						p.cancel();
					} else {
						p.complete(i);
					}
				});
	}

	private FutureSupplier<?> checkOverlayPermission() {
		if (Settings.canDrawOverlays(this)) return completedVoid();
		Log.i("Requesting ACTION_MANAGE_OVERLAY_PERMISSION permission");
		return startActivityForResult(() -> new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
				Uri.parse("package:" + getPackageName())));
	}

	private FutureSupplier<?> checkWriteSettingsPermission() {
		if (Settings.System.canWrite(this)) return completedVoid();
		Log.i("Requesting ACTION_MANAGE_WRITE_SETTINGS permission");
		return startActivityForResult(() -> new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
				Uri.parse("package:" + getPackageName())));
	}

	private FutureSupplier<?> checkAccessibilityPermission() {
		if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.O) || isAccessibilityEnabled())
			return completedVoid();
		Log.i("Requesting ACTION_ACCESSIBILITY_SETTINGS permission");
		var p = new Promise<>();
		var intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
		intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP);
		startActivity(intent);
		getContentResolver().registerContentObserver(
				Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false,
				new ContentObserver(FermataApplication.get().getHandler()) {
					@Override
					public void onChange(boolean selfChange) {
						if (!isAccessibilityEnabled()) return;
						getContentResolver().unregisterContentObserver(this);
						p.complete(null);
					}
				});
		return p;
	}

	private boolean isAccessibilityEnabled() {
		var cn = new ComponentName(this, AccessibilityEventDispatcherService.class);
		var enabledServices =
				Settings.Secure.getString(getContentResolver(), ENABLED_ACCESSIBILITY_SERVICES);
		if (enabledServices == null) return false;
		for (var c : enabledServices.split(":")) {
			var n = ComponentName.unflattenFromString(c);
			if ((n != null) && (n.equals(cn))) return true;
		}
		return false;
	}

	private FutureSupplier<?> requestRootPermission() {
		return Su.get().onSuccess(su -> {
			EventDispatcher.useSu(su);
			su.exec("appops set " + getPackageName() + " PROJECT_MEDIA allow");
		});
	}

	private FutureSupplier<Intent> requestScreenCapturePermission() {
		Log.i("Requesting screen cast permission");
		AccessibilityEventDispatcherService.autoClickOnButton(
				getString(R.string.media_projection_action_text));
		var mm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
		return startActivityForResult(mm::createScreenCaptureIntent);
	}

	@Override
	protected FutureSupplier<? extends ActivityDelegate> createDelegate(AppActivity a) {
		return NO_DELEGATE;
	}
}
