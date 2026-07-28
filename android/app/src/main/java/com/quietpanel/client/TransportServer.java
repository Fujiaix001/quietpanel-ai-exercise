package com.quietpanel.client;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/** One protocol server shared by ADB port-forward, Wi-Fi and Bluetooth PAN. */
public final class TransportServer {
    public static final int MODE_AUTO = 0;
    public static final int MODE_WIFI = 1;
    public static final int MODE_BT = 2;
    public static final int MODE_ADB = 3;

    private static final int PORT = 27183;

    public interface Listener {
        void onConnectionChanged(boolean connected, String detail);
        void onStateReceived(JSONObject system, JSONArray disks);
        void onActionResult(long id, boolean ok, String message);
        void onDisplayStateChanged(boolean displayOn);
        void onPageConfigReceived(JSONArray enabledPages);
        void onWeatherReceived(JSONObject weather);
    }

    private final Listener listener;
    private final WifiBeacon beacon = new WifiBeacon();
    private final BluetoothPanController panController;
    private final AtomicLong nextActionId = new AtomicLong(1);
    private volatile boolean running;
    private volatile int connectionMode = MODE_AUTO;

    private Thread serverThread;
    private ServerSocket serverSocket;
    private Socket activeClientSocket;
    private BufferedWriter writer;

    public TransportServer(Context context, Listener listener) {
        this.listener = listener;
        this.panController = new BluetoothPanController(context);
    }

    public synchronized void setMode(int mode) {
        if (mode < MODE_AUTO || mode > MODE_ADB) {
            mode = MODE_AUTO;
        }
        connectionMode = mode;
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
        if (connectionMode != MODE_ADB) {
            beacon.start();
        }
        if (connectionMode == MODE_AUTO || connectionMode == MODE_BT) {
            panController.requestTetheringEnabled();
        }
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runIpServer();
            }
        }, "quietpanel-transport-server");
        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        beacon.stop();
        panController.close();
        closeQuietly(serverSocket);
        serverSocket = null;
        closeQuietly(activeClientSocket);
        activeClientSocket = null;
        writer = null;
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
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

    private void runIpServer() {
        while (running) {
            try {
                synchronized (this) {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    if (connectionMode == MODE_ADB) {
                        serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
                    } else {
                        serverSocket.bind(new InetSocketAddress(PORT));
                    }
                }
                notifyConnection(false, waitingMessage());

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
                    BufferedWriter newWriter = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                    synchronized (this) {
                        writer = newWriter;
                    }

                    String transportName = socket.getInetAddress().isLoopbackAddress()
                            ? "ADB 連線" : "IP 連線";
                    handleStreamSession(reader, socket, transportName);
                }
            } catch (Exception error) {
                if (running) {
                    notifyConnection(false, "通訊服務異常：" + safeMessage(error));
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

    private String waitingMessage() {
        if (connectionMode == MODE_ADB) {
            return "等待 USB ADB 連線…";
        }
        if (connectionMode == MODE_WIFI) {
            return "等待 Wi-Fi 連線 (TCP " + PORT + ")…";
        }
        if (connectionMode == MODE_BT) {
            return "等待藍牙 PAN 連線 (TCP " + PORT + ")…";
        }
        return "等待 ADB / Wi-Fi / 藍牙 PAN…";
    }

    private void handleStreamSession(BufferedReader reader, Socket clientSocket,
                                     String transportName) {
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
                notifyConnection(false, waitingMessage());
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
                acknowledgement.put("version", BuildConfig.VERSION_NAME);
                writeMessage(acknowledgement);
            } else if ("display_state".equals(type)) {
                listener.onDisplayStateChanged(message.optBoolean("on", true));
            } else if ("page_config".equals(type)) {
                listener.onPageConfigReceived(message.optJSONArray("enabled"));
            } else if ("weather_state".equals(type)) {
                listener.onWeatherReceived(message.optJSONObject("weather"));
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

    private static void closeQuietly(Object socket) {
        if (socket == null) {
            return;
        }
        try {
            if (socket instanceof Socket) {
                ((Socket) socket).close();
            } else if (socket instanceof ServerSocket) {
                ((ServerSocket) socket).close();
            }
        } catch (Exception ignored) {
        }
    }
}
