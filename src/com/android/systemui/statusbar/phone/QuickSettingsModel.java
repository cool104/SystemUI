/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.telephony.Phone;
import com.android.systemui.R;
import com.android.systemui.settings.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

import java.util.List;
import java.util.Set;

class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkSignalChangedCallback,
        BatteryStateChangeCallback,
        BrightnessStateChangeCallback,
        RotationLockControllerCallback,
        LocationSettingsChangeCallback {
    // Sett InputMethoManagerService
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled = false;
    }
    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }
    static class ActivityState extends State {
        boolean activityIn;
        boolean activityOut;
    }
    static class RSSIState extends ActivityState {
        int signalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
        String networkType;
    }
    static class WifiState extends ActivityState {
        String signalContentDescription;
        boolean connected;
    }
    static class UserState extends State {
        Drawable avatar;
    }
    static class BrightnessState extends State {
        boolean autoBrightness;
    }
    static class ImmersiveState extends State {
        boolean isEnabled;
    }
    static class QuiteHourState extends State {
        boolean isEnabled;
    }
    public static class BluetoothState extends State {
        boolean connected = false;
        String stateContentDescription;
    }
    public static class RotationLockState extends State {
        boolean visible = false;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    public static class BasicRefreshCallback implements RefreshCallback {
        private final QuickSettingsBasicTile mView;
        private boolean mShowWhenEnabled;

        public BasicRefreshCallback(QuickSettingsBasicTile v) {
            mView = v;
        }
        public void refreshView(QuickSettingsTileView ignored, State state) {
            if (mShowWhenEnabled) {
                mView.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
            if (state.iconId != 0) {
                mView.setImageDrawable(null); // needed to flush any cached IDs
                mView.setImageResource(state.iconId);
            }
            if (state.label != null) {
                mView.setText(state.label);
            }
        }
        public BasicRefreshCallback setShowWhenEnabled(boolean swe) {
            mShowWhenEnabled = swe;
            return this;
        }
    }

    /** Broadcast receive to determine if there is an alarm set. */
    private BroadcastReceiver mAlarmIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                onAlarmChanged(intent);
                onNextAlarmChanged();
            }
        }
    };

    /** Broadcast receive to determine usb tether. */
    private BroadcastReceiver mUsbIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            }

            if (intent.getAction().equals(Intent.ACTION_MEDIA_SHARED)) {
                mMassStorageActive = true;
            }

            if (intent.getAction().equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mMassStorageActive = false;
            }

            onUsbChanged();
        }
    };

    /** ContentObserver to determine the next alarm */
    private class NextAlarmObserver extends ContentObserver {
        public NextAlarmObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onNextAlarmChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to watch adb */
    private class BugreportObserver extends ContentObserver {
        public BugreportObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onBugreportChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BUGREPORT_IN_POWER_MENU), false, this);
        }
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {
        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBrightnessLevelChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** ContentObserver to watch immersive **/
    private class ImmersiveObserver extends ContentObserver {
        public ImmersiveObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onImmersiveChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.IMMERSIVE_MODE),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** ContentObserver to watch quitehour **/
    private class QuiteHourObserver extends ContentObserver {
        public QuiteHourObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onQuiteHourChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUIET_HOURS_ENABLED),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** Callback for changes to remote display routes. */
    private class RemoteDisplayRouteCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final CurrentUserTracker mUserTracker;
    private final NextAlarmObserver mNextAlarmObserver;
    private final BugreportObserver mBugreportObserver;
    private final BrightnessObserver mBrightnessObserver;
    private final ImmersiveObserver mImmersiveObserver;
    private final QuiteHourObserver mQuiteHourObserver;

    private ConnectivityManager mCM;
    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private boolean mMassStorageActive = false;
    private String[] mUsbRegexs;

    private final MediaRouter mMediaRouter;
    private final RemoteDisplayRouteCallback mRemoteDisplayRouteCallback;

    private final boolean mHasMobileData;

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private UserState mUserState = new UserState();

    private QuickSettingsTileView mTimeTile;
    private RefreshCallback mTimeCallback;
    private State mTimeState = new State();

    private QuickSettingsTileView mAlarmTile;
    private RefreshCallback mAlarmCallback;
    private State mAlarmState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mUsbModeTile;
    private RefreshCallback mUsbModeCallback;
    private State mUsbModeState = new State();

    private QuickSettingsTileView mWifiTile;
    private QuickSettingsTileView mWifiBackTile;
    private RefreshCallback mWifiCallback;
    private RefreshCallback mWifiBackCallback;
    private WifiState mWifiState = new WifiState();
    private WifiState mWifiBackState = new WifiState();

    private QuickSettingsTileView mRemoteDisplayTile;
    private RefreshCallback mRemoteDisplayCallback;
    private State mRemoteDisplayState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private RSSIState mRSSIState = new RSSIState();

    private QuickSettingsTileView mBluetoothTile;
    private QuickSettingsTileView mBluetoothBackTile;
    private RefreshCallback mBluetoothCallback;
    private RefreshCallback mBluetoothBackCallback;
    private BluetoothState mBluetoothState = new BluetoothState();
    private BluetoothState mBluetoothBackState = new BluetoothState();

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    private QuickSettingsTileView mImeTile;
    private RefreshCallback mImeCallback = null;
    private State mImeState = new State();

    private QuickSettingsTileView mRotationLockTile;
    private RefreshCallback mRotationLockCallback;
    private RotationLockState mRotationLockState = new RotationLockState();

    private QuickSettingsTileView mBrightnessTile;
    private RefreshCallback mBrightnessCallback;
    private BrightnessState mBrightnessState = new BrightnessState();

    private QuickSettingsTileView mImmersiveTile;
    private RefreshCallback mImmersiveCallback;
    private ImmersiveState mImmersiveState = new ImmersiveState();

    private QuickSettingsTileView mQuiteHourTile;
    private RefreshCallback mQuiteHourCallback;
    private QuiteHourState mQuiteHourState = new QuiteHourState();

    private QuickSettingsTileView mBugreportTile;
    private RefreshCallback mBugreportCallback;
    private State mBugreportState = new State();

    private QuickSettingsTileView mSettingsTile;
    private RefreshCallback mSettingsCallback;
    private State mSettingsState = new State();

    private QuickSettingsTileView mSslCaCertWarningTile;
    private RefreshCallback mSslCaCertWarningCallback;
    private State mSslCaCertWarningState = new State();

    private RotationLockController mRotationLockController;

    public QuickSettingsModel(Context context) {
        mContext = context;
        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBrightnessObserver.startObserving();
                mImmersiveObserver.startObserving();
                mQuiteHourObserver.startObserving();
                refreshRotationLockTile();
                onBrightnessLevelChanged();
                onNextAlarmChanged();
                onBugreportChanged();
                rebindMediaRouterAsCurrentUser();
                onUsbChanged();
            }
        };

        mNextAlarmObserver = new NextAlarmObserver(mHandler);
        mNextAlarmObserver.startObserving();
        mBugreportObserver = new BugreportObserver(mHandler);
        mBugreportObserver.startObserving();
        mBrightnessObserver = new BrightnessObserver(mHandler);
        mBrightnessObserver.startObserving();
        mImmersiveObserver = new ImmersiveObserver(mHandler);
        mImmersiveObserver.startObserving();
        mQuiteHourObserver = new QuiteHourObserver(mHandler);
        mQuiteHourObserver.startObserving();

        mMediaRouter = (MediaRouter)context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        rebindMediaRouterAsCurrentUser();

        mRemoteDisplayRouteCallback = new RemoteDisplayRouteCallback();

        mCM = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasMobileData = mCM.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        IntentFilter alarmIntentFilter = new IntentFilter();
        alarmIntentFilter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(mAlarmIntentReceiver, alarmIntentFilter);

        IntentFilter usbIntentFilter = new IntentFilter();
        usbIntentFilter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        usbIntentFilter.addAction(UsbManager.ACTION_USB_STATE);
        usbIntentFilter.addAction(Intent.ACTION_MEDIA_SHARED);
        usbIntentFilter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        context.registerReceiver(mUsbIntentReceiver, usbIntentFilter);
    }

    void updateResources() {
        refreshSettingsTile();
        refreshBatteryTile();
        refreshBluetoothTile();
        refreshBrightnessTile();
        refreshImmersiveTile();
        refreshQuiteHourTile();
        refreshRotationLockTile();
        refreshRssiTile();
        refreshLocationTile();
    }

    // Settings
    void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSettingsTile = view;
        mSettingsCallback = cb;
        refreshSettingsTile();
    }
    void refreshSettingsTile() {
        Resources r = mContext.getResources();
        mSettingsState.label = r.getString(R.string.quick_settings_settings_label);
        mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(mUserTile, mUserState);
    }
    void setUserTileInfo(String name, Drawable avatar) {
        mUserState.label = name;
        mUserState.avatar = avatar;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Time
    void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTimeTile = view;
        mTimeCallback = cb;
        mTimeCallback.refreshView(view, mTimeState);
    }

    // Alarm
    void addAlarmTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAlarmTile = view;
        mAlarmCallback = cb;
        mAlarmCallback.refreshView(view, mAlarmState);
    }
    void onAlarmChanged(Intent intent) {
        mAlarmState.enabled = intent.getBooleanExtra("alarmSet", false);
        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }
    void onNextAlarmChanged() {
        final String alarmText = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED,
                UserHandle.USER_CURRENT);
        mAlarmState.label = alarmText;

        // When switching users, this is the only clue we're going to get about whether the
        // alarm is actually set, since we won't get the ACTION_ALARM_CHANGED broadcast
        mAlarmState.enabled = ! TextUtils.isEmpty(alarmText);

        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    // Usb Mode
    void addUsbModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUsbModeTile = view;
        mUsbModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbConnected) {
                    setUsbTethering(!mUsbTethered);
                }
            }
        });
        mUsbModeCallback = cb;
        onUsbChanged();
    }

    void onUsbChanged() {
        updateState();
        if (mUsbConnected && !mMassStorageActive) {
            if (mUsbTethered) {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_on;
                mUsbModeState.label = mContext.getString(R.string.quick_settings_usb_tether_on_label);
            } else {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_connected;
                mUsbModeState.label = mContext.getString(R.string.quick_settings_usb_tether_connected_label);
            }
            mUsbModeState.enabled = true;
        } else {
            mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_off;
            mUsbModeState.label = mContext.getString(R.string.quick_settings_usb_tether_off_label);
            mUsbModeState.enabled = false;
        }
        mUsbModeCallback.refreshView(mUsbModeTile, mUsbModeState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        onAirplaneModeChanged(airplaneMode != 0);
    }
    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON,
                                enabled ? 1 : 0);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }
    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_on :
                R.drawable.ic_qs_airplane_off);
        mAirplaneModeState.label = r.getString(R.string.quick_settings_airplane_mode_label);
        mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }
    void addWifiBackTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiBackTile = view;
        mWifiBackCallback = cb;
        mWifiCallback.refreshView(mWifiBackTile, mWifiBackState);
    }
    // Remove the double quotes that the SSID may contain
    public static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }
    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }
    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();

        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        mWifiState.enabled = enabled;
        mWifiState.connected = wifiConnected;
        mWifiState.activityIn = enabled && activityIn;
        mWifiState.activityOut = enabled && activityOut;
        if (wifiConnected) {
            mWifiState.iconId = wifiSignalIconId;
            mWifiState.label = removeDoubleQuotes(enabledDesc);
            mWifiState.signalContentDescription = wifiSignalContentDescription;

            mWifiBackState.iconId = wifiSignalIconId;
            mWifiBackState.label = getWifiIpAddr();
            mWifiBackState.signalContentDescription = wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            mWifiState.iconId = R.drawable.ic_qs_wifi_0;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_no_wifi);

            mWifiBackState.iconId = mWifiState.iconId;
            mWifiBackState.label = mWifiState.label;
            mWifiBackState.signalContentDescription = mWifiState.signalContentDescription;
        } else {
            mWifiState.iconId = R.drawable.ic_qs_wifi_no_network;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_off_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_wifi_off);

            mWifiBackState.iconId = mWifiState.iconId;
            mWifiBackState.label = mWifiState.label;
            mWifiBackState.signalContentDescription = mWifiState.signalContentDescription;
        }
        mWifiCallback.refreshView(mWifiTile, mWifiState);
        mWifiBackCallback.refreshView(mWifiBackTile, mWifiBackState);
    }

    String getWifiIpAddr() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();

        String ipString = String.format(
            "%d.%d.%d.%d",
            (ip & 0xff),
            (ip >> 8 & 0xff),
            (ip >> 16 & 0xff),
            (ip >> 24 & 0xff));

        return ipString;
    }

    boolean deviceHasMobileData() {
        return mHasMobileData;
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        mRSSICallback.refreshView(mRSSITile, mRSSIState);
    }
    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (deviceHasMobileData()) {
            // TODO: If view is in awaiting state, disable
            Resources r = mContext.getResources();
            mRSSIState.signalIconId = enabled && (mobileSignalIconId > 0)
                    ? mobileSignalIconId
                    : R.drawable.ic_qs_signal_no_signal;
            mRSSIState.signalContentDescription = enabled && (mobileSignalIconId > 0)
                    ? signalContentDescription
                    : r.getString(R.string.accessibility_no_signal);
            mRSSIState.dataTypeIconId = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataTypeIconId
                    : 0;
            mRSSIState.activityIn = enabled && activityIn;
            mRSSIState.activityOut = enabled && activityOut;
            mRSSIState.dataContentDescription = enabled && (dataTypeIconId > 0) && !mWifiState.enabled
                    ? dataContentDescription
                    : r.getString(R.string.accessibility_no_data);
            mRSSIState.label = enabled
                    ? removeTrailingPeriod(enabledDesc)
                    : r.getString(R.string.quick_settings_rssi_emergency_only);
            mRSSIState.networkType = getNetworkType(r);
            mRSSICallback.refreshView(mRSSITile, mRSSIState);
        }
    }

    private String getNetworkType(Resources r) {
        int state = networkModeToState(get2G3G());
        switch (state) {
            case 1:
                return r.getString(R.string.network_2G);
            case 2:
                return r.getString(R.string.network_3G_only);
            case 3:
                return r.getString(R.string.network_3G_auto);
            case 4:
                return r.getString(R.string.network_3G);
        }
        return "unknown";
    }

    private int get2G3G() {
        int state = 99;
        try {
            state = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE);
        } catch (SettingNotFoundException e) {
            // Do nothing
        }
        return state;
    }

    private int networkModeToState(int what) {
        switch (what) {
            case Phone.NT_MODE_WCDMA_PREF:
                return 4;
            case Phone.NT_MODE_GSM_UMTS:
                return 3;
            case Phone.NT_MODE_WCDMA_ONLY:
                return 2;
            case Phone.NT_MODE_GSM_ONLY:
                return 1;
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_GLOBAL:
                return 0;
        }
        return 0;
    }

    void refreshRssiTile() {
        if (mRSSITile != null) {
            // We reinflate the original view due to potential styling changes that may have
            // taken place due to a configuration change.
            mRSSITile.reinflateContent(LayoutInflater.from(mContext));
        }
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState.enabled = adapter.isEnabled();
        mBluetoothState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothState);
    }
    void addBluetoothBackTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothBackTile = view;
        mBluetoothBackCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothBackState.enabled = adapter.isEnabled();
        mBluetoothBackState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothBackState);
    }
    boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }
    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        mBluetoothState.enabled = on;
        onBluetoothStateChange(mBluetoothState);
    }
    public void onBluetoothStateChange(BluetoothState bluetoothStateIn) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = bluetoothStateIn.enabled;
        mBluetoothState.connected = bluetoothStateIn.connected;
        if (mBluetoothState.enabled) {
            if (mBluetoothState.connected) {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_on;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_connected);
            } else {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_on);
            }
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_label);
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_off;
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_off);
        }

        // Back tile: Show paired devices
        if (mBluetoothBackTile != null) {
            mBluetoothBackState.iconId = mBluetoothState.iconId;

            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> btDevices = adapter.getBondedDevices();
            if (btDevices.size() == 1) {
                // Show a generic label about the number of paired bluetooth devices
                mBluetoothBackState.label = 
                    r.getString(R.string.quick_settings_bluetooth_number_paired, btDevices.size());
            } else {
                mBluetoothBackState.label = r.getString(R.string.quick_settings_bluetooth_disabled);
            }
        }

        mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);

        if (mBluetoothBackTile != null) {
            mBluetoothBackCallback.refreshView(mBluetoothBackTile, mBluetoothBackState);
        }
    }
    void refreshBluetoothTile() {
        if (mBluetoothTile != null) {
            onBluetoothStateChange(mBluetoothState.enabled);
        }
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    void refreshBatteryTile() {
        if (mBatteryCallback == null) {
            return;
        }
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    void refreshLocationTile() {
        if (mLocationTile != null) {
            onLocationSettingsChanged(mLocationState.enabled);
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled) {
        int textResId = locationEnabled ? R.string.quick_settings_location_label
                : R.string.quick_settings_location_off_label;
        String label = mContext.getText(textResId).toString();
        int locationIconId = locationEnabled
                ? R.drawable.ic_qs_location_on : R.drawable.ic_qs_location_off;
        mLocationState.enabled = locationEnabled;
        mLocationState.label = label;
        mLocationState.iconId = locationIconId;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    // Bug report
    void addBugreportTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBugreportTile = view;
        mBugreportCallback = cb;
        onBugreportChanged();
    }
    // SettingsObserver callback
    public void onBugreportChanged() {
        final ContentResolver cr = mContext.getContentResolver();
        boolean enabled = false;
        try {
            enabled = (Settings.Global.getInt(cr, Settings.Global.BUGREPORT_IN_POWER_MENU) != 0);
        } catch (SettingNotFoundException e) {
        }

        mBugreportState.enabled = enabled && mUserTracker.isCurrentUserOwner();
        mBugreportCallback.refreshView(mBugreportTile, mBugreportState);
    }

    // Remote Display
    void addRemoteDisplayTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRemoteDisplayTile = view;
        mRemoteDisplayCallback = cb;
        final int[] count = new int[1];
        mRemoteDisplayTile.setOnPrepareListener(new QuickSettingsTileView.OnPrepareListener() {
            @Override
            public void onPrepare() {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mRemoteDisplayRouteCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                updateRemoteDisplays();
            }
            @Override
            public void onUnprepare() {
                mMediaRouter.removeCallback(mRemoteDisplayRouteCallback);
            }
        });

        updateRemoteDisplays();
    }

    private void rebindMediaRouterAsCurrentUser() {
        mMediaRouter.rebindAsUser(mUserTracker.getCurrentUserId());
    }

    private void updateRemoteDisplays() {
        MediaRouter.RouteInfo connectedRoute = mMediaRouter.getSelectedRoute(
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean enabled = connectedRoute != null
                && connectedRoute.matchesTypes(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean connecting;
        if (enabled) {
            connecting = connectedRoute.isConnecting();
        } else {
            connectedRoute = null;
            connecting = false;
            enabled = mMediaRouter.isRouteAvailable(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
        }

        mRemoteDisplayState.enabled = enabled;
        if (connectedRoute != null) {
            mRemoteDisplayState.label = connectedRoute.getName().toString();
            mRemoteDisplayState.iconId = connecting ?
                    R.drawable.ic_qs_cast_connecting : R.drawable.ic_qs_cast_connected;
        } else {
            mRemoteDisplayState.label = mContext.getString(
                    R.string.quick_settings_remote_display_no_connection_label);
            mRemoteDisplayState.iconId = R.drawable.ic_qs_cast_available;
        }
        mRemoteDisplayCallback.refreshView(mRemoteDisplayTile, mRemoteDisplayState);
    }

    // IME
    void addImeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImeTile = view;
        mImeCallback = cb;
        mImeCallback.refreshView(mImeTile, mImeState);
    }
    /* This implementation is taken from
       InputMethodManagerService.needsToShowImeSwitchOngoingNotification(). */
    private boolean needsToShowImeSwitchOngoingNotification(InputMethodManager imm) {
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi,
                    true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                            || auxSubtype.overridesImplicitlyEnabledSubtype()
                            || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }
    void onImeWindowStatusChanged(boolean visible) {
        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();

        mImeState.enabled = (visible && needsToShowImeSwitchOngoingNotification(imm));
        mImeState.label = getCurrentInputMethodName(mContext, mContext.getContentResolver(),
                imm, imis, mContext.getPackageManager());
        if (mImeCallback != null) {
            mImeCallback.refreshView(mImeTile, mImeState);
        }
    }
    private static String getCurrentInputMethodName(Context context, ContentResolver resolver,
            InputMethodManager imm, List<InputMethodInfo> imis, PackageManager pm) {
        if (resolver == null || imis == null) return null;
        final String currentInputMethodId = Settings.Secure.getString(resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(currentInputMethodId)) return null;
        for (InputMethodInfo imi : imis) {
            if (currentInputMethodId.equals(imi.getId())) {
                final InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                final CharSequence summary = subtype != null
                        ? subtype.getDisplayName(context, imi.getPackageName(),
                                imi.getServiceInfo().applicationInfo)
                        : context.getString(R.string.quick_settings_ime_label);
                return summary.toString();
            }
        }
        return null;
    }

    // Rotation lock
    void addRotationLockTile(QuickSettingsTileView view,
            RotationLockController rotationLockController,
            RefreshCallback cb) {
        mRotationLockTile = view;
        mRotationLockCallback = cb;
        mRotationLockController = rotationLockController;
        onRotationLockChanged();
    }
    void onRotationLockChanged() {
        onRotationLockStateChanged(mRotationLockController.isRotationLocked(),
                mRotationLockController.isRotationLockAffordanceVisible());
    }
    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        mRotationLockState.visible = affordanceVisible;
        mRotationLockState.enabled = rotationLocked;
        mRotationLockState.iconId = rotationLocked
                ? R.drawable.ic_qs_rotation_locked
                : R.drawable.ic_qs_auto_rotate;
        mRotationLockState.label = rotationLocked
                ? mContext.getString(R.string.quick_settings_rotation_locked_label)
                : mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        mRotationLockCallback.refreshView(mRotationLockTile, mRotationLockState);
    }
    void refreshRotationLockTile() {
        if (mRotationLockTile != null) {
            onRotationLockChanged();
        }
    }

    // Brightness
    void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBrightnessTile = view;
        mBrightnessCallback = cb;
        onBrightnessLevelChanged();
    }
    @Override
    public void onBrightnessLevelChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                mUserTracker.getCurrentUserId());
        mBrightnessState.autoBrightness =
                (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mBrightnessState.iconId = mBrightnessState.autoBrightness
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_qs_brightness_auto_off;
        mBrightnessState.label = r.getString(R.string.quick_settings_brightness_label);
        mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
    }
    void refreshBrightnessTile() {
        onBrightnessLevelChanged();
    }

    // Immersive
    void addImmersiveTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImmersiveTile = view;
        mImmersiveCallback = cb;
        onImmersiveChanged();
    }

    private void onImmersiveChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, 0,
                mUserTracker.getCurrentUserId());
        mImmersiveState.isEnabled = (mode == 1);
        mImmersiveState.iconId = mImmersiveState.isEnabled
                ? R.drawable.ic_qs_immersive_on
                : R.drawable.ic_qs_immersive_off;
        mImmersiveState.label = mImmersiveState.isEnabled
                ? r.getString(R.string.quick_settings_immersive_mode_label)
                : r.getString(R.string.quick_settings_immersive_mode_off_label);
        mImmersiveCallback.refreshView(mImmersiveTile, mImmersiveState);
    }
    void refreshImmersiveTile() {
        onImmersiveChanged();
    }

    // Immersive
    void addQuiteHourTile(QuickSettingsTileView view, RefreshCallback cb) {
        mQuiteHourTile = view;
        mQuiteHourCallback = cb;
        onQuiteHourChanged();
    }

    private void onQuiteHourChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.QUIET_HOURS_ENABLED, 0,
                mUserTracker.getCurrentUserId());
        mQuiteHourState.isEnabled = (mode == 1);
        mQuiteHourState.iconId = mQuiteHourState.isEnabled
                ? R.drawable.ic_qs_quiet_hours_on
                : R.drawable.ic_qs_quiet_hours_off;
        mQuiteHourState.label = mQuiteHourState.isEnabled
                ? r.getString(R.string.quick_settings_quiethours_label)
                : r.getString(R.string.quick_settings_quiethours_off_label);
        mQuiteHourCallback.refreshView(mQuiteHourTile, mQuiteHourState);
    }
    void refreshQuiteHourTile() {
        onQuiteHourChanged();
    }

    // SSL CA Cert warning.
    public void addSslCaCertWarningTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSslCaCertWarningTile = view;
        mSslCaCertWarningCallback = cb;
        // Set a sane default while we wait for the AsyncTask to finish (no cert).
        setSslCaCertWarningTileInfo(false, true);
    }
    public void setSslCaCertWarningTileInfo(boolean hasCert, boolean isManaged) {
        Resources r = mContext.getResources();
        mSslCaCertWarningState.enabled = hasCert;
        if (isManaged) {
            mSslCaCertWarningState.iconId = R.drawable.ic_qs_certificate_info;
        } else {
            mSslCaCertWarningState.iconId = android.R.drawable.stat_notify_error;
        }
        mSslCaCertWarningState.label = r.getString(R.string.ssl_ca_cert_warning);
        mSslCaCertWarningCallback.refreshView(mSslCaCertWarningTile, mSslCaCertWarningState);
    }

    private void updateState() {
        mUsbRegexs = mCM.getTetherableUsbRegexs();

        String[] available = mCM.getTetherableIfaces();
        String[] tethered = mCM.getTetheredIfaces();
        String[] errored = mCM.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
    }

    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {

        mUsbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) mUsbTethered = true;
            }
        }

    }

    private void setUsbTethering(boolean enabled) {
        if (mCM.setUsbTethering(enabled) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            return;
        }
    }
}
