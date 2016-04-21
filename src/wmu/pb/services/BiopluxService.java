package wmu.pb.services;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.bitalino.comm.BITalinoFrame;
import com.bitalino.deviceandroid.BitalinoAndroidDevice;
import com.bitalino.util.SensorDataConverter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Chronometer;
import android.widget.TextView;
import wmu.pb.itired.R;
import wmu.pb.model.DeviceConfiguration;
import wmu.pb.model.io.DataManager;
import wmu.pb.ui.NewRecordingActivity;
import wmu.pb.ui.SettingsActivity;

/**
 * Creates a connection with a bioplux device and receives frames sent from
 * device
 * 
 * @author Carlos Marten Modified by Caleb Ng 2015
 * @author Praveen Bhat 2016 Implemented worker thread model where one thread
 *         gets data from BITalino where other threads process the data and sent
 *         to UI for display.
 * 
 * 
 */
public class BiopluxService extends Service implements SensorEventListener {

	private static final String TAG = BiopluxService.class.getName();
	private static final String TAG_EMG = "EMG Signal";
	private static final String TAG_MF = "Median Frequency";
	private static final String TAG_VB = "Vibration";
	private static final String TAG_F = "Fatigue Detected";
	private static final String TAG_INC = "Vibration Increase Detected";
	// messages 'what' fields for the communication with the client
	public static final int MSG_REGISTER_CLIENT = 1;
	public static final int MSG_DATA = 2;
	public static final int MSG_RECORDING_DURATION = 3;
	public static final int MSG_SAVED = 4;
	public static final int MSG_CONNECTION_ERROR = 5;
	public static final int MSG_END_RECORDING_FLAG = 6;

	public static final String KEY_X_VALUE = "xValue";
	public static final String KEY_FRAME_DATA = "frame";

	// Codes for the activity to display the correct error message
	public static final int CODE_ERROR_WRITING_TEXT_FILE = 6;
	public static final int CODE_ERROR_SAVING_RECORDING = 7;

	public static final int FRAMES_TO_PROCESS = 100;
	public static final int FRAMES_FOR_FFT = 128;

	// Get 50 frames every 50 miliseconds
	private int numberOfFrames;

	// ---------------------changes start----------------------

	// simple count
	public int count = 0;

	Complex[] x = new Complex[128];
	double abs[] = new double[65];
	double power[] = new double[65];

	// double median_freq[] = new double[5000];

	public double median_newF;

	public double median_highF = 1;
	public double median_lowF = 100;

	// ---------------------Changes end--------------------

	// This is initially 50, and lowering this gets rid of the 1000Hz lag...
	public int TIMER_TIME = 100;

	// Used to synchronize timer and main thread
	private static final Object weAreWritingDataToFileLock = new Object();
	private boolean areWeWritingDataToFile;
	// Used to keep activity running while device screen is turned off
	private PowerManager powerManager;
	private WakeLock wakeLock = null;

	private DeviceConfiguration configuration;
	private BitalinoAndroidDevice connection;

	private Timer timer = null;
	private DataManager dataManager;
	private double samplingFrames;
	private int samplingCounter = 0;
	private double timeCounter = 0.0;
	private double xValue = 0;
	private boolean drawInBackground = true;
	private boolean killServiceError = false;
	private boolean clientActive = false;
	Notification serviceNotification = null;
	private SharedPreferences sharedPref;
	// private String patientHealthNumber = "1234567890";
	private String patientFName = "DEFAULT";
	private String patientLName = "DEFAULT";

	// Variables for handling the chronometer
	private Chronometer chronometer;
	private String duration = null;
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd.HH.mm.ss");
	private String currentDateandTime;

	// Target we publish for clients to send messages to IncomingHandler
	private final Messenger mMessenger = new Messenger(new IncomingHandler());

	// Messenger with interface for sending messages from the service
	private Messenger client = null;

	/*-----------------------Shake detect variables start ----------------*/
	private SensorManager mSensorManager;
	private Sensor mAccel;
	float ax, ay, az;
	float last_x;
	float last_y;
	float last_z;
	long lastUpdate;
	long counter = 0;
	long cur = 0;
	public static boolean shakeDetected = false;
	private static final int SHAKE_THRESHOLD = 75;
	public ConcurrentLinkedQueue<Float> shakeList = new ConcurrentLinkedQueue<Float>();

