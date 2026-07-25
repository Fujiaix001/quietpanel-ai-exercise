package com.quietpanel.client;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class TransportServer {
    public static final int MODE_AUTO = 0;
    public static final int MODE_WIFI = 1;
    public static final int MODE_BT = 2;

    private static final int PORT = 27183;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onConnectionChanged(boolean connected, String detail);
        void onStateReceived(JSONObject system, JSONArray disks);
        void onActionResult(long id, boolean ok, String message);
        void onDisplayStateChanged(boolean displayOn);
        void onPageConfigReceived(JSONArray enabledPages);
    }

    private final Listener listener;
    private final AtomicLong nextActionId = new AtomicLong(1);
    private volatile boolean running;
    private volatile int connectionMode = MODE_AUTO;

    private Thread wifiThread;
    private Thread btThread;

    private ServerSocket serverSocket;
    private BluetoothServerSocket btServerSocket;

    private Closeable activeClientSocket;
    private BufferedWriter writer;

    public TransportServer(Listener listener) {
        this.listener = listener;
    }

    public synchronized void setMode(int mode) {
        this.connectionMode = mode;
        if (running) {
            stop();
            start();
        }
    }

    public int getMode() {
        return connectionMode;
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        if (connectionMode == MODE_AUTO || connectionMode == MODE_WIFI) {
            wifiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWifiServer();
                }
            }, "quietpanel-wifi");
            wifiThread.start();
        }

        if (connectionMode == MODE_AUTO || connectionMode == MODE_BT) {
            btThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runBluetoothServer();
                }
            }, "quietpanel-bt");
            btThread.start();
        }
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        closeQuietly(btServerSocket);
        btServerSocket = null;
        closeQuietly(activeClientSocket);
        activeClientSocket = null;
        writer = null;
    }

    public long sendAction(String action) {
        long id = nextActionId.getAndIncrement();
        try {
            JSONObject message = new JSONObject();
            message.put("v", 1);
            message.put("type", "action");
            message.put("id", id);
            message.put("action", action);
            if (!writeMessage(message)) {
                listener.onActionResult(id, false, "未連線至電腦");
            }
        } catch (Exception error) {
            listener.onActionResult(id, false, safeMessage(error));
        }
        return id;
    }

    private void runWifiServer() {
        while (running) {
            try {
                synchronized (this) {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(PORT));
                }
                notifyConnection(false, "等待 Wi-Fi/IP 連線 (Port " + PORT + ")…");

                while (running) {
                    Socket socket = serverSocket.accept();
                    if (!running) {
                        closeQuietly(socket);
                        break;
                    }

                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(0);

                    synchronized (this) {
                        if (activeClientSocket != null) {
                            closeQuietly(socket);
                            continue;
                        }
                        activeClientSocket = socket;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    BufferedWriter bw = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                    synchronized (this) {
                        writer = bw;
                    }

                    handleStreamSession(reader, socket, "Wi-Fi 連線");
                }
            } catch (Exception error) {
                if (running) {
                    notifyConnection(false, "Wi-Fi 服務異常：" + safeMessage(error));
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                }
            } finally {
                synchronized (this) {
                    closeQuietly(serverSocket);
                    serverSocket = null;
                }
            }
        }
    }

    private void runBluetoothServer() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            if (connectionMode == MODE_BT) {
                notifyConnection(false, "裝置不支援藍牙");
            }
            return;
        }

        while (running) {
            try {
                if (!adapter.isEnabled()) {
                    if (connectionMode == MODE_BT) {
                        notifyConnection(false, "請先開啟裝置藍牙");
                    }
                    Thread.sleep(3000);
                    continue;
                }

                synchronized (this) {
                    btServerSocket = adapter.listenUsingRfcommWithServiceRecord("QuietPanel", SPP_UUID);
                }
                notifyConnection(false, "等待藍牙連線 (SPP)…");

                while (running) {
                    BluetoothSocket btSocket = btServerSocket.accept();
                    if (!running) {
                        closeQuietly(btSocket);
                        break;
                    }

                    synchronized (this) {
                        if (activeClientSocket != null) {
                            closeQuietly(btSocket);
                            continue;
                        }
                        activeClientSocket = btSocket;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(btSocket.getInputStream(), "UTF-8"));
                    BufferedWriter bw = new BufferedWriter(
                            new OutputStreamWriter(btSocket.getOutputStream(), "UTF-8"));
                    synchronized (this) {
                        writer = bw;
                    }

                    handleStreamSession(reader, btSocket, "藍牙連線");
                }
            } catch (Exception error) {
                if (running) {
                    notifyConnection(false, "藍牙服務等待中");
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                }
            } finally {
                synchronized (this) {
                    closeQuietly(btServerSocket);
                    btServerSocket = null;
                }
            }
        }
    }

    private void handleStreamSession(BufferedReader reader, Closeable clientSocket, String transportName) {
        notifyConnection(true, transportName + " 已建立");
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (Exception error) {
            if (running) {
                notifyConnection(false, transportName + " 中斷：" + safeMessage(error));
            }
        } finally {
            synchronized (this) {
                if (activeClientSocket == clientSocket) {
                    writer = null;
                    activeClientSocket = null;
                }
            }
            closeQuietly(clientSocket);
            if (running) {
                notifyConnection(false, "等待電腦連線中");
            }
        }
    }

    private void handleMessage(String line) {
        try {
            JSONObject message = new JSONObject(line);
            if (message.optInt("v", 0) != 1) {
                return;
            }

            String type = message.optString("type", "");
            if ("hello".equals(type)) {
                String version = message.optString("version", "?");
                notifyConnection(true, "Rust Bridge " + version + " 已連線");
                JSONObject acknowledgement = new JSONObject();
                acknowledgement.put("v", 1);
                acknowledgement.put("type", "hello_ack");
                acknowledgement.put("version", "8.1.1");
                writeMessage(acknowledgement);
            } else if ("display_state".equals(type)) {
                listener.onDisplayStateChanged(message.optBoolean("on", true));
            } else if ("page_config".equals(type)) {
                listener.onPageConfigReceived(message.optJSONArray("enabled"));
            } else if ("state".equals(type)) {
                listener.onStateReceived(
                        message.optJSONObject("system"),
                        message.optJSONArray("disks"));
            } else if ("action_result".equals(type)) {
                listener.onActionResult(
                        message.optLong("id", -1),
                        message.optBoolean("ok", false),
                        message.optString("message", ""));
            } else if ("ping".equals(type)) {
                JSONObject pong = new JSONObject();
                pong.put("v", 1);
                pong.put("type", "pong");
                writeMessage(pong);
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized boolean writeMessage(JSONObject message) {
        if (!running || writer == null || activeClientSocket == null) {
            return false;
        }

        try {
            writer.write(message.toString());
            writer.newLine();
            writer.flush();
            return true;
        } catch (Exception error) {
            closeQuietly(activeClientSocket);
            writer = null;
            activeClientSocket = null;
            return false;
        }
    }

    private void notifyConnection(boolean connected, String detail) {
        if (listener != null) {
            listener.onConnectionChanged(connected, detail);
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
