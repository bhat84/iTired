package wmu.pb.ui;

import java.sql.SQLException;
import java.text.DateFormat;
import java.util.Date;

import wmu.pb.itired.R;
import wmu.pb.model.Constants;
import wmu.pb.model.DeviceConfiguration;
import wmu.pb.model.DeviceRecording;
import wmu.pb.model.io.DataManager;
import wmu.pb.model.io.DatabaseHelper;
import wmu.pb.services.BiopluxService;
import wmu.pb.ui.NewRecordingActivity.IncomingHandler;

import com.j256.ormlite.android.apptools.OrmLiteBaseActivity;
import com.j256.ormlite.dao.Dao;
import com.jjoe64.graphview.GraphView.GraphViewData;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.ActivityManager.RunningServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

public class ConnectionStatusActivity extends OrmLiteBaseActivity<DatabaseHelper> implements android.widget.PopupMenu.OnMenuItemClickListener, OnSharedPreferenceChangeListener {
private static final String TAG = NewRecordingActivity.class.getName();
  
  // Keys used for communication with activity
  public static final String KEY_DURATION = "duration";
  public static final String KEY_RECORDING_NAME = "recordingName";
  public static final String KEY_CONFIGURATION = "configSelected";
  
  // key for recovery. Used when android kills activity
  public static final String KEY_CHRONOMETER_BASE = "chronometerBase";
  
  // Configuration parameters
  private String deviceName;
  private Boolean deviceConnected = false;

  // Android's widgets
  private  TextView uiRecordingName, uiConfigurationName, uiNumberOfBits,
      uiReceptionFrequency, uiSamplingFrequency, uiActiveChannels,
      uiMacAddress, uiConnectionStatus, uiBatteryLevel;
  private Button uiMainbutton;
  private Chronometer chronometer;

  // DIALOGS
  private  AlertDialog connectionErrorDialog;
  private  ProgressDialog savingDialog;
  
  // AUX VARIABLES
  private Context classContext = this;
  private Bundle extras;
  private LayoutInflater inflater;
  
  private DeviceConfiguration recordingConfiguration;
  private DeviceRecording recording;
  
  private Graph[] graphs;
  private int[] displayChannelPosition;
  private int currentZoomValue = 0;
  private String duration = null; 
  private SharedPreferences sharedPref = null;
  
  private boolean isServiceBounded = false;
  private boolean recordingOverride = false;
  private boolean savingDialogMessageChanged = false;
  private boolean closeRecordingActivity = false;
  private boolean drawState = true; //true -> Enable | false -> Disable
  private boolean goToEnd = true;
  
  // ERROR VARIABLES
  private int bpErrorCode   = 0;
  private boolean serviceError = false;
  private boolean connectionError = false;
  public static boolean btConnectError = false;
  
  // MESSENGERS USED TO COMMUNICATE ACTIVITY AND SERVICE
  private Messenger serviceMessenger = null;
  private final Messenger activityMessenger = new Messenger(new IncomingHandler());

    
  /**
   * Handler that receives messages from the service. It receives frames data,
   * error messages and a saved message if service stops correctly
   * 
   */

  @SuppressLint("HandlerLeak")
  class IncomingHandler extends Handler {
    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
      case BiopluxService.MSG_DATA: {
        /*appendDataToGraphs(
            msg.getData().getDouble(BiopluxService.KEY_X_VALUE),
            msg.getData().getDoubleArray(BiopluxService.KEY_FRAME_DATA));*/
        uiConnectionStatus.setText("CONNECTED");
        uiBatteryLevel.setText(BiopluxService.KEY_FRAME_DATA);
        System.out.println(msg.getData().getDoubleArray(BiopluxService.KEY_FRAME_DATA));
        break;
      }
      case BiopluxService.MSG_CONNECTION_ERROR: {
        serviceError = true;
        savingDialog.dismiss();
        displayConnectionErrorDialog(msg.arg1);
        uiConnectionStatus.setText("DISCONNECTED");
        break;
      }
      case DataManager.MSG_PERCENTAGE: {
        if (!savingDialogMessageChanged && msg.arg2 == DataManager.STATE_COMPRESSING_FILE) {
          savingDialog.setMessage(getString(R.string.nr_saving_dialog_compressing_message));
          savingDialogMessageChanged = true;
        }
        savingDialog.setProgress(msg.arg1);

        break;
      }
      case BiopluxService.MSG_SAVED: {
        super.handleMessage(msg);
      }
      }
    }
  }

  /**
   * Bind connection used to bind and unbind with service
   * onServiceConnected() called when the connection with the service has been established,
   * giving us the object we can use to interact with the service. We are
   * communicating with the service using a Messenger, so here we get a
   * client-side representation of that from the raw IBinder object.
   */
  private ServiceConnection bindConnection = new ServiceConnection() {
    public void onServiceConnected(ComponentName className, IBinder service) {
      serviceMessenger = new Messenger(service);
      isServiceBounded = true;
      Message msg = Message.obtain(null, BiopluxService.MSG_REGISTER_CLIENT);
      msg.replyTo = activityMessenger;
      try {
        serviceMessenger.send(msg);
      } catch (RemoteException e) {
        Log.e(TAG, "service conection failed", e);
        displayConnectionErrorDialog(10); // 10 -> fatal error
      }
    }
    
    /**
     *  This is called when the connection with the service has been
     *  unexpectedly disconnected -- that is, its process crashed.
     */
    public void onServiceDisconnected(ComponentName className) {
      serviceMessenger = null;
      isServiceBounded = false;
      Log.i(TAG, "service disconnected");
    }
  };

  /**
   * Appends x and y values received from service to all active graphs. The
   * graph always moves to the last value added
   */
  /*void appendDataToGraphs(double xValue, double[] data) {
    if(!serviceError){
      for (int i = 0; i < graphs.length; i++) {
        graphs[i].getSerie().appendData(
            new GraphViewData(xValue,
                data[displayChannelPosition[i]]), goToEnd, maxDataCount);
        System.out.println(xValue + " : " + data[displayChannelPosition[i]]);
      }
    }
  }*/
  

    

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.connection_status_activity);
    Log.i(TAG, "onCreate()");

    // GETTING EXTRA INFO FROM INTENT
    extras = getIntent().getExtras();
    recordingConfiguration = (DeviceConfiguration) extras.getSerializable(ConfigurationsActivity.KEY_CONFIGURATION);
    //recordingConfiguration = (DeviceConfiguration) ConfigurationsActivity.myconfig;

  
    deviceName = recordingConfiguration.getName();
    deviceConnected = true;
    
    inflater = this.getLayoutInflater();
    sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    
    
    uiConnectionStatus = (TextView) findViewById(R.id.connection_status);
    uiBatteryLevel = (TextView) findViewById(R.id.configuration_battery_level);
    