	/*-----------------------Shake detect variables end ----------------*/

	/*-------------Changes Start -----------------------*/
	public ConcurrentLinkedQueue<BITalinoFrame> frameList = new ConcurrentLinkedQueue<BITalinoFrame>();
	public ConcurrentLinkedQueue<Double> medfreql = new ConcurrentLinkedQueue<Double>();
	public ConcurrentLinkedQueue<Double> SMAlist = new ConcurrentLinkedQueue<Double>();
	public double[] emgdata = new double[FRAMES_FOR_FFT];

	/**
	 * Producer thread that is responsible for collecting EMG frames
	 * 
	 * @author Praveen
	 */
	class ProdThread implements Runnable {

		@Override
		public void run() {

			try {
				frameList.addAll(Arrays.asList(getFrames(numberOfFrames)));
			} catch (NullPointerException e) {
				e.printStackTrace();
			}

		}

	}

	/**
	 * Consumer thread which runs in background to process the captured EMG
	 * frames
	 * 
	 * @author Praveen
	 */
	class ConsThread extends AsyncTask<String, String, String> implements Runnable {
		BITalinoFrame[] frames = new BITalinoFrame[FRAMES_TO_PROCESS];
		boolean processFrameFlag = false;

		@Override
		public void run() {
			// TODO Auto-generated method stub
			this.doInBackground(null);

		}

		@SuppressWarnings("null")
		@Override
		protected String doInBackground(String... params) {

			// remove 64 elements from the ConcurrentListQueue frameList
			int n = 0;
			long starttime1 = System.nanoTime();

			if (frameList.isEmpty() != true) {
				processFrameFlag = true;
				while (n < FRAMES_TO_PROCESS) {
					frames[n++] = frameList.remove();
				}

			}

			// pad remaining samples with zero

			// Frames are sent for further processing.
			if (processFrameFlag) {
				processFrames(frames);
				processFrameFlag = false;
			}

			return "Completed";
		}

	}

	/*-------------Changes End ------------------------*/

	/**
	 * Handler of incoming messages from clients.
	 */
	@SuppressLint("HandlerLeak")
	class IncomingHandler extends Handler {
		ExecutorService exService = Executors.newFixedThreadPool(1);
		public ProdThread produceT = new ProdThread();

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				// register client
				client = msg.replyTo;
				clientActive = true;
				// removes notification
				stopForeground(true);

				timer = new Timer();
				timer.scheduleAtFixedRate(new TimerTask() {

					public void run() {
						// Execute the thread which starts collecting frames
						// from
						// BITalino device
						exService.execute(produceT);

					}
				}, 0, TIMER_TIME);

