package com.quietpanel.client;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import java.lang.reflect.Method;

/** Enables the Android 4.x Bluetooth PAN/NAP service used by Windows. */
final class BluetoothPanController {
    private static final String TAG = "QuietPanelPan";
    private static final int PROFILE_PAN = 5;
    private static final int RETRY_DELAY_MS = 300;
    private static final int MAX_ATTEMPTS = 20;
    private static final String ACTION_PAN_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED";
    private static final ComponentName MTK_PAN_SERVICE = new ComponentName(
            "com.mediatek.bluetooth",
            "com.mediatek.bluetooth.pan.BluetoothPanService");
    private static final String MTK_PAN_ACTION_DESCRIPTOR =
            "com.mediatek.bluetooth.pan.IBluetoothPanAction";
    // MTK's private AIDL orders disconnectPanDeviceAction first and
    // authorizeRspAction second.  This was verified against the target ROM's
    // live Binder endpoint: transaction 1 disconnects, transaction 2 accepts.
    private static final int TRANSACTION_AUTHORIZE_RSP =
            IBinder.FIRST_CALL_TRANSACTION + 1;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothProfile panProxy;
    private int generation;
    private boolean receiverRegistered;
    private boolean mtkActionBinding;
    private String pendingMtkAddress;

    private final BroadcastReceiver panStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (!ACTION_PAN_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (state == BluetoothProfile.STATE_CONNECTING && isPairedComputer(device)) {
                authorizeMtkPan(device.getAddress());
            }
        }
    };

    private final ServiceConnection mtkActionConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final String address;
            synchronized (BluetoothPanController.this) {
                address = pendingMtkAddress;
            }
            if (address != null) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(MTK_PAN_ACTION_DESCRIPTOR);
                    data.writeString(address);
                    data.writeInt(1);
                    if (service.transact(TRANSACTION_AUTHORIZE_RSP, data, reply, 0)) {
                        Log.i(TAG, "Auto-authorized paired computer for MTK PAN: " + address);
                    } else {
                        Log.w(TAG, "MTK PAN authorization transaction was rejected");
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Unable to send MTK PAN authorization", error);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    unbindMtkAction();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (BluetoothPanController.this) {
                mtkActionBinding = false;
                pendingMtkAddress = null;
            }
        }
    };

    BluetoothPanController(Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressLint("MissingPermission")
    synchronized void requestTetheringEnabled() {
        ensureReceiverRegistered();
        closeLocked();
        final int requestGeneration = generation;
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter is unavailable or disabled");
            return;
        }

        try {
            boolean requested = adapter.getProfileProxy(context,
                    new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    synchronized (BluetoothPanController.this) {
                        if (profile != PROFILE_PAN || requestGeneration != generation) {
                            closeProxy(proxy);
                            return;
                        }
                        panProxy = proxy;
                    }
                    // The target MTK Android 4.2 framework invokes this callback
                    // before its internal IBluetoothPan binding is ready. Keep the
                    // proxy alive and retry instead of closing it after one call.
                    scheduleAttempt(requestGeneration, 0);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    synchronized (BluetoothPanController.this) {
                        if (requestGeneration == generation) {
                            panProxy = null;
                        }
                    }
                }
            }, PROFILE_PAN);
            if (!requested) {
                Log.w(TAG, "Bluetooth PAN profile proxy request was rejected");
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to request Bluetooth PAN profile", error);
            // Some vendor Android 4.x builds expose the UI setting but not the
            // profile proxy. In that case the existing system setting remains.
        }
    }

    synchronized void close() {
        closeLocked();
        unbindMtkAction();
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(panStateReceiver);
            } catch (Exception error) {
                Log.w(TAG, "Unable to unregister PAN state receiver", error);
            }
            receiverRegistered = false;
        }
    }

    private void scheduleAttempt(final int requestGeneration, final int attempt) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                enableTethering(requestGeneration, attempt);
            }
        }, RETRY_DELAY_MS);
    }

    private void enableTethering(int requestGeneration, int attempt) {
        final BluetoothProfile proxy;
        synchronized (this) {
            if (requestGeneration != generation || panProxy == null) {
                return;
            }
            proxy = panProxy;
        }

        try {
            Method isEnabled = proxy.getClass().getMethod("isTetheringOn");
            if (Boolean.TRUE.equals(isEnabled.invoke(proxy))) {
                Log.i(TAG, "Bluetooth PAN tethering is enabled");
                finish(requestGeneration);
                return;
            }

            Method enable = proxy.getClass().getMethod(
                    "setBluetoothTethering", boolean.class);
            enable.invoke(proxy, true);
            if (Boolean.TRUE.equals(isEnabled.invoke(proxy))) {
                Log.i(TAG, "Bluetooth PAN tethering enabled on attempt " + (attempt + 1));
                finish(requestGeneration);
                return;
            }
        } catch (Exception error) {
            if (attempt == 0) {
                Log.w(TAG, "Bluetooth PAN service is not ready; retrying", error);
            }
        }

        if (attempt + 1 < MAX_ATTEMPTS) {
            scheduleAttempt(requestGeneration, attempt + 1);
        } else {
            Log.e(TAG, "Bluetooth PAN tethering did not become ready");
            finish(requestGeneration);
        }
    }

    private synchronized void finish(int requestGeneration) {
        // Keep the PAN proxy and receiver alive. MTK Android 4.2 sends an
        // incoming-authorization event after tethering is enabled; closing the
        // proxy here makes it impossible to handle that event unattended.
    }

    private synchronized void ensureReceiverRegistered() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_PAN_CONNECTION_STATE_CHANGED);
        context.registerReceiver(panStateReceiver, filter);
        receiverRegistered = true;
    }

    @SuppressLint("MissingPermission")
    private static boolean isPairedComputer(BluetoothDevice device) {
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) {
            return false;
        }
        BluetoothClass deviceClass = device.getBluetoothClass();
        return deviceClass != null
                && deviceClass.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER;
    }

    private synchronized void authorizeMtkPan(String address) {
        if (mtkActionBinding || address == null || address.length() == 0) {
            return;
        }
        pendingMtkAddress = address;
        Intent intent = new Intent();
        intent.setComponent(MTK_PAN_SERVICE);
        try {
            mtkActionBinding = context.bindService(
                    intent, mtkActionConnection, Context.BIND_AUTO_CREATE);
            if (!mtkActionBinding) {
                pendingMtkAddress = null;
                Log.w(TAG, "MTK PAN authorization service is unavailable");
            }
        } catch (Exception error) {
            mtkActionBinding = false;
            pendingMtkAddress = null;
            Log.w(TAG, "Unable to bind MTK PAN authorization service", error);
        }
    }

    private synchronized void unbindMtkAction() {
        if (!mtkActionBinding) {
            return;
        }
        try {
            context.unbindService(mtkActionConnection);
        } catch (Exception error) {
            Log.w(TAG, "Unable to unbind MTK PAN authorization service", error);
        } finally {
            mtkActionBinding = false;
            pendingMtkAddress = null;
        }
    }

    private void closeLocked() {
        generation++;
        BluetoothProfile proxy = panProxy;
        panProxy = null;
        if (proxy != null) {
            closeProxy(proxy);
        }
    }

    @SuppressLint("MissingPermission")
    private void closeProxy(BluetoothProfile proxy) {
        try {
            BluetoothAdapter currentAdapter = adapter;
            if (currentAdapter != null) {
                currentAdapter.closeProfileProxy(PROFILE_PAN, proxy);
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to close Bluetooth PAN profile proxy", error);
        }
    }
}
