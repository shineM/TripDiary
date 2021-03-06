package com.yupog2003.tripdiary.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.Time;
import android.util.Log;

import com.google.analytics.tracking.android.EasyTracker;
import com.google.analytics.tracking.android.MapBuilder;
import com.yupog2003.tripdiary.R;
import com.yupog2003.tripdiary.ViewTripActivity;
import com.yupog2003.tripdiary.data.FileHelper;
import com.yupog2003.tripdiary.data.POI;
import com.yupog2003.tripdiary.fragments.ViewMapFragment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class GenerateVideoService extends IntentService {

    NotificationCompat.Builder nb;
    NotificationManagerCompat nm;
    File tempDir;
    File ffmpegFile;
    POI[] pois;
    String tripName;
    String resultVideoPath;
    String videoName;
    String timeZone;
    int num_total_materials;
    int num_processed_materials;
    public int fps;
    public String fpsStr;
    public static final int secondsPerDiary = 2;
    public static final int secondsPerTitle = 1;
    public static final int secondsPerTrack = 1;
    public static final int secondsPerPicture = 2;
    public int videoWidth;
    public int videoHeight;
    public int diaryTextSize; //in px
    public int titleTextSize; //in px
    public static final String vcodec = "libx264";
    public static final String acodec = "aac";
    public static final String preset = "ultrafast";
    public static final String tag_background_music_path = "background_music_path";
    public static final String tag_video_width = "videowidth";
    public static final String tag_video_height = "videoheight";
    public static final String tag_fps = "fps";
    public static final String tag_videoname = "video_name";
    public static final String cacheDirName = "VideoCache";

    public GenerateVideoService() {
        super("GenerateVideoService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        tripName = ViewTripActivity.trip.tripName;
        timeZone = ViewTripActivity.trip.timezone;
        tempDir = new File(getCacheDir(), cacheDirName);
        ffmpegFile = new File(getFilesDir().getParent(), "ffmpeg");
        if (!ffmpegFile.exists()) {
            copyFFmpeg();
        }
        pois = ViewTripActivity.trip.pois.clone();
        num_total_materials = getNum_total_materials(pois);
        num_processed_materials = 0;
        Bitmap gmapBitmap = ViewMapFragment.gmapBitmap.copy(Bitmap.Config.RGB_565, true);
        Point[] trackPoints = ViewMapFragment.trackPoints.clone();
        ViewMapFragment.gmapBitmap.recycle();
        ViewMapFragment.trackPoints = null;
        String backgroundMusicPath = intent.getStringExtra(tag_background_music_path);
        fps = intent.getIntExtra(tag_fps, 25);
        fpsStr = "fps=" + String.valueOf(fps);
        videoWidth = intent.getIntExtra(tag_video_width, 1920);
        videoHeight = intent.getIntExtra(tag_video_height, 1080);
        diaryTextSize = videoWidth / 25;
        titleTextSize = diaryTextSize * 2;
        videoName = intent.getStringExtra(tag_videoname);
        if (videoName == null || videoName.equals("")) videoName = tripName + ".mp4";
        if (backgroundMusicPath != null) num_total_materials++;
        nm = NotificationManagerCompat.from(this);
        nb = new NotificationCompat.Builder(this);
        nb.setContentTitle(getString(R.string.encoding) + ":" + videoName);
        nb.setTicker(tripName);
        nb.setSmallIcon(R.drawable.ic_launcher);
        nb.setProgress(100, 0, false);
        nb.setOngoing(true);
        nm.notify(1, nb.build());
        resultVideoPath = generateTripVideo(tripName, pois, gmapBitmap, trackPoints, backgroundMusicPath);
    }

    private String generateTripVideo(String tripName, POI[] pois, Bitmap gmapBitmap, Point[] trackPoints, String backgroundMusicPath) {
        boolean success;
        File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        moviesDir.mkdirs();
        String resultVideoPath = moviesDir.getPath() + "/" + videoName;
        try {
            String tripTitleVideoName = generateTripTitleVideo(tripName);
            String[] trackVideoNames = generateTrackVideos(gmapBitmap, trackPoints);
            String[] poiVideoNames = generatePOIVideos(pois);
            if (trackVideoNames.length - poiVideoNames.length != 1) {
                return null;
            }
            BufferedWriter tripWriter = new BufferedWriter(new FileWriter(new File(tempDir, "trip_input.txt")));
            if (tripTitleVideoName != null) {
                tripWriter.write("file '" + tripTitleVideoName + "'\n");
            }
            for (int i = 0; i < poiVideoNames.length; i++) {
                if (trackVideoNames[i] != null) {
                    tripWriter.write("file '" + trackVideoNames[i] + "'\n");
                }
                if (poiVideoNames[i] != null) {
                    tripWriter.write("file '" + poiVideoNames[i] + "'\n");
                }
            }
            if (trackVideoNames[trackVideoNames.length - 1] != null) {
                tripWriter.write("file '" + trackVideoNames[trackVideoNames.length - 1] + "'\n");
            }
            tripWriter.flush();
            tripWriter.close();
            new File(resultVideoPath).delete();
            if (backgroundMusicPath != null) {
                runCommand(new String[]{"ffmpeg", "-f", "concat", "-i", "trip_input.txt", "-c", "copy", "temp.mp4"}, null, null);
                MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
                mediaMetadataRetriever.setDataSource(tempDir.getPath() + "/temp.mp4");
                int videoLength = Integer.valueOf(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
                mediaMetadataRetriever.setDataSource(backgroundMusicPath);
                int musicLength = Integer.valueOf(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
                int loop_times = videoLength / musicLength + 1;
                BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(new File(tempDir, "input.txt")));
                for (int i = 0; i < loop_times; i++) {
                    bufferedWriter.write("file '"+backgroundMusicPath+"'\n");
                }
                bufferedWriter.flush();
                bufferedWriter.close();
                String audioExt=backgroundMusicPath.substring(backgroundMusicPath.lastIndexOf(".")+1);
                //extend music
                runCommand(new String[]{"ffmpeg", "-f", "concat", "-i", "input.txt", "-c", "copy", "-strict", "-2", "atemp."+audioExt}, getString(R.string.add_background_music), null);
                //cut music to video length
                runCommand(new String[]{"ffmpeg", "-ss", "0", "-t", String.valueOf(videoLength), "-i", "atemp."+audioExt, "-c", "copy", "-strict", "-2", "atemp2."+audioExt}, getString(R.string.add_background_music), null);
                //add audio fade out
                String audioFadeOut = "afade=t=out:st=" + String.valueOf(videoLength - 2) + ":d=2";
                runCommand(new String[]{"ffmpeg", "-i", "atemp2."+audioExt, "-af", audioFadeOut, "-c:a", acodec, "-strict", "-2", "atemp3.mp4"}, getString(R.string.add_background_music), videoLength);
                //combine with video
                success = runCommand(new String[]{"ffmpeg", "-i", "temp.mp4", "-i", "atemp3.mp4", "-map", "0:v", "-map", "1:a", "-c", "copy", "-strict", "-2", resultVideoPath}, getString(R.string.add_background_music), null);
                new File(tempDir, "temp.mp4").delete();
                new File(tempDir, "atemp."+audioExt).delete();
                new File(tempDir, "atemp2."+audioExt).delete();
                new File(tempDir, "atemp3.mp4").delete();
                new File(tempDir, "input.txt").delete();
            } else {
                success = runCommand(new String[]{"ffmpeg", "-f", "concat", "-i", "trip_input.txt", "-c", "copy", resultVideoPath}, null, null);
            }
            new File(tempDir, tripTitleVideoName).delete();
            for (int i = 0; i < trackVideoNames.length; i++) {
                new File(tempDir, trackVideoNames[i]).delete();
            }
            for (int i = 0; i < poiVideoNames.length; i++) {
                new File(tempDir, poiVideoNames[i]).delete();
            }
            new File(tempDir, "trip_input.txt").delete();
        } catch (NullPointerException e) {
            e.printStackTrace();
            success = false;
        } catch (IOException e) {
            e.printStackTrace();
            success = false;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            success = false;
        }
        System.gc();
        return success ? resultVideoPath : null;
    }

    private String generateTripTitleVideo(String tripName) throws IOException {
        String videoName = "trip_title.mp4";
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(titleTextSize);
        textPaint.setAntiAlias(true);
        StaticLayout staticLayout = new StaticLayout(tripName, textPaint, videoWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        Bitmap bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.RGB_565);
        bitmap.eraseColor(Color.BLACK);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.translate(0, (videoHeight - staticLayout.getHeight()) / 2);
        staticLayout.draw(canvas);
        canvas.restore();
        FileOutputStream fileOutputStream = new FileOutputStream(new File(tempDir, "title_0.jpg"));
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
        fileOutputStream.flush();
        fileOutputStream.close();
        bitmap.recycle();
        FileHelper.copyFile(new File(tempDir, "title_0.jpg"), new File(tempDir, "title_1.jpg"));
        FileHelper.copyFile(new File(tempDir, "title_0.jpg"), new File(tempDir, "title_2.jpg"));
        String cmdDescription = videoName;
        boolean success = runCommand(new String[]{"ffmpeg", "-framerate", "1/" + String.valueOf(secondsPerTitle), "-i", "title_%d.jpg", "-c:v", vcodec, "-preset", preset, "-vf", fpsStr + ",fade=in:0:" + String.valueOf(fps), "temp.mp4"}, cmdDescription, secondsPerTitle * 2);
        boolean success2 = runCommand(new String[]{"ffmpeg", "-f", "lavfi", "-i", "aevalsrc=0", "-i", "temp.mp4", "-shortest", "-c:v", "copy", "-strict", "-2", videoName}, cmdDescription, null);
        videoName = success && success2 ? videoName : null;
        new File(tempDir, "temp.mp4").delete();
        new File(tempDir, "title_0.jpg").delete();
        new File(tempDir, "title_1.jpg").delete();
        new File(tempDir, "title_2.jpg").delete();
        num_processed_materials++;
        return videoName;
    }

    private String[] generatePOIVideos(POI[] pois) throws IOException {
        String[] poiVideoNames = new String[pois.length];
        for (int i = 0; i < pois.length; i++) {
            POI poi = pois[i];
            String poiVideoName = "POI_" + poi.title + ".mp4";
            BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(new File(tempDir, "poi_input.txt")));
            generateTitleVideo(bufferedWriter, poi);
            generateDiaryVideo(bufferedWriter, poi);
            generatePicVideo(bufferedWriter, poi);
            generateVideoVideo(bufferedWriter, poi);
            generateAudioVideo(bufferedWriter, poi);
            bufferedWriter.flush();
            bufferedWriter.close();
            boolean success = runCommand(new String[]{"ffmpeg", "-f", "concat", "-i", "poi_input.txt", "-c", "copy", poiVideoName}, poiVideoName, null);
            poiVideoNames[i] = success ? poiVideoName : null;
            BufferedReader bufferedReader = new BufferedReader(new FileReader(new File(tempDir, "poi_input.txt")));
            String s;
            while ((s = bufferedReader.readLine()) != null) {
                if (s.contains("file '")) {
                    String fileName = s.substring(s.indexOf("file '") + 6, s.lastIndexOf("'"));
                    new File(tempDir, fileName).delete();
                }
            }
            bufferedReader.close();
            new File(tempDir, "poi_input.txt").delete();
        }
        return poiVideoNames;
    }

    private void generateTitleVideo(BufferedWriter poiWriter, POI poi) throws IOException {
        String videoName = poi.title + "_title.mp4";
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(titleTextSize);
        textPaint.setAntiAlias(true);
        StaticLayout staticLayout = new StaticLayout(poi.title, textPaint, videoWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        Bitmap bitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.RGB_565);
        bitmap.eraseColor(Color.BLACK);
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.translate(0, (videoHeight - staticLayout.getHeight()) / 2);
        staticLayout.draw(canvas);
        canvas.restore();
        Time time = poi.time;
        time.switchTimezone(timeZone);
        String timeStr = time.format("%F %T");
        textPaint.setTextSize(diaryTextSize);
        int x = (videoWidth - (int) textPaint.measureText(timeStr)) / 2;
        int y = videoHeight / 2 + staticLayout.getHeight() + 1;
        canvas.drawText(timeStr, x, y, textPaint);
        FileOutputStream fileOutputStream = new FileOutputStream(new File(tempDir, "title_0.jpg"));
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
        fileOutputStream.flush();
        fileOutputStream.close();
        bitmap.recycle();
        FileHelper.copyFile(new File(tempDir, "title_0.jpg"), new File(tempDir, "title_1.jpg"));
        FileHelper.copyFile(new File(tempDir, "title_0.jpg"), new File(tempDir, "title_2.jpg"));
        String cmdDescription = videoName;
        boolean success = runCommand(new String[]{"ffmpeg", "-framerate", "1/" + String.valueOf(secondsPerTitle), "-i", "title_%d.jpg", "-c:v", vcodec, "-preset", preset, "-vf", fpsStr + ",fade=in:0:" + String.valueOf(fps), "temp.mp4"}, cmdDescription, secondsPerTitle * 2);
        boolean success2 = runCommand(new String[]{"ffmpeg", "-f", "lavfi", "-i", "aevalsrc=0", "-i", "temp.mp4", "-shortest", "-c:v", "copy", "-strict", "-2", videoName}, cmdDescription, null);
        if (success && success2) {
            poiWriter.write("file '" + videoName + "'\n");
        }
        new File(tempDir, "temp.mp4").delete();
        new File(tempDir, "title_0.jpg").delete();
        new File(tempDir, "title_1.jpg").delete();
        new File(tempDir, "title_2.jpg").delete();
        num_processed_materials++;
    }

    private void generateDiaryVideo(BufferedWriter poiWriter, POI poi) throws IOException {
        if (poi.diary.length() == 0) return;
        String videoName = poi.title + "_diary.mp4";
        String text = poi.diary;
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(diaryTextSize);
        textPaint.setAntiAlias(true);
        StaticLayout staticLayout = new StaticLayout(text, textPaint, videoWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        int num_bitmaps = staticLayout.getHeight() / videoHeight + 1;
        Bitmap bitmap = Bitmap.createBitmap(staticLayout.getWidth(), num_bitmaps * videoHeight, Bitmap.Config.RGB_565);
        bitmap.eraseColor(Color.BLACK);
        Canvas canvas = new Canvas(bitmap);
        staticLayout.draw(canvas);
        for (int i = 0; i < num_bitmaps; i++) {
            Bitmap diaryBitmap = Bitmap.createBitmap(bitmap, 0, i * videoHeight, videoWidth, videoHeight);
            FileOutputStream fileOutputStream = new FileOutputStream(new File(tempDir, "diary_" + String.valueOf(i + 1) + ".jpg"));
            diaryBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.flush();
            fileOutputStream.close();
            diaryBitmap.recycle();
            if (i == 0) {//for compensate ffmpeg bug - first frame may be skipped
                FileHelper.copyFile(new File(tempDir, "diary_1.jpg"), new File(tempDir, "diary_0.jpg"));
            }
            if (i == num_bitmaps - 1) { //for compensate ffmpeg bug - last frame may be skipped
                FileHelper.copyFile(new File(tempDir, "diary_" + String.valueOf(i + 1) + ".jpg"), new File(tempDir, "diary_" + String.valueOf(i + 2) + ".jpg"));
            }
        }
        bitmap.recycle();
        String cmdDescription = videoName;
        boolean success = runCommand(new String[]{"ffmpeg", "-framerate", "1/" + String.valueOf(secondsPerDiary), "-i", "diary_%d.jpg", "-c:v", vcodec, "-preset", preset, "-vf", fpsStr, "temp.mp4"}, cmdDescription, (num_bitmaps + 2) * secondsPerDiary);
        boolean success2 = runCommand(new String[]{"ffmpeg", "-f", "lavfi", "-i", "aevalsrc=0", "-i", "temp.mp4", "-shortest", "-c:v", "copy", "-strict", "-2", videoName}, cmdDescription, null);
        if (success && success2) {
            poiWriter.write("file '" + videoName + "'\n");
        }
        new File(tempDir, "temp.mp4").delete();
        for (int i = 0; i < num_bitmaps + 2; i++) {
            new File(tempDir, "diary_" + String.valueOf(i) + ".jpg").delete();
        }
        num_processed_materials++;

    }

    private void generatePicVideo(BufferedWriter poiWriter, POI poi) throws IOException {
        if (poi.picFiles.length == 0) return;
        String videoName = poi.title + "_picture.mp4";
        for (int i = 0; i < poi.picFiles.length; i++) {
            nb.setContentText(poi.title + "-" + getString(R.string.photo) + "-" + poi.picFiles[i].getName());
            publishProgress(i, poi.picFiles.length);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(poi.picFiles[i].getPath(), options);
            options.inSampleSize = (int) Math.max((float) options.outWidth / videoWidth, (float) options.outHeight / videoHeight);
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(poi.picFiles[i].getPath(), options);
            float ratio = Math.min((float) videoWidth / bitmap.getWidth(), (float) videoHeight / bitmap.getHeight());
            int destWidth = (int) (bitmap.getWidth() * ratio);
            int destHeight = (int) (bitmap.getHeight() * ratio);
            int destLeft = videoWidth / 2 - destWidth / 2;
            int destTop = videoHeight / 2 - destHeight / 2;
            int destRight = videoWidth / 2 + destWidth / 2;
            int destBottom = videoHeight / 2 + destHeight / 2;
            Rect sourceRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            Rect destRect = new Rect(destLeft, destTop, destRight, destBottom);
            Bitmap finalBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.RGB_565).copy(Bitmap.Config.RGB_565, true);
            finalBitmap.eraseColor(Color.BLACK);
            Canvas canvas = new Canvas(finalBitmap);
            canvas.drawBitmap(bitmap, sourceRect, destRect, null);
            String fileName = "image_" + String.valueOf(i + 1) + ".jpg";
            FileOutputStream fileOutputStream = new FileOutputStream(new File(tempDir, fileName));
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.flush();
            fileOutputStream.close();
            bitmap.recycle();
            finalBitmap.recycle();
            if (i == 0) {//for compensate ffmpeg bug - first frame may be skipped
                FileHelper.copyFile(new File(tempDir, fileName), new File(tempDir, "image_" + String.valueOf(0) + ".jpg"));
            }
            if (i == poi.picFiles.length - 1) { //for compensate ffmpeg bug - last frame may be skipped
                FileHelper.copyFile(new File(tempDir, fileName), new File(tempDir, "image_" + String.valueOf(i + 2) + ".jpg"));
            }
        }
        num_processed_materials++;
        String cmdDescription = videoName;
        boolean success = runCommand(new String[]{"ffmpeg", "-framerate", "1/" + String.valueOf(secondsPerPicture), "-i", "image_%d.jpg", "-c:v", vcodec, "-preset", preset, "-vf", fpsStr, "temp.mp4"}, cmdDescription, secondsPerPicture * (poi.picFiles.length + 2));
        boolean success2 = runCommand(new String[]{"ffmpeg", "-f", "lavfi", "-i", "aevalsrc=0", "-i", "temp.mp4", "-shortest", "-c:v", "copy", "-strict", "-2", videoName}, cmdDescription, null);
        if (success && success2) {
            poiWriter.write("file '" + videoName + "'\n");
        }
        new File(tempDir, "temp.mp4").delete();
        for (int i = 0; i < poi.picFiles.length + 2; i++) {
            new File(tempDir, "image_" + String.valueOf(i) + ".jpg").delete();
        }
        num_processed_materials++;

    }

    private void generateVideoVideo(BufferedWriter poiWriter, POI poi) throws IllegalArgumentException, NullPointerException, IOException {
        if (poi.videoFiles.length == 0) return;
        MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
        for (int i = 0; i < poi.videoFiles.length; i++) {
            String videoName = poi.title + "_video_" + String.valueOf(i) + ".mp4";
            metaRetriever.setDataSource(poi.videoFiles[i].getPath());
            int height = Integer.valueOf(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int width = Integer.valueOf(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int length = Integer.valueOf(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
            String cmdDescription = videoName;
            float ratio = Math.min((float) videoWidth / width, (float) videoHeight / height);
            int destWidth = (int) (width * ratio);
            int destHeight = (int) (height * ratio);
            String scale = "scale=" + String.valueOf(destWidth) + ":" + String.valueOf(destHeight);
            String pad = "pad=" + String.valueOf(videoWidth) + ":" + String.valueOf(videoHeight) + ":(ow-iw)/2:(oh-ih)/2";
            boolean success = runCommand(new String[]{"ffmpeg", "-i", poi.videoFiles[i].getAbsolutePath(), "-vf", scale + "," + pad + "," + fpsStr, "-c:v", vcodec, "-preset", preset, "-c:a", acodec, "-strict", "-2", videoName}, cmdDescription, length);
            if (success) {
                poiWriter.write("file '" + videoName + "'\n");
            }
            num_processed_materials++;
        }
    }

    private void generateAudioVideo(BufferedWriter poiWriter, POI poi) throws IOException, IllegalArgumentException, NullPointerException {
        if (poi.audioFiles.length == 0) return;
        MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
        Bitmap musicBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_music);
        Bitmap posterBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(posterBitmap);
        int left = (posterBitmap.getWidth() - musicBitmap.getWidth()) / 2;
        int top = (posterBitmap.getHeight() - musicBitmap.getHeight()) / 2;
        canvas.drawBitmap(musicBitmap, left, top, null);
        musicBitmap.recycle();
        FileOutputStream fileOutputStream = new FileOutputStream(new File(tempDir, "poster.jpg"));
        posterBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
        fileOutputStream.flush();
        fileOutputStream.close();
        for (int i = 0; i < poi.audioFiles.length; i++) {
            String videoName = poi.title + "_audio_" + String.valueOf(i) + ".mp4";
            metaRetriever.setDataSource(poi.audioFiles[i].getPath());
            int length = Integer.valueOf(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
            String cmdDescription = videoName;
            boolean success = runCommand(new String[]{"ffmpeg", "-loop", "1", "-i", "poster.jpg", "-i", poi.audioFiles[i].getAbsolutePath(), "-c:v", vcodec, "-preset", preset, "-vf", fpsStr, "-c:a", acodec, "-strict", "-2", "-shortest", videoName}, cmdDescription, length);
            if (success) {
                poiWriter.write("file '" + videoName + "'\n");
            }
            num_processed_materials++;
        }
        new File(tempDir, "poster.jpg").delete();
    }

    private String[] generateTrackVideos(Bitmap gmapBitmap, Point[] trackPoints) throws IOException {
        int frames_per_track = secondsPerTrack * fps;
        int num_tracks = trackPoints.length / frames_per_track;
        String[] trackVideoNames = new String[num_tracks];
        Bitmap runPoint = BitmapFactory.decodeResource(getResources(), R.drawable.runpoint);
        for (int i = 0; i < num_tracks; i++) {
            String[] fileNames = new String[frames_per_track];
            for (int j = 0; j < frames_per_track; j++) {
                String fileName = "track_" + String.valueOf(i) + "_" + String.valueOf(j) + ".jpg";
                nb.setContentText(fileName);
                publishProgress(j, frames_per_track);
                Point point = trackPoints[i * frames_per_track + j];
                Bitmap bitmap = gmapBitmap.copy(Bitmap.Config.RGB_565, true);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawBitmap(runPoint, point.x - runPoint.getWidth() / 2, point.y - runPoint.getHeight() / 2, null);
                fileNames[j] = fileName;
                FileOutputStream fos = new FileOutputStream(new File(tempDir, fileName));
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                fos.flush();
                fos.close();
                bitmap.recycle();
                if (i == num_tracks - 1 && j == frames_per_track - 1) {
                    for (int k = 0; k < 1 * fps; k++) {
                        FileHelper.copyFile(new File(tempDir, fileName), new File(tempDir, "track_" + String.valueOf(i) + "_" + String.valueOf(j + k + 1) + ".jpg"));
                    }
                }
                Log.i("trip", fileName);
            }
            num_processed_materials++;
            String trackVideoName = "track_" + String.valueOf(i) + ".mp4";
            String trackRepresentation = "track_" + String.valueOf(i) + "_%d.jpg";
            boolean success;
            if (i == num_tracks - 1) { //fade out
                String fadeout = "fade=out:" + String.valueOf(frames_per_track) + ":" + String.valueOf(1 * fps - 1);
                success = runCommand(new String[]{"ffmpeg", "-framerate", String.valueOf(fps), "-i", trackRepresentation, "-c:v", vcodec, "-preset", preset, "-vf", fadeout + "," + fpsStr, "temp.mp4"}, trackVideoName, secondsPerTitle + 1);
            } else {
                success = runCommand(new String[]{"ffmpeg", "-framerate", String.valueOf(fps), "-i", trackRepresentation, "-c:v", vcodec, "-preset", preset, "-vf", fpsStr, "temp.mp4"}, trackVideoName, secondsPerTitle);
            }
            boolean success2 = runCommand(new String[]{"ffmpeg", "-f", "lavfi", "-i", "aevalsrc=0", "-i", "temp.mp4", "-shortest", "-c:v", "copy", "-strict", "-2", trackVideoName}, trackVideoName, null);
            trackVideoNames[i] = success && success2 ? trackVideoName : null;
            new File(tempDir, "input.txt").delete();
            new File(tempDir, "temp.mp4").delete();
            for (int j = 0; j < fileNames.length; j++) {
                new File(tempDir, fileNames[j]).delete();
            }
            if (i == num_tracks - 1) {
                for (int j = 0; j < 1 * fps; j++) {
                    new File(tempDir, "track_" + String.valueOf(i) + "_" + String.valueOf(frames_per_track + j) + ".jpg").delete();
                }
            }
            num_processed_materials++;
        }
        gmapBitmap.recycle();
        return trackVideoNames;
    }

    private boolean runCommand(String[] cmds, String description, final Integer predicetedTimeLength) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmds.length; i++) {
            sb.append(cmds[i]).append(" ");
        }
        String cmd = sb.toString();
        Log.i("trip", "cmd:" + cmd);
        final String des = description == null ? cmd : description;
        nb.setContentText(des);
        nm.notify(1, nb.build());
        try {
            if (cmds[0].equals("ffmpeg")) {
                cmds[0] = ffmpegFile.getPath();
            }
            ProcessBuilder processBuilder = new ProcessBuilder(cmds);
            processBuilder.directory(tempDir);
            processBuilder.redirectErrorStream(true);
            final Process process = processBuilder.start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream inputStream = process.getInputStream();
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                        String s;
                        while ((s = bufferedReader.readLine()) != null) {
                            if (!s.contains("buffer underflow") && !s.contains("packet too large"))
                                Log.i("trip", s);
                            if (predicetedTimeLength != null) {
                                String[] toks = s.split(" ");
                                for (int i = 0; i < toks.length; i++) {
                                    if (toks[i].contains("time=")) {
                                        String timeStr = toks[i].substring(toks[i].indexOf("time=") + 5);
                                        String[] timeToks = timeStr.split(":");
                                        nb.setContentText(des + " " + timeStr);
                                        float timeLength = Integer.valueOf(timeToks[0]) * 60 * 60 + Integer.valueOf(timeToks[1]) * 60 + Float.valueOf(timeToks[2]);
                                        publishProgress(timeLength, predicetedTimeLength);
                                        break;
                                    }
                                }
                            }
                        }
                        bufferedReader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
            int exitVal = process.waitFor();
            Log.i("trip", "exit:" + String.valueOf(exitVal));
            return exitVal == 0;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean concatFiles(File source, File dest) {
        Log.i("trip", "concat:" + source.getPath() + ">>" + dest.getPath());
        nb.setContentText("concat" + source.getPath() + ">>" + dest.getPath());
        nm.notify(1, nb.build());
        try {
            FileInputStream fileInputStream = new FileInputStream(source);
            FileOutputStream fileOutputStream = new FileOutputStream(dest, true);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fileInputStream.read(buffer)) > 0) {
                fileOutputStream.write(buffer, 0, len);
            }
            fileOutputStream.flush();
            fileInputStream.close();
            fileOutputStream.close();
            return true;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void copyFFmpeg() {
        try {
            InputStream inputStream = getAssets().open("ffmpeg");
            FileOutputStream fileOutputStream = new FileOutputStream(ffmpegFile);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = inputStream.read(buffer)) > 0) {
                fileOutputStream.write(buffer, 0, len);
            }
            fileOutputStream.flush();
            fileOutputStream.close();
            inputStream.close();
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(new String[]{"chmod", "777", ffmpegFile.getPath()});
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();
                process.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int getNum_total_materials(POI[] pois) {
        int num = 0;
        num++;//trip title
        num += 2 * (pois.length + 1); //num tracks
        for (int i = 0; i < pois.length; i++) {
            POI poi = pois[i];
            num++;//title
            if (poi.diary.length() > 0) {
                num++;
            }
            if (poi.picFiles.length > 0) {
                num += 2;
            }
            num += poi.videoFiles.length;
            num += poi.audioFiles.length;
        }
        return num;
    }

    public void publishProgress(float materialProgress, float materialTotal) {
        if (nm == null || nb == null) return;
        int progress = (int) (2000 * (num_processed_materials + materialProgress / materialTotal) / num_total_materials);
        nb.setProgress(2000, progress, false);
        nm.notify(1, nb.build());
    }

    @Override
    public void onDestroy() {
        if (resultVideoPath != null) {
            EasyTracker.getInstance(this).send(MapBuilder.createEvent("Trip", "generate_video_sucess", tripName, null).build());
            nb.setContentTitle(getString(R.string.finish));
            nb.setContentText(resultVideoPath);
            nb.setOngoing(false);
            nb.setProgress(0, 0, false);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(resultVideoPath), "video/*");
            nb.setContentIntent(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
            Intent intent2 = new Intent(Intent.ACTION_SEND);
            intent2.setType("video/*");
            intent2.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(resultVideoPath)));
            nb.addAction(android.R.drawable.ic_menu_share, getString(R.string.share), PendingIntent.getActivity(this, 0, intent2, PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(1, nb.build());
        } else {
            EasyTracker.getInstance(this).send(MapBuilder.createEvent("Trip", "generate_video_fail", tripName, null).build());
            nm.cancel(1);
        }
        super.onDestroy();
    }
}
