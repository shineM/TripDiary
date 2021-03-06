package com.yupog2003.tripdiary.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.format.Time;
import android.widget.Toast;

import com.google.analytics.tracking.android.EasyTracker;
import com.google.analytics.tracking.android.MapBuilder;
import com.yupog2003.tripdiary.R;
import com.yupog2003.tripdiary.RecordActivity;
import com.yupog2003.tripdiary.data.FileHelper;
import com.yupog2003.tripdiary.data.POI;
import com.yupog2003.tripdiary.data.Trip;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Random;

public class RecordService extends Service implements LocationListener, GpsStatus.Listener, Runnable, SensorEventListener {
	public double latitude;
	public double longitude;
	public double elevation;
	BufferedWriter bw;
	Time time;
	NotificationCompat.Builder nb;
	public static RecordService instance;
	public final long startTime = System.currentTimeMillis() / 1000;
	public long totalTime = 0;
	public double nowSpeed = 0;
	public double totaldistance = 0;
	public float accuracy = -1;
	Location previousLocation;
	public boolean run;
	boolean screenOn;
	StopTripReceiver stopTripReceiver;
	PauseReceiver pauseReceiver;
	ScreenOnOffReceiver screenOnOffReceiver;
	String name;
	String path;
	String note;
	Trip trip;
	int recordDuration;
	public int updateDuration;
	long lastFixTime;
	public static final String actionStopTrip = "com.yupog2003.tripdiary.stopTrip";
	public static final String actionPauseTrip = "com.yupog2003.tripdiary.pauseTrip";
	public static final DecimalFormat doubleFormat = new DecimalFormat("#.#");

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		instance = this;
		name = intent.getStringExtra("name");
		path = intent.getStringExtra("path");
		note = intent.getStringExtra("note");
		trip = new Trip(RecordService.this, new File(path + "/" + name));
		trip.deleteCache();
		run = true;
		screenOn = true;
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(RecordService.this);
		recordDuration = Integer.valueOf(preferences.getString("record_duration", "1000"));
		updateDuration = Integer.valueOf(preferences.getString("update_duration", "1000"));
		setupNotification(path, name, note);
		if (trip.gpxFile.exists()) {
			try {
				if (trip.gpxFile.length() == 0) {
					bw = new BufferedWriter(new FileWriter(trip.gpxFile, true));
					bw.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n");
					bw.write("	<gpx>\n");
					bw.write("	<trk>\n");
					bw.write("	<trkseg>\n");
					bw.flush();
				} else {
					File temp = new File(trip.dir.getPath(), "temp");
					FileHelper.copyFile(trip.gpxFile, temp);
					BufferedReader br = new BufferedReader(new FileReader(temp));
					bw = new BufferedWriter(new FileWriter(trip.gpxFile, false));
					String s;
					while ((s = br.readLine()) != null) {
						if (s.contains("<?xml") || s.contains("<gpx") || s.contains("<trk>") || s.contains("<trkseg") || s.contains("<trkpt") || s.contains("<ele>") || s.contains("<time>") || s.contains("</trkpt")) {
							bw.write(s + "\n");
						}
						if (s.contains("</gpx>") || s.contains("</trkseg>") || s.contains("</trk>")) {
							break;
						}
					}
					bw.flush();
					br.close();
					temp.delete();
				}
			} catch (IOException e) {

				e.printStackTrace();
			}
			LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
			lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, recordDuration, 0, this);
			lm.addGpsStatusListener(this);
			new Thread(RecordService.this).start();
		}
		time = new Time(Time.TIMEZONE_UTC);
		stopTripReceiver = new StopTripReceiver();
		pauseReceiver = new PauseReceiver();
		screenOnOffReceiver = new ScreenOnOffReceiver();
		registerReceiver(stopTripReceiver, new IntentFilter(actionStopTrip));
		registerReceiver(pauseReceiver, new IntentFilter(actionPauseTrip));
		registerReceiver(screenOnOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
		registerReceiver(screenOnOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
		Intent i = new Intent(this, RecordActivity.class);
		i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		i.putExtra(RecordActivity.tag_rootpath, path);
		i.putExtra(RecordActivity.tag_tripname, name);
		i.putExtra(RecordActivity.tag_isgpsenabled, true);
		i.putExtra(RecordActivity.tag_addpoi_intent, false);
		startActivity(i);
		return START_REDELIVER_INTENT;
	}

	private void setupNotification(String path, String name, String note) {
		nb = new NotificationCompat.Builder(this);
		nb.setContentTitle(name);
		nb.setContentText(getString(R.string.click_or_swipe_down_to_view_detail));
		nb.setSmallIcon(R.drawable.ic_satellite);
		nb.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
		nb.setTicker(getString(R.string.Start_Trip));
		Intent i2 = new Intent(this, RecordActivity.class);
		i2.putExtra(RecordActivity.tag_rootpath, path);
		i2.putExtra(RecordActivity.tag_tripname, name);
		i2.putExtra(RecordActivity.tag_isgpsenabled, true);
		i2.putExtra(RecordActivity.tag_addpoi_intent, true);
		PendingIntent pi = PendingIntent.getActivity(this, 0, i2, PendingIntent.FLAG_UPDATE_CURRENT);
		nb.addAction(R.drawable.poi, getString(R.string.add_poi), pi);
		PendingIntent pi2 = PendingIntent.getBroadcast(this, 0, new Intent(actionPauseTrip), PendingIntent.FLAG_UPDATE_CURRENT);
		nb.addAction(R.drawable.ic_pause, getString(R.string.pause), pi2);
		PendingIntent pi3 = PendingIntent.getBroadcast(this, 0, new Intent(actionStopTrip), PendingIntent.FLAG_UPDATE_CURRENT);
		nb.addAction(R.drawable.ic_stop, getString(R.string.stop), pi3);
		Intent i3 = new Intent(this, RecordActivity.class);
		i3.putExtra(RecordActivity.tag_rootpath, path);
		i3.putExtra(RecordActivity.tag_tripname, name);
		i3.putExtra("isgpsenabled", true);
		i3.putExtra(RecordActivity.tag_addpoi_intent, false);
		PendingIntent pi4 = PendingIntent.getActivity(this, 1, i3, PendingIntent.FLAG_UPDATE_CURRENT);
		nb.setContentIntent(pi4);
		nb.setOngoing(true);
		nb.setStyle(getContent());
		startForeground(1, nb.build());
	}

	protected void updateNotification() {

		if (run && screenOn) {
			totalTime = System.currentTimeMillis() / 1000 - startTime;
			nb.setStyle(getContent());
			startForeground(1, nb.build());
		}
	}

	private NotificationCompat.InboxStyle getContent() {
		String timeExpression = String.valueOf(totalTime / 3600) + ":" + String.valueOf(totalTime % 3600 / 60) + ":" + String.valueOf(totalTime % 3600 % 60);
		NotificationCompat.InboxStyle content = new NotificationCompat.InboxStyle();
		content.addLine(getString(R.string.distance) + ":" + doubleFormat.format(totaldistance / 1000) + "km");
		content.addLine(getString(R.string.total_time) + ":" + timeExpression);
		content.addLine(getString(R.string.velocity) + ":" + doubleFormat.format(nowSpeed * 18 / 5) + "km/hr");
		content.addLine(getString(R.string.accuracy) + "=" + (accuracy == -1 ? "∞" : (doubleFormat.format(accuracy) + "m")));
		content.addLine(getString(R.string.Altitude) + ":" + doubleFormat.format(elevation));
		return content;
	}

	@Override
	public IBinder onBind(Intent arg0) {

		return null;
	}

	@Override
	public void onDestroy() {
		unregisterReceiver(stopTripReceiver);
		unregisterReceiver(pauseReceiver);
		unregisterReceiver(screenOnOffReceiver);
		SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		sm.unregisterListener(RecordService.this);
		super.onDestroy();
	}

	public class StopTripReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {

			run = false;
			try {
				if (bw != null) {
					bw.write("	</trkseg>\n");
					bw.write("	</trk>\n");
					bw.write("	</gpx>");
					bw.flush();
					bw.close();
				}
			} catch (IOException e) {

				e.printStackTrace();
			}
			trip.deleteCache();
			stopForeground(true);
			LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
			lm.removeUpdates(RecordService.this);
			Toast.makeText(getApplicationContext(), getString(R.string.trip_has_been_stopped), Toast.LENGTH_SHORT).show();
			EasyTracker.getInstance(RecordService.this).send(MapBuilder.createEvent("Trip", "stop", name, null).build());
			instance = null;
			RecordService.this.stopSelf();
		}
	}

	public class PauseReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {

			run = !run;
			nb = new NotificationCompat.Builder(RecordService.this);
			nb.setContentTitle(name);
			nb.setContentText(getString(R.string.click_or_swipe_down_to_view_detail));
			nb.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher));
			nb.setTicker(getString(R.string.Start_Trip));
			Intent i2 = new Intent(RecordService.this, RecordActivity.class);
			i2.putExtra(RecordActivity.tag_rootpath, path);
			i2.putExtra(RecordActivity.tag_tripname, name);
			i2.putExtra(RecordActivity.tag_isgpsenabled, true);
			i2.putExtra(RecordActivity.tag_addpoi_intent, "true");
			PendingIntent pi = PendingIntent.getActivity(RecordService.this, 0, i2, PendingIntent.FLAG_CANCEL_CURRENT);
			nb.addAction(R.drawable.poi, getString(R.string.add_poi), pi);
			if (run) {
				LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
				lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, recordDuration, 0, RecordService.this);
				new Thread(RecordService.this).start();
				PendingIntent pi2 = PendingIntent.getBroadcast(RecordService.this, 0, new Intent(actionPauseTrip), PendingIntent.FLAG_UPDATE_CURRENT);
				nb.addAction(R.drawable.ic_pause, getString(R.string.pause), pi2);
				nb.setSmallIcon(R.drawable.ic_satellite);
			} else {
				LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
				lm.removeUpdates(RecordService.this);
				PendingIntent pi2 = PendingIntent.getBroadcast(RecordService.this, 0, new Intent(actionPauseTrip), PendingIntent.FLAG_UPDATE_CURRENT);
				nb.addAction(R.drawable.ic_resume, getString(R.string.resume), pi2);
				nb.setSmallIcon(R.drawable.ic_pause);
			}
			PendingIntent pi3 = PendingIntent.getBroadcast(RecordService.this, 0, new Intent(actionStopTrip), PendingIntent.FLAG_UPDATE_CURRENT);
			nb.addAction(R.drawable.ic_stop, getString(R.string.stop), pi3);
			Intent i3 = new Intent(RecordService.this, RecordActivity.class);
			i3.putExtra(RecordActivity.tag_rootpath, path);
			i3.putExtra(RecordActivity.tag_tripname, name);
			i3.putExtra("isgpsenabled", true);
			PendingIntent pi4 = PendingIntent.getActivity(RecordService.this, 1, i3, PendingIntent.FLAG_CANCEL_CURRENT);
			nb.setContentIntent(pi4);
			nb.setOngoing(true);
			nb.setStyle(getContent());
			startForeground(1, nb.build());
		}

	}

	public class ScreenOnOffReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {

			if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
				screenOn = true;
				new Thread(RecordService.this).start();
				if (PreferenceManager.getDefaultSharedPreferences(RecordService.this).getBoolean("shaketoaddpoi", false)) {
					SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
					sm.registerListener(RecordService.this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI);
				}
			} else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				screenOn = false;
				SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
				sm.unregisterListener(RecordService.this);
			}
		}

	}

	public void onLocationChanged(Location location) {
		if (location == null)
			return;
		if (previousLocation == null) {
			previousLocation = location;
		}
		lastFixTime = System.currentTimeMillis();
		totaldistance += location.distanceTo(previousLocation);
		previousLocation = location;
		latitude = location.getLatitude();
		longitude = location.getLongitude();
		elevation = location.getAltitude();
		nowSpeed = location.getSpeed();
		accuracy = location.getAccuracy();
		time.setToNow();
		try {
			if (run) {
				bw.write("		<trkpt lat=\"" + String.valueOf(latitude) + "\" lon=\"" + String.valueOf(longitude) + "\">\n");
				bw.write("			<ele>" + String.valueOf(elevation) + "</ele>\n");
				bw.write("			<time>" + String.valueOf(time.year) + "-" + String.valueOf(time.month + 1) + "-" + String.valueOf(time.monthDay) + "T" + String.valueOf(time.hour) + ":" + String.valueOf(time.minute) + ":" + String.valueOf(time.second) + "Z</time>\n");
				bw.write("		</trkpt>\n");
				bw.flush();
			}
		} catch (IOException e) {

			e.printStackTrace();
		}
	}

	public void onProviderDisabled(String provider) {

		nb.setSmallIcon(R.drawable.ic_pause);
		startForeground(1, nb.build());
	}

	public void onProviderEnabled(String provider) {

		nb.setSmallIcon(R.drawable.ic_satellite);
		startForeground(1, nb.build());
	}

	public void onStatusChanged(String provider, int status, Bundle extras) {

	}

	public void run() {

		while (run && screenOn) {
			try {
				Thread.sleep(updateDuration);
				updateNotification();
			} catch (InterruptedException e) {

				e.printStackTrace();
			}
		}
	}

	public void onGpsStatusChanged(int event) {

		switch (event) {
		case GpsStatus.GPS_EVENT_FIRST_FIX:
			if (run) {
				((Vibrator) getSystemService(Service.VIBRATOR_SERVICE)).vibrate(200);
				nb.setSmallIcon(R.drawable.ic_resume);
				startForeground(1, nb.build());
			}
			break;
		case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
			if (screenOn && run) {
				boolean isGpsFix = System.currentTimeMillis() - lastFixTime < 3000;
				nb.setSmallIcon(isGpsFix ? R.drawable.ic_resume : R.drawable.ic_satellite);
				accuracy = isGpsFix ? accuracy : -1;
				startForeground(1, nb.build());
			}
			break;
		}
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy) {


	}

	long lastUpadateSensor = -1;
	long lastAddPOI = -1;
	public static final float SHAKE_THRESHOLD_GRAVITY = 2.7F;
	public static final int min_diffTime_between_add_poi = 5000; // milliseconds

	public void onSensorChanged(SensorEvent event) {

		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			long diffTime = System.currentTimeMillis() - lastUpadateSensor;
			if (diffTime > 100) {
				lastUpadateSensor = System.currentTimeMillis();
				float x = event.values[0] / SensorManager.GRAVITY_EARTH;
				float y = event.values[1] / SensorManager.GRAVITY_EARTH;
				float z = event.values[2] / SensorManager.GRAVITY_EARTH;
				float g = (float) Math.sqrt(x * x + y * y + z * z);
				if (g > SHAKE_THRESHOLD_GRAVITY && lastUpadateSensor - lastAddPOI > min_diffTime_between_add_poi) {
					String[] pois = new File(path + "/" + name).list(FileHelper.getDirFilter());
					int num_POIs = pois == null ? new Random().nextInt() : pois.length;
					POI poi = new POI(new File(path + "/" + name + "/" + String.valueOf(num_POIs)));
					Time time = new Time(Time.TIMEZONE_UTC);
					time.setToNow();
					poi.updateBasicInformation(null, time, latitude, longitude, elevation);
					lastAddPOI = lastUpadateSensor;
					((Vibrator) getSystemService(Service.VIBRATOR_SERVICE)).vibrate(200);
				}
			}
		}
	}
	
}