				break;
			case MSG_RECORDING_DURATION:
				dataManager.setDuration(msg.getData().getString(NewRecordingActivity.KEY_DURATION));
				break;
			case MSG_END_RECORDING_FLAG:
				stopChronometer();
				dataManager.setDuration(duration);
				break;
			default:
				super.handleMessage(msg);
			}
		}
	}

	/**
	 * Initializes the wake lock and the frames array
	 */
	@Override
	public void onCreate() {
		super.onCreate();

		sharedPref = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_MULTI_PROCESS);
		drawInBackground = sharedPref.getBoolean(SettingsActivity.KEY_DRAW_IN_BACKGROUND, true);
		powerManager = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakeLock");
		// -------------------Write log data to file start -----------------
		// String fileName = "logcat_"+System.currentTimeMillis()+".txt";

		// ------------------Write log data to file end --------------------

		if ((wakeLock != null) && (wakeLock.isHeld() == false)) {
			wakeLock.acquire();
		}

		chronometer = new Chronometer(this);
	}

	/**
	 * Returns the communication channel to the service or null if clients
	 * cannot bind to the service
	 */
	@Override
	public IBinder onBind(Intent intent) {
		Log.i(TAG, "onBind");

		return mMessenger.getBinder();

	}

	/**
	 * Changes the service to be run in the foreground and shows the
	 * notification
	 */
	@Override
	public boolean onUnbind(Intent intent) {
		Log.i(TAG, "onUNBind");
		clientActive = false;
		startForeground(R.string.service_id, serviceNotification);
		return true;
	}

	/**
	 * Gets information from the activity extracted from the intent and connects
	 * to bioplux device. Returns a do not re-create flag if killed by system
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.e(TAG, "Iniciamos conexion BITALINO");

		String recordingName = intent.getStringExtra(NewRecordingActivity.KEY_RECORDING_NAME).toString();
		configuration = (DeviceConfiguration) intent.getSerializableExtra(NewRecordingActivity.KEY_CONFIGURATION);
		patientFName = intent.getStringExtra("patientFName").toString();
		patientLName = intent.getStringExtra("patientLName").toString();

		final ExecutorService exService1 = Executors.newFixedThreadPool(1);
		final ConsThread consumeT = new ConsThread();

		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

		// samplingFrames = (double) configuration.getVisualizationFrequency()
		// / configuration.getSamplingFrequency();

		numberOfFrames = (int) (TIMER_TIME * configuration.getVisualizationFrequency() / 1000);// We
																								// round
																								// upthe
																								// number
																								// of
																								// frames

		Log.i(TAG, "numberOfFrames " + numberOfFrames + " receptionFrequency() "
				+ configuration.getVisualizationFrequency());
		Log.i(TAG, "samplingFrames " + samplingFrames);
		// Revisar frames = new Device.Frame[numberOfFrames];
		// Revisar for (mes.length; i++){
		// Revisar frames[i] = new Frame();
		// Revisar }

		try {
			if (connectToBiopluxDevice()) {
				// Once phone is connected to the Bitalino, start the
				// chronometer for accurate timing
				// @author Caleb Ng (2015)
				startChronometer();
				dataManager = new DataManager(this, recordingName + currentDateandTime, configuration, patientFName,
						patientLName);
				createNotification();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// exService.execute(produceT);
		timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {

			public void run() {
				// Start consumer thread which starts processing frames in bunch
				// collected by producer thread.
				exService1.execute(consumeT);
			}
		}, 5000, 200);

		mSensorManager.registerListener(this, mAccel, SensorManager.SENSOR_DELAY_NORMAL);

		return START_NOT_STICKY; // do not re-create service if system kills it
	}

	/**
	 * This function does the following. 1. EMG frame is scaled to milli volts
	 * using scaleEMG function. 2. Since each second we get only 100 samples, we
	 * pad remaining 28 samples with zeros for FFT calculation 3. Calculate
	 * median frequency 4. Calculate average median frequency using moving
	 * average algorithm 5. Displaying fatigue notification if median frequency
	 * is decreasing and vibration is consecutively increasing. to the activity
	 * 
	 * @author Praveen
	 */
	private void processFrames(BITalinoFrame[] frames) {
		// emgdata count variable
		int emgcount = 0;

		for (BITalinoFrame frame : frames) {

			emgdata[emgcount] = (SensorDataConverter.scaleEMG(0, frame.getAnalog(0)));
			preSendFrameActiivty(emgdata[emgcount]);
			emgcount++;

		}

		while (emgcount < 128) {
			emgdata[emgcount] = 0.0;
			emgcount++;
		}
		// }

		// process the copied data in array for fft, etc.
		for (int i = 0; i < emgdata.length; i++) {
			x[i] = new Complex(emgdata[i], 0);
		}

		// FFT of original data
		Complex[] y = fft(x);

		// calculate abs value and then get the power spectrum
		for (int j = 0; j < y.length / 2 + 1; j++) {

			abs[j] = y[j].abs() / 100;

			// abs[j] = abs[j]/1000;
			// System.out.println("abs Value: "+abs[j]);

			power[j] = abs[j] * abs[j];
			// System.out.println("power Value: "+power[j]);

		}

		// Compute area of spectrum

		double df = 0.78125;
		// double df = 1.0;

		double area = 0;
		int n1 = power.length;

		for (int m = 0; m < n1 - 1; m++) {
			area = area + df * ((power[m] + power[m + 1]) / 2);
			// System.out.println("Area : "+area);
		}
		// System.out.println(area);
		double ar = 0;
		int m;
		for (m = 0; m < n1 - 1; m++) {
			ar = ar + df * ((power[m] + power[m + 1]) / 2);

			// System.out.println("Ar : "+ar);
			if (ar >= area / 2) {
				break;
			}
		}

		median_newF = m * df;
		// System.out.println("Median Frequency: "+median_newF);
		Log.d(TAG_MF, String.valueOf(median_newF));
		// ------------------------------------

		double sma = 0.0;
		double totalMedFreql = 0.0;

		// Add median frequency to the median frequency list
		medfreql.add(median_newF);

		// Check if median frequency list contains 50 samples
		if (medfreql.size() == 30) {

			// Calculate the average of the first 30 samples and store it in
			// SMAList

			Double[] medfreqArry = new Double[medfreql.size()];
			medfreql.toArray(medfreqArry);
			for (int i = 0; i < medfreqArry.length; i++) {
				totalMedFreql = totalMedFreql + medfreqArry[i];
			}

			sma = totalMedFreql / 30;
			SMAlist.add(sma);

		} else if (medfreql.size() > 30) {
			Double[] medfreqArry = new Double[medfreql.size()];
			medfreql.toArray(medfreqArry);
			// static ConcurrentLinkedQueue<Double> SMAlist=new
			// ConcurrentLinkedQueue<Double>();
			sma = (SMAlist.element() + ((medfreql.element() - medfreqArry[medfreqArry.length - 1]) / 50));
			SMAlist.add(sma);
			medfreql.remove();

			if (SMAlist.size() >= 10) {

				if (isDecreasing(SMAlist) && shakeDetected) {
					NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this)
							.setSmallIcon(R.drawable.notification).setContentTitle("Muscle Fatigue Alert!")
							.setContentText("Your muscle reached fatigue. Please Stop exersising");

					Intent resultIntent = new Intent(this, NewRecordingActivity.class);
					Log.d(TAG_F, "Fatigue Reached");
					// Because clicking the notification opens a new ("special")
					// activity, there's
					// no need to create an artificial back stack.
					PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent,
							PendingIntent.FLAG_UPDATE_CURRENT);

					mBuilder.setContentIntent(resultPendingIntent);

					// Sets an ID for the notification
					int mNotificationId = 001;
					// Gets an instance of the NotificationManager service
					NotificationManager mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
					// Builds the notification and issues it.
					mNotifyMgr.notify(mNotificationId, mBuilder.build());

				}
			}

		}

	}

	/**
	 * Function to check wheather median frequency is decreasing.
	 * 
	 * @param median
	 *            frequency list
	 * @author Praveen
	 */

	public boolean isDecreasing(ConcurrentLinkedQueue<Double> list) {
		Double[] smaArray = new Double[list.size()];
		list.toArray(smaArray);

		double midvalue;
		if (list.size() % 2 != 0) {

			midvalue = smaArray[(list.size() / 2)];

		} else {
			midvalue = (smaArray[(list.size() / 2) - 1] + smaArray[list.size() / 2]) / 2;

		}

		if ((list.element() <= midvalue) && (midvalue < smaArray[smaArray.length - 1])) {
			return true;
		} else {
			return false;
		}

	}

	private void preSendFrameActiivty(double emgtemp) {
		timeCounter++;
		xValue = timeCounter / configuration.getVisualizationFrequency() * 1000;
		// gets default share preferences with multi-process flag
		Log.d(TAG_EMG, String.valueOf(emgtemp));
		if (clientActive || !clientActive && drawInBackground)
			sendFrameToActivity(emgtemp);

	}

	/**
	 * Get frames from the bioplux device
	 */
	private BITalinoFrame[] getFrames(int numberOfFrames) {
		// Log.e(TAG, "BITALINO Read frames");

		BITalinoFrame[] frames = connection.read(numberOfFrames);
		Log.e(TAG, "BITALINO Readed");
		// for (BITalinoFrame frame : frames)
		// Log.v(TAG, frame.toString());
		return frames;
	}

	/**
	 * Connects to a bioplux device and begins to acquire frames Returns true
	 * connection has established. False if an exception was caught
	 * 
	 * @throws IOException
	 */
	private boolean connectToBiopluxDevice() throws IOException {

		Log.e(TAG, "connectToBiopluxDevice");
		// BIOPLUX INITIALIZATION
		connection = new BitalinoAndroidDevice(configuration.getMacAddress());
		ArrayList<Integer> activeChannels = configuration.getActiveChannels();
		int[] activeChannelsArray = convertToBitalinoChannelsArray(activeChannels);

		if (connection.connect(configuration.getSamplingFrequency(), activeChannelsArray) != 0) {
			Log.e(TAG, "Bitalino connection error");

			killServiceError = true;
			stopSelf();

			return false;
		}
		if (connection.start() != 0) {
			Log.e(TAG, "Bitalino starting error");
			killServiceError = true;
			stopSelf();
			return false;
		}

		Log.e(TAG, "configuration.getNumberOfBits() " + configuration.getNumberOfBits());

		return true;
	}

	private int[] convertToBitalinoChannelsArray(ArrayList<Integer> activeChannels) {
		int[] activeChannelsArray = new int[activeChannels.size()];
		Iterator<Integer> iterator = activeChannels.iterator();
		Log.e(TAG, "BITALINO ActiveChannels ");

		for (int i = 0; i < activeChannelsArray.length; i++) {
			activeChannelsArray[i] = iterator.next().intValue() - 1;
			Log.e(TAG, "BITALINO ActiveChannels C" + activeChannelsArray[i]);
		}

		return activeChannelsArray;
	}

	/**
	 * Display notification on reaching muscle fatigue.
	 * 
	 * @author Praveen
	 */

	private void createNotification() {

		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this).setSmallIcon(R.drawable.notification)
				.setContentTitle("Alert").setContentText("Your muscle has reached fatigueness. Please stop!");

		// CREATE THE INTENT CALLED WHEN NOTIFICATION IS PRESSED
		Intent newRecordingIntent = new Intent(this, NewRecordingActivity.class);

		// PENDING INTENT
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, newRecordingIntent,
				PendingIntent.FLAG_CANCEL_CURRENT);
		mBuilder.setContentIntent(pendingIntent);

		// CREATES THE NOTIFICATION AND START SERVICE AS FOREGROUND
		serviceNotification = mBuilder.build();
	}

	/**
	 * Sends frame to activity via message
	 * 
	 * @param frame
	 *            acquired from the bioplux device
	 */
	// comentario �intentar optimizar el env�o de datos?
	// private void sendFrameToActivity(BITalinoFrame frame) {
	private void sendFrameToActivity(double emgframe) {
		Bundle b = new Bundle();
		b.putDouble(KEY_X_VALUE, xValue);
		b.putDouble(KEY_FRAME_DATA, emgframe);

		Message message = Message.obtain(null, MSG_DATA);
		message.setData(b);
		try {
			client.send(message);
		} catch (RemoteException e) {
			clientActive = false;
			Log.i(TAG, "client is dead");
		}
	}

	/**
	 * Notifies the client that the recording frames were stored properly
	 */
	private void sendSavedNotification() {
		Message message = Message.obtain(null, MSG_SAVED);
		try {
			client.send(message);
		} catch (RemoteException e) {
			Log.e(TAG, "client is dead. Service is being stopped", e);
			killServiceError = true;
			stopSelf();
		}
	}

	/**
	 * Sends the an error code to the client with the corresponding error that
	 * it has encountered
	 */
	private void sendErrorToActivity(int errorCode) {
		try {
			client.send(Message.obtain(null, MSG_CONNECTION_ERROR, errorCode, 0));
		} catch (RemoteException e) {
			Log.e(TAG, "Exception sending error message to activity. Service is stopping", e);
		}
	}

	@SuppressLint("NewApi")
	@Override
	public void onTaskRemoved(Intent rootIntent) {
		killServiceError = true;
		stopSelf();
		super.onTaskRemoved(rootIntent);
	}

	/**
	 * Stops the service properly whilst being destroyed
	 */
	private void stopService() {
		if (timer != null)
			timer.cancel();

		// connection.stop();
		connection.stop();

	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (!killServiceError) {

			stopService();
			new Thread() {
				@Override
				public void run() {
					boolean errorSavingRecording = false;
					if (!dataManager.saveAndCompressFile(client)) {
						errorSavingRecording = true;
						sendErrorToActivity(CODE_ERROR_SAVING_RECORDING);
					}
					if (!errorSavingRecording)
						sendSavedNotification();
					wakeLock.release();
				}
			}.start();
		}
		Log.i(TAG, "service destroyed");
	}

	/**
	 * Added Chronometer functionality from the NewRecordingActivity Starts
	 * Android' chronometer widget to display the recordings duration
	 */
	private void startChronometer() {
		currentDateandTime = sdf.format(new Date());
		// chronometer.setBase(SystemClock.elapsedRealtime());
		chronometer.setBase(SystemClock.currentThreadTimeMillis());
		chronometer.start();
	}

	/**
	 * Stops the chronometer and calculates the duration of the recording
	 */
	private void stopChronometer() {
		chronometer.stop();
		long elapsedMiliseconds = SystemClock.elapsedRealtime() - chronometer.getBase();
		/*
		 * duration = String.format("%02d:%02d:%02d", (int) ((elapsedMiliseconds
		 * / (1000 * 60 * 60)) % 24), // hours (int) ((elapsedMiliseconds /
		 * (1000 * 60)) % 60), // minutes (int) (elapsedMiliseconds / 1000) %
		 * 60); // seconds
		 */
		duration = String.valueOf((int) (elapsedMiliseconds / 1000) % 60);
		// System.out.println("##### BiopluxService ##### - Duration of
		// recording is: " + this.duration);
	}

	// compute the FFT of x[], assuming its length is a power of 2
	public static Complex[] fft(Complex[] x) {
		int N = x.length;

		// base case
		if (N == 1)
			return new Complex[] { x[0] };

		// radix 2 Cooley-Tukey FFT
		// if (N % 2 != 0) { throw new RuntimeException("N is not a power of
		// 2"); }

		// fft of even terms
		Complex[] even = new Complex[N / 2];
		for (int k = 0; k < N / 2; k++) {
			even[k] = x[2 * k];
		}
		Complex[] q = fft(even);

		// fft of odd terms
		Complex[] odd = even; // reuse the array
		for (int k = 0; k < N / 2; k++) {
			odd[k] = x[2 * k + 1];
		}
		Complex[] r = fft(odd);

		// combine
		Complex[] y = new Complex[N];
		for (int k = 0; k < N / 2; k++) {
			double kth = -2 * k * Math.PI / N;
			Complex wk = new Complex(Math.cos(kth), Math.sin(kth));
			y[k] = q[k].plus(wk.times(r[k]));
			y[k + N / 2] = q[k].minus(wk.times(r[k]));
		}
		return y;
	}

	/**
	 * Monitor the vibration speed from mobile accelerometer and looks for
	 * consecutive vibration above threshold
	 * 
	 * @author Praveen
	 */
	@Override
	public void onSensorChanged(SensorEvent event) {
		// TODO Auto-generated method stub
		long curTime = System.currentTimeMillis();

		if ((curTime - lastUpdate) > 200) {
			long diffTime = (curTime - lastUpdate);
			lastUpdate = curTime;

			ax = event.values[0];
			ay = event.values[1];
			az = event.values[2];

			float speed = Math.abs(ax + ay + az - last_x - last_y - last_z) / diffTime * 100000;
			if (counter != Long.MAX_VALUE) {
				counter++;
			} else {
				counter = 0;
			}
			if (speed > SHAKE_THRESHOLD) {

				// Toast.makeText(this, "shake detected w/ speed: " + speed,
				// Toast.LENGTH_SHORT).show();

				// add to the shakelist
				if (shakeList.size() == 0) {
					shakeList.add(speed);
					cur = counter;
				} else if (counter == cur + 1) {
					shakeList.add(speed);
					cur = counter;
					if (shakeList.size() == 5) {
						shakeDetected = true;
						Log.d(TAG_INC, String.valueOf(speed));
						mSensorManager.unregisterListener(this);

					}

				} else {
					shakeList.removeAll(shakeList);
					counter = 0;
				}

			}
			last_x = ax;
			last_y = ay;
			last_z = az;
			Log.d(TAG_VB, String.valueOf(speed));
			// System.out.println("Sensor Speed: "+speed);
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// TODO Auto-generated method stub

	}

}