package com.uw.eecse.seattlesnakers.controller.app;

import android.app.Fragment;
import android.app.FragmentManager;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageButton;

import com.uw.eecse.seattlesnakers.controller.R;
import com.uw.eecse.seattlesnakers.controller.app.settings.PreferencesFragment;
import com.uw.eecse.seattlesnakers.controller.ble.BleManager;
import com.uw.eecse.seattlesnakers.controller.ble.BleUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class PadActivity extends UartInterfaceActivity {
    // Log
    private final static String TAG = PadActivity.class.getSimpleName();
    private final static boolean kIsImmersiveModeEnabled = false;

    // Constants
    private final static float kMinAspectRatio = 1.8f;

    // UI TextBuffer (refreshing the text buffer is managed with a timer because a lot of changes can arrive really fast and could stall the main thread)
    private Handler mUIRefreshTimerHandler = new Handler();
    private Runnable mUIRefreshTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isUITimerRunning) {
                updateTextDataUI();
                // Log.d(TAG, "updateDataUI");
                mUIRefreshTimerHandler.postDelayed(this, 200);
            }
        }
    };
    private boolean isUITimerRunning = false;

    // Data
    private ViewGroup mContentView;
    // private EditText mBufferTextView;
    private volatile SpannableStringBuilder mTextSpanBuffer;
    private volatile ArrayList<UartDataChunk> mDataBuffer;
    private DataFragment mRetainedDataFragment;
    private int maxPacketsToPaintAsText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pad);

        mBleManager = BleManager.getInstance(this);
        restoreRetainedDataFragment();

        // UI
        mContentView = (ViewGroup) findViewById(R.id.contentView);
        /* mBufferTextView = (EditText) findViewById(R.id.bufferTextView);
        if (mBufferTextView != null) {
            mBufferTextView.setKeyListener(null);     // make it not editable
        } */

        ImageButton upButton1 = (ImageButton) findViewById(R.id.upButton1);
        upButton1.setOnTouchListener(mPadButtonTouchListener);
        ImageButton leftButton1 = (ImageButton) findViewById(R.id.leftButton1);
        leftButton1.setOnTouchListener(mPadButtonTouchListener);
        ImageButton rightButton1 = (ImageButton) findViewById(R.id.rightButton1);
        rightButton1.setOnTouchListener(mPadButtonTouchListener);
        ImageButton downButton1 = (ImageButton) findViewById(R.id.downButton1);
        downButton1.setOnTouchListener(mPadButtonTouchListener);

        ImageButton upButton2 = (ImageButton) findViewById(R.id.upButton2);
        upButton2.setOnTouchListener(mPadButtonTouchListener);
        ImageButton leftButton2 = (ImageButton) findViewById(R.id.leftButton2);
        leftButton2.setOnTouchListener(mPadButtonTouchListener);
        ImageButton rightButton2 = (ImageButton) findViewById(R.id.rightButton2);
        rightButton2.setOnTouchListener(mPadButtonTouchListener);
        ImageButton downButton2 = (ImageButton) findViewById(R.id.downButton2);
        downButton2.setOnTouchListener(mPadButtonTouchListener);

        ImageButton startButton = (ImageButton) findViewById(R.id.startButton);
        startButton.setOnTouchListener(mPadButtonTouchListener);
        ImageButton resetButton = (ImageButton) findViewById(R.id.resetButton);
        resetButton.setOnTouchListener(mPadButtonTouchListener);


        // Read shared preferences
        maxPacketsToPaintAsText = PreferencesFragment.getUartTextMaxPackets(this);
        //Log.d(TAG, "maxPacketsToPaintAsText: "+maxPacketsToPaintAsText);

        // Start services
        onServicesDiscovered();
    }


    @Override
    protected void onResume() {
        super.onResume();

        if (!kIsImmersiveModeEnabled) {
            final ViewGroup rootLayout = (ViewGroup) findViewById(R.id.rootLayout);
            ViewTreeObserver observer = rootLayout.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    adjustAspectRatio();

                    rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            });
        }

        // Setup listeners
        mBleManager.setBleListener(this);

        // Refresh timer
        isUITimerRunning = true;
        mUIRefreshTimerHandler.postDelayed(mUIRefreshTimerRunnable, 0);

    }

    @Override
    protected void onPause() {
        super.onPause();

        isUITimerRunning = false;
        mUIRefreshTimerHandler.removeCallbacksAndMessages(mUIRefreshTimerRunnable);
    }

    @Override
    protected void onDestroy() {
        // Retain data
        saveRetainedDataFragment();

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_pad, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (kIsImmersiveModeEnabled) {
            // Set full screen mode
            if (hasFocus) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                adjustAspectRatio();
            }
        }
    }

    // region UI
    View.OnTouchListener mPadButtonTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            final int tag = Integer.valueOf((String) view.getTag());
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                sendTouchEvent(tag, true);
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                view.setPressed(false);
                sendTouchEvent(tag, false);
                return true;
            }
            return false;
        }
    };

    private void sendTouchEvent(int tag, boolean pressed) {
        String data = "!B" + tag + (pressed ? "1" : "0");
        ByteBuffer buffer = ByteBuffer.allocate(data.length()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put(data.getBytes());
        sendDataWithCRC(buffer.array());
    }

    private void adjustAspectRatio() {
        ViewGroup rootLayout = mContentView;//(ViewGroup) findViewById(R.id.rootLayout);
        int mainWidth = rootLayout.getWidth();

        if (mainWidth > 0) {
            View player1Name = findViewById(R.id.player1Name);
            View player2Name = findViewById(R.id.player2Name);
            int mainHeight = rootLayout.getHeight() - player1Name.getLayoutParams().height - player2Name.getLayoutParams().height;
            if (mainHeight > 0) {
                // Add black bars if aspect ratio is below min
                float aspectRatio = mainWidth / (float) mainHeight;
                if (aspectRatio < kMinAspectRatio) {

                    if (kIsImmersiveModeEnabled) {
                        final int spacerHeight = Math.round(mainHeight * (kMinAspectRatio - aspectRatio));
                        player1Name.getLayoutParams().height = spacerHeight / 2;
                        player2Name.getLayoutParams().height = spacerHeight / 2;
                    } else {
                        final int spacerHeight = Math.round(mainHeight - mainWidth / kMinAspectRatio);
                        ViewGroup.LayoutParams topLayoutParams = player1Name.getLayoutParams();
                        topLayoutParams.height = spacerHeight / 2;
                        player1Name.setLayoutParams(topLayoutParams);

                        ViewGroup.LayoutParams bottomLayoutParams = player2Name.getLayoutParams();
                        bottomLayoutParams.height = spacerHeight / 2;
                        player2Name.setLayoutParams(bottomLayoutParams);
                    }
                }
            }
        }
    }

    /*
    public void onClickExit(View view) {
        finish();
    }*/

    private int mDataBufferLastSize = 0;
    private boolean mLastPacketEndsWithNewLine = false;

    private void updateTextDataUI() {

        if (mDataBufferLastSize != mDataBuffer.size()) {

            final int bufferSize = mDataBuffer.size();
            if (bufferSize > maxPacketsToPaintAsText) {
                mDataBufferLastSize = bufferSize - maxPacketsToPaintAsText;
                mTextSpanBuffer.clear();
                mTextSpanBuffer.append(getString(R.string.uart_text_dataomitted) + "\n");
            }

            // Add the a newline if was omitted last time
            if (mLastPacketEndsWithNewLine) {
                mTextSpanBuffer.append("\n");
                mLastPacketEndsWithNewLine = false;
            }

            // Log.d(TAG, "update packets: "+(bufferSize-mDataBufferLastSize));
            for (int i = mDataBufferLastSize; i < bufferSize; i++) {
                final UartDataChunk dataChunk = mDataBuffer.get(i);
                final byte[] bytes = dataChunk.getData();
                String formattedData = BleUtils.bytesToText(bytes, true);

                if (i == bufferSize - 1) {      // last packet
                    // Remove the last character if is a newline character
                    final int endIndex = formattedData.length() - 1;
                    final char lastCharacter = formattedData.charAt(endIndex);
                    mLastPacketEndsWithNewLine = lastCharacter == '\n' || lastCharacter == '\r'; //|| lastCharacter == '\r\n';
                    formattedData = mLastPacketEndsWithNewLine ? formattedData.substring(0, endIndex) : formattedData;
                }

                //
                mTextSpanBuffer.append(formattedData);
            }

            mDataBufferLastSize = mDataBuffer.size();
            // mBufferTextView.setText(mTextSpanBuffer);
            // mBufferTextView.setSelection(0, mTextSpanBuffer.length());        // to automatically scroll to the end
        }
    }


    // endregion

    // region Uart

    @Override
    public void onServicesDiscovered() {
        super.onServicesDiscovered();
        enableRxNotifications();
    }

    @Override
    public void onDisconnected() {
        super.onDisconnected();
        Log.d(TAG, "Disconnected. Back to previous activity");
        setResult(-1);      // Unexpected Disconnect
        finish();
    }

    @Override
    public synchronized void onDataAvailable(BluetoothGattCharacteristic characteristic) {
        super.onDataAvailable(characteristic);
        // UART RX
        if (characteristic.getService().getUuid().toString().equalsIgnoreCase(UUID_SERVICE)) {
            if (characteristic.getUuid().toString().equalsIgnoreCase(UUID_RX)) {
                final byte[] bytes = characteristic.getValue();

                final UartDataChunk dataChunk = new UartDataChunk(System.currentTimeMillis(), UartDataChunk.TRANSFERMODE_RX, bytes);
                mDataBuffer.add(dataChunk);
            }
        }
    }

    // endregion

    // region DataFragment
    public static class DataFragment extends Fragment {
        private SpannableStringBuilder mTextSpanBuffer;
        private ArrayList<UartDataChunk> mDataBuffer;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
        }
    }

    private void restoreRetainedDataFragment() {
        // find the retained fragment
        FragmentManager fm = getFragmentManager();
        mRetainedDataFragment = (DataFragment) fm.findFragmentByTag(TAG);

        if (mRetainedDataFragment == null) {
            // Create
            mRetainedDataFragment = new DataFragment();
            fm.beginTransaction().add(mRetainedDataFragment, TAG).commit();

            mDataBuffer = new ArrayList<>();
            mTextSpanBuffer = new SpannableStringBuilder();
        } else {
            // Restore status
            mTextSpanBuffer = mRetainedDataFragment.mTextSpanBuffer;
            mDataBuffer = mRetainedDataFragment.mDataBuffer;
        }
    }

    private void saveRetainedDataFragment() {
        mRetainedDataFragment.mTextSpanBuffer = mTextSpanBuffer;
        mRetainedDataFragment.mDataBuffer = mDataBuffer;
    }
    // endregion
}