//    initActivityContentLayout();
    
    // SETUP DIALOG
    setupConnectionErrorDialog();
  }
  
  
  
  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    Log.i(TAG, "onRestoreInstanceState");
    if (isServiceRunning()) {
      /*chronometer.setBase(savedInstanceState.getLong(KEY_CHRONOMETER_BASE));
      chronometer.start();
      uiMainbutton.setText(getString(R.string.nr_button_stop));*/
    }
    super.onRestoreInstanceState(savedInstanceState);
  }

  @Override
  protected void onResume() {
    // If service is running re-bind to it to send recording duration
    if (isServiceRunning()) {
      bindToService();
    }
    super.onResume();
    Log.i(TAG, "onResume()");
    
  }

  /**
   * Creates and shows a bluetooth error dialog if mac address is other than
   * 'test' and the bluetooth adapter is turned off. On positive click it
   * sends the user to android' settings for the user to turn bluetooth on
   * easily
   */
  private void showBluetoothDialog() {
    // Initializes custom title
    TextView customTitleView = (TextView) inflater.inflate(R.layout.dialog_custom_title, null);
    customTitleView.setText(R.string.nr_bluetooth_dialog_title);
    customTitleView.setBackgroundColor(getResources().getColor(R.color.error_dialog));
    
    // dialogs builder
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setCustomTitle(customTitleView)
        .setMessage(getResources().getString(R.string.nr_bluetooth_dialog_message))
        .setPositiveButton(getString(R.string.nr_bluetooth_dialog_positive_button),
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            Intent intentBluetooth = new Intent();
            intentBluetooth.setAction(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            classContext.startActivity(intentBluetooth);
          }
        });
    builder.setNegativeButton(
        getString(R.string.nc_dialog_negative_button),
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            // dialog gets closed
          }
        });

    // creates and shows bluetooth dialog
    (builder.create()).show();
  }
  
  /**
   * Sets up a connection error dialog with custom title. This is used to add
   * custom message '.setMessage()' and display different possible connection
   * errors it '.show()'
   * 
   */
  private void setupConnectionErrorDialog() {
    
    // Initializes custom title
    TextView customTitleView = (TextView) inflater.inflate(R.layout.dialog_custom_title, null);
    customTitleView.setText(R.string.nr_bluetooth_dialog_title);
    customTitleView.setBackgroundColor(getResources().getColor(R.color.error_dialog));
    
    // builder
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setCustomTitle(customTitleView)
         .setPositiveButton(
        getString(R.string.bp_positive_button),
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            savingDialog.dismiss();
            closeRecordingActivity = true;
            
          }
        });
    connectionErrorDialog = builder.create();
    connectionErrorDialog.setCancelable(false);
    connectionErrorDialog.setCanceledOnTouchOutside(false);
    
  }

  

  /**
   * If recording is running, shows save and quit confirmation dialog. If
   * service is stopped. destroys activity
   */
  @Override
  public void onBackPressed() {
//    if (isServiceRunning())
//      showBackDialog();
//    else {
      super.onBackPressed();
      overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
//    }
  }

    
  /**
   * Starts the recording if mac address is 'test' and recording is not
   * running OR if bluetooth is supported by the device, bluetooth is enabled,
   * mac is other than 'test' and recording is not running. Returns always
   * false for the main thread to be stopped and thus be available for the
   * progress dialog  spinning circle when we test the connection
   */
  private boolean startRecording() {
    
    BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    final ProgressDialog progress;
    if(recordingConfiguration.getMacAddress().compareTo("test")!= 0){ // 'test' is used to launch device emulator
      if (mBluetoothAdapter == null) {
        displayInfoToast(getString(R.string.nr_bluetooth_not_supported));
        return false;
      }
      if (!mBluetoothAdapter.isEnabled()){
        showBluetoothDialog();
        return false;
      }
      
    }
    //Toast.makeText(getApplicationContext(), "Recording",Toast.LENGTH_LONG).show();
    
    progress = ProgressDialog.show(this,getResources().getString(R.string.nr_progress_dialog_title),getResources().getString(R.string.nr_progress_dialog_message), true);
    
    Thread connectionThread = new Thread(new Runnable() {
          
      @Override
      public void run() {
        
        
        //Revisar   BitalinoAndroidDevice connectionTest = new BitalinoAndroidDevice(recordingConfiguration.getMacAddress()); 
          //Revisar
          //Revisar         connectionTest.Close();
        
        
        runOnUiThread(new Runnable(){
            public void run(){
              progress.dismiss();
            if(connectionError){
              displayConnectionErrorDialog(bpErrorCode);
            }else{
              Intent intent = new Intent(classContext, BiopluxService.class);
              intent.putExtra(KEY_RECORDING_NAME, recording.getName());
              intent.putExtra(KEY_CONFIGURATION, recordingConfiguration);
              startService(intent);
              bindToService();
              /*startChronometer();
              uiMainbutton.setText(getString(R.string.nr_button_stop));*/
              displayInfoToast(getString(R.string.nr_info_started));
              drawState = false;
              if (btConnectError == true) Toast.makeText(classContext, "Bluetooth Connection Error", Toast.LENGTH_LONG).show();
            }
            }
        });
      }
    });
    
    if(recordingConfiguration.getMacAddress().compareTo("test")==0 && !isServiceRunning() && !recordingOverride)
      {
        
        connectionThread.start();
      }
    else if(mBluetoothAdapter.isEnabled() && !isServiceRunning() && !recordingOverride) {
      connectionThread.start();
    }
    
    return false;
  }

  
  /**
   * Displays an error dialog with corresponding message based on the
   * errorCode it receives. If code is unknown it displays FATAL ERROR message
   */
  private void displayConnectionErrorDialog(int errorCode) {
    // Initializes custom title
    TextView customTitleView = (TextView) inflater.inflate(R.layout.dialog_custom_title, null);
    customTitleView.setBackgroundColor(getResources().getColor(R.color.error_dialog));
    
  }

  /**
   * Displays a custom view information toast with the message it receives as
   * parameter
   */
  private void displayInfoToast(String messageToDisplay) {
    Toast infoToast = new Toast(classContext);
    View toastView = inflater.inflate(R.layout.toast_info, null);
    infoToast.setView(toastView);
    ((TextView) toastView.findViewById(R.id.display_text)).setText(messageToDisplay);
    infoToast.show();
  }
  
  

  /**
   * Gets all the processes that are running on the OS and checks whether the
   * bioplux service is running. Returns true if it is running and false
   * otherwise
   */
  private boolean isServiceRunning() {
    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
      if (BiopluxService.class.getName().equals(service.service.getClassName()) && service.restarting == 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Attaches connection with the service and passes the recording name and
   * the correspondent configuration to it on its intent
   */
  void bindToService() {
    Intent intent = new Intent(classContext, BiopluxService.class);
    bindService(intent, bindConnection, 0);
  }

  /**
   * Detach our existing connection with the service
   */
  void unbindFromService() {
    if (isServiceBounded) {
      unbindService(bindConnection);
      isServiceBounded = false;
    }
  }
  
  
  
    
  @Override
  public boolean onMenuItemClick(MenuItem item) {
     
              return super.onOptionsItemSelected(item);
//      }
  }

  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
      String key) {
    if(key.compareTo(SettingsActivity.KEY_PREF_ZOOM_VALUE) == 0)
      currentZoomValue = Integer.valueOf(sharedPref.getString(SettingsActivity.KEY_PREF_ZOOM_VALUE, "150"));
  }

  
  @Override
  protected void onPause() {
    try {
      unbindFromService();
    } catch (Throwable t) {
      Log.e(TAG, "failed to unbind from service when activity is destroyed", t);
      displayConnectionErrorDialog(10); // 10 -> fatal error
    }
    super.onPause();
    Log.i(TAG, "onPause()");
  }
  

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    Log.i(TAG, "onSavedInstance");
    outState.putLong(KEY_CHRONOMETER_BASE, chronometer.getBase());
    super.onSaveInstanceState(outState);
  }

  /**
   * Destroys activity
   */
  @Override
  protected void onDestroy() {
    super.onDestroy();
    Log.i(TAG, "onDestroy()");
  }
}
